package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Validates collision-safe automatic backup naming, retention ordering, and save-name isolation. */
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
            validatePrefixIsolation(dir.resolve("prefix-isolation"));
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

    private static void validatePrefixIsolation(Path dir) throws Exception {
        Files.createDirectories(dir);
        String saveName = "foo";
        List<String> foreignArchives = List.of(
                "foo-bar-current.starchem-save",
                "foo-bar-previous.starchem-save",
                "foo-bar-20260719-010203-manual.starchem-save",
                "foo.bar-current.starchem-save",
                "foo.bar-previous.starchem-save",
                "foo.bar-20260719-010203-manual.starchem-save",
                "foo_bar-current.starchem-save",
                "foo_bar-previous.starchem-save",
                "foo_bar-20260719-010203-manual.starchem-save",
                "foo-20260720-123456-current.starchem-save",
                "foo-20260720-123456-previous.starchem-save");
        for (String filename : foreignArchives) {
            Files.writeString(dir.resolve(filename), "foreign:" + filename, StandardCharsets.UTF_8);
        }

        Path current = currentPath(dir, saveName);
        Files.writeString(current, "foo-current", StandardCharsets.UTF_8);
        ServerSaveStore store = new ServerSaveStore(dir, saveName, 1);
        store.rotateBackups(FIXED_TIME);

        require(backups(dir, saveName).size() == 1,
                "automatic rotation did not retain exactly one owned backup");
        assertForeignArchivesUntouched(dir, foreignArchives);

        Files.writeString(dir.resolve("foo-20260718-000000-manual.starchem-save"), "owned-old-1", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("foo-20260719-000000-manual.starchem-save"), "owned-old-2", StandardCharsets.UTF_8);
        ServerBackupAdmin admin = new ServerBackupAdmin(dir, saveName, 1);
        List<String> listed = admin.list();
        for (String foreign : foreignArchives) {
            require(listed.stream().noneMatch(line -> line.contains(foreign)),
                    "backup listing exposed a foreign archive: " + foreign);
        }
        require(admin.verifySelector("foo-bar-current.starchem-save").startsWith("Unknown backup selector:"),
                "backup verification accepted another save's current archive");
        require(admin.verifySelector("foo.bar-20260719-010203-manual.starchem-save").startsWith("Unknown backup selector:"),
                "backup verification accepted another save's timestamped archive");

        String pruneResult = admin.prune();
        require(pruneResult.startsWith("Pruned 2 backups; retained 1."),
                "administrative pruning did not operate on only the owned backup set: " + pruneResult);
        require(backups(dir, saveName).size() == 1,
                "administrative pruning retained the wrong number of owned backups");
        assertForeignArchivesUntouched(dir, foreignArchives);
    }

    private static void assertForeignArchivesUntouched(Path dir, List<String> filenames) throws Exception {
        for (String filename : filenames) {
            Path path = dir.resolve(filename);
            require(Files.isRegularFile(path), "foreign archive was deleted: " + filename);
            require(Files.readString(path, StandardCharsets.UTF_8).equals("foreign:" + filename),
                    "foreign archive was modified: " + filename);
        }
    }

    private static List<Path> backups(Path dir, String saveName) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> ServerSaveArchiveNames.isTimestampedBackup(saveName, path))
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
