package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Repeatedly switches authorized views while snapshots churn, validating revision-based stale-response rejection. */
public final class TcpViewSwitchStressValidator {
    private static final int REMOTE_SCOUT_ID_BASE = 910_000;

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

            List<String> authorizedSystems = authorizedSystems(harness.serverWorld, playerId, 3);
            TcpIntegrationHarness.require(authorizedSystems.size() >= 3,
                    "view-switch validation could not select three authoritative systems");
            for (int i = 1; i < authorizedSystems.size(); i++) {
                placeRemoteScout(harness.serverWorld, playerId, authorizedSystems.get(i), REMOTE_SCOUT_ID_BASE + i);
            }

            String desired = switching.network().clientViewedSystemId();
            String observerSystem = observer.network().clientViewedSystemId();
            long previousSequence = switching.network().clientSnapshotSequence();
            int targetIndex = Math.max(0, authorizedSystems.indexOf(desired));

            for (int i = 0; i < 160; i++) {
                targetIndex = (targetIndex + 1) % authorizedSystems.size();
                desired = authorizedSystems.get(targetIndex);
                switching.network().viewSystem(playerId, desired);
                if (i % 3 == 0) {
                    targetIndex = (targetIndex + 1) % authorizedSystems.size();
                    desired = authorizedSystems.get(targetIndex);
                    switching.network().viewSystem(playerId, desired);
                }
                harness.setAuthoritativePosition(playerId, unit.unitId,
                        1500 + i * 3.0, 2200 + Math.sin(i / 8.0) * 160);
                harness.runTicks(3);
                long sequence = switching.network().clientSnapshotSequence();
                TcpIntegrationHarness.require(sequence >= previousSequence,
                        "snapshot sequence moved backward during view switching");
                previousSequence = sequence;
                TcpIntegrationHarness.require(observerSystem.equals(observer.network().clientViewedSystemId()),
                        "another client's view changed during view-switch stress");
            }

            String expected = desired;
            harness.await(() -> !switching.network().clientViewSwitchPending()
                            && expected.equals(switching.network().clientViewedSystemId())
                            && expected.equals(switching.world().activeSystemId()),
                    12_000, "client did not settle on the newest requested view revision");
            harness.await(() -> sameOwnEntityKeys(harness.serverWorld, switching.world(), expected, playerId),
                    10_000, "final viewed system did not converge to the authoritative owned-entity set");
            TcpIntegrationHarness.require(observer.network().clientConnected(),
                    "observer disconnected during another client's view-switch stress");
            System.out.println("StarChem TCP view-switch stress validation passed.");
        }
    }

    private static List<String> authorizedSystems(World world, String playerId, int limit) {
        LinkedHashSet<String> systems = new LinkedHashSet<>();
        systems.add(world.playerHomeSystemId(playerId));
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map != null && map.systems() != null) {
            for (GalaxyMapSystem system : map.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                systems.add(system.id());
                if (systems.size() >= limit) break;
            }
        }
        return new ArrayList<>(systems);
    }

    private static void placeRemoteScout(World world, String playerId, String systemId, int unitId) {
        String previousSystem = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            Unit scout = new Unit(playerId, unitId, "scout", world.width * 0.5, world.height * 0.5);
            world.units.put(scout.key(), scout);
            world.saveActiveSystem();
        } finally {
            world.activateSystem(previousSystem);
        }
    }

    private static Set<String> ownUnitKeys(World world, String playerId) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Unit unit : world.units.values()) {
            if (playerId.equals(unit.playerId) && unit.hp > 0) keys.add(unit.key());
        }
        return keys;
    }

    private static Set<String> ownBaseKeys(World world, String playerId) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Base base : world.bases.values()) {
            if (playerId.equals(base.playerId) && base.hp > 0) keys.add(base.id);
        }
        return keys;
    }

    private static boolean sameOwnEntityKeys(World server, World client, String systemId, String playerId) {
        String oldServer = server.activeSystemId();
        String oldClient = client.activeSystemId();
        try {
            server.activateSystem(systemId);
            client.activateSystem(systemId);
            return ownUnitKeys(server, playerId).equals(ownUnitKeys(client, playerId))
                    && ownBaseKeys(server, playerId).equals(ownBaseKeys(client, playerId));
        } finally {
            server.activateSystem(oldServer);
            client.activateSystem(oldClient);
        }
    }
}
