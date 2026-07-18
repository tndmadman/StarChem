package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Handles local recovery for organized NPC factions.
 *
 * Recovery runs after ordinary tactical, expedition, and squad decisions but
 * before unit movement. Managed repair, escort, evacuation, and stranded orders
 * therefore remain authoritative for the whole movement step.
 */
final class NpcRecoverySystem {
    private static final double REPAIR_START_RATIO = 0.72;
    private static final double RETREAT_REPAIR_RATIO = 0.95;
    private static final double REPAIR_ESCORT_RATIO = 0.80;
    private static final double REPAIR_HP_PER_SECOND = 18.0;
    private static final double REPAIR_BLOCKED_STABILIZE_SECONDS = 20.0;
    private static final double STRANDED_GRACE_SECONDS = 45.0;
    private static final double RECOVERY_PROGRESS_TIMEOUT_SECONDS = 120.0;
    private static final double EPSILON = 0.001;
    private static final List<Cost> EMERGENCY_OUTPOST_COST = List.of(
            new Cost(Material.IRON, 180.0),
            new Cost(Material.COPPER, 90.0),
            new Cost(Material.SILICATES, 100.0),
            new Cost(Material.ICE, 40.0));
    private static final Map<World, Map<String, RecoveryRuntime>> RUNTIMES = new WeakHashMap<>();

    private NpcRecoverySystem() { }

    static synchronized void update(World world, NpcFaction faction) {
        if (world == null || faction == null || !faction.enabled()
                || faction.behavior() != NpcBehavior.FACTION) return;

        String systemId = world.activeSystemId();
        RecoveryRuntime runtime = runtime(world, faction, systemId);
        runtime.resetForSeed(world.systemSeed());
        runtime.managedUnitKeys.clear();
        double dt = runtime.advance(world.systemTime());

        List<Unit> units = livingUnits(world, faction.id());
        List<Base> bases = livingBases(world, faction.id());
        if (units.isEmpty()) {
            releaseRepairEscorts(world, runtime, Set.of());
            runtime.resetLocalProgress();
            transition(world, faction, runtime, NpcRecoveryState.IDLE,
                    "no local ships require recovery");
            return;
        }

        if (!bases.isEmpty()) {
            runtime.strandedSeconds = 0;
            runtime.recoveryStallSeconds = 0;
            runtime.lastRecoveryProgress = 0;
            RepairResult result = repairDamagedShips(world, faction, units, bases, dt, runtime);
            runtime.repairBlockedSeconds = result.blocked
                    ? runtime.repairBlockedSeconds + dt : 0;
            if (result.blocked) orderEmergencyRepairMining(world, units, bases);
            transition(world, faction, runtime,
                    result.repairing ? NpcRecoveryState.REPAIRING : NpcRecoveryState.ACTIVE,
                    result.blocked
                            ? "hull repair blocked; emergency iron/copper economy active"
                            : result.repairing
                            ? "damaged ships returning for paid hull repair"
                            : "local station support available");
            return;
        }

        runtime.repairBlockedSeconds = 0;
        runtime.repairShipKeys.clear();
        releaseRepairEscorts(world, runtime, Set.of());

        if (constructionRecoveryInProgress(world, units)) {
            runtime.strandedSeconds = 0;
            runtime.recoveryStallSeconds = 0;
            transition(world, faction, runtime, NpcRecoveryState.REBUILDING,
                    "existing timed station construction remains viable");
            return;
        }

        if (tryEmergencyRebuild(world, faction, units)) {
            runtime.resetLocalProgress();
            transition(world, faction, runtime, NpcRecoveryState.REBUILDING,
                    "emergency recovery-capable foothold established");
            return;
        }

        WormholeGate gate = evacuationGate(world, faction);
        if (gate != null) {
            runtime.strandedSeconds = 0;
            runtime.recoveryStallSeconds = 0;
            for (Unit unit : units) runtime.managedUnitKeys.add(unit.key());
            evacuate(units, gate);
            transition(world, faction, runtime, NpcRecoveryState.EVACUATING,
                    "withdrawing through wormhole to " + gate.toSystemId);
            return;
        }

        RecoveryPath path = recoveryPath(world, faction, units);
        if (path.reachable) {
            double progress = materialProgress(units, path.cost);
            if (progress > runtime.lastRecoveryProgress + 0.05) {
                runtime.lastRecoveryProgress = progress;
                runtime.recoveryStallSeconds = 0;
            } else {
                runtime.recoveryStallSeconds += dt;
            }
            progressRecoveryMining(world, units, path, runtime);
            if (runtime.recoveryStallSeconds + EPSILON < RECOVERY_PROGRESS_TIMEOUT_SECONDS) {
                runtime.strandedSeconds = 0;
                transition(world, faction, runtime, NpcRecoveryState.STRANDED_RECOVERY,
                        "mining emergency outpost materials; progress="
                                + (int)Math.floor(progress) + "/" + (int)Math.ceil(costTotal(path.cost)));
                return;
            }
        } else {
            runtime.lastRecoveryProgress = 0;
            runtime.recoveryStallSeconds = 0;
        }

        for (Unit unit : units) runtime.managedUnitKeys.add(unit.key());
        rallyAtRecoveryAsset(world, units, null);
        runtime.strandedSeconds += dt;
        if (runtime.strandedSeconds + EPSILON < STRANDED_GRACE_SECONDS) {
            transition(world, faction, runtime, NpcRecoveryState.STRANDED,
                    path.reachable
                            ? "recovery made no progress; scuttle grace period active"
                            : "no reachable rebuild or escape route; scuttle grace period active");
            return;
        }

        int removed = scuttle(world, faction.id());
        runtime.managedUnitKeys.clear();
        transition(world, faction, runtime, NpcRecoveryState.SCUTTLED,
                "recovery impossible; scuttled " + removed + " stranded ship(s)");
    }

    static synchronized boolean ownsUnit(World world, Unit unit) {
        if (world == null || unit == null) return false;
        Map<String, RecoveryRuntime> runtimes = RUNTIMES.get(world);
        if (runtimes == null) return false;
        String activeSystemId = world.activeSystemId();
        for (RecoveryRuntime runtime : runtimes.values()) {
            if (runtime.systemId.equals(activeSystemId)
                    && runtime.managedUnitKeys.contains(unit.key())) return true;
        }
        return false;
    }

    static synchronized NpcRecoveryState state(World world, NpcFaction faction, String systemId) {
        if (world == null || faction == null) return NpcRecoveryState.IDLE;
        return runtime(world, faction, systemId).state;
    }

    static synchronized double strandedSeconds(World world, NpcFaction faction, String systemId) {
        if (world == null || faction == null) return 0;
        return runtime(world, faction, systemId).strandedSeconds;
    }

    static synchronized double repairBlockedSeconds(World world, NpcFaction faction) {
        if (world == null || faction == null) return 0;
        Map<String, RecoveryRuntime> runtimes = RUNTIMES.get(world);
        if (runtimes == null) return 0;
        double blocked = 0;
        String prefix = faction.id() + "|";
        for (Map.Entry<String, RecoveryRuntime> entry : runtimes.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                blocked = Math.max(blocked, entry.getValue().repairBlockedSeconds);
            }
        }
        return blocked;
    }

    static double blockedStabilizeSeconds() { return REPAIR_BLOCKED_STABILIZE_SECONDS; }

    static synchronized void clear(World world) {
        if (world != null) RUNTIMES.remove(world);
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String, RecoveryRuntime> runtimes = RUNTIMES.get(world);
        if (runtimes == null) return out;
        List<Object> rows = new ArrayList<>();
        for (Map.Entry<String, RecoveryRuntime> entry : runtimes.entrySet()) {
            RecoveryRuntime runtime = entry.getValue();
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("key", entry.getKey());
            row.put("systemId", runtime.systemId);
            row.put("seed", runtime.seed);
            row.put("state", runtime.state.name());
            row.put("lastSystemTime", runtime.lastSystemTime);
            row.put("strandedSeconds", runtime.strandedSeconds);
            row.put("repairBlockedSeconds", runtime.repairBlockedSeconds);
            row.put("recoveryStallSeconds", runtime.recoveryStallSeconds);
            row.put("lastRecoveryProgress", runtime.lastRecoveryProgress);
            row.put("managedUnitKeys", List.copyOf(runtime.managedUnitKeys));
            row.put("repairShipKeys", List.copyOf(runtime.repairShipKeys));
            row.put("repairEscortTargets", new LinkedHashMap<>(runtime.repairEscortTargets));
            rows.add(row);
        }
        out.put("runtimes", rows);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        Map<String, RecoveryRuntime> runtimes = new LinkedHashMap<>();
        for (Object item : ServerSaveStore.list(data.get("runtimes"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String systemId = ServerSaveStore.string(row, "systemId", "");
            RecoveryRuntime runtime = new RecoveryRuntime(ServerSaveStore.longValue(row, "seed", world.systemSeed()), systemId);
            runtime.state = ServerSaveStore.enumValue(NpcRecoveryState.class, row.get("state"), NpcRecoveryState.IDLE);
            runtime.lastSystemTime = ServerSaveStore.doubleValue(row, "lastSystemTime", Double.NaN);
            runtime.strandedSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "strandedSeconds", 0));
            runtime.repairBlockedSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "repairBlockedSeconds", 0));
            runtime.recoveryStallSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "recoveryStallSeconds", 0));
            runtime.lastRecoveryProgress = Math.max(0, ServerSaveStore.doubleValue(row, "lastRecoveryProgress", 0));
            restoreStringList(row.get("managedUnitKeys"), runtime.managedUnitKeys);
            restoreStringList(row.get("repairShipKeys"), runtime.repairShipKeys);
            for (Map.Entry<String,Object> entry : ServerSaveStore.object(row.get("repairEscortTargets")).entrySet()) {
                String target = ServerSaveStore.asString(entry.getValue(), "");
                if (!entry.getKey().isBlank() && !target.isBlank()) runtime.repairEscortTargets.put(entry.getKey(), target);
            }
            String key = ServerSaveStore.string(row, "key", "");
            if (key.isBlank()) key = "|" + systemId;
            runtimes.put(key, runtime);
        }
        if (runtimes.isEmpty()) RUNTIMES.remove(world);
        else RUNTIMES.put(world, runtimes);
    }

    static synchronized void clearFaction(World world, NpcFaction faction) {
        if (world == null || faction == null) return;
        Map<String, RecoveryRuntime> runtimes = RUNTIMES.get(world);
        if (runtimes == null) return;
        String prefix = faction.id() + "|";
        runtimes.keySet().removeIf(key -> key.startsWith(prefix));
        if (runtimes.isEmpty()) RUNTIMES.remove(world);
    }

    private static void restoreStringList(Object saved, Set<String> target) {
        for (Object item : ServerSaveStore.list(saved)) {
            String value = ServerSaveStore.asString(item, "");
            if (!value.isBlank()) target.add(value);
        }
    }

    private static RepairResult repairDamagedShips(World world, NpcFaction faction,
                                                   List<Unit> units, List<Base> bases,
                                                   double dt, RecoveryRuntime runtime) {
        boolean strategicRetreat = NpcStrategicDirector.state(world, faction) == NpcStrategicState.RETREAT;
        double threshold = strategicRetreat ? RETREAT_REPAIR_RATIO : REPAIR_START_RATIO;
        Set<String> livingKeys = new LinkedHashSet<>();
        for (Unit unit : units) livingKeys.add(unit.key());
        runtime.repairShipKeys.retainAll(livingKeys);

        for (Unit unit : units) {
            if (!WeaponRules.armed(unit.type())) continue;
            double ratio = hpRatio(unit);
            if (ratio + EPSILON < threshold) runtime.repairShipKeys.add(unit.key());
            if (unit.hp + EPSILON >= unit.type().maxHp) runtime.repairShipKeys.remove(unit.key());
        }

        List<Unit> retreatingShips = new ArrayList<>();
        boolean blocked = false;
        for (Unit unit : units) {
            if (!runtime.repairShipKeys.contains(unit.key())) continue;
            retreatingShips.add(unit);
            runtime.managedUnitKeys.add(unit.key());
            Base station = nearestBase(bases, unit.x, unit.y);
            if (station == null) continue;
            double serviceRange = Math.max(55.0, station.type().unloadRange * 0.72);
            if (Calc.distance(unit.x, unit.y, station.x, station.y) > serviceRange) {
                unit.issueMove(station.x, station.y);
                continue;
            }

            hold(unit);
            double missing = Math.max(0, unit.type().maxHp - unit.hp);
            double amount = Math.min(missing, REPAIR_HP_PER_SECOND * Math.max(0, dt));
            if (amount <= EPSILON) continue;
            List<Cost> cost = repairCost(amount);
            if (!NpcResourceBudget.spend(world, faction, NpcBudgetCategory.STATION_RECOVERY, cost)) {
                blocked = true;
                continue;
            }
            unit.hp = Math.min(unit.type().maxHp, unit.hp + amount);
            if (unit.hp + EPSILON >= unit.type().maxHp) runtime.repairShipKeys.remove(unit.key());
        }
        assignRepairEscorts(world, units, retreatingShips, runtime);
        return new RepairResult(!retreatingShips.isEmpty(), blocked);
    }

    private static void assignRepairEscorts(World world, List<Unit> units,
                                            List<Unit> retreatingShips,
                                            RecoveryRuntime runtime) {
        Set<String> retreatingKeys = new LinkedHashSet<>();
        for (Unit unit : retreatingShips) retreatingKeys.add(unit.key());
        releaseRepairEscorts(world, runtime, retreatingKeys);
        if (retreatingShips.isEmpty()) return;

        Set<String> alreadyProtected = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : runtime.repairEscortTargets.entrySet()) {
            Unit escort = world.units.get(entry.getKey());
            Unit protectedShip = CombatTarget.unit(world, entry.getValue());
            if (escort == null || protectedShip == null
                    || !retreatingKeys.contains(protectedShip.key())) continue;
            alreadyProtected.add(protectedShip.key());
            runtime.managedUnitKeys.add(escort.key());
        }

        List<Unit> candidates = new ArrayList<>();
        for (Unit unit : units) {
            if (retreatingKeys.contains(unit.key()) || !WeaponRules.armed(unit.type())) continue;
            if (hpRatio(unit) < REPAIR_ESCORT_RATIO) continue;
            if (unit.orderType == UnitOrderType.ESCORT
                    && !runtime.repairEscortTargets.containsKey(unit.key())) continue;
            if (!runtime.repairEscortTargets.containsKey(unit.key())) candidates.add(unit);
        }
        candidates.sort(Comparator.comparingInt(unit -> unit.unitId));
        retreatingShips.sort(Comparator
                .comparingDouble(NpcRecoverySystem::hpRatio)
                .thenComparingInt(unit -> unit.unitId));

        int candidateIndex = 0;
        for (Unit protectedShip : retreatingShips) {
            if (alreadyProtected.contains(protectedShip.key()) || candidateIndex >= candidates.size()) continue;
            Unit escort = candidates.get(candidateIndex++);
            String targetKey = CombatTarget.unit(protectedShip);
            if (AUnitOrder.apply(world, new UnitOrderCommand(
                    escort.playerId,
                    escort.unitId,
                    UnitOrderType.ESCORT,
                    protectedShip.x,
                    protectedShip.y,
                    protectedShip.x,
                    protectedShip.y,
                    UnitOrderSystem.defaultRadius(UnitOrderType.ESCORT),
                    targetKey,
                    0))) {
                runtime.repairEscortTargets.put(escort.key(), targetKey);
                runtime.managedUnitKeys.add(escort.key());
            }
        }
    }

    private static void releaseRepairEscorts(World world, RecoveryRuntime runtime,
                                             Set<String> retreatingKeys) {
        for (Map.Entry<String, String> entry
                : new ArrayList<>(runtime.repairEscortTargets.entrySet())) {
            Unit protectedShip = CombatTarget.unit(world, entry.getValue());
            if (protectedShip != null && retreatingKeys.contains(protectedShip.key())) continue;
            Unit escort = world.units.get(entry.getKey());
            if (escort != null && escort.orderType == UnitOrderType.ESCORT
                    && escort.orderTarget.equals(entry.getValue())) {
                escort.clearOrder();
                if (escort.task != UnitTask.ATTACK) escort.task = UnitTask.IDLE;
            }
            runtime.repairEscortTargets.remove(entry.getKey());
            runtime.managedUnitKeys.remove(entry.getKey());
        }
    }

    private static List<Cost> repairCost(double hp) {
        return List.of(
                new Cost(Material.IRON, Math.max(0.05, hp * 0.04)),
                new Cost(Material.COPPER, Math.max(0.02, hp * 0.015)));
    }

    private static void orderEmergencyRepairMining(World world,
                                                   List<Unit> units, List<Base> bases) {
        double iron = materialInBases(bases, Material.IRON);
        double copper = materialInBases(bases, Material.COPPER);
        List<Material> priorities = iron <= copper
                ? List.of(Material.IRON, Material.COPPER)
                : List.of(Material.COPPER, Material.IRON);
        int offset = 0;
        for (Unit worker : units.stream()
                .filter(unit -> !unit.type().harvestKinds.isEmpty() && unit.freeCargo() > 0.05)
                .sorted(Comparator.comparingInt(unit -> unit.unitId)).toList()) {
            ResourceNode node = nearestRecoveryNode(world, worker,
                    priorities.get(offset++ % priorities.size()));
            if (node == null) node = nearestRecoveryNode(world, worker,
                    priorities.get(offset % priorities.size()));
            if (node != null) worker.startAutoHarvest(node.id);
        }
    }

    private static boolean constructionRecoveryInProgress(World world, List<Unit> units) {
        for (Unit unit : units) {
            if (unit.type().baseBuilder && NpcStationConstructionSystem.ownsBuilder(world, unit.key())) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryEmergencyRebuild(World world, NpcFaction faction, List<Unit> units) {
        Unit loadedBuilder = units.stream()
                .filter(unit -> unit.type().baseBuilder && !unit.basePackageType.isBlank())
                .filter(unit -> !NpcStationConstructionSystem.ownsBuilder(world, unit.key()))
                .filter(unit -> recoveryCapableStation(world, faction,
                        Rules.findBase(unit.basePackageType)))
                .min(Comparator.comparingInt(unit -> unit.unitId))
                .orElse(null);
        if (loadedBuilder != null) return world.placePackage(loadedBuilder);

        Unit builder = emptyBuilder(units);
        if (builder == null) return false;
        List<Cost> cost = emergencyCost(faction);
        if (!canCoverFromUnits(units, cost)) return false;
        consumeFromUnits(units, cost);

        String typeId = Rules.BASES.containsKey(faction.baseType())
                ? faction.baseType() : Rules.DEFAULT_BASE;
        String baseId = nextBaseId(world, faction.id());
        Base base = new Base(baseId, faction.id(), typeId,
                Calc.clamp(builder.x, 0, world.width),
                Calc.clamp(builder.y, 0, world.height));
        transferSurplusToBase(units, base);
        world.units.remove(builder.key());
        world.bases.put(base.id, base);
        world.status = faction.name() + " established an emergency " + base.type().name + ".";
        return true;
    }

    private static boolean recoveryCapableStation(World world, NpcFaction faction, BaseType station) {
        if (station == null) return false;
        if (station.buildableShips.contains("station_builder")
                && ResearchRules.shipUnlocked(world, faction.id(), "station_builder")) return true;
        for (String workerType : faction.workerUnitTypes()) {
            if (station.buildableShips.contains(workerType)
                    && ResearchRules.shipUnlocked(world, faction.id(), workerType)) return true;
        }
        return station.basePackages.contains(faction.baseType())
                || station.basePackages.contains(Rules.DEFAULT_BASE);
    }

    private static RecoveryPath recoveryPath(World world, NpcFaction faction, List<Unit> units) {
        Unit builder = emptyBuilder(units);
        List<Cost> cost = emergencyCost(faction);
        if (builder == null) return RecoveryPath.NONE;
        EnumMap<Material, Double> missing = missingCost(units, cost);
        if (missing.isEmpty()) return new RecoveryPath(true, builder, cost, missing);

        List<Unit> workers = units.stream()
                .filter(unit -> !unit.type().harvestKinds.isEmpty())
                .sorted(Comparator.comparingInt(unit -> unit.unitId))
                .toList();
        if (workers.isEmpty()) return RecoveryPath.NONE;
        double freeWorkerCargo = workers.stream().mapToDouble(Unit::freeCargo).sum();
        if (freeWorkerCargo + EPSILON < missing.values().stream()
                .mapToDouble(Double::doubleValue).sum()) return RecoveryPath.NONE;

        for (Map.Entry<Material, Double> entry : missing.entrySet()) {
            double available = 0;
            for (ResourceNode node : world.resources) {
                if (!node.active || node.amount <= EPSILON || node.material != entry.getKey()) continue;
                boolean harvestable = workers.stream()
                        .anyMatch(worker -> worker.type().harvestKinds.contains(node.kind));
                if (harvestable) available += node.amount;
            }
            if (available + EPSILON < entry.getValue()) return RecoveryPath.NONE;
        }
        return new RecoveryPath(true, builder, cost, missing);
    }

    private static void progressRecoveryMining(World world, List<Unit> units,
                                               RecoveryPath path, RecoveryRuntime runtime) {
        EnumMap<Material, Double> remaining = missingCost(units, path.cost);
        List<Unit> workers = units.stream()
                .filter(unit -> !unit.type().harvestKinds.isEmpty() && unit.freeCargo() > 0.05)
                .sorted(Comparator.comparingInt(unit -> unit.unitId))
                .toList();
        for (Unit worker : workers) {
            ResourceNode best = null;
            Material bestMaterial = null;
            double bestNeed = -1;
            double bestDistance = Double.MAX_VALUE;
            for (Map.Entry<Material, Double> need : remaining.entrySet()) {
                if (need.getValue() <= EPSILON) continue;
                ResourceNode node = nearestRecoveryNode(world, worker, need.getKey());
                if (node == null) continue;
                double distance = Calc.distance(worker.x, worker.y, node.x, node.y);
                if (need.getValue() > bestNeed + EPSILON
                        || (Math.abs(need.getValue() - bestNeed) <= EPSILON
                        && distance < bestDistance)) {
                    best = node;
                    bestMaterial = need.getKey();
                    bestNeed = need.getValue();
                    bestDistance = distance;
                }
            }
            if (best == null || bestMaterial == null) continue;
            worker.startAutoHarvest(best.id);
            remaining.put(bestMaterial,
                    Math.max(0, remaining.get(bestMaterial)
                            - Math.min(worker.freeCargo(), best.amount)));
        }

        runtime.managedUnitKeys.add(path.builder.key());
        hold(path.builder);
        rallyAtRecoveryAsset(world, units, path.builder);
    }

    private static ResourceNode nearestRecoveryNode(World world, Unit worker, Material material) {
        ResourceNode best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.amount <= EPSILON || node.material != material
                    || !worker.type().harvestKinds.contains(node.kind)) continue;
            double distance = Calc.distance(worker.x, worker.y, node.x, node.y);
            if (distance < bestDistance) {
                best = node;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Unit emptyBuilder(List<Unit> units) {
        return units.stream()
                .filter(unit -> unit.type().baseBuilder && unit.basePackageType.isBlank())
                .min(Comparator.comparingInt(unit -> unit.unitId))
                .orElse(null);
    }

    private static List<Cost> emergencyCost(NpcFaction faction) {
        BaseType type = Rules.findBase(faction.baseType());
        if (type != null && type.buildCost != null && !type.buildCost.isEmpty()) {
            return type.buildCost;
        }
        return EMERGENCY_OUTPOST_COST;
    }

    private static EnumMap<Material, Double> missingCost(List<Unit> units, List<Cost> cost) {
        EnumMap<Material, Double> missing = new EnumMap<>(Material.class);
        for (Cost need : cost) {
            double held = materialInUnits(units, need.material());
            double amount = Math.max(0, need.amount() - held);
            if (amount > EPSILON) missing.put(need.material(), amount);
        }
        return missing;
    }

    private static double materialProgress(List<Unit> units, List<Cost> cost) {
        double progress = 0;
        for (Cost need : cost) {
            progress += Math.min(need.amount(), materialInUnits(units, need.material()));
        }
        return progress;
    }

    private static double costTotal(List<Cost> cost) {
        return cost.stream().mapToDouble(Cost::amount).sum();
    }

    private static double materialInUnits(List<Unit> units, Material material) {
        double total = 0;
        for (Unit unit : units) total += unit.inventory.getOrDefault(material, 0.0);
        return total;
    }

    private static double materialInBases(List<Base> bases, Material material) {
        double total = 0;
        for (Base base : bases) total += base.inventory.getOrDefault(material, 0.0);
        return total;
    }

    private static boolean canCoverFromUnits(List<Unit> units, List<Cost> cost) {
        for (Cost need : cost) {
            if (materialInUnits(units, need.material()) + EPSILON < need.amount()) return false;
        }
        return true;
    }

    private static void consumeFromUnits(List<Unit> units, List<Cost> cost) {
        List<Unit> ordered = new ArrayList<>(units);
        ordered.sort(Comparator.comparingInt(unit -> unit.unitId));
        for (Cost need : cost) {
            double remaining = need.amount();
            for (Unit unit : ordered) {
                if (remaining <= EPSILON) break;
                double held = unit.inventory.getOrDefault(need.material(), 0.0);
                if (held <= EPSILON) continue;
                double take = Math.min(held, remaining);
                double left = held - take;
                if (left <= 0.05) unit.inventory.remove(need.material());
                else unit.inventory.put(need.material(), left);
                remaining -= take;
            }
        }
    }

    private static void transferSurplusToBase(List<Unit> units, Base base) {
        for (Unit unit : units) {
            for (Material material : new ArrayList<>(unit.inventory.keySet())) {
                double amount = unit.inventory.getOrDefault(material, 0.0);
                if (amount <= EPSILON) continue;
                HangarStore.add(base.inventory, material, amount);
                unit.inventory.remove(material);
            }
        }
    }

    private static WormholeGate evacuationGate(World world, NpcFaction faction) {
        if (world.wormholes.isEmpty()) return null;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        String homeId = NpcFactionRuntime.homeSystemIdFor(faction);
        WormholeGate best = null;
        double bestScore = -Double.MAX_VALUE;
        for (WormholeGate gate : world.wormholes) {
            GalaxyMapSystem target = system(map, gate.toSystemId);
            if (target != null && target.home() && !homeId.equals(gate.toSystemId)) continue;
            double score = 0;
            if (homeId.equals(gate.toSystemId)) score += 1000;
            if (target != null && faction.id().equals(target.controllerId())) score += 400;
            if (target != null && target.staticSystem()) score += 80;
            if (target != null && target.controlStatus() == SystemControlStatus.NEUTRAL) score += 20;
            score -= Math.floorMod(gate.toSystemId.hashCode(), 1000) * 0.0001;
            if (score > bestScore) {
                bestScore = score;
                best = gate;
            }
        }
        return best;
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        if (snapshot == null || snapshot.systems() == null || id == null) return null;
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && id.equals(system.id())) return system;
        }
        return null;
    }

    private static void evacuate(List<Unit> units, WormholeGate gate) {
        for (Unit unit : units) unit.issueMove(gate.x, gate.y);
    }

    private static void rallyAtRecoveryAsset(World world, List<Unit> units, Unit preferredAnchor) {
        Unit anchor = preferredAnchor;
        if (anchor == null) {
            anchor = units.stream()
                    .filter(MobileDepot::isDepot)
                    .min(Comparator.comparingInt(unit -> unit.unitId))
                    .orElseGet(() -> units.stream()
                            .filter(unit -> unit.type().baseBuilder)
                            .min(Comparator.comparingInt(unit -> unit.unitId))
                            .orElse(null));
        }
        if (anchor == null) return;
        int index = 0;
        for (Unit unit : units) {
            if (unit == anchor || !unit.type().harvestKinds.isEmpty()) continue;
            double angle = index++ * 1.7;
            unit.issueMove(
                    Calc.clamp(anchor.x + Math.cos(angle) * 110.0, 0, world.width),
                    Calc.clamp(anchor.y + Math.sin(angle) * 110.0, 0, world.height));
        }
    }

    private static int scuttle(World world, String factionId) {
        int removed = 0;
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (!factionId.equals(unit.playerId) || unit.hp <= 0) continue;
            world.explodeUnit(unit);
            world.units.remove(unit.key());
            removed++;
        }
        return removed;
    }

    private static void hold(Unit unit) {
        if (unit == null) return;
        unit.clearOrder();
        unit.attackTarget = "";
        unit.task = UnitTask.IDLE;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
    }

    private static double hpRatio(Unit unit) {
        return unit.hp / Math.max(1.0, unit.type().maxHp);
    }

    private static Base nearestBase(List<Base> bases, double x, double y) {
        Base best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Base base : bases) {
            double distance = Calc.distance(x, y, base.x, base.y);
            if (distance < bestDistance) {
                best = base;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static List<Unit> livingUnits(World world, String factionId) {
        List<Unit> units = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (factionId.equals(unit.playerId) && unit.hp > 0) units.add(unit);
        }
        units.sort(Comparator.comparingInt(unit -> unit.unitId));
        return units;
    }

    private static List<Base> livingBases(World world, String factionId) {
        List<Base> bases = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) bases.add(base);
        }
        return bases;
    }

    private static String nextBaseId(World world, String factionId) {
        int max = 0;
        String prefix = factionId + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return prefix + (max + 1);
    }

    private static RecoveryRuntime runtime(World world, NpcFaction faction, String systemId) {
        Map<String, RecoveryRuntime> byKey = RUNTIMES.computeIfAbsent(
                world, ignored -> new LinkedHashMap<>());
        String normalizedSystem = systemId == null ? "" : systemId;
        String key = faction.id() + "|" + normalizedSystem;
        return byKey.computeIfAbsent(key,
                ignored -> new RecoveryRuntime(world.systemSeed(), normalizedSystem));
    }

    private static void transition(World world, NpcFaction faction, RecoveryRuntime runtime,
                                   NpcRecoveryState next, String detail) {
        if (runtime.state == next) return;
        NpcRecoveryState previous = runtime.state;
        runtime.state = next;
        AiDevLog.add(world, faction,
                "recovery " + previous + " -> " + next + " [" + detail + "]");
    }

    private static final class RecoveryRuntime {
        final String systemId;
        long seed;
        NpcRecoveryState state = NpcRecoveryState.IDLE;
        double lastSystemTime = Double.NaN;
        double strandedSeconds;
        double repairBlockedSeconds;
        double recoveryStallSeconds;
        double lastRecoveryProgress;
        final Set<String> managedUnitKeys = new LinkedHashSet<>();
        final Set<String> repairShipKeys = new LinkedHashSet<>();
        final Map<String, String> repairEscortTargets = new LinkedHashMap<>();

        RecoveryRuntime(long seed, String systemId) {
            this.seed = seed;
            this.systemId = systemId;
        }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            state = NpcRecoveryState.IDLE;
            lastSystemTime = Double.NaN;
            managedUnitKeys.clear();
            repairShipKeys.clear();
            repairEscortTargets.clear();
            resetLocalProgress();
        }

        void resetLocalProgress() {
            strandedSeconds = 0;
            repairBlockedSeconds = 0;
            recoveryStallSeconds = 0;
            lastRecoveryProgress = 0;
        }

        double advance(double systemTime) {
            double dt = Double.isFinite(lastSystemTime) && systemTime >= lastSystemTime
                    ? Math.min(5.0, systemTime - lastSystemTime) : 0;
            lastSystemTime = systemTime;
            return Math.max(0, dt);
        }
    }

    private record RepairResult(boolean repairing, boolean blocked) { }

    private record RecoveryPath(boolean reachable, Unit builder,
                                List<Cost> cost,
                                EnumMap<Material, Double> missing) {
        static final RecoveryPath NONE = new RecoveryPath(
                false, null, List.of(), new EnumMap<>(Material.class));
    }
}

enum NpcRecoveryState {
    IDLE,
    ACTIVE,
    REPAIRING,
    REBUILDING,
    EVACUATING,
    STRANDED_RECOVERY,
    STRANDED,
    SCUTTLED
}
