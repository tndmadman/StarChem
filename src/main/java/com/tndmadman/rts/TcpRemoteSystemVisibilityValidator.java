package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.Set;

/** Validates server-approved arbitrary galaxy views and cross-system entity visibility. */
public final class TcpRemoteSystemVisibilityValidator {
    private TcpRemoteSystemVisibilityValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient viewer = harness.addClient("TCP Remote Viewer");
            TcpIntegrationHarness.TestClient owner = harness.addClient("TCP Remote Owner");
            harness.awaitJoined(viewer);
            harness.awaitJoined(owner);

            String viewerId = viewer.playerId();
            String ownerId = owner.playerId();
            String source = viewer.world().activeSystemId();
            String target = harness.serverWorld.playerHomeSystemId(ownerId);
            TcpIntegrationHarness.require(!source.equals(target), "remote-view target matched the viewer's current system");
            TcpIntegrationHarness.require(!directlyConnected(harness.serverWorld, source, target),
                    "visibility regression requires a non-adjacent target system");

            Set<String> expectedUnits = unitKeys(harness.serverWorld, target);
            Set<String> expectedBases = baseKeys(harness.serverWorld, target);
            TcpIntegrationHarness.require(expectedUnits.stream().anyMatch(key -> key.startsWith(ownerId + ":")),
                    "target system did not contain the remote player's ship");
            TcpIntegrationHarness.require(expectedBases.stream().anyMatch(key -> key.startsWith(ownerId + ":")),
                    "target system did not contain the remote player's station");

            viewer.network().viewSystem(viewerId, target);
            TcpIntegrationHarness.require(source.equals(viewer.world().activeSystemId()),
                    "client switched systems optimistically before server approval");
            harness.await(() -> !viewer.network().clientViewSwitchPending()
                            && target.equals(viewer.network().clientViewedSystemId())
                            && target.equals(viewer.world().activeSystemId()),
                    12_000, "client did not settle on the server-approved arbitrary system view");

            TcpIntegrationHarness.require(expectedUnits.equals(new LinkedHashSet<>(viewer.world().units.keySet())),
                    "remote system ship set did not match the authoritative server state");
            TcpIntegrationHarness.require(expectedBases.equals(new LinkedHashSet<>(viewer.world().bases.keySet())),
                    "remote system station set did not match the authoritative server state");

            viewer.network().viewSystem(viewerId, "missing_system_for_validation");
            harness.await(() -> !viewer.network().clientViewSwitchPending(), 5_000,
                    "invalid system view request was not rejected");
            TcpIntegrationHarness.require(target.equals(viewer.world().activeSystemId()),
                    "rejected view request changed the active client system");
            System.out.println("StarChem remote-system visibility validation passed.");
        }
    }

    private static boolean directlyConnected(World world, String source, String target) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(source);
            for (WormholeGate gate : world.wormholes) if (target.equals(gate.toSystemId)) return true;
            return false;
        } finally {
            world.activateSystem(old);
        }
    }

    private static Set<String> unitKeys(World world, String systemId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            return new LinkedHashSet<>(world.units.keySet());
        } finally {
            world.activateSystem(old);
        }
    }

    private static Set<String> baseKeys(World world, String systemId) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            return new LinkedHashSet<>(world.bases.keySet());
        } finally {
            world.activateSystem(old);
        }
    }
}
