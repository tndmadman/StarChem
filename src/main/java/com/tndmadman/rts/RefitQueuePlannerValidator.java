package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused validation for multi-station refit queue assignment and aggregate hangar capacity. */
public final class RefitQueuePlannerValidator {
    private static final double EXPECTED_REFIT_SECONDS = 1.5;

    private RefitQueuePlannerValidator() { }

    public static void main(String[] args) {
        require(Rules.base("outpost").canRefitShips,
                "configured Outpost is not refit-capable");
        require(Rules.base("shipyard").canRefitShips,
                "configured Shipyard is not refit-capable");
        require(close(WeaponRules.findLoadout("prospector").refitTimeSeconds(), EXPECTED_REFIT_SECONDS),
                "Prospector refit duration is not 1.5 seconds");
        require(close(WeaponRules.findLoadout("dreadnought").refitTimeSeconds(), EXPECTED_REFIT_SECONDS),
                "capital refit duration is not 1.5 seconds");

        String playerId = "REFIT_NETWORK";
        PlayerRegistry.reset("SOLO", "Refit Queue Validator", 0x50BEFF);
        World world = new World("Refit Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.completeResearch(playerId, "combat_doctrine");
        world.completeResearch(playerId, "battlefleet_engineering");

        Base outpost = new Base(playerId + ":B1", playerId, "outpost", 1000, 1000);
        Base shipyard = new Base(playerId + ":B2", playerId, "shipyard", 5000, 1000);
        world.bases.put(outpost.id, outpost);
        world.bases.put(shipyard.id, shipyard);

        ShipFitSpec spec = new ShipFitSpec("destroyer", List.of("light_railgun"),
                List.of("afterburner", "micro_jump_drive"));
        ShipLoadoutDefinition loadout = WorldFitCatalog.registerRuntime(world, "Distributed Mobility", spec);
        require(close(loadout.refitTimeSeconds(), EXPECTED_REFIT_SECONDS),
                "player-created fit did not inherit the 1.5-second refit duration");
        List<Cost> cost = WeaponRules.refitCost(loadout);
        fund(outpost, cost, 2);
        fund(shipyard, cost, 2);

        List<Unit> ships = new ArrayList<>();
        ships.add(addShip(world, playerId, 1, 1150, 1000));
        ships.add(addShip(world, playerId, 2, 1300, 1000));
        ships.add(addShip(world, playerId, 3, 4850, 1000));
        ships.add(addShip(world, playerId, 4, 4700, 1000));

        FitCommand.Result result = FitCommand.applyLocal(world, playerId, "REFIT_CLASS", Map.of(
                "name", "Distributed Mobility",
                "spec", spec.toMap(),
                "baseId", outpost.id));
        require(result.success(), "class refit request was rejected: " + result.message());
        require(refitJobs(outpost) == 2,
                "Outpost did not receive its two-job share of the refit queue");
        require(refitJobs(shipyard) == 2,
                "Shipyard did not receive its two-job share of the refit queue");
        require(result.message().contains("across 2 stations"),
                "result did not report multi-station distribution: " + result.message());

        for (Unit ship : ships) {
            ShipFittingWindow.ActiveRefit active = ShipFittingWindow.activeRefit(world, ship);
            require(active != null, "ship was not reserved in a station refit queue: " + ship.key());
            require(active.base() == outpost || active.base() == shipyard,
                    "ship was assigned to a non-refit station");
            require(close(active.job().duration, EXPECTED_REFIT_SECONDS),
                    "queued refit job did not use the 1.5-second duration");
            require(ship.task == UnitTask.MOVE || ProductionSystem.refitLocked(world, ship.key()),
                    "queued ship was neither recalling nor docked for refit");
        }

        require(!canAffordAnother(outpost, cost) && !canAffordAnother(shipyard, cost),
                "station hangars were not charged for their assigned refits");

        validateBusyStationAvoidance(spec);
        FitBootstrapValidator.validateOrThrow();
        System.out.println("StarChem distributed refit queue and custom-fit bootstrap validation passed.");
    }

    private static void validateBusyStationAvoidance(ShipFitSpec spec) {
        String playerId = "REFIT_BALANCE";
        World world = new World("Refit Balance Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.completeResearch(playerId, "combat_doctrine");
        world.completeResearch(playerId, "battlefleet_engineering");

        Base outpost = new Base(playerId + ":B1", playerId, "outpost", 1000, 1000);
        Base shipyard = new Base(playerId + ":B2", playerId, "shipyard", 2600, 1000);
        world.bases.put(outpost.id, outpost);
        world.bases.put(shipyard.id, shipyard);
        ShipLoadoutDefinition loadout = WorldFitCatalog.registerRuntime(world, "Queue Balance", spec);
        fund(outpost, WeaponRules.refitCost(loadout), 1);
        fund(shipyard, WeaponRules.refitCost(loadout), 1);

        ProductionJob blocker = new ProductionJob("P1", ProductionJobKind.CRAFTABLE,
                "targeting_computer", 600, 600, true, "");
        outpost.productionQueue.add(blocker);
        outpost.nextProductionJobId = 2;

        Unit ship = addShip(world, playerId, 1, 1080, 1000);
        RefitQueuePlanner.Result result = RefitQueuePlanner.enqueue(world, playerId, List.of(ship),
                loadout, false, outpost);
        require(result.success(), "single balanced refit request failed: " + result.message());
        require(refitJobs(outpost) == 0,
                "planner assigned a refit behind a 600-second blocked station queue");
        require(refitJobs(shipyard) == 1,
                "planner did not route the refit to the less-loaded Shipyard");
    }

    private static Unit addShip(World world, String playerId, int id, double x, double y) {
        Unit unit = new Unit(playerId, id, "destroyer", x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static void fund(Base base, List<Cost> cost, int jobs) {
        base.inventory.clear();
        for (Cost item : cost) base.inventory.put(item.material(), item.amount() * jobs);
    }

    private static int refitJobs(Base base) {
        int count = 0;
        for (ProductionJob job : base.productionQueue) if (job.kind == ProductionJobKind.REFIT) count++;
        return count;
    }

    private static boolean canAffordAnother(Base base, List<Cost> cost) {
        return HangarStore.canAfford(base.inventory, cost);
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 0.001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Refit queue validation failed: " + message);
    }
}
