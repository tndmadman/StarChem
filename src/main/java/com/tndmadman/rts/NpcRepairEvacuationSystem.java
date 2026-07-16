package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Evacuates damaged organized-faction ships from a surviving but unusable local
 * station to a reachable friendly repair system.
 *
 * The plan is galaxy-persistent and follows a real multi-hop wormhole route.
 * Healthy workers and industry remain behind; only damaged combat ships and a
 * bounded number of healthy escorts are reserved by the evacuation.
 */
final class NpcRepairEvacuationSystem {
    private static final double DAMAGED_RATIO = 0.72;
    private static final double NO_LOCAL_PATH_GRACE_SECONDS = 20.0;
    private static final double LOCAL_PROGRESS_TIMEOUT_SECONDS = 45.0;
    private static final double PROGRESS_EPSILON = 0.05;
    private static final Map<World, Map<String, RuntimeState>> RUNTIMES = new WeakHashMap<>();

    private NpcRepairEvacuationSystem() { }

    static synchronized void update(World world, NpcFaction faction, double dt) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) return;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());

        if (runtime.plan != null) {
            progressPlan(world, faction, runtime);
            return;
        }

        List<Unit> damaged = damagedCombat(world, faction.id());
        List<Base> bases = livingBases(world, faction.id());
        if (damaged.isEmpty() || bases.isEmpty()) {
            runtime.resetLocalAssessment();
            return;
        }

        RepairNeed need = repairNeed(damaged);
        double available = localRepairProgress(world, faction, need);
        boolean locallyReachable = localRepairReachable(world, faction, need);
        if (available > runtime.lastLocalProgress + PROGRESS_EPSILON) {
            runtime.lastLocalProgress = available;
            runtime.noProgressSeconds = 0;
        } else {
            runtime.noProgressSeconds += Math.max(0, dt);
        }
        if (locallyReachable) {
            runtime.noPathSeconds = 0;
            if (runtime.noProgressSeconds < LOCAL_PROGRESS_TIMEOUT_SECONDS) return;
        } else {
            runtime.noPathSeconds += Math.max(0, dt);
            if (runtime.noPathSeconds < NO_LOCAL_PATH_GRACE_SECONDS) return;
        }

        Destination destination = chooseDestination(world, faction, need);
        if (destination == null || destination.route.size() < 2) return;
        Set<String> roster = new LinkedHashSet<>();
        damaged.sort(Comparator.comparingDouble(NpcRepairEvacuationSystem::hpRatio)
                .thenComparingInt(unit -> unit.unitId));
        for (Unit unit : damaged) roster.add(unit.key());

        List<Unit> escorts = healthyEscorts(world, faction.id(), roster);
        for (int i = 0; i < Math.min(damaged.size(), escorts.size()); i++) {
            roster.add(escorts.get(i).key());
        }
        runtime.plan = new EvacuationPlan(
                world.activeSystemId(), destination.systemId,
                destination.route, roster);
        runtime.resetLocalAssessment();
        AiDevLog.add(world, faction,
                "repair evacuation started: " + world.activeSystemId()
                        + " -> " + destination.systemId
                        + " route=" + String.join(" -> ", destination.route));
        progressPlan(world, faction, runtime);
    }

    static synchronized boolean ownsUnit(World world, Unit unit) {
        if (world == null || unit == null) return false;
        Map<String, RuntimeState> byFaction = RUNTIMES.get(world);
        if (byFaction == null) return false;
        for (RuntimeState runtime : byFaction.values()) {
            if (runtime.plan != null && runtime.plan.unitKeys.contains(unit.key())) return true;
        }
        return false;
    }

    static synchronized void clearFaction(World world, NpcFaction faction) {
        if (world == null || faction == null) return;
        Map<String, RuntimeState> byFaction = RUNTIMES.get(world);
        if (byFaction == null) return;
        byFaction.remove(faction.id());
        if (byFaction.isEmpty()) RUNTIMES.remove(world);
    }

    static synchronized void clear(World world) {
        if (world != null) RUNTIMES.remove(world);
    }

    private static void progressPlan(World world, NpcFaction faction,
                                     RuntimeState runtime) {
        EvacuationPlan plan = runtime.plan;
        if (plan == null) return;

        MemberLocations locations = locateMembers(world, plan);
        plan.unitKeys.retainAll(locations.livingKeys());
        if (plan.unitKeys.isEmpty()) {
            runtime.plan = null;
            return;
        }

        Destination validated = validateOrReplaceDestination(world, faction, plan);
        if (validated == null) {
            releasePlan(world, faction, runtime,
                    "no reachable friendly repair system remains");
            return;
        }
        if (!validated.systemId.equals(plan.destinationSystemId)
                || !validated.route.equals(plan.route)) {
            plan.destinationSystemId = validated.systemId;
            plan.route.clear();
            plan.route.addAll(validated.route);
            AiDevLog.add(world, faction,
                    "repair evacuation rerouted to " + validated.systemId);
            locations = locateMembers(world, plan);
        }

        if (locations.allIn(plan.unitKeys, plan.destinationSystemId)) {
            releasePlan(world, faction, runtime,
                    "repair group reached " + plan.destinationSystemId);
            return;
        }

        String activeSystemId = world.activeSystemId();
        List<Unit> local = new ArrayList<>();
        for (String key : plan.unitKeys) {
            if (!activeSystemId.equals(locations.byKey.get(key))) continue;
            Unit unit = world.units.get(key);
            if (unit != null && unit.hp > 0) local.add(unit);
        }
        if (local.isEmpty() || activeSystemId.equals(plan.destinationSystemId)) return;

        int routeIndex = plan.route.indexOf(activeSystemId);
        if (routeIndex < 0 || routeIndex + 1 >= plan.route.size()) {
            Destination replacement = chooseDestination(
                    world, faction, repairNeed(local));
            if (replacement == null || replacement.route.size() < 2) return;
            plan.destinationSystemId = replacement.systemId;
            plan.route.clear();
            plan.route.addAll(replacement.route);
            routeIndex = 0;
        }
        String nextSystemId = plan.route.get(routeIndex + 1);
        WormholeGate gate = gateTo(world, nextSystemId);
        if (gate == null) return;
        for (Unit unit : local) unit.issueMove(gate.x, gate.y);
    }

    private static Destination validateOrReplaceDestination(World world,
                                                            NpcFaction faction,
                                                            EvacuationPlan plan) {
        RepairNeed need = repairNeedForKeys(world, plan.unitKeys, plan.route);
        if (destinationViable(world, faction, plan.destinationSystemId, need)) {
            List<String> route = shortestPath(
                    world.authoritativeGalaxyMapSnapshot(),
                    bestCurrentSystem(world, plan),
                    plan.destinationSystemId);
            if (!route.isEmpty()) return new Destination(
                    plan.destinationSystemId, route, 0);
        }
        return chooseDestination(world, faction, need);
    }

    private static Destination chooseDestination(World world, NpcFaction faction,
                                                 RepairNeed need) {
        String sourceSystemId = world.activeSystemId();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map == null || map.systems() == null) return null;
        List<Destination> choices = new ArrayList<>();
        for (GalaxyMapSystem system : map.systems()) {
            if (system == null || system.id() == null || system.id().isBlank()
                    || system.id().equals(sourceSystemId) || system.home()) continue;
            List<String> route = shortestPath(map, sourceSystemId, system.id());
            if (route.size() < 2) continue;
            double viability = destinationScore(world, faction, system.id(), need);
            if (viability < 0) continue;
            if (NpcFactionRuntime.homeSystemIdFor(faction).equals(system.id())) {
                viability += 1000;
            }
            viability -= (route.size() - 1) * 100;
            choices.add(new Destination(system.id(), route, viability));
        }
        return choices.stream()
                .max(Comparator.comparingDouble((Destination choice) -> choice.score)
                        .thenComparing(choice -> choice.systemId,
                                Comparator.reverseOrder()))
                .orElse(null);
    }

    private static boolean destinationViable(World world, NpcFaction faction,
                                             String systemId, RepairNeed need) {
        return destinationScore(world, faction, systemId, need) >= 0;
    }

    private static double destinationScore(World world, NpcFaction faction,
                                           String systemId, RepairNeed need) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            world.activateSystem(systemId);
            List<Base> bases = livingBases(world, faction.id());
            if (bases.isEmpty()) return -1;
            double iron = materialInBases(bases, Material.IRON);
            double copper = materialInBases(bases, Material.COPPER);
            boolean direct = iron + 0.001 >= need.iron
                    && copper + 0.001 >= need.copper;
            boolean mineable = materialReachable(world, faction, Material.IRON,
                    Math.max(0, need.iron - iron))
                    && materialReachable(world, faction, Material.COPPER,
                    Math.max(0, need.copper - copper));
            if (!direct && !mineable) return -1;
            double operational = bases.stream()
                    .anyMatch(StationFuelRules::isOperational) ? 120 : 0;
            return operational + Math.min(500, iron + copper);
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static boolean localRepairReachable(World world, NpcFaction faction,
                                                RepairNeed need) {
        List<Base> bases = livingBases(world, faction.id());
        double iron = materialInBases(bases, Material.IRON);
        double copper = materialInBases(bases, Material.COPPER);
        return materialReachable(world, faction, Material.IRON,
                Math.max(0, need.iron - iron))
                && materialReachable(world, faction, Material.COPPER,
                Math.max(0, need.copper - copper));
    }

    private static boolean materialReachable(World world, NpcFaction faction,
                                             Material material, double missing) {
        if (missing <= 0.001) return true;
        List<Unit> workers = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || unit.type().harvestKinds.isEmpty()) continue;
            workers.add(unit);
        }
        if (workers.isEmpty()) return false;
        double available = 0;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.amount <= 0.001
                    || node.material != material) continue;
            boolean harvestable = workers.stream()
                    .anyMatch(worker -> worker.type().harvestKinds.contains(node.kind));
            if (harvestable) available += node.amount;
        }
        return available + 0.001 >= missing;
    }

    private static double localRepairProgress(World world, NpcFaction faction,
                                              RepairNeed need) {
        double iron = 0;
        double copper = 0;
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            iron += base.inventory.getOrDefault(Material.IRON, 0.0);
            copper += base.inventory.getOrDefault(Material.COPPER, 0.0);
        }
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0) continue;
            iron += unit.inventory.getOrDefault(Material.IRON, 0.0);
            copper += unit.inventory.getOrDefault(Material.COPPER, 0.0);
        }
        return Math.min(need.iron, iron) + Math.min(need.copper, copper);
    }

    private static RepairNeed repairNeed(List<Unit> damaged) {
        double iron = 0;
        double copper = 0;
        for (Unit unit : damaged) {
            double missingHp = Math.max(0, unit.type().maxHp - unit.hp);
            iron += Math.max(0.05, missingHp * 0.04);
            copper += Math.max(0.02, missingHp * 0.015);
        }
        return new RepairNeed(iron, copper);
    }

    private static RepairNeed repairNeedForKeys(World world, Set<String> keys,
                                                List<String> route) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        List<Unit> units = new ArrayList<>();
        try {
            for (String systemId : route) {
                world.activateSystem(systemId);
                for (String key : keys) {
                    Unit unit = world.units.get(key);
                    if (unit != null && unit.hp > 0 && WeaponRules.armed(unit.type())) {
                        units.add(unit);
                    }
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return repairNeed(units);
    }

    private static List<Unit> damagedCombat(World world, String factionId) {
        List<Unit> result = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!factionId.equals(unit.playerId) || unit.hp <= 0
                    || !WeaponRules.armed(unit.type())) continue;
            if (hpRatio(unit) < DAMAGED_RATIO) result.add(unit);
        }
        return result;
    }

    private static List<Unit> healthyEscorts(World world, String factionId,
                                             Set<String> excluded) {
        List<Unit> result = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!factionId.equals(unit.playerId) || unit.hp <= 0
                    || excluded.contains(unit.key())
                    || !WeaponRules.armed(unit.type())
                    || hpRatio(unit) < 0.85) continue;
            result.add(unit);
        }
        result.sort(Comparator.comparingInt(unit -> unit.unitId));
        return result;
    }

    private static List<Base> livingBases(World world, String factionId) {
        List<Base> result = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) result.add(base);
        }
        return result;
    }

    private static double materialInBases(List<Base> bases, Material material) {
        double total = 0;
        for (Base base : bases) {
            total += base.inventory.getOrDefault(material, 0.0);
        }
        return total;
    }

    private static MemberLocations locateMembers(World world, EvacuationPlan plan) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        Map<String, String> locations = new LinkedHashMap<>();
        try {
            for (String systemId : plan.route) {
                world.activateSystem(systemId);
                for (String key : plan.unitKeys) {
                    Unit unit = world.units.get(key);
                    if (unit != null && unit.hp > 0) locations.put(key, systemId);
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return new MemberLocations(locations);
    }

    private static String bestCurrentSystem(World world, EvacuationPlan plan) {
        MemberLocations locations = locateMembers(world, plan);
        for (int i = plan.route.size() - 1; i >= 0; i--) {
            String systemId = plan.route.get(i);
            if (locations.byKey.containsValue(systemId)) return systemId;
        }
        return world.activeSystemId();
    }

    private static WormholeGate gateTo(World world, String systemId) {
        for (WormholeGate gate : world.wormholes) {
            if (systemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static List<String> shortestPath(GalaxyMapSnapshot map,
                                             String source, String target) {
        if (map == null || source == null || target == null
                || source.isBlank() || target.isBlank()) return List.of();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        Set<String> forbiddenHomes = new HashSet<>();
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && system.home()
                    && !source.equals(system.id()) && !target.equals(system.id())) {
                forbiddenHomes.add(system.id());
            }
        }
        for (GalaxyMapLink link : map.links()) {
            if (forbiddenHomes.contains(link.fromSystemId())
                    || forbiddenHomes.contains(link.toSystemId())) continue;
            adjacency.computeIfAbsent(link.fromSystemId(), ignored -> new ArrayList<>())
                    .add(link.toSystemId());
            adjacency.computeIfAbsent(link.toSystemId(), ignored -> new ArrayList<>())
                    .add(link.fromSystemId());
        }
        for (List<String> neighbors : adjacency.values()) neighbors.sort(String::compareTo);
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new HashSet<>();
        queue.add(source);
        visited.add(source);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (current.equals(target)) break;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!visited.add(next)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }
        if (!visited.contains(target)) return List.of();
        List<String> route = new ArrayList<>();
        String current = target;
        route.add(current);
        while (!current.equals(source)) {
            current = previous.get(current);
            if (current == null) return List.of();
            route.add(current);
        }
        java.util.Collections.reverse(route);
        return route;
    }

    private static void releasePlan(World world, NpcFaction faction,
                                    RuntimeState runtime, String reason) {
        if (runtime.plan != null) {
            AiDevLog.add(world, faction,
                    "repair evacuation ended: " + reason);
        }
        runtime.plan = null;
        runtime.resetLocalAssessment();
    }

    private static double hpRatio(Unit unit) {
        return unit.hp / Math.max(1.0, unit.type().maxHp);
    }

    private static RuntimeState runtime(World world, NpcFaction faction) {
        Map<String, RuntimeState> byFaction = RUNTIMES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        return byFaction.computeIfAbsent(faction.id(),
                ignored -> new RuntimeState(world.systemSeed()));
    }

    private static final class RuntimeState {
        long seed;
        double noPathSeconds;
        double noProgressSeconds;
        double lastLocalProgress;
        EvacuationPlan plan;

        RuntimeState(long seed) { this.seed = seed; }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            plan = null;
            resetLocalAssessment();
        }

        void resetLocalAssessment() {
            noPathSeconds = 0;
            noProgressSeconds = 0;
            lastLocalProgress = 0;
        }
    }

    private static final class EvacuationPlan {
        final String sourceSystemId;
        String destinationSystemId;
        final List<String> route = new ArrayList<>();
        final Set<String> unitKeys = new LinkedHashSet<>();

        EvacuationPlan(String sourceSystemId, String destinationSystemId,
                       List<String> route, Set<String> unitKeys) {
            this.sourceSystemId = sourceSystemId;
            this.destinationSystemId = destinationSystemId;
            this.route.addAll(route);
            this.unitKeys.addAll(unitKeys);
        }
    }

    private record RepairNeed(double iron, double copper) { }
    private record Destination(String systemId, List<String> route, double score) { }

    private static final class MemberLocations {
        final Map<String, String> byKey;

        MemberLocations(Map<String, String> byKey) { this.byKey = byKey; }

        Set<String> livingKeys() { return byKey.keySet(); }

        boolean allIn(Set<String> keys, String systemId) {
            if (keys.isEmpty()) return false;
            for (String key : keys) {
                if (!systemId.equals(byKey.get(key))) return false;
            }
            return true;
        }
    }
}
