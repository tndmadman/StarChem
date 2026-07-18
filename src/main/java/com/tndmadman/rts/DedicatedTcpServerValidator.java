package com.tndmadman.rts;

import java.util.LinkedHashSet;

/** Exercises the production headless dedicated-server path with real TCP clients. */
public final class DedicatedTcpServerValidator {
    private static final String STABLE_PROBE_SHIP = "station_builder";

    private DedicatedTcpServerValidator() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            TcpIntegrationHarness.require(harness.serverConfig.hostMode, "dedicated config did not enter host mode");
            TcpIntegrationHarness.require(harness.serverConfig.dedicatedServerMode(), "dedicated config lost its dedicated flag");
            TcpIntegrationHarness.TestClient first = harness.addClient("Dedicated Client A");
            TcpIntegrationHarness.TestClient second = harness.addClient("Dedicated Client B");
            harness.awaitJoined(first);
            harness.awaitJoined(second);
            TcpIntegrationHarness.require(harness.serverNetwork.serverPeerCount() == 2,
                    "dedicated server did not accept both TCP clients");
            TcpIntegrationHarness.require(!first.network().devToolsAllowed() && !second.network().devToolsAllowed(),
                    "dedicated server granted unauthenticated loopback developer access");

            Unit firstUnit = harness.firstUnit(harness.serverWorld, first.playerId());
            Unit secondUnit = harness.firstUnit(harness.serverWorld, second.playerId());
            TcpIntegrationHarness.require(firstUnit != null && secondUnit != null, "dedicated server did not create client units");
            stabilizeProbe(harness.serverWorld, first.playerId(), firstUnit.unitId);
            stabilizeProbe(harness.serverWorld, second.playerId(), secondUnit.unitId);
            harness.await(() -> stableProbe(first.world(), first.playerId(), firstUnit.unitId)
                            && stableProbe(second.world(), second.playerId(), secondUnit.unitId),
                    5_000, "dedicated clients did not receive the stable convergence probes");

            firstUnit = harness.unit(harness.serverWorld, first.playerId(), firstUnit.unitId);
            secondUnit = harness.unit(harness.serverWorld, second.playerId(), secondUnit.unitId);
            double firstStartX = firstUnit.x;
            double secondStartX = secondUnit.x;
            first.network().move(new MoveCommand(first.playerId(), firstUnit.unitId, firstUnit.x + 45, firstUnit.y + 10));
            second.network().move(new MoveCommand(second.playerId(), secondUnit.unitId, secondUnit.x + 35, secondUnit.y + 15));
            harness.await(() -> {
                Unit a = harness.unit(harness.serverWorld, first.playerId(), firstUnit.unitId);
                Unit b = harness.unit(harness.serverWorld, second.playerId(), secondUnit.unitId);
                return a != null && b != null && a.targetX != firstStartX && b.targetX != secondStartX;
            }, 5_000, "dedicated server did not process client commands");
            harness.runTicks(250);
            harness.awaitConverged(first, firstUnit.unitId, 1.0, 6_000);
            harness.awaitConverged(second, secondUnit.unitId, 1.0, 6_000);
            System.out.println("StarChem dedicated TCP server validation passed.");
        }
    }

    private static boolean stableProbe(World world, String playerId, int unitId) {
        Unit unit = findAcrossSystems(world, playerId, unitId);
        return unit != null && STABLE_PROBE_SHIP.equals(unit.shipTypeId);
    }

    private static void stabilizeProbe(World world, String playerId, int unitId) {
        TcpIntegrationHarness.require(Rules.findShip(STABLE_PROBE_SHIP) != null,
                "stable dedicated-server probe ship is not configured");
        String old = world.activeSystemId();
        try {
            for (String systemId : systemIds(world, playerId)) {
                if (systemId == null || systemId.isBlank() || systemId.contains("WAIT")) continue;
                world.activateSystem(systemId);
                Unit unit = world.units.get(Unit.key(playerId, unitId));
                if (unit == null) continue;
                unit.shipTypeId = STABLE_PROBE_SHIP;
                unit.task = UnitTask.IDLE;
                unit.automationResourceId = -1;
                unit.attackTarget = "";
                unit.targetX = unit.x;
                unit.targetY = unit.y;
                world.saveActiveSystem();
                return;
            }
            throw new IllegalStateException("dedicated-server probe unit was not found for " + playerId + ':' + unitId);
        } finally {
            world.activateSystem(old);
        }
    }

    private static Unit findAcrossSystems(World world, String playerId, int unitId) {
        String old = world.activeSystemId();
        try {
            for (String systemId : systemIds(world, playerId)) {
                if (systemId == null || systemId.isBlank() || systemId.contains("WAIT")) continue;
                world.activateSystem(systemId);
                Unit unit = world.units.get(Unit.key(playerId, unitId));
                if (unit != null) return unit;
            }
            return null;
        } finally {
            world.activateSystem(old);
        }
    }

    private static LinkedHashSet<String> systemIds(World world, String playerId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(world.activeSystemId());
        ids.add(world.playerHomeSystemId(playerId));
        GalaxyMapSnapshot galaxy = world.galaxyMapSnapshot();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) {
                if (system != null) ids.add(system.id());
            }
        }
        return ids;
    }
}
