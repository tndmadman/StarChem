package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class StrategicInfrastructureRules {
    private StrategicInfrastructureRules() { }

    static boolean isStrategic(Base base) {
        return base != null && base.hp > 0 && isStrategicType(base.typeId);
    }

    static boolean isStrategicType(String typeId) {
        return isCommandType(typeId) || isLogisticsType(typeId) || isRepairType(typeId)
                || isIntelType(typeId) || isDefensiveIntelType(typeId);
    }

    static boolean isCommandType(String typeId) {
        return "outpost".equals(typeId);
    }

    static boolean isLogisticsType(String typeId) {
        return "manufacturing".equals(typeId);
    }

    static boolean isRepairType(String typeId) {
        return "shipyard".equals(typeId);
    }

    static boolean isIntelType(String typeId) {
        return RadarTowerRules.isRadarTower(typeId);
    }

    static boolean isDefensiveIntelType(String typeId) {
        return "signal_jammer".equals(typeId) || "radar_decoy".equals(typeId);
    }

    static double controlInfluence(Base base) {
        if (!isStrategic(base)) return 0;
        if (isCommandType(base.typeId)) return 2.0;
        if (isRepairType(base.typeId) || isLogisticsType(base.typeId)) return 1.0;
        if (isIntelType(base.typeId)) return 0.5;
        return 0.25;
    }

    static boolean hasLogisticsNode(Collection<Base> bases, String ownerId) {
        if (bases == null || ownerId == null || ownerId.isBlank()) return false;
        for (Base base : bases) {
            if (base != null && base.hp > 0 && ownerId.equals(base.playerId)
                    && (isLogisticsType(base.typeId) || isRepairType(base.typeId) || isCommandType(base.typeId))) {
                return true;
            }
        }
        return false;
    }

    static List<String> labels(Collection<Base> bases, String ownerId) {
        if (bases == null || ownerId == null || ownerId.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (Base base : bases) {
            if (base == null || base.hp <= 0 || !ownerId.equals(base.playerId) || !isStrategic(base)) continue;
            String label = base.type().name;
            if (!out.contains(label)) out.add(label);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }
}
