package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class StationFuelRules {
    private static final Map<String, StationFuelRequirement> REQUIREMENTS = load();

    private StationFuelRules() { }

    static StationFuelRequirement requirement(String stationTypeId) {
        return REQUIREMENTS.get(stationTypeId);
    }

    static boolean isOperational(Base base) {
        StationFuelRequirement req = requirement(base.typeId);
        if (req == null) return true;
        return base.inventory.getOrDefault(req.material(), 0.0) > 0.05;
    }

    static void consume(World world, double dt) {
        if (dt <= 0) return;
        for (Base base : world.bases.values()) {
            StationFuelRequirement req = requirement(base.typeId);
            if (req == null || req.perSecond() <= 0) continue;
            double held = base.inventory.getOrDefault(req.material(), 0.0);
            if (held <= 0.001) continue;
            double next = held - req.perSecond() * dt;
            if (next <= 0.05) base.inventory.remove(req.material());
            else base.inventory.put(req.material(), next);
        }
    }

    private static Map<String, StationFuelRequirement> load() {
        Map<String, StationFuelRequirement> out = new LinkedHashMap<>();
        if (Files.exists(Path.of("config/stations.json"))) {
            try {
                parseStations(readObject(Path.of("config/stations.json")), out);
            } catch (Exception ex) {
                System.err.println("Could not load station fuel rules: " + ex.getMessage());
            }
        }
        if (out.isEmpty()) out.put("laboratory", new StationFuelRequirement(Material.FUEL, 0.25));
        return Map.copyOf(out);
    }

    private static void parseStations(Map<String,Object> doc, Map<String, StationFuelRequirement> out) {
        Map<String,Object> source = object(doc.getOrDefault("stationTypes", doc));
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> station = object(e.getValue());
            if (station.isEmpty()) continue;
            Map<String,Object> fuel = object(station.get("fuel"));
            String materialId = null;
            double perSecond = 0;
            if (!fuel.isEmpty()) {
                materialId = string(fuel, "material", "");
                perSecond = number(fuel, "perSecond", 0);
            } else if (station.containsKey("fuelMaterial") || station.containsKey("fuelUsePerSecond")) {
                materialId = string(station, "fuelMaterial", "");
                perSecond = number(station, "fuelUsePerSecond", 0);
            }
            if (materialId == null || materialId.isBlank() || perSecond <= 0) continue;
            out.put(e.getKey(), new StationFuelRequirement(material(materialId), perSecond));
        }
    }

    private static Map<String,Object> readObject(Path path) throws IOException {
        Object parsed = MiniJson.parse(Files.readString(path));
        return object(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return (Map<String,Object>) map;
        return Map.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Material material(String value) {
        try { return Material.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw new IllegalArgumentException("Unknown material: " + value); }
    }
}

record StationFuelRequirement(Material material, double perSecond) { }
