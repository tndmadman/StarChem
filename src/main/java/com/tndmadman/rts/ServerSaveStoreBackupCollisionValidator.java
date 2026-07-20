package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Validates collision-safe automatic backup naming and retention ordering. */
public final class ServerSaveStoreBackupCollisionValidator {
    private static final Instant FIXED_TIME = Instant.parse("2026-07-20T12:34:56.789Z");
    private static final String STAMP = "20260720-123456-789";

    private ServerSaveStoreBackupCollisionValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem backup filename collision validation passed.");
    }

    static void validate() throws Exception {
        Path dir = Files.createTempDirectory("starchem-backup-collision-validator-");
        try {
            validateDistinctBackups(dir);
            validateRetentionOrdering(dir);
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void validateDistinctBackups(Path dir) throws Exception {
        String saveName = "collision";
        ServerSaveStore store = new ServerSaveStore(dir, saveName, 10);
        Path current = currentPath(dir, saveName);
        for (int i = 0; i < 5; i++) {
            Files.writeString(current, "snapshot-" + i, StandardCharsets.UTF_8);
            store.rotateBackups(FIXED_TIME);
        }

        List<Path> backups = backups(dir, saveName);
        require(backups.size() == 5, "same-millisecond saves did not create five distinct backups: " + backups);
        for (int i = 0; i < backups.size(); i++) {
            String expectedName = saveName + "-" + STAMP + "-" + sequence(i) + ".starchem-save";
            require(backups.get(i).getFileName().toString().equals(expectedName),
                    "backup sequence was not deterministic: " + backups);
            require(Files.readString(backups.get(i), StandardCharsets.UTF_8).equals("snapshot-" + i),
                    "a same-millisecond backup was overwritten: " + backups.get(i));
        }
    }

    private static void validateRetentionOrdering(Path dir) throws Exception {
        String saveName = "retention";
        ServerSaveStore store = new ServerSaveStore(dir, saveName, 2);
        Path current = currentPath(dir, saveName);
        for (int i = 0; i < 3; i++) {
            Files.writeString(current, "retained-" + i, StandardCharsets.UTF_8);
            store.rotateBackups(FIXED_TIME);
        }

        List<Path> backups = backups(dir, saveName);
        require(backups.size() == 2, "backup retention did not keep the configured count: " + backups);
        require(backups.get(0).getFileName().toString().endsWith("-000001.starchem-save"),
                "backup retention removed the wrong same-millisecond archive: " + backups);
        require(backups.get(1).getFileName().toString().endsWith("-000002.starchem-save"),
                "backup retention ordering was not deterministic: " + backups);
        require(Files.readString(backups.get(0), StandardCharsets.UTF_8).equals("retained-1"),
                "backup retention kept stale content in the first retained archive");
        require(Files.readString(backups.get(1), StandardCharsets.UTF_8).equals("retained-2"),
                "backup retention kept stale content in the newest archive");
    }

    private static List<Path> backups(Path dir, String saveName) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().startsWith(saveName + "-" + STAMP + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(".starchem-save"))
                    .sorted()
                    .toList();
        }
    }

    private static Path currentPath(Path dir, String saveName) {
        return dir.resolve(saveName + "-current.starchem-save");
    }

    private static String sequence(int value) {
        return String.format(Locale.ROOT, "%06d", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
