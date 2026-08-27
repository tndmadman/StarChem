package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class ObserverSessionValidator {
    private static final String TEST_SERVER_FINGERPRINT = "44".repeat(32);

    private ObserverSessionValidator() { }

    public static void main(String[] args) throws Exception {
        validateServerAuthorityAndPersistence();
        validateVisibilityPolicies();
        validateClientPresentation();
        System.out.println("StarChem observer session validation passed.");
    }

    private static void validateServerAuthorityAndPersistence() throws Exception {
        Path saveDir = Files.createTempDirectory("starchem-observer-validator-");
        String saveName = "observer-validator";
        Files.writeString(saveDir.resolve(saveName + "-observers.json"),
                "{\"version\":1,\"enabled\":true,\"maxObservers\":2,\"invitations\":[],\"grants\":[]}");
        Config config = Config.parse(new String[]{"--server", "50000", "--save-dir", saveDir.toString(), "--save-name", saveName});
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport transport = PeerTransport.server(0, new PerfStats());
        transport.start();
        try (Socket socket = connect(loopback, transport.localPort())) {
            waitConnection(transport, loopback, socket.getLocalPort());
            World world = new World("Observer Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", "Observer Host", 0x50BEFF);
            ObserverSessions.configure(config, world);
            PeerServerSide server = new PeerServerSide(config, world, transport);
            ConnectionId connectionId = transport.connectionId(loopback, socket.getLocalPort());
            String welcome = register(server, connectionId, loopback, socket.getLocalPort(),
                    "Converted Observer", "validator-password", socket);
            require(welcome.startsWith("WELCOME|P1|"), "normal authenticated session did not register as P1");
            require(world.hasLiveAssets("P1"), "normal player did not receive gameplay assets before conversion");
            world.completeResearch("P1", "observer-conversion-marker");

            require(ObserverSessions.convertConnectedPlayer(server, connectionId, "P1"),
                    "authenticated defeated-player conversion was rejected");
            String observerState = receivePayload(socket, "OBSERVER_STATE|");
            require(observerState.contains("MODE|PUBLIC"), "conversion did not publish PUBLIC observer state");
            require(ObserverSessions.isObserver(world, "P1"), "converted identity was not server-authoritative observer");
            require(!world.hasLiveAssets("P1"), "observer conversion retained gameplay assets");
            require(!world.hasResearch("P1", "observer-conversion-marker"), "observer conversion retained private research");
            require(ObserverSessions.normalPlayerSessionCount(server) == 0,
                    "observer identity still consumed the normal player-session count");
            require(!server.devAllowed(connectionId, "P1"), "observer retained developer authority");

            require(ObserverSessions.handleObserverControl(server, new String[]{"MOVE", "P1", "1", "20", "20"}, connectionId),
                    "observer gameplay mutation was not intercepted server-side");
            String denied = receivePayload(socket, "OBSERVER_DENIED|");
            require(denied.contains("READ_ONLY|MOVE"), "observer mutation rejection was not explicit");
            require(!ObserverSessions.handleObserverControl(server,
                            new String[]{"VIEW_SYSTEM", "P1", world.activeSystemId(), "1"}, connectionId),
                    "observer read-only system-view command was incorrectly blocked");
            require(ObserverSessions.prepareResume(server, connectionId, "P1"),
                    "retained observer grant was not reconnect eligible");

            World restored = new World("Observer Validator Restore", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(restored);
            PlayerRegistry.reset("SOLO", "Observer Host", 0x50BEFF);
            ObserverSessions.configure(config, restored);
            require(ObserverSessions.isObserver(restored, "P1"),
                    "observer grant did not survive server-side permission reload");
        } finally {
            transport.shutdown();
            deleteTree(saveDir);
        }
    }

    private static void validateVisibilityPolicies() throws Exception {
        World world = new World("Observer Visibility", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Observer Host", 0x50BEFF);
        ObserverSessions.configure(null, world);
        PlayerRegistry.register("P2", "Alpha", 0x55CCFF, false);
        PlayerRegistry.register("P3", "Bravo", 0xFF8855, false);
        Unit alpha = new Unit("P2", 2, "prospector", 100, 100);
        Unit bravo = new Unit("P3", 3, "prospector", 100_000, 100_000);
        world.units.put(alpha.key(), alpha);
        world.units.put(bravo.key(), bravo);
        world.completeResearch("P2", "advanced_industry");
        Snapshot source = WorldNetAccess.snapshot(world, 77);

        setGrant(world, new ObserverSessions.Grant("P9", "Observer", ObserverSessions.VisibilityMode.FULL, ""));
        Snapshot full = ObserverSessions.sanitizeSnapshot(world, "P9", source);
        require(hasUnit(full, "P2") && hasUnit(full, "P3"), "FULL observer did not receive full tactical visibility");
        require(full.stocks().isEmpty(), "FULL observer received mutable/private stockpile authority state");
        require(!full.research().isEmpty(), "FULL observer unexpectedly lost full-information research view");

        setGrant(world, new ObserverSessions.Grant("P9", "Observer", ObserverSessions.VisibilityMode.PLAYER_FOLLOW, "P3"));
        Snapshot follow = ObserverSessions.sanitizeSnapshot(world, "P9", source);
        require(hasUnit(follow, "P3"), "PLAYER_FOLLOW observer did not receive followed player's own fleet");
        require(!hasUnit(follow, "P2"), "PLAYER_FOLLOW observer leaked a distant non-visible fleet");
        require(follow.research().isEmpty(), "PLAYER_FOLLOW observer leaked private research");
        require(follow.stocks().isEmpty(), "PLAYER_FOLLOW observer leaked stockpile state");

        setGrant(world, new ObserverSessions.Grant("P9", "Observer", ObserverSessions.VisibilityMode.PUBLIC, ""));
        Snapshot publicView = ObserverSessions.sanitizeSnapshot(world, "P9", source);
        require(hasUnit(publicView, "P2"), "PUBLIC observer did not use the deterministic public anchor");
        require(!hasUnit(publicView, "P3"), "PUBLIC observer leaked a distant hidden fleet");
        require(publicView.research().isEmpty(), "PUBLIC observer leaked private research");

        GalaxyMapSnapshot publicMap = ObserverSessions.emptyPublicGalaxy(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId());
        for (GalaxyMapSystem system : publicMap.systems()) {
            require(system.ships() == 0 && system.bases() == 0 && system.resources() == 0,
                    "PUBLIC galaxy map leaked strategic object counts");
            require(system.controllerId().isBlank(), "PUBLIC galaxy map leaked controller identity");
        }
    }

    private static void validateClientPresentation() {
        World world = new World("Observer Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("WAIT", "Observer Client", 0x50BEFF);
        require(ObserverSessions.applyClientState(world, "OBSERVER_STATE|1|MODE|PLAYER_FOLLOW|FOLLOW|P2"),
                "client rejected valid observer state");
        require(ObserverSessions.clientObserver(world), "client observer state was not retained");
        require(ObserverSessions.clientMode(world) == ObserverSessions.VisibilityMode.PLAYER_FOLLOW,
                "client observer visibility mode was not retained");
        require("P2".equals(ObserverSessions.clientFollow(world)), "client observer follow target was not retained");
        require(ObserverSessions.clientWatermark(world).contains("PLAYER FOLLOW"),
                "observer watermark did not identify the active visibility policy");

        BufferedImage image = new BufferedImage(900, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        new LeaderboardHud().draw(graphics, world, image.getWidth());
        graphics.dispose();
        require(changedPixels(image, 250, 8, 650, 55) > 100,
                "permanent observer watermark was not rendered in the center HUD region");

        ObserverSessions.clearClient(world);
        require(!ObserverSessions.clientObserver(world) && ObserverSessions.clientWatermark(world).isBlank(),
                "observer client state did not clear on session shutdown");
    }

    @SuppressWarnings("unchecked")
    private static void setGrant(World world, ObserverSessions.Grant grant) throws Exception {
        Field statesField = ObserverSessions.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        Map<World, Object> states = (Map<World, Object>) statesField.get(null);
        Object state = states.get(world);
        require(state != null, "observer server state was not configured");
        Field grantsField = state.getClass().getDeclaredField("grants");
        grantsField.setAccessible(true);
        Map<String, ObserverSessions.Grant> grants = (Map<String, ObserverSessions.Grant>) grantsField.get(state);
        grants.put(grant.playerId(), grant);
    }

    private static boolean hasUnit(Snapshot snapshot, String playerId) {
        for (UnitState state : snapshot.units()) if (playerId.equals(state.playerId())) return true;
        return false;
    }

    private static int changedPixels(BufferedImage image, int x1, int y1, int x2, int y2) {
        int count = 0;
        for (int y = Math.max(0, y1); y < Math.min(image.getHeight(), y2); y++) {
            for (int x = Math.max(0, x1); x < Math.min(image.getWidth(), x2); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) count++;
            }
        }
        return count;
    }

    private static String register(PeerServerSide server, ConnectionId connectionId, InetAddress address,
                                   int port, String name, String password, Socket socket) throws Exception {
        server.join(connectionId, address, port, name, false, "");
        String required = receivePayload(socket, "AUTH_REQUIRED|");
        String[] parts = required.split("\\|", -1);
        require(parts.length == 3 && PasswordAuth.decodeHex(parts[2]).length == 16,
                "server did not issue an observer-test registration salt");
        String verifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT,
                PasswordAuth.decodeHex(parts[2]));
        server.join(connectionId, address, port, name, verifier, false, "");
        return receivePayload(socket, "WELCOME|");
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
        require(transport.hasConnection(address, port), "observer validator TCP connection was not registered");
    }

    private static String receivePayload(Socket socket, String prefix) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        for (int attempt = 0; attempt < 300; attempt++) {
            TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(input);
            if (frame == null) break;
            if (frame.message().startsWith(prefix)) return frame.message();
        }
        throw new IllegalStateException("Did not receive TCP frame starting with " + prefix);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
