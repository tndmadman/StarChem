package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Validates fail-safe administration and moderation companion-file recovery. */
public final class CompanionStateRecoveryValidator {
    private CompanionStateRecoveryValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem companion state recovery validation passed.");
    }

    static void validate() throws Exception {
        Path root = Files.createTempDirectory("starchem-companion-recovery-validator-");
        try {
            validateAdministrationPreviousRecovery(root.resolve("admin-previous"));
            validateModerationPreviousRecovery(root.resolve("moderation-previous"));
            validateInvalidTypesRestrictAdmission(root.resolve("invalid-types"));
            validateUnmanagedHostDoesNotLockAdmission(root.resolve("unmanaged-host"));
            validateTotalLossAndExplicitReset(root.resolve("total-loss"));
            validateUnreadableCurrentRecovery(root.resolve("unreadable"));
            validateAtomicMoveFallback(root.resolve("atomic-fallback"));
            validateFailedMovesPreserveCurrent(root.resolve("move-failure"));
        } finally {
            CompanionRecoveryRegistry.resetForTests();
            deleteTree(root);
        }
    }

    private static void validateAdministrationPreviousRecovery(Path dir) throws Exception {
        Files.createDirectories(dir);
        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore store = new ServerAdminStore(dir, "admin-previous");
        ServerAccessPolicy first = new ServerAccessPolicy(true, "planned maintenance", 7, "first motd");
        ServerAccessPolicy second = new ServerAccessPolicy(false, "", 11, "second motd");
        store.save(first);
        store.save(second);
        require(Files.isRegularFile(store.previousPath()), "administration save did not retain a previous file");

        Files.writeString(store.path(), "{\"version\":1", StandardCharsets.UTF_8);
        ServerAccessPolicy recovered = store.load();
        require(store.loadStatus().recoveredPrevious(), "truncated administration JSON did not recover from previous");
        require(recovered.storedMaintenance() && recovered.maxSlots() == 7
                        && "first motd".equals(recovered.motd()),
                "administration previous recovery returned the wrong policy");
        require(store.loadStatus().summary("Administration").contains("recovered from previous"),
                "administration recovery was not clearly reported");

        ServerAdminStore restartedStore = new ServerAdminStore(dir, "admin-previous");
        ServerAccessPolicy restarted = restartedStore.load();
        require(!restartedStore.loadStatus().recoveredPrevious(),
                "administration current file was not repaired after previous recovery");
        require(restarted.storedMaintenance() && restarted.maxSlots() == 7,
                "repaired administration state changed across restart");
    }

    private static void validateModerationPreviousRecovery(Path dir) throws Exception {
        Files.createDirectories(dir);
        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore admin = new ServerAdminStore(dir, "moderation-previous");
        admin.save(ServerAccessPolicy.open());
        ServerModerationStore store = new ServerModerationStore(dir, "moderation-previous");
        long now = System.currentTimeMillis();
        ServerModerationState first = ServerModerationState.open().add(new ModerationEntry(
                "legacy-ban", ModerationKind.PLAYER_BAN, "P1", "First", "P1", now, 0, "first"));
        ServerModerationState second = ServerModerationState.open().add(new ModerationEntry(
                "new-ban", ModerationKind.IP_BAN, "P2", "Second", "192.0.2.2", now + 1, 0, "second"));
        store.save(first);
        store.save(second);
        require(Files.isRegularFile(store.previousPath()), "moderation save did not retain a previous file");

        Files.writeString(store.path(), "{\"version\":1", StandardCharsets.UTF_8);
        ServerModerationState recovered = store.load();
        require(store.loadStatus().recoveredPrevious(), "truncated moderation JSON did not recover from previous");
        require(recovered.entries().size() == 1
                        && "legacy-ban".equals(recovered.entries().get(0).id()),
                "moderation previous recovery returned the wrong state");
        require(store.loadStatus().summary("Moderation").contains("recovered from previous"),
                "moderation recovery was not clearly reported");

        ServerModerationStore restartedStore = new ServerModerationStore(dir, "moderation-previous");
        ServerModerationState restarted = restartedStore.load();
        require(!restartedStore.loadStatus().recoveredPrevious(),
                "moderation current file was not repaired after previous recovery");
        require(restarted.entries().size() == 1
                        && "legacy-ban".equals(restarted.entries().get(0).id()),
                "repaired moderation state changed across restart");
    }

    private static void validateInvalidTypesRestrictAdmission(Path dir) throws Exception {
        Files.createDirectories(dir);
        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore admin = new ServerAdminStore(dir, "invalid-admin");
        Files.writeString(admin.path(),
                "{\"version\":1,\"maintenance\":\"false\",\"maintenanceReason\":\"\",\"maxSlots\":0,\"motd\":\"\"}",
                StandardCharsets.UTF_8);
        ServerAccessPolicy policy = admin.load();
        require(admin.loadStatus().restricted(), "invalid administration types did not trigger restricted recovery");
        require(policy.maintenance(), "invalid administration types failed open");

        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore moderationAdmin = new ServerAdminStore(dir, "invalid-moderation");
        moderationAdmin.save(ServerAccessPolicy.open());
        ServerModerationStore moderation = new ServerModerationStore(dir, "invalid-moderation");
        Files.writeString(moderation.path(),
                "{\"version\":1,\"whitelistEnabled\":\"false\",\"whitelist\":[],\"entries\":[]}",
                StandardCharsets.UTF_8);
        moderation.load();
        require(moderation.loadStatus().restricted(), "invalid moderation types did not trigger restricted recovery");
        require(CompanionRecoveryRegistry.restricted(), "invalid moderation types failed to restrict admission");
    }

    private static void validateUnmanagedHostDoesNotLockAdmission(Path dir) throws Exception {
        Files.createDirectories(dir);
        CompanionRecoveryRegistry.resetForTests();
        ServerModerationStore moderation = new ServerModerationStore(dir, "unmanaged");
        moderation.load();
        require(moderation.loadStatus().restricted(), "unmanaged host did not detect missing moderation state");
        require(!CompanionRecoveryRegistry.restricted(),
                "moderation recovery lock affected a host without managed administration state");
        require(!ServerAccessPolicy.open().maintenance(),
                "ordinary host admission was forced into dedicated-server recovery mode");
    }

    private static void validateTotalLossAndExplicitReset(Path dir) throws Exception {
        Files.createDirectories(dir);
        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore admin = new ServerAdminStore(dir, "lost");
        ServerAccessPolicy restricted = admin.load();
        ServerModerationStore moderation = new ServerModerationStore(dir, "lost");
        ServerModerationState empty = moderation.load();
        require(admin.loadStatus().restricted() && moderation.loadStatus().restricted(),
                "total companion-file loss was not detected");
        require(restricted.maintenance(), "total companion-file loss started with open admission");
        require(empty.entries().isEmpty(), "total moderation loss invented moderation records");
        require(CompanionRecoveryRegistry.statusReason().contains("new identities are blocked")
                        && CompanionRecoveryRegistry.statusReason().contains("retained identities may continue or reconnect")
                        && CompanionRecoveryRegistry.statusReason().contains("maintenance off"),
                "restricted recovery policy was not documented in status output");
        require(Files.isRegularFile(admin.path()) && Files.isRegularFile(moderation.path()),
                "restricted recovery did not seed verified current files");

        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore restartedAdmin = new ServerAdminStore(dir, "lost");
        ServerAccessPolicy restartedPolicy = restartedAdmin.load();
        ServerModerationStore restartedModeration = new ServerModerationStore(dir, "lost");
        restartedModeration.load();
        require(restartedPolicy.maintenance(), "persistent recovery lock did not survive restart");

        ServerAccessPolicy open = restartedPolicy.withMaintenance(false, "");
        restartedAdmin.save(open);
        require(!CompanionRecoveryRegistry.restricted() && !open.maintenance(),
                "trusted local maintenance reset did not reopen admission");

        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore finalAdmin = new ServerAdminStore(dir, "lost");
        ServerAccessPolicy finalPolicy = finalAdmin.load();
        ServerModerationStore finalModeration = new ServerModerationStore(dir, "lost");
        finalModeration.load();
        require(!finalPolicy.maintenance(), "explicit recovery reset did not persist across restart");
    }

    private static void validateUnreadableCurrentRecovery(Path dir) throws Exception {
        Files.createDirectories(dir);
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return;
        CompanionRecoveryRegistry.resetForTests();
        ServerAdminStore store = new ServerAdminStore(dir, "unreadable");
        ServerAccessPolicy first = new ServerAccessPolicy(true, "previous", 3, "previous");
        store.save(first);
        store.save(ServerAccessPolicy.open());
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(store.path());
        try {
            Files.setPosixFilePermissions(store.path(), Set.of());
            if (Files.isReadable(store.path())) return;
            ServerAccessPolicy recovered = store.load();
            require(store.loadStatus().recoveredPrevious(),
                    "unreadable administration current file did not recover from previous");
            require(recovered.storedMaintenance() && recovered.maxSlots() == 3,
                    "unreadable administration recovery returned the wrong previous state");
        } finally {
            Files.setPosixFilePermissions(store.path(), original);
        }
    }

    private static void validateAtomicMoveFallback(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path source = dir.resolve("source.tmp");
        Path target = dir.resolve("target.json");
        Files.writeString(source, "fallback", StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        CompanionStateFiles.moveReplacing(source, target, (from, to, options) -> {
            calls.incrementAndGet();
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "validator fallback");
            }
            Files.move(from, to, options);
        });
        require(calls.get() == 2 && "fallback".equals(Files.readString(target, StandardCharsets.UTF_8)),
                "atomic move fallback did not complete the replacement");
    }

    private static void validateFailedMovesPreserveCurrent(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path current = dir.resolve("current.txt");
        Path previous = dir.resolve("previous.txt");
        Files.writeString(current, "value:old\n", StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        try {
            CompanionStateFiles.save(current, previous, "value:new",
                    CompanionStateRecoveryValidator::parseTestValue, value -> value,
                    (from, to, options) -> {
                        calls.incrementAndGet();
                        throw new IOException("injected move failure");
                    });
            throw new IllegalStateException("failed companion moves unexpectedly succeeded");
        } catch (IOException expected) {
            require(calls.get() >= 2, "failed atomic move was not retried without atomic replacement");
        }
        require("value:old\n".equals(Files.readString(current, StandardCharsets.UTF_8)),
                "failed previous rotation replaced the current file");
        require(!Files.exists(previous), "failed previous rotation published an unverified previous file");
    }

    private static String parseTestValue(String value) throws IOException {
        if (value == null || !value.trim().startsWith("value:")) throw new IOException("test value is invalid");
        return value.trim();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                        try {
                            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path));
                            permissions.add(PosixFilePermission.OWNER_READ);
                            permissions.add(PosixFilePermission.OWNER_WRITE);
                            permissions.add(PosixFilePermission.OWNER_EXECUTE);
                            Files.setPosixFilePermissions(path, permissions);
                        } catch (Exception ignored) { }
                    }
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) throw io;
            throw ex;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
