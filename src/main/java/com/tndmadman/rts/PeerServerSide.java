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
    private static final long AUTH_CHALLENGE_MS = 30_000;
    private static final double MAX_DEV_RESOURCE_AMOUNT = 100_000.0;
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    final World world;
    final Config config;
    final PeerTransport transport;
    final ClientViewCache views = new ClientViewCache();
    private final Map<ConnectionId, ServerPeer> peers = new LinkedHashMap<>();
    private final Map<String, PlayerSession> sessions = new LinkedHashMap<>();
    private final Map<ConnectionId, AuthChallenge> authChallenges = new LinkedHashMap<>();
    private final Set<String> devRequests = new LinkedHashSet<>();
    private int nextPlayer = 1;
    private long sequence = 1, lastSnapshot, lastGalaxy, lastResourceCorrection;

    PeerServerSide(Config config, World world, PeerTransport transport) {
        this(config, world, transport, List.of());
    }

    PeerServerSide(Config config, World world, PeerTransport transport, List<PersistentPlayerSession> restoredSessions) {
        this.config = config;
        this.world = world;
        this.transport = transport;
        PlayerRegistry.activate(world);
        SystemAudio.markNonRendered(world);
        restorePersistentSessions(restoredSessions);
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
        removeExpiredAuthChallenges(now);
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
        if (packet == null || !packet.connectionId().valid()) return;
        disconnectPeer(packet.connectionId(), System.currentTimeMillis(), "disconnected");
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

    void sendInitialTo(ConnectionId connectionId) { sendInitial(peers.get(connectionId)); }
    void change(String playerId, Runnable action) { views.applyChange(world, playerId, action); }
    String ownerId(ConnectionId connectionId, String fallback) { ServerPeer peer = peers.get(connectionId); return peer == null ? fallback : peer.playerId(); }
    boolean owns(ConnectionId connectionId, String playerId) { ServerPeer peer = peers.get(connectionId); return peer != null && playerId != null && playerId.equals(peer.playerId()); }
    boolean devAllowed(ConnectionId connectionId, String playerId) {
        ServerPeer peer = peers.get(connectionId);
        return config.devMode && peer != null && playerId != null && playerId.equals(peer.playerId()) && peer.devFreeBuild();
    }
    boolean localDevAllowed(String playerId) { return config.devMode && playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId); }
    ConnectionId connectionIdForPlayer(String playerId) {
        for (ServerPeer peer : peers.values()) if (peer.playerId().equals(playerId)) return peer.connectionId();
        return ConnectionId.NONE;
    }
    int peerCount() { return peers.size(); }
    boolean sessionConnected(String playerId) {
        PlayerSession session = sessions.get(playerId);
        return session != null && session.connected;
    }

    List<PersistentPlayerSession> persistentSessions() {
        List<PersistentPlayerSession> out = new ArrayList<>();
        for (PlayerSession session : sessions.values()) {
            if (session == null || session.playerId == null || session.playerId.isBlank()) continue;
            out.add(new PersistentPlayerSession(session.playerId, session.name, session.rgb,
                    session.passwordSalt, session.passwordDigest, session.tokenDigest,
                    session.previousTokenDigest, session.previousTokenValidUntil));
        }
        return List.copyOf(out);
    }
    void touch(ConnectionId connectionId) {
        ServerPeer peer = peers.get(connectionId);
        if (peer != null) peers.put(connectionId, new ServerPeer(peer.playerId(), peer.connectionId(), peer.address(), peer.port(), System.currentTimeMillis(), peer.devFreeBuild()));
    }
    void requestView(ConnectionId connectionId, String playerId, String systemId, long revision) {
        touch(connectionId);
        if (!owns(connectionId, playerId)) return;
        if (!views.requestView(world, playerId, systemId, revision)) {
            long deniedRevision = Math.max(0, revision);
            String retainedView = views.view(world, playerId);
            views.setViewRevision(playerId, deniedRevision);
            transport.sendOrdered("VIEW_DENIED|" + deniedRevision + "|" + packetPart(retainedView) + "|"
                    + packetPart("Unknown galaxy system: " + systemId), connectionId);
            sendInitialTo(connectionId);
            return;
        }
        sendInitialTo(connectionId);
    }

    void forceResourceCorrectionForTest() {
        lastSnapshot = 0;
        lastResourceCorrection = 0;
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
        for (Map.Entry<ConnectionId, ServerPeer> entry : peers.entrySet()) {
            ServerPeer peer = entry.getValue();
            if (!playerId.equals(peer.playerId())) continue;
            if (localHostPeer(peer) && !enabled) return;
            ServerPeer updated = new ServerPeer(peer.playerId(), peer.connectionId(), peer.address(), peer.port(), peer.lastSeen(), enabled);
            entry.setValue(updated);
            PlayerSession session = sessions.get(playerId);
            if (session != null) session.devFreeBuild = enabled;
            world.setDevFreeBuild(playerId, enabled);
            transport.sendOrdered("DEVSTATUS|" + (enabled ? "1" : "0"), peer.connectionId());
            String name = session == null ? playerId : session.name;
            world.status = "Dev access " + (enabled ? "granted to " : "revoked from ") + name + ".";
            System.out.println(world.status);
            broadcastNow();
            return;
        }
    }

    void join(ConnectionId connectionId, InetAddress address, int port, String name, boolean requestedDev, String suppliedDevToken) {
        join(connectionId, address, port, name, "", "", "", requestedDev, suppliedDevToken);
    }

    void join(ConnectionId connectionId, InetAddress address, int port, String name, String passwordVerifier,
              boolean requestedDev, String suppliedDevToken) {
        String cleanName = Config.clean(name);
        ServerPeer existingPeer = peers.get(connectionId);
        if (existingPeer != null) {
            PlayerSession existingSession = sessions.get(existingPeer.playerId());
            if (existingSession != null) sendSessionState(existingSession, existingPeer, existingSession.currentToken);
            return;
        }
        PlayerSession namedSession = sessionByName(cleanName);
        if (namedSession != null) {
            byte[] passwordVerifierBytes = PasswordAuth.decodeVerifier(passwordVerifier);
            if (passwordVerifierBytes.length == 0) {
                transport.sendOrdered(joinDenied("Player password is required for server identity."), connectionId);
                return;
            }
            reclaimByPassword(connectionId, address, port, namedSession, passwordVerifierBytes, requestedDev, suppliedDevToken);
            return;
        }
        join(connectionId, address, port, name, passwordVerifier, "", "", requestedDev, suppliedDevToken);
    }

    void join(ConnectionId connectionId, InetAddress address, int port, String name, String passwordVerifier,
              String proofNonce, String proof, boolean requestedDev, String suppliedDevToken) {
        String cleanName = Config.clean(name);
        ServerPeer existingPeer = peers.get(connectionId);
        if (existingPeer != null) {
            PlayerSession existingSession = sessions.get(existingPeer.playerId());
            if (existingSession != null) sendSessionState(existingSession, existingPeer, existingSession.currentToken);
            return;
        }
        PlayerSession namedSession = sessionByName(cleanName);
        if (namedSession != null) {
            if (PasswordAuth.validVerifier(proof) && PasswordAuth.validNonce(proofNonce)) {
                reclaimByProof(connectionId, address, port, namedSession, proofNonce, proof, requestedDev, suppliedDevToken);
            } else {
                issueAuthChallenge(connectionId, cleanName, namedSession);
            }
            return;
        }
        byte[] passwordVerifierBytes = PasswordAuth.decodeVerifier(passwordVerifier);
        if (passwordVerifierBytes.length == 0) {
            transport.sendOrdered(authRequired(), connectionId);
            return;
        }
        String id = "P" + nextPlayer++;
        int rgb = colorFor(sessions.size() + 1);
        boolean devAllowed = DevAccessPolicy.authorize(config.devMode, config.dedicatedServerMode(), address,
                requestedDev, config.devToken, suppliedDevToken);
        if (requestedDev) devRequests.add(id);
        auditDevRequest(cleanName, address, port, requestedDev, devAllowed);
        String token = newSessionToken();
        byte[] passwordSalt = PasswordAuth.newSalt();
        PlayerSession session = new PlayerSession(id, cleanName, rgb, passwordSalt,
                PasswordAuth.serverDigest(passwordVerifierBytes, passwordSalt), token, digestToken(token),
                connectionId, true, 0, devAllowed);
        sessions.put(id, session);
        ServerPeer peer = new ServerPeer(id, connectionId, address, port, System.currentTimeMillis(), devAllowed);
        peers.put(connectionId, peer);
        PlayerRegistry.register(id, cleanName, rgb, false);
        world.setDevFreeBuild(id, devAllowed);
        WorldNetAccess.addPeerGroup(world, id);
        views.setHome(world, id);
        sendSessionState(session, peer, token);
    }

    private void issueAuthChallenge(ConnectionId connectionId, String cleanName, PlayerSession session) {
        if (connectionId == null || !connectionId.valid() || session == null
                || session.passwordSalt == null || session.passwordSalt.length == 0
                || session.passwordDigest == null || session.passwordDigest.length == 0) {
            transport.sendOrdered(joinDenied("Player password is required for server identity."), connectionId);
            return;
        }
        String nonce = PasswordAuth.newNonce();
        authChallenges.put(connectionId, new AuthChallenge(cleanName, nonce, System.currentTimeMillis()));
        transport.sendOrdered("AUTH_CHALLENGE|" + packetPart(cleanName) + "|"
                + PasswordAuth.encodeVerifier(session.passwordSalt) + "|" + nonce, connectionId);
    }

    private void reclaimByProof(ConnectionId connectionId, InetAddress address, int port, PlayerSession session,
                                String proofNonce, String proof, boolean requestedDev, String suppliedDevToken) {
        AuthChallenge challenge = authChallenges.remove(connectionId);
        if (challenge == null || !challenge.nonce.equals(proofNonce)
                || !normalizedName(session.name).equals(normalizedName(challenge.name))
                || System.currentTimeMillis() - challenge.createdAt > AUTH_CHALLENGE_MS
                || !PasswordAuth.proofMatches(session.passwordDigest, session.name, proofNonce, proof)) {
            transport.sendOrdered(joinDenied("Password rejected for " + session.name + "."), connectionId);
            return;
        }
        bindReclaimedSession(connectionId, address, port, session, requestedDev, suppliedDevToken);
    }

    private void reclaimByPassword(ConnectionId connectionId, InetAddress address, int port, PlayerSession session,
                                   byte[] passwordVerifier, boolean requestedDev, String suppliedDevToken) {
        if (session.passwordDigest == null || session.passwordDigest.length == 0) {
            session.passwordSalt = PasswordAuth.newSalt();
            session.passwordDigest = PasswordAuth.serverDigest(passwordVerifier, session.passwordSalt);
        } else if (session.passwordSalt == null || session.passwordSalt.length == 0) {
            if (!MessageDigest.isEqual(session.passwordDigest, passwordVerifier)) {
                transport.sendOrdered(joinDenied("Password rejected for " + session.name + "."), connectionId);
                return;
            }
            session.passwordSalt = PasswordAuth.newSalt();
            session.passwordDigest = PasswordAuth.serverDigest(passwordVerifier, session.passwordSalt);
        } else if (!MessageDigest.isEqual(session.passwordDigest, PasswordAuth.serverDigest(passwordVerifier, session.passwordSalt))) {
            transport.sendOrdered(joinDenied("Password rejected for " + session.name + "."), connectionId);
            return;
        }
        bindReclaimedSession(connectionId, address, port, session, requestedDev, suppliedDevToken);
    }

    private void bindReclaimedSession(ConnectionId connectionId, InetAddress address, int port, PlayerSession session,
                                      boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        if (session.connected && session.connectionId != null && session.connectionId.valid()
                && !session.connectionId.equals(connectionId)) {
            transport.sendOrdered(sessionBusy("Session is already active on another connection."), connectionId);
            return;
        }
        ServerPeer connectionOwner = peers.get(connectionId);
        if (connectionOwner != null && !session.playerId.equals(connectionOwner.playerId())) disconnectPeer(connectionId, now, "replaced");

        boolean devAllowed = DevAccessPolicy.authorize(config.devMode, config.dedicatedServerMode(), address,
                requestedDev, config.devToken, suppliedDevToken);
        if (requestedDev) devRequests.add(session.playerId); else devRequests.remove(session.playerId);
        auditDevRequest(session.name, address, port, requestedDev, devAllowed);
        ServerPeer peer = new ServerPeer(session.playerId, connectionId, address, port, now, devAllowed);
        peers.put(connectionId, peer);
        session.connectionId = connectionId;
        session.connected = true;
        session.disconnectedAt = 0;
        session.devFreeBuild = devAllowed;
        PlayerRegistry.register(session.playerId, session.name, session.rgb, false);
        world.setDevFreeBuild(session.playerId, devAllowed);
        String rotatedToken = rotateToken(session, now);
        sendSessionState(session, peer, rotatedToken);
        world.status = "Reclaimed " + session.name + " as " + session.playerId + ".";
    }

    boolean resume(ConnectionId connectionId, InetAddress address, int port, String playerId, String token,
                   boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        PlayerSession session = sessions.get(playerId);
        if (session == null || sessionExpired(session, now) || !tokenMatches(session, token, connectionId, now)) {
            if (session != null && sessionExpired(session, now)) destroySession(playerId);
            transport.sendOrdered(sessionDenied("Session expired or token was rejected."), connectionId);
            return false;
        }

        if (session.connected && connectionId.equals(session.connectionId)) {
            ServerPeer currentPeer = peers.get(connectionId);
            if (currentPeer != null && playerId.equals(currentPeer.playerId())) {
                sendSessionState(session, currentPeer, session.currentToken);
                return true;
            }
        }

        if (session.connected && session.connectionId != null && session.connectionId.valid()
                && !session.connectionId.equals(connectionId)) {
            transport.sendOrdered(sessionBusy("Session is already active on another connection."), connectionId);
            return false;
        }

        ServerPeer connectionOwner = peers.get(connectionId);
        if (connectionOwner != null && !playerId.equals(connectionOwner.playerId())) disconnectPeer(connectionId, now, "replaced");

        boolean devAllowed = DevAccessPolicy.authorize(config.devMode, config.dedicatedServerMode(), address,
                requestedDev, config.devToken, suppliedDevToken);
        if (requestedDev) devRequests.add(playerId); else devRequests.remove(playerId);
        auditDevRequest(session.name, address, port, requestedDev, devAllowed);

        ServerPeer peer = new ServerPeer(playerId, connectionId, address, port, now, devAllowed);
        peers.put(connectionId, peer);
        session.connectionId = connectionId;
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

    void removePeer(ConnectionId connectionId) {
        disconnectPeer(connectionId, System.currentTimeMillis(), "left");
    }

    private void disconnectPeer(ConnectionId connectionId, long now, String reason) {
        authChallenges.remove(connectionId);
        ServerPeer peer = peers.remove(connectionId);
        if (peer == null) return;
        transport.closeConnection(peer.connectionId());
        PlayerSession session = sessions.get(peer.playerId());
        if (session == null) return;
        session.connectionId = ConnectionId.NONE;
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
        if (session.connectionId != null && session.connectionId.valid()) {
            ServerPeer peer = peers.remove(session.connectionId);
            if (peer != null) transport.closeConnection(peer.connectionId());
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
        for (ServerPeer peer : peers.values()) transport.send(message, peer.connectionId(), DeliveryClass.LEADERBOARD);
    }

    private void sendLeaderboard(ServerPeer peer) {
        if (peer == null) return;
        List<LeaderboardEntry> entries = GlobalLeaderboard.aggregate(world, allKnownSystems());
        GlobalLeaderboard.set(world, entries);
        transport.send(GlobalLeaderboard.encode(entries), peer.connectionId(), DeliveryClass.LEADERBOARD);
    }

    private void broadcastGalaxy() {
        if (peers.isEmpty()) return;
        String message = GalaxyMapWire.encode(config.galaxyCopies, world.authoritativeGalaxyMapSnapshot());
        for (ServerPeer peer : peers.values()) transport.send(message, peer.connectionId(), DeliveryClass.GALAXY);
    }

    private void sendGalaxy(ServerPeer peer) {
        if (peer == null) return;
        String message = GalaxyMapWire.encode(config.galaxyCopies, world.authoritativeGalaxyMapSnapshot());
        transport.send(message, peer.connectionId(), DeliveryClass.GALAXY);
    }

    private void sendSessionState(PlayerSession session, ServerPeer peer, String token) {
        if (session == null || peer == null) return;
        transport.sendOrdered(welcome(session.playerId, session.name, session.rgb, peer.devFreeBuild(), token), peer.connectionId());
        transport.sendOrdered(envMessage(), peer.connectionId());
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
        for (ServerPeer peer : peers.values()) transport.sendOrdered(message, peer.connectionId());
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

    private PlayerSession sessionByName(String name) {
        String wanted = normalizedName(name);
        for (PlayerSession session : sessions.values()) if (wanted.equals(normalizedName(session.name))) return session;
        return null;
    }

    private void restorePersistentSessions(List<PersistentPlayerSession> restoredSessions) {
        if (restoredSessions == null || restoredSessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (PersistentPlayerSession restored : restoredSessions) {
            if (restored == null || restored.playerId().isBlank() || restored.tokenDigest().length == 0) continue;
            byte[] previous = restored.previousTokenDigest().length == 0 ? null : restored.previousTokenDigest();
            PlayerSession session = new PlayerSession(restored.playerId(), restored.name(), restored.rgb(),
                    restored.passwordSalt(), restored.passwordDigest(), "",
                    restored.tokenDigest(), ConnectionId.NONE, false, 0, false);
            session.previousTokenDigest = previous;
            session.previousTokenValidUntil = Math.max(0, restored.previousTokenValidUntil());
            sessions.put(session.playerId, session);
            PlayerRegistry.register(session.playerId, session.name, session.rgb, false);
            views.setHome(world, session.playerId);
            try {
                String suffix = session.playerId.startsWith("P") ? session.playerId.substring(1) : "";
                if (!suffix.isBlank()) nextPlayer = Math.max(nextPlayer, Integer.parseInt(suffix) + 1);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void removeTimedOut(long now) {
        for (ConnectionId connectionId : new ArrayList<>(peers.keySet())) {
            ServerPeer peer = peers.get(connectionId);
            if (peer != null && now - peer.lastSeen() > TIMEOUT_MS) disconnectPeer(connectionId, now, "timed out");
        }
    }

    private void removeExpiredAuthChallenges(long now) {
        authChallenges.entrySet().removeIf(entry -> now - entry.getValue().createdAt > AUTH_CHALLENGE_MS);
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

    private boolean tokenMatches(PlayerSession session, String token, ConnectionId connectionId, long now) {
        if (session == null || token == null || token.isBlank()) return false;
        byte[] candidate = digestToken(token);
        if (MessageDigest.isEqual(session.tokenDigest, candidate)) return true;
        return session.connected && connectionId != null && connectionId.equals(session.connectionId)
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

    private String authRequired() { return "AUTH_REQUIRED|REGISTER"; }
    private String joinDenied(String message) { return "JOIN_DENIED|" + packetPart(message); }
    private String sessionBusy(String message) { return "SESSION_BUSY|" + packetPart(message); }
    private String sessionDenied(String message) { return "SESSION_DENIED|" + packetPart(message); }
    private String packetPart(String value) { return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }
    boolean requestedDev(String[] parts) { return parts.length > 2 && flag(parts[2]); }
    String requestedDevToken(String[] parts) { return parts.length > 3 ? parts[3] : ""; }
    boolean requestedResumeDev(String[] parts) { return parts.length > 3 && flag(parts[3]); }
    String requestedResumeDevToken(String[] parts) { return parts.length > 4 ? parts[4] : ""; }
    static long disconnectGraceMs() { return DISCONNECT_GRACE_MS; }
    private String envMessage() { return "ENV|" + world.systemId() + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()); }
    private String welcome(String id, String name, int rgb, boolean devAllowed, String token) {
        return "WELCOME|" + id + "|" + Config.clean(name) + "|" + rgb + "|" + world.systemId() + "|"
                + world.systemSeed() + "|" + Calc.round(world.systemTime()) + "|DEV|" + (devAllowed ? "1" : "0")
                + "|SESSION|" + token;
    }
    private int colorFor(int i) { int[] colors = {0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C}; return colors[Math.floorMod(i, colors.length)]; }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }

    private record AuthChallenge(String name, String nonce, long createdAt) { }

    private static final class PlayerSession {
        final String playerId;
        final String name;
        final int rgb;
        byte[] passwordSalt;
        byte[] passwordDigest;
        String currentToken;
        byte[] tokenDigest;
        byte[] previousTokenDigest;
        long previousTokenValidUntil;
        ConnectionId connectionId;
        boolean connected;
        long disconnectedAt;
        boolean devFreeBuild;

        PlayerSession(String playerId, String name, int rgb, byte[] passwordSalt, byte[] passwordDigest,
                      String currentToken, byte[] tokenDigest, ConnectionId connectionId,
                      boolean connected, long disconnectedAt, boolean devFreeBuild) {
            this.playerId = playerId;
            this.name = name;
            this.rgb = rgb;
            this.passwordSalt = passwordSalt == null ? new byte[0] : passwordSalt;
            this.passwordDigest = passwordDigest == null ? new byte[0] : passwordDigest;
            this.currentToken = currentToken;
            this.tokenDigest = tokenDigest;
            this.connectionId = connectionId;
            this.connected = connected;
            this.disconnectedAt = disconnectedAt;
            this.devFreeBuild = devFreeBuild;
        }
    }
}
