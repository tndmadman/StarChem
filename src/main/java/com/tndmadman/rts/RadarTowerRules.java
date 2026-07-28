package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RadarTowerRules {
    static final String TIER_ONE = "radar_picket";
    static final String TIER_TWO = "radar_array";
    static final String TIER_THREE = "radar_nexus";

    private static final Map<String, RadarTowerTier> TIERS = tiers();

    private RadarTowerRules() { }

    static boolean isRadarTower(String stationTypeId) {
        return TIERS.containsKey(stationTypeId);
    }

    static RadarTowerTier tier(String stationTypeId) {
        return TIERS.get(stationTypeId);
    }

    static int tierNumber(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        return tier == null ? 0 : tier.tier();
    }

    static double sensorRange(String stationTypeId) {
        RadarTowerTier tier = tier(stationTypeId);
        return tier == null ? 0 : tier.sensorRange();
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
        return List.copyOf(TIERS.values());
    }

    private static Map<String, RadarTowerTier> tiers() {
        Map<String, RadarTowerTier> tiers = new LinkedHashMap<>();
        tiers.put(TIER_ONE, new RadarTowerTier(TIER_ONE, 1, 1_500, ""));
        tiers.put(TIER_TWO, new RadarTowerTier(TIER_TWO, 2, 2_800, "advanced_industry"));
        tiers.put(TIER_THREE, new RadarTowerTier(TIER_THREE, 3, 4_800, "battlefleet_engineering"));
        return Map.copyOf(tiers);
    }

    record RadarTowerTier(String stationTypeId, int tier, double sensorRange, String requiredResearchId) {
        RadarTowerTier {
            stationTypeId = stationTypeId == null ? "" : stationTypeId;
            tier = Math.max(1, tier);
            sensorRange = Math.max(0, sensorRange);
            requiredResearchId = requiredResearchId == null ? "" : requiredResearchId;
        }
    }
}
