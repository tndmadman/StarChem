package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

record GalaxyTopologyRules(int wanderingWormholePairs) {
    static final int DEFAULT_WANDERING_PAIRS = 4;
    static final int MAX_WANDERING_PAIRS = 32;

    GalaxyTopologyRules {
        if (wanderingWormholePairs < 0 || wanderingWormholePairs > MAX_WANDERING_PAIRS) {
            throw new IllegalArgumentException("wanderingWormholePairs must be between 0 and " + MAX_WANDERING_PAIRS + ".");
        }
    }

    static GalaxyTopologyRules load() {
        Path config = configuredPath();
        if (!Files.exists(config)) return new GalaxyTopologyRules(DEFAULT_WANDERING_PAIRS);
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(config)));
            Map<String,Object> topology = object(root.get("topology"));
            Object value = topology.get("wanderingWormholePairs");
            if (value == null) return new GalaxyTopologyRules(DEFAULT_WANDERING_PAIRS);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException("wanderingWormholePairs must be an integer.");
            }
            return new GalaxyTopologyRules(number.intValue());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not load galaxy topology config " + config + ": " + ex.getMessage(), ex);
        }
    }

    private static Path configuredPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return Path.of("config/galaxy.json");
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            Object path = files.get("galaxy");
            if (path instanceof String text && !text.isBlank()) return Path.of(text.trim());
        } catch (Exception ignored) { }
        return Path.of("config/galaxy.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
    }
}
