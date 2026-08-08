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
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ServerSaveStore {
    static final int SAVE_FORMAT_VERSION = 6;
    private static final String EXTENSION = ".starchem-save";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(java.time.ZoneOffset.UTC);
    private static final int MAX_BACKUPS_PER_MILLISECOND = 1_000_000;

    static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;
    static final int MAX_ARCHIVE_ENTRIES = 4;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    static final int MAX_PLAYERS_BYTES = 64 * 1024 * 1024;
    static final int MAX_GALAXY_BYTES = 384 * 1024 * 1024;
    static final int MAX_RUNTIME_BYTES = 64 * 1024 * 1024;
    private static final long COMPRESSION_RATIO_MIN_BYTES = 1024L * 1024;
    private static final long MAX_COMPRESSION_RATIO = 200;
    private static final Map<String,Integer> ENTRY_LIMITS = Map.of(
            "manifest.json", MAX_MANIFEST_BYTES,
            "players.json", MAX_PLAYERS_BYTES,
            "galaxy.json", MAX_GALAXY_BYTES,
            "runtime.json", MAX_RUNTIME_BYTES);

    private final Path saveDir;
    private final String saveName;
    private final int backupCount;
    private final ServerPersistenceCoordinator persistence;
    private String saveId = "";
    private Instant createdAt = Instant.now();
    private List<PersistentPlayerSession> loadedPlayerSessions = List.of();

    ServerSaveStore(Path saveDir, String saveName, int backupCount) {
        this.saveDir = saveDir == null ? Path.of("saves") : saveDir;
        this.saveName = Config.cleanSaveName(saveName);
        this.backupCount = Math.max(1, backupCount);
        this.persistence = ServerPersistenceCoordinator.forSave(this.saveDir, this.saveName);
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
        String safeReason = reason == null || reason.isBlank() ? "manual" : reason;
        SaveSnapshot snapshot = captureSnapshot(world, config, safeReason, sessions);
        boolean coalesce = "autosave".equals(safeReason);
        boolean asynchronous = coalesce || "backup-source".equals(safeReason);
        ServerPersistenceCoordinator.Submission submission = persistence.submit(
                "save-" + safeReason, coalesce, () -> persist(snapshot));
        if (!submission.accepted()) throw new IOException("Persistence queue is full; save was not queued.");
        if (!asynchronous) persistence.await(submission);
    }

    private SaveSnapshot captureSnapshot(World world, Config config, String reason,
                                         List<PersistentPlayerSession> sessions) {
        Map<String,Object> save = capture(world, config, reason, sessions);
        return new SaveSnapshot(
                freezeMap(object(save.get("manifest"))),
                freezeMap(object(save.get("players"))),
                freezeMap(object(save.get("galaxy"))),
                freezeMap(object(save.get("runtime"))));
    }

    private void persist(SaveSnapshot snapshot) throws IOException {
        Files.createDirectories(saveDir);
        byte[] players = jsonBytes(snapshot.players());
        byte[] galaxy = jsonBytes(snapshot.galaxy());
        byte[] runtime = jsonBytes(snapshot.runtime());
        Map<String,Object> manifestMap = new LinkedHashMap<>(snapshot.manifest());
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
        promoteVerified(temp);
    }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> raw) {
            Map<String,Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?,?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> raw) {
            List<Object> copy = new ArrayList<>(raw.size());
            for (Object item : raw) copy.add(freeze(item));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof byte[] bytes) return bytes.clone();
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> freezeMap(Map<String,Object> value) {
        return (Map<String,Object>)freeze(value == null ? Map.of() : value);
    }

    void promoteVerified(Path temp) throws IOException {
        validateArchive(temp);
        rotateBackups();
        if (Files.exists(currentPath())) Files.move(currentPath(), previousPath(), StandardCopyOption.REPLACE_EXISTING);
        movePromote(temp, currentPath());
    }

    private World readWorld(Path path, Config config) throws IOException {
        ValidatedArchive archive = validateArchive(path);
        int version = archive.version();
        Map<String,Object> manifest = archive.manifest();
        Map<String,Object> players = archive.players();
        Map<String,Object> galaxy = archive.galaxy();
        Map<String,Object> runtime = archive.runtime();
        saveId = string(manifest, "saveId", "");
        createdAt = parseInstant(string(manifest, "createdAt", ""));
        ServerSaveMigration.Result migrated = ServerSaveMigration.migrate(version, manifest, players, galaxy, runtime);
        manifest = migrated.manifest();
        players = migrated.players();
        galaxy = migrated.galaxy();
        runtime = migrated.runtime();
        if (!migrated.notes().isEmpty()) {
            System.out.println("Loaded save migrated from format " + version + " to " + SAVE_FORMAT_VERSION + ": "
                    + String.join("; ", migrated.notes()));
        }
        SkirmishSettings skirmish = SkirmishSettings.fromSaved(manifest.get("skirmish"), config.skirmishSettings);
        World world = new World(config.playerName, skirmish.disabledNpcFactionIds(), config.systemId, false);
        SkirmishRuntime.bind(world, skirmish);
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
        // Dynamic fit definitions must exist before galaxy units and production queues resolve their fit IDs.
        WorldFitCatalog.restore(world, runtime.get("shipFits"));
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
        manifest.put("skirmish", SkirmishRuntime.settings(world).saveMap());
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
        Map<String,Object> runtime = new LinkedHashMap<>(world.captureServerSaveRuntime());
        runtime.put("shipFits", WorldFitCatalog.capture(world));
        save.put("runtime", runtime);
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
                    .filter(Files::isRegularFile)
                    .filter(path -> ServerSaveArchiveNames.isTimestampedBackup(saveName, path))
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

    static ValidatedArchive validateArchive(Path path) throws IOException {
        Map<String,byte[]> entries = readEntries(path);
        Map<String,Object> manifest = parseObject(entries.get("manifest.json"), "manifest.json");
        int version = saveFormatVersion(manifest);
        if (version > SAVE_FORMAT_VERSION) throw new IOException("Save format " + version + " is newer than this server supports.");
        if (version < 1) throw new IOException("Save format " + version + " is not supported.");

        Map<String,Object> players = parseObject(entries.get("players.json"), "players.json");
        Map<String,Object> galaxy = parseObject(entries.get("galaxy.json"), "galaxy.json");
        Map<String,Object> runtime;
        if (version >= 2 && version <= SAVE_FORMAT_VERSION) {
            runtime = parseObject(entries.get("runtime.json"), "runtime.json");
            verifyRequiredChecksum(entries, manifest, "players.json", "playersSha256");
            verifyRequiredChecksum(entries, manifest, "galaxy.json", "galaxySha256");
            verifyRequiredChecksum(entries, manifest, "runtime.json", "runtimeSha256");
        } else if (version == 1) {
            runtime = entries.containsKey("runtime.json")
                    ? parseObject(entries.get("runtime.json"), "runtime.json")
                    : new LinkedHashMap<>();
            verifyLegacyChecksum(entries, manifest, "players.json", "playersSha256");
            verifyLegacyChecksum(entries, manifest, "galaxy.json", "galaxySha256");
            verifyLegacyChecksum(entries, manifest, "runtime.json", "runtimeSha256");
        } else {
            throw new IOException("Save format " + version + " is not supported.");
        }
        return new ValidatedArchive(version, manifest, players, galaxy, runtime);
    }

    private static int saveFormatVersion(Map<String,Object> manifest) throws IOException {
        Object value = manifest.get("saveFormatVersion");
        if (!(value instanceof Number number)) throw new IOException("Save manifest is missing a numeric saveFormatVersion.");
        double decimal = number.doubleValue();
        long integer = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integer || integer < 1 || integer > Integer.MAX_VALUE) {
            throw new IOException("Save manifest has an invalid saveFormatVersion.");
        }
        return (int)integer;
    }

    private static void verifyRequiredChecksum(Map<String,byte[]> entries, Map<String,Object> manifest,
                                               String entryName, String field) throws IOException {
        Object value = manifest.get(field);
        if (!(value instanceof String expected) || !expected.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Save manifest has an invalid " + field + ".");
        }
        verifyChecksum(entries, entryName, expected);
    }

    private static void verifyLegacyChecksum(Map<String,byte[]> entries, Map<String,Object> manifest,
                                             String entryName, String field) throws IOException {
        Object value = manifest.get(field);
        if (value == null) return;
        if (!(value instanceof String expected)) throw new IOException("Save manifest has an invalid " + field + ".");
        if (expected.isBlank()) return;
        if (!expected.matches("(?i)[0-9a-f]{64}")) throw new IOException("Save manifest has an invalid " + field + ".");
        verifyChecksum(entries, entryName, expected);
    }

    private static void verifyChecksum(Map<String,byte[]> entries, String entryName, String expected) throws IOException {
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
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Save archive is missing.");
        long archiveSize = Files.size(path);
        if (archiveSize > MAX_ARCHIVE_BYTES) {
            throw new IOException("Save archive exceeds " + MAX_ARCHIVE_BYTES + " compressed bytes.");
        }
        Map<String,ZipEntry> metadata = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Save archive contains more than " + MAX_ARCHIVE_ENTRIES + " entries.");
                }
                if (entry.isDirectory()) throw new IOException("Save archive contains unsupported directory entry " + entry.getName() + ".");
                String name = entry.getName();
                Integer limit = ENTRY_LIMITS.get(name);
                if (limit == null) throw new IOException("Save archive contains unexpected entry " + name + ".");
                if (metadata.putIfAbsent(name, entry) != null) {
                    throw new IOException("Save archive contains duplicate entry " + name + ".");
                }
                long declaredSize = entry.getSize();
                if (declaredSize > limit) throw new IOException(name + " exceeds " + limit + " bytes.");
                enforceCompressionRatio(name, declaredSize, entry.getCompressedSize());
            }

            Map<String,byte[]> out = new LinkedHashMap<>();
            long total = 0;
            for (Map.Entry<String,ZipEntry> item : metadata.entrySet()) {
                String name = item.getKey();
                ZipEntry entry = item.getValue();
                int limit = ENTRY_LIMITS.get(name);
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = BoundedText.readBytes(input, limit, name);
                }
                total += bytes.length;
                if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new IOException("Save archive exceeds " + MAX_TOTAL_UNCOMPRESSED_BYTES + " total uncompressed bytes.");
                }
                enforceCompressionRatio(name, bytes.length, entry.getCompressedSize());
                out.put(name, bytes);
            }
            return out;
        } catch (IOException ex) {
            throw new IOException("Could not read save archive: " + ex.getMessage(), ex);
        }
    }

    private static void enforceCompressionRatio(String name, long uncompressed, long compressed) throws IOException {
        if (uncompressed < COMPRESSION_RATIO_MIN_BYTES || compressed <= 0) return;
        if (uncompressed / compressed > MAX_COMPRESSION_RATIO) {
            throw new IOException(name + " exceeds the supported compression ratio.");
        }
    }

    private static Map<String,Object> parseObject(byte[] bytes, String name) throws IOException {
        if (bytes == null) throw new IOException("Save archive is missing " + name + ".");
        Object parsed;
        try {
            MiniJson.Limits limits = jsonLimits(name);
            parsed = MiniJson.parse(BoundedText.decodeUtf8(bytes, limits.maxDocumentChars(), name), limits);
        } catch (RuntimeException ex) {
            throw new IOException("Could not parse " + name + ": " + ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map<?,?> raw)) throw new IOException(name + " is not an object.");
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static MiniJson.Limits jsonLimits(String name) {
        int documentChars = ENTRY_LIMITS.getOrDefault(name, MAX_MANIFEST_BYTES);
        return new MiniJson.Limits(documentChars, 128, 20_000_000L, 4 * 1024 * 1024,
                2_000_000, 128, true);
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

    private record SaveSnapshot(Map<String,Object> manifest, Map<String,Object> players,
                                Map<String,Object> galaxy, Map<String,Object> runtime) { }

    record ValidatedArchive(int version, Map<String,Object> manifest, Map<String,Object> players,
                            Map<String,Object> galaxy, Map<String,Object> runtime) { }
}
