package com.tndmadman.rts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class MultiplayerCompatibility {
    static final int PROTOCOL_VERSION = 14;
    private static final Path DEFAULT_MANIFEST = Path.of("config/starchem.json");
    private static final String CONFIG_HASH_SCHEMA = "StarChemConfigFingerprint/v1";
    private static final int WIRE_FIELD_COUNT = 10;

    private MultiplayerCompatibility() { }

    static Descriptor local() { return LocalHolder.VALUE; }

    static String versionClientHandshake(String message) {
        if (message == null) return null;
        String device = ClientDeviceIdentityStore.deviceId();
        String deviceFields = ServerDeviceIdentity.valid(device) ? "|DEVICE|" + device : "";
        if (message.startsWith("JOIN|")) {
            return "JOIN_V1|" + message.substring("JOIN|".length()) + deviceFields + '|' + local().wireFields();
        }
        if (message.startsWith("RESUME|")) {
            return "RESUME_V1|" + message.substring("RESUME|".length()) + deviceFields + '|' + local().wireFields();
        }
        return message;
    }

    static String versionServerWelcome(String message) {
        if (message == null || !message.startsWith("WELCOME|")) return message;
        return message + '|' + local().wireFields();
    }

    static WireResult inspectClientHandshake(String message) {
        if (message == null) return WireResult.pass(null);
        if (message.startsWith("JOIN|") || message.startsWith("RESUME|")) {
            String reason = "Connection refused: client uses the legacy multiplayer handshake. "
                    + "Client compatibility values are unavailable; server " + local().summary() + '.';
            return WireResult.reject(denialPayload("LEGACY_HANDSHAKE", reason));
        }

        String command;
        String legacyCommand;
        int compatibilityStart;
        if (message.startsWith("JOIN_V1|")) {
            command = "JOIN_V1";
            legacyCommand = "JOIN";
        } else if (message.startsWith("RESUME_V1|")) {
            command = "RESUME_V1";
            legacyCommand = "RESUME";
        } else {
            return WireResult.pass(message);
        }

        try {
            String[] parts = message.split("\\|", -1);
            if (!command.equals(parts[0])) throw new WireFormatException("MALFORMED_HANDSHAKE", "invalid command");
            compatibilityStart = parts.length - WIRE_FIELD_COUNT;
            int minimumPayloadFields = "JOIN_V1".equals(command) ? 4 : 5;
            if (compatibilityStart < minimumPayloadFields) throw new WireFormatException("MISSING_FIELDS", "missing compatibility fields");
            Descriptor client = Descriptor.parse(parts, compatibilityStart);
            Decision decision = compare(client, local());
            if (!decision.compatible()) return WireResult.reject(denialPayload(decision.code(), decision.message()));
            int payloadEnd = compatibilityStart;
            if (payloadEnd >= 2 && "DEVICE".equals(parts[payloadEnd - 2])) {
                if (!ServerDeviceIdentity.valid(parts[payloadEnd - 1])) {
                    throw new WireFormatException("MALFORMED_HANDSHAKE", "client device identifier is invalid");
                }
                payloadEnd -= 2;
            }
            String normalized = legacyCommand + '|' + String.join("|", Arrays.copyOfRange(parts, 1, payloadEnd));
            return WireResult.accept(normalized);
        } catch (WireFormatException ex) {
            String reason = "Connection refused: malformed multiplayer handshake (" + ex.getMessage() + "). "
                    + "Server " + local().summary() + '.';
            return WireResult.reject(denialPayload(ex.code, reason));
        }
    }

    static WireResult inspectServerWelcome(String message) {
        if (message == null) return WireResult.pass(null);
        if (message.startsWith("COMPAT_DENIED|")) {
            String[] parts = message.split("\\|", 3);
            String reason = parts.length >= 3 && !parts[2].isBlank()
                    ? parts[2]
                    : "Connection refused: multiplayer compatibility check failed.";
            return WireResult.reject(reason);
        }
        if (!message.startsWith("WELCOME|")) return WireResult.pass(message);

        try {
            String[] parts = message.split("\\|", -1);
            Descriptor server = Descriptor.parse(parts, 11);
            Decision decision = compare(local(), server);
            if (!decision.compatible()) return WireResult.reject(decision.message());
            String normalized = "WELCOME|" + String.join("|", Arrays.copyOfRange(parts, 1, 11));
            return WireResult.accept(normalized);
        } catch (WireFormatException ex) {
            String reason = "Connection refused: server sent an older or malformed multiplayer handshake ("
                    + ex.getMessage() + "). Client " + local().summary() + "; server compatibility values unavailable.";
            return WireResult.reject(reason);
        }
    }

    static Decision compare(Descriptor client, Descriptor server) {
        if (client == null || server == null) {
            return new Decision(false, "MISSING_FIELDS", "Connection refused: compatibility information is missing.");
        }
        if (client.protocolVersion() != server.protocolVersion()) {
            return mismatch("PROTOCOL_MISMATCH", "network protocol", client, server);
        }
        if (!client.applicationVersion().equals(server.applicationVersion())) {
            return mismatch("APPLICATION_MISMATCH", "application version", client, server);
        }
        if (client.rulesVersion() != server.rulesVersion()) {
            return mismatch("RULES_MISMATCH", "rules version", client, server);
        }
        if (!client.configHash().equals(server.configHash())) {
            return mismatch("CONFIG_MISMATCH", "configuration fingerprint", client, server);
        }
        String detail = client.buildCommit().equals(server.buildCommit())
                ? "Compatible multiplayer build."
                : "Compatible multiplayer release; build commits differ but protocol, application version, rules, and configuration match.";
        return new Decision(true, "MATCH", detail);
    }

    private static Decision mismatch(String code, String label, Descriptor client, Descriptor server) {
        String message = "Connection refused: " + label + " mismatch. Client " + client.summary()
                + "; server " + server.summary() + '.';
        return new Decision(false, code, message);
    }

    private static String denialPayload(String code, String message) {
        return "COMPAT_DENIED|" + packetPart(code) + '|' + packetPart(message);
    }

    private static Descriptor load(Path manifest) {
        try {
            Path normalizedManifest = safeRelativePath(manifest.toString());
            byte[] manifestBytes = requireFile(normalizedManifest);
            Object parsed = MiniJson.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> rawRoot)) {
                throw new IllegalStateException("config/starchem.json must contain a JSON object");
            }
            Map<String,Object> root = stringMap(rawRoot);
            int rulesVersion = positiveInteger(root.get("rulesVersion"), "rulesVersion");
            Object filesValue = root.get("files");
            if (!(filesValue instanceof Map<?,?> rawFiles) || rawFiles.isEmpty()) {
                throw new IllegalStateException("config/starchem.json files section is missing or empty");
            }

            Set<String> paths = new TreeSet<>();
            paths.add(portable(normalizedManifest));
            collectPaths(stringMap(rawFiles), paths);
            String configHash = hashFiles(paths);
            return new Descriptor(PROTOCOL_VERSION, BuildInfo.version(), BuildInfo.commit(), rulesVersion, configHash);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("could not load multiplayer compatibility data: " + ex.getMessage(), ex);
        }
    }

    private static void collectPaths(Object value, Set<String> paths) {
        if (value instanceof String text) {
            if (text.isBlank()) throw new IllegalStateException("configuration manifest contains a blank file path");
            paths.add(portable(safeRelativePath(text)));
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) collectPaths(item, paths);
            return;
        }
        if (value instanceof Map<?,?> map) {
            for (Object item : map.values()) collectPaths(item, paths);
            return;
        }
        throw new IllegalStateException("configuration manifest contains a non-path file entry");
    }

    private static String hashFiles(Set<String> paths) {
        MessageDigest digest = sha256();
        digest.update(CONFIG_HASH_SCHEMA.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
        for (String portablePath : paths) {
            Path path = safeRelativePath(portablePath);
            byte[] bytes = requireFile(path);
            byte[] pathBytes = portablePath.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(pathBytes.length).array());
            digest.update(pathBytes);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
            digest.update(bytes);
        }
        return hex(digest.digest());
    }

    private static byte[] requireFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) throw new IllegalStateException("missing configuration file: " + portable(path));
            return Files.readAllBytes(path);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("could not read configuration file " + portable(path) + ": " + ex.getMessage(), ex);
        }
    }

    private static Path safeRelativePath(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalStateException("configuration path is blank");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalStateException("configuration path escapes the release directory: " + raw);
        }
        return path;
    }

    private static Map<String,Object> stringMap(Map<?,?> source) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : source.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static int positiveInteger(Object value, String label) {
        if (!(value instanceof Number number)) throw new IllegalStateException(label + " must be a positive integer");
        double numeric = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(numeric) || numeric != integer || integer <= 0) {
            throw new IllegalStateException(label + " must be a positive integer");
        }
        return integer;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }

    private static String packetPart(String value) {
        return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    enum WireAction { PASS, ACCEPT, REJECT }

    record WireResult(WireAction action, String message, String detail) {
        static WireResult pass(String message) { return new WireResult(WireAction.PASS, message, ""); }
        static WireResult accept(String message) { return new WireResult(WireAction.ACCEPT, message, ""); }
        static WireResult reject(String detail) { return new WireResult(WireAction.REJECT, null, detail); }
    }

    record Decision(boolean compatible, String code, String message) { }

    record Descriptor(int protocolVersion, String applicationVersion, String buildCommit,
                      int rulesVersion, String configHash) {
        Descriptor {
            applicationVersion = cleanWireValue(applicationVersion, "application version");
            buildCommit = cleanWireValue(buildCommit, "build commit");
            configHash = cleanHash(configHash);
            if (protocolVersion <= 0) throw new IllegalArgumentException("protocol version must be positive");
            if (rulesVersion <= 0) throw new IllegalArgumentException("rules version must be positive");
        }

        String wireFields() {
            return "PROTO|" + protocolVersion + "|APP|" + applicationVersion + "|BUILD|" + buildCommit
                    + "|RULES|" + rulesVersion + "|CFG|" + configHash;
        }

        String summary() {
            return "[app=" + applicationVersion + ", build=" + buildCommit + ", protocol=" + protocolVersion
                    + ", rules=" + rulesVersion + ", config=" + configHash + ']';
        }

        static Descriptor parse(String[] parts, int start) {
            if (parts == null || parts.length != start + WIRE_FIELD_COUNT) {
                throw new WireFormatException("MISSING_FIELDS", "required version fields are missing");
            }
            requireMarker(parts, start, "PROTO");
            requireMarker(parts, start + 2, "APP");
            requireMarker(parts, start + 4, "BUILD");
            requireMarker(parts, start + 6, "RULES");
            requireMarker(parts, start + 8, "CFG");
            try {
                return new Descriptor(Integer.parseInt(parts[start + 1]), parts[start + 3], parts[start + 5],
                        Integer.parseInt(parts[start + 7]), parts[start + 9]);
            } catch (NumberFormatException ex) {
                throw new WireFormatException("MALFORMED_HANDSHAKE", "protocol or rules version is not numeric");
            } catch (IllegalArgumentException ex) {
                throw new WireFormatException("MALFORMED_HANDSHAKE", ex.getMessage());
            }
        }

        private static void requireMarker(String[] parts, int index, String expected) {
            if (!expected.equals(parts[index])) {
                throw new WireFormatException("MALFORMED_HANDSHAKE", "expected " + expected + " marker");
            }
        }

        private static String cleanWireValue(String value, String label) {
            String clean = value == null ? "" : value.trim();
            if (clean.isBlank() || clean.length() > 128 || clean.indexOf('|') >= 0
                    || clean.indexOf('\n') >= 0 || clean.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(label + " is invalid");
            }
            return clean;
        }

        private static String cleanHash(String value) {
            String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (clean.length() != 64) throw new IllegalArgumentException("configuration fingerprint is invalid");
            for (int i = 0; i < clean.length(); i++) {
                char c = clean.charAt(i);
                if (!Character.isDigit(c) && (c < 'a' || c > 'f')) {
                    throw new IllegalArgumentException("configuration fingerprint is invalid");
                }
            }
            return clean;
        }
    }

    private static final class LocalHolder {
        private static final Descriptor VALUE = load(DEFAULT_MANIFEST);
        private LocalHolder() { }
    }

    private static final class WireFormatException extends RuntimeException {
        final String code;
        WireFormatException(String code, String message) {
            super(message);
            this.code = code == null || code.isBlank() ? "MALFORMED_HANDSHAKE" : code;
        }
    }
}
