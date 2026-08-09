package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Real TCP and bounded-state regression coverage for issue #295. */
public final class Issue295MultiplayerCommsValidator {
    private Issue295MultiplayerCommsValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        System.out.println("StarChem issue #295 multiplayer communications validation passed.");
    }

    static void validateOrThrow() throws Exception {
        validateTextSafetyAndBindings();
        validateRealTcpComms();
    }

    private static void validateTextSafetyAndBindings() {
        String unsafe = "  alpha\u202Eevil\u001B[31m\n beta  ";
        String clean = TextSafety.chatText(unsafe, 64);
        require("alphaevil[31m beta".equals(clean),
                "chat normalization retained bidi/control/newline content: " + TextSafety.terminal(clean));
        String bounded = TextSafety.chatText("🙂".repeat(MultiplayerComms.MAX_CHAT_CODE_POINTS + 25),
                MultiplayerComms.MAX_CHAT_CODE_POINTS);
        require(bounded.codePointCount(0, bounded.length()) == MultiplayerComms.MAX_CHAT_CODE_POINTS,
                "chat length was not bounded by code points");
        require(!TextSafety.containsUnsafeTerminalText(TextSafety.terminal("safe\u001B[2J")),
                "terminal escaping retained unsafe controls");
        GameSettings settings = GameSettings.forTest();
        require(settings.binding("chat_open") != null
                        && settings.binding("chat_open").keyCode() == java.awt.event.KeyEvent.VK_ENTER,
                "chat-open binding is missing or not Enter by default");
    }

    private static void validateRealTcpComms() throws Exception {
        Path sessionStore = Files.createTempFile("starchem-issue295-session-", ".properties");
        Files.deleteIfExists(sessionStore);
        Path saveDir = Files.createTempDirectory("starchem-issue295-server-");
        System.setProperty("starchem.sessionStore", sessionStore.toString());

        PeerNetwork server = null;
        PeerNetwork first = null;
        PeerNetwork second = null;
        World firstWorld = null;
        World secondWorld = null;
        try {
            int port = freePort();
            Config serverConfig = Config.dedicatedServer("Comms Validation Server", port, false, false,
                    Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "", 1, saveDir, "issue295", 0, 2, false);
            World serverWorld = new World(serverConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            server = PeerNetwork.start(serverConfig, serverWorld);

            Config firstConfig = Config.join("Comms Alpha", "127.0.0.1", port, false);
            PendingPlayerPassword.remember(firstConfig, "alpha-validator-password".toCharArray(), false);
            firstWorld = new World(firstConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            first = PeerNetwork.start(firstConfig, firstWorld);

            Config secondConfig = Config.join("Comms Beta", "127.0.0.1", port, false);
            PendingPlayerPassword.remember(secondConfig, "beta-validator-password".toCharArray(), false);
            secondWorld = new World(secondConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            second = PeerNetwork.start(secondConfig, secondWorld);

            final PeerNetwork liveServer = server;
            final PeerNetwork liveFirst = first;
            final PeerNetwork liveSecond = second;
            final World liveFirstWorld = firstWorld;
            final World liveSecondWorld = secondWorld;
            final World liveServerWorld = serverWorld;

            runUntil(liveServer, liveFirst, liveSecond,
                    () -> liveFirst.clientReady() && liveSecond.clientReady()
                            && !"SOLO".equals(liveFirst.localPlayerId())
                            && !"SOLO".equals(liveSecond.localPlayerId())
                            && firstUnit(liveFirstWorld, liveFirst.localPlayerId()) != null
                            && firstUnit(liveSecondWorld, liveSecond.localPlayerId()) != null,
                    15_000, "two clients did not join and receive authoritative state");

            String firstId = liveFirst.localPlayerId();
            String secondId = liveSecond.localPlayerId();

            require(MultiplayerComms.sendChat(liveFirstWorld, MultiplayerComms.ChatChannel.GLOBAL, "",
                    "hello global").sent(), "global chat was rejected locally");
            runUntil(liveServer, liveFirst, liveSecond,
                    () -> containsMessage(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL, "hello global"),
                    4_000, "global chat did not reach another connected player");
            MultiplayerComms.ChatMessage global = lastMessage(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL);
            require(global != null && firstId.equals(global.senderId()),
                    "global sender identity was not supplied by the authenticated connection");

            require(MultiplayerComms.sendChat(liveFirstWorld, MultiplayerComms.ChatChannel.SYSTEM, "",
                    "hello system").sent(), "system chat was rejected locally");
            runUntil(liveServer, liveFirst, liveSecond,
                    () -> containsMessage(liveFirstWorld, MultiplayerComms.ChatChannel.SYSTEM, "hello system"),
                    4_000, "system chat did not return to its same-system sender");

            require(MultiplayerComms.sendChat(liveFirstWorld, MultiplayerComms.ChatChannel.DIRECT, secondId,
                    "hello direct").sent(), "direct chat was rejected locally");
            runUntil(liveServer, liveFirst, liveSecond,
                    () -> containsMessage(liveSecondWorld, MultiplayerComms.ChatChannel.DIRECT, "hello direct"),
                    4_000, "direct chat did not reach its connected recipient");
            MultiplayerComms.ChatMessage direct = lastMessage(liveSecondWorld, MultiplayerComms.ChatChannel.DIRECT);
            require(direct != null && firstId.equals(direct.senderId()) && secondId.equals(direct.directTargetId()),
                    "direct sender/recipient metadata was not authoritative");

            PlayerRegistry.activate(liveServerWorld);
            DiplomacySystem.setRelationship(liveServerWorld, firstId, secondId, DiplomacySystem.Relationship.ALLIED);
            require(MultiplayerComms.sendChat(liveFirstWorld, MultiplayerComms.ChatChannel.TEAM, "",
                    "hello ally").sent(), "team chat was rejected locally");
            runUntil(liveServer, liveFirst, liveSecond,
                    () -> containsMessage(liveSecondWorld, MultiplayerComms.ChatChannel.TEAM, "hello ally"),
                    4_000, "team chat did not follow authoritative diplomacy");

            Unit localUnit = firstUnit(liveFirstWorld, firstId);
            require(localUnit != null, "first client has no local unit for ping validation");
            require(MultiplayerComms.sendWorldPing(liveFirstWorld, MultiplayerComms.TacticalPingType.THREAT,
                    localUnit.x, localUnit.y).sent(), "valid world ping was rejected locally");
            runUntil(liveServer, liveFirst, liveSecond,
                    () -> !MultiplayerComms.pings(liveFirstWorld).isEmpty(),
                    4_000, "world ping did not round-trip to its sender");
            MultiplayerComms.TacticalPing ping = MultiplayerComms.pings(liveFirstWorld).get(0);
            require(ping.type() == MultiplayerComms.TacticalPingType.THREAT
                            && firstId.equals(ping.senderId()) && Double.isFinite(ping.worldX()),
                    "world ping type, sender, or coordinates were corrupted");
            require(!MultiplayerComms.sendWorldPing(liveFirstWorld, MultiplayerComms.TacticalPingType.MOVE,
                    Double.NaN, 1).sent(), "NaN ping coordinate passed client validation");
            require(!MultiplayerComms.sendWorldPing(liveFirstWorld, MultiplayerComms.TacticalPingType.MOVE,
                    Double.POSITIVE_INFINITY, 1).sent(), "infinite ping coordinate passed client validation");

            GalaxyMapSnapshot map = liveFirstWorld.galaxyMapSnapshot();
            String knownSystem = map == null || map.systems() == null || map.systems().isEmpty()
                    ? "" : map.systems().get(0).id();
            if (!knownSystem.isBlank()) {
                require(MultiplayerComms.sendSystemPing(liveFirstWorld, MultiplayerComms.TacticalPingType.ATTENTION,
                        knownSystem).sent(), "known galaxy-system ping was rejected locally");
                runUntil(liveServer, liveFirst, liveSecond,
                        () -> containsSystemPing(liveFirstWorld, knownSystem), 4_000,
                        "galaxy-system ping did not round-trip");
            }

            PeerClientSide firstSide = clientSide(liveFirst);
            String forgedTarget = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("P999".getBytes(StandardCharsets.UTF_8));
            String forgedText = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("spoof probe".getBytes(StandardCharsets.UTF_8));
            firstSide.transport.sendOrdered("CHAT_SEND|GLOBAL|" + forgedTarget + "|" + forgedText,
                    firstSide.config.serverAddress.getAddress(), firstSide.config.serverAddress.getPort());
            runUntil(liveServer, liveFirst, liveSecond,
                    () -> containsMessage(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL, "spoof probe"),
                    4_000, "spoof probe was not processed");
            MultiplayerComms.ChatMessage spoof = lastMessage(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL);
            require(spoof != null && firstId.equals(spoof.senderId()),
                    "client-controlled fields changed the authoritative sender identity");

            int directBefore = MultiplayerComms.messages(liveFirstWorld, MultiplayerComms.ChatChannel.DIRECT).size();
            require(MultiplayerComms.sendChat(liveFirstWorld, MultiplayerComms.ChatChannel.DIRECT, "P999999",
                    "unavailable target").sent(), "unavailable direct target was rejected before server processing");
            runTicks(liveServer, liveFirst, liveSecond, 80, false);
            require(MultiplayerComms.messages(liveFirstWorld, MultiplayerComms.ChatChannel.DIRECT).size() == directBefore,
                    "unavailable direct recipient created a message or identity leak");
            require(liveFirstWorld.status.contains("Direct recipient is unavailable")
                            || liveFirstWorld.status.contains("COMMS"),
                    "unavailable direct recipient did not produce generic feedback");

            int beforeFlood = MultiplayerComms.messages(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL).size();
            double simBefore = liveServerWorld.systemTime;
            for (int i = 0; i < 40; i++) {
                MultiplayerComms.sendChat(liveFirstWorld, MultiplayerComms.ChatChannel.GLOBAL, "", "flood-" + i);
            }
            runTicks(liveServer, liveFirst, liveSecond, 220, true);
            int afterFlood = MultiplayerComms.messages(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL).size();
            require(afterFlood - beforeFlood < 40, "communication-specific burst limiting accepted every flood packet");
            require(afterFlood <= MultiplayerComms.MAX_CHANNEL_HISTORY, "per-channel history exceeded its hard cap");
            require(liveServerWorld.systemTime > simBefore, "chat flood prevented authoritative simulation progress");

            MultiplayerComms.block(liveSecondWorld, firstId);
            require(MultiplayerComms.messages(liveSecondWorld, MultiplayerComms.ChatChannel.GLOBAL).stream()
                            .noneMatch(message -> firstId.equals(message.senderId())),
                    "local block did not suppress sender history");
            require(MultiplayerComms.pings(liveSecondWorld).stream()
                            .noneMatch(candidate -> firstId.equals(candidate.senderId())),
                    "local block did not suppress sender pings");
            MultiplayerComms.unblock(liveSecondWorld, firstId);

            int historyBeforeReconnect = MultiplayerComms.messages(liveFirstWorld,
                    MultiplayerComms.ChatChannel.GLOBAL).size();
            liveFirst.forceClientDisconnectForTest();
            runUntil(liveServer, liveFirst, liveSecond, liveFirst::clientReady, 15_000,
                    "client did not reconnect after forced socket loss");
            runTicks(liveServer, liveFirst, liveSecond, 60, false);
            require(MultiplayerComms.messages(liveFirstWorld, MultiplayerComms.ChatChannel.GLOBAL).size()
                            == historyBeforeReconnect,
                    "reconnect replayed historical chat backlog");

            firstSide = clientSide(liveFirst);
            String huge = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("A".repeat(4_500).getBytes(StandardCharsets.UTF_8));
            firstSide.transport.sendOrdered("CHAT_SEND|GLOBAL||" + huge,
                    firstSide.config.serverAddress.getAddress(), firstSide.config.serverAddress.getPort());
            firstSide.transport.sendOrdered("CHAT_SEND|GLOBAL||%%%not-base64%%%",
                    firstSide.config.serverAddress.getAddress(), firstSide.config.serverAddress.getPort());
            firstSide.transport.sendOrdered("PING_SEND|MOVE|WORLD|" + encode(liveFirstWorld.activeSystemId())
                            + "|NaN|1|",
                    firstSide.config.serverAddress.getAddress(), firstSide.config.serverAddress.getPort());
            runTicks(liveServer, liveFirst, liveSecond, 80, true);
            require(liveFirst.clientReady() && liveSecond.clientReady(),
                    "malformed/oversized comms traffic destabilized healthy clients");
        } finally {
            MultiplayerComms.clearClient(firstWorld);
            MultiplayerComms.clearClient(secondWorld);
            if (second != null) second.shutdown();
            if (first != null) first.shutdown();
            if (server != null) server.shutdown();
            System.clearProperty("starchem.sessionStore");
            Files.deleteIfExists(sessionStore);
        }
    }

    private static PeerClientSide clientSide(PeerNetwork network) throws Exception {
        Field field = PeerNetwork.class.getDeclaredField("client");
        field.setAccessible(true);
        return (PeerClientSide) field.get(network);
    }

    private static boolean containsMessage(World world, MultiplayerComms.ChatChannel channel, String text) {
        for (MultiplayerComms.ChatMessage message : MultiplayerComms.messages(world, channel)) {
            if (text.equals(message.text())) return true;
        }
        return false;
    }

    private static boolean containsSystemPing(World world, String systemId) {
        for (MultiplayerComms.TacticalPing ping : MultiplayerComms.pings(world)) {
            if (ping.targetKind() == MultiplayerComms.PingTargetKind.SYSTEM
                    && systemId.equals(ping.targetSystemId())) return true;
        }
        return false;
    }

    private static MultiplayerComms.ChatMessage lastMessage(World world, MultiplayerComms.ChatChannel channel) {
        List<MultiplayerComms.ChatMessage> messages = MultiplayerComms.messages(world, channel);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    private static Unit firstUnit(World world, String playerId) {
        if (world == null || playerId == null) return null;
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) return unit;
        return null;
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void runTicks(PeerNetwork server, PeerNetwork first, PeerNetwork second,
                                 int count, boolean simulate) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            if (simulate) server.updateServerWorlds(0.016);
            tick(server, first, second);
        }
    }

    private static void runUntil(PeerNetwork server, PeerNetwork first, PeerNetwork second,
                                 Check check, long timeoutMs, String failure) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!check.ok() && System.currentTimeMillis() < deadline) tick(server, first, second);
        require(check.ok(), failure);
    }

    private static void tick(PeerNetwork server, PeerNetwork first, PeerNetwork second)
            throws InterruptedException {
        server.tick();
        first.tick();
        second.tick();
        Thread.sleep(8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Issue #295 validation failed: " + message);
    }

    @FunctionalInterface
    private interface Check { boolean ok() throws Exception; }
}
