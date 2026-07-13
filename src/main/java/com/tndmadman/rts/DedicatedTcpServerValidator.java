package com.tndmadman.rts;

/** Exercises the production headless dedicated-server path with real TCP clients. */
public final class DedicatedTcpServerValidator {
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
            first.network().move(new MoveCommand(first.playerId(), firstUnit.unitId, firstUnit.x + 45, firstUnit.y + 10));
            second.network().move(new MoveCommand(second.playerId(), secondUnit.unitId, secondUnit.x + 35, secondUnit.y + 15));
            harness.await(() -> {
                Unit a = harness.unit(harness.serverWorld, first.playerId(), firstUnit.unitId);
                Unit b = harness.unit(harness.serverWorld, second.playerId(), secondUnit.unitId);
                return a != null && b != null && a.targetX != firstUnit.x && b.targetX != secondUnit.x;
            }, 5_000, "dedicated server did not process client commands");
            harness.runTicks(250);
            harness.awaitConverged(first, firstUnit.unitId, 1.0, 6_000);
            harness.awaitConverged(second, secondUnit.unitId, 1.0, 6_000);
            System.out.println("StarChem dedicated TCP server validation passed.");
        }
    }
}
