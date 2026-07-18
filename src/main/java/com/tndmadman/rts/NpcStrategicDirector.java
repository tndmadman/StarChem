package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Owns one strategic objective per organized faction and World instance.
 *
 * Local NpcSystem instances remain responsible for executing orders in their
 * active system, but they all consult this shared director so production,
 * research, raids, expansion, and recovery support one galaxy-wide objective.
 */
final class NpcStrategicDirector {
    private static final Map<World, Map<String, RuntimeState>> RUNTIMES = new WeakHashMap<>();

    private NpcStrategicDirector() { }

    static synchronized NpcStrategicState update(World world, NpcFaction faction, double dt) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) {
            return NpcStrategicState.ESTABLISH;
        }

        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        if (!runtime.homeSystemId.equals(world.activeSystemId())) return runtime.state;

        if (Double.isFinite(dt) && dt > 0) {
            runtime.reviewTimer -= dt;
            runtime.stateSeconds += dt;
        }
        if (runtime.initialized && runtime.reviewTimer > 0) return runtime.state;

        NpcStrategicSnapshot snapshot = inspect(world, faction);
        NpcStrategicState next = choose(world, runtime, faction, snapshot);
        transition(world, faction, runtime, next, snapshot);
        runtime.snapshot = snapshot;
        runtime.initialized = true;
        runtime.reviewTimer = Math.max(1.0, faction.orderSeconds());
        return runtime.state;
    }

    static synchronized NpcStrategicState state(World world, NpcFaction faction) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) {
            return NpcStrategicState.ESTABLISH;
        }
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        return runtime.state;
    }

    static synchronized boolean initialized(World world, NpcFaction faction) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) {
            return false;
        }
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        return runtime.initialized;
    }

    static synchronized NpcStrategicSnapshot snapshot(World world, NpcFaction faction) {
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        return runtime.snapshot;
    }

    static synchronized int transitionCount(World world, NpcFaction faction) {
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        return runtime.transitionCount;
    }

    static synchronized void onSpawned(World world, NpcFaction faction) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) return;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        runtime.state = NpcStrategicState.ESTABLISH;
        runtime.stateSeconds = 0;
        runtime.reviewTimer = 0;
        runtime.initialized = true;
        runtime.snapshot = NpcStrategicSnapshot.EMPTY;
    }

    static synchronized void onDefeated(World world, NpcFaction faction) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) return;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        runtime.state = NpcStrategicState.DEFEATED;
        runtime.stateSeconds = 0;
        runtime.reviewTimer = 0;
        runtime.initialized = true;
        runtime.snapshot = NpcStrategicSnapshot.EMPTY;
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
            row.put("homeSystemId", runtime.homeSystemId);
            row.put("seed", runtime.seed);
            row.put("state", runtime.state.name());
            row.put("reviewTimer", runtime.reviewTimer);
            row.put("stateSeconds", runtime.stateSeconds);
            row.put("transitionCount", runtime.transitionCount);
            row.put("initialized", runtime.initialized);
            row.put("snapshot", captureSnapshot(runtime.snapshot));
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
            NpcFaction faction = null;
            for (NpcFaction candidate : NpcRules.factions()) {
                if (candidate.id().equals(factionId)) {
                    faction = candidate;
                    break;
                }
            }
            if (faction == null || faction.behavior() != NpcBehavior.FACTION) continue;
            RuntimeState runtime = new RuntimeState(faction, ServerSaveStore.longValue(row, "seed", world.systemSeed()));
            runtime.state = ServerSaveStore.enumValue(NpcStrategicState.class, row.get("state"), NpcStrategicState.DEFEATED);
            runtime.reviewTimer = Math.max(0, ServerSaveStore.doubleValue(row, "reviewTimer", 0));
            runtime.stateSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "stateSeconds", 0));
            runtime.transitionCount = Math.max(0, ServerSaveStore.intValue(row, "transitionCount", 0));
            runtime.initialized = ServerSaveStore.boolValue(row, "initialized", false);
            runtime.snapshot = restoreSnapshot(row.get("snapshot"));
            byFaction.put(faction.id(), runtime);
        }
        if (byFaction.isEmpty()) RUNTIMES.remove(world);
        else RUNTIMES.put(world, byFaction);
    }

    private static RuntimeState runtime(World world, NpcFaction faction) {
        Map<String, RuntimeState> byFaction = RUNTIMES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        return byFaction.computeIfAbsent(
                faction.id(), ignored -> new RuntimeState(faction, world.systemSeed()));
    }

    private static Map<String,Object> captureSnapshot(NpcStrategicSnapshot snapshot) {
        NpcStrategicSnapshot s = snapshot == null ? NpcStrategicSnapshot.EMPTY : snapshot;
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("stations", s.stations());
        out.put("workers", s.workers());
        out.put("combat", s.combat());
        out.put("damagedCombat", s.damagedCombat());
        out.put("support", s.support());
        out.put("industry", s.industry());
        out.put("systemsWithAssets", s.systemsWithAssets());
        out.put("controlledSystems", s.controlledSystems());
        out.put("threats", s.threats());
        out.put("completedResearch", s.completedResearch());
        out.put("missingResearch", s.missingResearch());
        out.put("fuel", s.fuel());
        out.put("researchCapable", s.researchCapable());
        out.put("fuelCapable", s.fuelCapable());
        return out;
    }

    private static NpcStrategicSnapshot restoreSnapshot(Object saved) {
        Map<String,Object> data = ServerSaveStore.object(saved);
        if (data.isEmpty()) return NpcStrategicSnapshot.EMPTY;
        return new NpcStrategicSnapshot(
                ServerSaveStore.intValue(data, "stations", 0),
                ServerSaveStore.intValue(data, "workers", 0),
                ServerSaveStore.intValue(data, "combat", 0),
                ServerSaveStore.intValue(data, "damagedCombat", 0),
                ServerSaveStore.intValue(data, "support", 0),
                ServerSaveStore.intValue(data, "industry", 0),
                ServerSaveStore.intValue(data, "systemsWithAssets", 0),
                ServerSaveStore.intValue(data, "controlledSystems", 0),
                ServerSaveStore.intValue(data, "threats", 0),
                ServerSaveStore.intValue(data, "completedResearch", 0),
                ServerSaveStore.intValue(data, "missingResearch", 0),
                ServerSaveStore.doubleValue(data, "fuel", 0),
                ServerSaveStore.boolValue(data, "researchCapable", false),
                ServerSaveStore.boolValue(data, "fuelCapable", false));
    }

    private static NpcStrategicSnapshot inspect(World world, NpcFaction faction) {
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        int stations = 0;
        int workers = 0;
        int combat = 0;
        int damagedCombat = 0;
        int support = 0;
        int industry = 0;
        int systemsWithAssets = 0;
        int threats = 0;
        double fuel = 0;
        boolean researchCapable = false;
        boolean fuelCapable = false;

        Set<String> workerTypes = faction.workerTypeSet();
        Set<String> supportTypes = faction.supportTypeSet();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        int controlledSystems = 0;
        if (map != null && map.systems() != null) {
            for (GalaxyMapSystem system : map.systems()) {
                if (system != null && faction.id().equals(system.controllerId())) controlledSystems++;
            }
        }

        try {
            if (map != null && map.systems() != null) {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    boolean systemAssets = false;

                    for (Base base : world.bases.values()) {
                        if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
                        stations++;
                        systemAssets = true;
                        fuel += base.inventory.getOrDefault(Material.FUEL, 0.0);
                        for (String topicId : faction.researchTopicIds()) {
                            ResearchTopic topic = ResearchRules.topic(topicId);
                            if (topic != null && !world.hasResearch(faction.id(), topic.id)
                                    && topic.canResearchAt(base.typeId)) {
                                researchCapable = true;
                            }
                        }
                        for (CraftableItem item : CraftingRules.recipesForOutput(Material.FUEL)) {
                            if (item.canCraftAt(base.typeId) && item.unlockedFor(world, faction.id())) {
                                fuelCapable = true;
                            }
                        }
                    }

                    for (Unit unit : world.units.values()) {
                        if (faction.id().equals(unit.playerId) && unit.hp > 0) {
                            systemAssets = true;
                            ShipType type = unit.type();
                            if (!type.harvestKinds.isEmpty()
                                    && (workerTypes.isEmpty() || workerTypes.contains(unit.shipTypeId))) workers++;
                            if (WeaponRules.armed(type)) {
                                combat++;
                                double hpRatio = unit.hp / Math.max(1.0, type.maxHp);
                                if (faction.retreatHpPercent() > 0
                                        && hpRatio <= faction.retreatHpPercent()) {
                                    damagedCombat++;
                                }
                            }
                            if (supportTypes.contains(unit.shipTypeId)) support++;
                            if (faction.industryUnitTypes().contains(unit.shipTypeId)) industry++;
                        }
                    }

                    if (systemAssets) systemsWithAssets++;
                    threats += localThreatCount(world, faction);
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }

        int completedResearch = 0;
        for (String topicId : faction.researchTopicIds()) {
            if (world.hasResearch(faction.id(), topicId)) completedResearch++;
        }
        int missingResearch = Math.max(0,
                faction.researchTopicIds().size() - completedResearch);
        return new NpcStrategicSnapshot(
                stations,
                workers,
                combat,
                damagedCombat,
                support,
                industry,
                systemsWithAssets,
                controlledSystems,
                threats,
                completedResearch,
                missingResearch,
                fuel,
                researchCapable,
                fuelCapable);
    }

    private static int localThreatCount(World world, NpcFaction faction) {
        int threats = 0;
        for (Unit enemy : world.units.values()) {
            if (enemy.hp <= 0 || faction.id().equals(enemy.playerId)
                    || !WeaponRules.armed(enemy.type())) continue;
            if (NpcRules.isNpcFaction(enemy.playerId) && !faction.attackNpcFactions()) continue;
            for (Base base : world.bases.values()) {
                if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
                if (Calc.distance(base.x, base.y, enemy.x, enemy.y) <= faction.defendRange()) {
                    threats++;
                    break;
                }
            }
        }
        return threats;
    }

    private static NpcStrategicState choose(World world, RuntimeState runtime,
                                             NpcFaction faction,
                                             NpcStrategicSnapshot snapshot) {
        if (!snapshot.hasAssets()) {
            return world.hasLiveAssets(faction.id())
                    ? NpcStrategicState.FORTIFY : NpcStrategicState.DEFEATED;
        }
        if (snapshot.stations() <= 0) return NpcStrategicState.RECOVER;

        NpcFactionCapacitySnapshot capacity =
                NpcFactionCapacitySystem.snapshot(world, faction);
        NpcExpeditionSnapshot expedition =
                NpcExpeditionSystem.snapshot(world, faction);
        boolean activeExpedition = activeExpansionCommitment(expedition)
                && (faction.maxStations() <= 0
                || capacity.livingStations() < faction.maxStations());
        boolean stationCapacityAvailable = faction.maxStations() <= 0
                || capacity.stationCommitments() < faction.maxStations();
        boolean expansionAvailable = snapshot.controlledSystems()
                < faction.maxControlledSystems() && stationCapacityAvailable;

        double retreatSeconds = Math.max(20.0, faction.orderSeconds() * 6.0);
        boolean severeDamage = snapshot.combat() > 0
                && snapshot.damagedCombat() * 2 >= snapshot.combat();
        boolean overwhelmed = snapshot.threats() > Math.max(1, snapshot.combat());
        boolean repairsBlocked = NpcRecoverySystem.repairBlockedSeconds(world, faction)
                >= NpcRecoverySystem.blockedStabilizeSeconds();
        if (runtime.state == NpcStrategicState.RETREAT
                && runtime.stateSeconds < retreatSeconds) {
            return NpcStrategicState.RETREAT;
        }
        if (severeDamage && repairsBlocked && !overwhelmed) {
            return NpcStrategicState.STABILIZE_ECONOMY;
        }
        if (severeDamage || overwhelmed) return NpcStrategicState.RETREAT;
        if (snapshot.threats() > 0) return NpcStrategicState.FORTIFY;

        // Once a target is selected, keep strategy committed while readiness
        // obtains a deployer/roster and while the expedition is in flight.
        if (activeExpedition) return NpcStrategicState.EXPAND;

        int workerFloor = faction.maxWorkers() <= 0 ? 0
                : Math.max(1, (int)Math.ceil(faction.maxWorkers() * 0.67));
        if (snapshot.workers() < workerFloor) return NpcStrategicState.STABILIZE_ECONOMY;

        int infrastructureTarget = faction.homeInfrastructureTarget();
        if (snapshot.stations() < infrastructureTarget) {
            return NpcStrategicState.ESTABLISH;
        }

        boolean lowFuel = faction.fuelReserve() > 0
                && snapshot.fuel() + 0.001 < faction.fuelReserve() * 0.40;
        if (lowFuel) {
            return snapshot.fuelCapable()
                    ? NpcStrategicState.STABILIZE_ECONOMY
                    : NpcStrategicState.FORTIFY;
        }

        if (snapshot.missingResearch() > 0) {
            return snapshot.researchCapable()
                    ? NpcStrategicState.RESEARCH
                    : NpcStrategicState.FORTIFY;
        }

        int supportFloor = Math.min(2, Math.max(0, faction.maxSupportUnits()));
        int industryFloor = Math.min(1, Math.max(0, faction.maxIndustryUnits()));
        if (snapshot.support() < supportFloor
                || snapshot.industry() < industryFloor) {
            return NpcStrategicState.FORTIFY;
        }

        if (snapshot.combat() < Math.max(1, faction.targetFleetSize())) {
            return NpcStrategicState.BUILD_FLEET;
        }

        double prepareSeconds = Math.max(12.0, faction.orderSeconds() * 4.0);
        double raidSeconds = Math.max(18.0, faction.orderSeconds() * 6.0);
        double expansionSeconds = Math.max(45.0, faction.orderSeconds() * 15.0);
        double fortifySeconds = Math.max(15.0, faction.orderSeconds() * 5.0);

        if (runtime.state == NpcStrategicState.PREPARE_RAID) {
            return runtime.stateSeconds >= prepareSeconds
                    ? NpcStrategicState.RAID : runtime.state;
        }
        if (runtime.state == NpcStrategicState.RAID) {
            if (runtime.stateSeconds < raidSeconds) return runtime.state;
            return expansionAvailable
                    ? NpcStrategicState.EXPAND : NpcStrategicState.FORTIFY;
        }
        if (runtime.state == NpcStrategicState.EXPAND) {
            if (expansionAvailable
                    && runtime.stateSeconds < expansionSeconds) return runtime.state;
            return NpcStrategicState.FORTIFY;
        }
        if (runtime.state == NpcStrategicState.FORTIFY
                && runtime.stateSeconds < fortifySeconds) {
            return runtime.state;
        }
        return NpcStrategicState.PREPARE_RAID;
    }

    private static boolean activeExpansionCommitment(NpcExpeditionSnapshot expedition) {
        if (expedition == null || !expedition.active()) return false;
        return switch (expedition.state()) {
            case PLANNING, RESERVING, ASSEMBLING, LAUNCHING,
                    TRAVELLING, ESTABLISHING, DEFENDING -> true;
            case SUCCEEDED, ABORTING, FAILED -> false;
        };
    }

    private static void transition(World world, NpcFaction faction,
                                   RuntimeState runtime,
                                   NpcStrategicState next,
                                   NpcStrategicSnapshot snapshot) {
        if (next == runtime.state) return;
        NpcStrategicState previous = runtime.state;
        runtime.state = next;
        runtime.stateSeconds = 0;
        runtime.transitionCount++;
        AiDevLog.add(world, faction, "strategy " + previous + " -> " + next
                + " [stations=" + snapshot.stations()
                + ", workers=" + snapshot.workers()
                + ", combat=" + snapshot.combat()
                + ", threats=" + snapshot.threats() + "]");
    }

    private static final class RuntimeState {
        final String homeSystemId;
        long seed;
        NpcStrategicState state = NpcStrategicState.DEFEATED;
        NpcStrategicSnapshot snapshot = NpcStrategicSnapshot.EMPTY;
        double reviewTimer;
        double stateSeconds;
        int transitionCount;
        boolean initialized;

        RuntimeState(NpcFaction faction, long seed) {
            this.homeSystemId = NpcFactionRuntime.homeSystemIdFor(faction);
            this.seed = seed;
        }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            state = NpcStrategicState.DEFEATED;
            snapshot = NpcStrategicSnapshot.EMPTY;
            reviewTimer = 0;
            stateSeconds = 0;
            transitionCount = 0;
            initialized = false;
        }
    }
}

enum NpcStrategicState {
    ESTABLISH,
    STABILIZE_ECONOMY,
    RESEARCH,
    FORTIFY,
    BUILD_FLEET,
    PREPARE_RAID,
    RAID,
    EXPAND,
    RECOVER,
    RETREAT,
    DEFEATED;

    boolean runsEconomy() { return this != DEFEATED; }
    boolean buildsShips() { return this != RETREAT && this != DEFEATED; }
    boolean buildsStations() { return this == ESTABLISH || this == RECOVER; }
    boolean prioritizesFleet() { return this == BUILD_FLEET || this == PREPARE_RAID || this == RAID || this == EXPAND; }
    boolean allowsResearch() { return this == RESEARCH; }
    boolean allowsRaid() { return this == RAID; }
}

record NpcStrategicSnapshot(
        int stations,
        int workers,
        int combat,
        int damagedCombat,
        int support,
        int industry,
        int systemsWithAssets,
        int controlledSystems,
        int threats,
        int completedResearch,
        int missingResearch,
        double fuel,
        boolean researchCapable,
        boolean fuelCapable
) {
    static final NpcStrategicSnapshot EMPTY = new NpcStrategicSnapshot(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);

    boolean hasAssets() { return systemsWithAssets > 0; }
}
