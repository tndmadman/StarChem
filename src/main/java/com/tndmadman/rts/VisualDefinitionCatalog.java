package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Presentation-only art direction data. Invalid runtime data falls back to procedural rendering. */
final class VisualDefinitionCatalog {
    static final Path CONFIG_PATH = Path.of("config/visual-definitions.json");
    static final int SCHEMA_VERSION = 1;
    private static final VisualDefinitionCatalog EMPTY = new VisualDefinitionCatalog(Map.of(), Map.of(), Map.of(), Map.of());
    private static VisualDefinitionCatalog cached;
    private static boolean attempted;

    private final Map<String, ShipVisualDefinition> ships;
    private final Map<String, StationVisualDefinition> stations;
    private final Map<String, CelestialVisualDefinition> celestials;
    private final Map<String, SystemVisualDefinition> systems;

    private VisualDefinitionCatalog(Map<String, ShipVisualDefinition> ships,
                                    Map<String, StationVisualDefinition> stations,
                                    Map<String, CelestialVisualDefinition> celestials,
                                    Map<String, SystemVisualDefinition> systems) {
        this.ships = Map.copyOf(ships);
        this.stations = Map.copyOf(stations);
        this.celestials = Map.copyOf(celestials);
        this.systems = Map.copyOf(systems);
    }

    static synchronized ShipVisualDefinition ship(String id) {
        return runtime().ships.get(id == null ? "" : id);
    }

    static synchronized StationVisualDefinition station(String id) {
        return runtime().stations.get(id == null ? "" : id);
    }

    static synchronized CelestialVisualDefinition celestial(String systemId, String bodyId) {
        return runtime().celestials.get(celestialKey(systemId, bodyId));
    }

    static synchronized SystemVisualDefinition system(String id) {
        return runtime().systems.get(id == null ? "" : id);
    }

    static synchronized VisualDefinitionCatalog loadForValidation(Path path) {
        return load(path);
    }

    static synchronized void reload() {
        cached = null;
        attempted = false;
    }

    int shipCount() { return ships.size(); }
    int stationCount() { return stations.size(); }
    int celestialCount() { return celestials.size(); }
    int systemCount() { return systems.size(); }
    boolean containsShip(String id) { return ships.containsKey(id); }
    boolean containsStation(String id) { return stations.containsKey(id); }
    boolean containsSystem(String id) { return systems.containsKey(id); }

    private static VisualDefinitionCatalog runtime() {
        if (attempted) return cached;
        attempted = true;
        try {
            cached = load(CONFIG_PATH);
        } catch (RuntimeException ex) {
            System.err.println("Visual catalog disabled; using procedural fallbacks: " + ex.getMessage());
            cached = EMPTY;
        }
        return cached;
    }

    private static VisualDefinitionCatalog load(Path path) {
        try {
            Map<String,Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(path)));
            int version = ServerSaveStore.intValue(root, "version", -1);
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("config/visual-definitions.json schema version must be "
                        + SCHEMA_VERSION + " (found " + version + ").");
            }

            Map<String,ShipVisualDefinition> ships = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("ships"))) {
                Map<String,Object> row = ServerSaveStore.object(raw);
                String id = required(row, "id", "ship visual");
                ShipVisualDefinition def = new ShipVisualDefinition(
                        id,
                        enumValue(ShipVisualPreset.class, text(row, "preset", "WEDGE"), "ship preset for " + id),
                        number(row, "lengthScale", 1, 0.55, 2.0),
                        number(row, "widthScale", 1, 0.45, 1.8),
                        number(row, "asymmetry", 0, -0.45, 0.45),
                        integer(row, "armorSections", 2, 0, 12),
                        integer(row, "engineCount", 2, 1, 8),
                        integer(row, "podCount", 0, 0, 12),
                        integer(row, "hardpointCount", 0, 0, 16),
                        integer(row, "hangarCount", 0, 0, 6),
                        integer(row, "antennaCount", 0, 0, 8),
                        rgb(text(row, "accent", "#7CCFFF"), id, "accent"),
                        rgb(text(row, "glow", "#8EEBFF"), id, "glow"),
                        enumValue(ShipMarking.class, text(row, "marking", "NONE"), "ship marking for " + id));
                duplicate(ships, id, def, "ship visual");
            }

            Map<String,StationVisualDefinition> stations = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("stations"))) {
                Map<String,Object> row = ServerSaveStore.object(raw);
                String id = required(row, "id", "station visual");
                StationVisualDefinition def = new StationVisualDefinition(
                        id,
                        enumValue(StationVisualPreset.class, text(row, "preset", "OUTPOST"), "station preset for " + id),
                        number(row, "scale", 1, 0.65, 1.7),
                        integer(row, "ringCount", 0, 0, 4),
                        integer(row, "armCount", 2, 0, 8),
                        integer(row, "moduleCount", 2, 0, 12),
                        integer(row, "lightCount", 4, 0, 24),
                        rgb(text(row, "accent", "#7CCFFF"), id, "accent"));
                duplicate(stations, id, def, "station visual");
            }

            Map<String,CelestialVisualDefinition> celestials = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("celestials"))) {
                Map<String,Object> row = ServerSaveStore.object(raw);
                String systemId = required(row, "systemId", "celestial visual");
                String bodyId = required(row, "bodyId", "celestial visual");
                String key = celestialKey(systemId, bodyId);
                CelestialVisualDefinition def = new CelestialVisualDefinition(
                        systemId,
                        bodyId,
                        enumValue(CelestialVisualClass.class, text(row, "class", "ROCKY"), "celestial class for " + key),
                        rgb(text(row, "secondary", "#D9E2E8"), key, "secondary"),
                        rgb(text(row, "atmosphere", "#9BDFFF"), key, "atmosphere"),
                        number(row, "atmosphereOpacity", 0, 0, 0.8),
                        integer(row, "cloudLayers", 0, 0, 8),
                        ServerSaveStore.boolValue(row, "rings", false),
                        integer(row, "craters", 0, 0, 32),
                        integer(row, "bands", 0, 0, 16),
                        ServerSaveStore.boolValue(row, "iceCaps", false),
                        ServerSaveStore.boolValue(row, "lava", false),
                        integer(row, "nightLights", 0, 0, 32),
                        number(row, "corona", 0, 0, 1.5));
                duplicate(celestials, key, def, "celestial visual");
            }

            Map<String,SystemVisualDefinition> systems = new LinkedHashMap<>();
            for (Object raw : ServerSaveStore.list(root.get("systems"))) {
                Map<String,Object> row = ServerSaveStore.object(raw);
                String id = required(row, "id", "system visual");
                SystemVisualDefinition def = new SystemVisualDefinition(
                        id,
                        rgb(text(row, "tint", "#142030"), id, "tint"),
                        number(row, "tintOpacity", 0.08, 0, 0.35),
                        rgb(text(row, "starColor", "#E8F4FF"), id, "starColor"),
                        rgb(text(row, "nebulaColor", "#345478"), id, "nebulaColor"),
                        integer(row, "starCount", 180, 0, 1200),
                        integer(row, "nebulaLayers", 0, 0, 8),
                        integer(row, "dustCount", 0, 0, 512),
                        number(row, "galacticBand", 0, 0, 1),
                        ServerSaveStore.longValue(row, "seed", 0));
                duplicate(systems, id, def, "system visual");
            }

            if (ships.isEmpty() && stations.isEmpty() && celestials.isEmpty() && systems.isEmpty()) {
                throw new IllegalStateException("Rich visual definition catalog is empty.");
            }
            return new VisualDefinitionCatalog(ships, stations, celestials, systems);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Could not load rich visual definitions: " + ex.getMessage(), ex);
        }
    }

    private static String celestialKey(String systemId, String bodyId) {
        return (systemId == null ? "" : systemId) + ":" + (bodyId == null ? "" : bodyId);
    }

    private static String required(Map<String,Object> row, String key, String label) {
        String value = text(row, key, "");
        if (value.isBlank()) throw new IllegalStateException(label + " requires " + key + ".");
        return value;
    }

    private static String text(Map<String,Object> row, String key, String fallback) {
        return ServerSaveStore.string(row, key, fallback).trim();
    }

    private static double number(Map<String,Object> row, String key, double fallback, double min, double max) {
        double value = ServerSaveStore.doubleValue(row, key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalStateException("Rich visual numeric field " + key + " is out of range.");
        }
        return value;
    }

    private static int integer(Map<String,Object> row, String key, int fallback, int min, int max) {
        int value = ServerSaveStore.intValue(row, key, fallback);
        if (value < min || value > max) {
            throw new IllegalStateException("Rich visual integer field " + key + " is out of range.");
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
            throw new IllegalStateException("Rich visual " + id + " has invalid " + field + ": " + value + ".");
        }
    }

    private static <K,V> void duplicate(Map<K,V> map, K key, V value, String label) {
        if (map.putIfAbsent(key, value) != null) throw new IllegalStateException("Duplicate " + label + ": " + key);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalStateException("Invalid " + label + ": " + value + "."); }
    }
}

enum ShipVisualPreset { MINING_CLAW, DEPLOYER, CARGO_FRAME, INDUSTRIAL, NEEDLE, WEDGE, SPINE, BATTLESHIP, FLIGHT_DECK, DREAD_SLAB, TITAN_SPINE }
enum ShipMarking { NONE, STRIPE, CHEVRON, INDUSTRIAL, COMMAND }
enum StationVisualPreset { OUTPOST, SHIPYARD, LABORATORY, FACTORY }
enum CelestialVisualClass { STAR, RED_STAR, PULSAR, ROCKY, DESERT, OCEAN, ICE, VOLCANIC, GAS_GIANT, MOON }

record ShipVisualDefinition(String id, ShipVisualPreset preset, double lengthScale, double widthScale,
                            double asymmetry, int armorSections, int engineCount, int podCount,
                            int hardpointCount, int hangarCount, int antennaCount,
                            int accentRgb, int glowRgb, ShipMarking marking) { }

record StationVisualDefinition(String id, StationVisualPreset preset, double scale, int ringCount,
                               int armCount, int moduleCount, int lightCount, int accentRgb) { }

record CelestialVisualDefinition(String systemId, String bodyId, CelestialVisualClass visualClass,
                                 int secondaryRgb, int atmosphereRgb, double atmosphereOpacity,
                                 int cloudLayers, boolean rings, int craters, int bands,
                                 boolean iceCaps, boolean lava, int nightLights, double corona) { }

record SystemVisualDefinition(String id, int tintRgb, double tintOpacity, int starColorRgb,
                              int nebulaColorRgb, int starCount, int nebulaLayers, int dustCount,
                              double galacticBand, long seed) { }
