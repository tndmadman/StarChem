package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RadarTowerRules {
    static final String TIER_ONE = "radar_picket";
    static final String TIER_TWO = "radar_array";
    static final String TIER_THREE = "radar_nexus";
    private static final String RADAR_ROLE = "radar";

    private static final Map<String, RadarTowerTier> TIERS = load();

    private RadarTowerRules() { }

    static boolean isRadarTower(String stationTypeId) {
        return stationTypeId != null && TIERS.containsKey(stationTypeId);
    }

    static RadarTowerTier tier(String stationTypeId) {
        return stationTypeId == null ? null : TIERS.get(stationTypeId);
    }

    static int tierNumber(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        return tier == null ? 0 : tier.tier();
    }

    static double sensorRange(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        return tier == null ? 0 : tier.sensorRange();
    }

    static int resourceDispatchLimit(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        return tier == null ? 0 : tier.resourceDispatchLimit();
    }

    static boolean unlocked(World world, String playerId, String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        if (tier == null || tier.requiredResearchId().isBlank()) return true;
        return world != null && world.hasResearch(playerId, tier.requiredResearchId());
    }

    static ResearchTopic requiredResearch(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        if (tier == null || tier.requiredResearchId().isBlank()) return null;
        return ResearchRules.topic(tier.requiredResearchId());
    }

    static String requiredResearchName(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        if (tier == null || tier.requiredResearchId().isBlank()) return "";
        ResearchTopic topic = ResearchRules.topic(tier.requiredResearchId());
        return topic == null ? tier.requiredResearchId() : topic.name;
    }

    static List<RadarTowerTier> all() {
        List<RadarTowerTier> out = new ArrayList<>(TIERS.values());
        out.sort(Comparator.comparingInt(RadarTowerTier::tier).thenComparing(RadarTowerTier::stationTypeId));
        return List.copyOf(out);
    }

    private static Map<String, RadarTowerTier> load() {
        // Normal games load ships from JSON, but this also removes the old emergency fallback Scout hull.
        Rules.SHIPS.remove("scout");
        Path stations = stationConfigPath();
        if (!Files.exists(stations)) throw new RuleConfigurationException("Missing station configuration: " + stations);
        try {
            Object parsed = MiniJson.parse(Files.readString(stations));
            Map<String,Object> root = object(parsed);
            Map<String,Object> stationTypes = object(root.getOrDefault("stationTypes", root));
            Map<String,RadarTowerTier> out = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : stationTypes.entrySet()) {
                Map<String,Object> station = object(entry.getValue());
                if (!RADAR_ROLE.equals(string(station, "role", "").trim().toLowerCase(Locale.ROOT))) continue;
                String id = entry.getKey();
                int tier = integer(station, "radarTier", 0);
                double sensorRange = number(station, "sensorRange", 0);
                String requiredResearch = string(station, "requiredResearch", "").trim();
                int dispatchLimit = integer(station, "resourceDispatchLimit", 0);
                if (tier <= 0) throw new RuleConfigurationException("Radar station " + id + " requires radarTier >= 1.");
                if (!Double.isFinite(sensorRange) || sensorRange <= 0) {
                    throw new RuleConfigurationException("Radar station " + id + " requires a positive sensorRange.");
                }
                if (dispatchLimit < 0) {
                    throw new RuleConfigurationException("Radar station " + id + " requires resourceDispatchLimit >= 0.");
                }
                if (!requiredResearch.isBlank() && ResearchRules.topic(requiredResearch) == null) {
                    throw new RuleConfigurationException("Radar station " + id + " references unknown research: " + requiredResearch);
                }
                out.put(id, new RadarTowerTier(id, tier, sensorRange, requiredResearch, dispatchLimit));
            }
            if (out.isEmpty()) throw new RuleConfigurationException("No stations with role 'radar' were configured.");
            return Collections.unmodifiableMap(out);
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load radar station rules from " + stations + ": " + ex.getMessage());
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

    private static int integer(Map<String,Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    record RadarTowerTier(String stationTypeId, int tier, double sensorRange, String requiredResearchId,
                          int resourceDispatchLimit) {
        RadarTowerTier {
            stationTypeId = stationTypeId == null ? "" : stationTypeId;
            tier = Math.max(1, tier);
            sensorRange = Math.max(0, sensorRange);
            requiredResearchId = requiredResearchId == null ? "" : requiredResearchId;
            resourceDispatchLimit = Math.max(0, resourceDispatchLimit);
        }
    }
}
