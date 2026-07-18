package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ServerSaveStoreValidator {
    private ServerSaveStoreValidator() { }

    public static void main(String[] args) throws Exception {
        validateCurrentPreviousFallback();
        validateChecksumRejection();
        validateBackupPruningKeepsCurrentAndPrevious();
        System.out.println("StarChem server save store validation passed.");
    }

    private static void validateCurrentPreviousFallback() throws Exception {
        Path dir = tempDir("fallback");
        try {
            Config config = config(dir, "fallback-save", 3);
            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            World first = worldWithResearch("advanced_industry");
            store.save(first, config, "first");
            World second = worldWithResearch("combat_doctrine");
            store.save(second, config, "second");
            corrupt(dir.resolve("fallback-save-current.starchem-save"));

            Optional<World> loaded = store.load(config);
            require(loaded.isPresent(), "fallback did not load previous save");
            require(loaded.get().hasResearch("SOLO", "advanced_industry"),
                    "fallback did not restore previous save contents");
            require(!loaded.get().hasResearch("SOLO", "combat_doctrine"),
                    "fallback unexpectedly loaded corrupted current save");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateChecksumRejection() throws Exception {
        Path dir = tempDir("checksum");
        try {
            Config config = config(dir, "checksum-save", 3);
            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(worldWithResearch("advanced_industry"), config, "checksum");
            corruptEntry(dir.resolve("checksum-save-current.starchem-save"), "galaxy.json");

            try {
                store.load(config);
                throw new IllegalStateException("expected corrupted save to be rejected");
            } catch (IOException ex) {
                require(ex.getMessage() != null && !ex.getMessage().isBlank(),
                        "checksum rejection did not include a readable error");
            }
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateBackupPruningKeepsCurrentAndPrevious() throws Exception {
        Path dir = tempDir("backups");
        try {
            Config config = config(dir, "backup-save", 1);
            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(worldWithResearch("advanced_industry"), config, "one");
            Thread.sleep(1100);
            store.save(worldWithResearch("combat_doctrine"), config, "two");
            Thread.sleep(1100);
            store.save(worldWithResearch("battlefleet_engineering"), config, "three");

            require(Files.exists(dir.resolve("backup-save-current.starchem-save")),
                    "backup pruning removed current save");
            require(Files.exists(dir.resolve("backup-save-previous.starchem-save")),
                    "backup pruning removed previous save");
            try (Stream<Path> stream = Files.list(dir)) {
                long timestamped = stream
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith("backup-save-"))
                        .filter(name -> name.endsWith(".starchem-save"))
                        .filter(name -> !name.equals("backup-save-current.starchem-save"))
                        .filter(name -> !name.equals("backup-save-previous.starchem-save"))
                        .count();
                require(timestamped <= 1, "backup pruning kept too many timestamped archives: " + timestamped);
            }
        } finally {
            deleteTree(dir);
        }
    }

    private static World worldWithResearch(String marker) {
        PlayerRegistry.reset("SOLO", "Save Store Validator", 0x50BEFF);
        World world = new World("Save Store Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        world.completedResearch.computeIfAbsent("SOLO", ignored -> new java.util.LinkedHashSet<>()).add(marker);
        return world;
    }

    private static Config config(Path dir, String name, int backups) {
        return Config.dedicatedServer("Save Store Validator", 34567, false, false, Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, "", 1, dir, name, 60, backups, false);
    }

    private static Path tempDir(String name) throws IOException {
        Files.createDirectories(Path.of("build"));
        return Files.createTempDirectory(Path.of("build"), "server-save-" + name + "-");
    }

    private static void corrupt(Path path) throws IOException {
        Files.write(path, List.of("not a starchem save archive"));
    }

    private static void corruptEntry(Path path, String entryName) throws IOException {
        Map<String,byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        entries.put(entryName, "{}".getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            for (Map.Entry<String,byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
