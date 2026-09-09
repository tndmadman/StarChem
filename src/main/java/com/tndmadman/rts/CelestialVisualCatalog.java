package com.tndmadman.rts;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class CelestialVisualCatalog {
    private static final Path CONFIG = Path.of("config/visuals/celestials.json");
    private static final CatalogData DATA = load();

    private CelestialVisualCatalog() { }

    static CelestialVisualDefinition resolve(String systemId, CelestialBodyDefinition body) {
        if (body == null) return fallback(CelestialVisualClass.ROCKY, Color.GRAY, "fallback");
        String assignment = DATA.assignments.get(key(systemId, body.id()));
        if (assignment != null) {
            CelestialVisualDefinition defined = DATA.presets.get(assignment);
            if (defined != null) return defined;
        }
        CelestialVisualClass visualClass = infer(body);
        return fallback(visualClass, body.color(), "fallback-" + visualClass.name().toLowerCase(Locale.ROOT));
    }

    static int configuredPresetCount() { return DATA.presets.size(); }

    private static CatalogData load() {
        Map<String, CelestialVisualDefinition> presets = new LinkedHashMap<>(builtIns());
        Map<String, String> assignments = new LinkedHashMap<>();
        if (!Files.exists(CONFIG)) return new CatalogData(Map.copyOf(presets), Map.of());
        try {
            Map<String, Object> root = object(MiniJson.parse(Files.readString(CONFIG)));
            for (Map.Entry<String, Object> entry : object(root.get("presets")).entrySet()) {
                Map<String, Object> value = object(entry.getValue());
                if (value.isEmpty()) continue;
                CelestialVisualDefinition inherited = presets.get(string(value, "extends", ""));
                CelestialVisualClass fallbackClass = inherited == null ? CelestialVisualClass.ROCKY : inherited.visualClass();
                Color fallbackPrimary = inherited == null ? Color.GRAY : inherited.primary();
                Color fallbackSecondary = inherited == null ? fallbackPrimary.darker() : inherited.secondary();
                Color fallbackAccent = inherited == null ? fallbackPrimary.brighter() : inherited.accent();
                Color fallbackAtmosphere = inherited == null ? fallbackAccent : inherited.atmosphere();
                Color fallbackRing = inherited == null ? fallbackSecondary : inherited.ringColor();
                CelestialVisualDefinition parsed = new CelestialVisualDefinition(
                        entry.getKey(),
                        CelestialVisualClass.parse(string(value, "class", fallbackClass.name()), fallbackClass),
                        color(value.get("primary"), fallbackPrimary),
                        color(value.get("secondary"), fallbackSecondary),
                        color(value.get("accent"), fallbackAccent),
                        color(value.get("atmosphere"), fallbackAtmosphere),
                        number(value, "atmosphereStrength", inherited == null ? 0 : inherited.atmosphereStrength()),
                        number(value, "cloudCoverage", inherited == null ? 0 : inherited.cloudCoverage()),
                        number(value, "ringInnerRadius", inherited == null ? 0 : inherited.ringInnerRadius()),
                        number(value, "ringOuterRadius", inherited == null ? 0 : inherited.ringOuterRadius()),
                        number(value, "ringFlattening", inherited == null ? 0.36 : inherited.ringFlattening()),
                        number(value, "ringAngle", inherited == null ? 0 : inherited.ringAngle()),
                        color(value.get("ringColor"), fallbackRing),
                        bool(value, "emissive", inherited != null && inherited.emissive()));
                presets.put(entry.getKey(), parsed);
            }
            for (Map.Entry<String, Object> system : object(root.get("systems")).entrySet()) {
                for (Map.Entry<String, Object> body : object(system.getValue()).entrySet()) {
                    String visualId = body.getValue() == null ? "" : String.valueOf(body.getValue()).trim();
                    if (!visualId.isBlank()) assignments.put(key(system.getKey(), body.getKey()), visualId);
                }
            }
        } catch (Exception ex) {
            System.err.println("Could not load celestial visuals " + CONFIG + ": " + ex.getMessage());
        }
        return new CatalogData(Map.copyOf(presets), Map.copyOf(assignments));
    }

    private static Map<String, CelestialVisualDefinition> builtIns() {
        Map<String, CelestialVisualDefinition> out = new LinkedHashMap<>();
        out.put("star", new CelestialVisualDefinition("star", CelestialVisualClass.STAR,
                new Color(255, 205, 96), new Color(255, 133, 56), Color.WHITE,
                new Color(255, 196, 90), 0, 0, 0, 0, 0.36, 0, new Color(255, 180, 100), true));
        out.put("rocky", new CelestialVisualDefinition("rocky", CelestialVisualClass.ROCKY,
                new Color(145, 120, 96), new Color(70, 61, 55), new Color(190, 166, 130),
                new Color(180, 185, 190), 0, 0, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("terrestrial", new CelestialVisualDefinition("terrestrial", CelestialVisualClass.TERRESTRIAL,
                new Color(47, 105, 160), new Color(56, 115, 63), new Color(194, 183, 121),
                new Color(95, 175, 255), 0.72, 0.42, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("desert", new CelestialVisualDefinition("desert", CelestialVisualClass.DESERT,
                new Color(190, 132, 70), new Color(116, 72, 45), new Color(232, 188, 112),
                new Color(210, 160, 95), 0.22, 0.08, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("ice", new CelestialVisualDefinition("ice", CelestialVisualClass.ICE,
                new Color(156, 207, 224), new Color(72, 119, 152), new Color(231, 246, 250),
                new Color(151, 219, 255), 0.35, 0.08, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("lava", new CelestialVisualDefinition("lava", CelestialVisualClass.LAVA,
                new Color(72, 48, 42), new Color(25, 22, 23), new Color(255, 104, 24),
                new Color(255, 90, 30), 0.12, 0, 0, 0, 0.36, 0, Color.GRAY, true));
        out.put("gas_giant", new CelestialVisualDefinition("gas_giant", CelestialVisualClass.GAS_GIANT,
                new Color(199, 149, 94), new Color(126, 89, 68), new Color(236, 203, 151),
                new Color(210, 186, 150), 0.20, 0.18, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("ice_giant", new CelestialVisualDefinition("ice_giant", CelestialVisualClass.ICE_GIANT,
                new Color(104, 160, 193), new Color(63, 105, 142), new Color(175, 221, 235),
                new Color(115, 202, 235), 0.32, 0.16, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("toxic", new CelestialVisualDefinition("toxic", CelestialVisualClass.TOXIC,
                new Color(129, 143, 77), new Color(73, 84, 54), new Color(194, 190, 92),
                new Color(184, 202, 86), 0.65, 0.62, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("dead", new CelestialVisualDefinition("dead", CelestialVisualClass.DEAD,
                new Color(101, 101, 104), new Color(54, 55, 59), new Color(157, 153, 146),
                new Color(170, 170, 175), 0, 0, 0, 0, 0.36, 0, Color.GRAY, false));
        out.put("industrial", new CelestialVisualDefinition("industrial", CelestialVisualClass.INDUSTRIAL,
                new Color(82, 94, 103), new Color(40, 48, 54), new Color(152, 177, 190),
                new Color(95, 170, 210), 0.16, 0.03, 0, 0, 0.36, 0, Color.GRAY, true));
        return out;
    }

    private static CelestialVisualDefinition fallback(CelestialVisualClass visualClass, Color source, String id) {
        Color base = source == null ? Color.GRAY : source;
        Color dark = mix(base, Color.BLACK, 0.48);
        Color light = mix(base, Color.WHITE, 0.42);
        return switch (visualClass) {
            case STAR -> new CelestialVisualDefinition(id, visualClass, base, mix(base, new Color(255, 92, 32), 0.32), Color.WHITE,
                    base, 0, 0, 0, 0, 0.36, 0, light, true);
            case TERRESTRIAL -> new CelestialVisualDefinition(id, visualClass, base, mix(base, new Color(42, 126, 62), 0.48), light,
                    mix(base, new Color(90, 185, 255), 0.62), 0.65, 0.38, 0, 0, 0.36, 0, dark, false);
            case DESERT -> new CelestialVisualDefinition(id, visualClass, base, dark, light, light, 0.18, 0.05, 0, 0, 0.36, 0, dark, false);
            case ICE -> new CelestialVisualDefinition(id, visualClass, base, mix(base, new Color(50, 105, 150), 0.55), Color.WHITE,
                    mix(base, new Color(135, 215, 255), 0.72), 0.34, 0.08, 0, 0, 0.36, 0, dark, false);
            case LAVA -> new CelestialVisualDefinition(id, visualClass, dark, mix(dark, Color.BLACK, 0.45), new Color(255, 100, 22),
                    new Color(255, 86, 24), 0.10, 0, 0, 0, 0.36, 0, dark, true);
            case GAS_GIANT -> new CelestialVisualDefinition(id, visualClass, base, dark, light, light, 0.18, 0.14, 0, 0, 0.36, 0, dark, false);
            case ICE_GIANT -> new CelestialVisualDefinition(id, visualClass, base, dark, light, light, 0.30, 0.14, 0, 0, 0.36, 0, dark, false);
            case TOXIC -> new CelestialVisualDefinition(id, visualClass, base, dark, light, light, 0.62, 0.58, 0, 0, 0.36, 0, dark, false);
            case DEAD -> new CelestialVisualDefinition(id, visualClass, base, dark, light, light, 0, 0, 0, 0, 0.36, 0, dark, false);
            case INDUSTRIAL -> new CelestialVisualDefinition(id, visualClass, base, dark, light, new Color(90, 170, 215), 0.12, 0, 0, 0, 0.36, 0, dark, true);
            case ROCKY -> new CelestialVisualDefinition(id, visualClass, base, dark, light, light, 0, 0, 0, 0, 0.36, 0, dark, false);
        };
    }

    private static CelestialVisualClass infer(CelestialBodyDefinition body) {
        String haystack = (body.id() + " " + body.name()).toLowerCase(Locale.ROOT);
        if (body.parentId() == null || containsAny(haystack, "sun", "star", "pulsar")) return CelestialVisualClass.STAR;
        if (containsAny(haystack, "gas giant", "leviathan", "jovian")) return CelestialVisualClass.GAS_GIANT;
        if (containsAny(haystack, "ice giant", "neptune", "uranus")) return CelestialVisualClass.ICE_GIANT;
        if (containsAny(haystack, "cloud", "toxic", "acid", "venus")) return CelestialVisualClass.TOXIC;
        if (containsAny(haystack, "lava", "volcan", "slag", "crucible", "magma")) return CelestialVisualClass.LAVA;
        if (containsAny(haystack, "ice", "frost", "frozen", "glacier")) return CelestialVisualClass.ICE;
        if (containsAny(haystack, "desert", "dune", "arid")) return CelestialVisualClass.DESERT;
        if (containsAny(haystack, "dead", "tomb", "broken", "shatter", "barren", "moon", "warden")) return CelestialVisualClass.DEAD;
        if (containsAny(haystack, "refinery", "industrial", "artificial", "factory", "station world")) return CelestialVisualClass.INDUSTRIAL;
        if (containsAny(haystack, "terra", "garden", "ocean", "inner planet")) return CelestialVisualClass.TERRESTRIAL;
        if (containsAny(haystack, "giant")) return CelestialVisualClass.GAS_GIANT;
        return CelestialVisualClass.ROCKY;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String key(String systemId, String bodyId) {
        return clean(systemId) + "/" + clean(bodyId);
    }

    private static String clean(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static double number(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static Color color(Object value, Color fallback) {
        if (value == null) return fallback;
        try {
            String clean = String.valueOf(value).trim().replace("#", "");
            if (clean.length() == 6) return new Color(Integer.parseInt(clean, 16));
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static Color mix(Color a, Color b, double ratio) {
        double t = Math.max(0, Math.min(1, ratio));
        return new Color(
                (int)Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                (int)Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int)Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private record CatalogData(Map<String, CelestialVisualDefinition> presets, Map<String, String> assignments) { }
}
