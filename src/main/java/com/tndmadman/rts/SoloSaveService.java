package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Independently named solo campaigns, backed by verified server-format archives. */
final class SoloSaveService {
    static final String ARCHIVE = "solo-current.starchem-save";
    private static final String MANUAL = "solo-manual.starchem-save";
    private static final String NAME = "name.txt";
    private static final Map<World,String> WORLD_SLOTS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final Path root;
    private final Map<String,ServerSaveStore> stores = new LinkedHashMap<>();

    record Slot(String id, String name, Instant savedAt, String activeSystemId, String status) {
        @Override public String toString() {
            return name + " — " + (savedAt == null ? "Not yet saved" : savedAt.toString())
                    + (activeSystemId.isBlank() ? "" : " — " + activeSystemId)
                    + " [" + status + "]";
        }
    }

    SoloSaveService() { this(DefaultStoragePaths.graphicalSaveDirectory().resolve("solo")); }
    SoloSaveService(Path root) { this.root = root.toAbsolutePath().normalize(); }

    static void activate(World world, String id) {
        if (world != null && id != null && !id.isBlank()) WORLD_SLOTS.put(world, id);
    }
    static void forget(World world) { if (world != null) WORLD_SLOTS.remove(world); }
    static String fogPlayerKey(World world, String playerId) {
        String id = WORLD_SLOTS.get(world);
        return id == null ? playerId : "solo-" + id + "-" + playerId;
    }

    synchronized Slot create(String displayName) throws IOException {
        String name = cleanName(displayName);
        secureRoot();
        String id = UUID.randomUUID().toString();
        Path directory = slotDir(id);
        Files.createDirectory(directory);
        writeName(directory, name);
        return new Slot(id, name, null, "", "NEW");
    }

    synchronized List<Slot> list() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        secureRoot();
        List<Slot> out = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path directory : dirs.toList()) {
                String id = directory.getFileName().toString();
                if (!validId(id) || Files.isSymbolicLink(directory)
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                Map<String,Object> manifest = Map.of();
                String status = "MISSING";
                for (Path archive : recoveryFiles(directory)) {
                    try {
                        Map<String,Object> candidate = ServerSaveStore.readManifest(archive);
                        if (!"solo-state".equals(ServerSaveStore.string(candidate, "archiveKind", ""))
                                || !id.equals(ServerSaveStore.string(candidate, "soloSlotId", ""))) continue;
                        manifest = candidate;
                        status = archive.getFileName().toString().equals(ARCHIVE) ? "SAVED" : "RECOVERY";
                        break;
                    } catch (IOException ignored) { status = "CORRUPT"; }
                }
                String name = readName(directory, ServerSaveStore.string(manifest, "soloSlotName", "Solo Campaign"));
                Instant time = parseTime(ServerSaveStore.string(manifest, "lastSavedAt", ""));
                out.add(new Slot(id, name, time, ServerSaveStore.string(manifest, "activeSystemId", ""), status));
            }
        }
        out.sort(Comparator.comparing(Slot::savedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return List.copyOf(out);
    }

    synchronized Slot mostRecentValid() throws IOException {
        for (Slot slot : list()) {
            for (Path archive : recoveryFiles(slotDir(slot.id()))) {
                try {
                    ServerSaveStore.ValidatedArchive checked = ServerSaveStore.validateArchive(archive);
                    if ("solo-state".equals(ServerSaveStore.string(checked.manifest(), "archiveKind", ""))
                            && slot.id().equals(ServerSaveStore.string(checked.manifest(), "soloSlotId", ""))) {
                        return slot;
                    }
                } catch (IOException ignored) { }
            }
        }
        return null;
    }

    void save(String id, World world, Config config, String reason) throws IOException {
        if (config.role() != NetworkRole.SOLO) throw new IOException("Only solo games may use solo slots.");
        Path directory = slotDir(id);
        requireDirectory(directory);
        ServerSaveStore store = store(id, directory);
        store.setSoloMetadata(id, readName(directory, config.playerName));
        PlayerRegistry.activate(world);
        store.save(world, config, reason);
        if (!"autosave".equals(reason)) {
            Path temporary = directory.resolve("solo-manual.tmp");
            try {
                Files.copy(directory.resolve(ARCHIVE), temporary, StandardCopyOption.REPLACE_EXISTING);
                ServerSaveStore.validateArchive(temporary);
                Files.move(temporary, directory.resolve(MANUAL), StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    World load(String id, Config config) throws IOException {
        if (config.role() != NetworkRole.SOLO) throw new IOException("Only solo games may load solo slots.");
        Path directory = slotDir(id);
        requireDirectory(directory);
        IOException failure = null;
        for (Path archive : recoveryFiles(directory)) {
            try {
                ServerSaveStore.ValidatedArchive checked = ServerSaveStore.validateArchive(archive);
                if (!"solo-state".equals(ServerSaveStore.string(checked.manifest(), "archiveKind", ""))
                        || !id.equals(ServerSaveStore.string(checked.manifest(), "soloSlotId", ""))) {
                    throw new IOException("Archive belongs to another save slot.");
                }
                ServerSaveStore store = store(id, directory);
                store.setSoloMetadata(id, readName(directory, "Solo Campaign"));
                World world = store.loadArchive(archive, config);
                activate(world, id);
                return world;
            } catch (IOException ex) { failure = ex; }
        }
        throw failure == null ? new IOException("No saved campaign exists in that slot.") : failure;
    }

    synchronized void rename(String id, String name) throws IOException {
        Path directory = slotDir(id);
        requireDirectory(directory);
        writeName(directory, cleanName(name));
    }

    synchronized void delete(String id) throws IOException {
        Path directory = slotDir(id);
        requireDirectory(directory);
        try (var stream = Files.list(directory)) {
            for (Path file : stream.toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Slot contains unsupported entries; deletion stopped.");
                }
            }
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.toList()) Files.delete(file);
        }
        Files.delete(directory);
        stores.remove(id);
    }

    private synchronized ServerSaveStore store(String id, Path directory) {
        return stores.computeIfAbsent(id, ignored -> new ServerSaveStore(directory, "solo", 5, true));
    }

    private List<Path> recoveryFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> result = new ArrayList<>();
        for (String name : List.of(ARCHIVE, "solo-previous.starchem-save", MANUAL)) {
            Path archive = directory.resolve(name);
            if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) result.add(archive);
        }
        try (var stream = Files.list(directory)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> ServerSaveArchiveNames.isTimestampedBackup("solo", path))
                    .sorted(Comparator.reverseOrder())
                    .forEach(result::add);
        }
        return result;
    }

    private void secureRoot() throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
            throw new IOException("Solo save root must not be a symbolic link.");
        }
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid solo save root.");
    }
    private void requireDirectory(Path directory) throws IOException {
        secureRoot();
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Solo slot is missing or unsafe.");
        }
    }
    private Path slotDir(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid solo slot ID.");
        Path directory = root.resolve(id).normalize();
        if (!directory.getParent().equals(root)) throw new IllegalArgumentException("Unsafe slot directory.");
        return directory;
    }
    private static boolean validId(String id) {
        if (id == null) return false;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static String cleanName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || name.chars().anyMatch(c -> c < 32)) {
            throw new IllegalArgumentException("Slot name must be 1 to 80 printable characters.");
        }
        return name.trim();
    }
    private static void writeName(Path directory, String name) throws IOException {
        Path temp = directory.resolve("name-new.tmp");
        try {
            Files.writeString(temp, name);
            Files.move(temp, directory.resolve(NAME), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static String readName(Path directory, String fallback) {
        try {
            Path path = directory.resolve(NAME);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 256) {
                return cleanName(Files.readString(path));
            }
        } catch (IOException | IllegalArgumentException ignored) { }
        return fallback;
    }
    private static Instant parseTime(String value) {
        try { return Instant.parse(value); } catch (RuntimeException ex) { return null; }
    }
}
