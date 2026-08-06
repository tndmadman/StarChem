package com.tndmadman.rts;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Supplies the pieces an expedition needs before the persistent expedition
 * state machine commits resources or starts moving ships.
 *
 * Station deployers are disposable infrastructure, not permanent logistics
 * support, so this system may commission one even when the freighter/hauler/
 * salvager support cap is full. It also gives surviving ships time to evacuate
 * a selected target and prevents a silent RESERVING deadlock.
 */
final class NpcExpeditionReadinessSystem {
    private static final String BUILDER_TYPE = "station_builder";
    private static final double MIN_ROSTER_HP = 0.70;
    private static final double FRIENDLY_TRANSIT_GRACE_SECONDS = 45.0;
    private static final double ROSTER_TIMEOUT_SECONDS = 120.0;
    private static final double EPSILON = 0.001;
    private static final Map<World, Map<String, RuntimeState>> RUNTIMES = new WeakHashMap<>();

    private NpcExpeditionReadinessSystem() { }

    static synchronized void ensureInfrastructureBuilder(
            World world, NpcFaction faction, NpcStrategicState strategy) {
        if (world == null || faction == null || strategy != NpcStrategicState.ESTABLISH) return;
        if (!NpcFactionRuntime.homeSystemIdFor(faction).equals(world.activeSystemId())) return;
        if (NpcStationConstructionSystem.hasActivePlan(world, faction)) return;
        if (livingLocalStations(world, faction.id()) >= faction.homeInfrastructureTarget()) return;
        if (availableBuilder(world, faction) != null) return;
        commissionBuilder(world, faction, NpcBudgetCategory.STATION_RECOVERY,
                "commissioned infrastructure deployer");
    }

    /** Returns false when the expedition should remain paused this tick. */
    static synchronized boolean allowProgress(
            World world, NpcFaction faction, NpcStrategicState strategy, double dt) {
        if (world == null || faction == null || strategy != NpcStrategicState.EXPAND) return true;
        if (!NpcFactionRuntime.homeSystemIdFor(faction).equals(world.activeSystemId())) return true;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        NpcExpeditionSnapshot expedition = NpcExpeditionSystem.snapshot(world, faction);
        if (!expedition.active() || !preLaunch(expedition.state())) {
            runtime.resetWaits();
            setStatus(world, faction, runtime, "");
            return true;
        }

        double step = Double.isFinite(dt) && dt > 0 ? dt : 0;
        String planKey = expedition.targetSystemId() + "|" + expedition.state();
        if (!planKey.equals(runtime.planKey)) {
            runtime.planKey = planKey;
            runtime.transitSeconds = 0;
            runtime.rosterSeconds = 0;
        }

        FriendlyPresence presence = friendlyPresence(world, faction, expedition.targetSystemId());
        if (presence.transientUnits > 0 && presence.bases == 0) {
            runtime.transitSeconds += step;
            if (runtime.transitSeconds + EPSILON < FRIENDLY_TRANSIT_GRACE_SECONDS) {
                setStatus(world, faction, runtime,
                        "waiting for " + presence.transientUnits
                                + " surviving ship(s) to clear " + expedition.targetSystemId()
                                + " before reservation");
                return false;
            }
            setStatus(world, faction, runtime,
                    "friendly transit did not clear after "
                            + (int)FRIENDLY_TRANSIT_GRACE_SECONDS
                            + "s; releasing target for replanning");
            return true;
        }
        runtime.transitSeconds = 0;

        RosterReadiness readiness = rosterReadiness(world, faction);
        if (!readiness.builderReady) {
            if (commissionBuilder(world, faction, NpcBudgetCategory.EXPANSION,
                    "commissioned replacement expedition deployer")) {
                readiness = rosterReadiness(world, faction);
            }
        }

        if (readiness.ready(faction)) {
            runtime.rosterSeconds = 0;
            setStatus(world, faction, runtime, "");
            return true;
        }

        runtime.rosterSeconds += step;
        String blocker = readiness.blocker(faction);
        setStatus(world, faction, runtime, blocker);
        if (runtime.rosterSeconds + EPSILON < ROSTER_TIMEOUT_SECONDS) return false;

        AiDevLog.add(world, faction,
                "expedition reservation cancelled after "
                        + (int)ROSTER_TIMEOUT_SECONDS + "s: " + blocker);
        NpcFactionScopedRuntimeReset.cancelUnlaunchedExpedition(world, faction);
        runtime.resetWaits();
        runtime.planKey = "";
        setStatus(world, faction, runtime, "reservation timed out; plan refunded and released");
        return false;
    }

    static synchronized String status(World world, NpcFaction faction) {
        if (world == null || faction == null) return "";
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        return runtime.status;
    }

    static synchronized void clear(World world) {
        if (world != null) RUNTIMES.remove(world);
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String, RuntimeState> byFaction = RUNTIMES.get(world);
        if (byFaction == null) return out;
        List<Object> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, RuntimeState> entry : byFaction.entrySet()) {
            RuntimeState runtime = entry.getValue();
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("factionId", entry.getKey());
            row.put("seed", runtime.seed);
            row.put("planKey", runtime.planKey);
            row.put("status", runtime.status);
            row.put("transitSeconds", runtime.transitSeconds);
            row.put("rosterSeconds", runtime.rosterSeconds);
            rows.add(row);
        }
        out.put("runtimes", rows);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        Map<String, RuntimeState> byFaction = new LinkedHashMap<>();
        for (Object item : ServerSaveStore.list(data.get("runtimes"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String factionId = ServerSaveStore.string(row, "factionId", "");
            if (factionId.isBlank()) continue;
            RuntimeState runtime = new RuntimeState(ServerSaveStore.longValue(row, "seed", world.systemSeed()));
            runtime.planKey = ServerSaveStore.string(row, "planKey", "");
            runtime.status = ServerSaveStore.string(row, "status", "");
            runtime.transitSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "transitSeconds", 0));
            runtime.rosterSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "rosterSeconds", 0));
            byFaction.put(factionId, runtime);
        }
        if (byFaction.isEmpty()) RUNTIMES.remove(world);
        else RUNTIMES.put(world, byFaction);
    }

    static synchronized void clearFaction(World world, NpcFaction faction) {
        if (world == null || faction == null) return;
        Map<String, RuntimeState> byFaction = RUNTIMES.get(world);
        if (byFaction == null) return;
        byFaction.remove(faction.id());
        if (byFaction.isEmpty()) RUNTIMES.remove(world);
    }

    private static boolean preLaunch(NpcExpeditionState state) {
        return state == NpcExpeditionState.PLANNING
                || state == NpcExpeditionState.RESERVING;
    }

    private static RosterReadiness rosterReadiness(World world, NpcFaction faction) {
        boolean builder = false;
        boolean worker = false;
        int combat = 0;
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || NpcExpeditionSystem.ownsUnit(world, unit.key())) continue;
            double hpRatio = unit.hp / Math.max(1.0, unit.type().maxHp);
            if (hpRatio < MIN_ROSTER_HP) continue;
            if (!builder && unit.type().baseBuilder && unit.basePackageType.isBlank()
                    && !NpcStationConstructionSystem.ownsBuilder(world, unit.key())
                    && !NpcRecoverySystem.ownsUnit(world, unit)
                    && !NpcRepairEvacuationSystem.ownsUnit(world, unit)) {
                builder = true;
                continue;
            }
            if (!worker && !unit.type().harvestKinds.isEmpty()
                    && (faction.workerTypeSet().isEmpty()
                    || faction.workerTypeSet().contains(unit.shipTypeId))) {
                worker = true;
                continue;
            }
            if (WeaponRules.armed(unit)) combat++;
        }
        return new RosterReadiness(builder, worker, combat);
    }

    private static Unit availableBuilder(World world, NpcFaction faction) {
        return world.units.values().stream()
                .filter(unit -> faction.id().equals(unit.playerId) && unit.hp > 0)
                .filter(unit -> unit.type().baseBuilder && unit.basePackageType.isBlank())
                .filter(unit -> unit.hp / Math.max(1.0, unit.type().maxHp) >= MIN_ROSTER_HP)
                .filter(unit -> !NpcExpeditionSystem.ownsUnit(world, unit.key()))
                .filter(unit -> !NpcStationConstructionSystem.ownsBuilder(world, unit.key()))
                .filter(unit -> !NpcRecoverySystem.ownsUnit(world, unit))
                .filter(unit -> !NpcRepairEvacuationSystem.ownsUnit(world, unit))
                .min(Comparator.comparingInt(unit -> unit.unitId))
                .orElse(null);
    }

    private static boolean commissionBuilder(World world, NpcFaction faction,
                                             NpcBudgetCategory category,
                                             String event) {
        if (availableBuilder(world, faction) != null) return true;
        ShipType builderType = Rules.ship(BUILDER_TYPE);
        if (builderType == null || !ResearchRules.shipUnlocked(world, faction.id(), BUILDER_TYPE)) {
            return false;
        }
        Base producer = builderProducer(world, faction, builderType);
        if (producer == null) return false;
        if (!NpcResourceBudget.canAfford(world, faction, category, builderType.buildCost)
                || !NpcResourceBudget.spend(world, faction, category, builderType.buildCost)) {
            return false;
        }

        int unitId = nextGalaxyUnitId(world, faction.id());
        double angle = unitId * 1.35;
        Unit builder = new Unit(faction.id(), unitId, BUILDER_TYPE,
                Calc.clamp(producer.x + Math.cos(angle)
                        * (producer.type().buildRadius + 45), 0, world.width),
                Calc.clamp(producer.y + Math.sin(angle)
                        * (producer.type().buildRadius + 45), 0, world.height));
        world.units.put(builder.key(), builder);
        AiDevLog.add(world, faction, event + " #" + builder.unitId
                + " at " + producer.id + " (outside permanent support cap)");
        return true;
    }

    private static Base builderProducer(World world, NpcFaction faction, ShipType builderType) {
        Base best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0
                    || !base.type().buildableShips.contains(builderType.id)
                    || !StationFuelRules.isOperational(base)) continue;
            double score = 0;
            for (Cost cost : builderType.buildCost) {
                score += Math.min(cost.amount(),
                        base.inventory.getOrDefault(cost.material(), 0.0));
            }
            score -= base.productionQueue.size() * 25.0;
            if (best == null || score > bestScore
                    || (Math.abs(score - bestScore) <= EPSILON
                    && base.id.compareTo(best.id) < 0)) {
                best = base;
                bestScore = score;
            }
        }
        return best;
    }

    private static FriendlyPresence friendlyPresence(
            World world, NpcFaction faction, String targetSystemId) {
        if (targetSystemId == null || targetSystemId.isBlank()) return FriendlyPresence.NONE;
        GalaxyMapSystem target = null;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map != null && map.systems() != null) {
            for (GalaxyMapSystem system : map.systems()) {
                if (system != null && targetSystemId.equals(system.id())) {
                    target = system;
                    break;
                }
            }
        }
        if (target != null && faction.id().equals(target.controllerId())) {
            return FriendlyPresence.NONE;
        }

        String previous = world.activeSystemId();
        String previousStatus = world.status;
        int units = 0;
        int bases = 0;
        try {
            world.activateSystem(targetSystemId);
            for (Unit unit : world.units.values()) {
                if (faction.id().equals(unit.playerId) && unit.hp > 0) units++;
            }
            for (Base base : world.bases.values()) {
                if (faction.id().equals(base.playerId) && base.hp > 0) bases++;
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return new FriendlyPresence(units, bases);
    }

    private static int nextGalaxyUnitId(World world, String factionId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        int max = 0;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            if (map != null && map.systems() != null) {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    for (Unit unit : world.units.values()) {
                        if (factionId.equals(unit.playerId)) max = Math.max(max, unit.unitId);
                    }
                }
            } else {
                for (Unit unit : world.units.values()) {
                    if (factionId.equals(unit.playerId)) max = Math.max(max, unit.unitId);
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return max + 1;
    }

    private static int livingLocalStations(World world, String factionId) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) count++;
        }
        return count;
    }

    private static RuntimeState runtime(World world, NpcFaction faction) {
        Map<String, RuntimeState> byFaction = RUNTIMES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        return byFaction.computeIfAbsent(faction.id(),
                ignored -> new RuntimeState(world.systemSeed()));
    }

    private static void setStatus(World world, NpcFaction faction,
                                  RuntimeState runtime, String status) {
        String normalized = status == null ? "" : status;
        if (normalized.equals(runtime.status)) return;
        runtime.status = normalized;
        if (!normalized.isBlank()) AiDevLog.add(world, faction,
                "expedition readiness: " + normalized);
    }

    private static final class RuntimeState {
        long seed;
        String planKey = "";
        String status = "";
        double transitSeconds;
        double rosterSeconds;

        RuntimeState(long seed) { this.seed = seed; }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            planKey = "";
            status = "";
            resetWaits();
        }

        void resetWaits() {
            transitSeconds = 0;
            rosterSeconds = 0;
        }
    }

    private record RosterReadiness(boolean builderReady, boolean workerReady,
                                   int combatReady) {
        boolean ready(NpcFaction faction) {
            return builderReady && workerReady
                    && combatReady >= Math.max(2, faction.raidFleetSize());
        }

        String blocker(NpcFaction faction) {
            if (!builderReady) return "waiting for an available station deployer";
            if (!workerReady) return "waiting for a healthy expedition worker";
            int required = Math.max(2, faction.raidFleetSize());
            if (combatReady < required) {
                return "waiting for expedition combat roster "
                        + combatReady + "/" + required;
            }
            return "waiting for expedition roster";
        }
    }

    private record FriendlyPresence(int transientUnits, int bases) {
        static final FriendlyPresence NONE = new FriendlyPresence(0, 0);
    }
}
