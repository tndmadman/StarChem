package com.tndmadman.rts;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

final class PeerServerSide {
    private static final long SNAPSHOT_MS = 100;
    private static final long GALAXY_MS = 1000;
    private static final long RESOURCE_CORRECTION_MS = 5000;
    private static final long TIMEOUT_MS = 4000;
    private static final long RESUME_REPLAY_MS = 10_000;
    private static final long AUTH_CHALLENGE_MS = 30_000;
    private static final double MAX_DEV_RESOURCE_AMOUNT = 100_000.0;
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    final World world;
    final Config config;
    final PeerTransport transport;
    final ClientViewCache views = new ClientViewCache();
    private final AuthoritativeSystemScheduler systemScheduler = new AuthoritativeSystemScheduler();
    private final Map<ConnectionId, ServerPeer> peers = new LinkedHashMap<>();
    private final Map<String, PlayerSession> sessions = new LinkedHashMap<>();
    private final Map<ConnectionId, AuthChallenge> authChallenges = new LinkedHashMap<>();
    private final Map<ConnectionId, RegistrationChallenge> registrationChallenges = new LinkedHashMap<>();
    private final Map<ConnectionId, SessionChallenge> sessionChallenges = new LinkedHashMap<>();
    private final Set<String> devRequests = new LinkedHashSet<>();
    private final AuthAttemptLimiter authAttemptLimiter = new AuthAttemptLimiter();
    private final AuthDecoySaltStore authDecoySalts;
    private final String authenticationServerFingerprint;
    private ServerAdmissionGate admissionGate = ServerAdmissionGate.open();
    private int nextPlayer = 1;
    private long sequence = 1, lastSnapshot, lastGalaxy, lastResourceCorrection;

    PeerServerSide(Config config, World world, PeerTransport transport) {
        this(config, world, transport, List.of());
    }

    PeerServerSide(Config config, World world, PeerTransport transport, List<PersistentPlayerSession> restoredSessions) {
        this.config = config;
        this.world = world;
        this.transport = transport;
        this.authDecoySalts = new AuthDecoySaltStore(config);
        this.authenticationServerFingerprint = transport == null ? "" : transport.serverFingerprint();
        PlayerRegistry.activate(world);
        SystemAudio.markNonRendered(world);
        restorePersistentSessions(restoredSessions);
    }

    String statusLine() {
        int retained = Math.max(0, sessions.size() - peers.size());
        String result = "HOST " + world.systemName() + " TCP " + transport.localPort()
                + " | clients " + peers.size() + " | retained " + retained + " | queued " + transport.queuedCount()
                + " | " + systemScheduler.statusLine();
        return result + " | " + SkirmishRuntime.settings(world).statusLabel()
                + (config.devMode ? " | dev host" : "");
    }

    void updateWorlds(double dt) {
        PlayerRegistry.activate(world);
        systemScheduler.update(world, dt, world::galaxyMapSnapshot);
        ResourceNetDebug.serverUpdateSystems(world, systemScheduler.lastUpdatedSystems(), dt);
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
        authAttemptLimiter.prune(now);
        removeTimedOut(now);
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
        if (peer == null) return;
        transport.sendOrdered(SkirmishRuntime.settings(world).packet(), peer.connectionId());
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
    void reserveNextPlayer(int candidate) { nextPlayer = Math.max(nextPlayer, Math.max(1, candidate)); }
    boolean sessionConnected(String playerId) {
        PlayerSession session = sessions.get(playerId);
        return session != null && session.connected;
    }

    void setAdmissionGate(ServerAdmissionGate gate) {
        admissionGate = gate == null ? ServerAdmissionGate.open() : gate;
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
        if (!registrationAllowed(address)) {
            join(connectionId, address, port, cleanName, "", "", "", requestedDev, suppliedDevToken);
            return;
        }
        PlayerSession namedSession = sessionByName(cleanName);
        if (namedSession != null) {
            if (!allowAuthAttempt(address, cleanName, connectionId)) return;
            byte[] passwordVerifierBytes = PasswordAuth.decodeVerifier(passwordVerifier);
            if (passwordVerifierBytes.length == 0) {
                denyAuthentication(connectionId, "Password rejected.");
                return;
            }
            reclaimByPassword(connectionId, address, port, namedSession, passwordVerifierBytes, requestedDev, suppliedDevToken);
            return;
        }
        join(connectionId, address, port, cleanName, passwordVerifier, "", "", requestedDev, suppliedDevToken);
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
        CredentialResponse credential = CredentialResponse.parse(passwordVerifier);
        if (PasswordAuth.validNonce(proofNonce) && authChallenges.containsKey(connectionId)
                && PasswordAuth.validVerifier(credential.scopedVerifier)) {
            reclaimByCredential(connectionId, address, port, cleanName, passwordVerifier, proofNonce,
                    requestedDev, suppliedDevToken);
            return;
        }
        if (!allowAuthAttempt(address, cleanName, connectionId)) return;
        PlayerSession namedSession = sessionByName(cleanName);
        if (namedSession != null) {
            issueAuthChallenge(connectionId, cleanName, namedSession);
            return;
        }
        if (!registrationAllowed(address)) {
            issueAuthChallenge(connectionId, cleanName, null);
            return;
        }
        byte[] passwordVerifierBytes = PasswordAuth.decodeVerifier(credential.scopedVerifier);
        RegistrationChallenge registration = registrationChallenges.get(connectionId);
        long registrationNow = System.currentTimeMillis();
        boolean validRegistration = registration != null
                && normalizedName(cleanName).equals(normalizedName(registration.name))
                && registrationNow - registration.createdAt <= AUTH_CHALLENGE_MS
                && registration.scopedSalt.length == 16;
        if (passwordVerifierBytes.length == 0 || !validRegistration) {
            issueRegistrationChallenge(connectionId, cleanName);
            return;
        }
        long now = System.currentTimeMillis();
        String admissionReason = admissionGate.denialReason(connectionId, "", cleanName, address, true, now);
        if (admissionReason != null && !admissionReason.isBlank()) {
            transport.sendOrdered(joinDenied(admissionReason), connectionId);
            return;
        }
        authChallenges.remove(connectionId);
        registrationChallenges.remove(connectionId);
        String id = "P" + nextPlayer++;
        int rgb = colorFor(sessions.size() + 1);
        boolean devAllowed = DevAccessPolicy.authorize(config.devMode, config.dedicatedServerMode(), address,
                requestedDev, config.devToken, suppliedDevToken);
        if (requestedDev) devRequests.add(id);
        auditDevRequest(cleanName, address, port, requestedDev, devAllowed);
        String token = newSessionToken();
        byte[] passwordSalt = PasswordAuth.versionedPasswordSalt(registration.scopedSalt);
        PlayerSession session = new PlayerSession(id, cleanName, rgb, passwordSalt,
                PasswordAuth.serverDigest(passwordVerifierBytes, registration.scopedSalt), token, digestToken(token),
                connectionId, true, 0, devAllowed);
        sessions.put(id, session);
        ServerPeer peer = new ServerPeer(id, connectionId, address, port, now, devAllowed);
        peers.put(connectionId, peer);
        PlayerRegistry.register(id, cleanName, rgb, false);
        world.setDevFreeBuild(id, devAllowed);
        WorldNetAccess.addPeerGroup(world, id);
        systemScheduler.refreshNow();
        views.setHome(world, id);
        sendSessionState(session, peer, token);
    }

    private boolean allowAuthAttempt(InetAddress address, String cleanName, ConnectionId connectionId) {
        if (authAttemptLimiter.allow(address, cleanName, System.currentTimeMillis())) return true;
        denyAuthentication(connectionId, "Authentication temporarily unavailable.");
        return false;
    }

    private boolean registrationAllowed(InetAddress address) {
        return address != null && address.isLoopbackAddress();
    }

    private void issueRegistrationChallenge(ConnectionId connectionId, String cleanName) {
        if (connectionId == null || !connectionId.valid()) return;
        long now = System.currentTimeMillis();
        RegistrationChallenge existing = registrationChallenges.get(connectionId);
        if (existing == null || !normalizedName(cleanName).equals(normalizedName(existing.name))
                || now - existing.createdAt > AUTH_CHALLENGE_MS) {
            existing = new RegistrationChallenge(cleanName, PasswordAuth.digestSalt(
                    PasswordAuth.newVersionedPasswordSalt()), now);
            registrationChallenges.put(connectionId, existing);
        }
        transport.sendOrdered(authRequired(existing.scopedSalt), connectionId);
    }

    private void issueAuthChallenge(ConnectionId connectionId, String cleanName, PlayerSession session) {
        if (connectionId == null || !connectionId.valid()) return;
        byte[] decoySalt = authDecoySalts.saltFor(cleanName);
        boolean retained = session != null
                && session.passwordSalt != null && session.passwordSalt.length > 0
                && session.passwordDigest != null && session.passwordDigest.length > 0;
        int authVersion = retained ? PasswordAuth.passwordVersion(session.passwordSalt) : PasswordAuth.AUTH_VERSION_V2;
        byte[] currentSalt = retained ? PasswordAuth.digestSalt(session.passwordSalt) : decoySalt;
        byte[] scopedSalt = authVersion >= PasswordAuth.AUTH_VERSION_V2
                ? currentSalt.clone() : PasswordAuth.upgradeSalt(currentSalt);
        String playerId = retained ? session.playerId : "";
        if (retained) Arrays.fill(decoySalt, (byte)0);
        String nonce = PasswordAuth.newNonce();
        authChallenges.put(connectionId, new AuthChallenge(cleanName, nonce, playerId, authVersion,
                scopedSalt, System.currentTimeMillis()));
        transport.sendOrdered("AUTH_CHALLENGE|" + packetPart(cleanName) + "|"
                + PasswordAuth.encodeChallengeSalts(currentSalt, scopedSalt) + "|" + nonce, connectionId);
    }

    private void reclaimByCredential(ConnectionId connectionId, InetAddress address, int port, String cleanName,
                                     String credentialMaterial, String challengeNonce,
                                     boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        AuthChallenge challenge = authChallenges.remove(connectionId);
        CredentialResponse credential = CredentialResponse.parse(credentialMaterial);
        boolean contextMatches = challenge != null
                && challenge.nonce.equals(challengeNonce)
                && normalizedName(cleanName).equals(normalizedName(challenge.name))
                && now - challenge.createdAt <= AUTH_CHALLENGE_MS;
        String playerId = challenge == null ? "" : challenge.playerId;
        PlayerSession session = sessions.get(playerId);
        boolean sessionMatches = session != null && !playerId.isBlank()
                && normalizedName(session.name).equals(normalizedName(challenge.name));
        byte[] suppliedVerifier = challenge != null && challenge.authVersion < PasswordAuth.AUTH_VERSION_V2
                ? PasswordAuth.decodeVerifier(credential.legacyVerifier)
                : PasswordAuth.decodeVerifier(credential.scopedVerifier);
        byte[] digestSalt = session == null ? new byte[0] : PasswordAuth.digestSalt(session.passwordSalt);
        boolean credentialMatches = contextMatches && sessionMatches
                && PasswordAuth.passwordCredentialMatches(session.passwordDigest, suppliedVerifier, digestSalt);
        Arrays.fill(suppliedVerifier, (byte)0);
        Arrays.fill(digestSalt, (byte)0);
        if (!credentialMatches) {
            denyAuthentication(connectionId, "Password rejected.");
            return;
        }
        if (challenge.authVersion < PasswordAuth.AUTH_VERSION_V2) {
            byte[] scopedVerifier = PasswordAuth.decodeVerifier(credential.scopedVerifier);
            if (scopedVerifier.length == 0 || challenge.scopedSalt.length != 16) {
                Arrays.fill(scopedVerifier, (byte)0);
                denyAuthentication(connectionId, "Password upgrade required.");
                return;
            }
            session.passwordSalt = PasswordAuth.versionedPasswordSalt(challenge.scopedSalt);
            session.passwordDigest = PasswordAuth.serverDigest(scopedVerifier, challenge.scopedSalt);
            Arrays.fill(scopedVerifier, (byte)0);
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
                denyAuthentication(connectionId, "Password rejected.");
                return;
            }
            session.passwordSalt = PasswordAuth.newSalt();
            session.passwordDigest = PasswordAuth.serverDigest(passwordVerifier, session.passwordSalt);
        } else if (!MessageDigest.isEqual(session.passwordDigest, PasswordAuth.serverDigest(
                passwordVerifier, PasswordAuth.digestSalt(session.passwordSalt)))) {
            denyAuthentication(connectionId, "Password rejected.");
            return;
        }
        bindReclaimedSession(connectionId, address, port, session, requestedDev, suppliedDevToken);
    }

    private void bindReclaimedSession(ConnectionId connectionId, InetAddress address, int port, PlayerSession session,
                                      boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        String admissionReason = admissionGate.denialReason(connectionId, session.playerId, session.name,
                address, false, now);
        if (admissionReason != null && !admissionReason.isBlank()) {
            transport.sendOrdered(joinDenied(admissionReason), connectionId);
            return;
        }
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
        if (session == null || !tokenMatches(session, token, connectionId, now)) {
            transport.sendOrdered(sessionDenied("Session token was rejected."), connectionId);
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

        return bindResumedSession(connectionId, address, port, session, requestedDev, suppliedDevToken, now);
    }

    boolean resume(ConnectionId connectionId, InetAddress address, int port, String playerId, String token,
                   String proofNonce, String proof, boolean requestedDev, String suppliedDevToken) {
        return resume(connectionId, address, port, playerId, token, requestedDev, suppliedDevToken);
    }

    private void issueSessionChallenge(ConnectionId connectionId, PlayerSession session) {
        if (connectionId == null || !connectionId.valid() || session == null
                || session.tokenDigest == null || session.tokenDigest.length == 0) {
            transport.sendOrdered(sessionDenied("Session token was rejected."), connectionId);
            return;
        }
        String nonce = PasswordAuth.newNonce();
        sessionChallenges.put(connectionId, new SessionChallenge(session.playerId, nonce, System.currentTimeMillis()));
        transport.sendOrdered("SESSION_CHALLENGE|" + packetPart(session.playerId) + "|" + nonce, connectionId);
    }

    private boolean resumeByProof(ConnectionId connectionId, InetAddress address, int port, PlayerSession session,
                                  String proofNonce, String proof, boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        SessionChallenge challenge = sessionChallenges.remove(connectionId);
        boolean challengeMatches = challenge != null && session.playerId.equals(challenge.playerId)
                && challenge.nonce.equals(proofNonce) && now - challenge.createdAt <= AUTH_CHALLENGE_MS;
        if (!challengeMatches || !sessionProofMatches(session, proofNonce, proof, now)) {
            transport.sendOrdered(sessionDenied("Session token was rejected."), connectionId);
            return false;
        }
        return bindResumedSession(connectionId, address, port, session, requestedDev, suppliedDevToken, now);
    }

    private boolean sessionProofMatches(PlayerSession session, String proofNonce, String proof, long now) {
        boolean currentMatches = PasswordAuth.sessionProofMatches(
                session.tokenDigest, session.playerId, proofNonce, proof);
        boolean previousMatches = !session.connected && session.previousTokenDigest != null
                && now <= session.previousTokenValidUntil
                && PasswordAuth.sessionProofMatches(
                session.previousTokenDigest, session.playerId, proofNonce, proof);
        // A successful previous-token recovery reaches rotateToken(), which consumes the older token.
        return currentMatches || previousMatches;
    }

    private boolean bindResumedSession(ConnectionId connectionId, InetAddress address, int port, PlayerSession session,
                                       boolean requestedDev, String suppliedDevToken, long now) {
        sessionChallenges.remove(connectionId);
        if (session.connected && connectionId.equals(session.connectionId)) {
            ServerPeer currentPeer = peers.get(connectionId);
            if (currentPeer != null && session.playerId.equals(currentPeer.playerId())) {
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
        world.status = "Reconnected " + session.name + " as " + session.playerId + ".";
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
        registrationChallenges.remove(connectionId);
        sessionChallenges.remove(connectionId);
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
        world.status = session.name + " " + reason + "; player identity and world state retained indefinitely.";
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
        for (ServerPeer peer : peers.values()) {
            String message = GalaxyMapWire.encode(config.galaxyCopies,
                    views.galaxySnapshot(world, peer.playerId()));
            transport.send(message, peer.connectionId(), DeliveryClass.GALAXY);
        }
    }

    private void sendGalaxy(ServerPeer peer) {
        if (peer == null) return;
        String message = GalaxyMapWire.encode(config.galaxyCopies,
                views.galaxySnapshot(world, peer.playerId()));
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
            case "togglePauseAi" -> world.aiDevSettings.pauseAi = !world.aiDevSettings.pauseAi;
            case "stepAi" -> world.aiDevSettings.stepAi = true;
            case "toggleFastAi" -> world.aiDevSettings.fastAi = !world.aiDevSettings.fastAi;
            case "toggleFreezePlayerUnits" -> world.aiDevSettings.freezePlayerUnits = !world.aiDevSettings.freezePlayerUnits;
            case "toggleFreezeNpcCombat" -> world.aiDevSettings.freezeNpcCombat = !world.aiDevSettings.freezeNpcCombat;
            case "toggleDisableAttacks" -> world.aiDevSettings.disableAttacks = !world.aiDevSettings.disableAttacks;
            case "toggleDisableEconomy" -> world.aiDevSettings.disableEconomy = !world.aiDevSettings.disableEconomy;
            case "disableProductionTimers" -> DevTimerSettings.configure(world, true);
            case "enableProductionTimers" -> DevTimerSettings.configure(world, false);
            case "togglePreset" -> world.aiDevSettings.togglePreset();
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
        PlayerSession found = null;
        for (PlayerSession session : sessions.values()) {
            if (wanted.equals(normalizedName(session.name)) && found == null) found = session;
        }
        return found;
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
        registrationChallenges.entrySet().removeIf(entry -> now - entry.getValue().createdAt > AUTH_CHALLENGE_MS);
        sessionChallenges.entrySet().removeIf(entry -> now - entry.getValue().createdAt > AUTH_CHALLENGE_MS);
    }

    private byte[] randomProofKey() {
        byte[] key = new byte[32];
        SESSION_RANDOM.nextBytes(key);
        return key;
    }

    private void denyAuthentication(ConnectionId connectionId, String message) {
        authChallenges.remove(connectionId);
        transport.sendOrdered(joinDenied(message), connectionId);
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
        if (PasswordAuth.sessionTokenMatches(session.tokenDigest, token)) return true;
        boolean sameConnection = session.connected && connectionId != null && connectionId.equals(session.connectionId);
        return session.previousTokenDigest != null && now <= session.previousTokenValidUntil
                && (!session.connected || sameConnection)
                && PasswordAuth.sessionTokenMatches(session.previousTokenDigest, token);
    }

    private byte[] digestToken(String token) {
        return PasswordAuth.tokenDigest(token);
    }

    private String authRequired(byte[] scopedSalt) {
        return "AUTH_REQUIRED|REGISTER|" + PasswordAuth.encodeVerifier(scopedSalt);
    }
    private String joinDenied(String message) { return "JOIN_DENIED|" + packetPart(message); }
    private String sessionBusy(String message) { return "SESSION_BUSY|" + packetPart(message); }
    private String sessionDenied(String message) { return "SESSION_DENIED|" + packetPart(message); }
    private String packetPart(String value) { return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }
    boolean requestedDev(String[] parts) { return parts.length > 2 && flag(parts[2]); }
    String requestedDevToken(String[] parts) { return parts.length > 3 ? parts[3] : ""; }
    boolean requestedResumeDev(String[] parts) { return parts.length > 3 && flag(parts[3]); }
    String requestedResumeDevToken(String[] parts) { return parts.length > 4 ? parts[4] : ""; }
    private String envMessage() { return "ENV|" + world.systemId() + "|" + ClientEnvironmentSeed.forActiveSystem(world) + "|" + Calc.round(world.systemTime()); }
    private String welcome(String id, String name, int rgb, boolean devAllowed, String token) {
        return "WELCOME|" + id + "|" + Config.clean(name) + "|" + rgb + "|" + world.systemId() + "|"
                + ClientEnvironmentSeed.forActiveSystem(world) + "|" + Calc.round(world.systemTime()) + "|DEV|" + (devAllowed ? "1" : "0")
                + "|SESSION|" + token;
    }
    private int colorFor(int i) { int[] colors = {0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C}; return colors[Math.floorMod(i, colors.length)]; }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }

    private record AuthChallenge(String name, String nonce, String playerId, int authVersion,
                                 byte[] scopedSalt, long createdAt) { }
    private record RegistrationChallenge(String name, byte[] scopedSalt, long createdAt) { }
    private record SessionChallenge(String playerId, String nonce, long createdAt) { }

    private record CredentialResponse(String scopedVerifier, String legacyVerifier) {
        static CredentialResponse parse(String value) {
            String raw = value == null ? "" : value.trim();
            int separator = raw.indexOf(':');
            String scoped = separator < 0 ? raw : raw.substring(0, separator);
            String legacy = separator < 0 ? "" : raw.substring(separator + 1);
            return new CredentialResponse(PasswordAuth.validVerifier(scoped) ? scoped.toLowerCase(Locale.ROOT) : "",
                    PasswordAuth.validVerifier(legacy) ? legacy.toLowerCase(Locale.ROOT) : "");
        }
    }

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
