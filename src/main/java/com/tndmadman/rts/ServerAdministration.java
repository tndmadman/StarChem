package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

record ServerAccessPolicy(boolean maintenance, String maintenanceReason, int maxSlots, String motd) {
    static final int MAX_SLOTS = 10_000;
    static final int MAX_TEXT = 512;

    ServerAccessPolicy {
        maintenanceReason = clean(maintenanceReason);
        maxSlots = Math.max(0, Math.min(MAX_SLOTS, maxSlots));
        motd = clean(motd);
    }

    static ServerAccessPolicy open() { return new ServerAccessPolicy(false, "", 0, ""); }

    ServerAccessPolicy withMaintenance(boolean enabled, String reason) {
        return new ServerAccessPolicy(enabled, enabled ? reason : "", maxSlots, motd);
    }

    ServerAccessPolicy withMaxSlots(int slots) {
        return new ServerAccessPolicy(maintenance, maintenanceReason, slots, motd);
    }

    ServerAccessPolicy withMotd(String message) {
        return new ServerAccessPolicy(maintenance, maintenanceReason, maxSlots, message);
    }

    String maintenanceMessage() {
        return maintenanceReason.isBlank() ? "Server is temporarily in maintenance mode." : maintenanceReason;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= MAX_TEXT ? clean : clean.substring(0, MAX_TEXT);
    }
}

final class ServerAdminStore {
    private final Path path;

    ServerAdminStore(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        this.path = dir.resolve(Config.cleanSaveName(saveName) + "-admin.json");
    }

    Path path() { return path; }

    ServerAccessPolicy load() {
        if (!Files.isRegularFile(path)) return ServerAccessPolicy.open();
        try {
            Object parsed = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> raw)) throw new IOException("admin file root is not an object");
            Map<String,Object> map = stringMap(raw);
            boolean maintenance = booleanValue(map.get("maintenance"));
            String reason = stringValue(map.get("maintenanceReason"));
            int maxSlots = integerValue(map.get("maxSlots"));
            String motd = stringValue(map.get("motd"));
            return new ServerAccessPolicy(maintenance, reason, maxSlots, motd);
        } catch (Exception ex) {
            System.err.println("Could not load server administration settings: " + ex.getMessage());
            return ServerAccessPolicy.open();
        }
    }

    void save(ServerAccessPolicy policy) throws IOException {
        ServerAccessPolicy safe = policy == null ? ServerAccessPolicy.open() : policy;
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("version", 1);
        map.put("maintenance", safe.maintenance());
        map.put("maintenanceReason", safe.maintenanceReason());
        map.put("maxSlots", safe.maxSlots());
        map.put("motd", safe.motd());
        map.put("updatedAt", Instant.now().toString());
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, MiniJson.stringify(map) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String,Object> stringMap(Map<?,?> raw) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static int integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ex) { return 0; }
    }

    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
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
                        .filter(path -> path.getFileName().toString().startsWith(saveName + "-"))
                        .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
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
        Path current = currentPath();
        if (!Files.isRegularFile(current)) return "Current save does not exist.";
        String suffix = cleanLabel(label);
        String base = saveName + "-" + STAMP.format(Instant.now()) + (suffix.isBlank() ? "" : "-" + suffix);
        try {
            Files.createDirectories(saveDir);
            Path target = uniquePath(base);
            Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES);
            Verification verification = verify(target);
            if (!verification.valid()) {
                Files.deleteIfExists(target);
                return "Backup verification failed: " + verification.detail();
            }
            return "Created backup " + target.getFileName() + ".";
        } catch (IOException ex) {
            return "Could not create backup: " + ex.getMessage();
        }
    }

    String verifySelector(String selector) {
        Path target = resolveSelector(selector);
        if (target == null) return "Unknown backup selector: " + selector;
        Verification result = verify(target);
        return (result.valid() ? "Valid: " : "Invalid: ") + target.getFileName() + " | " + result.detail();
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

    private Verification verify(Path path) {
        if (path == null || !Files.isRegularFile(path)) return new Verification(false, "file is missing");
        try {
            Map<String,byte[]> entries = readEntries(path);
            Map<String,Object> manifest = parseObject(entries.get("manifest.json"), "manifest.json");
            verifyChecksum(entries, manifest, "players.json", "playersSha256");
            verifyChecksum(entries, manifest, "galaxy.json", "galaxySha256");
            verifyChecksum(entries, manifest, "runtime.json", "runtimeSha256");
            Object version = manifest.get("saveFormatVersion");
            return new Verification(true, "format " + (version == null ? "unknown" : version) + " | checksums passed");
        } catch (Exception ex) {
            return new Verification(false, ex.getMessage());
        }
    }

    private List<Path> timestampedBackups() throws IOException {
        try (var stream = Files.list(saveDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(saveName + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .filter(path -> !path.equals(currentPath()))
                    .filter(path -> !path.equals(previousPath()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private Path resolveSelector(String selector) {
        if (selector == null || selector.isBlank() || "current".equalsIgnoreCase(selector)) return currentPath();
        if ("previous".equalsIgnoreCase(selector)) return previousPath();
        Path candidate = saveDir.resolve(Path.of(selector).getFileName().toString()).normalize();
        if (!candidate.getParent().equals(saveDir.normalize())) return null;
        return candidate;
    }

    private Path uniquePath(String base) {
        Path candidate = saveDir.resolve(base + EXTENSION);
        int suffix = 1;
        while (Files.exists(candidate)) candidate = saveDir.resolve(base + "-" + suffix++ + EXTENSION);
        return candidate;
    }

    private Path currentPath() { return saveDir.resolve(saveName + "-current" + EXTENSION); }
    private Path previousPath() { return saveDir.resolve(saveName + "-previous" + EXTENSION); }

    private static String cleanLabel(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return clean.length() <= 48 ? clean : clean.substring(0, 48);
    }

    private static Map<String,byte[]> readEntries(Path path) throws IOException {
        Map<String,byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) out.put(entry.getName(), zip.readAllBytes());
            }
        }
        return out;
    }

    private static Map<String,Object> parseObject(byte[] bytes, String name) throws IOException {
        if (bytes == null) throw new IOException("archive is missing " + name);
        Object parsed;
        try { parsed = MiniJson.parse(new String(bytes, StandardCharsets.UTF_8)); }
        catch (RuntimeException ex) { throw new IOException("could not parse " + name + ": " + ex.getMessage(), ex); }
        if (!(parsed instanceof Map<?,?> raw)) throw new IOException(name + " is not an object");
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static void verifyChecksum(Map<String,byte[]> entries, Map<String,Object> manifest,
                                       String entryName, String field) throws Exception {
        String expected = manifest.get(field) == null ? "" : String.valueOf(manifest.get(field));
        if (expected.isBlank()) return;
        byte[] bytes = entries.get(entryName);
        if (bytes == null) throw new IOException("archive is missing " + entryName);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!expected.equalsIgnoreCase(actual)) throw new IOException(entryName + " checksum mismatch");
    }

    private record Verification(boolean valid, String detail) { }
}
