package com.tndmadman.rts;

import java.io.DataInputStream;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class SessionRecoveryValidator {
    private SessionRecoveryValidator() { }

    public static void main(String[] args) throws Exception {
        validateServerSessionRecovery();
        validateClientReconnectStateAndPersistence();
        System.out.println("StarChem TCP session recovery validation passed.");
    }

    private static void validateServerSessionRecovery() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport transport = PeerTransport.server(0, new PerfStats());
        transport.start();
        try (Socket firstClient = connect(loopback, transport.localPort());
             Socket reboundClient = connect(loopback, transport.localPort());
             Socket restartedClient = connect(loopback, transport.localPort())) {
            waitConnection(transport, loopback, firstClient.getLocalPort());
            waitConnection(transport, loopback, reboundClient.getLocalPort());
            waitConnection(transport, loopback, restartedClient.getLocalPort());

            Config config = Config.host("Session Host", transport.localPort(), false);
            World world = new World("Session Host", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", "Session Host", 0x50BEFF);
            PeerServerSide server = new PeerServerSide(config, world, transport);

            ConnectionId firstEndpoint = transport.connectionId(loopback, firstClient.getLocalPort());
            server.join(firstEndpoint, loopback, firstClient.getLocalPort(), "Recovery Client", false, "");
            String firstWelcome = receivePayload(firstClient, "WELCOME|");
            String firstToken = markerValue(firstWelcome, "SESSION");
            require(validToken(firstToken), "initial join did not issue a session token");
            require(server.owns(firstEndpoint, "P1"), "initial TCP connection did not own P1");
            require(world.hasLiveAssets("P1"), "initial join did not create P1 assets");
            world.completeResearch("P1", "session-recovery-marker");

            ConnectionId reboundEndpoint = transport.connectionId(loopback, reboundClient.getLocalPort());
            require(!server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "a second TCP connection displaced an active player session");
            String busyResponse = receivePayload(reboundClient, "SESSION_BUSY|");
            require(busyResponse.contains("already active"), "active-session rejection did not explain the conflict");
            require(server.owns(firstEndpoint, "P1"), "active connection lost ownership after takeover attempt");
            require(!server.owns(reboundEndpoint, "P1"), "takeover connection gained ownership of an active session");
            require(world.hasLiveAssets("P1"), "takeover attempt changed P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "takeover attempt changed P1 research");

            long timeoutNow = System.currentTimeMillis() + 10_000;
            server.tick(timeoutNow);
            require(!server.owns(firstEndpoint, "P1"), "timed-out TCP connection remained connected");
            require(world.hasLiveAssets("P1"), "timeout deleted P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "timeout deleted P1 research");
            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "valid session could not rebind to a new TCP connection");
            String reboundWelcome = receivePayload(reboundClient, "WELCOME|");
            String reboundToken = markerValue(reboundWelcome, "SESSION");
            require(validToken(reboundToken) && !reboundToken.equals(firstToken), "resume token was not rotated");
            require(server.owns(reboundEndpoint, "P1"), "rebound connection did not own P1");
            require(!server.owns(firstEndpoint, "P1"), "old connection retained P1 ownership after rebind");

            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "duplicate resume retry was not idempotent");
            String retryWelcome = receivePayload(reboundClient, "WELCOME|");
            require(reboundToken.equals(markerValue(retryWelcome, "SESSION")),
                    "duplicate resume retry changed the active token again");

            require(!server.resume(firstEndpoint, loopback, firstClient.getLocalPort(), "P1", firstToken, false, ""),
                    "stale connection reclaimed the session with an old token");
            require(server.owns(reboundEndpoint, "P1"), "stale resume attempt displaced the valid connection");

            server.removePeer(reboundEndpoint);
            require(!server.owns(reboundEndpoint, "P1"), "explicit leave kept the connection active");
            require(world.hasLiveAssets("P1"), "explicit leave deleted P1 assets");
            require(world.hasResearch("P1", "session-recovery-marker"), "explicit leave deleted P1 research");

            ConnectionId restartedEndpoint = transport.connectionId(loopback, restartedClient.getLocalPort());
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
        } finally {
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

            World firstWorld = new World("Recovery Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(firstWorld);
            PlayerRegistry.reset("WAIT", "Recovery Client", 0x50BEFF);
            PeerTransport firstTransport = PeerTransport.client(config.serverAddress, new PerfStats());
            PeerClientSide firstClient = new PeerClientSide(config, firstWorld, firstTransport);
            require(firstClient.statusLine().contains("reconnecting P9"),
                    "client restart did not begin in reconnecting state");

            firstClient.readWelcome(welcomeParts(firstWorld, "P9", firstToken));
            require(firstClient.statusLine().contains("syncing P9"),
                    "WELCOME skipped the authoritative initial-sync state");
            require(!firstClient.readyState(), "WELCOME marked the client ready before initial state arrived");
            applyInitialSync(firstClient, firstWorld, "P9", 1);
            require(firstClient.statusLine().contains("CLIENT P9"),
                    "initial authoritative snapshot did not mark the client connected");

            firstClient.tick(System.currentTimeMillis() + PeerClientSide.serverSilenceMs() + 1000);
            require(firstClient.statusLine().contains("reconnecting P9"),
                    "server silence did not move the client into reconnecting state");
            firstClient.move(new MoveCommand("P9", 1, 10, 10));
            require(firstTransport.queuedCount() == 0, "gameplay command was queued while reconnecting");
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
            require(firstClient.statusLine().contains("syncing P9"),
                    "resumed WELCOME skipped authoritative resynchronization");
            require(!firstClient.readyState(), "resumed WELCOME restored readiness before state arrived");
            applyInitialSync(firstClient, firstWorld, "P9", 2);
            require(firstClient.statusLine().contains("CLIENT P9"),
                    "resumed authoritative snapshot did not restore connected state");
            require(rotatedToken.equals(SessionTokenStore.load(config).token()),
                    "rotated resume token was not persisted");

            World secondWorld = new World("Recovery Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(secondWorld);
            PlayerRegistry.reset("WAIT", "Recovery Client", 0x50BEFF);
            PeerTransport secondTransport = PeerTransport.client(config.serverAddress, new PerfStats());
            PeerClientSide restartedClient = new PeerClientSide(config, secondWorld, secondTransport);
            require(restartedClient.statusLine().contains("reconnecting P9"),
                    "new client process did not load the persisted session");
            firstTransport.shutdown();
            secondTransport.shutdown();
        } finally {
            SessionTokenStore.clear(config);
            System.clearProperty("starchem.sessionStore");
            Files.deleteIfExists(store);
        }
    }

    private static void applyInitialSync(PeerClientSide client, World world, String playerId, long sequence) {
        PlayerRegistry.activate(world);
        if (!world.hasLiveAssets(playerId)) WorldNetAccess.respawnPlayer(world, playerId);
        String initial = SyncPacketBuilder.build(world, new ClientViewCache(), playerId, sequence, SyncKind.INITIAL);
        client.readFullView(initial);
        require(client.readyState(), "client did not become ready after authoritative initial state was applied");
    }

    private static Socket connect(InetAddress address, int port) throws Exception {
        Socket socket = new Socket(address, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!transport.hasConnection(address, port) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        require(transport.hasConnection(address, port), "TCP connection was not registered");
    }

    private static String[] welcomeParts(World world, String playerId, String token) {
        return new String[]{
                "WELCOME", playerId, "Recovery Client", Integer.toString(0x50BEFF),
                world.systemId(), Long.toString(world.systemSeed()), "0",
                "DEV", "0", "SESSION", token
        };
    }

    private static String receivePayload(Socket socket, String prefix) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        for (int attempt = 0; attempt < 200; attempt++) {
            TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(input);
            if (frame == null) break;
            if (frame.message().startsWith(prefix)) return frame.message();
        }
        throw new IllegalStateException("Did not receive TCP frame starting with " + prefix);
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