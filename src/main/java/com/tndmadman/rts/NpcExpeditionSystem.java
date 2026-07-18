package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Owns one persistent strategic expedition per organized NPC faction.
 *
 * Planning and timers advance only while the faction home system is the
 * authoritative simulated system. Orders are reasserted from every local
 * system pass after ordinary tactical AI, allowing the exact roster to travel
 * through real wormholes without being replaced by local mining or combat
 * orders. Supplies and the carried foothold package are deducted once during
 * reservation, refunded only when an expedition aborts before its first
 * wormhole transit, and never duplicated by retries or replanning.
 */
final class NpcExpeditionSystem {
    private static final double SUCCESS_COOLDOWN_SECONDS = 150.0;
    private static final double FAILURE_COOLDOWN_SECONDS = 45.0;
    private static final double ASSEMBLY_RADIUS = 125.0;
    private static final double ASSEMBLY_GATE_OFFSET = 230.0;
    private static final double DEFEND_SECONDS = 18.0;
    private static final int MAX_ESTABLISH_RETRIES = 3;
    private static final int MIN_SURVIVING_COMBAT = 2;
    private static final double EPSILON = 0.001;
    private static final double EXPANSION_FRACTION = 0.20;
    private static final double EXPANSION_CAP_PER_MATERIAL = 250.0;
    private static final Map<World, Map<String, RuntimeState>> RUNTIMES = new WeakHashMap<>();

    private NpcExpeditionSystem() { }

    static synchronized void update(World world, NpcFaction faction,
                                    NpcStrategicState strategy, double dt) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) return;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        String activeSystemId = world.activeSystemId();
        String homeSystemId = NpcFactionRuntime.homeSystemIdFor(faction);

        if (homeSystemId.equals(activeSystemId)) {
            double step = Double.isFinite(dt) && dt > 0 ? dt : 0;
            runtime.cooldownSeconds = Math.max(0, runtime.cooldownSeconds - step);
            if (runtime.plan == null) {
                if (strategy == NpcStrategicState.EXPAND && runtime.cooldownSeconds <= EPSILON) {
                    begin(world, faction, runtime);
                }
            } else {
                runtime.plan.stateSeconds += step;
                progressFromHome(world, faction, runtime, strategy);
            }
        }

        ExpeditionPlan plan = runtime.plan;
        if (plan != null && !plan.released) reassertLocalOrders(world, faction, plan);
    }

    static synchronized boolean ownsUnit(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return false;
        Map<String, RuntimeState> byFaction = RUNTIMES.get(world);
        if (byFaction == null) return false;
        for (RuntimeState runtime : byFaction.values()) {
            ExpeditionPlan plan = runtime.plan;
            if (plan != null && !plan.released && plan.rosterKeys().contains(unitKey)) return true;
        }
        return false;
    }

    static synchronized boolean protectsStationlessCurrentSystem(World world, NpcFaction faction) {
        if (world == null || faction == null) return false;
        RuntimeState runtime = runtime(world, faction);
        ExpeditionPlan plan = runtime.plan;
        if (plan == null || plan.released || world.bases.values().stream()
                .anyMatch(base -> faction.id().equals(base.playerId) && base.hp > 0)) return false;
        if (plan.state != NpcExpeditionState.LAUNCHING
                && plan.state != NpcExpeditionState.TRAVELLING
                && plan.state != NpcExpeditionState.ESTABLISHING
                && plan.state != NpcExpeditionState.ABORTING) return false;
        for (Unit unit : world.units.values()) {
            if (unit.hp > 0 && plan.rosterKeys().contains(unit.key())) return true;
        }
        return false;
    }

    static synchronized NpcExpeditionSnapshot snapshot(World world, NpcFaction faction) {
        if (world == null || faction == null) return NpcExpeditionSnapshot.NONE;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        ExpeditionPlan plan = runtime.plan;
        if (plan == null) {
            return new NpcExpeditionSnapshot(false, runtime.lastState, "", runtime.lastTarget,
                    List.of(), 0, "", "", List.of(), List.of(), Map.of(),
                    0, runtime.cooldownSeconds, runtime.lastReason, false, false);
        }
        return new NpcExpeditionSnapshot(true, plan.state, plan.sourceSystemId, plan.targetSystemId,
                List.copyOf(plan.route), plan.routeIndex, plan.builderKey, plan.workerKey,
                List.copyOf(plan.combatKeys), List.copyOf(plan.supportKeys), Map.copyOf(plan.supplies),
                plan.targetScore, runtime.cooldownSeconds, plan.reason, plan.launched, plan.suppliesDelivered);
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String, RuntimeState> byFaction = RUNTIMES.get(world);
        if (byFaction == null) return out;
        List<Object> runtimes = new ArrayList<>();
        for (Map.Entry<String, RuntimeState> entry : byFaction.entrySet()) {
            RuntimeState runtime = entry.getValue();
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("factionId", entry.getKey());
            row.put("seed", runtime.seed);
            row.put("cooldownSeconds", runtime.cooldownSeconds);
            row.put("lastState", runtime.lastState.name());
            row.put("lastTarget", runtime.lastTarget);
            row.put("lastReason", runtime.lastReason);
            row.put("plan", capturePlan(runtime.plan));
            runtimes.add(row);
        }
        out.put("runtimes", runtimes);
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
            runtime.cooldownSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "cooldownSeconds", 0));
            runtime.lastState = ServerSaveStore.enumValue(NpcExpeditionState.class, row.get("lastState"), NpcExpeditionState.FAILED);
            runtime.lastTarget = ServerSaveStore.string(row, "lastTarget", "");
            runtime.lastReason = ServerSaveStore.string(row, "lastReason", "");
            runtime.plan = restorePlan(row.get("plan"));
            byFaction.put(factionId, runtime);
        }
        if (byFaction.isEmpty()) RUNTIMES.remove(world);
        else RUNTIMES.put(world, byFaction);
    }

    static synchronized void clear(World world) {
        if (world != null) RUNTIMES.remove(world);
    }

    private static Map<String,Object> capturePlan(ExpeditionPlan plan) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (plan == null) return out;
        out.put("sourceSystemId", plan.sourceSystemId);
        out.put("targetSystemId", plan.targetSystemId);
        out.put("targetName", plan.targetName);
        out.put("route", List.copyOf(plan.route));
        out.put("targetScore", plan.targetScore);
        out.put("initialControllerId", plan.initialControllerId);
        out.put("initialHostileBases", plan.initialHostileBases);
        out.put("combatKeys", List.copyOf(plan.combatKeys));
        out.put("supportKeys", List.copyOf(plan.supportKeys));
        out.put("supplies", ServerSaveStore.materialMap(plan.supplies));
        out.put("packageCost", ServerSaveStore.materialMap(plan.packageCost));
        out.put("state", plan.state.name());
        out.put("sourceBaseId", plan.sourceBaseId);
        out.put("builderKey", plan.builderKey);
        out.put("workerKey", plan.workerKey);
        out.put("packageType", plan.packageType);
        out.put("footholdBaseId", plan.footholdBaseId);
        out.put("reason", plan.reason);
        out.put("routeIndex", plan.routeIndex);
        out.put("establishRetries", plan.establishRetries);
        out.put("terminalTicks", plan.terminalTicks);
        out.put("stateSeconds", plan.stateSeconds);
        out.put("footholdX", plan.footholdX);
        out.put("footholdY", plan.footholdY);
        out.put("suppliesReserved", plan.suppliesReserved);
        out.put("packageCommitted", plan.packageCommitted);
        out.put("suppliesDelivered", plan.suppliesDelivered);
        out.put("launched", plan.launched);
        out.put("released", plan.released);
        return out;
    }

    private static ExpeditionPlan restorePlan(Object saved) {
        Map<String,Object> data = ServerSaveStore.object(saved);
        if (data.isEmpty()) return null;
        String sourceSystemId = ServerSaveStore.string(data, "sourceSystemId", "");
        String targetSystemId = ServerSaveStore.string(data, "targetSystemId", "");
        if (sourceSystemId.isBlank() || targetSystemId.isBlank()) return null;
        List<String> route = new ArrayList<>();
        for (Object item : ServerSaveStore.list(data.get("route"))) {
            String value = ServerSaveStore.asString(item, "");
            if (!value.isBlank()) route.add(value);
        }
        ExpeditionPlan plan = new ExpeditionPlan(
                sourceSystemId,
                targetSystemId,
                ServerSaveStore.string(data, "targetName", targetSystemId),
                route,
                ServerSaveStore.doubleValue(data, "targetScore", 0),
                ServerSaveStore.string(data, "initialControllerId", ""),
                ServerSaveStore.intValue(data, "initialHostileBases", 0));
        for (Object item : ServerSaveStore.list(data.get("combatKeys"))) {
            String key = ServerSaveStore.asString(item, "");
            if (!key.isBlank()) plan.combatKeys.add(key);
        }
        for (Object item : ServerSaveStore.list(data.get("supportKeys"))) {
            String key = ServerSaveStore.asString(item, "");
            if (!key.isBlank()) plan.supportKeys.add(key);
        }
        plan.supplies.putAll(ServerSaveStore.restoreMaterialMap(data.get("supplies")));
        plan.packageCost.putAll(ServerSaveStore.restoreMaterialMap(data.get("packageCost")));
        plan.state = ServerSaveStore.enumValue(NpcExpeditionState.class, data.get("state"), NpcExpeditionState.PLANNING);
        plan.sourceBaseId = ServerSaveStore.string(data, "sourceBaseId", "");
        plan.builderKey = ServerSaveStore.string(data, "builderKey", "");
        plan.workerKey = ServerSaveStore.string(data, "workerKey", "");
        plan.packageType = ServerSaveStore.string(data, "packageType", "");
        plan.footholdBaseId = ServerSaveStore.string(data, "footholdBaseId", "");
        plan.reason = ServerSaveStore.string(data, "reason", "");
        plan.routeIndex = Math.max(0, ServerSaveStore.intValue(data, "routeIndex", 0));
        plan.establishRetries = Math.max(0, ServerSaveStore.intValue(data, "establishRetries", 0));
        plan.terminalTicks = Math.max(0, ServerSaveStore.intValue(data, "terminalTicks", 0));
        plan.stateSeconds = Math.max(0, ServerSaveStore.doubleValue(data, "stateSeconds", 0));
        plan.footholdX = ServerSaveStore.doubleValue(data, "footholdX", 0);
        plan.footholdY = ServerSaveStore.doubleValue(data, "footholdY", 0);
        plan.suppliesReserved = ServerSaveStore.boolValue(data, "suppliesReserved", false);
        plan.packageCommitted = ServerSaveStore.boolValue(data, "packageCommitted", false);
        plan.suppliesDelivered = ServerSaveStore.boolValue(data, "suppliesDelivered", false);
        plan.launched = ServerSaveStore.boolValue(data, "launched", false);
        plan.released = ServerSaveStore.boolValue(data, "released", false);
        return plan;
    }

    private static void begin(World world, NpcFaction faction, RuntimeState runtime) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        String homeId = NpcFactionRuntime.homeSystemIdFor(faction);
        GalaxyMapSystem home = system(map, homeId);
        if (home == null || !faction.id().equals(home.controllerId())) return;
        if (!NpcResourceBudget.canLaunchExpansion(world, faction)) return;
        if (NpcStationConstructionSystem.hasAnyActivePlan(world, faction)) return;
        TargetChoice choice = chooseTarget(world, faction, map);
        if (choice == null) return;
        runtime.plan = new ExpeditionPlan(
                homeId, choice.system.id(), choice.system.name(),
                choice.route, choice.score, choice.system.controllerId(), choice.hostileBases);
        transition(world, faction, runtime.plan, NpcExpeditionState.PLANNING,
                "selected " + choice.system.name() + " score=" + (int)Math.round(choice.score)
                        + " route=" + String.join(" -> ", choice.route));
    }

    private static void progressFromHome(World world, NpcFaction faction, RuntimeState runtime,
                                         NpcStrategicState strategy) {
        ExpeditionPlan plan = runtime.plan;
        if (plan == null) return;
        switch (plan.state) {
            case PLANNING -> progressPlanning(world, faction, plan);
            case RESERVING -> progressReserving(world, faction, plan, strategy);
            case ASSEMBLING -> progressAssembling(world, faction, plan);
            case LAUNCHING, TRAVELLING -> progressForwardTransit(world, faction, plan);
            case ESTABLISHING -> progressEstablishing(world, faction, plan);
            case DEFENDING -> progressDefending(world, faction, runtime, plan);
            case ABORTING -> progressAbort(world, faction, runtime, plan);
            case SUCCEEDED, FAILED -> finishTerminal(runtime, plan);
        }
    }

    private static void progressPlanning(World world, NpcFaction faction, ExpeditionPlan plan) {
        if (!refreshRouteAndTarget(world, faction, plan, false)) {
            beginAbort(world, faction, plan, "target or route became invalid during planning");
            return;
        }
        transition(world, faction, plan, NpcExpeditionState.RESERVING,
                "target and route validated");
    }

    private static void progressReserving(World world, NpcFaction faction, ExpeditionPlan plan,
                                          NpcStrategicState strategy) {
        if (strategy != NpcStrategicState.EXPAND) {
            beginAbort(world, faction, plan, "strategy changed before resources were committed");
            return;
        }
        if (!refreshRouteAndTarget(world, faction, plan, false)) {
            beginAbort(world, faction, plan, "target invalidated before reservation");
            return;
        }
        if (!NpcResourceBudget.canLaunchExpansion(world, faction)) return;

        Base source = NpcResourceBudget.expansionSupplyBase(world, faction);
        Roster roster = selectRoster(world, faction);
        if (source == null || roster == null) return;

        String packageType = validFootholdType(faction);
        BaseType packageBase = Rules.findBase(packageType);
        if (packageBase == null) return;
        if (!NpcResourceBudget.canAfford(world, faction,
                NpcBudgetCategory.EXPANSION, packageBase.buildCost)) return;
        if (!NpcResourceBudget.spend(world, faction,
                NpcBudgetCategory.EXPANSION, packageBase.buildCost)) return;
        EnumMap<Material, Double> packageCost = costMap(packageBase.buildCost);

        EnumMap<Material, Double> supplies = reserveSupplies(source);
        if (supplies.isEmpty()) {
            refundToBase(source, packageCost);
            return;
        }

        plan.sourceBaseId = source.id;
        plan.builderKey = roster.builder.key();
        plan.workerKey = roster.worker.key();
        plan.combatKeys.addAll(roster.combatKeys);
        plan.supportKeys.addAll(roster.supportKeys);
        plan.packageType = packageType;
        plan.packageCost.putAll(packageCost);
        plan.packageCommitted = true;
        plan.supplies.putAll(supplies);
        plan.suppliesReserved = true;
        roster.builder.basePackageType = packageType;
        plan.routeIndex = 0;
        transition(world, faction, plan, NpcExpeditionState.ASSEMBLING,
                "reserved foothold package, supplies, and roster of "
                        + plan.rosterKeys().size() + " ships");
    }

    private static void progressAssembling(World world, NpcFaction faction, ExpeditionPlan plan) {
        if (!refreshRouteAndTarget(world, faction, plan, false)) {
            beginAbort(world, faction, plan, "target invalidated before launch");
            return;
        }
        MemberLocations locations = locateMembers(world, plan);
        if (!refreshRequiredRoster(world, faction, plan, locations, true)) return;
        if (!locations.allIn(plan.rosterKeys(), plan.sourceSystemId)) {
            plan.launched = locations.anyOutside(plan.rosterKeys(), plan.sourceSystemId);
            transition(world, faction, plan, NpcExpeditionState.LAUNCHING,
                    "fleet began entering the first wormhole");
            return;
        }
        Point assembly = assemblyPoint(world, plan);
        if (assembly == null) {
            beginAbort(world, faction, plan, "first wormhole disappeared");
            return;
        }
        if (allNear(world, plan.sourceSystemId, plan.rosterKeys(), assembly.x, assembly.y, ASSEMBLY_RADIUS)) {
            transition(world, faction, plan, NpcExpeditionState.LAUNCHING,
                    "fleet assembled at departure gate");
        }
    }

    private static void progressForwardTransit(World world, NpcFaction faction, ExpeditionPlan plan) {
        MemberLocations locations = locateMembers(world, plan);
        plan.launched |= locations.anyOutside(plan.rosterKeys(), plan.sourceSystemId);
        if (!refreshRouteAndTarget(world, faction, plan, plan.launched)) {
            beginAbort(world, faction, plan, "route or target invalidated in transit");
            return;
        }
        if (!refreshRequiredRoster(world, faction, plan, locations, false)) return;
        if (plan.rosterKeys().isEmpty()) {
            beginAbort(world, faction, plan, "expedition fleet was destroyed");
            return;
        }

        int completeIndex = locations.commonRouteIndex(plan.rosterKeys(), plan.route);
        if (completeIndex >= 0) {
            plan.routeIndex = completeIndex;
            if (completeIndex == plan.route.size() - 1) {
                transition(world, faction, plan, NpcExpeditionState.ESTABLISHING,
                        "full expedition arrived in " + plan.targetName);
                return;
            }
            if (plan.state == NpcExpeditionState.LAUNCHING && completeIndex > 0) {
                transition(world, faction, plan, NpcExpeditionState.TRAVELLING,
                        "first wormhole transit completed");
            }
            return;
        }

        int minimum = locations.minimumRouteIndex(plan.rosterKeys(), plan.route);
        int maximum = locations.maximumRouteIndex(plan.rosterKeys(), plan.route);
        if (minimum < 0 || maximum < 0) {
            beginAbort(world, faction, plan, "expedition members left the planned route");
            return;
        }
        plan.routeIndex = Math.min(minimum, plan.route.size() - 1);
    }

    private static void progressEstablishing(World world, NpcFaction faction, ExpeditionPlan plan) {
        if (!targetOwnershipStillValid(world, faction, plan, true)) {
            beginAbort(world, faction, plan, "target captured before foothold completion");
            return;
        }
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(plan.targetSystemId);
        try {
            Base foothold = findFoothold(world, faction.id(), plan.footholdX, plan.footholdY);
            if (foothold != null && !NpcStationConstructionSystem.hasActivePlan(world, faction)) {
                deliverSupplies(foothold, plan);
                plan.footholdBaseId = foothold.id;
                transition(world, faction, plan, NpcExpeditionState.DEFENDING,
                        "foothold construction completed");
                return;
            }

            Unit builder = world.units.get(plan.builderKey);
            if (builder == null || builder.hp <= 0) {
                beginAbort(world, faction, plan, "deployer lost during establishment");
                return;
            }
            if (!NpcStationConstructionSystem.hasActivePlan(world, faction)) {
                if (plan.establishRetries >= MAX_ESTABLISH_RETRIES) {
                    beginAbort(world, faction, plan, "foothold site failed repeatedly");
                    return;
                }
                if (plan.packageType.isBlank()) plan.packageType = validFootholdType(faction);
                builder.basePackageType = plan.packageType;
                boolean started = NpcStationConstructionSystem.startLoaded(
                        world, faction, builder, plan.packageType);
                plan.establishRetries++;
                if (!started) return;
            }
            NpcStationConstructionSnapshot construction =
                    NpcStationConstructionSystem.snapshot(world, faction);
            if (construction.active()) {
                plan.footholdX = construction.targetX();
                plan.footholdY = construction.targetY();
            }
            commandDefenders(world, faction, plan, builder.x, builder.y);
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static void progressDefending(World world, NpcFaction faction, RuntimeState runtime,
                                          ExpeditionPlan plan) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(plan.targetSystemId);
        try {
            Base foothold = world.bases.get(plan.footholdBaseId);
            if (foothold == null || foothold.hp <= 0) {
                beginAbort(world, faction, plan, "new foothold was destroyed");
                return;
            }
            MemberLocations local = MemberLocations.fromCurrentSystem(world, plan.rosterKeys());
            pruneOptionalCasualties(plan, local);
            if (!local.contains(plan.workerKey) || livingCombatCount(plan, local) < MIN_SURVIVING_COMBAT) {
                beginAbort(world, faction, plan, "foothold defense force fell below minimum strength");
                return;
            }
            commandDefenders(world, faction, plan, foothold.x, foothold.y);
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        if (plan.stateSeconds + EPSILON < DEFEND_SECONDS) return;
        plan.released = true;
        runtime.cooldownSeconds = SUCCESS_COOLDOWN_SECONDS;
        runtime.lastState = NpcExpeditionState.SUCCEEDED;
        runtime.lastTarget = plan.targetSystemId;
        runtime.lastReason = "foothold survived establishment defense";
        transition(world, faction, plan, NpcExpeditionState.SUCCEEDED,
                runtime.lastReason);
    }

    private static void progressAbort(World world, NpcFaction faction, RuntimeState runtime,
                                      ExpeditionPlan plan) {
        cancelFootholdConstruction(world, faction, plan);
        MemberLocations locations = locateMembers(world, plan);
        pruneOptionalCasualties(plan, locations);
        Set<String> survivors = locations.presentKeys(plan.rosterKeys());
        if (survivors.isEmpty()) {
            fail(world, faction, runtime, plan, plan.reason + "; no ships survived");
            return;
        }
        if (!plan.launched) {
            refundReservation(world, faction, plan);
            clearBuilderPackage(world, plan);
            fail(world, faction, runtime, plan, plan.reason + "; aborted before launch");
            return;
        }
        if (locations.allIn(survivors, plan.sourceSystemId)) {
            clearBuilderPackage(world, plan);
            fail(world, faction, runtime, plan, plan.reason + "; surviving ships returned home");
            return;
        }

        int maximum = locations.maximumRouteIndex(survivors, plan.route);
        int minimum = locations.minimumRouteIndex(survivors, plan.route);
        if (maximum <= 0 || minimum < 0) {
            fail(world, faction, runtime, plan, plan.reason + "; no valid recovery route remained");
            return;
        }
        plan.routeIndex = maximum;
    }

    private static void finishTerminal(RuntimeState runtime, ExpeditionPlan plan) {
        if (++plan.terminalTicks < 2) return;
        runtime.plan = null;
    }

    private static void fail(World world, NpcFaction faction, RuntimeState runtime,
                             ExpeditionPlan plan, String reason) {
        plan.released = true;
        plan.reason = reason;
        runtime.cooldownSeconds = Math.max(runtime.cooldownSeconds, FAILURE_COOLDOWN_SECONDS);
        runtime.lastState = NpcExpeditionState.FAILED;
        runtime.lastTarget = plan.targetSystemId;
        runtime.lastReason = reason;
        transition(world, faction, plan, NpcExpeditionState.FAILED, reason);
    }

    private static void beginAbort(World world, NpcFaction faction, ExpeditionPlan plan, String reason) {
        if (plan.state == NpcExpeditionState.ABORTING || plan.state == NpcExpeditionState.FAILED) return;
        plan.reason = reason;
        transition(world, faction, plan, NpcExpeditionState.ABORTING, reason);
    }

    private static void reassertLocalOrders(World world, NpcFaction faction, ExpeditionPlan plan) {
        String active = world.activeSystemId();
        int index = plan.route.indexOf(active);
        if (index < 0) return;
        Set<String> localKeys = new LinkedHashSet<>();
        for (Unit unit : world.units.values()) {
            if (unit.hp > 0 && plan.rosterKeys().contains(unit.key())) localKeys.add(unit.key());
        }
        if (localKeys.isEmpty()) return;

        switch (plan.state) {
            case ASSEMBLING -> {
                if (index != 0) return;
                Point assembly = assemblyPointInCurrentSystem(world, plan);
                if (assembly != null) moveFormation(world, localKeys, assembly.x, assembly.y, 28.0);
            }
            case LAUNCHING, TRAVELLING -> {
                if (index != plan.routeIndex || index >= plan.route.size() - 1) {
                    holdUnits(world, localKeys);
                    return;
                }
                WormholeGate gate = gateTo(world, plan.route.get(index + 1));
                if (gate != null) moveToGate(world, localKeys, gate);
            }
            case ESTABLISHING -> {
                if (active.equals(plan.targetSystemId)) {
                    Unit builder = world.units.get(plan.builderKey);
                    double x = builder == null ? plan.footholdX : builder.x;
                    double y = builder == null ? plan.footholdY : builder.y;
                    commandDefenders(world, faction, plan, x, y);
                }
            }
            case DEFENDING -> {
                if (active.equals(plan.targetSystemId)) {
                    Base foothold = world.bases.get(plan.footholdBaseId);
                    if (foothold != null) commandDefenders(world, faction, plan, foothold.x, foothold.y);
                }
            }
            case ABORTING -> {
                if (index != plan.routeIndex || index <= 0) {
                    holdUnits(world, localKeys);
                    return;
                }
                WormholeGate gate = gateTo(world, plan.route.get(index - 1));
                if (gate != null) moveToGate(world, localKeys, gate);
            }
            default -> { }
        }
    }

    private static boolean refreshRequiredRoster(World world, NpcFaction faction, ExpeditionPlan plan,
                                                 MemberLocations locations, boolean beforeLaunch) {
        pruneOptionalCasualties(plan, locations);
        if (!locations.contains(plan.builderKey)) {
            beginAbort(world, faction, plan, "required deployer was lost");
            return false;
        }
        if (!locations.contains(plan.workerKey)) {
            beginAbort(world, faction, plan, "required worker was lost");
            return false;
        }
        if (livingCombatCount(plan, locations) < MIN_SURVIVING_COMBAT) {
            beginAbort(world, faction, plan, "combat escort fell below minimum strength");
            return false;
        }
        if (beforeLaunch && locations.anyOutside(plan.rosterKeys(), plan.sourceSystemId)) plan.launched = true;
        return true;
    }

    private static void pruneOptionalCasualties(ExpeditionPlan plan, MemberLocations locations) {
        plan.combatKeys.removeIf(key -> !locations.contains(key));
        plan.supportKeys.removeIf(key -> !locations.contains(key));
    }

    private static int livingCombatCount(ExpeditionPlan plan, MemberLocations locations) {
        int count = 0;
        for (String key : plan.combatKeys) if (locations.contains(key)) count++;
        return count;
    }

    private static void commandDefenders(World world, NpcFaction faction, ExpeditionPlan plan,
                                         double anchorX, double anchorY) {
        String threat = nearestThreat(world, faction, anchorX, anchorY);
        int index = 0;
        for (String key : plan.combatKeys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            if (!threat.isBlank()) {
                unit.attack(threat);
            } else {
                double angle = index++ * 1.7;
                unit.issueMove(
                        Calc.clamp(anchorX + Math.cos(angle) * 220.0, 0, world.width),
                        Calc.clamp(anchorY + Math.sin(angle) * 220.0, 0, world.height));
            }
        }
        Unit worker = world.units.get(plan.workerKey);
        if (worker != null && worker.hp > 0) {
            worker.issueMove(Calc.clamp(anchorX + 115.0, 0, world.width),
                    Calc.clamp(anchorY + 55.0, 0, world.height));
        }
        for (String key : plan.supportKeys) {
            Unit support = world.units.get(key);
            if (support != null && support.hp > 0) {
                support.issueMove(Calc.clamp(anchorX - 120.0, 0, world.width),
                        Calc.clamp(anchorY + 70.0, 0, world.height));
            }
        }
    }

    private static String nearestThreat(World world, NpcFaction faction, double x, double y) {
        String best = "";
        double bestDistance = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0 || !hostile(faction, unit.playerId)) continue;
            double distance = Calc.distance(x, y, unit.x, unit.y);
            if (distance < bestDistance) {
                best = CombatTarget.unit(unit);
                bestDistance = distance;
            }
        }
        for (Base base : world.bases.values()) {
            if (base.hp <= 0 || !hostile(faction, base.playerId)) continue;
            double distance = Calc.distance(x, y, base.x, base.y);
            if (distance < bestDistance) {
                best = CombatTarget.base(base);
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean hostile(NpcFaction faction, String ownerId) {
        if (ownerId == null || ownerId.isBlank() || faction.id().equals(ownerId)) return false;
        return !NpcRules.isNpcFaction(ownerId) || faction.attackNpcFactions();
    }

    private static Roster selectRoster(World world, NpcFaction faction) {
        Unit builder = null;
        Unit worker = null;
        List<Unit> combat = new ArrayList<>();
        List<Unit> support = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0 || ownsUnit(world, unit.key())) continue;
            double hpRatio = unit.hp / Math.max(1.0, unit.type().maxHp);
            if (hpRatio < 0.70) continue;
            if (builder == null && unit.type().baseBuilder && unit.basePackageType.isBlank()
                    && !NpcStationConstructionSystem.ownsBuilder(world, unit.key())) {
                builder = unit;
                continue;
            }
            if (worker == null && !unit.type().harvestKinds.isEmpty()
                    && (faction.workerTypeSet().isEmpty() || faction.workerTypeSet().contains(unit.shipTypeId))) {
                worker = unit;
                continue;
            }
            if (WeaponRules.armed(unit.type())) combat.add(unit);
            else if (faction.supportTypeSet().contains(unit.shipTypeId)) support.add(unit);
        }
        int requested = Math.max(MIN_SURVIVING_COMBAT, faction.raidFleetSize());
        if (builder == null || worker == null || combat.size() < requested) return null;
        combat.sort(Comparator
                .comparingDouble((Unit unit) -> -(unit.hp / Math.max(1.0, unit.type().maxHp)))
                .thenComparingInt(unit -> unit.unitId));
        support.sort(Comparator.comparingInt(unit -> unit.unitId));
        List<String> combatKeys = new ArrayList<>();
        for (int i = 0; i < requested; i++) combatKeys.add(combat.get(i).key());
        List<String> supportKeys = support.isEmpty() ? List.of() : List.of(support.get(0).key());
        return new Roster(builder, worker, combatKeys, supportKeys);
    }

    private static EnumMap<Material, Double> reserveSupplies(Base source) {
        EnumMap<Material, Double> reserved = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            if (!material.raw && material != Material.FUEL) continue;
            double held = source.inventory.getOrDefault(material, 0.0);
            double amount = Math.min(EXPANSION_CAP_PER_MATERIAL, held * EXPANSION_FRACTION);
            if (amount <= EPSILON) continue;
            reserved.put(material, amount);
        }
        double total = reserved.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 1.0) return new EnumMap<>(Material.class);
        for (Map.Entry<Material, Double> entry : reserved.entrySet()) {
            double left = source.inventory.getOrDefault(entry.getKey(), 0.0) - entry.getValue();
            if (left <= 0.05) source.inventory.remove(entry.getKey());
            else source.inventory.put(entry.getKey(), left);
        }
        return reserved;
    }

    private static EnumMap<Material, Double> costMap(List<Cost> costs) {
        EnumMap<Material, Double> result = new EnumMap<>(Material.class);
        if (costs == null) return result;
        for (Cost cost : costs) {
            if (cost != null && cost.amount() > EPSILON) {
                result.merge(cost.material(), cost.amount(), Double::sum);
            }
        }
        return result;
    }

    private static void refundToBase(Base base, Map<Material, Double> materials) {
        if (base == null || materials == null) return;
        for (Map.Entry<Material, Double> entry : materials.entrySet()) {
            if (entry.getValue() > EPSILON) HangarStore.add(base.inventory, entry.getKey(), entry.getValue());
        }
    }

    private static void refundReservation(World world, NpcFaction faction, ExpeditionPlan plan) {
        if (plan.launched || (!plan.suppliesReserved && !plan.packageCommitted)) return;
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(plan.sourceSystemId);
        try {
            Base source = world.bases.get(plan.sourceBaseId);
            if (source == null || source.hp <= 0) source = NpcResourceBudget.expansionSupplyBase(world, faction);
            if (source == null) return;
            refundToBase(source, plan.supplies);
            refundToBase(source, plan.packageCost);
            plan.supplies.clear();
            plan.packageCost.clear();
            plan.suppliesReserved = false;
            plan.packageCommitted = false;
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static void deliverSupplies(Base foothold, ExpeditionPlan plan) {
        if (plan.suppliesDelivered) return;
        refundToBase(foothold, plan.supplies);
        plan.supplies.clear();
        plan.suppliesDelivered = true;
        plan.suppliesReserved = false;
        plan.packageCost.clear();
        plan.packageCommitted = false;
    }

    private static void clearBuilderPackage(World world, ExpeditionPlan plan) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            for (String systemId : plan.route) {
                world.activateSystem(systemId);
                Unit builder = world.units.get(plan.builderKey);
                if (builder != null && builder.hp > 0) {
                    builder.basePackageType = "";
                    builder.clearOrder();
                    builder.task = UnitTask.IDLE;
                    builder.targetX = builder.x;
                    builder.targetY = builder.y;
                    world.saveActiveSystem();
                    return;
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static void cancelFootholdConstruction(World world, NpcFaction faction, ExpeditionPlan plan) {
        if (plan.state != NpcExpeditionState.ABORTING) return;
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(plan.targetSystemId);
        try {
            NpcStationConstructionSystem.cancel(world, faction, "expedition aborted");
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static boolean refreshRouteAndTarget(World world, NpcFaction faction,
                                                 ExpeditionPlan plan, boolean launched) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        GalaxyMapSystem target = system(map, plan.targetSystemId);
        if (target == null || !target.staticSystem() || target.home()
                || (!launched && faction.id().equals(target.controllerId()))
                || !NpcSystemScope.allowsExpansion(target.id(), faction.id())) return false;
        List<String> route = shortestPath(map, plan.sourceSystemId, plan.targetSystemId);
        if (route.isEmpty()) return false;
        if (!launched) {
            plan.route.clear();
            plan.route.addAll(route);
            plan.routeIndex = 0;
        } else if (!plan.route.equals(route)) {
            return false;
        }
        return targetOwnershipStillValid(world, faction, plan, launched);
    }

    private static boolean targetOwnershipStillValid(World world, NpcFaction faction,
                                                     ExpeditionPlan plan, boolean launched) {
        GalaxyMapSystem target = system(world.authoritativeGalaxyMapSnapshot(), plan.targetSystemId);
        if (target == null) return false;
        String owner = target.controllerId() == null ? "" : target.controllerId();
        if (!owner.equals(plan.initialControllerId) && !owner.isBlank()
                && !faction.id().equals(owner)) return false;
        CandidateScan scan = scanSystem(world, faction, plan.targetSystemId);
        if (!launched && scan.friendlyAssets > 0) return false;
        return launched || scan.hostileBases <= plan.initialHostileBases;
    }

    private static TargetChoice chooseTarget(World world, NpcFaction faction, GalaxyMapSnapshot map) {
        if (map == null || map.empty()) return null;
        String sourceId = NpcFactionRuntime.homeSystemIdFor(faction);
        Map<String, Integer> degree = degrees(map);
        List<TargetChoice> choices = new ArrayList<>();
        for (GalaxyMapSystem candidate : map.systems()) {
            if (candidate == null || candidate.id().equals(sourceId) || !candidate.staticSystem()
                    || candidate.home() || faction.id().equals(candidate.controllerId())
                    || !NpcSystemScope.allowsExpansion(candidate.id(), faction.id())) continue;
            List<String> route = shortestPath(map, sourceId, candidate.id());
            if (route.isEmpty()) continue;
            CandidateScan scan = scanSystem(world, faction, candidate.id());
            if (scan.friendlyAssets > 0) continue;
            int hops = route.size() - 1;
            int connections = degree.getOrDefault(candidate.id(), 0);
            int frontier = frontierValue(map, candidate.id(), faction.id());
            double score = 0;
            score += candidate.controlStatus() == SystemControlStatus.NEUTRAL ? 320.0 : -80.0;
            score -= hops * 72.0;
            score += scan.activeResources * 8.0;
            score -= scan.hostileCombat * 55.0;
            score -= scan.hostileBases * 95.0;
            score += connections * 24.0;
            score += frontier * 16.0;
            score += route.size() > 1 ? 90.0 : 0.0;
            if (connections <= 1) score -= 35.0;
            score -= Math.floorMod(candidate.id().hashCode(), 10_000) * 0.000001;
            choices.add(new TargetChoice(candidate, route, score, scan.hostileBases));
        }
        return choices.stream()
                .max(Comparator.comparingDouble((TargetChoice choice) -> choice.score)
                        .thenComparing(choice -> choice.system.id(), Comparator.reverseOrder()))
                .orElse(null);
    }

    private static CandidateScan scanSystem(World world, NpcFaction faction, String systemId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        int resources = 0;
        int hostileCombat = 0;
        int hostileBases = 0;
        int friendlyAssets = 0;
        world.activateSystem(systemId);
        try {
            for (ResourceNode node : world.resources) if (node.active && node.amount > 0.05) resources++;
            for (Unit unit : world.units.values()) {
                if (unit.hp <= 0) continue;
                if (faction.id().equals(unit.playerId)) friendlyAssets++;
                else if (hostile(faction, unit.playerId) && WeaponRules.armed(unit.type())) hostileCombat++;
            }
            for (Base base : world.bases.values()) {
                if (base.hp <= 0) continue;
                if (faction.id().equals(base.playerId)) friendlyAssets++;
                else if (hostile(faction, base.playerId)) hostileBases++;
            }
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return new CandidateScan(resources, hostileCombat, hostileBases, friendlyAssets);
    }

    private static Map<String, Integer> degrees(GalaxyMapSnapshot map) {
        Map<String, Integer> result = new HashMap<>();
        for (GalaxyMapLink link : map.links()) {
            result.merge(link.fromSystemId(), 1, Integer::sum);
            result.merge(link.toSystemId(), 1, Integer::sum);
        }
        return result;
    }

    private static int frontierValue(GalaxyMapSnapshot map, String systemId, String factionId) {
        int value = 0;
        for (GalaxyMapLink link : map.links()) {
            String other = link.fromSystemId().equals(systemId) ? link.toSystemId()
                    : link.toSystemId().equals(systemId) ? link.fromSystemId() : "";
            if (other.isBlank()) continue;
            GalaxyMapSystem system = system(map, other);
            if (system != null && system.staticSystem() && !system.home()
                    && !factionId.equals(system.controllerId())) value++;
        }
        return value;
    }

    private static List<String> shortestPath(GalaxyMapSnapshot map, String source, String target) {
        if (map == null || source == null || target == null) return List.of();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (GalaxyMapLink link : map.links()) {
            adjacency.computeIfAbsent(link.fromSystemId(), ignored -> new ArrayList<>()).add(link.toSystemId());
            adjacency.computeIfAbsent(link.toSystemId(), ignored -> new ArrayList<>()).add(link.fromSystemId());
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
        ArrayList<String> route = new ArrayList<>();
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

    private static GalaxyMapSystem system(GalaxyMapSnapshot map, String id) {
        if (map == null || map.systems() == null || id == null) return null;
        for (GalaxyMapSystem system : map.systems()) if (system != null && id.equals(system.id())) return system;
        return null;
    }

    private static MemberLocations locateMembers(World world, ExpeditionPlan plan) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        Map<String, String> locations = new LinkedHashMap<>();
        try {
            for (String systemId : plan.route) {
                world.activateSystem(systemId);
                for (String key : plan.rosterKeys()) {
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

    private static Point assemblyPoint(World world, ExpeditionPlan plan) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(plan.sourceSystemId);
        try {
            return assemblyPointInCurrentSystem(world, plan);
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static Point assemblyPointInCurrentSystem(World world, ExpeditionPlan plan) {
        if (plan.route.size() < 2) return null;
        WormholeGate gate = gateTo(world, plan.route.get(1));
        if (gate == null) return null;
        Base source = world.bases.get(plan.sourceBaseId);
        double anchorX = source == null ? world.width * 0.5 : source.x;
        double anchorY = source == null ? world.height * 0.5 : source.y;
        double dx = anchorX - gate.x;
        double dy = anchorY - gate.y;
        double length = Math.max(1.0, Math.hypot(dx, dy));
        return new Point(
                Calc.clamp(gate.x + dx / length * ASSEMBLY_GATE_OFFSET, 0, world.width),
                Calc.clamp(gate.y + dy / length * ASSEMBLY_GATE_OFFSET, 0, world.height));
    }

    private static boolean allNear(World world, String systemId, Collection<String> keys,
                                   double x, double y, double radius) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(systemId);
        try {
            for (String key : keys) {
                Unit unit = world.units.get(key);
                if (unit == null || unit.hp <= 0 || Calc.distance(unit.x, unit.y, x, y) > radius) return false;
            }
            return !keys.isEmpty();
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static void moveFormation(World world, Collection<String> keys,
                                      double x, double y, double spacing) {
        int index = 0;
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            double angle = index * 1.7;
            double radius = index == 0 ? 0 : spacing * (1 + index / 5.0);
            prepareMove(unit,
                    Calc.clamp(x + Math.cos(angle) * radius, 0, world.width),
                    Calc.clamp(y + Math.sin(angle) * radius, 0, world.height));
            index++;
        }
    }

    private static void moveToGate(World world, Collection<String> keys, WormholeGate gate) {
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit != null && unit.hp > 0) prepareMove(unit, gate.x, gate.y);
        }
    }

    private static void holdUnits(World world, Collection<String> keys) {
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            unit.clearOrder();
            unit.attackTarget = "";
            unit.automationResourceId = -1;
            unit.task = UnitTask.IDLE;
            unit.targetX = unit.x;
            unit.targetY = unit.y;
        }
    }

    private static void prepareMove(Unit unit, double x, double y) {
        unit.clearOrder();
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.issueMove(x, y);
    }

    private static WormholeGate gateTo(World world, String targetSystemId) {
        for (WormholeGate gate : world.wormholes) {
            if (targetSystemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static Base findFoothold(World world, String factionId, double targetX, double targetY) {
        Base best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!factionId.equals(base.playerId) || base.hp <= 0) continue;
            double distance = targetX == 0 && targetY == 0 ? 0 : Calc.distance(base.x, base.y, targetX, targetY);
            if (distance < bestDistance) {
                best = base;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String validFootholdType(NpcFaction faction) {
        return Rules.findBase(faction.baseType()) == null ? Rules.DEFAULT_BASE : faction.baseType();
    }

    private static RuntimeState runtime(World world, NpcFaction faction) {
        Map<String, RuntimeState> byFaction = RUNTIMES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
        return byFaction.computeIfAbsent(faction.id(), ignored -> new RuntimeState(world.systemSeed()));
    }

    private static void transition(World world, NpcFaction faction, ExpeditionPlan plan,
                                   NpcExpeditionState state, String reason) {
        NpcExpeditionState previous = plan.state;
        plan.state = state;
        plan.stateSeconds = 0;
        plan.reason = reason == null ? "" : reason;
        AiDevLog.add(world, faction, "expedition " + previous + " -> " + state
                + " [target=" + plan.targetSystemId + ", " + plan.reason + "]");
        world.status = faction.name() + " expedition: " + state.name().toLowerCase().replace('_', ' ') + ".";
    }

    private static final class RuntimeState {
        long seed;
        double cooldownSeconds;
        ExpeditionPlan plan;
        NpcExpeditionState lastState = NpcExpeditionState.FAILED;
        String lastTarget = "";
        String lastReason = "";

        RuntimeState(long seed) { this.seed = seed; }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            cooldownSeconds = 0;
            plan = null;
            lastState = NpcExpeditionState.FAILED;
            lastTarget = "";
            lastReason = "system seed changed";
        }
    }

    private static final class ExpeditionPlan {
        final String sourceSystemId;
        final String targetSystemId;
        final String targetName;
        final List<String> route = new ArrayList<>();
        final double targetScore;
        final String initialControllerId;
        final int initialHostileBases;
        final List<String> combatKeys = new ArrayList<>();
        final List<String> supportKeys = new ArrayList<>();
        final EnumMap<Material, Double> supplies = new EnumMap<>(Material.class);
        final EnumMap<Material, Double> packageCost = new EnumMap<>(Material.class);
        NpcExpeditionState state = NpcExpeditionState.PLANNING;
        String sourceBaseId = "";
        String builderKey = "";
        String workerKey = "";
        String packageType = "";
        String footholdBaseId = "";
        String reason = "";
        int routeIndex;
        int establishRetries;
        int terminalTicks;
        double stateSeconds;
        double footholdX;
        double footholdY;
        boolean suppliesReserved;
        boolean packageCommitted;
        boolean suppliesDelivered;
        boolean launched;
        boolean released;

        ExpeditionPlan(String sourceSystemId, String targetSystemId, String targetName,
                       List<String> route, double targetScore, String initialControllerId,
                       int initialHostileBases) {
            this.sourceSystemId = sourceSystemId;
            this.targetSystemId = targetSystemId;
            this.targetName = targetName;
            this.route.addAll(route);
            this.targetScore = targetScore;
            this.initialControllerId = initialControllerId == null ? "" : initialControllerId;
            this.initialHostileBases = initialHostileBases;
        }

        Set<String> rosterKeys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            if (!builderKey.isBlank()) keys.add(builderKey);
            if (!workerKey.isBlank()) keys.add(workerKey);
            keys.addAll(combatKeys);
            keys.addAll(supportKeys);
            return keys;
        }
    }

    private static final class MemberLocations {
        final Map<String, String> byKey;

        MemberLocations(Map<String, String> byKey) { this.byKey = byKey; }

        static MemberLocations fromCurrentSystem(World world, Collection<String> keys) {
            Map<String, String> locations = new LinkedHashMap<>();
            for (String key : keys) {
                Unit unit = world.units.get(key);
                if (unit != null && unit.hp > 0) locations.put(key, world.activeSystemId());
            }
            return new MemberLocations(locations);
        }

        boolean contains(String key) { return key != null && !key.isBlank() && byKey.containsKey(key); }

        boolean allIn(Collection<String> keys, String systemId) {
            if (keys.isEmpty()) return false;
            for (String key : keys) if (!systemId.equals(byKey.get(key))) return false;
            return true;
        }

        boolean anyOutside(Collection<String> keys, String systemId) {
            for (String key : keys) {
                String location = byKey.get(key);
                if (location != null && !systemId.equals(location)) return true;
            }
            return false;
        }

        Set<String> presentKeys(Collection<String> keys) {
            LinkedHashSet<String> present = new LinkedHashSet<>();
            for (String key : keys) if (byKey.containsKey(key)) present.add(key);
            return present;
        }

        int commonRouteIndex(Collection<String> keys, List<String> route) {
            int common = -1;
            for (String key : keys) {
                String systemId = byKey.get(key);
                if (systemId == null) return -1;
                int index = route.indexOf(systemId);
                if (index < 0) return -1;
                if (common < 0) common = index;
                else if (common != index) return -1;
            }
            return common;
        }

        int minimumRouteIndex(Collection<String> keys, List<String> route) {
            int minimum = Integer.MAX_VALUE;
            for (String key : keys) {
                String systemId = byKey.get(key);
                if (systemId == null) continue;
                int index = route.indexOf(systemId);
                if (index >= 0) minimum = Math.min(minimum, index);
            }
            return minimum == Integer.MAX_VALUE ? -1 : minimum;
        }

        int maximumRouteIndex(Collection<String> keys, List<String> route) {
            int maximum = -1;
            for (String key : keys) {
                String systemId = byKey.get(key);
                if (systemId == null) continue;
                int index = route.indexOf(systemId);
                if (index >= 0) maximum = Math.max(maximum, index);
            }
            return maximum;
        }
    }

    private record Roster(Unit builder, Unit worker, List<String> combatKeys, List<String> supportKeys) { }
    private record TargetChoice(GalaxyMapSystem system, List<String> route,
                                double score, int hostileBases) { }
    private record CandidateScan(int activeResources, int hostileCombat,
                                 int hostileBases, int friendlyAssets) { }
    private record Point(double x, double y) { }
}

enum NpcExpeditionState {
    PLANNING,
    RESERVING,
    ASSEMBLING,
    LAUNCHING,
    TRAVELLING,
    ESTABLISHING,
    DEFENDING,
    SUCCEEDED,
    ABORTING,
    FAILED
}

record NpcExpeditionSnapshot(boolean active, NpcExpeditionState state,
                             String sourceSystemId, String targetSystemId,
                             List<String> route, int routeIndex,
                             String builderKey, String workerKey,
                             List<String> combatKeys, List<String> supportKeys,
                             Map<Material, Double> supplies, double targetScore,
                             double cooldownSeconds, String reason,
                             boolean launched, boolean suppliesDelivered) {
    static final NpcExpeditionSnapshot NONE = new NpcExpeditionSnapshot(
            false, NpcExpeditionState.FAILED, "", "", List.of(), 0,
            "", "", List.of(), List.of(), Map.of(), 0, 0, "", false, false);
}
