package com.tndmadman.rts;

import java.util.LinkedHashMap;
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

    private static RuntimeState runtime(World world, NpcFaction faction) {
        Map<String, RuntimeState> byFaction = RUNTIMES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        return byFaction.computeIfAbsent(
                faction.id(), ignored -> new RuntimeState(faction, world.systemSeed()));
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
                            if (supportTypes.contains(unit.shipTypeId) || type.baseBuilder) support++;
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
        if (!snapshot.hasAssets()) return NpcStrategicState.DEFEATED;
        if (snapshot.stations() <= 0) return NpcStrategicState.RECOVER;

        boolean severeDamage = snapshot.combat() > 0
                && snapshot.damagedCombat() * 2 >= snapshot.combat();
        boolean overwhelmed = snapshot.threats() > Math.max(1, snapshot.combat());
        boolean repairsBlocked = NpcRecoverySystem.repairBlockedSeconds(world, faction)
                >= NpcRecoverySystem.blockedStabilizeSeconds();
        if (severeDamage && repairsBlocked && !overwhelmed) {
            return NpcStrategicState.STABILIZE_ECONOMY;
        }
        if (severeDamage || overwhelmed) return NpcStrategicState.RETREAT;
        if (snapshot.threats() > 0) return NpcStrategicState.FORTIFY;

        int workerFloor = faction.maxWorkers() <= 0 ? 0
                : Math.max(1, (int)Math.ceil(faction.maxWorkers() * 0.67));
        if (snapshot.workers() < workerFloor) return NpcStrategicState.STABILIZE_ECONOMY;

        int establishmentTarget = faction.maxStations() <= 0
                ? 1 : Math.min(2, faction.maxStations());
        if (snapshot.stations() < establishmentTarget) return NpcStrategicState.ESTABLISH;

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

        int infrastructureTarget = Math.max(establishmentTarget, faction.maxStations());
        int supportFloor = Math.min(2, Math.max(0, faction.maxSupportUnits()));
        int industryFloor = Math.min(1, Math.max(0, faction.maxIndustryUnits()));
        if (snapshot.stations() < infrastructureTarget
                || snapshot.support() < supportFloor
                || snapshot.industry() < industryFloor) {
            return NpcStrategicState.FORTIFY;
        }

        if (snapshot.combat() < Math.max(1, faction.targetFleetSize())) {
            return NpcStrategicState.BUILD_FLEET;
        }

        double prepareSeconds = Math.max(4.0, faction.orderSeconds() * 2.0);
        double raidSeconds = Math.max(6.0, faction.orderSeconds() * 3.0);
        double expansionSeconds = Math.max(10.0, faction.orderSeconds() * 5.0);
        double fortifySeconds = Math.max(6.0, faction.orderSeconds() * 2.0);

        if (runtime.state == NpcStrategicState.PREPARE_RAID) {
            return runtime.stateSeconds >= prepareSeconds
                    ? NpcStrategicState.RAID : runtime.state;
        }
        if (runtime.state == NpcStrategicState.RAID) {
            if (runtime.stateSeconds < raidSeconds) return runtime.state;
            return snapshot.controlledSystems() < 2
                    ? NpcStrategicState.EXPAND : NpcStrategicState.FORTIFY;
        }
        if (runtime.state == NpcStrategicState.EXPAND) {
            if (snapshot.controlledSystems() < 2
                    && runtime.stateSeconds < expansionSeconds) return runtime.state;
            return NpcStrategicState.FORTIFY;
        }
        if (runtime.state == NpcStrategicState.FORTIFY
                && runtime.stateSeconds < fortifySeconds) {
            return runtime.state;
        }
        return NpcStrategicState.PREPARE_RAID;
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
    boolean buildsStations() { return this == ESTABLISH || this == FORTIFY || this == EXPAND || this == RECOVER; }
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
