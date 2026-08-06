package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded JSON field encoding for fit commands and catalogs. */
final class FitStateWire {
    private static final int MAX_JSON_CHARS = 340 * 1024;
    private static final int MAX_FIELD_CHARS = 460 * 1024;
    private static final MiniJson.Limits LIMITS = new MiniJson.Limits(
            MAX_JSON_CHARS, 12, 100_000, 2_048, 65_536, 128, true);

    private FitStateWire() { }

    static String encode(Map<String,Object> value) {
        Map<String,Object> safe = value == null ? Map.of() : value;
        String json = MiniJson.stringify(safe);
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Fit data exceeds the network limit.");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_FIELD_CHARS) throw new IllegalArgumentException("Fit packet exceeds the network limit.");
        return encoded;
    }

    static Map<String,Object> decode(String value) {
        if (value == null || value.isBlank()) return Map.of();
        if (value.length() > MAX_FIELD_CHARS) throw new IllegalArgumentException("Fit packet exceeds the network limit.");
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Malformed fit packet encoding.", ex); }
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Fit data exceeds the network limit.");
        Object parsed = MiniJson.parse(json, LIMITS);
        if (!(parsed instanceof Map<?,?> raw)) throw new IllegalArgumentException("Fit data must be an object.");
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) if (entry.getKey() != null) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return Collections.unmodifiableMap(out);
    }
}
