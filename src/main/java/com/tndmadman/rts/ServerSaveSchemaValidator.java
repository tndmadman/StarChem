package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Validates strict save schemas, checksum enforcement, and pre-promotion recovery safety. */
public final class ServerSaveSchemaValidator {
    private static final byte[] PLAYERS = json(Map.of("roster", java.util.List.of()));
    private static final byte[] GALAXY = json(Map.of());
    private static final byte[] RUNTIME = json(Map.of());

    private ServerSaveSchemaValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem strict save schema validation passed.");
    }

    static void validate() throws Exception {
        Path dir = Files.createTempDirectory("starchem-save-schema-validator-");
        try {
            validateAcceptedSchemas(dir.resolve("accepted"));
            validateRejectedSchemas(dir.resolve("rejected"));
            validateBackupConsistency(dir.resolve("backup"));
            validatePromotionOrdering(dir.resolve("promotion"));
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateAcceptedSchemas(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path v2 = dir.resolve("valid-v2.starchem-save");
        writeV2(v2, manifestV2(), true, true, true, null);
        ServerSaveStore.ValidatedArchive validated = ServerSaveStore.validateArchive(v2);
        require(validated.version() == 2, "valid v2 archive reported the wrong version");

        Path v1 = dir.resolve("valid-v1.starchem-save");
        Map<String,Object> legacyManifest = new LinkedHashMap<>();
        legacyManifest.put("saveFormatVersion", 1);
        writeArchive(v1, legacyManifest, true, true, false, null);
        ServerSaveStore.ValidatedArchive legacy = ServerSaveStore.validateArchive(v1);
        require(legacy.version() == 1, "explicit v1 archive was not accepted");
        require(legacy.runtime().isEmpty(), "v1 archive without runtime did not receive an empty runtime document");
    }

    private static void validateRejectedSchemas(Path dir) throws Exception {
        Files.createDirectories(dir);

        Map<String,Object> missingVersion = manifestV2();
        missingVersion.remove("saveFormatVersion");
        expectInvalid(writeV2(dir.resolve("missing-version.starchem-save"), missingVersion, true, true, true, null),
                "missing saveFormatVersion");

        Map<String,Object> stringVersion = manifestV2();
        stringVersion.put("saveFormatVersion", "2");
        expectInvalid(writeV2(dir.resolve("string-version.starchem-save"), stringVersion, true, true, true, null),
                "string saveFormatVersion");

        Map<String,Object> fractionalVersion = manifestV2();
        fractionalVersion.put("saveFormatVersion", 2.5);
        expectInvalid(writeV2(dir.resolve("fractional-version.starchem-save"), fractionalVersion, true, true, true, null),
                "fractional saveFormatVersion");

        Map<String,Object> zeroVersion = manifestV2();
        zeroVersion.put("saveFormatVersion", 0);
        expectInvalid(writeV2(dir.resolve("zero-version.starchem-save"), zeroVersion, true, true, true, null),
                "zero saveFormatVersion");

        Map<String,Object> omittedHash = manifestV2();
        omittedHash.remove("playersSha256");
        expectInvalid(writeV2(dir.resolve("omitted-hash.starchem-save"), omittedHash, true, true, true, null),
                "omitted v2 checksum");

        Map<String,Object> blankHash = manifestV2();
        blankHash.put("playersSha256", "");
        expectInvalid(writeV2(dir.resolve("blank-hash.starchem-save"), blankHash, true, true, true, null),
                "blank v2 checksum");

        Map<String,Object> malformedHash = manifestV2();
        malformedHash.put("playersSha256", "xyz");
        expectInvalid(writeV2(dir.resolve("malformed-hash.starchem-save"), malformedHash, true, true, true, null),
                "malformed v2 checksum");

        Map<String,Object> wrongHash = manifestV2();
        wrongHash.put("playersSha256", "0".repeat(64));
        expectInvalid(writeV2(dir.resolve("wrong-hash.starchem-save"), wrongHash, true, true, true, null),
                "incorrect v2 checksum");

        expectInvalid(writeV2(dir.resolve("missing-players.starchem-save"), manifestV2(), false, true, true, null),
                "missing players.json");
        expectInvalid(writeV2(dir.resolve("missing-galaxy.starchem-save"), manifestV2(), true, false, true, null),
                "missing galaxy.json");
        expectInvalid(writeV2(dir.resolve("missing-runtime.starchem-save"), manifestV2(), true, true, false, null),
                "missing runtime.json");

        Path duplicate = writeV2(dir.resolve("duplicate-entry.starchem-save"), manifestV2(), true, true, true,
                new ExtraEntry("player2.json", PLAYERS));
        replaceAscii(duplicate, "player2.json", "players.json");
        expectInvalid(duplicate, "duplicate required entry");

        Path truncated = writeV2(dir.resolve("truncated.starchem-save"), manifestV2(), true, true, true, null);
        byte[] complete = Files.readAllBytes(truncated);
        Files.write(truncated, Arrays.copyOf(complete, complete.length - 12));
        expectInvalid(truncated, "truncated archive");

        Path nonObject = dir.resolve("non-object.starchem-save");
        writeArchive(nonObject, manifestV2(), true, true, true, null);
        replaceEntry(nonObject, "players.json", "[]\n".getBytes(StandardCharsets.UTF_8));
        expectInvalid(nonObject, "non-object JSON document");
    }

    private static void validateBackupConsistency(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path current = dir.resolve("schema-current.starchem-save");
        writeV2(current, manifestV2(), true, true, true, null);
        ServerBackupAdmin admin = new ServerBackupAdmin(dir, "schema", 5);
        require(admin.verifyCurrent().valid(), "backup verifier rejected a valid archive");

        Map<String,Object> invalidManifest = manifestV2();
        invalidManifest.put("runtimeSha256", "");
        writeV2(current, invalidManifest, true, true, true, null);
        require(!admin.verifyCurrent().valid(), "backup verifier accepted a schema rejected by normal loading");
    }

    private static void validatePromotionOrdering(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path current = dir.resolve("promotion-current.starchem-save");
        Path previous = dir.resolve("promotion-previous.starchem-save");
        byte[] currentBytes = "current-before".getBytes(StandardCharsets.UTF_8);
        byte[] previousBytes = "previous-before".getBytes(StandardCharsets.UTF_8);
        Files.write(current, currentBytes);
        Files.write(previous, previousBytes);

        ServerSaveStore store = new ServerSaveStore(dir, "promotion", 5);
        Path invalidTemp = dir.resolve("invalid-new.tmp");
        writeV2(invalidTemp, manifestV2(), true, true, false, null);
        try {
            store.promoteVerified(invalidTemp);
            throw new IllegalStateException("invalid temporary archive was promoted");
        } catch (IOException expected) {
            // Expected: validation must happen before backup rotation or recovery-file replacement.
        }
        require(Arrays.equals(currentBytes, Files.readAllBytes(current)), "failed verification changed current save");
        require(Arrays.equals(previousBytes, Files.readAllBytes(previous)), "failed verification changed previous save");
        try (var stream = Files.list(dir)) {
            require(stream.noneMatch(path -> ServerSaveArchiveNames.isTimestampedBackup("promotion", path)),
                    "failed verification created a timestamped backup");
        }

        Path validTemp = dir.resolve("valid-new.tmp");
        writeV2(validTemp, manifestV2(), true, true, true, null);
        store.promoteVerified(validTemp);
        ServerSaveStore.validateArchive(current);
        require(Arrays.equals(currentBytes, Files.readAllBytes(previous)),
                "successful promotion did not move the former current save to previous");
    }

    private static Map<String,Object> manifestV2() throws Exception {
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("saveFormatVersion", 2);
        manifest.put("playersSha256", sha256(PLAYERS));
        manifest.put("galaxySha256", sha256(GALAXY));
        manifest.put("runtimeSha256", sha256(RUNTIME));
        return manifest;
    }

    private static Path writeV2(Path path, Map<String,Object> manifest, boolean players, boolean galaxy,
                                boolean runtime, ExtraEntry extra) throws Exception {
        writeArchive(path, manifest, players, galaxy, runtime, extra);
        return path;
    }

    private static void writeArchive(Path path, Map<String,Object> manifest, boolean players, boolean galaxy,
                                     boolean runtime, ExtraEntry extra) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            writeEntry(zip, "manifest.json", json(manifest));
            if (players) writeEntry(zip, "players.json", PLAYERS);
            if (galaxy) writeEntry(zip, "galaxy.json", GALAXY);
            if (runtime) writeEntry(zip, "runtime.json", RUNTIME);
            if (extra != null) writeEntry(zip, extra.name(), extra.bytes());
        }
    }

    private static void replaceEntry(Path archive, String entryName, byte[] replacement) throws Exception {
        Path temp = archive.resolveSibling(archive.getFileName() + ".rewrite");
        try (java.util.zip.ZipFile input = new java.util.zip.ZipFile(archive.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] bytes;
                if (entryName.equals(entry.getName())) {
                    bytes = replacement;
                } else {
                    try (var stream = input.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }
                }
                writeEntry(output, entry.getName(), bytes);
            }
        }
        Files.move(temp, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void replaceAscii(Path path, String from, String to) throws Exception {
        require(from.length() == to.length(), "replacement names must be the same length");
        byte[] bytes = Files.readAllBytes(path);
        byte[] needle = from.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = to.getBytes(StandardCharsets.US_ASCII);
        int replacements = 0;
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            System.arraycopy(replacement, 0, bytes, i, replacement.length);
            replacements++;
            i += needle.length - 1;
        }
        require(replacements >= 2, "duplicate-entry fixture did not update local and central names");
        Files.write(path, bytes);
    }

    private static void expectInvalid(Path path, String description) throws Exception {
        try {
            ServerSaveStore.validateArchive(path);
            throw new IllegalStateException("accepted " + description);
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static byte[] json(Map<String,Object> value) {
        return (MiniJson.stringify(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record ExtraEntry(String name, byte[] bytes) { }
}
