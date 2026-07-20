package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class ServerSaveStore {
    static final int SAVE_FORMAT_VERSION = 2;
    private static final String EXTENSION = ".starchem-save";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(java.time.ZoneOffset.UTC);
    private static final int MAX_BACKUPS_PER_MILLISECOND = 1_000_000;

    private final Path saveDir;
    private final String saveName;
    private final int backupCount;
    private String saveId = "";
    private Instant createdAt = Instant.now();
    private List<PersistentPlayerSession> loadedPlayerSessions = List.of();

    ServerSaveStore(Path saveDir, String saveName, int backupCount) {
        this.saveDir = saveDir == null ? Path.of("saves") : saveDir;
        this.saveName = Config.cleanSaveName(saveName);
        this.backupCount = Math.max(1, backupCount);
    }

    Optional<World> load(Config config) throws IOException {
        IOException currentFailure = null;
        try {
            if (Files.exists(currentPath())) return Optional.of(readWorld(currentPath(), config));
        } catch (IOException ex) {
            currentFailure = ex;
            System.err.println("Could not load current save: " + ex.getMessage());
        }
        try {
            if (Files.exists(previousPath())) {
                System.err.println("Attempting previous save fallback.");
                return Optional.of(readWorld(previousPath(), config));
            }
        } catch (IOException ex) {
            if (currentFailure != null) ex.addSuppressed(currentFailure);
            throw ex;
        }
        if (currentFailure != null) throw currentFailure;
        return Optional.empty();
    }

    List<PersistentPlayerSession> loadedPlayerSessions() {
        return loadedPlayerSessions;
    }

    void save(World world, Config config, String reason) throws IOException {
        save(world, config, reason, List.of());
    }

    void save(World world, Config config, String reason, List<PersistentPlayerSession> sessions) throws IOException {
        if (world == null) return;
        Files.createDirectories(saveDir);
        Map<String,Object> save = capture(world, config, reason, sessions);
        byte[] players = jsonBytes(object(save.get("players")));
        byte[] galaxy = jsonBytes(object(save.get("galaxy")));
        byte[] runtime = jsonBytes(object(save.get("runtime")));
        Map<String,Object> manifestMap = object(save.get("manifest"));
        manifestMap.put("playersSha256", sha256(players));
        manifestMap.put("galaxySha256", sha256(galaxy));
        manifestMap.put("runtimeSha256", sha256(runtime));
        byte[] manifest = jsonBytes(manifestMap);
        Path temp = saveDir.resolve(saveName + "-new.tmp");
        try (OutputStream raw = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            writeEntry(zip, "manifest.json", manifest);
            writeEntry(zip, "players.json", players);
            writeEntry(zip, "galaxy.json", galaxy);
            writeEntry(zip, "runtime.json", runtime);
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.READ)) {
            channel.force(true);
        }
        readManifest(temp);
        rotateBackups();
        if (Files.exists(currentPath())) Files.move(currentPath(), previousPath(), StandardCopyOption.REPLACE_EXISTING);
        movePromote(temp, currentPath());
    }

    private World readWorld(Path path, Config config) throws IOException {
        Map<String,byte[]> entries = readEntries(path);
        Map<String,Object> manifest = parseObject(entries.get("manifest.json"), "manifest.json");
        int version = intValue(manifest, "saveFormatVersion", 0);
        if (version > SAVE_FORMAT_VERSION) throw new IOException("Save format " + version + " is newer than this server supports.");
        verifyChecksum(entries, manifest, "players.json", "playersSha256");
        verifyChecksum(entries, manifest, "galaxy.json", "galaxySha256");
        verifyChecksum(entries, manifest, "runtime.json", "runtimeSha256");
        saveId = string(manifest, "saveId", "");
        createdAt = parseInstant(string(manifest, "createdAt", ""));
        Map<String,Object> players = parseObject(entries.get("players.json"), "players.json");
        Map<String,Object> galaxy = parseObject(entries.get("galaxy.json"), "galaxy.json");
        Map<String,Object> runtime = entries.containsKey("runtime.json")
                ? parseObject(entries.get("runtime.json"), "runtime.json")
                : new LinkedHashMap<>();
        ServerSaveMigration.Result migrated = ServerSaveMigration.migrate(version, manifest, players, galaxy, runtime);
        manifest = migrated.manifest();
        players = migrated.players();
        galaxy = migrated.galaxy();
        runtime = migrated.runtime();
        if (!migrated.notes().isEmpty()) {
            System.out.println("Loaded save migrated from format " + version + " to " + SAVE_FORMAT_VERSION + ": "
                    + String.join("; ", migrated.notes()));
        }
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false);
        PlayerRegistry.activate(world);
        restorePlayers(players);
        loadedPlayerSessions = restorePlayerSessions(players.get("sessions"));
        world.completedResearch.clear();
        for (Map.Entry<String,Object> entry : object(players.get("completedResearch")).entrySet()) {
            LinkedHashSet<String> topics = new LinkedHashSet<>();
            for (Object topic : list(entry.getValue())) {
                String id = SaveContentResolver.productionItemId(ProductionJobKind.RESEARCH, asString(topic, ""));
                if (!id.isBlank()) topics.add(id);
            }
            world.completedResearch.put(entry.getKey(), topics);
        }
        world.restoreServerSaveGalaxy(galaxy);
        world.restoreServerSaveRuntime(runtime);
        return world;
    }

    private Map<String,Object> capture(World world, Config config, String reason, List<PersistentPlayerSession> sessions) {
        world.saveActiveSystem();
        Instant now = Instant.now();
        if (saveId.isBlank()) {
            saveId = saveName + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);
            createdAt = now;
        }
        Map<String,Object> save = new LinkedHashMap<>();
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("saveFormatVersion", SAVE_FORMAT_VERSION);
        manifest.put("saveId", saveId);
        manifest.put("serverId", Config.cleanSaveName(config.playerName));
        manifest.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt));
        manifest.put("lastSavedAt", DateTimeFormatter.ISO_INSTANT.format(now));
        manifest.put("reason", reason == null ? "manual" : reason);
        manifest.put("applicationVersion", BuildInfo.version());
        manifest.put("buildCommit", BuildInfo.commit());
        MultiplayerCompatibility.Descriptor descriptor = MultiplayerCompatibility.local();
        manifest.put("rulesVersion", descriptor.rulesVersion());
        manifest.put("configurationFingerprint", descriptor.configHash());
        manifest.put("archiveKind", "server-state");
        save.put("manifest", manifest);

        Map<String,Object> players = new LinkedHashMap<>();
        List<Object> roster = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", player.id());
            row.put("name", player.name());
            row.put("rgb", player.rgb());
            row.put("local", player.local());
            roster.add(row);
        }
        players.put("roster", roster);
        Map<String,Object> research = new LinkedHashMap<>();
        for (Map.Entry<String,java.util.Set<String>> entry : world.completedResearch.entrySet()) research.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        players.put("completedResearch", research);
        players.put("sessions", capturePlayerSessions(sessions));
        save.put("players", players);
        save.put("galaxy", world.captureServerSaveGalaxy());
        save.put("runtime", world.captureServerSaveRuntime());
        return save;
    }

    private void restorePlayers(Map<String,Object> players) {
        boolean localSet = false;
        for (Object item : list(players.get("roster"))) {
            Map<String,Object> row = object(item);
            String id = string(row, "id", "");
            if (id.isBlank()) continue;
            boolean local = boolValue(row, "local", false);
            PlayerRegistry.register(id, string(row, "name", id), intValue(row, "rgb", 0x888888), local);
            localSet |= local;
        }
        if (!localSet) PlayerRegistry.register("SOLO", "Server", 0x50BEFF, true);
    }

    private List<Object> capturePlayerSessions(List<PersistentPlayerSession> sessions) {
        List<Object> out = new ArrayList<>();
        if (sessions == null) return out;
        for (PersistentPlayerSession session : sessions) {
            if (session == null || session.playerId().isBlank() || session.tokenDigest().length == 0) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("playerId", session.playerId());
            row.put("name", session.name());
            row.put("rgb", session.rgb());
            row.put("passwordSalt", PasswordAuth.encodeVerifier(session.passwordSalt()));
            row.put("passwordVerifierSha256", PasswordAuth.encodeVerifier(session.passwordDigest()));
            row.put("tokenDigestSha256", encodeBytes(session.tokenDigest()));
            row.put("previousTokenDigestSha256", encodeBytes(session.previousTokenDigest()));
            row.put("previousTokenValidUntil", session.previousTokenValidUntil());
            out.add(row);
        }
        return out;
    }

    private List<PersistentPlayerSession> restorePlayerSessions(Object saved) {
        List<PersistentPlayerSession> out = new ArrayList<>();
        for (Object item : list(saved)) {
            Map<String,Object> row = object(item);
            String playerId = string(row, "playerId", "");
            byte[] tokenDigest = decodeBytes(string(row, "tokenDigestSha256", ""));
            if (playerId.isBlank() || tokenDigest.length == 0) continue;
            out.add(new PersistentPlayerSession(
                    playerId,
                    string(row, "name", playerId),
                    intValue(row, "rgb", 0x888888),
                    PasswordAuth.decodeHex(string(row, "passwordSalt", "")),
                    PasswordAuth.decodeVerifier(string(row, "passwordVerifierSha256", "")),
                    tokenDigest,
                    decodeBytes(string(row, "previousTokenDigestSha256", "")),
                    longValue(row, "previousTokenValidUntil", 0)));
        }
        return List.copyOf(out);
    }

    private void rotateBackups() throws IOException {
        rotateBackups(Instant.now());
    }

    void rotateBackups(Instant backupTime) throws IOException {
        if (Files.exists(currentPath())) copyCurrentBackup(backupTime == null ? Instant.now() : backupTime);
        List<Path> backups;
        try (var stream = Files.list(saveDir)) {
            backups = stream
                    .filter(path -> path.getFileName().toString().startsWith(saveName + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .filter(path -> !path.equals(currentPath()))
                    .filter(path -> !path.equals(previousPath()))
                    .sorted()
                    .toList();
        }
        int excess = backups.size() - backupCount;
        for (int i = 0; i < excess; i++) Files.deleteIfExists(backups.get(i));
    }

    private void copyCurrentBackup(Instant backupTime) throws IOException {
        String stamp = BACKUP_TIMESTAMP.format(backupTime);
        String prefix = saveName + "-" + stamp + "-";
        for (int sequence = 0; sequence < MAX_BACKUPS_PER_MILLISECOND; sequence++) {
            String suffix = String.format(java.util.Locale.ROOT, "%06d", sequence);
            Path target = saveDir.resolve(prefix + suffix + EXTENSION);
            try {
                Files.copy(currentPath(), target);
                return;
            } catch (FileAlreadyExistsException ignored) {
                // Another save already claimed this sequence; try the next sortable name.
            }
        }
        throw new IOException("Could not allocate a unique backup filename for " + saveName + " at " + stamp + ".");
    }

    private Path currentPath() { return saveDir.resolve(saveName + "-current" + EXTENSION); }
    private Path previousPath() { return saveDir.resolve(saveName + "-previous" + EXTENSION); }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void movePromote(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyChecksum(Map<String,byte[]> entries, Map<String,Object> manifest, String entryName, String field) throws IOException {
        String expected = string(manifest, field, "");
        if (expected.isBlank()) return;
        byte[] bytes = entries.get(entryName);
        if (bytes == null) throw new IOException("Save archive is missing " + entryName + ".");
        String actual = sha256(bytes);
        if (!expected.equalsIgnoreCase(actual)) throw new IOException(entryName + " checksum mismatch.");
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IOException("Could not calculate checksum.", ex);
        }
    }

    private static Map<String,byte[]> readEntries(Path path) throws IOException {
        Map<String,byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) out.put(entry.getName(), zip.readAllBytes());
        }
        return out;
    }

    private static Map<String,Object> readManifest(Path path) throws IOException {
        return parseObject(readEntries(path).get("manifest.json"), "manifest.json");
    }

    private static Map<String,Object> parseObject(byte[] bytes, String name) throws IOException {
        if (bytes == null) throw new IOException("Save archive is missing " + name + ".");
        try {
            return object(MiniJson.parse(new String(bytes, StandardCharsets.UTF_8)));
        } catch (RuntimeException ex) {
            throw new IOException("Could not parse " + name + ": " + ex.getMessage(), ex);
        }
    }

    private static byte[] jsonBytes(Map<String,Object> data) {
        return (MiniJson.stringify(data) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Instant parseInstant(String value) {
        try { return value == null || value.isBlank() ? Instant.now() : Instant.parse(value); }
        catch (RuntimeException ex) { return Instant.now(); }
    }

    private static String encodeBytes(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decodeBytes(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try { return Base64.getUrlDecoder().decode(value); }
        catch (RuntimeException ex) { return new byte[0]; }
    }

    static Map<String,Object> materialMap(Map<Material,Double> inventory) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (inventory == null) return out;
        for (Map.Entry<Material,Double> entry : inventory.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0.000001) out.put(entry.getKey().name(), entry.getValue());
        }
        return out;
    }

    static EnumMap<Material,Double> restoreMaterialMap(Object value) {
        EnumMap<Material,Double> out = new EnumMap<>(Material.class);
        for (Map.Entry<String,Object> entry : object(value).entrySet()) {
            Material material = SaveContentResolver.material(entry.getKey());
            double amount = asDouble(entry.getValue(), 0);
            if (material != null && amount > 0.000001) out.put(material, amount);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>)map : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List<?> items ? (List<Object>)items : List.of();
    }

    static String string(Map<String,Object> map, String key, String fallback) { return asString(map.get(key), fallback); }
    static String asString(Object value, String fallback) { return value == null ? fallback : value.toString(); }
    static boolean boolValue(Map<String,Object> map, String key, boolean fallback) { return map.get(key) instanceof Boolean b ? b : fallback; }
    static int intValue(Map<String,Object> map, String key, int fallback) { return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, longValue(map, key, fallback))); }
    static long longValue(Map<String,Object> map, String key, long fallback) { return map.get(key) instanceof Number n ? n.longValue() : fallback; }
    static double doubleValue(Map<String,Object> map, String key, double fallback) { return asDouble(map.get(key), fallback); }
    static double asDouble(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }

    static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        if (type == null || value == null) return fallback;
        try { return Enum.valueOf(type, value.toString()); }
        catch (RuntimeException ex) { return fallback; }
    }
}
