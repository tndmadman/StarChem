package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-authoritative, bounded multiplayer chat and tactical ping transport.
 *
 * Client packets never carry a trusted sender identity. The authenticated TCP
 * connection is resolved to the authoritative player before channel membership,
 * rate limits, text safety, visibility, or recipients are evaluated.
 */
final class MultiplayerComms {
    static final int MAX_CHAT_CODE_POINTS = 320;
    static final int MAX_CLIENT_HISTORY = 240;
    static final int MAX_CHANNEL_HISTORY = 80;
    static final int MAX_CLIENT_PINGS = 64;
    static final int MAX_ACTIVE_PINGS_PER_SENDER = 8;
    static final long PING_LIFETIME_MS = 8_000;

    private static final int MAX_CHAT_WIRE_CHARS = 2_048;
    private static final int MAX_PING_WIRE_CHARS = 1_024;
    private static final int MAX_TEXT_ENCODED_CHARS = 2_048;
    private static final int MAX_ID_ENCODED_CHARS = 256;
    private static final int MAX_RATE_KEYS = 2_048;
    private static final long POLICY_RELOAD_MS = 2_000;
    private static final long DENIAL_FEEDBACK_MS = 750;
    private static final double PING_COALESCE_DISTANCE = 72.0;
    private static final long PING_COALESCE_MS = 1_500;

    private static final Map<PeerServerSide, ServerState> SERVERS = new WeakHashMap<>();
    private static final Map<World, ClientState> CLIENTS = new WeakHashMap<>();
    private static final Map<World, PeerClientSide> CLIENT_CONNECTIONS = new WeakHashMap<>();

    private MultiplayerComms() { }

    enum ChatChannel {
        GLOBAL("Global"),
        SYSTEM("System"),
        TEAM("Team"),
        DIRECT("Direct");

        final String label;

        ChatChannel(String label) { this.label = label; }

        static ChatChannel parse(String value) {
            if (value == null) return null;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { return null; }
        }
    }

    enum TacticalPingType {
        ATTENTION("Attention / rally", 0x59C8FF),
        THREAT("Enemy / threat", 0xFF6A5C),
        DEFEND("Defend", 0xFFD166),
        RESOURCE("Resource", 0x66E38D),
        MOVE("Move here", 0xC89CFF);

        final String label;
        final int rgb;

        TacticalPingType(String label, int rgb) {
            this.label = label;
            this.rgb = rgb & 0xFFFFFF;
        }

        static TacticalPingType parse(String value) {
            if (value == null) return null;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { return null; }
        }
    }

    enum PingTargetKind {
        WORLD,
        SYSTEM;

        static PingTargetKind parse(String value) {
            if (value == null) return null;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { return null; }
        }
    }

    record ChatMessage(long id, long serverTimeMs, ChatChannel channel,
                       String senderId, String senderName, String systemId,
                       String directTargetId, String text) { }

    record TacticalPing(long id, long serverTimeMs, TacticalPingType type,
                        String senderId, String senderName, String systemId,
                        PingTargetKind targetKind, double worldX, double worldY,
                        String targetSystemId, long receivedAtMs) { }

    record SendResult(boolean sent, String message) {
        static SendResult ok() { return new SendResult(true, ""); }
        static SendResult fail(String message) { return new SendResult(false, message == null ? "" : message); }
    }

    /** Register the live client side and consume comms-specific server packets. */
    static boolean acceptClientPacket(PeerClientSide client, String raw) {
        if (client == null) return false;
        synchronized (MultiplayerComms.class) {
            CLIENT_CONNECTIONS.put(client.world, client);
            CLIENTS.computeIfAbsent(client.world, ignored -> new ClientState());
        }
        if (raw == null) return false;
        if (raw.startsWith("CHAT_EVENT|")) {
            readChatEvent(client.world, raw);
            return true;
        }
        if (raw.startsWith("PING_EVENT|")) {
            readPingEvent(client.world, raw);
            return true;
        }
        if (raw.startsWith("COMMS_RESULT|")) {
            readResult(client.world, raw);
            return true;
        }
        return false;
    }

    /** Early authoritative dispatch, before PacketSideA performs an unbounded split. */
    static boolean handleServer(PeerServerSide server, String raw, NetPacket packet) {
        if (raw == null) return false;
        boolean chat = raw.startsWith("CHAT_SEND|");
        boolean ping = raw.startsWith("PING_SEND|");
        if (!chat && !ping) return false;
        if (server == null || packet == null || packet.connectionId() == null || !packet.connectionId().valid()) return true;
        ConnectionId connectionId = packet.connectionId();
        String senderId = server.ownerId(connectionId, "");
        if (senderId.isBlank() || !server.owns(connectionId, senderId)) {
            server.transport.recordMalformedPacket();
            return true;
        }
        server.touch(connectionId);
        try {
            if (chat) handleChat(server, connectionId, senderId, raw);
            else handlePing(server, connectionId, senderId, raw);
        } catch (RuntimeException ex) {
            server.transport.recordMalformedPacket();
        }
        return true;
    }

    static SendResult sendChat(World world, ChatChannel channel, String directTargetId, String text) {
        PeerClientSide client = client(world);
        if (client == null || !client.connectedState()) return SendResult.fail("Chat is unavailable while disconnected or synchronizing.");
        ChatChannel safeChannel = channel == null ? ChatChannel.GLOBAL : channel;
        String clean = TextSafety.chatText(text, MAX_CHAT_CODE_POINTS);
        if (clean.isBlank()) return SendResult.fail("Message is empty.");
        String target = safeChannel == ChatChannel.DIRECT ? cleanPlayerSelector(directTargetId) : "";
        if (safeChannel == ChatChannel.DIRECT && target.isBlank()) return SendResult.fail("Choose a direct-message recipient first.");
        String packet = "CHAT_SEND|" + safeChannel.name() + "|" + encode(target) + "|" + encode(clean);
        if (packet.length() > MAX_CHAT_WIRE_CHARS) return SendResult.fail("Message is too long.");
        if (!sendOrdered(client, packet)) return SendResult.fail("Chat could not be queued to the server.");
        return SendResult.ok();
    }

    static SendResult sendWorldPing(World world, TacticalPingType type, double x, double y) {
        PeerClientSide client = client(world);
        if (client == null || !client.connectedState()) return SendResult.fail("Tactical pings are unavailable while disconnected or synchronizing.");
        if (!Double.isFinite(x) || !Double.isFinite(y)) return SendResult.fail("Ping coordinates are invalid.");
        String systemId = cleanSystemId(client.viewedSystemId().isBlank() ? world.activeSystemId() : client.viewedSystemId());
        if (systemId.isBlank()) return SendResult.fail("No server-approved system is active.");
        TacticalPingType safeType = type == null ? TacticalPingType.ATTENTION : type;
        String packet = "PING_SEND|" + safeType.name() + "|WORLD|" + encode(systemId) + "|"
                + Double.toHexString(x) + "|" + Double.toHexString(y) + "|";
        if (!sendOrdered(client, packet)) return SendResult.fail("Ping could not be queued to the server.");
        return SendResult.ok();
    }

    static SendResult sendSystemPing(World world, TacticalPingType type, String targetSystemId) {
        PeerClientSide client = client(world);
        if (client == null || !client.connectedState()) return SendResult.fail("Tactical pings are unavailable while disconnected or synchronizing.");
        String systemId = cleanSystemId(client.viewedSystemId().isBlank() ? world.activeSystemId() : client.viewedSystemId());
        String target = cleanSystemId(targetSystemId);
        if (systemId.isBlank() || target.isBlank()) return SendResult.fail("A valid galaxy system is required.");
        TacticalPingType safeType = type == null ? TacticalPingType.ATTENTION : type;
        String packet = "PING_SEND|" + safeType.name() + "|SYSTEM|" + encode(systemId) + "|||" + encode(target);
        if (!sendOrdered(client, packet)) return SendResult.fail("Ping could not be queued to the server.");
        return SendResult.ok();
    }

    static synchronized List<ChatMessage> messages(World world, ChatChannel channel) {
        ClientState state = CLIENTS.get(world);
        if (state == null || channel == null) return List.of();
        Deque<ChatMessage> queue = state.messages.get(channel);
        if (queue == null || queue.isEmpty()) return List.of();
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage message : queue) if (!state.blocked.contains(message.senderId())) out.add(message);
        return List.copyOf(out);
    }

    static synchronized List<TacticalPing> pings(World world) {
        ClientState state = CLIENTS.get(world);
        if (state == null) return List.of();
        pruneClientPings(state, System.currentTimeMillis());
        List<TacticalPing> out = new ArrayList<>();
        for (TacticalPing ping : state.pings) if (!state.blocked.contains(ping.senderId())) out.add(ping);
        return List.copyOf(out);
    }

    static synchronized int unread(World world, ChatChannel channel) {
        ClientState state = CLIENTS.get(world);
        return state == null || channel == null ? 0 : state.unread.getOrDefault(channel, 0);
    }

    static synchronized void markRead(World world, ChatChannel channel) {
        ClientState state = CLIENTS.get(world);
        if (state != null && channel != null) state.unread.put(channel, 0);
    }

    static synchronized void block(World world, String playerId) {
        ClientState state = CLIENTS.computeIfAbsent(world, ignored -> new ClientState());
        String id = cleanPlayerSelector(playerId);
        if (id.isBlank() || PlayerRegistry.isLocal(id)) return;
        if (state.blocked.size() >= 128 && !state.blocked.contains(id)) return;
        state.blocked.add(id);
    }

    static synchronized void unblock(World world, String playerId) {
        ClientState state = CLIENTS.get(world);
        if (state != null) state.blocked.remove(cleanPlayerSelector(playerId));
    }

    static synchronized Set<String> blocked(World world) {
        ClientState state = CLIENTS.get(world);
        return state == null ? Set.of() : Set.copyOf(state.blocked);
    }

    static List<PlayerInfo> directCandidates(World world) {
        PlayerRegistry.activate(world);
        List<PlayerInfo> out = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player == null || player.id().isBlank() || PlayerRegistry.isLocal(player.id())
                    || NpcRules.isNpcFaction(player.id())) continue;
            out.add(player);
        }
        return List.copyOf(out);
    }

    static synchronized void clearClient(World world) {
        if (world == null) return;
        CLIENTS.remove(world);
        CLIENT_CONNECTIONS.remove(world);
    }

    private static void handleChat(PeerServerSide server, ConnectionId connectionId,
                                   String senderId, String raw) {
        if (raw.length() > MAX_CHAT_WIRE_CHARS) {
            rejectMalformed(server, connectionId);
            return;
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 4) {
            rejectMalformed(server, connectionId);
            return;
        }
        ChatChannel channel = ChatChannel.parse(parts[1]);
        String directTarget = decode(parts[2], MAX_ID_ENCODED_CHARS, 96);
        String text = TextSafety.chatText(decode(parts[3], MAX_TEXT_ENCODED_CHARS, 2_048), MAX_CHAT_CODE_POINTS);
        if (channel == null || text.isBlank()) {
            rejectMalformed(server, connectionId);
            return;
        }

        ServerState state = serverState(server);
        long now = System.currentTimeMillis();
        ServerCommsPolicy policy = state.policy(now);
        if (!policy.enabled || !policy.channelEnabled(channel)) {
            sendFeedback(server, state, senderId, connectionId, "DISABLED", "That chat channel is disabled by the server.", now);
            return;
        }
        if (policy.mutedPlayers.contains(senderId.toLowerCase(Locale.ROOT))) {
            sendFeedback(server, state, senderId, connectionId, "MUTED", "You are muted from player chat on this server.", now);
            return;
        }
        if (!state.allowChat(senderId, channel, directTarget)) {
            sendFeedback(server, state, senderId, connectionId, "THROTTLED", "Chat rate limit exceeded; try again shortly.", now);
            return;
        }

        String senderName = TextSafety.playerName(PlayerRegistry.baseName(senderId));
        String systemId = cleanSystemId(server.views.view(server.world, senderId));
        String resolvedDirect = "";
        List<ConnectionId> recipients = new ArrayList<>();

        switch (channel) {
            case GLOBAL -> recipients.addAll(connectedRecipients(server));
            case SYSTEM -> {
                if (systemId.isBlank()) {
                    sendFeedback(server, state, senderId, connectionId, "UNAVAILABLE", "System chat is unavailable right now.", now);
                    return;
                }
                for (PersistentPlayerSession session : server.persistentSessions()) {
                    if (!connected(server, session.playerId())) continue;
                    if (systemId.equals(server.views.view(server.world, session.playerId()))) {
                        recipients.add(server.connectionIdForPlayer(session.playerId()));
                    }
                }
            }
            case TEAM -> {
                for (PersistentPlayerSession session : server.persistentSessions()) {
                    if (!connected(server, session.playerId())) continue;
                    if (DiplomacySystem.allied(server.world, senderId, session.playerId())) {
                        recipients.add(server.connectionIdForPlayer(session.playerId()));
                    }
                }
            }
            case DIRECT -> {
                resolvedDirect = connectedPlayerId(server, directTarget);
                if (resolvedDirect.isBlank()) {
                    sendFeedback(server, state, senderId, connectionId, "UNAVAILABLE", "Direct recipient is unavailable.", now);
                    return;
                }
                if (!state.allowDirectPair(senderId, resolvedDirect)) {
                    sendFeedback(server, state, senderId, connectionId, "THROTTLED", "Direct-message rate limit exceeded; try again shortly.", now);
                    return;
                }
                recipients.add(server.connectionIdForPlayer(resolvedDirect));
                if (!resolvedDirect.equals(senderId)) recipients.add(connectionId);
            }
        }

        LinkedHashSet<ConnectionId> unique = new LinkedHashSet<>(recipients);
        if (unique.isEmpty()) unique.add(connectionId);
        long id = state.nextMessageId++;
        String event = "CHAT_EVENT|" + id + "|" + now + "|" + channel.name() + "|"
                + encode(senderId) + "|" + encode(senderName) + "|" + encode(systemId) + "|"
                + encode(resolvedDirect) + "|" + encode(text);
        for (ConnectionId recipient : unique) if (recipient != null && recipient.valid()) server.transport.sendOrdered(event, recipient);
    }

    private static void handlePing(PeerServerSide server, ConnectionId connectionId,
                                   String senderId, String raw) {
        if (raw.length() > MAX_PING_WIRE_CHARS) {
            rejectMalformed(server, connectionId);
            return;
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 7) {
            rejectMalformed(server, connectionId);
            return;
        }
        TacticalPingType type = TacticalPingType.parse(parts[1]);
        PingTargetKind kind = PingTargetKind.parse(parts[2]);
        String systemId = cleanSystemId(decode(parts[3], MAX_ID_ENCODED_CHARS, 192));
        String targetSystem = cleanSystemId(decode(parts[6], MAX_ID_ENCODED_CHARS, 192));
        if (type == null || kind == null || systemId.isBlank()) {
            rejectMalformed(server, connectionId);
            return;
        }

        ServerState state = serverState(server);
        long now = System.currentTimeMillis();
        ServerCommsPolicy policy = state.policy(now);
        if (!policy.enabled || !policy.pings) {
            sendFeedback(server, state, senderId, connectionId, "DISABLED", "Tactical pings are disabled by the server.", now);
            return;
        }
        if (policy.mutedPlayers.contains(senderId.toLowerCase(Locale.ROOT))) {
            sendFeedback(server, state, senderId, connectionId, "MUTED", "You are muted from tactical pings on this server.", now);
            return;
        }
        if (!state.allowPing(senderId)) {
            sendFeedback(server, state, senderId, connectionId, "THROTTLED", "Tactical ping rate limit exceeded; try again shortly.", now);
            return;
        }

        String approvedView = cleanSystemId(server.views.view(server.world, senderId));
        if (!systemId.equals(approvedView)) {
            sendFeedback(server, state, senderId, connectionId, "DENIED", "Ping target is outside your server-approved view.", now);
            return;
        }

        double x = Double.NaN;
        double y = Double.NaN;
        if (kind == PingTargetKind.WORLD) {
            if (parts[4].length() > 32 || parts[5].length() > 32) {
                rejectMalformed(server, connectionId);
                return;
            }
            try {
                x = Double.valueOf(parts[4]);
                y = Double.valueOf(parts[5]);
            } catch (RuntimeException ex) {
                rejectMalformed(server, connectionId);
                return;
            }
            if (!validWorldPing(server, senderId, systemId, x, y)) {
                sendFeedback(server, state, senderId, connectionId, "DENIED", "Ping location is invalid or outside current sensor visibility.", now);
                return;
            }
            targetSystem = "";
        } else {
            if (targetSystem.isBlank() || !knownGalaxySystem(server, senderId, targetSystem)) {
                sendFeedback(server, state, senderId, connectionId, "DENIED", "That galaxy system is not available to ping.", now);
                return;
            }
            x = Double.NaN;
            y = Double.NaN;
        }

        state.prunePings(now);
        ServerPing active = state.coalesce(senderId, type, systemId, kind, x, y, targetSystem, now);
        if (active == null && state.activePingCount(senderId) >= MAX_ACTIVE_PINGS_PER_SENDER) {
            sendFeedback(server, state, senderId, connectionId, "THROTTLED", "You already have too many active tactical pings.", now);
            return;
        }
        if (active == null) {
            active = new ServerPing(state.nextPingId++, senderId, type, systemId, kind, x, y, targetSystem, now);
            state.activePings.addLast(active);
            while (state.activePings.size() > 128) state.activePings.removeFirst();
        } else {
            active = active.refreshed(x, y, targetSystem, now);
            state.replacePing(active);
        }

        String senderName = TextSafety.playerName(PlayerRegistry.baseName(senderId));
        String event = pingEvent(active, senderName);
        for (ConnectionId recipient : pingRecipients(server, active)) {
            if (recipient != null && recipient.valid()) server.transport.sendOrdered(event, recipient);
        }
    }

    private static List<ConnectionId> pingRecipients(PeerServerSide server, ServerPing ping) {
        LinkedHashSet<ConnectionId> recipients = new LinkedHashSet<>();
        String old = server.world.activeSystemId();
        try {
            if (ping.kind == PingTargetKind.WORLD) server.world.activateSystem(ping.systemId);
            for (PersistentPlayerSession session : server.persistentSessions()) {
                if (session == null || !connected(server, session.playerId())) continue;
                String playerId = session.playerId();
                if (!playerId.equals(ping.senderId)
                        && !DiplomacySystem.allied(server.world, ping.senderId, playerId)) continue;
                boolean allowed;
                if (ping.kind == PingTargetKind.WORLD) {
                    allowed = ping.systemId.equals(server.views.view(server.world, playerId))
                            && VisibilityRules.pointVisible(server.world, playerId, ping.x, ping.y);
                } else {
                    allowed = knownGalaxySystem(server, playerId, ping.targetSystemId);
                }
                if (allowed || playerId.equals(ping.senderId)) {
                    recipients.add(server.connectionIdForPlayer(playerId));
                }
            }
        } finally {
            if (old != null && !old.isBlank() && !old.contains("WAIT") && !old.equals(server.world.activeSystemId())) {
                server.world.activateSystem(old);
            }
        }
        ConnectionId sender = server.connectionIdForPlayer(ping.senderId);
        if (sender.valid()) recipients.add(sender);
        return List.copyOf(recipients);
    }

    private static boolean validWorldPing(PeerServerSide server, String senderId, String systemId,
                                          double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
        String old = server.world.activeSystemId();
        try {
            server.world.activateSystem(systemId);
            if (!systemId.equals(server.world.activeSystemId())) return false;
            if (x < 0 || y < 0 || x > server.world.width || y > server.world.height) return false;
            return VisibilityRules.pointVisible(server.world, senderId, x, y);
        } finally {
            if (old != null && !old.isBlank() && !old.contains("WAIT") && !old.equals(server.world.activeSystemId())) {
                server.world.activateSystem(old);
            }
        }
    }

    private static boolean knownGalaxySystem(PeerServerSide server, String playerId, String systemId) {
        if (systemId == null || systemId.isBlank()) return false;
        GalaxyMapSnapshot snapshot = server.views.galaxySnapshot(server.world, playerId);
        if (snapshot == null || snapshot.systems() == null) return false;
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && systemId.equals(system.id())) return true;
        }
        return false;
    }

    private static String pingEvent(ServerPing ping, String senderName) {
        return "PING_EVENT|" + ping.id + "|" + ping.createdAtMs + "|" + ping.type.name() + "|"
                + encode(ping.senderId) + "|" + encode(senderName) + "|" + encode(ping.systemId) + "|"
                + ping.kind.name() + "|" + (Double.isFinite(ping.x) ? Double.toHexString(ping.x) : "") + "|"
                + (Double.isFinite(ping.y) ? Double.toHexString(ping.y) : "") + "|" + encode(ping.targetSystemId);
    }

    private static List<ConnectionId> connectedRecipients(PeerServerSide server) {
        LinkedHashSet<ConnectionId> out = new LinkedHashSet<>();
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null) continue;
            ConnectionId id = server.connectionIdForPlayer(session.playerId());
            if (id.valid()) out.add(id);
        }
        return List.copyOf(out);
    }

    private static boolean connected(PeerServerSide server, String playerId) {
        return server != null && playerId != null && server.connectionIdForPlayer(playerId).valid();
    }

    private static String connectedPlayerId(PeerServerSide server, String selector) {
        String wanted = cleanPlayerSelector(selector);
        if (wanted.isBlank()) return "";
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null || !connected(server, session.playerId())) continue;
            if (session.playerId().equalsIgnoreCase(wanted)) return session.playerId();
        }
        return "";
    }

    private static void rejectMalformed(PeerServerSide server, ConnectionId connectionId) {
        if (server != null) server.transport.recordMalformedPacket();
        if (server != null && connectionId != null && connectionId.valid()) {
            server.transport.sendOrdered(resultPacket("MALFORMED", "Malformed communication packet was rejected."), connectionId);
        }
    }

    private static void sendFeedback(PeerServerSide server, ServerState state, String playerId,
                                     ConnectionId connectionId, String code, String text, long now) {
        if (connectionId == null || !connectionId.valid()) return;
        long last = state.lastFeedback.getOrDefault(playerId, 0L);
        if (now - last < DENIAL_FEEDBACK_MS) return;
        state.lastFeedback.put(playerId, now);
        if (state.lastFeedback.size() > 512) state.lastFeedback.remove(state.lastFeedback.keySet().iterator().next());
        server.transport.sendOrdered(resultPacket(code, text), connectionId);
    }

    private static String resultPacket(String code, String text) {
        String safeCode = code == null ? "INFO" : code.replace("|", "").trim();
        return "COMMS_RESULT|" + safeCode + "|" + encode(TextSafety.chatText(text, 240));
    }

    private static void readChatEvent(World world, String raw) {
        if (world == null || raw.length() > 4_096) return;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 9) return;
        try {
            long id = Long.parseLong(parts[1]);
            long time = Long.parseLong(parts[2]);
            ChatChannel channel = ChatChannel.parse(parts[3]);
            String senderId = cleanPlayerSelector(decode(parts[4], MAX_ID_ENCODED_CHARS, 96));
            String senderName = TextSafety.playerName(decode(parts[5], MAX_ID_ENCODED_CHARS, 128));
            String systemId = cleanSystemId(decode(parts[6], MAX_ID_ENCODED_CHARS, 192));
            String direct = cleanPlayerSelector(decode(parts[7], MAX_ID_ENCODED_CHARS, 96));
            String text = TextSafety.chatText(decode(parts[8], MAX_TEXT_ENCODED_CHARS, 2_048), MAX_CHAT_CODE_POINTS);
            if (id < 0 || time < 0 || channel == null || senderId.isBlank() || text.isBlank()) return;
            ClientState state;
            synchronized (MultiplayerComms.class) {
                state = CLIENTS.computeIfAbsent(world, ignored -> new ClientState());
                ChatMessage message = new ChatMessage(id, time, channel, senderId, senderName,
                        systemId, direct, text);
                addMessage(state, message);
            }
        } catch (RuntimeException ignored) { }
    }

    private static void readPingEvent(World world, String raw) {
        if (world == null || raw.length() > 2_048) return;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 11) return;
        try {
            long id = Long.parseLong(parts[1]);
            long time = Long.parseLong(parts[2]);
            TacticalPingType type = TacticalPingType.parse(parts[3]);
            String senderId = cleanPlayerSelector(decode(parts[4], MAX_ID_ENCODED_CHARS, 96));
            String senderName = TextSafety.playerName(decode(parts[5], MAX_ID_ENCODED_CHARS, 128));
            String systemId = cleanSystemId(decode(parts[6], MAX_ID_ENCODED_CHARS, 192));
            PingTargetKind kind = PingTargetKind.parse(parts[7]);
            double x = parts[8].isBlank() ? Double.NaN : Double.valueOf(parts[8]);
            double y = parts[9].isBlank() ? Double.NaN : Double.valueOf(parts[9]);
            String targetSystem = cleanSystemId(decode(parts[10], MAX_ID_ENCODED_CHARS, 192));
            if (id < 0 || time < 0 || type == null || kind == null || senderId.isBlank() || systemId.isBlank()) return;
            if (kind == PingTargetKind.WORLD && (!Double.isFinite(x) || !Double.isFinite(y))) return;
            if (kind == PingTargetKind.SYSTEM && targetSystem.isBlank()) return;
            TacticalPing ping = new TacticalPing(id, time, type, senderId, senderName, systemId,
                    kind, x, y, targetSystem, System.currentTimeMillis());
            synchronized (MultiplayerComms.class) {
                ClientState state = CLIENTS.computeIfAbsent(world, ignored -> new ClientState());
                addClientPing(state, ping);
            }
        } catch (RuntimeException ignored) { }
    }

    private static void readResult(World world, String raw) {
        if (world == null || raw.length() > 1_024) return;
        String[] parts = raw.split("\\|", 3);
        if (parts.length != 3) return;
        String text = TextSafety.chatText(decode(parts[2], MAX_TEXT_ENCODED_CHARS, 1_024), 240);
        if (!text.isBlank()) AlertCenter.push(world, "COMMS: " + text);
    }

    private static void addMessage(ClientState state, ChatMessage message) {
        Deque<ChatMessage> channel = state.messages.get(message.channel());
        channel.addLast(message);
        while (channel.size() > MAX_CHANNEL_HISTORY) channel.removeFirst();
        state.unread.put(message.channel(), Math.min(999, state.unread.getOrDefault(message.channel(), 0) + 1));
        while (totalMessages(state) > MAX_CLIENT_HISTORY) removeOldestMessage(state);
    }

    private static int totalMessages(ClientState state) {
        int total = 0;
        for (Deque<ChatMessage> queue : state.messages.values()) total += queue.size();
        return total;
    }

    private static void removeOldestMessage(ClientState state) {
        ChatChannel oldestChannel = null;
        ChatMessage oldest = null;
        for (Map.Entry<ChatChannel, Deque<ChatMessage>> entry : state.messages.entrySet()) {
            ChatMessage candidate = entry.getValue().peekFirst();
            if (candidate == null) continue;
            if (oldest == null || candidate.serverTimeMs() < oldest.serverTimeMs()
                    || candidate.serverTimeMs() == oldest.serverTimeMs() && candidate.id() < oldest.id()) {
                oldest = candidate;
                oldestChannel = entry.getKey();
            }
        }
        if (oldestChannel != null) state.messages.get(oldestChannel).pollFirst();
    }

    private static void addClientPing(ClientState state, TacticalPing ping) {
        long now = System.currentTimeMillis();
        pruneClientPings(state, now);
        for (int i = 0; i < state.pings.size(); i++) {
            TacticalPing current = state.pings.get(i);
            if (current.id() == ping.id() || coalescible(current, ping, now)) {
                state.pings.set(i, ping);
                return;
            }
        }
        int senderCount = 0;
        for (TacticalPing current : state.pings) if (current.senderId().equals(ping.senderId())) senderCount++;
        if (senderCount >= MAX_ACTIVE_PINGS_PER_SENDER) {
            for (int i = 0; i < state.pings.size(); i++) {
                if (state.pings.get(i).senderId().equals(ping.senderId())) {
                    state.pings.remove(i);
                    break;
                }
            }
        }
        state.pings.add(ping);
        while (state.pings.size() > MAX_CLIENT_PINGS) state.pings.remove(0);
    }

    private static boolean coalescible(TacticalPing a, TacticalPing b, long now) {
        if (!a.senderId().equals(b.senderId()) || a.type() != b.type() || a.targetKind() != b.targetKind()) return false;
        if (now - a.receivedAtMs() > PING_COALESCE_MS) return false;
        if (a.targetKind() == PingTargetKind.SYSTEM) return a.targetSystemId().equals(b.targetSystemId());
        return a.systemId().equals(b.systemId()) && Math.hypot(a.worldX() - b.worldX(), a.worldY() - b.worldY()) <= PING_COALESCE_DISTANCE;
    }

    private static void pruneClientPings(ClientState state, long now) {
        state.pings.removeIf(ping -> now - ping.receivedAtMs() >= PING_LIFETIME_MS);
    }

    private static PeerClientSide client(World world) {
        synchronized (MultiplayerComms.class) { return CLIENT_CONNECTIONS.get(world); }
    }

    private static boolean sendOrdered(PeerClientSide client, String packet) {
        if (client == null || packet == null || packet.isBlank() || client.config == null
                || client.config.serverAddress == null || client.config.serverAddress.getAddress() == null) return false;
        client.transport.sendOrdered(packet, client.config.serverAddress.getAddress(), client.config.serverAddress.getPort());
        return true;
    }

    private static synchronized ServerState serverState(PeerServerSide server) {
        return SERVERS.computeIfAbsent(server, ServerState::new);
    }

    private static String encode(String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String encoded, int maxEncodedChars, int maxDecodedBytes) {
        if (encoded == null || encoded.length() > maxEncodedChars) return "";
        if (encoded.isBlank()) return "";
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > maxDecodedBytes) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String cleanPlayerSelector(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() > 64) return "";
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') return "";
        }
        return clean;
    }

    private static String cleanSystemId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.isBlank() || clean.length() > 128 || clean.contains("WAIT")) return "";
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') return "";
        }
        return clean;
    }

    private static final class ClientState {
        final EnumMap<ChatChannel, Deque<ChatMessage>> messages = new EnumMap<>(ChatChannel.class);
        final EnumMap<ChatChannel, Integer> unread = new EnumMap<>(ChatChannel.class);
        final List<TacticalPing> pings = new ArrayList<>();
        final LinkedHashSet<String> blocked = new LinkedHashSet<>();

        ClientState() {
            for (ChatChannel channel : ChatChannel.values()) messages.put(channel, new ArrayDeque<>());
        }
    }

    private static final class ServerState {
        final PeerServerSide server;
        final BoundedBuckets buckets = new BoundedBuckets();
        final Deque<ServerPing> activePings = new ArrayDeque<>();
        final Map<String, Long> lastFeedback = new LinkedHashMap<>();
        long nextMessageId = 1;
        long nextPingId = 1;
        long lastPolicyCheck;
        ServerCommsPolicy currentPolicy;

        ServerState(PeerServerSide server) {
            this.server = server;
            this.currentPolicy = ServerCommsPolicy.load(server == null ? null : server.config, null);
        }

        ServerCommsPolicy policy(long now) {
            if (now - lastPolicyCheck < POLICY_RELOAD_MS) return currentPolicy;
            lastPolicyCheck = now;
            currentPolicy = ServerCommsPolicy.load(server == null ? null : server.config, currentPolicy);
            return currentPolicy;
        }

        boolean allowChat(String senderId, ChatChannel channel, String directTarget) {
            long now = System.nanoTime();
            return buckets.take("all-chat", 24.0, 48.0, 1.0, now)
                    && buckets.take("sender-chat:" + senderId, 4.0, 8.0, 1.0, now)
                    && buckets.take("channel:" + channel.name(), 12.0, 24.0, 1.0, now);
        }

        boolean allowDirectPair(String senderId, String recipientId) {
            return buckets.take("dm:" + senderId + ":" + recipientId, 2.0, 4.0, 1.0, System.nanoTime());
        }

        boolean allowPing(String senderId) {
            long now = System.nanoTime();
            return buckets.take("all-ping", 8.0, 16.0, 1.0, now)
                    && buckets.take("sender-ping:" + senderId, 1.25, 3.0, 1.0, now);
        }

        void prunePings(long now) {
            activePings.removeIf(ping -> now - ping.createdAtMs >= PING_LIFETIME_MS);
        }

        int activePingCount(String senderId) {
            int count = 0;
            for (ServerPing ping : activePings) if (ping.senderId.equals(senderId)) count++;
            return count;
        }

        ServerPing coalesce(String senderId, TacticalPingType type, String systemId,
                            PingTargetKind kind, double x, double y, String targetSystem, long now) {
            ServerPing found = null;
            for (ServerPing ping : activePings) {
                if (!ping.senderId.equals(senderId) || ping.type != type || ping.kind != kind
                        || now - ping.createdAtMs > PING_COALESCE_MS) continue;
                if (kind == PingTargetKind.SYSTEM && ping.targetSystemId.equals(targetSystem)) found = ping;
                else if (kind == PingTargetKind.WORLD && ping.systemId.equals(systemId)
                        && Math.hypot(ping.x - x, ping.y - y) <= PING_COALESCE_DISTANCE) found = ping;
            }
            return found;
        }

        void replacePing(ServerPing replacement) {
            Deque<ServerPing> next = new ArrayDeque<>();
            for (ServerPing ping : activePings) next.addLast(ping.id == replacement.id ? replacement : ping);
            activePings.clear();
            activePings.addAll(next);
        }
    }

    private static final class BoundedBuckets {
        private final LinkedHashMap<String, TokenBucket> buckets = new LinkedHashMap<>();

        boolean take(String key, double rate, double burst, double cost, long now) {
            TokenBucket bucket = buckets.get(key);
            if (bucket == null) {
                if (buckets.size() >= MAX_RATE_KEYS) buckets.remove(buckets.keySet().iterator().next());
                bucket = new TokenBucket(burst, now);
                buckets.put(key, bucket);
            }
            return bucket.take(rate, burst, cost, now);
        }
    }

    private static final class TokenBucket {
        double tokens;
        long lastNanos;

        TokenBucket(double burst, long now) {
            tokens = Math.max(1.0, burst);
            lastNanos = now;
        }

        boolean take(double rate, double burst, double cost, long now) {
            long elapsed = Math.max(0, now - lastNanos);
            if (elapsed > 0) {
                tokens = Math.min(burst, tokens + elapsed / 1_000_000_000.0 * Math.max(0, rate));
                lastNanos = now;
            }
            if (tokens < cost) return false;
            tokens -= cost;
            return true;
        }
    }

    private static final class ServerCommsPolicy {
        final boolean enabled;
        final boolean global;
        final boolean system;
        final boolean team;
        final boolean direct;
        final boolean pings;
        final Set<String> mutedPlayers;
        final Path path;
        final long modifiedAt;

        ServerCommsPolicy(boolean enabled, boolean global, boolean system, boolean team,
                          boolean direct, boolean pings, Set<String> mutedPlayers,
                          Path path, long modifiedAt) {
            this.enabled = enabled;
            this.global = global;
            this.system = system;
            this.team = team;
            this.direct = direct;
            this.pings = pings;
            this.mutedPlayers = mutedPlayers == null ? Set.of() : Set.copyOf(mutedPlayers);
            this.path = path;
            this.modifiedAt = modifiedAt;
        }

        boolean channelEnabled(ChatChannel channel) {
            return switch (channel) {
                case GLOBAL -> global;
                case SYSTEM -> system;
                case TEAM -> team;
                case DIRECT -> direct;
            };
        }

        static ServerCommsPolicy load(Config config, ServerCommsPolicy previous) {
            if (config == null) return defaults(null, 0);
            Path dir = config.saveDir == null ? Path.of("saves") : config.saveDir;
            Path path = dir.resolve(Config.cleanSaveName(config.saveName) + "-comms.properties");
            try {
                long modified = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
                if (previous != null && path.equals(previous.path) && modified == previous.modifiedAt) return previous;
                if (!Files.isRegularFile(path)) seed(path);
                modified = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(path)) { properties.load(in); }
                LinkedHashSet<String> muted = new LinkedHashSet<>();
                String rawMuted = properties.getProperty("mutedPlayers", "");
                for (String token : rawMuted.split("[,\\s]+")) {
                    String id = cleanPlayerSelector(token).toLowerCase(Locale.ROOT);
                    if (!id.isBlank() && muted.size() < 256) muted.add(id);
                }
                return new ServerCommsPolicy(
                        flag(properties, "enabled", true),
                        flag(properties, "global", true),
                        flag(properties, "system", true),
                        flag(properties, "team", true),
                        flag(properties, "direct", true),
                        flag(properties, "pings", true),
                        muted, path, modified);
            } catch (IOException ex) {
                if (previous != null) return previous;
                System.err.println("Could not load multiplayer comms policy: " + TextSafety.terminal(ex.getMessage()));
                return defaults(path, 0);
            }
        }

        private static void seed(Path path) throws IOException {
            if (path == null) return;
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Properties properties = new Properties();
            properties.setProperty("enabled", "true");
            properties.setProperty("global", "true");
            properties.setProperty("system", "true");
            properties.setProperty("team", "true");
            properties.setProperty("direct", "true");
            properties.setProperty("pings", "true");
            properties.setProperty("mutedPlayers", "");
            try (OutputStream out = Files.newOutputStream(path,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                properties.store(out,
                        "StarChem multiplayer communication policy. Edit while the server runs; changes reload within two seconds. "
                                + "System chat recipients are connected players currently viewing the same server-approved system. "
                                + "mutedPlayers is a comma-separated list of retained player IDs.");
            } catch (java.nio.file.FileAlreadyExistsException ignored) { }
        }

        private static boolean flag(Properties properties, String key, boolean fallback) {
            String value = properties.getProperty(key);
            return value == null ? fallback : Boolean.parseBoolean(value.trim());
        }

        private static ServerCommsPolicy defaults(Path path, long modifiedAt) {
            return new ServerCommsPolicy(true, true, true, true, true, true, Set.of(), path, modifiedAt);
        }
    }

    private static final class ServerPing {
        final long id;
        final String senderId;
        final TacticalPingType type;
        final String systemId;
        final PingTargetKind kind;
        final double x;
        final double y;
        final String targetSystemId;
        final long createdAtMs;

        ServerPing(long id, String senderId, TacticalPingType type, String systemId,
                   PingTargetKind kind, double x, double y, String targetSystemId, long createdAtMs) {
            this.id = id;
            this.senderId = senderId;
            this.type = type;
            this.systemId = systemId;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.targetSystemId = targetSystemId == null ? "" : targetSystemId;
            this.createdAtMs = createdAtMs;
        }

        ServerPing refreshed(double x, double y, String targetSystemId, long now) {
            return new ServerPing(id, senderId, type, systemId, kind, x, y, targetSystemId, now);
        }
    }
}