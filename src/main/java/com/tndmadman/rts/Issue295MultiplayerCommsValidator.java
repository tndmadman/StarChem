package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Real-TCP regression coverage for issue #295 multiplayer chat and tactical pings. */
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
        require(!clean.contains("\u202E") && !clean.contains("\u001B") && !clean.contains("\n")
                        && clean.equals("alphaevil[31m beta"),
                "chat text normalization retained control/bidi/newline content");
        String longText = "🙂".repeat(MultiplayerComms.MAX_CHAT_CODE_POINTS + 25);
        String bounded = TextSafety.chatText(longText, MultiplayerComms.MAX_CHAT_CODE_POINTS);
        require(bounded.codePointCount(0, bounded.length()) == MultiplayerComms.MAX_CHAT_CODE_POINTS,
                "chat length was not bounded by code points");
        require(!TextSafety.containsUnsafeTerminalText(TextSafety.terminal("safe\u001B[2J")),
                "terminal escaping left unsafe terminal controls");
        GameSettings settings = GameSettings.forTest();
        require(settings.binding("chat_open") != null
                        && settings.binding("chat_open").keyCode() == java.awt.event.KeyEvent.VK_ENTER,
                "chat open binding is missing or not Enter by default");
    }

    private static void validateRealTcpComms() throws Exception {
        Path sessionStore = Files.createTempFile("starchem-issue295-session-", ".properties");
        Files.deleteIfExists(sessionStore);
        Path saveDir = Files.createTempDirectory("starchem-issue295-server-");
        System.setProperty("starchem.sessionStore", sessionStore.toString());
        PeerNetwork server = null;
        PeerNetwork first = null;
        PeerNetwork second = null;
        World serverWorld = null;
        World firstWorld = null;
        World secondWorld = null;
        try {
            int port = freePort();
            Config serverConfig = Config.dedicatedServer("Comms Validation Server", port, false, false,
                    Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "", 1, saveDir, "issue295", 0, 2, false);
            serverWorld = new World(serverConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            server = PeerNetwork.start(serverConfig, serverWorld);

            Config firstConfig = Config.join("Comms Alpha", "127.0.0.1", port, false);
            PendingPlayerPassword.remember(firstConfig, "alpha-validator-password".toCharArray(), false);
            firstWorld = new World(firstConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            first = PeerNetwork.start(firstConfig, firstWorld);

            Config secondConfig = Config.join("Comms Beta", "127.0.0.1", port, false);
            PendingPlayerPassword.remember(secondConfig, "beta-validator-password".toCharArray(), false);
            secondWorld = new World(secondConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            second = PeerNetwork.start(secondConfig, secondWorld);

            PeerNetwork finalServer = server;
            PeerNetwork finalFirst = first;
            PeerNetwork finalSecond = second;
            World finalFirstWorld = firstWorld;
            World finalSecondWorld = secondWorld;
            runUntil(finalServer, finalFirst, finalSecond,
                    () -> finalFirst.clientReady() && finalSecond.clientReady()
                            && !"SOLO".equals(finalFirst.localPlayerId())
                            && !"SOLO".equals(finalSecond.localPlayerId())
                            && firstUnit(finalFirstWorld, finalFirst.localPlayerId()) != null
                            && firstUnit(finalSecondWorld, finalSecond.localPlayerId()) != null,
                    15_000, "two clients did not join and receive initial state");

            String firstId = first.localPlayerId();
            String secondId = second.localPlayerId();

            require(MultiplayerComms.sendChat(firstWorld, MultiplayerComms.ChatChannel.GLOBAL, "",
                    "hello global").sent(), "global chat send was rejected locally");
            runUntil(server, first, second,
                    () -> containsMessage(secondWorld, MultiplayerComms.ChatChannel.GLOBAL, "hello global"),
                    4_000, "global chat did not reach another connected player");
            MultiplayerComms.ChatMessage global = lastMessage(secondWorld, MultiplayerComms.ChatChannel.GLOBAL);
            require(global != null && firstId.equals(global.senderId()),
                    "server did not supply the authenticated sender identity");

            require(MultiplayerComms.sendChat(firstWorld, MultiplayerComms.ChatChannel.SYSTEM, "",
                    "hello system").sent(), "system chat send was rejected locally");
            runUntil(server, first, second,
                    () -> containsMessage(secondWorld, MultiplayerComms.ChatChannel.SYSTEM, "hello system"),
                    4_000, "system chat did not reach a player viewing the same system");

            require(MultiplayerComms.sendChat(firstWorld, MultiplayerComms.ChatChannel.DIRECT, secondId,
                    "hello direct").sent(), "direct chat send was rejected locally");
            runUntil(server, first, second,
                    () -> containsMessage(secondWorld, MultiplayerComms.ChatChannel.DIRECT, "hello direct"),
                    4_000, "direct chat did not reach its authenticated recipient");
            MultiplayerComms.ChatMessage direct = lastMessage(secondWorld, MultiplayerComms.ChatChannel.DIRECT);
            require(direct != null && firstId.equals(direct.senderId()) && secondId.equals(direct.directTargetId()),
                    "direct chat routing metadata was not server authoritative");

            PlayerRegistry.activate(serverWorld);
            DiplomacySystem.setRelationship(serverWorld, firstId, secondId, DiplomacySystem.Relationship.ALLIED);
            require(MultiplayerComms.sendChat(firstWorld, MultiplayerComms.ChatChannel.TEAM, "",
                    "hello ally").sent(), "team chat send was rejected locally");
            runUntil(server, first, second,
                    () -> containsMessage(secondWorld, MultiplayerComms.ChatChannel.TEAM, "hello ally"),
                    4_000, "team chat did not use authoritative diplomacy relationships");

            Unit firstUnit = firstUnit(firstWorld, firstId);
            require(firstUnit != null, "first client has no local unit for ping validation");
            require(MultiplayerComms.sendWorldPing(firstWorld, MultiplayerComms.TacticalPingType.THREAT,
                    firstUnit.x, firstUnit.y).sent(), "valid world ping was rejected locally");
            runUntil(server, first, second,
                    () -> !MultiplayerComms.pings(firstWorld).isEmpty(),
                    4_000, "server did not return the sender's tactical ping");
            MultiplayerComms.TacticalPing ping = MultiplayerComms.pings(firstWorld).get(0);
            require(ping.type() == MultiplayerComms.TacticalPingType.THREAT
                            && firstId.equals(ping.senderId()) && Double.isFinite(ping.worldX()),
                    "world ping type, identity, or coordinates were corrupted");
            require(!MultiplayerComms.sendWorldPing(firstWorld, MultiplayerComms.TacticalPingType.MOVE,
                    Double.NaN, 1).sent(), "NaN ping coordinate passed client validation");

            GalaxyMapSnapshot map = firstWorld.galaxyMapSnapshot();
            String knownSystem = map == null || map.systems().isEmpty() ? "" : map.systems().get(0).id();
            if (!knownSystem.isBlank()) {
                require(MultiplayerComms.sendSystemPing(firstWorld, MultiplayerComms.TacticalPingType.ATTENTION,
                        knownSystem).sent(), "known galaxy-system ping was rejected locally");
                runTicks(server, first, second, 80);
                boolean foundSystemPing = false;
                for (MultiplayerComms.TacticalPing candidate : MultiplayerComms.pings(firstWorld)) {
                    if (candidate.targetKind() == MultiplayerComms.PingTargetKind.SYSTEM
                            && knownSystem.equals(candidate.targetSystemId())) foundSystemPing = true;
                }
                require(foundSystemPing, "galaxy-system ping did not round-trip");
            }

            PeerClientSide firstSide = clientSide(first);
            String spoofText = encode("forged-name: payload");
            firstSide.transport.sendOrdered("CHAT_SEND|GLOBAL||" + spoofText,
                    firstSide.config.serverAddress.getAddress(), firstSide.config.serverAddress.getPort());
            runTicks(server, first, second, 80);
            MultiplayerComms.ChatMessage spoof = lastMessage(secondWorld, MultiplayerComms.ChatChannel.GLOBAL);
            require(spoof != null && firstId.equals(spoof.senderId()),
                    "a client-controlled payload changed the displayed sender identity");

            int beforeFlood = MultiplayerComms.messages(secondWorld, MultiplayerComms.ChatChannel.GLOBAL).size();
            for (int i = 0; i < 40; i++) {
                MultiplayerComms.sendChat(firstWorld, MultiplayerComms.ChatChannel.GLOBAL, "", "flood-" + i);
            }
            runTicks(server, first, second, 220);
            int afterFlood = MultiplayerComms.messages(secondWorld, MultiplayerComms.ChatChannel.GLOBAL).size();
            require(afterFlood - beforeFlood < 40,
                    "communication-specific rate limits accepted an unrestricted burst");
            require(afterFlood <= MultiplayerComms.MAX_CHANNEL_HISTORY,
                    "client channel history exceeded its hard cap");

            MultiplayerComms.block(secondWorld, firstId);
            require(MultiplayerComms.messages(secondWorld, MultiplayerComms.ChatChannel.GLOBAL).stream()
                            .noneMatch(message -> firstId.equals(message.senderId())),
                    "local block did not suppress existing sender history");
            MultiplayerComms.unblock(secondWorld, firstId);

            int historyBeforeReconnect = MultiplayerComms.messages(firstWorld, MultiplayerComms.ChatChannel.GLOBAL).size();
            first.forceClientDisconnectForTest();
            runUntil(server, first, second, first::clientReady, 15_000,
                    "client did not reconnect after forced socket loss");
            runTicks(server, first, second, 60);
            require(MultiplayerComms.messages(firstWorld, MultiplayerComms.ChatChannel.GLOBAL).size()
                            == historyBeforeReconnect,
                    "reconnect replayed historical chat backlog");

            firstSide = clientSide(first);
            String huge = "A".repeat(4_500);
            firstSide.transport.sendOrdered("CHAT_SEND|GLOBAL||" + encode(huge),
                    firstSide.config.serverAddress.getAddress(), firstSide.config.serverAddress.getPort());
            runTicks(server, first, second, 60);
            require(first.clientReady() && second.clientReady(),
                    "oversized comms packet destabilized connected clients");
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void runTicks(PeerNetwork server, PeerNetwork first, PeerNetwork second, int count)
            throws InterruptedException {
        for (int i = 0; i < count; i++) tick(server, first, second);
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
