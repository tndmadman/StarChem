package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Regression coverage for nearest-first and cross-system production sourcing. */
public final class ProductionLogisticsSourcingValidator {
    private static final double EPSILON = 0.05;

    private ProductionLogisticsSourcingValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem production logistics sourcing validation passed.");
    }

    static void validateOrThrow() {
        validateNearestLocalSourceWins();
        validateLocalSourceSpillsOnlyWhenNeeded();
        validateOwnedRemoteStationCanFundProduction();
    }

    private static void validateNearestLocalSourceWins() {
        World world = world("Nearest Source Validator");
        String playerId = "PROD_NEAREST_TEST";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Base near = base(world, playerId + ":B2", playerId, "shipyard", 240, 100);
        Base far = base(world, playerId + ":B3", playerId, "shipyard", 1_400, 100);
        List<Cost> cost = prospectorCost();
        seed(near, cost, 2.0);
        seed(far, cost, 2.0);
        EnumMap<Material,Double> farBefore = snapshot(far);

        require(world.buildShip(target.id, "prospector"),
                "nearest-first production request was rejected");
        require(target.productionQueue.size() == 1
                        && ProductionSystem.waitingForResources(target.productionQueue.get(0)),
                "nearest-first request did not enter the logistics-backed production queue");

        for (Cost need : cost) {
            double expectedNear = need.amount();
            double actualNear = near.inventory.getOrDefault(need.material(), 0.0);
            require(close(actualNear, expectedNear),
                    "closest station did not provide the full required " + need.material());
            require(close(far.inventory.getOrDefault(need.material(), 0.0),
                            farBefore.getOrDefault(need.material(), 0.0)),
                    "farther station was touched even though the closest station had enough " + need.material());
        }
    }

    private static void validateLocalSourceSpillsOnlyWhenNeeded() {
        World world = world("Nearest Spill Validator");
        String playerId = "PROD_SPILL_TEST";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Base near = base(world, playerId + ":B2", playerId, "shipyard", 220, 100);
        Base far = base(world, playerId + ":B3", playerId, "shipyard", 1_500, 100);
        List<Cost> cost = prospectorCost();
        seed(near, cost, 0.40);
        seed(far, cost, 2.0);

        require(world.buildShip(target.id, "prospector"),
                "spillover production request was rejected");

        for (Cost need : cost) {
            double nearRemaining = near.inventory.getOrDefault(need.material(), 0.0);
            require(nearRemaining <= EPSILON,
                    "closest station was not exhausted before using a farther source for " + need.material());
            double expectedFar = need.amount() * 1.40;
            double actualFar = far.inventory.getOrDefault(need.material(), 0.0);
            require(close(actualFar, expectedFar),
                    "farther station supplied more or less than the unsatisfied remainder for " + need.material());
        }
    }

    private static void validateOwnedRemoteStationCanFundProduction() {
        World world = world("Cross-System Production Validator");
        String playerId = "PROD_REMOTE_TEST";
        String targetSystemId = world.activeSystemId();
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        List<Cost> cost = prospectorCost();

        String sourceSystemId = reachableOtherSystem(world, playerId, targetSystemId);
        require(!sourceSystemId.isBlank(), "validator could not find a reachable remote system");
        world.activateSystem(sourceSystemId);
        Base remote = base(world, playerId + ":REMOTE", playerId, "shipyard", 420, 420);
        seed(remote, cost, 2.0);
        world.activateSystem(targetSystemId);

        require(world.buildShip(target.id, "prospector"),
                "production request could not call resources from an owned remote station");
        require(target.productionQueue.size() == 1
                        && ProductionSystem.waitingForResources(target.productionQueue.get(0)),
                "remote-backed production request was not queued while cargo was in transit");

        world.activateSystem(sourceSystemId);
        require(countManagedShuttles(world) > 0,
                "remote station resources were not loaded onto cross-system logistics shuttles");
        for (Cost need : cost) {
            require(close(remote.inventory.getOrDefault(need.material(), 0.0), need.amount()),
                    "remote station was not debited by exactly the requested amount of " + need.material());
        }

        deliverManagedShuttles(world, playerId, targetSystemId, target);
        world.activateSystem(targetSystemId);
        world.logisticsSystem.update(world, 2.1);
        ProductionJob job = target.productionQueue.isEmpty() ? null : target.productionQueue.get(0);
        require(job != null && job.resourcesReserved && !ProductionSystem.waitingForResources(job),
                "remote cargo reached the destination but did not fund the manufacturing job");
    }

    private static void deliverManagedShuttles(World world, String playerId,
                                                String targetSystemId, Base target) {
        List<String> systems = systemIds(world);
        for (int guard = 0; guard < 64; guard++) {
            boolean found = false;
            for (String systemId : systems) {
                world.activateSystem(systemId);
                List<Unit> managed = managedShuttles(world);
                if (managed.isEmpty()) continue;
                found = true;
                if (systemId.equals(targetSystemId)) {
                    for (Unit shuttle : managed) {
                        shuttle.x = target.x;
                        shuttle.y = target.y;
                    }
                    InterSystemProductionLogistics.update(world);
                    continue;
                }

                List<String> path = LogisticsRouteSystem.pathForTest(
                        world, playerId, systemId, targetSystemId);
                require(path.size() >= 2,
                        "in-transit production shuttle lost its route to the destination");
                WormholeGate gate = gateTo(world, path.get(1));
                require(gate != null, "next-hop wormhole was missing for production cargo");
                for (Unit shuttle : managed) {
                    shuttle.x = gate.x;
                    shuttle.y = gate.y;
                }
                world.transferTouchingShips(playerId);
            }
            if (!found || countManagedShuttlesGalaxy(world, playerId) == 0) return;
        }
        throw new IllegalStateException(
                "Production logistics sourcing validation failed: cross-system shuttles did not finish within guard");
    }

    private static int countManagedShuttlesGalaxy(World world, String playerId) {
        int count = 0;
        String previous = world.activeSystemId();
        for (String systemId : systemIds(world)) {
            world.activateSystem(systemId);
            for (Unit unit : world.units.values()) {
                if (playerId.equals(unit.playerId) && InterSystemProductionLogistics.manages(unit)) count++;
            }
        }
        world.activateSystem(previous);
        return count;
    }

    private static List<Unit> managedShuttles(World world) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (InterSystemProductionLogistics.manages(unit)) out.add(unit);
        }
        return out;
    }

    private static int countManagedShuttles(World world) {
        return managedShuttles(world).size();
    }

    private static WormholeGate gateTo(World world, String systemId) {
        for (WormholeGate gate : world.wormholes) {
            if (systemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static String reachableOtherSystem(World world, String playerId, String targetSystemId) {
        for (String systemId : systemIds(world)) {
            if (systemId.equals(targetSystemId)) continue;
            if (LogisticsRouteSystem.pathForTest(world, playerId, systemId, targetSystemId).size() >= 2) {
                return systemId;
            }
        }
        return "";
    }

    private static List<String> systemIds(World world) {
        List<String> out = new ArrayList<>();
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && system.id() != null && !system.id().isBlank()) out.add(system.id());
        }
        return List.copyOf(out);
    }

    private static List<Cost> prospectorCost() {
        ShipType ship = Rules.ship("prospector");
        ShipLoadoutDefinition loadout = WeaponRules.defaultLoadout(ship.id);
        return WeaponRules.buildCost(ship, loadout);
    }

    private static World world(String name) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        return new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
    }

    private static Base base(World world, String id, String playerId,
                             String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void seed(Base base, List<Cost> costs, double multiplier) {
        for (Cost cost : costs) {
            base.inventory.put(cost.material(), cost.amount() * multiplier);
        }
    }

    private static EnumMap<Material,Double> snapshot(Base base) {
        return new EnumMap<>(base.inventory);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(
                    "Production logistics sourcing validation failed: " + message);
        }
    }
}
