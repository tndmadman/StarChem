package com.tndmadman.rts;

import java.util.Map;
import java.util.Set;

/** End-to-end validation for issue 290 owner-fleet reconciliation over authenticated TCP. */
public final class ControlGroupTcpValidator {
    private ControlGroupTcpValidator() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        validate();
        System.out.println("Control group TCP validation passed.");
    }

    static void validate() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            TcpIntegrationHarness.TestClient client = harness.addClient("ControlGroupTcp");
            harness.awaitJoined(client);
            String playerId = client.playerId();
            require(TcpIntegrationHarness.realPlayerId(playerId), "client did not receive a real player identity");

            Unit authoritative = harness.firstUnit(harness.serverWorld, playerId);
            require(authoritative != null, "joined player has no authoritative ship");
            String unitKey = authoritative.key();

            harness.await(() -> {
                OwnerFleetLocationRegistry.State state = fleetState(client);
                return state.initialized() && state.locations().containsKey(unitKey);
            }, 10_000, "initial owner-fleet galaxy projection was not delivered");
            assertOwnerOnly(playerId, fleetState(client).locations());

            ControlGroupManager groups = new ControlGroupManager();
            groups.assign(1, Set.of(unitKey), FleetFormation.GRID);
            groups.prune(fleetState(client).locations());
            require(groups.size(1) == 1, "initial reconciliation pruned a live group member");

            String firstSystem = fleetState(client).locations().get(unitKey);
            String secondSystem = anotherSystem(harness.serverWorld, firstSystem);
            movePlayerAssets(harness.serverWorld, playerId, firstSystem, secondSystem);
            harness.await(() -> secondSystem.equals(fleetState(client).locations().get(unitKey)),
                    10_000, "owner-fleet galaxy projection did not follow a cross-system transfer");
            groups.prune(fleetState(client).locations());
            require(groups.size(1) == 1 && groups.contains(1, unitKey),
                    "cross-system transfer lost stable control-group membership");

            // Exercise the broadcast sequence that exposed the original issue-290 transport regression.
            harness.serverNetwork.setRuntimeDevEnabled(true);
            harness.serverNetwork.setRemoteDevAccess(playerId, true);
            harness.serverNetwork.setServerFreeBuild(playerId, true);
            harness.runTicks(80);
            harness.await(() -> harness.serverNetwork.serverSessionConnected(playerId)
                            && client.network().clientConnected(),
                    10_000, "control-group galaxy metadata destabilized the authenticated session");
            harness.serverNetwork.setServerFreeBuild(playerId, false);
            harness.runTicks(40);

            client.network().forceClientDisconnectForTest();
            harness.await(() -> client.network().clientReconnecting() || !client.network().clientConnected(),
                    6_000, "forced disconnect did not enter reconnect flow");
            harness.await(() -> client.network().clientConnected()
                            && harness.serverNetwork.serverSessionConnected(playerId),
                    25_000, "client did not resume its authenticated session");

            String thirdSystem = anotherSystem(harness.serverWorld, secondSystem);
            movePlayerAssets(harness.serverWorld, playerId, secondSystem, thirdSystem);
            harness.await(() -> thirdSystem.equals(fleetState(client).locations().get(unitKey)),
                    10_000, "fresh owner-fleet state was not reconciled after reconnect");
            groups.prune(fleetState(client).locations());
            require(groups.size(1) == 1, "reconnect/resync pruned a surviving group member");

            destroyUnit(harness.serverWorld, unitKey, thirdSystem);
            harness.await(() -> {
                OwnerFleetLocationRegistry.State state = fleetState(client);
                return state.initialized() && !state.locations().containsKey(unitKey);
            }, 10_000, "destroyed ship remained in owner-fleet galaxy projection");
            groups.prune(fleetState(client).locations());
            require(groups.empty(1), "destroyed ship left a ghost control-group member");
            assertOwnerOnly(playerId, fleetState(client).locations());
        }
    }

    private static OwnerFleetLocationRegistry.State fleetState(TcpIntegrationHarness.TestClient client) {
        return OwnerFleetLocationRegistry.state(client.world());
    }

    private static String anotherSystem(World world, String excluded) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot != null && snapshot.systems() != null) {
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system != null && system.id() != null && !system.id().isBlank()
                        && !system.id().equals(excluded)) return system.id();
            }
        }
        throw new IllegalStateException("No alternate system is available for control-group validation.");
    }

    private static void movePlayerAssets(World world, String playerId, String sourceSystem, String targetSystem) {
        String previous = world.activeSystemId();
        try {
            world.activateSystem(sourceSystem);
            world.movePlayerAssetsToSystem(playerId, targetSystem);
            world.saveActiveSystem();
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
    }

    private static void destroyUnit(World world, String unitKey, String systemId) {
        String previous = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            Unit removed = world.units.remove(unitKey);
            require(removed != null, "authoritative ship was missing before destruction");
            world.saveActiveSystem();
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
    }

    private static void assertOwnerOnly(String playerId, Map<String,String> locations) {
        String prefix = playerId + ":";
        for (String key : locations.keySet()) {
            require(key != null && key.startsWith(prefix),
                    "owner-fleet projection leaked a foreign unit key: " + key);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Control group TCP validation failed: " + message);
    }
}
