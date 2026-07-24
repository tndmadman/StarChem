package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Focused validation for bounded archive expansion, strict UTF-8, and JSON parser limits. */
public final class ServerSaveResourceLimitsValidator {
    private static final byte[] EMPTY_OBJECT = "{}\n".getBytes(StandardCharsets.UTF_8);

    private ServerSaveResourceLimitsValidator() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("starchem-save-resource-validator-");
        try {
            validate(dir);
            System.out.println("StarChem save resource-limit validation passed.");
        } finally {
            deleteTree(dir);
        }
    }

    static void validate(Path dir) throws Exception {
        Files.createDirectories(dir);
        validateArchiveBounds(dir.resolve("archives"));
        validateParserBounds();
        validateCompanionFallback(dir.resolve("companions"));
    }

    private static void validateArchiveBounds(Path dir) throws Exception {
        Files.createDirectories(dir);

        Path oversizedManifest = dir.resolve("oversized-manifest.starchem-save");
        writeSingleEntry(oversizedManifest, "manifest.json", new byte[ServerSaveStore.MAX_MANIFEST_BYTES + 1]);
        expectInvalidArchive(oversizedManifest, "oversized manifest");

        Path unexpected = dir.resolve("unexpected.starchem-save");
        writeSingleEntry(unexpected, "extra.bin", new byte[] { 1 });
        expectInvalidArchive(unexpected, "unexpected entry");

        Path compressedBomb = dir.resolve("compression-ratio.starchem-save");
        byte[] repeated = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(repeated, (byte)' ');
        writeSingleEntry(compressedBomb, "galaxy.json", repeated);
        expectInvalidArchive(compressedBomb, "excessive compression ratio");

        byte[] malformedUtf8 = new byte[] { '{', '"', 'x', '"', ':', '"', (byte)0xC3, (byte)0x28, '"', '}', '\n' };
        Path malformed = dir.resolve("malformed-utf8.starchem-save");
        writeValidShape(malformed, malformedUtf8, EMPTY_OBJECT, EMPTY_OBJECT);
        expectInvalidArchive(malformed, "malformed UTF-8");

        Path valid = dir.resolve("valid.starchem-save");
        writeValidShape(valid, "{\"roster\":[]}\n".getBytes(StandardCharsets.UTF_8), EMPTY_OBJECT, EMPTY_OBJECT);
        ServerSaveStore.validateArchive(valid);
    }

    private static void validateParserBounds() {
        MiniJson.Limits small = new MiniJson.Limits(512, 4, 20, 8, 3, 6, true);
        MiniJson.parse("{\"a\":[1,2]}", small);

        expectInvalidJson("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}", small, "nesting limit");
        expectInvalidJson("{\"a\":\"123456789\"}", small, "string limit");
        expectInvalidJson("[1,2,3,4]", small, "collection limit");
        expectInvalidJson("1234567", small, "number limit");
        expectInvalidJson("{\"a\":1,\"b\":2,\"c\":3}", new MiniJson.Limits(512, 8, 5, 32, 8, 16, true), "token limit");
        expectInvalidJson("{\"a\":1,\"a\":2}", small, "duplicate keys");
        expectInvalidJson("{\"a\":\"\\ud800\"}", small, "unpaired surrogate");
        expectInvalidJson("{\"a\":\"\u0001\"}", small, "raw control character");
    }

    private static void validateCompanionFallback(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path current = dir.resolve("state.json");
        Path previous = dir.resolve("state-previous.json");
        Files.write(current, new byte[CompanionStateFiles.MAX_COMPANION_BYTES + 1]);
        Files.writeString(previous, "{}\n", StandardCharsets.UTF_8);

        CompanionLoad<Map<String,Object>> recovered = CompanionStateFiles.load(current, previous, "Test",
                ServerSaveResourceLimitsValidator::parseMap, ignored -> Map.of("restricted", true));
        require(recovered.status().recoveredPrevious(), "oversized current companion did not recover previous");

        Files.write(previous, new byte[CompanionStateFiles.MAX_COMPANION_BYTES + 1]);
        CompanionLoad<Map<String,Object>> restricted = CompanionStateFiles.load(current, previous, "Test",
                ServerSaveResourceLimitsValidator::parseMap, ignored -> Map.of("restricted", true));
        require(restricted.status().restricted(), "oversized current and previous companions did not restrict recovery");
    }

    private static Map<String,Object> parseMap(String text) throws IOException {
        Object parsed;
        try {
            parsed = MiniJson.parse(text);
        } catch (RuntimeException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map<?,?> raw)) throw new IOException("root is not an object");
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static void writeSingleEntry(Path path, String name, byte[] bytes) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            writeEntry(zip, name, bytes);
        }
    }

    private static void writeValidShape(Path path, byte[] players, byte[] galaxy, byte[] runtime) throws Exception {
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("saveFormatVersion", 2);
        manifest.put("playersSha256", sha256(players));
        manifest.put("galaxySha256", sha256(galaxy));
        manifest.put("runtimeSha256", sha256(runtime));
        byte[] manifestBytes = (MiniJson.stringify(manifest) + "\n").getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            writeEntry(zip, "manifest.json", manifestBytes);
            writeEntry(zip, "players.json", players);
            writeEntry(zip, "galaxy.json", galaxy);
            writeEntry(zip, "runtime.json", runtime);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void expectInvalidArchive(Path path, String description) throws Exception {
        try {
            ServerSaveStore.validateArchive(path);
            throw new IllegalStateException("accepted " + description);
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static void expectInvalidJson(String text, MiniJson.Limits limits, String description) {
        try {
            MiniJson.parse(text, limits);
            throw new IllegalStateException("accepted " + description);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
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
}
