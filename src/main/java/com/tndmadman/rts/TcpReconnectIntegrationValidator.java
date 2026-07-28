package com.tndmadman.rts;

import java.net.InetAddress;

/** Exercises abrupt socket loss and the complete automatic RESUME handshake through PeerNetwork. */
public final class TcpReconnectIntegrationValidator {
    private static final String VALIDATOR_PASSWORD = "validator-password";
    private static final String REMOTE_RADAR_ID = "REMOTE-RADAR-900101";

    private TcpReconnectIntegrationValidator() { }

    public static void main(String[] args) throws Exception {
        validateNormalReconnect();
        validateRemoteViewReconnect();
        System.out.println("StarChem TCP automatic reconnect validation passed.");
    }

    private static void validateNormalReconnect() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
             TcpFaultProxy proxy = new TcpFaultProxy(InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            TcpIntegrationHarness.TestClient reconnecting = harness.addProxiedClient("TCP Reconnect", proxy);
            rememberForRestart(reconnecting.config());
            TcpIntegrationHarness.TestClient observer = harness.addClient("TCP Observer");
            harness.awaitJoined(reconnecting);
            harness.awaitJoined(observer);

            String playerId = reconnecting.playerId();
            Unit unit = harness.firstUnit(harness.serverWorld, playerId);
            TcpIntegrationHarness.require(unit != null, "reconnecting player had no authoritative unit");
            String tokenBefore = SessionTokenStore.load(reconnecting.config()).token();
            ConnectionId connectionBefore = harness.serverNetwork.connectionIdForPlayer(playerId);
            TcpIntegrationHarness.require(connectionBefore.valid(), "initial server connection ID was missing");

            proxy.dropActiveConnection();
            harness.await(() -> reconnecting.network().clientReconnecting(), 5_000,
                    "client did not enter reconnecting state after real socket loss");
            reconnecting.network().move(new MoveCommand(playerId, unit.unitId, unit.x + 10, unit.y));
            TcpIntegrationHarness.require(reconnecting.world().status.contains("Command blocked while reconnecting"),
                    "client accepted gameplay commands while reconnecting");

            harness.await(() -> !harness.serverNetwork.serverSessionConnected(playerId), 5_000,
                    "server did not detach the closed TCP connection");
            TcpIntegrationHarness.require(harness.serverWorld.hasLiveAssets(playerId),
                    "server removed player state instead of retaining the resumable session");

            harness.await(() -> reconnecting.network().clientConnected()
                            && playerId.equals(reconnecting.playerId())
                            && harness.serverNetwork.serverSessionConnected(playerId),
                    15_000, "automatic full-path TCP RESUME did not complete");

            String tokenAfter = SessionTokenStore.load(reconnecting.config()).token();
            ConnectionId connectionAfter = harness.serverNetwork.connectionIdForPlayer(playerId);
            TcpIntegrationHarness.require(connectionAfter.valid() && !connectionAfter.equals(connectionBefore),
                    "reconnect did not attach a new connection ID");
            TcpIntegrationHarness.require(!tokenAfter.isBlank() && !tokenAfter.equals(tokenBefore),
                    "successful RESUME did not rotate and persist the session token");
            TcpIntegrationHarness.require(observer.network().clientConnected(), "observer was affected by another client's reconnect");

            Unit resumed = harness.unit(harness.serverWorld, playerId, unit.unitId);
            double targetX = resumed.x + 55;
            reconnecting.network().move(new MoveCommand(playerId, resumed.unitId, targetX, resumed.y));
            harness.await(() -> {
                Unit current = harness.unit(harness.serverWorld, playerId, resumed.unitId);
                return current != null && Math.abs(current.targetX - Double.parseDouble(Calc.round(targetX))) < 0.001;
            }, 5_000, "post-resume command did not reach the authoritative server");
        }
    }

    private static void validateRemoteViewReconnect() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
             TcpFaultProxy proxy = new TcpFaultProxy(InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            TcpIntegrationHarness.TestClient reconnecting = harness.addProxiedClient("TCP Remote View Reconnect", proxy);
            TcpIntegrationHarness.TestClient observer = harness.addClient("TCP Remote View Observer");
            harness.awaitJoined(reconnecting);
            harness.awaitJoined(observer);

            String playerId = reconnecting.playerId();
            String remoteSystem = StarSystems.CORSAIR_SYSTEM_ID;
            placeRemoteRadar(harness.serverWorld, playerId, remoteSystem);
            reconnecting.network().viewSystem(playerId, remoteSystem);
            harness.await(() -> !reconnecting.network().clientViewSwitchPending()
                            && remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    12_000, "client did not establish an authorized remote view before reconnect validation");

            removeRemoteRadar(harness.serverWorld, playerId, remoteSystem);
            harness.await(() -> remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && !currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    8_000, "remote view did not remain available after the discovery radar was removed");

            proxy.dropActiveConnection();
            harness.await(() -> reconnecting.network().clientReconnecting(), 5_000,
                    "remote-view client did not begin reconnecting after socket loss");
            harness.await(() -> !harness.serverNetwork.serverSessionConnected(playerId), 5_000,
                    "server did not detach the remote-view client connection");
            harness.await(() -> reconnecting.network().clientConnected()
                            && harness.serverNetwork.serverSessionConnected(playerId)
                            && remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && !currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    15_000, "resumed session did not restore its authoritative remote view");

            long resumedSequence = reconnecting.network().clientSnapshotSequence();
            harness.await(() -> reconnecting.network().clientSnapshotSequence() > resumedSequence
                            && remoteSystem.equals(reconnecting.network().clientViewedSystemId())
                            && remoteSystem.equals(reconnecting.world().activeSystemId())
                            && !currentSystemHasPlayerAssets(reconnecting.world(), playerId),
                    8_000, "remote view did not remain synchronized after session resume");
            TcpIntegrationHarness.require(observer.network().clientConnected(),
                    "observer was affected by remote-view reconnect");
        }
    }

    private static void placeRemoteRadar(World world, String playerId, String systemId) {
        String previousSystem = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            world.bases.put(REMOTE_RADAR_ID, new Base(REMOTE_RADAR_ID, playerId, RadarTowerRules.TIER_ONE,
                    world.width * 0.5, world.height * 0.5));
            world.saveActiveSystem();
        } finally {
            world.activateSystem(previousSystem);
        }
    }

    private static void removeRemoteRadar(World world, String playerId, String systemId) {
        String previousSystem = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            Base radar = world.bases.get(REMOTE_RADAR_ID);
            if (radar != null && playerId.equals(radar.playerId)) world.bases.remove(REMOTE_RADAR_ID);
            world.saveActiveSystem();
        } finally {
            world.activateSystem(previousSystem);
        }
    }

    private static void rememberForRestart(Config config) {
        char[] password = VALIDATOR_PASSWORD.toCharArray();
        try {
            PendingPlayerPassword.remember(config, password, true);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static boolean currentSystemHasPlayerAssets(World world, String playerId) {
        for (Unit unit : world.units.values()) {
            if (playerId.equals(unit.playerId) && unit.hp > 0) return true;
        }
        for (Base base : world.bases.values()) {
            if (playerId.equals(base.playerId) && base.hp > 0) return true;
        }
        return false;
    }
}
