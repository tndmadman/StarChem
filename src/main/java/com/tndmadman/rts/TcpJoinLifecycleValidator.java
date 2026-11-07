package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Real TCP regression for admission generations, duplicate handshakes,
 * initial-view readiness and proof-based recovery. Complements the security,
 * slow-client and dedicated-server validators in the normal check suite.
 */
public final class TcpJoinLifecycleValidator {
    private TcpJoinLifecycleValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated();
             TcpFaultProxy proxy = new TcpFaultProxy(
                     InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            TcpIntegrationHarness.TestClient first = harness.addProxiedClient("Lifecycle First", proxy);
            TcpIntegrationHarness.TestClient second = harness.addClient("Lifecycle Second");
            harness.awaitJoined(first);
            harness.awaitJoined(second);
            harness.await(() -> harness.serverNetwork.serverConnectionReady(first.playerId())
                    && harness.serverNetwork.serverConnectionReady(second.playerId()), 10_000,
                    "server did not acknowledge both authenticated initial world views");

            Set<String> ids = new LinkedHashSet<>();
            require(ids.add(first.playerId()) && ids.add(second.playerId()),
                    "distinct connections were assigned the same identity");
            require(harness.serverNetwork.persistentPlayerSessions().size() == 2,
                    "fresh dedicated server created extra retained identities");
            require(harness.serverNetwork.serverPeerCount() == 2,
                    "both peers were not retained as connected");
            assertStarter(harness.serverWorld, first.playerId());
            assertStarter(harness.serverWorld, second.playerId());

            ConnectionId firstServerId = harness.serverNetwork.connectionIdForPlayer(first.playerId());
            ConnectionId secondServerId = harness.serverNetwork.connectionIdForPlayer(second.playerId());
            ConnectionId firstClientId = first.network().clientConnectionId();
            int firstTransitions = first.network().clientSyncTransitions();
            int secondTransitions = second.network().clientSyncTransitions();
            require(firstServerId.valid() && secondServerId.valid() && firstClientId.valid(),
                    "missing TCP generation identifiers after join");
            require(harness.serverNetwork.serverInitialSyncBuilds(first.playerId()) == 1
                    && harness.serverNetwork.serverInitialSyncBuilds(second.playerId()) == 1,
                    "a healthy join rebuilt the full authoritative initial snapshot");

            // Repeat JOIN and RESUME after READY on the same authenticated sockets,
            // then deliver a real duplicate WELCOME. Neither side may demote READY
            // or serialize another initial authoritative world snapshot.
            first.network().sendDuplicateHandshakeForTest();
            second.network().sendDuplicateHandshakeForTest();
            harness.serverNetwork.replayWelcomeForTest(first.playerId());
            harness.serverNetwork.replayWelcomeForTest(second.playerId());
            harness.runTicks(600);
            require(harness.serverNetwork.serverConnectionReady(first.playerId())
                    && harness.serverNetwork.serverConnectionReady(second.playerId()),
                    "duplicate handshake demoted server READY");
            require(first.network().clientConnected() && second.network().clientConnected(),
                    "duplicate WELCOME demoted client readiness");
            require(first.network().clientSyncTransitions() == firstTransitions
                    && second.network().clientSyncTransitions() == secondTransitions,
                    "duplicate WELCOME restarted authoritative client synchronization");
            require(harness.serverNetwork.serverInitialSyncBuilds(first.playerId()) == 1
                    && harness.serverNetwork.serverInitialSyncBuilds(second.playerId()) == 1,
                    "duplicate JOIN/RESUME rebuilt full initial state");
            require(harness.serverNetwork.connectionIdForPlayer(first.playerId()).equals(firstServerId)
                    && harness.serverNetwork.connectionIdForPlayer(second.playerId()).equals(secondServerId)
                    && first.network().clientConnectionId().equals(firstClientId),
                    "same-generation duplicate handshake caused connection churn");

            // Force an actual TCP drop via the fault proxy; proof-based recovery
            // must retain the same owner, existing fleet and other client's socket.
            String restoredPlayerId = first.playerId();
            proxy.dropActiveConnection();
            harness.await(() -> !first.network().clientConnectionId().equals(firstClientId)
                    && first.network().clientConnected()
                    && harness.serverNetwork.serverConnectionReady(restoredPlayerId), 30_000,
                    "client did not become READY again after a forced TCP disconnect");
            require(restoredPlayerId.equals(first.playerId()),
                    "RESUME assigned a different player identity");
            require(harness.serverWorld.hasLiveAssets(restoredPlayerId),
                    "recovered owner lost authoritative fleet/assets");
            require(harness.serverNetwork.serverConnectionReady(second.playerId())
                    && second.network().clientConnected()
                    && harness.serverNetwork.connectionIdForPlayer(second.playerId()).equals(secondServerId),
                    "reconnect disrupted healthy peer");
            assertStarter(harness.serverWorld, restoredPlayerId);
            require(harness.serverNetwork.persistentPlayerSessions().size() == 2,
                    "reconnect created duplicate persistent player identities");
            System.out.println("StarChem TCP JOIN lifecycle validation passed: two READY clients, "
                    + "15 Prospectors and T1 radar, duplicate JOIN/RESUME/WELCOME, "
                    + "single initial sync, forced reconnect, owner and fleet preservation.");
        }
    }

    private static void assertStarter(World world, String playerId) {
        require(world.startingFleetSettings().prospectorCount() == 15
                        && world.startingFleetSettings().tierOneRadar(),
                "dedicated campaign does not have the 15-Prospector/T1 starter policy");
        String priorSystem = world.activeSystemId();
        try {
            world.activateSystem(world.playerHomeSystemId(playerId));
            long miners = world.units.values().stream()
                    .filter(unit -> playerId.equals(unit.playerId)
                            && Rules.STARTING_SHIP.equals(unit.shipTypeId)).count();
            long radars = world.bases.values().stream()
                    .filter(base -> playerId.equals(base.playerId)
                            && RadarTowerRules.TIER_ONE.equals(base.typeId)).count();
            require(miners == 15, "expected 15 Prospectors for " + playerId + ", found " + miners);
            require(radars == 1, "expected one Tier-1 radar for " + playerId + ", found " + radars);
        } finally {
            world.activateSystem(priorSystem);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
