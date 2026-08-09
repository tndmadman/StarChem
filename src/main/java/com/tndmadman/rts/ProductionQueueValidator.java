package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class ProductionQueueValidator {
    private ProductionQueueValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem production queue validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("SOLO", "Queue Validator", 0x50BEFF);
        World world = new World("Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "QUEUE_TEST";

        Base yard = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        fill(yard);
        double ironBefore = yard.inventory.get(Material.IRON);
        require(world.buildShip(yard.id, "prospector"), "ship should enqueue");
        require(yard.productionQueue.size() == 1, "ship queue missing");
        require(world.units.isEmpty(), "timed ship completed immediately");
        require(yard.inventory.get(Material.IRON) < ironBefore, "ship resources were not reserved");
        ProductionJob cancelled = yard.productionQueue.get(0);
        ProductionSystem.update(world, cancelled.duration / 2.0);
        require(cancelled.remaining > 0 && cancelled.remaining < cancelled.duration, "ship progress did not advance");
        require(ProductionSystem.cancel(world, playerId, yard.id, cancelled.id), "active cancellation failed");
        require(close(yard.inventory.get(Material.IRON), ironBefore), "active cancellation did not refund resources");

        require(world.buildShip(yard.id, "prospector"), "first queue entry failed");
        require(world.buildShip(yard.id, "prospector"), "second queue entry failed");
        require(world.buildShip(yard.id, "prospector"), "third queue entry failed");
        String thirdId = yard.productionQueue.get(2).id;
        require(ProductionSystem.move(world, playerId, yard.id, thirdId, -1), "waiting job reorder failed");
        require(yard.productionQueue.get(1).id.equals(thirdId), "waiting job did not move to the requested position");
        ProductionSystem.update(world, 1000);
        require(yard.productionQueue.isEmpty(), "ship queue did not drain");
        require(countUnits(world, playerId, "prospector") == 3, "queued ships were not produced");

        Base outpost = base(world, playerId + ":B2", playerId, "outpost", 300, 300);
        fill(outpost);
        Unit deployer = new Unit(playerId, 99, "station_builder", 310, 300);
        world.units.put(deployer.key(), deployer);
        require(world.loadBasePackage(outpost.id, "shipyard"), "station package should enqueue");
        require(outpost.productionQueue.size() == 1, "station package queue missing");
        require(outpost.productionQueue.get(0).reservedUnitKey.equals(deployer.key()), "Deployer was not reserved");
        ProductionSystem.update(world, 1000);
        require("shipyard".equals(deployer.basePackageType), "completed package was not loaded into its Deployer");

        Base lab = base(world, playerId + ":B3", playerId, "laboratory", 500, 500);
        fill(lab);
        require(world.research(lab.id, "advanced_industry"), "first research should enqueue");
        ProductionJob research = lab.productionQueue.get(0);
        double researchRemaining = research.remaining;
        lab.inventory.remove(Material.FUEL);
        ProductionSystem.update(world, 10);
        require(close(research.remaining, researchRemaining), "research advanced without station fuel");
        HangarStore.add(lab.inventory, Material.FUEL, 100);
        ProductionSystem.update(world, 10);
        require(research.remaining < researchRemaining, "research did not resume after refueling");
        require(world.research(lab.id, "combat_doctrine"), "research prerequisite chain should enqueue");
        require(lab.productionQueue.size() == 2, "research chain queue missing");
        require(!ProductionSystem.cancel(world, playerId, lab.id, lab.productionQueue.get(0).id),
                "prerequisite research cancellation should be rejected while dependents remain");
        require(lab.productionQueue.size() == 2, "rejected prerequisite cancellation changed the queue");

        BaseState state = NetBaseSync.toState(lab);
        Base restored = NetBaseSync.fromState(state);
        require(restored.productionQueue.size() == 2, "network state lost queued jobs");
        require(restored.productionQueue.get(0).itemId.equals("advanced_industry"), "network state changed queue order");

        ProductionSystem.update(world, 1000);
        require(world.hasResearch(playerId, "advanced_industry"), "first research did not complete");
        require(world.hasResearch(playerId, "combat_doctrine"), "chained research did not complete");

        validateLogisticsQueuePersistence();
        validateLogisticsEscrow();
        validateLostShuttleRecovery();
        validateMultiSystemRequestScope();
        validateDisabledTimers();
        validateAutoProductionQueueVisibility();
        validateAutoProductionAllocation();
        validateAutoProductionRecipeSelection();
        validateMalformedQueueRejection();
        Issue294ProductionPolicyValidator.validateOrThrow();
    }

    private static void validateLogisticsQueuePersistence() {
        World world = new World("Logistics Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "LOGISTICS_QUEUE_TEST";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Base source = base(world, playerId + ":B2", playerId, "shipyard", 600, 600);
        fill(target);
        fill(source);

        require(world.buildShip(target.id, "prospector"), "funded lead ship should enqueue");
        target.inventory.clear();
        require(world.buildShip(target.id, "prospector"), "first logistics-backed ship should enqueue");
        require(world.buildShip(target.id, "prospector"), "duplicate logistics-backed ship should enqueue separately");
        require(target.productionQueue.size() == 3, "logistics-backed jobs did not enter the real queue");

        List<String> orderBefore = jobIds(target);
        require(!ProductionSystem.waitingForResources(target.productionQueue.get(0)), "funded lead ship became resource-blocked");
        require(ProductionSystem.waitingForResources(target.productionQueue.get(1)), "first logistics job is not resource-blocked");
        require(ProductionSystem.waitingForResources(target.productionQueue.get(2)), "duplicate logistics job was collapsed");

        Base restored = NetBaseSync.fromState(NetBaseSync.toState(target));
        require(jobIds(restored).equals(orderBefore), "snapshot changed logistics-backed queue order");
        require(ProductionSystem.waitingForResources(restored.productionQueue.get(1)), "snapshot lost waiting-resource state");

        dockAllLogisticsShuttles(world, target);
        world.logisticsSystem.update(world, 2.1);

        require(target.productionQueue.size() == 3, "resource delivery replaced or removed queued jobs");
        require(jobIds(target).equals(orderBefore), "resource delivery changed queue IDs or order");
        require(target.productionQueue.get(1).resourcesReserved, "first delivered job was not funded in place");
        require(target.productionQueue.get(2).resourcesReserved, "duplicate delivered job was not funded in place");
        require(!ProductionSystem.waitingForResources(target.productionQueue.get(1)), "first delivered job remained blocked");
        require(!ProductionSystem.waitingForResources(target.productionQueue.get(2)), "duplicate delivered job remained blocked");

        ProductionSystem.update(world, 1000);
        require(target.productionQueue.isEmpty(), "funded logistics queue did not drain");
        require(countUnits(world, playerId, "prospector") == 3, "logistics queue did not produce every requested ship");
    }

    private static void validateLogisticsEscrow() {
        World world = new World("Logistics Escrow Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "LOGISTICS_ESCROW_TEST";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Base source = base(world, playerId + ":B2", playerId, "shipyard", 700, 700);
        fill(source);
        ShipType ship = Rules.ship("prospector");
        for (Cost cost : ship.buildCost) target.inventory.put(cost.material(), cost.amount() / 2.0);

        require(world.buildShip(target.id, ship.id), "first partially funded logistics job should enqueue");
        require(world.buildShip(target.id, ship.id), "second logistics job should account for first job escrow");
        require(target.productionQueue.size() == 2, "escrow logistics jobs missing");
        for (Cost cost : ship.buildCost) {
            require(target.inventory.getOrDefault(cost.material(), 0.0) <= 0.05,
                    "target inventory was not reserved into the first job escrow");
        }

        dockAllLogisticsShuttles(world, target);
        world.logisticsSystem.update(world, 2.1);
        require(target.productionQueue.get(0).resourcesReserved, "first escrow-backed job was not funded");
        require(target.productionQueue.get(1).resourcesReserved, "second job double-counted shared target inventory");
    }

    private static void validateLostShuttleRecovery() {
        World world = new World("Lost Logistics Shuttle Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "LOGISTICS_RECOVERY_TEST";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Base source = base(world, playerId + ":B2", playerId, "shipyard", 800, 800);
        fill(source);

        require(world.buildShip(target.id, "prospector"), "recovery logistics job should enqueue");
        int beforeLoss = countLogisticsShuttles(world);
        require(beforeLoss > 0, "recovery test launched no logistics shuttles");
        removeOneLogisticsShuttle(world);
        require(countLogisticsShuttles(world) == beforeLoss - 1, "recovery test did not remove a shuttle");

        world.logisticsSystem.update(world, 2.1);
        require(countLogisticsShuttles(world) >= beforeLoss,
                "missing in-transit cargo was not requested again after shuttle loss");
        dockAllLogisticsShuttles(world, target);
        world.logisticsSystem.update(world, 2.1);
        require(target.productionQueue.get(0).resourcesReserved,
                "job remained short after replacement logistics shuttles arrived");
    }

    private static void validateMultiSystemRequestScope() {
        World world = new World("Multi-System Logistics Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "LOGISTICS_SYSTEM_TEST";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Base source = base(world, playerId + ":B2", playerId, "shipyard", 750, 750);
        fill(source);
        String targetSystem = world.activeSystemId();

        require(world.buildShip(target.id, "prospector"), "multi-system logistics job should enqueue");
        require(ProductionSystem.waitingForResources(target.productionQueue.get(0)), "multi-system job was not waiting");
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.logisticsSystem.update(world, 2.1);
        world.activateSystem(targetSystem);

        require(target.productionQueue.size() == 1, "updating another system deleted the production job");
        require(ProductionSystem.waitingForResources(target.productionQueue.get(0)),
                "updating another system deleted or detached its logistics request");
        dockAllLogisticsShuttles(world, target);
        world.logisticsSystem.update(world, 2.1);
        require(target.productionQueue.get(0).resourcesReserved,
                "system-scoped logistics request did not finish after returning to its system");
    }

    private static void validateDisabledTimers() {
        World world = new World("Instant Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "INSTANT_TEST";
        Base yard = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        fill(yard);

        DevTimerSettings.configure(world, true);
        require(world.buildShip(yard.id, "prospector"), "instant ship should enqueue");
        require(yard.productionQueue.size() == 1, "instant ship queue missing before tick");
        ResearchSystem.update(world, 0.016);
        require(yard.productionQueue.isEmpty(), "disabled timers did not drain production");
        require(countUnits(world, playerId, "prospector") == 1, "disabled timers did not produce ship");

        DevTimerSettings.configure(world, false);
        require(world.buildShip(yard.id, "prospector"), "re-enabled timer ship should enqueue");
        ResearchSystem.update(world, 0.016);
        require(yard.productionQueue.size() == 1, "re-enabled production timer completed immediately");
        require(countUnits(world, playerId, "prospector") == 1, "re-enabled timer unexpectedly produced a ship");
    }

    private static void validateAutoProductionQueueVisibility() {
        World world = new World("Auto-Production Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "AUTO_QUEUE_TEST";
        Base outpost = base(world, playerId + ":B1", playerId, "outpost", 100, 100);

        require(world.buildShip(outpost.id, "prospector"),
                "auto-production ship request should be accepted");
        require(world.loadBasePackage(outpost.id, "manufacturing"),
                "auto-production station request should be accepted");
        require(ProductionPlanner.planCount(world) == 2,
                "auto-production plans were not retained while prerequisites were missing");
        require(outpost.productionQueue.size() == 2,
                "auto-production roots did not appear in the real Outpost queue");
        require(outpost.productionQueue.get(0).kind == ProductionJobKind.SHIP,
                "planned ship did not retain its queue kind");
        require(outpost.productionQueue.get(1).kind == ProductionJobKind.STATION_PACKAGE,
                "planned station did not retain its queue kind");
        require(ProductionSystem.waitingForResources(outpost.productionQueue.get(0)),
                "planned ship was not visibly waiting for prerequisites");
        require(ProductionSystem.waitingForResources(outpost.productionQueue.get(1)),
                "planned station was not visibly waiting for prerequisites");

        List<String> plannedIds = jobIds(outpost);
        Base restored = NetBaseSync.fromState(NetBaseSync.toState(outpost));
        require(jobIds(restored).equals(plannedIds),
                "network state lost auto-production root queue entries");

        fill(outpost);
        ProductionPlanner.update(world, 1.0);
        require(ProductionPlanner.planCount(world) == 0,
                "ready auto-production plans were not converted into funded queue jobs");
        require(jobIds(outpost).equals(plannedIds),
                "locally funded auto-production changed queue identity or order");
        require(outpost.productionQueue.get(0).resourcesReserved,
                "planned ship was not funded in place");
        require(outpost.productionQueue.get(1).resourcesReserved,
                "planned station was not funded in place");
        require(!ProductionSystem.waitingForResources(outpost.productionQueue.get(0)),
                "funded ship remained marked as waiting");
        require(!ProductionSystem.waitingForResources(outpost.productionQueue.get(1)),
                "funded station remained marked as waiting");
    }

    private static void validateAutoProductionAllocation() {
        World world = new World("Auto-Production Allocation Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "AUTO_ALLOCATION_TEST";
        world.completeResearch(playerId, "advanced_industry");

        List<Base> yards = new ArrayList<>();
        List<Base> plants = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            yards.add(base(world, playerId + ":Y" + (i + 1), playerId, "shipyard",
                    100 + i * 180, 100));
            Base plant = base(world, playerId + ":M" + (i + 1), playerId, "manufacturing",
                    100 + i * 180, 400);
            plant.inventory.put(Material.STEEL_PLATE, 12.0);
            plant.inventory.put(Material.NICKEL_STEEL, 6.0);
            plant.inventory.put(Material.FUEL, 1_000.0);
            plants.add(plant);
        }

        Base stock = plants.get(0);
        stock.inventory.put(Material.CARGO_POD, 18.0);
        stock.inventory.put(Material.LOGISTICS_CONTROL_MODULE, 3.0);
        stock.inventory.put(Material.FUEL_CELL_STACK, 6.0);
        stock.inventory.put(Material.CARBON, 60.0);
        stock.inventory.put(Material.POINT_DEFENSE_LASER_ASSEMBLY, 3.0);

        for (Base yard : yards) {
            require(world.buildShip(yard.id, "hauler"),
                    "hauler request should create an auto-production plan");
        }
        require(ProductionPlanner.planCount(world) == 3,
                "competing ship requests did not create three plans");

        ProductionPlanner.update(world, 1.0);
        Set<String> plantsUsed = new HashSet<>();
        int structuralFrameJobs = countCraftableJobs(plants, "structural_frame", plantsUsed);
        require(structuralFrameJobs == 3,
                "three haulers sharing 24 Structural Frames did not queue three recipe batches");
        require(plantsUsed.size() == 3,
                "auto-production did not distribute prerequisite batches across idle plants");

        ProductionPlanner.update(world, 1.0);
        require(countCraftableJobs(plants, "structural_frame", new HashSet<>()) == 3,
                "rechecking allocated future output queued duplicate Structural Frame batches");
    }

    private static void validateAutoProductionRecipeSelection() {
        World salvageWorld = new World("Alternate Recipe Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String salvagePlayer = "ALTERNATE_RECIPE_TEST";
        Base salvagePlant = base(salvageWorld, salvagePlayer + ":M1", salvagePlayer, "manufacturing", 100, 100);
        salvagePlant.inventory.put(Material.NICKEL_STEEL, 6.0);
        salvagePlant.inventory.put(Material.SCRAP_METAL, 18.0);
        salvagePlant.inventory.put(Material.INDUSTRIAL_LUBRICANT, 2.0);
        salvagePlant.inventory.put(Material.FUEL, 1_000.0);

        require(salvageWorld.craftItem(salvagePlant.id, "structural_frame"),
                "salvage-backed Structural Frame request should create a plan");
        ProductionPlanner.update(salvageWorld, 1.0);
        require(hasCraftableJob(salvagePlant, "reclaim_steel_plate"),
                "planner ignored the viable reclaimed Steel Plate recipe");
        require(!hasCraftableJob(salvagePlant, "steel_plate"),
                "planner selected the unavailable standard Steel Plate recipe");

        World deterministicWorld = new World("Recipe Preference Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String deterministicPlayer = "RECIPE_PREFERENCE_TEST";
        Base deterministicPlant = base(deterministicWorld, deterministicPlayer + ":M1", deterministicPlayer,
                "manufacturing", 100, 100);
        deterministicPlant.inventory.put(Material.NICKEL_STEEL, 6.0);
        deterministicPlant.inventory.put(Material.IRON, 30.0);
        deterministicPlant.inventory.put(Material.CARBON, 4.0);
        deterministicPlant.inventory.put(Material.SCRAP_METAL, 18.0);
        deterministicPlant.inventory.put(Material.INDUSTRIAL_LUBRICANT, 2.0);
        deterministicPlant.inventory.put(Material.FUEL, 1_000.0);

        require(deterministicWorld.craftItem(deterministicPlant.id, "structural_frame"),
                "fully funded alternate-recipe request should create a plan");
        ProductionPlanner.update(deterministicWorld, 1.0);
        require(hasCraftableJob(deterministicPlant, "steel_plate"),
                "equally viable recipes did not preserve deterministic configuration order");
        require(!hasCraftableJob(deterministicPlant, "reclaim_steel_plate"),
                "deterministic selection unexpectedly preferred the later reclamation recipe");

        World multiBatchWorld = new World("Multi-Batch Recipe Validator", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        String multiBatchPlayer = "MULTI_BATCH_RECIPE_TEST";
        Base packageOutpost = base(multiBatchWorld, multiBatchPlayer + ":B1", multiBatchPlayer,
                "outpost", 100, 100);
        Base multiBatchPlant = base(multiBatchWorld, multiBatchPlayer + ":M1", multiBatchPlayer,
                "manufacturing", 300, 100);
        packageOutpost.inventory.put(Material.STRUCTURAL_FRAME, 20.0);
        packageOutpost.inventory.put(Material.POWER_REGULATOR, 8.0);
        packageOutpost.inventory.put(Material.CARGO_POD, 4.0);
        packageOutpost.inventory.put(Material.ICE, 80.0);
        multiBatchPlant.inventory.put(Material.IRON, 30.0);
        multiBatchPlant.inventory.put(Material.CARBON, 4.0);
        multiBatchPlant.inventory.put(Material.SCRAP_METAL, 54.0);
        multiBatchPlant.inventory.put(Material.METHANE, 12.0);
        multiBatchPlant.inventory.put(Material.SULFUR, 6.0);
        multiBatchPlant.inventory.put(Material.FUEL, 1_000.0);

        require(multiBatchWorld.loadBasePackage(packageOutpost.id, "shipyard"),
                "multi-batch Shipyard request should create a plan");
        ProductionPlanner.update(multiBatchWorld, 1.0);
        require(hasCraftableJob(multiBatchPlant, "industrial_lubricant"),
                "planner did not select the achievable multi-batch reclamation route");
        require(!hasCraftableJob(multiBatchPlant, "steel_plate"),
                "planner preferred a one-batch-fundable recipe that could not complete the request");

        ProductionPlanner.update(multiBatchWorld, 1.0);
        require(countCraftableJobs(List.of(multiBatchPlant), "industrial_lubricant", new HashSet<>()) == 1,
                "rechecking the multi-batch route duplicated its prerequisite job");

        ProductionSystem.update(multiBatchWorld, 1_000.0);
        ProductionPlanner.update(multiBatchWorld, 1.0);
        require(hasCraftableJob(multiBatchPlant, "reclaim_steel_plate"),
                "planner did not queue the selected reclaimed Steel Plate recipe");
        require(!hasCraftableJob(multiBatchPlant, "steel_plate"),
                "planner switched back to the blocked standard Steel Plate recipe");

        ProductionPlanner.update(multiBatchWorld, 1.0);
        require(countCraftableJobs(List.of(multiBatchPlant), "reclaim_steel_plate", new HashSet<>()) == 1,
                "rechecking the selected multi-batch recipe duplicated its job");
    }

    private static boolean hasCraftableJob(Base base, String itemId) {
        for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.CRAFTABLE && job.itemId.equals(itemId)) return true;
        }
        return false;
    }

    private static int countCraftableJobs(List<Base> bases, String itemId, Set<String> usedBaseIds) {
        int count = 0;
        for (Base base : bases) {
            for (ProductionJob job : base.productionQueue) {
                if (job.kind != ProductionJobKind.CRAFTABLE || !job.itemId.equals(itemId)) continue;
                count++;
                usedBaseIds.add(base.id);
            }
        }
        return count;
    }

    private static void validateMalformedQueueRejection() {
        String valid = "P7^SHIP^prospector^12.0^6.0^1^-^-";
        DecodedProductionQueue legacy = StrictProductionQueueCodec.decode(
                "P1^SHIP^prospector^10.0^5.0^1^-", "QUEUE_VALIDATION", "LEGACY:B1");
        require(legacy.jobs().size() == 1, "legacy seven-column queue row was rejected");
        require(legacy.nextProductionJobId() == 2, "legacy queue did not restore the next job ID");

        DecodedProductionQueue current = StrictProductionQueueCodec.decode(valid,
                "QUEUE_VALIDATION", "CURRENT:B1");
        require(current.jobs().size() == 1, "current eight-column queue row was rejected");
        require(current.nextProductionJobId() == 8, "current queue did not restore the next job ID");

        expectQueueRejected("P8^SHIP", "expected 7 or 8 columns");
        expectQueueRejected("P8^UNKNOWN^prospector^10^5^1^-^-", "unknown job kind");
        expectQueueRejected("P8^SHIP^prospector^bad^5^1^-^-", "duration is not a number");
        expectQueueRejected("P8^SHIP^prospector^NaN^5^1^-^-", "duration must be finite");
        expectQueueRejected("P8^SHIP^prospector^-1^0^1^-^-", "duration must be between");
        expectQueueRejected("P8^SHIP^prospector^31536001^0^1^-^-", "duration must be between");
        expectQueueRejected("P8^SHIP^prospector^10^11^1^-^-", "remaining must be between");
        expectQueueRejected("P8^SHIP^missing_ship^10^5^1^-^-", "unknown ship item ID");
        expectQueueRejected("P8^SHIP^prospector^10^5^2^-^-", "flag must be 0 or 1");
        expectQueueRejected("bad^SHIP^prospector^10^5^1^-^-", "job ID must match");
        expectQueueRejected(valid + "~" + valid, "duplicate job ID");
        expectQueueRejected(valid + "^extra", "expected 7 or 8 columns");

        Base preserved = new Base("PRESERVED:B1", "QUEUE_TEST", "shipyard", 100, 100);
        ProductionJob original = new ProductionJob("P7", ProductionJobKind.SHIP, "prospector",
                12, 6, true, "");
        preserved.productionQueue.add(original);
        preserved.nextProductionJobId = 8;
        String before = ProductionQueueCodec.write(preserved.productionQueue);
        boolean rejected = false;
        try {
            StrictProductionQueueCodec.readInto(valid + "~P8^SHIP", preserved, "QUEUE_VALIDATION");
        } catch (SnapshotDecodeException ex) {
            rejected = true;
        }
        require(rejected, "malformed queue was not rejected during transactional apply");
        require(ProductionQueueCodec.write(preserved.productionQueue).equals(before),
                "failed queue decode changed the previously valid queue");
        require(preserved.nextProductionJobId == 8,
                "failed queue decode changed the next production job ID");

        PlayerRegistry.reset("SOLO", "Queue Validator", 0x50BEFF);
        World world = new World("Atomic Snapshot Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Base live = base(world, "ATOMIC:B1", "SOLO", "shipyard", 100, 100);
        live.productionQueue.add(new ProductionJob("P7", ProductionJobKind.SHIP, "prospector",
                12, 6, true, ""));
        live.nextProductionJobId = 8;
        BaseState validOther = new BaseState("ATOMIC:B2", "SOLO", "shipyard", 200, 200,
                Rules.base("shipyard").maxHp, Rules.base("shipyard").maxShield, "-", valid);
        BaseState malformedLive = new BaseState(live.id, live.playerId, live.typeId, live.x, live.y,
                live.hp, live.shield, CargoCodec.write(live.inventory), valid + "~P8^SHIP");
        Snapshot malformedSnapshot = new Snapshot(99,
                List.of(new PlayerInfo("SOLO", "Queue Validator", 0x50BEFF, true)),
                List.of(), List.of(), List.of(validOther, malformedLive), List.of(), List.of(), List.of(),
                world.activeSystemId(), world.systemTime());
        rejected = false;
        try {
            WorldNetAccess.apply(world, malformedSnapshot);
        } catch (SnapshotDecodeException ex) {
            rejected = true;
        }
        require(rejected, "malformed base snapshot was not rejected");
        require(world.bases.get(live.id) == live, "failed snapshot replaced the previously valid base");
        require(!world.bases.containsKey(validOther.id()), "failed snapshot partially added another base");
        require(ProductionQueueCodec.write(live.productionQueue).equals(before),
                "failed snapshot changed the previously valid base queue");
    }

    private static void expectQueueRejected(String encoded, String expectedReason) {
        boolean rejected = false;
        try {
            StrictProductionQueueCodec.decode(encoded, "QUEUE_VALIDATION", "BAD:B1");
        } catch (SnapshotDecodeException ex) {
            rejected = true;
            require(ex.getMessage().contains(expectedReason),
                    "decode error did not explain " + expectedReason + ": " + ex.getMessage());
        }
        require(rejected, "malformed queue was accepted: " + encoded);
    }

    private static Base base(World world, String id, String playerId, String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void fill(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static void dockAllLogisticsShuttles(World world, Base target) {
        for (Unit unit : world.units.values()) {
            if (!LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)) continue;
            unit.x = target.x;
            unit.y = target.y;
        }
    }

    private static void removeOneLogisticsShuttle(World world) {
        Iterator<Unit> it = world.units.values().iterator();
        while (it.hasNext()) {
            if (!LogisticsSystem.SHUTTLE_TYPE.equals(it.next().shipTypeId)) continue;
            it.remove();
            return;
        }
    }

    private static int countLogisticsShuttles(World world) {
        int count = 0;
        for (Unit unit : world.units.values()) if (LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)) count++;
        return count;
    }

    private static List<String> jobIds(Base base) {
        List<String> ids = new ArrayList<>();
        for (ProductionJob job : base.productionQueue) ids.add(job.id);
        return ids;
    }

    private static int countUnits(World world, String playerId, String typeId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId) && unit.shipTypeId.equals(typeId)) count++;
        return count;
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 0.01; }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Production queue validation failed: " + message);
    }
}
