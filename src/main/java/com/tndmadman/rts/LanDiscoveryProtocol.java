package com.tndmadman.rts;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class LanDiscoveryProtocol {
    static final int DISCOVERY_PORT = 50001;
    static final int MAX_PACKET_BYTES = 1024;
    private static final String MAGIC = "STARCHEM_DISCOVERY_V1";
    private static final String QUERY = MAGIC + "|QUERY";

    private LanDiscoveryProtocol() { }

    static byte[] queryBytes() {
        return QUERY.getBytes(StandardCharsets.UTF_8);
    }

    static boolean isQuery(byte[] data, int length) {
        if (data == null || length <= 0 || length > MAX_PACKET_BYTES) return false;
        return QUERY.equals(new String(data, 0, length, StandardCharsets.UTF_8));
    }

    static byte[] responseBytes(String serverName, int gamePort) {
        String payload = String.join("|",
                MAGIC,
                "RESPONSE",
                encode(clean(serverName, 80)),
                Integer.toString(gamePort),
                encode(clean(BuildInfo.version(), 40)),
                encode(clean(BuildInfo.shortCommit(), 20)));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PACKET_BYTES) throw new IllegalStateException("LAN discovery response is too large.");
        return bytes;
    }

    static DiscoveredServer parseResponse(byte[] data, int length, InetAddress source) {
        if (data == null || source == null || length <= 0 || length > MAX_PACKET_BYTES) return null;
        String[] parts = new String(data, 0, length, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 6 || !MAGIC.equals(parts[0]) || !"RESPONSE".equals(parts[1])) return null;
        try {
            String name = decode(parts[2], 80);
            int port = Integer.parseInt(parts[3]);
            String version = decode(parts[4], 40);
            String commit = decode(parts[5], 20);
            if (name.isBlank() || port < 1 || port > 65535 || version.isBlank()) return null;
            return new DiscoveredServer(source.getHostAddress(), port, name, version, commit,
                    BuildInfo.compatible(version), System.currentTimeMillis());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value, int maxChars) {
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("Discovery field is too large.");
        return clean(new String(bytes, StandardCharsets.UTF_8), maxChars);
    }

    private static String clean(String value, int maxChars) {
        String cleaned = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars);
    }

    record DiscoveredServer(String host, int port, String name, String version, String commit,
                            boolean compatible, long seenAtMillis) {
        String endpoint() { return host + ":" + port; }
        String displayLabel() {
            String build = commit == null || commit.isBlank() || "unknown".equals(commit) ? version : version + " (" + commit + ")";
            return name + " — " + endpoint() + " — " + build + (compatible ? "" : " — INCOMPATIBLE");
        }
    }
}
