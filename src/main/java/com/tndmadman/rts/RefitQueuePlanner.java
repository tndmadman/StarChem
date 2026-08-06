package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans and atomically commits refits across every owned refit-capable station. */
final class RefitQueuePlanner {
    private static final double OFFLINE_QUEUE_PENALTY_SECONDS = 900;
    private static final double BLOCKED_QUEUE_PENALTY_SECONDS = 3_600;
    private static int failAfterInsertionsForTest = -1;

    private RefitQueuePlanner() { }

    static Result preflight(World world, String playerId, List<Unit> requested,
                            ShipLoadoutDefinition loadout, boolean free, Base preferredBase) {
        Plan plan = plan(world, playerId, requested, loadout,
                ShipModuleRules.moduleIds(loadout), free, preferredBase);
        return plan.success() ? resultFor(plan, loadout) : Result.fail(plan.failure());
    }

    static Result enqueue(World world, String playerId, List<Unit> requested,
                          ShipLoadoutDefinition loadout, boolean free, Base preferredBase) {
        Plan plan = plan(world, playerId, requested, loadout,
                ShipModuleRules.moduleIds(loadout), free, preferredBase);
        if (!plan.success()) return Result.fail(plan.failure());
        return commit(world, playerId, plan, loadout, null, "", free);
    }

    static Result enqueueCustom(World world, String playerId, List<Unit> requested, String name,
                                ShipFitSpec spec, boolean free, Base preferredBase) {
        ShipLoadoutDefinition preview = PlayerFitRules.previewDefinition(name, spec);
        Plan plan = plan(world, playerId, requested, preview, spec.moduleIds(), free, preferredBase);
        if (!plan.success()) return Result.fail(plan.failure());
        return commit(world, playerId, plan, preview, spec, name, free);
    }

    static void failAfterInsertionsForTest(int value) {
        failAfterInsertionsForTest = value;
    }

    private static Result commit(World world, String playerId, Plan plan,
                                 ShipLoadoutDefinition preview, ShipFitSpec customSpec,
                                 String customName, boolean free) {
        String stale = revalidate(world, playerId, plan, preview, free);
        if (!stale.isBlank()) return Result.fail(stale);

        Map<Base,EnumMap<Material,Double>> inventoryBefore = new LinkedHashMap<>();
        Map<Base,Integer> queueSizeBefore = new LinkedHashMap<>();
        Map<Base,Long> nextJobBefore = new LinkedHashMap<>();
        for (Base station : plan.stationCosts().keySet()) {
            inventoryBefore.put(station, new EnumMap<>(station.inventory));
            queueSizeBefore.put(station, station.productionQueue.size());
            nextJobBefore.put(station, station.nextProductionJobId);
        }
        Map<Unit,UnitState> unitBefore = new LinkedHashMap<>();
        for (Assignment assignment : plan.assignments()) {
            unitBefore.put(assignment.unit(), UnitState.capture(assignment.unit()));
        }

        Result result = resultFor(plan, preview);
        try {
            if (!free) for (Map.Entry<Base,List<Cost>> entry : plan.stationCosts().entrySet()) {
                HangarStore.spend(entry.getKey().inventory, entry.getValue());
            }

            List<Committed> committed = new ArrayList<>();
            int inserted = 0;
            for (Assignment assignment : plan.assignments()) {
                ProductionJob job = ProductionSystem.enqueueRefitPrepaid(
                        assignment.station(), assignment.unit(), preview,
                        assignment.quote(), !free);
                if (job == null) throw new IllegalStateException("Could not insert a planned refit job.");
                committed.add(new Committed(assignment, job));
                inserted++;
                if (failAfterInsertionsForTest > 0 && inserted >= failAfterInsertionsForTest) {
                    throw new IllegalStateException("Injected refit transaction failure after job " + inserted + ".");
                }
            }

            for (Committed value : committed) {
                ProductionSystem.beginRefit(world, value.assignment().station(),
                        value.assignment().unit(), value.job());
            }

            if (customSpec != null) {
                ShipLoadoutDefinition installed = WorldFitCatalog.registerRuntime(world, customName, customSpec);
                if (!installed.id().equals(preview.id())) {
                    throw new IllegalStateException("Runtime fit registration changed the planned fit ID.");
                }
            }
        } catch (RuntimeException ex) {
            rollbackStations(inventoryBefore, queueSizeBefore, nextJobBefore);
            for (Map.Entry<Unit,UnitState> entry : unitBefore.entrySet()) {
                entry.getValue().restore(entry.getKey());
            }
            return Result.fail("Refit transaction was rolled back: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        } finally {
            failAfterInsertionsForTest = -1;
        }

        world.status = result.message();
        AlertCenter.push(world, result.message());
        return result;
    }

    private static void rollbackStations(Map<Base,EnumMap<Material,Double>> inventoryBefore,
                                         Map<Base,Integer> queueSizeBefore,
                                         Map<Base,Long> nextJobBefore) {
        for (Map.Entry<Base,Integer> entry : queueSizeBefore.entrySet()) {
            Base station = entry.getKey();
            while (station.productionQueue.size() > entry.getValue()) {
                station.productionQueue.remove(station.productionQueue.size() - 1);
            }
            station.nextProductionJobId = nextJobBefore.get(station);
            station.inventory.clear();
            station.inventory.putAll(inventoryBefore.get(station));
        }
    }

    private static String revalidate(World world, String playerId, Plan plan,
                                     ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || playerId == null || playerId.isBlank()) return "Refit world is unavailable.";
        if (!free && !WeaponRules.unlocked(world, playerId, loadout)) {
            return loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, playerId, loadout) + ".";
        }
        for (Assignment assignment : plan.assignments()) {
            Base station = assignment.station();
            Unit unit = assignment.unit();
            if (station.hp <= 0 || !station.type().canRefitShips || !playerId.equals(station.playerId)) {
                return "A planned refit station is no longer available.";
            }
            if (unit.hp <= 0 || !playerId.equals(unit.playerId)
                    || !loadout.hullId().equals(unit.shipTypeId)
                    || loadout.id().equals(unit.loadoutId)
                    || !assignment.quote().sourceLoadoutId().equals(unit.loadoutId)
                    || ProductionSystem.refitReserved(world, unit.key())) {
                return "A planned refit ship is no longer available.";
            }
        }
        if (!free) for (Map.Entry<Base,List<Cost>> entry : plan.stationCosts().entrySet()) {
            if (!HangarStore.canAfford(entry.getKey().inventory, entry.getValue())) {
                return entry.getKey().type().name + " can no longer fund its assigned refits.";
            }
        }
        return "";
    }

    private static Plan plan(World world, String playerId, List<Unit> requested,
                             ShipLoadoutDefinition loadout, List<String> destinationModules,
                             boolean free, Base preferredBase) {
        if (world == null || playerId == null || playerId.isBlank() || loadout == null) {
            return Plan.fail("Refit request is incomplete.");
        }
        List<Unit> units = cleanUnits(world, playerId, requested, loadout);
        if (units.isEmpty()) return Plan.fail("No available ships can be queued for this refit.");
        if (!free && !WeaponRules.unlocked(world, playerId, loadout)) {
            return Plan.fail(loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, playerId, loadout) + ".");
        }
        List<Base> stations = stations(world, playerId);
        if (stations.isEmpty()) return Plan.fail("No owned refit-capable station exists in this system.");

        Map<Base,EnumMap<Material,Double>> simulatedInventory = new LinkedHashMap<>();
        Map<Base,Double> availableAt = new LinkedHashMap<>();
        Map<Base,EnumMap<Material,Double>> stationCostTotals = new LinkedHashMap<>();
        for (Base station : stations) {
            simulatedInventory.put(station, new EnumMap<>(station.inventory));
            availableAt.put(station, queueAvailableAt(world, station));
            stationCostTotals.put(station, new EnumMap<>(Material.class));
        }

        List<Assignment> assignments = new ArrayList<>();
        Map<Base,Integer> distribution = new LinkedHashMap<>();
        for (Unit unit : units) {
            RefitQuote quote;
            try {
                quote = RefitQuote.between(world, unit, loadout, destinationModules);
            } catch (IllegalArgumentException ex) {
                return Plan.fail(ex.getMessage());
            }
            Base station = chooseStation(unit, quote, preferredBase, stations,
                    simulatedInventory, availableAt, free);
            if (station == null) {
                return Plan.fail("No refit-capable station can fund every requested conversion. "
                        + "Required additions vary by source fit.");
            }
            if (!free) {
                HangarStore.spend(simulatedInventory.get(station), quote.requiredMaterials());
                merge(stationCostTotals.get(station), quote.requiredMaterials());
            }
            double ready = Math.max(availableAt.getOrDefault(station, 0.0),
                    travelSeconds(unit, station));
            availableAt.put(station, ready + quote.durationSeconds());
            distribution.merge(station, 1, Integer::sum);
            assignments.add(new Assignment(station, unit, quote));
        }

        Map<Base,List<Cost>> stationCosts = new LinkedHashMap<>();
        for (Map.Entry<Base,EnumMap<Material,Double>> entry : stationCostTotals.entrySet()) {
            if (distribution.containsKey(entry.getKey())) {
                stationCosts.put(entry.getKey(), costs(entry.getValue()));
            }
        }
        return Plan.ok(assignments, distribution, stationCosts);
    }

    private static Result resultFor(Plan plan, ShipLoadoutDefinition loadout) {
        String message = "Queued " + plan.assignments().size() + " "
                + Rules.ship(loadout.hullId()).name + " refit"
                + (plan.assignments().size() == 1 ? "" : "s") + " atomically across "
                + plan.distribution().size() + " station"
                + (plan.distribution().size() == 1 ? "" : "s") + ": "
                + distributionLabel(plan.distribution()) + ".";
        return Result.ok(plan.assignments().size(), plan.distribution(),
                plan.assignments().get(0).station(), message);
    }

    static Base bestStation(World world, Unit unit, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || unit == null || loadout == null) return null;
        RefitQuote quote;
        try {
            quote = RefitQuote.between(world, unit, loadout);
        } catch (RuntimeException ex) {
            return null;
        }
        List<Base> stations = stations(world, unit.playerId);
        Map<Base,EnumMap<Material,Double>> inventory = new LinkedHashMap<>();
        Map<Base,Double> available = new LinkedHashMap<>();
        for (Base station : stations) {
            inventory.put(station, new EnumMap<>(station.inventory));
            available.put(station, queueAvailableAt(world, station));
        }
        return chooseStation(unit, quote, null, stations, inventory, available, free);
    }

    static List<Base> stations(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        List<Base> out = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && playerId.equals(base.playerId)
                    && base.type().canRefitShips) out.add(base);
        }
        out.sort(Comparator.comparing(base -> base.id));
        return List.copyOf(out);
    }

    private static List<Unit> cleanUnits(World world, String playerId, List<Unit> requested,
                                         ShipLoadoutDefinition loadout) {
        if (requested == null || requested.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<Unit> out = new ArrayList<>();
        for (Unit unit : requested) {
            if (unit == null || unit.hp <= 0 || !playerId.equals(unit.playerId)
                    || !loadout.hullId().equals(unit.shipTypeId)
                    || loadout.id().equals(unit.loadoutId)
                    || ProductionSystem.refitReserved(world, unit.key())
                    || !seen.add(unit.key())) continue;
            out.add(unit);
        }
        return List.copyOf(out);
    }

    private static Base chooseStation(Unit unit, RefitQuote quote, Base preferredBase,
                                      List<Base> stations,
                                      Map<Base,EnumMap<Material,Double>> inventory,
                                      Map<Base,Double> availableAt, boolean free) {
        Base best = null;
        double bestFinish = Double.MAX_VALUE;
        for (Base station : stations) {
            if (!free && !HangarStore.canAfford(
                    inventory.get(station), quote.requiredMaterials())) continue;
            double finish = Math.max(availableAt.getOrDefault(station, 0.0),
                    travelSeconds(unit, station)) + quote.durationSeconds();
            if (station == preferredBase) finish -= 0.001;
            if (finish < bestFinish - 0.000001
                    || Math.abs(finish - bestFinish) <= 0.000001
                    && (best == null || station.id.compareTo(best.id) < 0)) {
                best = station;
                bestFinish = finish;
            }
        }
        return best;
    }

    private static double queueAvailableAt(World world, Base station) {
        double available = StationFuelRules.isOperational(station)
                ? 0 : OFFLINE_QUEUE_PENALTY_SECONDS;
        for (ProductionJob job : station.productionQueue) {
            if (ProductionSystem.waitingForResources(job)) {
                available += BLOCKED_QUEUE_PENALTY_SECONDS;
            }
            if (job.kind == ProductionJobKind.REFIT) {
                Unit subject = world.units.get(job.subjectUnitKey);
                if (subject != null && subject.hp > 0) {
                    available = Math.max(available, travelSeconds(subject, station));
                }
            }
            available += Math.max(0, job.remaining);
        }
        return available;
    }

    private static double travelSeconds(Unit unit, Base station) {
        double distance = Math.max(0, Calc.distance(unit.x, unit.y, station.x, station.y)
                - Math.max(0, station.type().refitRange * 0.55));
        return distance / Math.max(1, unit.type().speed);
    }

    private static void merge(EnumMap<Material,Double> total, List<Cost> costs) {
        for (Cost cost : costs) total.merge(cost.material(), cost.amount(), Double::sum);
    }

    private static List<Cost> costs(EnumMap<Material,Double> total) {
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material,Double> entry : total.entrySet()) {
            if (entry.getValue() > 0) out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    private static String distributionLabel(Map<Base,Integer> distribution) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<Base,Integer> entry : distribution.entrySet()) {
            labels.add(entry.getKey().type().name + " " + entry.getKey().id
                    + " ×" + entry.getValue());
        }
        return String.join(", ", labels);
    }

    record Result(boolean success, int queued, int stationsUsed, Base primaryBase,
                  Map<String,Integer> distribution, String message) {
        static Result ok(int queued, Map<Base,Integer> distribution,
                         Base primaryBase, String message) {
            Map<String,Integer> copy = new LinkedHashMap<>();
            for (Map.Entry<Base,Integer> entry : distribution.entrySet()) {
                copy.put(entry.getKey().id, entry.getValue());
            }
            return new Result(true, queued, distribution.size(), primaryBase,
                    Map.copyOf(copy), message);
        }

        static Result fail(String message) {
            return new Result(false, 0, 0, null, Map.of(),
                    message == null || message.isBlank()
                            ? "Refit queue request was rejected." : message);
        }
    }

    private record Plan(boolean success, String failure,
                        List<Assignment> assignments,
                        Map<Base,Integer> distribution,
                        Map<Base,List<Cost>> stationCosts) {
        static Plan ok(List<Assignment> assignments,
                       Map<Base,Integer> distribution,
                       Map<Base,List<Cost>> stationCosts) {
            return new Plan(true, "", List.copyOf(assignments),
                    Map.copyOf(distribution), Map.copyOf(stationCosts));
        }

        static Plan fail(String failure) {
            return new Plan(false,
                    failure == null ? "Refit request was rejected." : failure,
                    List.of(), Map.of(), Map.of());
        }
    }

    private record Assignment(Base station, Unit unit, RefitQuote quote) { }
    private record Committed(Assignment assignment, ProductionJob job) { }

    private record UnitState(UnitTask task, String attackTarget,
                             String logisticsTargetBaseId, String logisticsRequestId,
                             String orderTarget, UnitOrderType orderType,
                             double targetX, double targetY, double heading,
                             double orbitAngle, double orbitRetarget,
                             double weaponCooldown, double weaponFlashTimer,
                             double wormholeCooldown, double microJumpCooldown,
                             double microJumpFlashTimer,
                             double miningAnchorX, double miningAnchorY,
                             double orderX1, double orderY1,
                             double orderX2, double orderY2, double orderRadius,
                             int automationResourceId, int orderPhase,
                             boolean miningAnchorSet, boolean afterburnerActive) {
        static UnitState capture(Unit unit) {
            return new UnitState(unit.task, unit.attackTarget,
                    unit.logisticsTargetBaseId, unit.logisticsRequestId,
                    unit.orderTarget, unit.orderType,
                    unit.targetX, unit.targetY, unit.heading,
                    unit.orbitAngle, unit.orbitRetarget,
                    unit.weaponCooldown, unit.weaponFlashTimer,
                    unit.wormholeCooldown, unit.microJumpCooldown,
                    unit.microJumpFlashTimer,
                    unit.miningAnchorX, unit.miningAnchorY,
                    unit.orderX1, unit.orderY1, unit.orderX2, unit.orderY2,
                    unit.orderRadius, unit.automationResourceId, unit.orderPhase,
                    unit.miningAnchorSet, unit.afterburnerActive);
        }

        void restore(Unit unit) {
            unit.task = task;
            unit.attackTarget = attackTarget;
            unit.logisticsTargetBaseId = logisticsTargetBaseId;
            unit.logisticsRequestId = logisticsRequestId;
            unit.orderTarget = orderTarget;
            unit.orderType = orderType;
            unit.targetX = targetX;
            unit.targetY = targetY;
            unit.heading = heading;
            unit.orbitAngle = orbitAngle;
            unit.orbitRetarget = orbitRetarget;
            unit.weaponCooldown = weaponCooldown;
            unit.weaponFlashTimer = weaponFlashTimer;
            unit.wormholeCooldown = wormholeCooldown;
            unit.microJumpCooldown = microJumpCooldown;
            unit.microJumpFlashTimer = microJumpFlashTimer;
            unit.miningAnchorX = miningAnchorX;
            unit.miningAnchorY = miningAnchorY;
            unit.orderX1 = orderX1;
            unit.orderY1 = orderY1;
            unit.orderX2 = orderX2;
            unit.orderY2 = orderY2;
            unit.orderRadius = orderRadius;
            unit.automationResourceId = automationResourceId;
            unit.orderPhase = orderPhase;
            unit.miningAnchorSet = miningAnchorSet;
            unit.afterburnerActive = afterburnerActive;
        }
    }
}
