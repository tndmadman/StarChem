package com.tndmadman.rts;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Galaxy-wide, read-only metrics used by declarative scenario triggers. */
final class ScenarioMetrics {
    private final Set<String> players = new LinkedHashSet<>();
    private final Map<String,Integer> ships = new HashMap<>();
    private final Map<String,Integer> combatShips = new HashMap<>();
    private final Map<String,Integer> stations = new HashMap<>();
    private final Map<String,Integer> liveAssets = new HashMap<>();
    private final Map<String,Integer> fleetPower = new HashMap<>();
    private final Map<String,Integer> controlledSystems = new HashMap<>();
    private final Map<String,Map<String,Integer>> shipTypes = new HashMap<>();
    private final Map<String,Map<String,Integer>> stationTypes = new HashMap<>();
    private double maxSystemTime;

    private ScenarioMetrics() { }

    static ScenarioMetrics collect(World world) {
        ScenarioMetrics metrics = new ScenarioMetrics();
        if (world == null) return metrics;
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) metrics.players.add(player.id());
        metrics.players.addAll(world.completedResearch.keySet());

        GalaxyMapSnapshot galaxy = world.authoritativeGalaxyMapSnapshot();
        Set<String> visited = new LinkedHashSet<>();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) {
                if (system == null) continue;
                if (system.id() != null && !system.id().isBlank()) visited.add(system.id());
                if (human(system.controllerId())) {
                    metrics.controlledSystems.merge(system.controllerId(), 1, Integer::sum);
                    metrics.players.add(system.controllerId());
                }
            }
        }

        String previousSystem = world.activeSystemId();
        String previousStatus = world.status;
        if (visited.isEmpty() && previousSystem != null && !previousSystem.isBlank()) visited.add(previousSystem);
        try {
            for (String systemId : visited) {
                world.activateSystem(systemId);
                metrics.maxSystemTime = Math.max(metrics.maxSystemTime, world.systemTime());
                for (Unit unit : world.units.values()) {
                    if (unit.hp <= 0 || !human(unit.playerId)) continue;
                    metrics.players.add(unit.playerId);
                    metrics.ships.merge(unit.playerId, 1, Integer::sum);
                    metrics.liveAssets.merge(unit.playerId, 1, Integer::sum);
                    if (WeaponRules.armed(unit)) metrics.combatShips.merge(unit.playerId, 1, Integer::sum);
                    metrics.shipTypes.computeIfAbsent(unit.playerId, ignored -> new HashMap<>())
                            .merge(unit.shipTypeId, 1, Integer::sum);
                    metrics.fleetPower.merge(unit.playerId, strength(unit.hp, unit.shield), Integer::sum);
                }
                for (Base base : world.bases.values()) {
                    if (base.hp <= 0 || !human(base.playerId)) continue;
                    metrics.players.add(base.playerId);
                    metrics.stations.merge(base.playerId, 1, Integer::sum);
                    metrics.liveAssets.merge(base.playerId, 1, Integer::sum);
                    metrics.stationTypes.computeIfAbsent(base.playerId, ignored -> new HashMap<>())
                            .merge(base.typeId, 1, Integer::sum);
                    metrics.fleetPower.merge(base.playerId, strength(base.hp, base.shield), Integer::sum);
                }
            }
        } finally {
            if (previousSystem != null && !previousSystem.isBlank()) world.activateSystem(previousSystem);
            world.status = previousStatus;
        }
        return metrics;
    }

    Set<String> players() { return Set.copyOf(players); }
    int ships(String id) { return ships.getOrDefault(id, 0); }
    int combatShips(String id) { return combatShips.getOrDefault(id, 0); }
    int stations(String id) { return stations.getOrDefault(id, 0); }
    int liveAssets(String id) { return liveAssets.getOrDefault(id, 0); }
    int fleetPower(String id) { return fleetPower.getOrDefault(id, 0); }
    int controlledSystems(String id) { return controlledSystems.getOrDefault(id, 0); }
    Map<String,Integer> shipTypes(String id) { return shipTypes.getOrDefault(id, Map.of()); }
    Map<String,Integer> stationTypes(String id) { return stationTypes.getOrDefault(id, Map.of()); }
    double maxSystemTime() { return maxSystemTime; }

    private static int strength(double hp, double shield) {
        double total = Math.max(0, hp) + Math.max(0, shield);
        if (!Double.isFinite(total)) return 0;
        return (int)Math.min(Integer.MAX_VALUE, Math.round(total));
    }

    private static boolean human(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }
}
