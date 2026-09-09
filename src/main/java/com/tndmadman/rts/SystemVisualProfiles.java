package com.tndmadman.rts;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Optional presentation metadata. Simulation state remains in StarSystemDefinition. */
final class SystemVisualProfiles {
    private static final Map<String, Optional<SystemVisualProfile>> CACHE = new ConcurrentHashMap<>();

    private SystemVisualProfiles() { }

    static Optional<SystemVisualProfile> forSystem(StarSystemDefinition definition) {
        if (definition == null || definition.id() == null || definition.id().isBlank()) return Optional.empty();
        return CACHE.computeIfAbsent(definition.id(), SystemVisualProfiles::load);
    }

    private static Optional<SystemVisualProfile> load(String systemId) {
        String fileName = systemId.toLowerCase(Locale.ROOT).replace('_', '-') + ".json";
        Path path = Path.of("config", "visuals", fileName);
        if (!Files.exists(path)) return Optional.empty();
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));
            Map<String,Object> nebula = object(root.get("nebula"));
            Map<String,SystemVisualProfile.BodyVisual> bodies = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : object(root.get("bodies")).entrySet()) {
                Map<String,Object> body = object(entry.getValue());
                bodies.put(entry.getKey(), new SystemVisualProfile.BodyVisual(
                        string(body, "style", "planet"),
                        color(body.get("accent"), Color.WHITE),
                        color(body.get("bands"), new Color(90, 100, 120)),
                        color(body.get("atmosphere"), new Color(150, 190, 230)),
                        color(body.get("storm"), new Color(220, 190, 190))));
            }
            return Optional.of(new SystemVisualProfile(
                    integer(root, "seed", systemId.hashCode()),
                    color(root.get("background"), new Color(7, 8, 18)),
                    color(root.get("backgroundAccent"), new Color(18, 16, 40)),
                    color(root.get("starColor"), new Color(217, 231, 255)),
                    integer(root, "starCount", 420),
                    number(root, "parallax", 0.14),
                    color(nebula.get("primary"), new Color(92, 67, 158)),
                    color(nebula.get("secondary"), new Color(46, 83, 128)),
                    color(nebula.get("highlight"), new Color(172, 105, 196)),
                    number(nebula, "opacity", 0.5),
                    integer(nebula, "clouds", 14),
                    integer(nebula, "dustCount", 150),
                    Map.copyOf(bodies)));
        } catch (Exception ex) {
            System.err.println("Could not load visual profile for " + systemId + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Map<String,Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static Color color(Object value, Color fallback) {
        if (value == null) return fallback;
        try { return Color.decode(String.valueOf(value)); }
        catch (NumberFormatException ex) { return fallback; }
    }
}

record SystemVisualProfile(
        long seed,
        Color background,
        Color backgroundAccent,
        Color starColor,
        int starCount,
        double parallax,
        Color nebulaPrimary,
        Color nebulaSecondary,
        Color nebulaHighlight,
        double nebulaOpacity,
        int nebulaClouds,
        int dustCount,
        Map<String, BodyVisual> bodies) {

    BodyVisual body(String id) {
        return bodies == null ? null : bodies.get(id);
    }

    record BodyVisual(String style, Color accent, Color bands, Color atmosphere, Color storm) { }
}
