package com.tndmadman.rts;

import java.util.*;

/** Validates simultaneous clients, command isolation, convergence, and survivor responsiveness. */
public final class TcpMultiClientValidator {
    private TcpMultiClientValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            List<TcpIntegrationHarness.TestClient> clients = new ArrayList<>();
            for (int i = 1; i <= 4; i++) clients.add(harness.addClient("TCP Multi " + i));
            for (TcpIntegrationHarness.TestClient client : clients) harness.awaitJoined(client);

            Set<String> ids = new LinkedHashSet<>();
            for (TcpIntegrationHarness.TestClient client : clients) {
                TcpIntegrationHarness.require(ids.add(client.playerId()), "duplicate player ID assigned to simultaneous clients");
            }
            TcpIntegrationHarness.require(harness.serverNetwork.serverPeerCount() == clients.size(),
                    "server did not retain all simultaneous TCP clients");

            Map<String, double[]> expectedTargets = new LinkedHashMap<>();
            Map<String, Integer> unitIds = new LinkedHashMap<>();
            int index = 0;
            for (TcpIntegrationHarness.TestClient client : clients) {
                Unit unit = harness.firstUnit(harness.serverWorld, client.playerId());
                TcpIntegrationHarness.require(unit != null, "missing authoritative unit for " + client.playerId());
                double targetX = unit.x + 25 + index * 9;
                double targetY = unit.y + 15 + index * 7;
                expectedTargets.put(client.playerId(), new double[]{Double.parseDouble(Calc.round(targetX)), Double.parseDouble(Calc.round(targetY))});
                unitIds.put(client.playerId(), unit.unitId);
                client.network().move(new MoveCommand(client.playerId(), unit.unitId, targetX, targetY));
                index++;
            }

            harness.await(() -> {
                for (TcpIntegrationHarness.TestClient client : clients) {
                    Unit unit = harness.unit(harness.serverWorld, client.playerId(), unitIds.get(client.playerId()));
                    double[] expected = expectedTargets.get(client.playerId());
                    if (unit == null || Math.abs(unit.targetX - expected[0]) > 0.001 || Math.abs(unit.targetY - expected[1]) > 0.001) return false;
                }
                return true;
            }, 5_000, "one or more simultaneous client commands did not reach the authoritative server");

            harness.runTicks(300);
            for (TcpIntegrationHarness.TestClient client : clients) {
                harness.awaitConverged(client, unitIds.get(client.playerId()), 1.0, 6_000);
            }

            TcpIntegrationHarness.TestClient removed = clients.remove(0);
            removed.network().shutdown();
            harness.await(() -> harness.serverNetwork.serverPeerCount() == clients.size(), 5_000,
                    "disconnecting one client affected server peer accounting");

            TcpIntegrationHarness.TestClient survivor = clients.get(0);
            Unit survivorUnit = harness.unit(harness.serverWorld, survivor.playerId(), unitIds.get(survivor.playerId()));
            double survivorTarget = survivorUnit.x + 40;
            survivor.network().move(new MoveCommand(survivor.playerId(), survivorUnit.unitId, survivorTarget, survivorUnit.y));
            harness.await(() -> {
                Unit unit = harness.unit(harness.serverWorld, survivor.playerId(), survivorUnit.unitId);
                return unit != null && Math.abs(unit.targetX - Double.parseDouble(Calc.round(survivorTarget))) < 0.001;
            }, 4_000, "healthy clients stopped responding after another client disconnected");

            System.out.println("StarChem TCP multi-client validation passed.");
        }
    }
}
