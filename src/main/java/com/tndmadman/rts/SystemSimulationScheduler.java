package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class SystemSimulationScheduler {
    private static final double WARM_STEP_SECONDS = 0.12;
    private static final double COLD_STEP_SECONDS = 0.75;
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
        double threshold = tier == SimulationTier.WARM ? WARM_STEP_SECONDS : COLD_STEP_SECONDS;
        if (next + 0.000001 < threshold) {
            bySystem.put(systemId, next);
            return 0;
        }
        bySystem.put(systemId, 0.0);
        return Math.min(next, threshold * 2.0);
    }

    static synchronized void removeSystems(World world, Iterable<String> systemIds) {
        Map<String, Double> bySystem = ACCUMULATED.get(world);
        if (bySystem == null || systemIds == null) return;
        for (String systemId : systemIds) bySystem.remove(systemId);
    }

    private static SimulationTier tier(World world) {
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
        return npcAssets ? SimulationTier.WARM : SimulationTier.COLD;
    }

    private enum SimulationTier { HOT, WARM, COLD }
}
