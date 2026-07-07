package com.tndmadman.rts;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class StarSystems {
    static final String DEFAULT_SYSTEM_ID = "sol_standard";
    static final String PLAYER_HOME_SYSTEM_ID = "empty_frontier";
    static final String CORSAIR_SYSTEM_ID = "corsair_den";

    private static final Map<String, StarSystemDefinition> SYSTEMS = loadAll();

    private StarSystems() { }

    static StarSystemDefinition get(String id) {
        StarSystemDefinition found = SYSTEMS.get(id == null || id.isBlank() ? DEFAULT_SYSTEM_ID : id);
        if (found != null) return found;
        return SYSTEMS.getOrDefault(DEFAULT_SYSTEM_ID, fallback());
    }

    static StarSystemDefinition defaultSystem() { return get(DEFAULT_SYSTEM_ID); }

    static List<StarSystemDefinition> options() { return List.copyOf(SYSTEMS.values()); }

    static StarSystemDefinition firstByRole(String role, String fallbackId) {
        for (StarSystemDefinition system : SYSTEMS.values()) {
            if (system.role().equalsIgnoreCase(role)) return system;
        }
        return get(fallbackId);
    }

    private static Map<String, StarSystemDefinition> loadAll() {
        Map<String, StarSystemDefinition> out = new LinkedHashMap<>();
        List<String> files = manifestSystemFiles();
        for (String file : files) {
            try {
                StarSystemDefinition system = parse(Path.of(file));
                out.put(system.id(), system);
            } catch (Exception ex) {
                System.err.println("Could not load star system " + file + ": " + ex.getMessage());
            }
        }
        if (out.isEmpty()) out.put(DEFAULT_SYSTEM_ID, fallback());
        return out;
    }

    private static List<String> manifestSystemFiles() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return defaultSystemFiles();
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            Object systems = files.get("systems");
            List<String> out = new ArrayList<>();
            for (Object item : array(systems)) out.add(String.valueOf(item));
            return out.isEmpty() ? defaultSystemFiles() : List.copyOf(out);
        } catch (Exception ex) {
            System.err.println("Could not read star system manifest: " + ex.getMessage());
            return defaultSystemFiles();
        }
    }

    private static List<String> defaultSystemFiles() {
        return List.of(
                "config/systems/sol-standard.json",
                "config/systems/red-dwarf.json",
                "config/systems/gas-giant-frontier.json",
                "config/systems/ice-belt.json",
                "config/systems/warzone.json",
                "config/systems/corsair-den.json",
                "config/systems/empty-frontier.json");
    }

    private static StarSystemDefinition parse(Path path) throws IOException {
        Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));
        String id = string(root, "id", stripJson(path.getFileName().toString()));
        String name = string(root, "name", id);
        String role = string(root, "role", "standard");
        int width = integer(root, "width", 18000);
        int height = integer(root, "height", 16000);
        List<CelestialBodyDefinition> bodies = parseBodies(root.get("bodies"));
        List<ResourceBelt> belts = parseBelts(root.get("resourceBelts"));
        List<Material> spawnMaterials = parseMaterials(root.get("spawnMaterials"));
        if (bodies.isEmpty()) bodies = fallbackBodies();
        if (belts.isEmpty()) belts = fallbackBelts();
        if (spawnMaterials.isEmpty()) spawnMaterials = List.of(Material.IRON, Material.COPPER, Material.SILICATES, Material.ICE);
        return new StarSystemDefinition(id, name, role, width, height, bodies, belts, spawnMaterials);
    }

    private static List<CelestialBodyDefinition> parseBodies(Object value) {
        List<CelestialBodyDefinition> out = new ArrayList<>();
        for (Object item : array(value)) {
            Map<String,Object> b = object(item);
            if (b.isEmpty()) continue;
            String id = string(b, "id", "body" + out.size());
            out.add(new CelestialBodyDefinition(
                    id,
                    string(b, "name", id),
                    nullableString(b.get("parent")),
                    number(b, "orbit", 0),
                    number(b, "radius", 20),
                    number(b, "speed", 0),
                    color(string(b, "color", "#FFFFFF"))));
        }
        return List.copyOf(out);
    }

    private static List<ResourceBelt> parseBelts(Object value) {
        List<ResourceBelt> out = new ArrayList<>();
        for (Object item : array(value)) {
            Map<String,Object> b = object(item);
            if (b.isEmpty()) continue;
            out.add(new ResourceBelt(
                    string(b, "name", "Resource Belt"),
                    nodeKind(string(b, "kind", "SILICATE_ROCK")),
                    parseMaterials(b.get("materials")),
                    number(b, "orbit", 2500),
                    number(b, "width", 300),
                    number(b, "arc", 1.0),
                    integer(b, "count", 20),
                    number(b, "amount", 100),
                    number(b, "harvestRate", 8),
                    number(b, "radius", 3)));
        }
        return List.copyOf(out);
    }

    private static List<Material> parseMaterials(Object value) {
        List<Material> out = new ArrayList<>();
        for (Object item : array(value)) {
            try { out.add(Material.valueOf(String.valueOf(item).trim().toUpperCase(Locale.ROOT))); }
            catch (Exception ignored) { }
        }
        return List.copyOf(out);
    }

    private static StarSystemDefinition fallback() {
        return new StarSystemDefinition(DEFAULT_SYSTEM_ID, "Sol Standard", "standard", 18000, 16000,
                fallbackBodies(), fallbackBelts(), List.of(Material.IRON, Material.COPPER, Material.SILICATES, Material.ICE));
    }

    private static List<CelestialBodyDefinition> fallbackBodies() {
        return List.of(
                new CelestialBodyDefinition("sun", "Sun", null, 0, 210, 0, new Color(255,205,80)),
                new CelestialBodyDefinition("inner", "Inner Planet", "sun", 1450, 46, 0.018, new Color(80,145,210)),
                new CelestialBodyDefinition("rock", "Rock Planet", "sun", 2600, 68, -0.012, new Color(160,115,75)),
                new CelestialBodyDefinition("giant", "Gas Giant", "sun", 4100, 110, 0.007, new Color(205,150,95)),
                new CelestialBodyDefinition("ice", "Outer Ice Planet", "sun", 5750, 78, -0.0045, new Color(150,205,230)),
                new CelestialBodyDefinition("inner_moon", "Inner Moon", "inner", 210, 18, 0.06, new Color(180,185,190)),
                new CelestialBodyDefinition("giant_moon_a", "Giant Moon A", "giant", 260, 24, -0.045, new Color(190,175,145)),
                new CelestialBodyDefinition("giant_moon_b", "Giant Moon B", "giant", 390, 18, 0.035, new Color(145,165,190)));
    }

    private static List<ResourceBelt> fallbackBelts() {
        return List.of(
                new ResourceBelt("Inner Iron Belt", NodeKind.SILICATE_ROCK, List.of(Material.IRON), 1900, 260, 1.0, 130, 22, 7.5, 2.8),
                new ResourceBelt("Copper Arc", NodeKind.SILICATE_ROCK, List.of(Material.COPPER), 2650, 300, 0.8, 110, 18, 6.5, 2.6),
                new ResourceBelt("Silicate Belt", NodeKind.SILICATE_ROCK, List.of(Material.SILICATES), 3500, 360, 1.2, 140, 24, 8.0, 3.0),
                new ResourceBelt("Ice Ring", NodeKind.SILICATE_ROCK, List.of(Material.ICE), 4650, 420, 0.9, 115, 20, 7.0, 2.8),
                new ResourceBelt("Hydrogen Drift", NodeKind.GAS_CLOUD, List.of(Material.HYDROGEN), 5450, 520, 1.1, 120, 26, 9.0, 4.8),
                new ResourceBelt("Outer Gas Band", NodeKind.GAS_CLOUD, List.of(Material.HELIUM, Material.METHANE, Material.AMMONIA, Material.HYDROGEN), 6650, 620, 1.4, 160, 22, 7.5, 4.5));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) { return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of(); }
    private static List<Object> array(Object value) { return value instanceof List<?> list ? new ArrayList<>(list) : List.of(); }
    private static String string(Map<String,Object> map, String key, String fallback) { Object v = map.get(key); return v == null ? fallback : String.valueOf(v); }
    private static String nullableString(Object value) { if (value == null) return null; String s = String.valueOf(value); return s.isBlank() || "null".equalsIgnoreCase(s) ? null : s; }
    private static double number(Map<String,Object> map, String key, double fallback) { Object v = map.get(key); return v instanceof Number n ? n.doubleValue() : fallback; }
    private static int integer(Map<String,Object> map, String key, int fallback) { Object v = map.get(key); return v instanceof Number n ? n.intValue() : fallback; }
    private static NodeKind nodeKind(String value) { try { return NodeKind.valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception ex) { return NodeKind.SILICATE_ROCK; } }
    private static String stripJson(String filename) { return filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename; }

    private static Color color(String hex) {
        try {
            String clean = hex.trim().replace("#", "");
            return new Color(Integer.parseInt(clean, 16));
        } catch (Exception ex) {
            return Color.WHITE;
        }
    }
}
