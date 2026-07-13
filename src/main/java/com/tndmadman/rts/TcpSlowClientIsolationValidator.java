package com.tndmadman.rts;

import java.net.InetAddress;

/** Validates that one backpressured client cannot stall healthy clients or the authoritative server. */
public final class TcpSlowClientIsolationValidator {
    private TcpSlowClientIsolationValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
             TcpFaultProxy proxy = new TcpFaultProxy(InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            TcpIntegrationHarness.TestClient slow = harness.addProxiedClient("TCP Slow", proxy);
            TcpIntegrationHarness.TestClient healthy = harness.addClient("TCP Healthy");
            harness.awaitJoined(slow);
            harness.awaitJoined(healthy);

            Unit slowUnit = harness.firstUnit(harness.serverWorld, slow.playerId());
            Unit healthyUnit = harness.firstUnit(harness.serverWorld, healthy.playerId());
            TcpIntegrationHarness.require(slowUnit != null && healthyUnit != null, "test units were not created");
            long healthySequenceBefore = healthy.network().clientSnapshotSequence();
            long slowSequenceBefore = slow.network().clientSnapshotSequence();
            ConnectionId slowConnection = harness.serverNetwork.connectionIdForPlayer(slow.playerId());
            TcpIntegrationHarness.require(slowConnection.valid(), "slow client connection ID was not registered");

            proxy.pauseServerToClient();
            Thread.sleep(100);
            for (int i = 0; i < 450; i++) {
                harness.setAuthoritativePosition(slow.playerId(), slowUnit.unitId, 1800 + i, 2100 + Math.sin(i / 10.0) * 120);
                harness.setAuthoritativePosition(healthy.playerId(), healthyUnit.unitId, 3200 + i, 3500 + Math.cos(i / 12.0) * 120);
                harness.tick();
            }

            double targetX = healthyUnit.x + 70;
            healthy.network().move(new MoveCommand(healthy.playerId(), healthyUnit.unitId, targetX, healthyUnit.y));
            harness.await(() -> {
                Unit unit = harness.unit(harness.serverWorld, healthy.playerId(), healthyUnit.unitId);
                return unit != null && Math.abs(unit.targetX - Double.parseDouble(Calc.round(targetX))) < 0.001;
            }, 4_000, "slow client blocked a healthy client's command path");

            TcpIntegrationHarness.require(healthy.network().clientSnapshotSequence() > healthySequenceBefore,
                    "healthy client stopped receiving snapshots while another client was slow");
            TcpIntegrationHarness.require(slow.network().clientSnapshotSequence() <= slowSequenceBefore + 2,
                    "paused proxy unexpectedly continued delivering sustained snapshot traffic");
            ConnectionDiagnostics diagnostics = harness.serverNetwork.connectionDiagnostics(slowConnection);
            TcpIntegrationHarness.require(diagnostics.queuedFrames() <= 3,
                    "slow client's replaceable state queue grew without bound: " + diagnostics);
            TcpIntegrationHarness.require(healthy.network().clientConnected(), "healthy client was disconnected by another client's backpressure");

            proxy.resumeServerToClient();
            harness.awaitConverged(slow, slowUnit.unitId, 1.25, 10_000);
            TcpIntegrationHarness.require(healthy.network().clientConnected(), "healthy client did not remain connected after slow client recovery");
            System.out.println("StarChem TCP slow-client isolation validation passed.");
        }
    }
}
