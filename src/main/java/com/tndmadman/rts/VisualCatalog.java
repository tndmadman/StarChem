package com.tndmadman.rts;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Central lookup point for renderer-only authored visual definitions. */
final class VisualCatalog {
    private static final Map<String, ShipVisualDefinition> SHIPS = new ConcurrentHashMap<>();
    private static final Map<String, StationVisualDefinition> STATIONS = new ConcurrentHashMap<>();
    private static final Map<String, SystemVisualDefinition> SYSTEMS = new ConcurrentHashMap<>();
    private static final Map<String, CelestialVisualDefinition> CELESTIALS = new ConcurrentHashMap<>();
    private static final Map<String, ResourceVisualDefinition> RESOURCES = new ConcurrentHashMap<>();

    private VisualCatalog() { }

    static ShipVisualDefinition ship(ShipType type) {
        if (type == null) return ShipVisualDefinition.conventional("unknown");
        String typeId = type.id;
        ShipVisualDefinition authored = SHIPS.get(typeId);
        return authored != null ? authored : ShipVisualDefinition.conventional(typeId);
    }

    static StationVisualDefinition station(String stationId) {
        StationVisualDefinition authored = STATIONS.get(stationId);
        return authored != null ? authored : StationVisualDefinition.conventional(stationId);
    }

    static SystemVisualDefinition system(String systemId) {
        SystemVisualDefinition authored = SYSTEMS.get(systemId);
        return authored != null ? authored : SystemVisualDefinition.conventional(systemId);
    }

    static CelestialVisualDefinition celestial(String bodyId) {
        CelestialVisualDefinition authored = CELESTIALS.get(bodyId);
        return authored != null ? authored : CelestialVisualDefinition.conventional(bodyId);
    }

    static ResourceVisualDefinition resource(String resourceId) {
        ResourceVisualDefinition authored = RESOURCES.get(resourceId);
        return authored != null ? authored : ResourceVisualDefinition.conventional(resourceId);
    }

    static void registerShip(String shipTypeId, ShipVisualDefinition definition) {
        register(SHIPS, shipTypeId, definition);
    }

    static void registerStation(String stationId, StationVisualDefinition definition) {
        register(STATIONS, stationId, definition);
    }

    static void registerSystem(String systemId, SystemVisualDefinition definition) {
        register(SYSTEMS, systemId, definition);
    }

    static void registerCelestial(String bodyId, CelestialVisualDefinition definition) {
        register(CELESTIALS, bodyId, definition);
    }

    static void registerResource(String resourceId, ResourceVisualDefinition definition) {
        register(RESOURCES, resourceId, definition);
    }

    private static <T> void register(Map<String, T> map, String id, T definition) {
        if (id == null || id.isBlank() || definition == null) return;
        map.put(id, definition);
    }

    static String safeId(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        boolean separator = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                result.append(c);
                separator = false;
            } else if (!separator && result.length() > 0) {
                result.append('-');
                separator = true;
            }
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '-') {
            result.setLength(result.length() - 1);
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }
}