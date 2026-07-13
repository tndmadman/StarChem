package com.tndmadman.rts;

/** Repeatedly switches views while snapshots are churned, validating revision-based stale-response rejection. */
public final class TcpViewSwitchStressValidator {
    private TcpViewSwitchStressValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient switching = harness.addClient("TCP View Switch");
            TcpIntegrationHarness.TestClient observer = harness.addClient("TCP View Observer");
            harness.awaitJoined(switching);
            harness.awaitJoined(observer);
            String playerId = switching.playerId();
            Unit unit = harness.firstUnit(harness.serverWorld, playerId);
            TcpIntegrationHarness.require(unit != null, "view-switch client had no authoritative unit");
            String desired = switching.network().clientViewedSystemId();
            String observerSystem = observer.network().clientViewedSystemId();
            long previousSequence = switching.network().clientSnapshotSequence();

            for (int i = 0; i < 160; i++) {
                String firstTarget = harness.reachableFromSystem(desired);
                TcpIntegrationHarness.require(firstTarget != null && !firstTarget.isBlank(),
                        "current system had no reachable view target: " + desired);
                switching.network().jump(playerId, firstTarget, 0, 0);
                desired = firstTarget;
                if (i % 3 == 0) {
                    String secondTarget = harness.reachableFromSystem(desired);
                    if (secondTarget != null && !secondTarget.isBlank()) {
                        switching.network().jump(playerId, secondTarget, 0, 0);
                        desired = secondTarget;
                    }
                }
                harness.setAuthoritativePosition(playerId, unit.unitId,
                        1500 + i * 3.0, 2200 + Math.sin(i / 8.0) * 160);
                harness.runTicks(3);
                long sequence = switching.network().clientSnapshotSequence();
                TcpIntegrationHarness.require(sequence >= previousSequence, "snapshot sequence moved backward during view switching");
                previousSequence = sequence;
                TcpIntegrationHarness.require(observerSystem.equals(observer.network().clientViewedSystemId()),
                        "another client's view changed during view-switch stress");
            }

            String expected = desired;
            harness.await(() -> !switching.network().clientViewSwitchPending()
                            && expected.equals(switching.network().clientViewedSystemId()),
                    12_000, "client did not settle on the newest requested view revision");
            harness.await(() -> sameEntityKeys(harness.serverWorld, switching.world(), expected),
                    10_000, "final viewed system did not converge to the authoritative entity set");
            TcpIntegrationHarness.require(observer.network().clientConnected(),
                    "observer disconnected during another client's view-switch stress");
            System.out.println("StarChem TCP view-switch stress validation passed.");
        }
    }

    private static java.util.Set<Integer> resourceIds(World world) {
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        for (ResourceNode resource : world.resources) ids.add(resource.id);
        return ids;
    }

    private static boolean sameEntityKeys(World server, World client, String systemId) {
        String oldServer = server.activeSystemId();
        String oldClient = client.activeSystemId();
        try {
            server.activateSystem(systemId);
            client.activateSystem(systemId);
            return server.units.keySet().equals(client.units.keySet())
                    && server.bases.keySet().equals(client.bases.keySet())
                    && resourceIds(server).equals(resourceIds(client));
        } finally {
            server.activateSystem(oldServer);
            client.activateSystem(oldClient);
        }
    }
}
