package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class SystemSimulationScheduler {
    private static final double WARM_STEP_SECONDS = 0.12;
    private static final double COLD_STEP_SECONDS = 0.75;
    private static final double DORMANT_STEP_SECONDS = 5.0;
    private static final Map<World, Map<String, Double>> ACCUMULATED = new WeakHashMap<>();

    private SystemSimulationScheduler() { }

    static synchronized double step(World world, double dt) {
        if (world == null || dt <= 0) return 0;
        SimulationTier tier = tier(world);
        if (tier == SimulationTier.HOT) return dt;

        String systemId = world.activeSystemId();
        if (systemId == null || systemId.isBlank()) return dt;
        Map<String, Double> bySystem = ACCUMULATED.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
        double next = bySystem.getOrDefault(systemId, 0.0) + dt;
        double threshold = intervalSeconds(tier);
        if (next + 0.000001 < threshold) {
            bySystem.put(systemId, next);
            return 0;
        }
        bySystem.put(systemId, 0.0);
        // Batching may delay an inactive system, but it must never delete elapsed
        // simulation time when the authoritative scheduler releases it.
        return next;
    }

    static double intervalSeconds(SimulationTier tier) {
        if (tier == null) return DORMANT_STEP_SECONDS;
        return switch (tier) {
            case HOT -> 0;
            case WARM -> WARM_STEP_SECONDS;
            case COLD -> COLD_STEP_SECONDS;
            case DORMANT -> DORMANT_STEP_SECONDS;
        };
    }

    static synchronized void removeSystems(World world, Iterable<String> systemIds) {
        Map<String, Double> bySystem = ACCUMULATED.get(world);
        if (bySystem == null || systemIds == null) return;
        for (String systemId : systemIds) bySystem.remove(systemId);
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String, Double> bySystem = ACCUMULATED.get(world);
        if (bySystem != null) {
            for (Map.Entry<String, Double> entry : bySystem.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                    out.put(entry.getKey(), Math.max(0, entry.getValue()));
                }
            }
        }
        return out;
    }

    static synchronized void restore(World world, Object state) {
        if (world == null) return;
        Map<String,Object> saved = ServerSaveStore.object(state);
        Map<String, Double> bySystem = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : saved.entrySet()) {
            double value = ServerSaveStore.asDouble(entry.getValue(), 0);
            if (entry.getKey() != null && !entry.getKey().isBlank() && value > 0) {
                bySystem.put(entry.getKey(), value);
            }
        }
        if (bySystem.isEmpty()) ACCUMULATED.remove(world);
        else ACCUMULATED.put(world, bySystem);
    }

    static SimulationTier tier(World world) {
        if (world == null) return SimulationTier.DORMANT;
        boolean npcAssets = false;
        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0) continue;
            if (!NpcRules.isNpcFaction(unit.playerId)) return SimulationTier.HOT;
            npcAssets = true;
            if (unit.task == UnitTask.ATTACK || !unit.attackTarget.isBlank()) return SimulationTier.HOT;
        }
        for (Base base : world.bases.values()) {
            if (base.hp <= 0) continue;
            if (!NpcRules.isNpcFaction(base.playerId)) return SimulationTier.HOT;
            npcAssets = true;
        }
        if (!world.shots.isEmpty()) return SimulationTier.HOT;
        if (npcAssets) return SimulationTier.WARM;
        if (!world.items.isEmpty() || !world.explosions.isEmpty()) return SimulationTier.COLD;
        return SimulationTier.DORMANT;
    }

    enum SimulationTier { HOT, WARM, COLD, DORMANT }
}
