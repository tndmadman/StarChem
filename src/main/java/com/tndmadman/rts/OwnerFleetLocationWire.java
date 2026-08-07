package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class OwnerFleetLocationWire {
    private static final String PREFIX = "OWNER_FLEET";
    private static final int MAX_UNITS = 10_000;
    private static final int MAX_TEXT = 128;

    private OwnerFleetLocationWire() { }

    static String encode(Map<String, String> locations) {
        Map<String, String> sorted = new TreeMap<>();
        if (locations != null) sorted.putAll(locations);
        if (sorted.size() > MAX_UNITS) throw new IllegalArgumentException("Owner fleet location state exceeds safe limits.");

        StringBuilder out = new StringBuilder(PREFIX);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String unitKey = clean(entry.getKey());
            String systemId = clean(entry.getValue());
            if (unitKey.isBlank() || systemId.isBlank()) continue;
            out.append('|').append(token(unitKey)).append(',').append(token(systemId));
        }
        return out.toString();
    }

    static Map<String, String> decode(String message) {
        if (message == null || (!message.equals(PREFIX) && !message.startsWith(PREFIX + "|"))) {
            throw new SnapshotDecodeException("Malformed owner fleet location packet.");
        }
        String[] parts = message.split("\\|", -1);
        if (parts.length - 1 > MAX_UNITS) {
            throw new SnapshotDecodeException("Owner fleet location packet exceeds safe limits.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String[] row = parts[i].split(",", -1);
            if (row.length != 2) throw new SnapshotDecodeException("Malformed owner fleet location row.");
            String unitKey = text(row[0]);
            String systemId = text(row[1]);
            if (unitKey.isBlank() || systemId.isBlank() || unitKey.length() > MAX_TEXT || systemId.length() > MAX_TEXT) {
                throw new SnapshotDecodeException("Malformed owner fleet location identity.");
            }
            if (out.putIfAbsent(unitKey, systemId) != null) {
                throw new SnapshotDecodeException("Duplicate owner fleet unit key.");
            }
        }
        return Map.copyOf(out);
    }

    private static String token(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clean(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String token) {
        try {
            if (token == null || token.isBlank()) return "";
            return clean(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new SnapshotDecodeException("Malformed owner fleet location text token.");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
