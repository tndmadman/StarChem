package com.tndmadman.rts;

import java.net.InetAddress;

/** Exercises abrupt socket loss and the complete automatic RESUME handshake through PeerNetwork. */
public final class TcpReconnectIntegrationValidator {
    private TcpReconnectIntegrationValidator() { }

    public static void main(String[] args) throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
             TcpFaultProxy proxy = new TcpFaultProxy(InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            TcpIntegrationHarness.TestClient reconnecting = harness.addProxiedClient("TCP Reconnect", proxy);
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

            System.out.println("StarChem TCP automatic reconnect validation passed.");
        }
    }
}
