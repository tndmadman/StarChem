package com.tndmadman.rts;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

final class PeerServerSide {
    private static final long SNAPSHOT_MS = 100;
    private static final long GALAXY_MS = 1000;
    private static final long RESOURCE_CORRECTION_MS = 5000;
    private static final long TIMEOUT_MS = 4000;
    private static final long DISCONNECT_GRACE_MS = 60_000;
    private static final long RESUME_REPLAY_MS = 10_000;
    private static final double MAX_DEV_RESOURCE_AMOUNT = 100_000.0;
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    final World world;
    final Config config;
    final PeerTransport transport;
    final ClientViewCache views = new ClientViewCache();
    private final Map<String, ServerPeer> peers = new LinkedHashMap<>();
    private final Map<String, PlayerSession> sessions = new LinkedHashMap<>();
    private final Set<String> devRequests = new LinkedHashSet<>();
    private int nextPlayer = 1;
    private long sequence = 1, lastSnapshot, lastGalaxy, lastResourceCorrection;

    PeerServerSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
        PlayerRegistry.activate(world);
        SystemAudio.markNonRendered(world);
    }

    String statusLine() {
        int retained = Math.max(0, sessions.size() - peers.size());
        String result = "HOST " + world.systemName() + " TCP " + transport.localPort()
                + " | clients " + peers.size() + " | retained " + retained + " | queued " + transport.queuedCount();
        return result + (config.devMode ? " | dev host" : "");
    }

    void updateWorlds(double dt) {
        PlayerRegistry.activate(world);
        String old = world.activeSystemId();
        String[] systems = allKnownSystems();
        ResourceNetDebug.serverUpdateSystems(world, systems, dt);
        try {
            for (String systemId : systems) {
                world.activateSystem(systemId);
                world.updateCurrentSystem(dt);
            }
        } finally {
            world.activateSystem(old);
        }
    }

    private String[] allKnownSystems() {
        Set<String> out = new LinkedHashSet<>();
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        if (snapshot != null && !snapshot.empty()) {
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                out.add(system.id());
            }
        }
        if (out.isEmpty()) out.add(world.activeSystemId());
        out.removeIf(systemId -> systemId == null || systemId.isBlank() || systemId.contains("WAIT"));
        return out.toArray(new String[0]);
    }

    void tick(long now) {
        PlayerRegistry.activate(world);
        removeTimedOut(now);
        removeExpiredSessions(now);
        if (now - lastSnapshot >= SNAPSHOT_MS) {
            boolean fullResources = now - lastResourceCorrection >= RESOURCE_CORRECTION_MS;
            broadcastNow(fullResources);
            lastSnapshot = now;
            if (fullResources) lastResourceCorrection = now;
        }
        if (now - lastGalaxy >= GALAXY_MS) { broadcastGalaxy(); lastGalaxy = now; }
    }

    void handle(String message, NetPacket packet) {
        PlayerRegistry.activate(world);
        PeerServerPackets.handle(this, message, packet);
    }

    void connectionClosed(NetPacket packet) {
        if (packet == null || packet.address() == null) return;
        disconnectPeer(endpoint(packet.address(), packet.port()), System.currentTimeMillis(), "disconnected");
    }

    void broadcastNow() {
        broadcastNow(false);
    }

    private void broadcastNow(boolean fullResources) {
        sequence = PeerSyncBatch.send(world, views, PeerSyncTargets.array(peers.values()), sequence, fullResources, transport::send);
        broadcastLeaderboard();
    }

    void sendInitial(ServerPeer peer) {
        sequence = PeerSyncBatch.sendInitial(world, views, peer, sequence, transport::send);
        sendLeaderboard(peer);
        sendGalaxy(peer);
    }

    void sendInitialTo(String endpoint) { sendInitial(peers.get(endpoint)); }
    void change(String playerId, Runnable action) { views.applyChange(world, playerId, action); }
    String ownerId(String endpoint, String fallback) { ServerPeer peer = peers.get(endpoint); return peer == null ? fallback : peer.playerId(); }
    boolean owns(String endpoint, String playerId) { ServerPeer peer = peers.get(endpoint); return peer != null && playerId != null && playerId.equals(peer.playerId()); }
    boolean devAllowed(String endpoint, String playerId) {
        ServerPeer peer = peers.get(endpoint);
        return config.devMode && peer != null && playerId != null && playerId.equals(peer.playerId()) && peer.devFreeBuild();
    }
    boolean localDevAllowed(String playerId) { return config.devMode && playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId); }
    void touch(String endpoint) {
        ServerPeer peer = peers.get(endpoint);
        if (peer != null) peers.put(endpoint, new ServerPeer(peer.playerId(), peer.address(), peer.port(), System.currentTimeMillis(), peer.devFreeBuild()));
    }

    List<DevPeerAccess> devAccessPeers() {
        List<DevPeerAccess> out = new ArrayList<>();
        for (ServerPeer peer : peers.values()) {
            PlayerSession session = sessions.get(peer.playerId());
            String name = session == null ? peer.playerId() : session.name;
            out.add(new DevPeerAccess(peer.playerId(), name, devRequests.contains(peer.playerId()),
                    peer.devFreeBuild(), localHostPeer(peer)));
        }
        return List.copyOf(out);
    }

    void setDevAccess(String playerId, boolean enabled) {
        if (!config.devMode || playerId == null || playerId.isBlank()) return;
        for (Map.Entry<String, ServerPeer> entry : peers.entrySet()) {
            ServerPeer peer = entry.getValue();
            if (!playerId.equals(peer.playerId())) continue;
            if (localHostPeer(peer) && !enabled) return;
            ServerPeer updated = new ServerPeer(peer.playerId(), peer.address(), peer.port(), peer.lastSeen(), enabled);
            entry.setValue(updated);
            PlayerSession session = sessions.get(playerId);
            if (session != null) session.devFreeBuild = enabled;
            world.setDevFreeBuild(playerId, enabled);
            transport.sendOrdered("DEVSTATUS|" + (enabled ? "1" : "0"), peer.address(), peer.port());
            String name = session == null ? playerId : session.name;
            world.status = "Dev access " + (enabled ? "granted to " : "revoked from ") + name + ".";
            System.out.println(world.status);
            broadcastNow();
            return;
        }
    }

    void join(String endpoint, InetAddress address, int port, String name, boolean requestedDev, String suppliedDevToken) {
        String cleanName = Config.clean(name);
        ServerPeer existingPeer = peers.get(endpoint);
        if (existingPeer != null) {
            PlayerSession existingSession = sessions.get(existingPeer.playerId());
            if (existingSession != null) sendSessionState(existingSession, existingPeer, existingSession.currentToken);
            return;
        }
        if (nameInUse(cleanName)) {
            transport.sendOrdered(joinDenied("Name already in use: " + cleanName), address, port);
            return;
        }
        String id = "P" + nextPlayer++;
        int rgb = colorFor(sessions.size() + 1);
        boolean devAllowed = DevAccessPolicy.authorize(config.devMode, config.dedicatedServerMode(), address,
                requestedDev, config.devToken, suppliedDevToken);
        if (requestedDev) devRequests.add(id);
        auditDevRequest(cleanName, address, port, requestedDev, devAllowed);
        String token = newSessionToken();
        PlayerSession session = new PlayerSession(id, cleanName, rgb, token, digestToken(token), endpoint, true, 0, devAllowed);
        sessions.put(id, session);
        ServerPeer peer = new ServerPeer(id, address, port, System.currentTimeMillis(), devAllowed);
        peers.put(endpoint, peer);
        PlayerRegistry.register(id, cleanName, rgb, false);
        world.setDevFreeBuild(id, devAllowed);
        WorldNetAccess.addPeerGroup(world, id);
        views.setHome(world, id);
        sendSessionState(session, peer, token);
    }

    boolean resume(String endpoint, InetAddress address, int port, String playerId, String token,
                   boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        PlayerSession session = sessions.get(playerId);
        if (session == null || sessionExpired(session, now) || !tokenMatches(session, token, endpoint, now)) {
            if (session != null && sessionExpired(session, now)) destroySession(playerId);
            transport.sendOrdered(sessionDenied("Session expired or token was rejected."), address, port);
            return false;
        }

        if (session.connected && endpoint.equals(session.endpoint)) {
            ServerPeer currentPeer = peers.get(endpoint);
            if (currentPeer != null && playerId.equals(currentPeer.playerId())) {
                sendSessionState(session, currentPeer, session.currentToken);
                return true;
            }
        }

        if (session.connected && session.endpoint != null && !session.endpoint.isBlank()
                && !session.endpoint.equals(endpoint)) {
            transport.sendOrdered(sessionBusy("Session is already active on another connection."), address, port);
            return false;
        }

        ServerPeer endpointOwner = peers.get(endpoint);
        if (endpointOwner != null && !playerId.equals(endpointOwner.playerId())) disconnectPeer(endpoint, now, "replaced");

        boolean devAllowed = DevAccessPolicy.authorize(config.devMode, config.dedicatedServerMode(), address,
                requestedDev, config.devToken, suppliedDevToken);
        if (requestedDev) devRequests.add(playerId); else devRequests.remove(playerId);
        auditDevRequest(session.name, address, port, requestedDev, devAllowed);

        ServerPeer peer = new ServerPeer(playerId, address, port, now, devAllowed);
        peers.put(endpoint, peer);
        session.endpoint = endpoint;
        session.connected = true;
        session.disconnectedAt = 0;
        session.devFreeBuild = devAllowed;
        PlayerRegistry.register(playerId, session.name, session.rgb, false);
        world.setDevFreeBuild(playerId, devAllowed);
        String rotatedToken = rotateToken(session, now);
        sendSessionState(session, peer, rotatedToken);
        world.status = "Reconnected " + session.name + " as " + playerId + ".";
        return true;
    }

    void applyDevFreeCrafting(String playerId, boolean enabled) {
        change(playerId, () -> {
            world.setDevFreeBuild(playerId, enabled);
            PlayerSession session = sessions.get(playerId);
            if (session != null) session.devFreeBuild = enabled;
            world.status = "Dev free crafting " + (enabled ? "enabled" : "disabled") + " for " + playerId + ".";
        });
        broadcastNow();
    }

    void applyDevHangarResource(String playerId, String baseId, Material material, double amount) {
        if (baseId == null || baseId.isBlank() || material == null || amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        double safeAmount = Math.min(amount, MAX_DEV_RESOURCE_AMOUNT);
        change(playerId, () -> {
            Base base = world.bases.get(baseId);
            if (base == null || !playerId.equals(base.playerId)) return;
            HangarStore.add(base.inventory, material, safeAmount);
            world.status = "Dev added " + (int)safeAmount + " " + material.label + " to " + base.id + " hangar.";
        });
        broadcastNow();
    }

    void applyDevAiCommand(String playerId, String command) {
        if (command == null || command.isBlank()) return;
        change(playerId, () -> applyAiCommand(command));
        broadcastNow();
    }

    void removePeer(String endpoint) {
        disconnectPeer(endpoint, System.currentTimeMillis(), "left");
    }

    private void disconnectPeer(String endpoint, long now, String reason) {
        ServerPeer peer = peers.remove(endpoint);
        if (peer == null) return;
        transport.closeConnection(peer.address(), peer.port());
        PlayerSession session = sessions.get(peer.playerId());
        if (session == null) return;
        session.endpoint = "";
        session.connected = false;
        session.disconnectedAt = now;
        session.devFreeBuild = false;
        devRequests.remove(peer.playerId());
        world.setDevFreeBuild(peer.playerId(), false);
        world.status = session.name + " " + reason + "; session retained for " + (DISCONNECT_GRACE_MS / 1000) + " seconds.";
        broadcastNow();
    }

    private void destroySession(String playerId) {
        PlayerSession session = sessions.remove(playerId);
        if (session == null) return;
        if (session.endpoint != null && !session.endpoint.isBlank()) {
            ServerPeer peer = peers.remove(session.endpoint);
            if (peer != null) transport.closeConnection(peer.address(), peer.port());
        }
        devRequests.remove(playerId);
        world.setDevFreeBuild(playerId, false);
        PlayerRegistry.remove(playerId);
        views.remove(playerId);
        Set<String> deletedSystems = world.removePlayerAndPruneEmptySystems(playerId);
        views.removeSystems(deletedSystems);
        sendDeletedSystems(deletedSystems);
        world.status = deletedSystems.isEmpty()
                ? "Expired disconnected session " + playerId + "."
                : "Removed " + deletedSystems.size() + " abandoned system(s) after session " + playerId + " expired.";
        broadcastNow();
    }

    private void broadcastLeaderboard() {
        if (peers.isEmpty()) return;
        List<LeaderboardEntry> entries = GlobalLeaderboard.aggregate(world, allKnownSystems());
        GlobalLeaderboard.set(world, entries);
        String message = GlobalLeaderboard.encode(entries);
        for (ServerPeer peer : peers.values()) transport.send(message, peer.address(), peer.port());
    }

    private void sendLeaderboard(ServerPeer peer) {
        if (peer == null) return;
        List<LeaderboardEntry> entries = GlobalLeaderboard.aggregate(world, allKnownSystems());
        GlobalLeaderboard.set(world, entries);
        transport.sendOrdered(GlobalLeaderboard.encode(entries), peer.address(), peer.port());
    }

    private void broadcastGalaxy() {
        if (peers.isEmpty()) return;
        String message = GalaxyMapWire.encode(config.galaxyCopies, world.authoritativeGalaxyMapSnapshot());
        for (ServerPeer peer : peers.values()) transport.send(message, peer.address(), peer.port());
    }

    private void sendGalaxy(ServerPeer peer) {
        if (peer == null) return;
        String message = GalaxyMapWire.encode(config.galaxyCopies, world.authoritativeGalaxyMapSnapshot());
        transport.sendOrdered(message, peer.address(), peer.port());
    }

    private void sendSessionState(PlayerSession session, ServerPeer peer, String token) {
        if (session == null || peer == null) return;
        transport.sendOrdered(welcome(session.playerId, session.name, session.rgb, peer.devFreeBuild(), token), peer.address(), peer.port());
        transport.sendOrdered(envMessage(), peer.address(), peer.port());
        sendInitial(peer);
    }

    private void applyAiCommand(String command) {
        switch (command) {
            case "togglePauseAi" -> AiDevSettings.pauseAi = !AiDevSettings.pauseAi;
            case "stepAi" -> AiDevSettings.stepAi = true;
            case "toggleFastAi" -> AiDevSettings.fastAi = !AiDevSettings.fastAi;
            case "toggleFreezePlayerUnits" -> AiDevSettings.freezePlayerUnits = !AiDevSettings.freezePlayerUnits;
            case "toggleFreezeNpcCombat" -> AiDevSettings.freezeNpcCombat = !AiDevSettings.freezeNpcCombat;
            case "toggleDisableAttacks" -> AiDevSettings.disableAttacks = !AiDevSettings.disableAttacks;
            case "toggleDisableEconomy" -> AiDevSettings.disableEconomy = !AiDevSettings.disableEconomy;
            case "disableProductionTimers" -> DevTimerSettings.configure(world, true);
            case "enableProductionTimers" -> DevTimerSettings.configure(world, false);
            case "togglePreset" -> AiDevSettings.togglePreset();
            case "spawnCorsairs" -> AiDevCommands.spawnCorsairs(world);
            case "killCorsairs" -> AiDevCommands.killCorsairs(world);
            case "resetCorsairs" -> AiDevCommands.resetCorsairs(world);
            case "giveCorsairResources" -> AiDevCommands.giveCorsairResources(world);
            case "givePlayerResources" -> AiDevCommands.givePlayerResources(world);
            case "spawnLootField" -> AiDevCommands.spawnLootField(world);
            case "spawnAttackWave" -> AiDevCommands.spawnAttackWave(world);
            case "forceRaid" -> AiDevCommands.forceRaid(world);
            case "forceStation" -> AiDevCommands.forceStation(world);
            case "forceResearch" -> AiDevCommands.forceResearch(world);
            case "forceCraft" -> AiDevCommands.forceCraft(world);
            default -> { }
        }
    }

    private void sendDeletedSystems(Set<String> deletedSystems) {
        if (deletedSystems == null || deletedSystems.isEmpty() || peers.isEmpty()) return;
        String message = "SYSDEL|" + String.join(";", deletedSystems);
        for (ServerPeer peer : peers.values()) transport.sendOrdered(message, peer.address(), peer.port());
    }

    private void auditDevRequest(String name, InetAddress address, int port, boolean requestedDev, boolean allowed) {
        if (!requestedDev) return;
        String source = address == null ? "unknown" : address.getHostAddress() + ':' + port;
        System.out.println("Dev access " + (allowed ? "granted" : "denied") + " for " + packetPart(name) + " from " + source + '.');
    }

    private boolean localHostPeer(ServerPeer peer) {
        return peer != null && !config.dedicatedServerMode() && peer.address() != null && peer.address().isLoopbackAddress();
    }

    private String normalizedName(String name) { return Config.clean(name).toLowerCase(Locale.ROOT); }

    private boolean nameInUse(String name) {
        String wanted = normalizedName(name);
        for (PlayerSession session : sessions.values()) if (wanted.equals(normalizedName(session.name))) return true;
        return false;
    }

    private void removeTimedOut(long now) {
        for (String endpoint : new ArrayList<>(peers.keySet())) {
            ServerPeer peer = peers.get(endpoint);
            if (peer != null && now - peer.lastSeen() > TIMEOUT_MS) disconnectPeer(endpoint, now, "timed out");
        }
    }

    private void removeExpiredSessions(long now) {
        for (String playerId : new ArrayList<>(sessions.keySet())) {
            PlayerSession session = sessions.get(playerId);
            if (session != null && sessionExpired(session, now)) destroySession(playerId);
        }
    }

    private boolean sessionExpired(PlayerSession session, long now) {
        return session != null && !session.connected && session.disconnectedAt > 0
                && now - session.disconnectedAt > DISCONNECT_GRACE_MS;
    }

    private String rotateToken(PlayerSession session, long now) {
        String token = newSessionToken();
        session.previousTokenDigest = session.tokenDigest;
        session.previousTokenValidUntil = now + RESUME_REPLAY_MS;
        session.currentToken = token;
        session.tokenDigest = digestToken(token);
        return token;
    }

    private String newSessionToken() {
        byte[] bytes = new byte[32];
        SESSION_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean tokenMatches(PlayerSession session, String token, String endpoint, long now) {
        if (session == null || token == null || token.isBlank()) return false;
        byte[] candidate = digestToken(token);
        if (MessageDigest.isEqual(session.tokenDigest, candidate)) return true;
        return session.connected && endpoint != null && endpoint.equals(session.endpoint)
                && session.previousTokenDigest != null && now <= session.previousTokenValidUntil
                && MessageDigest.isEqual(session.previousTokenDigest, candidate);
    }

    private byte[] digestToken(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private String joinDenied(String message) { return "JOIN_DENIED|" + packetPart(message); }
    private String sessionBusy(String message) { return "SESSION_BUSY|" + packetPart(message); }
    private String sessionDenied(String message) { return "SESSION_DENIED|" + packetPart(message); }
    private String packetPart(String value) { return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }
    boolean requestedDev(String[] parts) { return parts.length > 2 && flag(parts[2]); }
    String requestedDevToken(String[] parts) { return parts.length > 3 ? parts[3] : ""; }
    boolean requestedResumeDev(String[] parts) { return parts.length > 3 && flag(parts[3]); }
    String requestedResumeDevToken(String[] parts) { return parts.length > 4 ? parts[4] : ""; }
    String endpoint(InetAddress address, int port) { return address.getHostAddress() + ':' + port; }
    static long disconnectGraceMs() { return DISCONNECT_GRACE_MS; }
    private String envMessage() { return "ENV|" + world.systemId() + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()); }
    private String welcome(String id, String name, int rgb, boolean devAllowed, String token) {
        return "WELCOME|" + id + "|" + Config.clean(name) + "|" + rgb + "|" + world.systemId() + "|"
                + world.systemSeed() + "|" + Calc.round(world.systemTime()) + "|DEV|" + (devAllowed ? "1" : "0")
                + "|SESSION|" + token;
    }
    private int colorFor(int i) { int[] colors = {0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C}; return colors[Math.floorMod(i, colors.length)]; }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }

    private static final class PlayerSession {
        final String playerId;
        final String name;
        final int rgb;
        String currentToken;
        byte[] tokenDigest;
        byte[] previousTokenDigest;
        long previousTokenValidUntil;
        String endpoint;
        boolean connected;
        long disconnectedAt;
        boolean devFreeBuild;

        PlayerSession(String playerId, String name, int rgb, String currentToken, byte[] tokenDigest, String endpoint,
                      boolean connected, long disconnectedAt, boolean devFreeBuild) {
            this.playerId = playerId;
            this.name = name;
            this.rgb = rgb;
            this.currentToken = currentToken;
            this.tokenDigest = tokenDigest;
            this.endpoint = endpoint;
            this.connected = connected;
            this.disconnectedAt = disconnectedAt;
            this.devFreeBuild = devFreeBuild;
        }
    }
}
