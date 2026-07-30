package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded encoding for authoritative team assignments and relationship overrides. */
final class DiplomacyStateWire {
    private static final int MAX_JSON_CHARS = 32 * 1024;
    private static final int MAX_FIELD_CHARS = 48 * 1024;
    private static final MiniJson.Limits LIMITS = new MiniJson.Limits(
            MAX_JSON_CHARS,
            8,
            8_192,
            256,
            2_048,
            32,
            true);

    private DiplomacyStateWire() { }

    static String encode(Map<String,Object> state) {
        if (state == null || state.isEmpty()) return "";
        String json = MiniJson.stringify(state);
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Diplomacy state exceeds the network limit.");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_FIELD_CHARS) {
            throw new IllegalArgumentException("Diplomacy packet field exceeds the network limit.");
        }
        return encoded;
    }

    static Map<String,Object> decode(String field) {
        if (field == null || field.isBlank()) return Map.of();
        if (field.length() > MAX_FIELD_CHARS) {
            throw new IllegalArgumentException("Diplomacy packet field exceeds the network limit.");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(field);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed diplomacy state encoding.", ex);
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Diplomacy state exceeds the network limit.");
        }
        final Object parsed;
        try {
            parsed = MiniJson.parse(json, LIMITS);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Malformed diplomacy state.", ex);
        }
        if (!(parsed instanceof Map<?,?> raw)) {
            throw new IllegalArgumentException("Diplomacy state must be an object.");
        }
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) {
            if (entry.getKey() == null) continue;
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(out);
    }
}
