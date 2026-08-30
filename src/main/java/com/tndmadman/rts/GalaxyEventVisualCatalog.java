package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Strict, data-only visual profiles for discovered galaxy events. */
final class GalaxyEventVisualCatalog {
    static final Path CONFIG_PATH = Path.of("config/event-visuals.json");
    static final int SCHEMA_VERSION = 1;
    private static GalaxyEventVisualCatalog cached;

    private final Map<String, GalaxyEventVisualStyle> byId;

    private GalaxyEventVisualCatalog(Map<String, GalaxyEventVisualStyle> byId) {
        this.byId = Map.copyOf(byId);
    }

    static synchronized GalaxyEventVisualStyle visual(String definitionId) {
        if (cached == null) cached = load(CONFIG_PATH);
        if (definitionId == null || definitionId.isBlank()) return GalaxyEventVisualStyle.NONE;
        return cached.byId.getOrDefault(definitionId, GalaxyEventVisualStyle.NONE);
    }

    static synchronized GalaxyEventVisualCatalog loadForValidation(Path path) {
        return load(path);
    }

    static synchronized void reload() {
        cached = load(CONFIG_PATH);
    }

    int size() { return byId.size(); }
    boolean contains(String id) { return byId.containsKey(id); }

    private static GalaxyEventVisualCatalog load(Path path) {
        try {
            Map<String,Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(path)));
            int version = ServerSaveStore.intValue(root, "version", -1);
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("config/event-visuals.json schema version must be "
                        + SCHEMA_VERSION + " (found " + version + ").");
            }
            Map<String,GalaxyEventVisualStyle> index = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("definitions"))) {
                Map<String,Object> row = ServerSaveStore.object(raw);
                String id = text(row, "id", "");
                if (id.isBlank()) throw new IllegalStateException("Galaxy event visual id is required.");
                GalaxyEventVisualStyle style = parse(id, row);
                if (index.putIfAbsent(id, style) != null) {
                    throw new IllegalStateException("Duplicate galaxy event visual id: " + id);
                }
            }
            if (index.isEmpty()) throw new IllegalStateException("Galaxy event visual catalog is empty.");
            return new GalaxyEventVisualCatalog(index);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Could not load galaxy event visuals: " + ex.getMessage(), ex);
        }
    }

    private static GalaxyEventVisualStyle parse(String id, Map<String,Object> row) {
        boolean enabled = ServerSaveStore.boolValue(row, "enabled", true);
        EventVisualArea area = enumValue(EventVisualArea.class, text(row, "effectArea", "SYSTEM"),
                "effectArea for " + id);
        // Gameplay storm modifiers are currently system-wide. Refuse a visual-only radius
        // so the client can never imply a safe area that the authoritative simulation does not honor.
        if (area != EventVisualArea.SYSTEM) {
            throw new IllegalStateException("Galaxy event visual " + id + " must use SYSTEM effectArea.");
        }
        EventParticleType particleType = enumValue(EventParticleType.class,
                text(row, "particleType", "DUST"), "particleType for " + id);
        double sizeMin = number(row, "particleSizeMin", 1.0, 0.2, 64.0);
        double sizeMax = number(row, "particleSizeMax", 3.0, sizeMin, 96.0);
        return new GalaxyEventVisualStyle(
                enabled,
                area,
                rgb(text(row, "tintColor", "#000000"), id, "tintColor"),
                number(row, "tintOpacity", 0, 0, 0.75),
                particleType,
                rgb(text(row, "particleColor", "#FFFFFF"), id, "particleColor"),
                number(row, "particleOpacity", 0.35, 0, 1),
                integer(row, "particleCount", 0, 0, 256),
                sizeMin,
                sizeMax,
                number(row, "particleSpeed", 0, 0, 1000),
                number(row, "driftX", 0, -1000, 1000),
                number(row, "driftY", 0, -1000, 1000),
                number(row, "noiseOpacity", 0, 0, 0.35),
                integer(row, "noiseSamples", 0, 0, 512),
                number(row, "pulseSpeed", 0, 0, 50),
                number(row, "pulseIntensity", 0, 0, 1),
                number(row, "lightningChancePerSecond", 0, 0, 1),
                rgb(text(row, "lightningColor", "#FFFFFF"), id, "lightningColor"),
                text(row, "bannerText", ""),
                rgb(text(row, "bannerColor", "#FFFFFF"), id, "bannerColor"),
                rgb(text(row, "mapColor", "#EBC3FF"), id, "mapColor"),
                ServerSaveStore.boolValue(row, "mapPulse", false),
                number(row, "mapPulseSpeed", 0, 0, 50));
    }

    private static String text(Map<String,Object> row, String key, String fallback) {
        return ServerSaveStore.string(row, key, fallback).trim();
    }

    private static double number(Map<String,Object> row, String key, double fallback, double min, double max) {
        double value = ServerSaveStore.doubleValue(row, key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalStateException("Galaxy event visual numeric field " + key + " is out of range.");
        }
        return value;
    }

    private static int integer(Map<String,Object> row, String key, int fallback, int min, int max) {
        int value = ServerSaveStore.intValue(row, key, fallback);
        if (value < min || value > max) {
            throw new IllegalStateException("Galaxy event visual integer field " + key + " is out of range.");
        }
        return value;
    }

    private static int rgb(String value, String id, String field) {
        String clean = value == null ? "" : value.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        try {
            if (clean.length() != 6) throw new NumberFormatException();
            return Integer.parseInt(clean, 16) & 0xFFFFFF;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Galaxy event visual " + id + " has invalid " + field + ": " + value + ".");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalStateException("Invalid " + label + ": " + value + "."); }
    }
}

enum EventVisualArea { SYSTEM }
enum EventParticleType { DUST, SPARK, STREAK }

record GalaxyEventVisualStyle(boolean enabled,
                              EventVisualArea effectArea,
                              int tintColorRgb,
                              double tintOpacity,
                              EventParticleType particleType,
                              int particleColorRgb,
                              double particleOpacity,
                              int particleCount,
                              double particleSizeMin,
                              double particleSizeMax,
                              double particleSpeed,
                              double driftX,
                              double driftY,
                              double noiseOpacity,
                              int noiseSamples,
                              double pulseSpeed,
                              double pulseIntensity,
                              double lightningChancePerSecond,
                              int lightningColorRgb,
                              String bannerText,
                              int bannerColorRgb,
                              int mapColorRgb,
                              boolean mapPulse,
                              double mapPulseSpeed) {
    static final GalaxyEventVisualStyle NONE = new GalaxyEventVisualStyle(false, EventVisualArea.SYSTEM,
            0, 0, EventParticleType.DUST, 0xFFFFFF, 0, 0, 1, 1, 0, 0, 0,
            0, 0, 0, 0, 0, 0xFFFFFF, "", 0xFFFFFF, 0xEBC3FF, false, 0);
}
