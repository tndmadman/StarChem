package com.tndmadman.rts;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Data-driven cosmetic station catalog. Invalid runtime data safely falls back to built-ins. */
final class StationVisualCatalog {
    static final Path CONFIG = Path.of("config/visuals/stations.json");
    private static final Map<String, StationVisualDefinition> DEFINITIONS = loadRuntime();

    private StationVisualCatalog() { }

    static StationVisualDefinition resolve(String typeId) {
        if (typeId == null) return null;
        return DEFINITIONS.get(typeId.trim().toLowerCase(Locale.ROOT));
    }

    static int configuredCount() { return DEFINITIONS.size(); }

    static Map<String, StationVisualDefinition> loadForValidation(Path path) {
        return Map.copyOf(load(path, true));
    }

    private static Map<String, StationVisualDefinition> loadRuntime() {
        Map<String, StationVisualDefinition> builtIns = builtIns();
        if (!Files.exists(CONFIG)) return Map.copyOf(builtIns);
        try {
            return Map.copyOf(load(CONFIG, false));
        } catch (RuntimeException ex) {
            System.err.println("Could not load station visuals " + CONFIG + "; using built-ins: " + ex.getMessage());
            return Map.copyOf(builtIns);
        }
    }

    private static Map<String, StationVisualDefinition> load(Path path, boolean strict) {
        Map<String, StationVisualDefinition> out = new LinkedHashMap<>(builtIns());
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));
            Map<String,Object> stationRows = object(root.get("stations"));
            if (strict && stationRows.isEmpty()) throw new IllegalArgumentException("stations object is empty.");
            for (Map.Entry<String,Object> entry : stationRows.entrySet()) {
                String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
                Map<String,Object> row = object(entry.getValue());
                if (row.isEmpty()) {
                    if (strict) throw new IllegalArgumentException("Station visual " + id + " is not an object.");
                    continue;
                }
                StationVisualDefinition fallback = out.get(id);
                try {
                    StationVisualDefinition parsed = new StationVisualDefinition(
                            id,
                            preset(text(row, "preset", fallback == null ? "OUTPOST" : fallback.preset().name())),
                            number(row, "scale", fallback == null ? 1.0 : fallback.scale()),
                            integer(row, "ringCount", fallback == null ? 0 : fallback.ringCount()),
                            integer(row, "armCount", fallback == null ? 2 : fallback.armCount()),
                            integer(row, "moduleCount", fallback == null ? 2 : fallback.moduleCount()),
                            integer(row, "lightCount", fallback == null ? 4 : fallback.lightCount()),
                            integer(row, "batteryCount", fallback == null ? 0 : fallback.batteryCount()),
                            color(row.get("accent"), fallback == null ? new Color(110,205,255) : fallback.accent()));
                    out.put(id, parsed);
                } catch (RuntimeException ex) {
                    if (strict) throw new IllegalArgumentException("Invalid station visual " + id + ": " + ex.getMessage(), ex);
                    System.err.println("Ignoring invalid station visual " + id + ": " + ex.getMessage());
                }
            }
            return out;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse station visual config: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, StationVisualDefinition> builtIns() {
        Map<String, StationVisualDefinition> out = new LinkedHashMap<>();
        out.put("outpost", new StationVisualDefinition("outpost", StationVisualPreset.OUTPOST,
                1.0, 1, 3, 4, 8, 2, new Color(111,203,255)));
        out.put("shipyard", new StationVisualDefinition("shipyard", StationVisualPreset.SHIPYARD,
                1.16, 1, 4, 6, 12, 4, new Color(103,184,255)));
        out.put("laboratory", new StationVisualDefinition("laboratory", StationVisualPreset.LABORATORY,
                1.06, 2, 3, 5, 10, 1, new Color(101,232,247)));
        out.put("manufacturing", new StationVisualDefinition("manufacturing", StationVisualPreset.FACTORY,
                1.08, 0, 4, 8, 12, 3, new Color(240,169,84)));
        return out;
    }

    private static StationVisualPreset preset(String value) {
        try { return StationVisualPreset.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw new IllegalArgumentException("unknown preset " + value); }
    }

    private static String text(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key); return value == null ? fallback : String.valueOf(value).trim();
    }
    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key); return value instanceof Number n ? n.doubleValue() : fallback;
    }
    private static int integer(Map<String,Object> map, String key, int fallback) {
        Object value = map.get(key); return value instanceof Number n ? n.intValue() : fallback;
    }
    private static Color color(Object value, Color fallback) {
        if (value == null) return fallback;
        String clean = String.valueOf(value).trim().replace("#", "");
        try {
            if (clean.length() != 6) throw new NumberFormatException();
            return new Color(Integer.parseInt(clean, 16));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid accent color " + value);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
    }
}
