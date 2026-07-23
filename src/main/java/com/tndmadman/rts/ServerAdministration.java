package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record ServerAccessPolicy(boolean maintenance, String maintenanceReason, int maxSlots, String motd) {
    static final int MAX_SLOTS = 10_000;
    static final int MAX_TEXT = 512;

    ServerAccessPolicy {
        maintenanceReason = clean(maintenanceReason);
        maxSlots = Math.max(0, Math.min(MAX_SLOTS, maxSlots));
        motd = clean(motd);
    }

    static ServerAccessPolicy open() { return new ServerAccessPolicy(false, "", 0, ""); }

    static ServerAccessPolicy restricted(String detail) {
        String reason = "Companion administration state requires operator recovery";
        if (detail != null && !detail.isBlank()) reason += ": " + detail;
        return new ServerAccessPolicy(true, reason, 0, "");
    }

    @Override public boolean maintenance() {
        return maintenance || CompanionRecoveryRegistry.restricted();
    }

    @Override public String maintenanceReason() {
        String recovery = CompanionRecoveryRegistry.statusReason();
        if (recovery.isBlank()) return maintenanceReason;
        if (maintenanceReason.isBlank()) return recovery;
        return maintenanceReason + "; " + recovery;
    }

    boolean storedMaintenance() { return maintenance; }
    String storedMaintenanceReason() { return maintenanceReason; }

    ServerAccessPolicy withMaintenance(boolean enabled, String reason) {
        if (!enabled) CompanionRecoveryRegistry.requestOperatorReset();
        return new ServerAccessPolicy(enabled, enabled ? reason : "", maxSlots, motd);
    }

    ServerAccessPolicy withMaxSlots(int slots) {
        return new ServerAccessPolicy(maintenance, maintenanceReason, slots, motd);
    }

    ServerAccessPolicy withMotd(String message) {
        return new ServerAccessPolicy(maintenance, maintenanceReason, maxSlots, message);
    }

    String maintenanceMessage() {
        String reason = maintenanceReason();
        return reason.isBlank() ? "Server is temporarily in maintenance mode." : reason;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= MAX_TEXT ? clean : clean.substring(0, MAX_TEXT);
    }
}

final class ServerAdminStore {
    private final Path path;
    private final Path previousPath;
    private CompanionLoadStatus loadStatus = CompanionLoadStatus.current("not loaded");

    ServerAdminStore(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        String cleanName = Config.cleanSaveName(saveName);
        this.path = dir.resolve(cleanName + "-admin.json");
        this.previousPath = dir.resolve(cleanName + "-admin-previous.json");
        CompanionRecoveryRegistry.configure(dir, cleanName);
        CompanionRecoveryRegistry.enableRestrictedAdmission();
    }

    Path path() { return path; }
    Path previousPath() { return previousPath; }
    CompanionLoadStatus loadStatus() { return loadStatus; }

    ServerAccessPolicy load() {
        CompanionLoad<ServerAccessPolicy> loaded = CompanionStateFiles.load(path, previousPath,
                "Administration", ServerAdminStore::parsePolicy, ServerAccessPolicy::restricted);
        ServerAccessPolicy policy = loaded.value();
        loadStatus = loaded.status();
        boolean ready = !loadStatus.restricted();

        if (loadStatus.recoveredPrevious()) {
            try {
                CompanionStateFiles.repairCurrent(path, policy, ServerAdminStore::parsePolicy,
                        ServerAdminStore::serializePolicy);
            } catch (IOException ex) {
                loadStatus = CompanionLoadStatus.previous(loadStatus.detail()
                        + "; current repair failed: " + ex.getMessage());
            }
        } else if (loadStatus.restricted()) {
            try {
                CompanionStateFiles.repairCurrent(path, policy, ServerAdminStore::parsePolicy,
                        ServerAdminStore::serializePolicy);
                ready = true;
            } catch (IOException ex) {
                System.err.println("Could not seed restricted server administration settings: " + ex.getMessage());
            }
        }

        CompanionRecoveryRegistry.recordAdministration(loadStatus, ready);
        if (loadStatus.recoveredPrevious() || loadStatus.restricted()) {
            System.err.println(loadStatus.summary("Server administration"));
        }
        return policy;
    }

    synchronized void save(ServerAccessPolicy policy) throws IOException {
        ServerAccessPolicy safe = policy == null ? ServerAccessPolicy.open() : policy;
        CompanionStateFiles.save(path, previousPath, safe, ServerAdminStore::parsePolicy,
                ServerAdminStore::serializePolicy);
        loadStatus = CompanionLoadStatus.current("verified save");
        CompanionRecoveryRegistry.recordAdministration(loadStatus, true);
        CompanionRecoveryRegistry.completeAdministrationSave(safe.storedMaintenance());
    }

    private static ServerAccessPolicy parsePolicy(String text) throws IOException {
        Object parsed;
        try { parsed = MiniJson.parse(text); }
        catch (RuntimeException ex) { throw new IOException("could not parse admin JSON: " + ex.getMessage(), ex); }
        if (!(parsed instanceof Map<?,?> raw)) throw new IOException("admin file root is not an object");
        Map<String,Object> map = stringMap(raw);
        Object version = map.get("version");
        if (version != null && !(version instanceof Number)) throw new IOException("admin version is not numeric");
        boolean maintenance = requiredBoolean(map, "maintenance");
        String reason = requiredString(map, "maintenanceReason");
        int maxSlots = requiredInteger(map, "maxSlots");
        if (maxSlots < 0 || maxSlots > ServerAccessPolicy.MAX_SLOTS) {
            throw new IOException("admin maxSlots is outside the supported range");
        }
        String motd = requiredString(map, "motd");
        return new ServerAccessPolicy(maintenance, reason, maxSlots, motd);
    }

    private static String serializePolicy(ServerAccessPolicy policy) {
        ServerAccessPolicy safe = policy == null ? ServerAccessPolicy.open() : policy;
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("version", 1);
        map.put("maintenance", safe.storedMaintenance());
        map.put("maintenanceReason", safe.storedMaintenanceReason());
        map.put("maxSlots", safe.maxSlots());
        map.put("motd", safe.motd());
        map.put("updatedAt", Instant.now().toString());
        return MiniJson.stringify(map);
    }

    private static Map<String,Object> stringMap(Map<?,?> raw) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static boolean requiredBoolean(Map<String,Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) throw new IOException("admin " + key + " is not boolean");
        return bool;
    }

    private static int requiredInteger(Map<String,Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IOException("admin " + key + " is not numeric");
        long raw = number.longValue();
        if (raw != number.doubleValue() || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            throw new IOException("admin " + key + " is not an integer");
        }
        return (int)raw;
    }

    private static String requiredString(Map<String,Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text)) throw new IOException("admin " + key + " is not text");
        return text;
    }
}

final class ServerBackupAdmin {
    private static final String EXTENSION = ".starchem-save";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path saveDir;
    private final String saveName;
    private final int retention;

    ServerBackupAdmin(Path saveDir, String saveName, int retention) {
        this.saveDir = saveDir == null ? Path.of("saves") : saveDir;
        this.saveName = Config.cleanSaveName(saveName);
        this.retention = Math.max(1, retention);
    }

    List<String> list() {
        try {
            if (!Files.isDirectory(saveDir)) return List.of("No save directory exists.");
            List<Path> files;
            try (var stream = Files.list(saveDir)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> ServerSaveArchiveNames.belongsTo(saveName, path))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            if (files.isEmpty()) return List.of("No save archives are available.");
            List<String> out = new ArrayList<>();
            for (Path path : files) {
                out.add(path.getFileName() + " | " + Files.size(path) + " bytes | "
                        + Files.getLastModifiedTime(path).toInstant());
            }
            return List.copyOf(out);
        } catch (IOException ex) {
            return List.of("Could not list backups: " + ex.getMessage());
        }
    }

    String create(String label) {
        return createVerified(label).message();
    }

    BackupCreation createVerified(String label) {
        Path current = currentPath();
        if (!Files.isRegularFile(current)) {
            return new BackupCreation(false, null, "Current save does not exist.");
        }
        String suffix = cleanLabel(label);
        String base = saveName + "-" + STAMP.format(Instant.now()) + (suffix.isBlank() ? "" : "-" + suffix);
        try {
            Files.createDirectories(saveDir);
            Path target = uniquePath(base);
            Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES);
            Verification verification = verify(target);
            if (!verification.valid()) {
                Files.deleteIfExists(target);
                return new BackupCreation(false, null, "Backup verification failed: " + verification.detail());
            }
            return new BackupCreation(true, target, "Created backup " + target.getFileName() + ".");
        } catch (IOException ex) {
            return new BackupCreation(false, null, "Could not create backup: " + ex.getMessage());
        }
    }

    String verifySelector(String selector) {
        Path target = resolveSelector(selector);
        if (target == null) return "Unknown backup selector: " + selector;
        Verification result = verify(target);
        return (result.valid() ? "Valid: " : "Invalid: ") + target.getFileName() + " | " + result.detail();
    }

    Verification verifyCurrent() {
        return verify(currentPath());
    }

    String restoreCurrent(Path backup) {
        Verification source = verify(backup);
        if (!source.valid()) return "Could not restore current save: recovery backup is invalid: " + source.detail();
        Path temp = null;
        try {
            Files.createDirectories(saveDir);
            temp = Files.createTempFile(saveDir, saveName + "-restore-", ".tmp");
            Files.copy(backup, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Verification staged = verify(temp);
            if (!staged.valid()) return "Could not restore current save: staged backup is invalid: " + staged.detail();
            moveReplace(temp, currentPath());
            temp = null;
            Verification restored = verify(currentPath());
            if (!restored.valid()) return "Could not restore current save: restored archive is invalid: " + restored.detail();
            return "Restored current save from " + backup.getFileName() + ".";
        } catch (IOException ex) {
            return "Could not restore current save: " + ex.getMessage();
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    String prune() {
        try {
            if (!Files.isDirectory(saveDir)) return "No save directory exists.";
            List<Path> backups = timestampedBackups();
            int remove = Math.max(0, backups.size() - retention);
            for (int i = 0; i < remove; i++) Files.deleteIfExists(backups.get(i));
            return "Pruned " + remove + " backup" + (remove == 1 ? "" : "s") + "; retained " + (backups.size() - remove) + ".";
        } catch (IOException ex) {
            return "Could not prune backups: " + ex.getMessage();
        }
    }

    static String tlsFingerprint(Config config) {
        Path dir = config == null || config.saveDir == null ? Path.of("saves") : config.saveDir;
        String name = config == null ? "server" : Config.cleanSaveName(config.saveName);
        Path path = dir.resolve(name + "-tls.p12");
        if (!Files.isRegularFile(path)) return "missing";
        try (InputStream input = Files.newInputStream(path)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, "starchem-local-tls".toCharArray());
            Certificate certificate = store.getCertificate("starchem-server");
            if (certificate == null) return "unavailable";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    Verification verify(Path path) {
        if (path == null || !Files.isRegularFile(path)) return new Verification(false, "file is missing");
        try {
            ServerSaveStore.ValidatedArchive archive = ServerSaveStore.validateArchive(path);
            return new Verification(true, "format " + archive.version() + " | schema and checksums passed");
        } catch (Exception ex) {
            return new Verification(false, ex.getMessage());
        }
    }

    private List<Path> timestampedBackups() throws IOException {
        try (var stream = Files.list(saveDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> ServerSaveArchiveNames.isTimestampedBackup(saveName, path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private Path resolveSelector(String selector) {
        if (selector == null || selector.isBlank() || "current".equalsIgnoreCase(selector)) return currentPath();
        if ("previous".equalsIgnoreCase(selector)) return previousPath();
        Path candidate = saveDir.resolve(Path.of(selector).getFileName().toString()).normalize();
        Path normalizedDir = saveDir.toAbsolutePath().normalize();
        if (!candidate.toAbsolutePath().getParent().equals(normalizedDir)) return null;
        return ServerSaveArchiveNames.belongsTo(saveName, candidate) ? candidate : null;
    }

    private Path uniquePath(String base) {
        Path candidate = saveDir.resolve(base + EXTENSION);
        int suffix = 1;
        while (Files.exists(candidate)) candidate = saveDir.resolve(base + "-" + suffix++ + EXTENSION);
        return candidate;
    }

    private Path currentPath() { return saveDir.resolve(saveName + "-current" + EXTENSION); }
    private Path previousPath() { return saveDir.resolve(saveName + "-previous" + EXTENSION); }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String cleanLabel(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return clean.length() <= 48 ? clean : clean.substring(0, 48);
    }

    record BackupCreation(boolean success, Path path, String message) { }
    record Verification(boolean valid, String detail) { }
}
