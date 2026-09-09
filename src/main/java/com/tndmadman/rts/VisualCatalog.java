package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Client-local cosmetic content catalog. Gameplay rules, saves, and network snapshots reference
 * their existing stable IDs; renderers resolve those IDs to visual metadata here.
 */
final class VisualCatalog {
    static final Path CONFIG_PATH = Path.of("config/visuals.json");
    static final int SCHEMA_VERSION = 1;

    private static volatile VisualCatalog cached;

    private final Map<String, ShipVisualDefinition> ships;
    private final Map<String, StationVisualDefinition> stations;
    private final Map<String, SystemVisualDefinition> systems;
    private final Map<String, CatalogCelestialVisualDefinition> celestials;

    private VisualCatalog(Map<String, ShipVisualDefinition> ships,
                          Map<String, StationVisualDefinition> stations,
                          Map<String, SystemVisualDefinition> systems,
                          Map<String, CatalogCelestialVisualDefinition> celestials) {
        this.ships = Map.copyOf(ships);
        this.stations = Map.copyOf(stations);
        this.systems = Map.copyOf(systems);
        this.celestials = Map.copyOf(celestials);
    }

    static ShipVisualDefinition ship(String shipTypeId) {
        return current().ships.getOrDefault(normalize(shipTypeId), ShipVisualDefinition.FALLBACK);
    }

    static StationVisualDefinition station(String stationTypeId) {
        return current().stations.getOrDefault(normalize(stationTypeId), StationVisualDefinition.FALLBACK);
    }

    static SystemVisualDefinition system(String systemId) {
        return current().systems.getOrDefault(normalize(systemId), SystemVisualDefinition.FALLBACK);
    }

    static CatalogCelestialVisualDefinition celestial(String systemId, String bodyId) {
        return current().celestials.getOrDefault(celestialKey(systemId, bodyId), CatalogCelestialVisualDefinition.FALLBACK);
    }

    /** Reload cosmetic content without touching gameplay state; cached sprites are invalidated. */
    static synchronized void reload() {
        cached = loadSafely(CONFIG_PATH);
        ShipSpriteCache.clear();
    }

    static VisualCatalog loadStrictForValidation(Path path) {
        return loadStrict(path);
    }

    boolean containsShip(String id) { return ships.containsKey(normalize(id)); }
    boolean containsStation(String id) { return stations.containsKey(normalize(id)); }
    boolean containsSystem(String id) { return systems.containsKey(normalize(id)); }
    boolean containsCelestial(String systemId, String bodyId) {
        return celestials.containsKey(celestialKey(systemId, bodyId));
    }
    int shipCount() { return ships.size(); }
    int stationCount() { return stations.size(); }
    int systemCount() { return systems.size(); }
    int celestialCount() { return celestials.size(); }

    private static VisualCatalog current() {
        VisualCatalog value = cached;
        if (value != null) return value;
        synchronized (VisualCatalog.class) {
            value = cached;
            if (value == null) {
                value = loadSafely(CONFIG_PATH);
                cached = value;
            }
            return value;
        }
    }

    private static VisualCatalog loadSafely(Path path) {
        try {
            return loadStrict(path);
        } catch (RuntimeException ex) {
            System.err.println("Visual content disabled; using safe fallback visuals: " + ex.getMessage());
            return empty();
        }
    }

    private static VisualCatalog loadStrict(Path path) {
        try {
            Map<String, Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(path)));
            int version = ServerSaveStore.intValue(root, "version", -1);
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("config/visuals.json schema version must be "
                        + SCHEMA_VERSION + " (found " + version + ").");
            }

            Map<String, ShipVisualDefinition> ships = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("ships"))) {
                Map<String, Object> row = ServerSaveStore.object(raw);
                String id = requiredId(row, "id", "ship visual");
                ShipVisualFamily family = enumValue(ShipVisualFamily.class,
                        text(row, "family", "COMBAT"), "ship family for " + id);
                int seed = ServerSaveStore.intValue(row, "seed", stableSeed(id));
                int detailCount = boundedInt(row, "detailCount", 2, 1, 6, id);
                putUnique(ships, id, new ShipVisualDefinition(id, family, seed, detailCount), "ship visual");
            }

            Map<String, StationVisualDefinition> stations = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("stations"))) {
                Map<String, Object> row = ServerSaveStore.object(raw);
                String id = requiredId(row, "id", "station visual");
                String style = text(row, "style", "legacy");
                int hullSides = boundedInt(row, "hullSides", 6, 3, 12, id);
                int hullColor = rgb(text(row, "hullColor", "#141D2A"), id, "hullColor");
                int coreColor = rgb(text(row, "coreColor", "#7DCDFF"), id, "coreColor");
                double coreScale = boundedDouble(row, "coreScale", 1.0, 0.5, 2.0, id);
                putUnique(stations, id, new StationVisualDefinition(id, style, hullSides,
                        hullColor, coreColor, coreScale), "station visual");
            }

            Map<String, SystemVisualDefinition> systems = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("systems"))) {
                Map<String, Object> row = ServerSaveStore.object(raw);
                String id = requiredId(row, "id", "system visual");
                int orbitColor = rgb(text(row, "orbitColor", "#789BBE"), id, "orbitColor");
                int orbitAlpha = boundedInt(row, "orbitAlpha", 42, 0, 255, id);
                int seed = ServerSaveStore.intValue(row, "seed", stableSeed(id));
                putUnique(systems, id, new SystemVisualDefinition(id, orbitColor, orbitAlpha, seed), "system visual");
            }

            Map<String, CatalogCelestialVisualDefinition> celestials = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("celestials"))) {
                Map<String, Object> row = ServerSaveStore.object(raw);
                String systemId = requiredId(row, "systemId", "celestial visual system");
                String bodyId = requiredId(row, "bodyId", "celestial visual body");
                String style = text(row, "style", "legacy");
                String colorText = text(row, "color", "");
                Integer color = colorText.isBlank() ? null : rgb(colorText, systemId + "/" + bodyId, "color");
                double glowScale = boundedDouble(row, "glowScale", 2.2, 1.0, 5.0,
                        systemId + "/" + bodyId);
                int seed = ServerSaveStore.intValue(row, "seed", stableSeed(systemId + "/" + bodyId));
                String key = celestialKey(systemId, bodyId);
                if (celestials.putIfAbsent(key, new CatalogCelestialVisualDefinition(systemId, bodyId, style,
                        color, glowScale, seed)) != null) {
                    throw new IllegalStateException("Duplicate celestial visual id: " + systemId + "/" + bodyId);
                }
            }

            return new VisualCatalog(ships, stations, systems, celestials);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read visual content: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalStateException) throw ex;
            throw new IllegalStateException("Could not parse visual content: " + ex.getMessage(), ex);
        }
    }

    private static VisualCatalog empty() {
        return new VisualCatalog(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static String requiredId(Map<String, Object> row, String key, String label) {
        String value = text(row, key, "");
        if (value.isBlank()) throw new IllegalStateException(label + " " + key + " is required.");
        return normalize(value);
    }

    private static String text(Map<String, Object> row, String key, String fallback) {
        return ServerSaveStore.string(row, key, fallback).trim();
    }

    private static int boundedInt(Map<String, Object> row, String key, int fallback,
                                  int min, int max, String id) {
        int value = ServerSaveStore.intValue(row, key, fallback);
        if (value < min || value > max) {
            throw new IllegalStateException("Visual " + id + " field " + key + " is out of range.");
        }
        return value;
    }

    private static double boundedDouble(Map<String, Object> row, String key, double fallback,
                                        double min, double max, String id) {
        double value = ServerSaveStore.doubleValue(row, key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalStateException("Visual " + id + " field " + key + " is out of range.");
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
            throw new IllegalStateException("Visual " + id + " has invalid " + field + ": " + value + ".");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid " + label + ": " + value + ".");
        }
    }

    private static <T> void putUnique(Map<String, T> map, String id, T value, String label) {
        if (map.putIfAbsent(normalize(id), value) != null) {
            throw new IllegalStateException("Duplicate " + label + " id: " + id);
        }
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    private static String celestialKey(String systemId, String bodyId) {
        return normalize(systemId) + '/' + normalize(bodyId);
    }

    private static int stableSeed(String value) {
        int hash = 0x811C9DC5;
        String normalized = normalize(value);
        for (int i = 0; i < normalized.length(); i++) {
            hash ^= normalized.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
