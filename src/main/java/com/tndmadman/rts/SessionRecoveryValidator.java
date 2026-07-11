package com.tndmadman.rts;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class SessionRecoveryValidator {
    private SessionRecoveryValidator() { }

    public static void main(String[] args) throws Exception {
        validateServerSessionRecovery();
        validateClientReconnectStateAndPersistence();
        System.out.println("StarChem session recovery validation passed.");
    }

    private static void validateServerSessionRecovery() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket serverSocket = new DatagramSocket(0, loopback);
             DatagramSocket firstClient = new DatagramSocket(0, loopback);
             DatagramSocket reboundClient = new DatagramSocket(0, loopback);
             DatagramSocket restartedClient = new DatagramSocket(0, loopback)) {
            firstClient.setSoTimeout(1500);
            reboundClient.setSoTimeout(1500);
            restartedClient.setSoTimeout(1500);

            Config config = Config.host("Session Host", serverSocket.getLocalPort(), false);
            World world = new World("Session Host", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", "Session Host", 0x50BEFF);
            PeerTransport transport = new PeerTransport(serverSocket);
            PeerServerSide server = new PeerServerSide(config, world, transport);

            String firstEndpoint = server.endpoint(loopback, firstClient.getLocalPort());
            server.join(firstEndpoint, loopback, firstClient.getLocalPort(), "Recovery Client", false, "");
            String firstWelcome = receivePayload(firstClient, "WELCOME|");
            String firstToken = markerValue(firstWelcome, "SESSION");
            require(validToken(firstToken), "initial join did not issue a session token");
            require(server.owns(firstEndpoint, "P1"), "initial endpoint did not own P1");
            require(world.hasLiveAssets("P1"), "initial join did not create P1 assets");
            world.completeResearch("P1", "session-recovery-marker");

            String reboundEndpoint = server.endpoint(loopback, reboundClient.getLocalPort());
            require(!server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "a second endpoint displaced an active player session");
            String busyResponse = receivePayload(reboundClient, "SESSION_BUSY|");
            require(busyResponse.contains("already active"), "active-session rejection did not explain the conflict");
            require(server.owns(firstEndpoint, "P1"), "active endpoint lost ownership after takeover attempt");
            require(!server.owns(reboundEndpoint, "P1"), "takeover endpoint gained ownership of an active session");
            require(world.hasLiveAssets("P1"), "takeover attempt changed P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "takeover attempt changed P1 research");

            long timeoutNow = System.currentTimeMillis() + 10_000;
            server.tick(timeoutNow);
            require(!server.owns(firstEndpoint, "P1"), "timed-out endpoint remained connected");
            require(world.hasLiveAssets("P1"), "timeout deleted P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "timeout deleted P1 research");

            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "valid session could not rebind to a new UDP endpoint");
            String reboundWelcome = receivePayload(reboundClient, "WELCOME|");
            String reboundToken = markerValue(reboundWelcome, "SESSION");
            require(validToken(reboundToken) && !reboundToken.equals(firstToken), "resume token was not rotated");
            require(server.owns(reboundEndpoint, "P1"), "rebound endpoint did not own P1");
            require(!server.owns(firstEndpoint, "P1"), "old endpoint retained P1 ownership after rebind");

            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "duplicate resume retry was not idempotent");
            String retryWelcome = receivePayload(reboundClient, "WELCOME|");
            require(reboundToken.equals(markerValue(retryWelcome, "SESSION")),
                    "duplicate resume retry changed the active token again");

            require(!server.resume(firstEndpoint, loopback, firstClient.getLocalPort(), "P1", firstToken, false, ""),
                    "stale endpoint reclaimed the session with an old token");
            require(server.owns(reboundEndpoint, "P1"), "stale resume attempt displaced the valid endpoint");

            server.removePeer(reboundEndpoint);
            require(!server.owns(reboundEndpoint, "P1"), "explicit leave kept the endpoint connected");
            require(world.hasLiveAssets("P1"), "explicit leave deleted P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "explicit leave deleted P1 research");

            String restartedEndpoint = server.endpoint(loopback, restartedClient.getLocalPort());
            require(server.resume(restartedEndpoint, loopback, restartedClient.getLocalPort(), "P1", reboundToken, false, ""),
                    "saved session could not resume after a client restart");
            String restartedWelcome = receivePayload(restartedClient, "WELCOME|");
            String restartedToken = markerValue(restartedWelcome, "SESSION");
            require(validToken(restartedToken), "client restart did not receive a replacement token");
            require(world.hasLiveAssets("P1"), "client restart changed P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "client restart changed P1 research");

            server.removePeer(restartedEndpoint);
            server.tick(System.currentTimeMillis() + PeerServerSide.disconnectGraceMs() + 1000);
            require(!world.hasLiveAssets("P1"), "expired session did not remove P1 assets");
            require(!world.hasResearch("P1", "session-recovery-marker"), "expired session did not remove P1 research");
            transport.shutdown();
        }
    }

    private static void validateClientReconnectStateAndPersistence() throws Exception {
        Path store = Files.createTempFile("starchem-session-validator-", ".properties");
        Files.deleteIfExists(store);
        System.setProperty("starchem.sessionStore", store.toString());
        Config config = Config.join("Recovery Client", "127.0.0.1", 50000, false);
        String firstToken = "A".repeat(43);
        String rotatedToken = "B".repeat(43);
        try {
            SessionTokenStore.save(config, "P9", firstToken);
            SessionTokenStore.StoredSession stored = SessionTokenStore.load(config);
            require(stored.valid() && "P9".equals(stored.playerId()) && firstToken.equals(stored.token()),
                    "session token store did not round-trip the saved identity");

            InetAddress loopback = InetAddress.getLoopbackAddress();
            try (DatagramSocket firstSocket = new DatagramSocket(0, loopback);
                 DatagramSocket secondSocket = new DatagramSocket(0, loopback)) {
                World firstWorld = new World("Recovery Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
                PlayerRegistry.activate(firstWorld);
                PlayerRegistry.reset("WAIT", "Recovery Client", 0x50BEFF);
                PeerTransport firstTransport = new PeerTransport(firstSocket);
                PeerClientSide firstClient = new PeerClientSide(config, firstWorld, firstTransport);
                require(firstClient.statusLine().contains("reconnecting P9"),
                        "client restart did not begin in reconnecting state");

                firstClient.readWelcome(welcomeParts(firstWorld, "P9", firstToken));
                require(firstClient.statusLine().contains("CLIENT P9"), "WELCOME did not mark the client connected");
                firstClient.tick(System.currentTimeMillis() + PeerClientSide.serverSilenceMs() + 1000);
                require(firstClient.statusLine().contains("reconnecting P9"),
                        "server silence did not move the client into reconnecting state");
                firstClient.move(new MoveCommand("P9", 1, 10, 10));
                require(firstTransport.pendingCount() == 0, "gameplay command was queued while reconnecting");
                require(firstWorld.status.contains("Command blocked while reconnecting"),
                        "blocked reconnecting command was not reported to the UI");

                firstClient.handle("SESSION_BUSY|Session is already active on another connection.");
                require(firstClient.statusLine().contains("reconnecting P9"),
                        "active-session response stopped the reconnect attempt");
                require(firstWorld.status.contains("Waiting to resume"),
                        "active-session response was not reported to the UI");
                require(firstToken.equals(SessionTokenStore.load(config).token()),
                        "active-session response cleared the saved resume token");

                firstClient.readWelcome(welcomeParts(firstWorld, "P9", rotatedToken));
                require(firstClient.statusLine().contains("CLIENT P9"), "resumed WELCOME did not restore connected state");
                require(rotatedToken.equals(SessionTokenStore.load(config).token()),
                        "rotated resume token was not persisted");

                World secondWorld = new World("Recovery Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
                PlayerRegistry.activate(secondWorld);
                PlayerRegistry.reset("WAIT", "Recovery Client", 0x50BEFF);
                PeerTransport secondTransport = new PeerTransport(secondSocket);
                PeerClientSide restartedClient = new PeerClientSide(config, secondWorld, secondTransport);
                require(restartedClient.statusLine().contains("reconnecting P9"),
                        "new client process did not load the persisted session");
                firstTransport.shutdown();
                secondTransport.shutdown();
            }
        } finally {
            SessionTokenStore.clear(config);
            System.clearProperty("starchem.sessionStore");
            Files.deleteIfExists(store);
        }
    }

    private static String[] welcomeParts(World world, String playerId, String token) {
        return new String[]{
                "WELCOME", playerId, "Recovery Client", Integer.toString(0x50BEFF),
                world.systemId(), Long.toString(world.systemSeed()), "0",
                "DEV", "0", "SESSION", token
        };
    }

    private static String receivePayload(DatagramSocket socket, String prefix) throws Exception {
        byte[] buffer = new byte[PacketChunks.MAX_DATAGRAM_BYTES + 1];
        for (int attempt = 0; attempt < 200; attempt++) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            String payload = raw;
            if (raw.startsWith("REL|")) {
                String[] reliable = raw.split("\\|", 3);
                if (reliable.length < 3) continue;
                payload = reliable[2];
            }
            if (payload.startsWith(prefix)) return payload;
        }
        throw new IllegalStateException("Did not receive packet starting with " + prefix);
    }

    private static String markerValue(String message, String marker) {
        if (message == null || marker == null) return "";
        String[] parts = message.split("\\|", -1);
        for (int i = 0; i + 1 < parts.length; i++) if (marker.equals(parts[i])) return parts[i + 1];
        return "";
    }

    private static boolean validToken(String token) {
        return token != null && token.length() >= 32 && token.length() <= 256;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
