package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Focused regression coverage for retained identity limits, timestamps, archive, delete, and name reuse. */
final class IdentityLifecycleValidator {
    private IdentityLifecycleValidator() { }

    static void validate() throws Exception {
        validateStorePersistenceAndRollback();
        validateFreshServerLimit();
        validateAdministrativeLifecycle();
    }

    private static void validateStorePersistenceAndRollback() throws Exception {
        Path dir = Files.createTempDirectory("starchem-identity-store-");
        try {
            AtomicLong clock = new AtomicLong(1_000L);
            ServerIdentityStore store = new ServerIdentityStore(dir, "identity", clock::get);
            require(store.synchronize(List.of(session("P7", "Seven"))).success(), "identity synchronization failed");
            ServerIdentityStore.IdentityRecord created = store.find("P7");
            require(created != null && created.createdAt() == 1_000L && created.lastSeenAt() == 1_000L,
                    "identity creation timestamps were not recorded");
            clock.set(5_000L);
            require(store.recordSeen("P7", "Seven").success(), "identity last-seen update failed");
            require(store.archive("P7").success() && store.find("P7").archived(), "identity archive failed");
            require(store.nextPlayerNumber() >= 8, "player ID high-water mark was not retained");

            ServerIdentityStore reloaded = new ServerIdentityStore(dir, "identity", clock::get);
            ServerIdentityStore.IdentityRecord restored = reloaded.find("Seven");
            require(restored != null && restored.archived() && restored.lastSeenAt() == 5_000L,
                    "identity lifecycle state did not survive reload");
            require(!reloaded.denialReason("P7").isBlank(), "archived identity was not denied");
            reloaded.failNextSaveForTest();
            ServerIdentityStore.MutationResult failedRestore = reloaded.restore("P7");
            require(!failedRestore.success() && reloaded.find("P7").archived(),
                    "failed lifecycle save did not roll back memory state");
            require(ServerIdentityAdministration.parseAgeMillis("2w") == 1_209_600_000L,
                    "dormant age parsing is incorrect");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateFreshServerLimit() throws Exception {
        Path dir = Files.createTempDirectory("starchem-identity-default-");
        try {
            CompanionRecoveryRegistry.resetForTests();
            ServerAccessPolicy policy = new ServerAdminStore(dir, "fresh").load();
            require(policy.maxSlots() == 128, "fresh dedicated server did not default to 128 retained identities");
        } finally {
            CompanionRecoveryRegistry.resetForTests();
            deleteTree(dir);
        }
    }

    private static void validateAdministrativeLifecycle() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            TcpIntegrationHarness.TestClient original = harness.addClient("Lifecycle Player");
            harness.awaitJoined(original);
            String originalId = original.playerId();
            require(harness.headlessServer != null, "identity lifecycle test requires a headless server");
            require(harness.serverNetwork.serverIdentityStore().find(originalId) != null,
                    "successful registration did not create lifecycle metadata");

            List<String> archive = ServerIdentityAdministration.execute(harness.headlessServer,
                    List.of("archive", originalId, "confirm"));
            require(archive.get(0).contains("Archived"), "archive command did not succeed: " + archive);
            require(harness.serverNetwork.serverIdentityStore().find(originalId).archived(),
                    "archive state was not persisted");
            require(!harness.serverNetwork.serverSessionConnected(originalId),
                    "archiving did not disconnect the active identity");
            original.network().shutdown();

            List<String> restore = ServerIdentityAdministration.execute(harness.headlessServer,
                    List.of("restore", originalId));
            require(restore.get(0).contains("Restored"), "restore command did not succeed: " + restore);
            TcpIntegrationHarness.TestClient reclaimed = harness.addClient("Lifecycle Player");
            harness.awaitJoined(reclaimed);
            require(originalId.equals(reclaimed.playerId()), "restored identity did not reclaim the original player ID");
            reclaimed.network().shutdown();
            harness.await(() -> !harness.serverNetwork.serverSessionConnected(originalId), 5_000,
                    "restored identity did not disconnect before deletion");

            long backupsBefore;
            try (var stream = Files.list(harness.serverSaveDir)) {
                backupsBefore = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".starchem-save")).count();
            }
            List<String> deletion = ServerIdentityAdministration.execute(harness.headlessServer,
                    List.of("delete", originalId, "confirm"));
            require(deletion.get(0).contains("Permanently deleted"), "delete command did not succeed: " + deletion);
            require(harness.serverNetwork.persistentPlayerSessions().stream()
                            .noneMatch(session -> originalId.equals(session.playerId())),
                    "deleted session remained in the retained session roster");
            require(!harness.serverWorld.hasLiveAssets(originalId), "deleted identity retained live assets");
            require(harness.serverNetwork.serverIdentityStore().find(originalId) == null,
                    "deleted identity retained lifecycle metadata");
            long backupsAfter;
            try (var stream = Files.list(harness.serverSaveDir)) {
                backupsAfter = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".starchem-save")).count();
            }
            require(backupsAfter > backupsBefore, "identity deletion did not create a recovery backup");

            Config replacementConfig = Config.join("Lifecycle Player", "127.0.0.1",
                    harness.serverConfig.port, false);
            SessionTokenStore.clear(replacementConfig);
            SessionTokenStore.clearScopedCredential(replacementConfig);
            TcpIntegrationHarness.TestClient replacement = harness.addClient("Lifecycle Player");
            harness.awaitJoined(replacement);
            require(!originalId.equals(replacement.playerId()), "deleted player ID was recycled or stale credentials reclaimed it");
        }
    }

    private static PersistentPlayerSession session(String id, String name) {
        return new PersistentPlayerSession(id, name, 0x50BEFF, new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[0], 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
