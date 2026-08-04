#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
def read(path): return (ROOT/path).read_text(encoding='utf-8')
def write(path,text): (ROOT/path).write_text(text,encoding='utf-8')
def replace_once(text,old,new,label):
    n=text.count(old)
    if n!=1: raise SystemExit(f'{label}: expected 1 match, found {n}')
    return text.replace(old,new,1)
def regex_once(text,pattern,repl,label):
    updated,n=re.subn(pattern,repl,text,count=1,flags=re.S)
    if n!=1: raise SystemExit(f'{label}: expected 1 regex match, found {n}')
    return updated

# Custom preview quotes must carry their not-yet-registered module layout.
quote=read('src/main/java/com/tndmadman/rts/RefitQuote.java')
quote=replace_once(quote,
'''    static RefitQuote between(Unit unit, ShipLoadoutDefinition destination) {
        if (unit == null || destination == null || !unit.shipTypeId.equals(destination.hullId())) {
''',
'''    static RefitQuote between(Unit unit, ShipLoadoutDefinition destination) {
        return between(unit, destination, ShipModuleRules.moduleIds(destination));
    }

    static RefitQuote between(Unit unit, ShipLoadoutDefinition destination, List<String> destinationModules) {
        if (unit == null || destination == null || !unit.shipTypeId.equals(destination.hullId())) {
''','RefitQuote overload')
quote=replace_once(quote,
'''        List<String> sourceModules = ShipModuleRules.moduleIds(source);
        List<String> targetModules = ShipModuleRules.moduleIds(destination);
''',
'''        List<String> sourceModules = ShipModuleRules.moduleIds(source);
        List<String> targetModules = destinationModules == null ? List.of() : List.copyOf(destinationModules);
''','RefitQuote destination modules')
write('src/main/java/com/tndmadman/rts/RefitQuote.java',quote)

# Replace the planner with immutable planning and all-or-nothing station commits.
write('src/main/java/com/tndmadman/rts/RefitQueuePlanner.java',r'''package com.tndmadman.rts;

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
        Plan plan = plan(world, playerId, requested, loadout, ShipModuleRules.moduleIds(loadout), free, preferredBase);
        return plan.success() ? resultFor(plan, loadout) : Result.fail(plan.failure());
    }

    static Result enqueue(World world, String playerId, List<Unit> requested,
                          ShipLoadoutDefinition loadout, boolean free, Base preferredBase) {
        Plan plan = plan(world, playerId, requested, loadout, ShipModuleRules.moduleIds(loadout), free, preferredBase);
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

    static void failAfterInsertionsForTest(int value) { failAfterInsertionsForTest = value; }

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

        List<Committed> committed = new ArrayList<>();
        try {
            if (!free) for (Map.Entry<Base,List<Cost>> entry : plan.stationCosts().entrySet()) {
                HangarStore.spend(entry.getKey().inventory, entry.getValue());
            }
            int inserted = 0;
            for (Assignment assignment : plan.assignments()) {
                ProductionJob job = ProductionSystem.enqueueRefitPrepaid(assignment.station(), assignment.unit(),
                        preview, assignment.quote(), !free);
                if (job == null) throw new IllegalStateException("Could not insert a planned refit job.");
                committed.add(new Committed(assignment, job));
                inserted++;
                if (failAfterInsertionsForTest > 0 && inserted >= failAfterInsertionsForTest) {
                    throw new IllegalStateException("Injected refit transaction failure after job " + inserted + ".");
                }
            }

            ShipLoadoutDefinition installed = customSpec == null
                    ? preview : WorldFitCatalog.registerRuntime(world, customName, customSpec);
            if (!installed.id().equals(preview.id())) {
                throw new IllegalStateException("Runtime fit registration changed the planned fit ID.");
            }
            for (Committed value : committed) {
                ProductionSystem.beginRefit(world, value.assignment().station(), value.assignment().unit(), value.job());
            }
            for (Base station : plan.stationCosts().keySet()) {
                ProductionSystem.processBaseAfterTransaction(world, station);
            }
            Result result = resultFor(plan, installed);
            world.status = result.message();
            AlertCenter.push(world, result.message());
            return result;
        } catch (RuntimeException ex) {
            for (Map.Entry<Base,Integer> entry : queueSizeBefore.entrySet()) {
                Base station = entry.getKey();
                while (station.productionQueue.size() > entry.getValue()) {
                    station.productionQueue.remove(station.productionQueue.size() - 1);
                }
                station.nextProductionJobId = nextJobBefore.get(station);
                station.inventory.clear();
                station.inventory.putAll(inventoryBefore.get(station));
            }
            return Result.fail("Refit transaction was rolled back: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        } finally {
            failAfterInsertionsForTest = -1;
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
            try { quote = RefitQuote.between(unit, loadout, destinationModules); }
            catch (IllegalArgumentException ex) { return Plan.fail(ex.getMessage()); }
            Base station = chooseStation(unit, quote, preferredBase, stations, simulatedInventory, availableAt, free);
            if (station == null) {
                return Plan.fail("No refit-capable station can fund every requested conversion. Required additions vary by source fit.");
            }
            if (!free) {
                HangarStore.spend(simulatedInventory.get(station), quote.requiredMaterials());
                merge(stationCostTotals.get(station), quote.requiredMaterials());
            }
            double ready = Math.max(availableAt.getOrDefault(station, 0.0), travelSeconds(unit, station));
            availableAt.put(station, ready + quote.durationSeconds());
            distribution.merge(station, 1, Integer::sum);
            assignments.add(new Assignment(station, unit, quote));
        }
        Map<Base,List<Cost>> stationCosts = new LinkedHashMap<>();
        for (Map.Entry<Base,EnumMap<Material,Double>> entry : stationCostTotals.entrySet()) {
            if (distribution.containsKey(entry.getKey())) stationCosts.put(entry.getKey(), costs(entry.getValue()));
        }
        return Plan.ok(assignments, distribution, stationCosts);
    }

    private static Result resultFor(Plan plan, ShipLoadoutDefinition loadout) {
        String message = "Queued " + plan.assignments().size() + " " + Rules.ship(loadout.hullId()).name
                + " refit" + (plan.assignments().size() == 1 ? "" : "s") + " atomically across "
                + plan.distribution().size() + " station" + (plan.distribution().size() == 1 ? "" : "s") + ": "
                + distributionLabel(plan.distribution()) + ".";
        return Result.ok(plan.assignments().size(), plan.distribution(), plan.assignments().get(0).station(), message);
    }

    static Base bestStation(World world, Unit unit, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || unit == null || loadout == null) return null;
        RefitQuote quote;
        try { quote = RefitQuote.between(unit, loadout); }
        catch (RuntimeException ex) { return null; }
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
            if (base != null && base.hp > 0 && playerId.equals(base.playerId) && base.type().canRefitShips) out.add(base);
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
                    || ProductionSystem.refitReserved(world, unit.key()) || !seen.add(unit.key())) continue;
            out.add(unit);
        }
        return List.copyOf(out);
    }

    private static Base chooseStation(Unit unit, RefitQuote quote, Base preferredBase,
                                      List<Base> stations, Map<Base,EnumMap<Material,Double>> inventory,
                                      Map<Base,Double> availableAt, boolean free) {
        Base best = null;
        double bestFinish = Double.MAX_VALUE;
        for (Base station : stations) {
            if (!free && !HangarStore.canAfford(inventory.get(station), quote.requiredMaterials())) continue;
            double finish = Math.max(availableAt.getOrDefault(station, 0.0), travelSeconds(unit, station))
                    + quote.durationSeconds();
            if (station == preferredBase) finish -= 0.001;
            if (finish < bestFinish - 0.000001 || Math.abs(finish - bestFinish) <= 0.000001
                    && (best == null || station.id.compareTo(best.id) < 0)) {
                best = station;
                bestFinish = finish;
            }
        }
        return best;
    }

    private static double queueAvailableAt(World world, Base station) {
        double available = StationFuelRules.isOperational(station) ? 0 : OFFLINE_QUEUE_PENALTY_SECONDS;
        for (ProductionJob job : station.productionQueue) {
            if (ProductionSystem.waitingForResources(job)) available += BLOCKED_QUEUE_PENALTY_SECONDS;
            if (job.kind == ProductionJobKind.REFIT) {
                Unit subject = world.units.get(job.subjectUnitKey);
                if (subject != null && subject.hp > 0) available = Math.max(available, travelSeconds(subject, station));
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
        for (Map.Entry<Material,Double> entry : total.entrySet()) if (entry.getValue() > 0) {
            out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    private static String distributionLabel(Map<Base,Integer> distribution) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<Base,Integer> entry : distribution.entrySet()) {
            labels.add(entry.getKey().type().name + " " + entry.getKey().id + " ×" + entry.getValue());
        }
        return String.join(", ", labels);
    }

    record Result(boolean success, int queued, int stationsUsed, Base primaryBase,
                  Map<String,Integer> distribution, String message) {
        static Result ok(int queued, Map<Base,Integer> distribution, Base primaryBase, String message) {
            Map<String,Integer> copy = new LinkedHashMap<>();
            for (Map.Entry<Base,Integer> entry : distribution.entrySet()) copy.put(entry.getKey().id, entry.getValue());
            return new Result(true, queued, distribution.size(), primaryBase, Map.copyOf(copy), message);
        }
        static Result fail(String message) {
            return new Result(false, 0, 0, null, Map.of(),
                    message == null || message.isBlank() ? "Refit queue request was rejected." : message);
        }
    }

    private record Plan(boolean success, String failure, List<Assignment> assignments,
                        Map<Base,Integer> distribution, Map<Base,List<Cost>> stationCosts) {
        static Plan ok(List<Assignment> assignments, Map<Base,Integer> distribution,
                       Map<Base,List<Cost>> stationCosts) {
            return new Plan(true, "", List.copyOf(assignments), Map.copyOf(distribution), Map.copyOf(stationCosts));
        }
        static Plan fail(String failure) {
            return new Plan(false, failure == null ? "Refit request was rejected." : failure,
                    List.of(), Map.of(), Map.of());
        }
    }

    private record Assignment(Base station, Unit unit, RefitQuote quote) { }
    private record Committed(Assignment assignment, ProductionJob job) { }
}
''')

# Route custom refits through the transactional planner so catalog registration is part of commit.
fit=read('src/main/java/com/tndmadman/rts/FitCommand.java')
fit=regex_once(fit,
 r'''    private static Result refit\(World world, String actorId, Map<String,Object> payload\) \{.*?\n    \}\n\n    private static Result refitClass''',
'''    private static Result refit(World world, String actorId, Map<String,Object> payload) {
        Base preferred = ownedBase(world, actorId, ServerSaveStore.string(payload, "baseId", ""));
        String unitKey = ServerSaveStore.string(payload, "unitKey", "");
        Unit unit = world.units.get(unitKey);
        if (unit == null || !actorId.equals(unit.playerId)) return Result.fail("Selected ship was not found.");
        Candidate candidate = candidate(payload);
        RefitQueuePlanner.Result queued = RefitQueuePlanner.enqueueCustom(world, actorId, List.of(unit),
                candidate.name(), candidate.spec(), world.devFreeBuildFor(actorId), preferred);
        return queued.success() ? Result.ok(queued.message(), true, true) : Result.fail(queued.message());
    }

    private static Result refitClass''','FitCommand single refit')
fit=regex_once(fit,
 r'''    private static Result refitClass\(World world, String actorId, Map<String,Object> payload\) \{.*?\n    \}\n\n    private static Result build''',
'''    private static Result refitClass(World world, String actorId, Map<String,Object> payload) {
        Base preferred = ownedBase(world, actorId, ServerSaveStore.string(payload, "baseId", ""));
        Candidate candidate = candidate(payload);
        ShipLoadoutDefinition preview = candidate.definition();
        List<Unit> eligible = new ArrayList<>();
        int already = 0, reserved = 0;
        for (Unit unit : world.units.values()) {
            if (!actorId.equals(unit.playerId) || unit.hp <= 0 || !preview.hullId().equals(unit.shipTypeId)) continue;
            if (preview.id().equals(unit.loadoutId)) { already++; continue; }
            if (ProductionSystem.refitReserved(world, unit.key())) { reserved++; continue; }
            eligible.add(unit);
        }
        if (eligible.isEmpty()) return Result.fail("No available " + Rules.ship(preview.hullId()).name
                + " ships can be recalled. Already fitted: " + already + "; already reserved: " + reserved + ".");
        RefitQueuePlanner.Result queued = RefitQueuePlanner.enqueueCustom(world, actorId, eligible,
                candidate.name(), candidate.spec(), world.devFreeBuildFor(actorId), preferred);
        if (!queued.success()) return Result.fail(queued.message());
        world.status = queued.message() + " Already fitted: " + already + "; already reserved: " + reserved + ".";
        return Result.ok(world.status, true, true);
    }

    private static Result build''','FitCommand class refit')
write('src/main/java/com/tndmadman/rts/FitCommand.java',fit)

write('src/main/java/com/tndmadman/rts/AtomicRefitTransactionValidator.java',r'''package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AtomicRefitTransactionValidator {
    private AtomicRefitTransactionValidator() { }

    public static void main(String[] args) {
        String player = "ATOMIC_REFIT";
        PlayerRegistry.reset(player, "Atomic Refit", 0x55CCFF);
        World world = new World("Atomic Refit", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.completeResearch(player, "advanced_industry");

        Base outpost = new Base(player + ":B1", player, "outpost", 700, 700);
        Base shipyard = new Base(player + ":B2", player, "shipyard", 3600, 700);
        world.bases.put(outpost.id, outpost);
        world.bases.put(shipyard.id, shipyard);
        for (Base base : List.of(outpost, shipyard)) for (Material material : Material.values()) {
            base.inventory.put(material, 1000.0);
        }

        List<Unit> ships = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double x = i < 2 ? 760 + i * 60 : 3540 + i * 30;
            Unit unit = new Unit(player, i + 1, "prospector", x, 700);
            unit.loadoutId = WeaponRules.defaultLoadoutId("prospector");
            unit.task = UnitTask.ATTACK;
            unit.attackTarget = "B:test-target";
            unit.targetX = x + 500;
            unit.targetY = 900;
            world.units.put(unit.key(), unit);
            ships.add(unit);
        }
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("afterburner"));
        String runtimeId = spec.runtimeId();

        Map<Base,EnumMap<Material,Double>> beforeInventory = Map.of(
                outpost, new EnumMap<>(outpost.inventory), shipyard, new EnumMap<>(shipyard.inventory));
        List<State> beforeState = ships.stream().map(State::capture).toList();
        long revision = WorldFitCatalog.revision(world);
        RefitQueuePlanner.failAfterInsertionsForTest(1);
        RefitQueuePlanner.Result failed = RefitQueuePlanner.enqueueCustom(world, player, ships,
                "Atomic Afterburner", spec, false, outpost);
        require(!failed.success(), "injected transaction failure was accepted");
        require(outpost.productionQueue.isEmpty() && shipyard.productionQueue.isEmpty(),
                "failed transaction left queue jobs behind");
        require(WorldFitCatalog.revision(world) == revision && !catalogContains(world, runtimeId),
                "failed transaction polluted the runtime catalog");
        for (Base base : List.of(outpost, shipyard)) {
            require(base.inventory.equals(beforeInventory.get(base)),
                    "failed transaction changed inventory at " + base.id);
        }
        for (int i = 0; i < ships.size(); i++) beforeState.get(i).requireSame(ships.get(i));

        RefitQueuePlanner.Result success = RefitQueuePlanner.enqueueCustom(world, player, ships,
                "Atomic Afterburner", spec, false, outpost);
        require(success.success() && success.queued() == ships.size(), "atomic class refit did not queue every ship");
        require(success.stationsUsed() == 2, "atomic planner did not distribute nearby ships across both stations");
        require(catalogContains(world, runtimeId), "successful transaction did not register runtime fit");
        int queued = outpost.productionQueue.size() + shipyard.productionQueue.size();
        require(queued == ships.size(), "successful transaction queued an incorrect job count");
        for (Base base : List.of(outpost, shipyard)) {
            EnumMap<Material,Double> reserved = new EnumMap<>(Material.class);
            for (ProductionJob job : base.productionQueue) {
                require(job.refitQuoteVersion == RefitQuote.CURRENT_VERSION, "job lost quote version");
                for (Cost cost : job.reservedCost) reserved.merge(cost.material(), cost.amount(), Double::sum);
            }
            for (Material material : Material.values()) {
                double expected = beforeInventory.get(base).getOrDefault(material, 0.0)
                        - reserved.getOrDefault(material, 0.0);
                require(close(base.inventory.getOrDefault(material, 0.0), expected),
                        "station aggregate reservation is wrong for " + material + " at " + base.id);
            }
        }

        for (Base base : List.of(outpost, shipyard)) {
            for (ProductionJob job : List.copyOf(base.productionQueue)) {
                require(ProductionSystem.cancel(world, player, base.id, job.id), "could not cancel atomic refit");
            }
            require(base.inventory.equals(beforeInventory.get(base)),
                    "atomic cancellation did not restore exact inventory at " + base.id);
        }
        System.out.println("StarChem atomic distributed refit transaction validation passed.");
    }

    private static boolean catalogContains(World world, String id) {
        for (Object item : ServerSaveStore.list(WorldFitCatalog.networkView(world).get("definitions"))) {
            if (id.equals(ServerSaveStore.string(ServerSaveStore.object(item), "id", ""))) return true;
        }
        return false;
    }

    private static boolean close(double left, double right) { return Math.abs(left - right) < 0.000001; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record State(UnitTask task, String attackTarget, double targetX, double targetY,
                         int automationResourceId, UnitOrderType orderType, boolean afterburnerActive) {
        static State capture(Unit unit) {
            return new State(unit.task, unit.attackTarget, unit.targetX, unit.targetY,
                    unit.automationResourceId, unit.orderType, unit.afterburnerActive);
        }
        void requireSame(Unit unit) {
            require(unit.task == task && attackTarget.equals(unit.attackTarget)
                            && close(unit.targetX, targetX) && close(unit.targetY, targetY)
                            && unit.automationResourceId == automationResourceId
                            && unit.orderType == orderType && unit.afterburnerActive == afterburnerActive,
                    "failed transaction changed ship state for " + unit.key());
        }
    }
}
''')
print('Applied issue #289 phase 2B atomic refit transactions.')
