package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class StationPackageResearchRules {
    private static final Map<String,String> REQUIRED = load();

    private StationPackageResearchRules() { }

    static boolean unlocked(World world, String playerId, String stationTypeId) {
        String required = requiredResearchId(stationTypeId);
        return required.isBlank() || world != null && world.hasResearch(playerId, required);
    }

    static String requiredResearchId(String stationTypeId) {
        return stationTypeId == null ? "" : REQUIRED.getOrDefault(stationTypeId, "");
    }

    static String requiredResearchName(String stationTypeId) {
        String id = requiredResearchId(stationTypeId);
        if (id.isBlank()) return "";
        ResearchTopic topic = ResearchRules.topic(id);
        return topic == null ? id : topic.name;
    }

    private static Map<String,String> load() {
        Path stations = stationConfigPath();
        if (!Files.exists(stations)) throw new RuleConfigurationException("Missing station configuration: " + stations);
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(stations)));
            Map<String,Object> types = object(root.getOrDefault("stationTypes", root));
            Map<String,String> out = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : types.entrySet()) {
                Map<String,Object> row = object(entry.getValue());
                String required = string(row, "requiredResearch", "").trim();
                if (!required.isBlank() && ResearchRules.topic(required) == null) {
                    throw new RuleConfigurationException("Station " + entry.getKey()
                            + " references unknown research: " + required);
                }
                out.put(entry.getKey(), required);
            }
            return Collections.unmodifiableMap(out);
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load station research gates from " + stations + ": " + ex.getMessage());
        }
    }

    private static Path stationConfigPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return Path.of("config/stations.json");
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            return Path.of(string(files, "stations", "config/stations.json"));
        } catch (Exception ignored) {
            return Path.of("config/stations.json");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
