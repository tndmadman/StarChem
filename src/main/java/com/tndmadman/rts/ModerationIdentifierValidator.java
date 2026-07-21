package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Validates collision-resistant moderation IDs and exact-versus-selector removal behavior. */
public final class ModerationIdentifierValidator {
    private static final Set<ModerationKind> BAN_KINDS = Set.of(
            ModerationKind.PLAYER_BAN, ModerationKind.IP_BAN, ModerationKind.DEVICE_BAN);

    private ModerationIdentifierValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem moderation identifier validation passed.");
    }

    static void validate() throws Exception {
        validateGeneratedIdentifiers();
        validateDuplicateNormalization();
        validateExactAndSelectorRemoval();
        validatePersistenceMigration();
    }

    private static void validateGeneratedIdentifiers() {
        ServerModerationState state = ServerModerationState.open();
        HashSet<String> ids = new HashSet<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 4_096; i++) {
            ModerationEntry entry = new ModerationEntry("", ModerationKind.PLAYER_BAN,
                    "P" + i, "Pilot" + i, "P" + i, now, 0, "generated ID validation");
            require(entry.id().length() == 32 && entry.id().chars().allMatch(ModerationIdentifierValidator::hex),
                    "generated moderation ID was not a 128-bit lowercase hexadecimal value");
            require(ids.add(entry.id()), "generated moderation IDs collided");
            state = state.add(entry);
        }
        require(state.entries().size() == ids.size(), "large moderation entry set lost or duplicated records");
    }

    private static void validateDuplicateNormalization() {
        long now = System.currentTimeMillis();
        ServerModerationState state = ServerModerationState.open()
                .add(new ModerationEntry("legacy01", ModerationKind.PLAYER_BAN,
                        "P1", "Alpha", "P1", now, 0, "first"))
                .add(new ModerationEntry("LEGACY01", ModerationKind.IP_BAN,
                        "P2", "Beta", "192.0.2.2", now, 0, "duplicate"));
        require(state.entries().size() == 2, "duplicate moderation ID normalization dropped an entry");
        require("legacy01".equals(state.entries().get(0).id()), "first legacy moderation ID was changed");
        require(!state.entries().get(0).id().equalsIgnoreCase(state.entries().get(1).id()),
                "adding a duplicate moderation ID did not assign a unique replacement");
        require(state.entries().get(1).id().length() == 32,
                "duplicate moderation ID replacement was not collision resistant");
    }

    private static void validateExactAndSelectorRemoval() {
        long now = System.currentTimeMillis();
        ServerModerationState state = ServerModerationState.open()
                .add(new ModerationEntry("ban00001", ModerationKind.PLAYER_BAN,
                        "P7", "Pilot", "P7", now, 0, "player"))
                .add(new ModerationEntry("ban00002", ModerationKind.IP_BAN,
                        "P7", "Pilot", "192.0.2.7", now, 0, "ip"))
                .add(new ModerationEntry("kick0001", ModerationKind.KICK,
                        "P7", "Pilot", "P7", now, now + 60_000, "kick"));

        ModerationRemoval exact = state.removeById("BAN00001", BAN_KINDS);
        require(exact.exactId() && exact.removedCount() == 1,
                "exact moderation ID removal did not remove exactly one entry");
        require(exact.state().entries().stream().anyMatch(entry -> "ban00002".equals(entry.id())),
                "exact moderation ID removal deleted an unrelated ban");
        require(exact.state().entries().stream().anyMatch(entry -> "kick0001".equals(entry.id())),
                "exact moderation ID removal deleted a kick");

        ModerationRemoval bulk = state.removeBySelector("pilot", BAN_KINDS);
        require(!bulk.exactId() && bulk.removedCount() == 2,
                "selector removal did not report its multi-entry effect");
        require(bulk.state().entries().size() == 1 && bulk.state().entries().get(0).kind() == ModerationKind.KICK,
                "ban selector removal affected a non-ban entry");

        ModerationRemoval commandExact = ServerCommandExtensions.resolveModerationRemoval(state, "BAN00001", BAN_KINDS);
        String exactMessage = ServerCommandExtensions.moderationRemovalMessage(commandExact, "ban", "BAN00001");
        require(commandExact.exactId() && commandExact.removedCount() == 1
                        && "Removed ban entry BAN00001.".equals(exactMessage),
                "command removal did not clearly report exact-ID behavior");

        ModerationRemoval commandBulk = ServerCommandExtensions.resolveModerationRemoval(state, "pilot", BAN_KINDS);
        String bulkMessage = ServerCommandExtensions.moderationRemovalMessage(commandBulk, "ban", "pilot");
        require(!commandBulk.exactId() && commandBulk.removedCount() == 2
                        && bulkMessage.contains("Removed 2 ban entries by selector")
                        && bulkMessage.contains("may affect multiple records"),
                "command removal did not clearly report selector-based bulk behavior");

        require(state.removeMatching("kick0001", null).entries().size() == state.entries().size(),
                "unban-compatible removal removed a kick by ID");
        require(state.removeMatching("P7", ModerationKind.KICK).entries().size() == 2,
                "unkick-compatible selector removal did not remove the kick");
        require(state.removeMatching("BAN00001", null).entries().size() == 2,
                "exact ID compatibility removal did not target exactly one ban");
    }

    private static void validatePersistenceMigration() throws Exception {
        Path dir = Files.createTempDirectory("starchem-moderation-id-validator-");
        try {
            long now = System.currentTimeMillis();
            ServerModerationStore store = new ServerModerationStore(dir, "validation");
            ServerModerationState legacy = ServerModerationState.open()
                    .add(new ModerationEntry("a1b2c3d4", ModerationKind.PLAYER_BAN,
                            "P3", "Legacy", "P3", now, 0, "legacy"));
            store.save(legacy);
            ServerModerationState restored = store.load();
            require(restored.entries().size() == 1 && "a1b2c3d4".equals(restored.entries().get(0).id()),
                    "existing short moderation ID did not survive persistence migration");
            require(restored.removeById("A1B2C3D4", BAN_KINDS).removedCount() == 1,
                    "existing short moderation ID was no longer addressable");

            Files.writeString(store.path(), duplicateJson(now), StandardCharsets.UTF_8);
            ServerModerationState normalized = store.load();
            require(normalized.entries().size() == 2, "duplicate persisted IDs caused an entry to be lost");
            HashSet<String> ids = new HashSet<>();
            for (ModerationEntry entry : normalized.entries()) {
                require(ids.add(entry.id().toLowerCase(Locale.ROOT)),
                        "duplicate persisted IDs remained ambiguous after loading");
            }
            require(normalized.entries().stream().anyMatch(entry -> "deadbeef".equals(entry.id())),
                    "the first persisted legacy ID was not preserved");

            ServerModerationState restarted = new ServerModerationStore(dir, "validation").load();
            require(restarted.entries().stream()
                            .map(entry -> entry.id().toLowerCase(Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet()).equals(ids),
                    "normalized moderation IDs were not persisted across restart");
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static String duplicateJson(long now) {
        return "{\"version\":1,\"whitelistEnabled\":false,\"whitelist\":[],\"entries\":["
                + "{\"id\":\"deadbeef\",\"kind\":\"PLAYER_BAN\",\"playerId\":\"P4\",\"playerName\":\"First\",\"target\":\"P4\",\"createdAt\":" + now + ",\"expiresAt\":0,\"reason\":\"first\"},"
                + "{\"id\":\"DEADBEEF\",\"kind\":\"IP_BAN\",\"playerId\":\"P5\",\"playerName\":\"Second\",\"target\":\"192.0.2.5\",\"createdAt\":" + (now + 1) + ",\"expiresAt\":0,\"reason\":\"duplicate\"}"
                + "]}";
    }

    private static boolean hex(int value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
