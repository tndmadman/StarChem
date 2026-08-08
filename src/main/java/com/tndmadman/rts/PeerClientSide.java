package com.tndmadman.rts;

final class PeerClientSide {
    private static final long HEARTBEAT_MS = 1000;
    private static final long JOIN_TIMEOUT_MS = 8000;
    private static final long INITIAL_SYNC_TIMEOUT_MS = 30_000;
    private static final long SERVER_SILENCE_MS = 5000;
    private static final long RECONNECT_TIMEOUT_MS = 55_000;
    final Config config;
    final World world;
    final PeerTransport transport;
    private ConnectionState state;
    private long attemptStarted;
    private long lastHandshake;
    private long lastPing;
    private long lastServerPacket;
    private long lastSnapshotSequence;
    private long nextViewRevision = 1;
    private long pendingViewRevision;
    private boolean connectedOnce;
    private boolean devApproved;
    private boolean syncingResume;
    private boolean viewSnapshotMode;
    private boolean viewRequestPending;
    private boolean viewRequestFallbackMode;
    private String viewRequestFallbackSystemId = "";
    private String localPlayerId = "SOLO";
    private String sessionToken = "";
    private String passwordVerifier = "";
    private String scopedPasswordVerifier = "";
    private String authChallengeSalt = "";
    private String authScopedSalt = "";
    private String authServerFingerprint = "";
    private String authChallengeNonce = "";
    private String sessionChallengeNonce = "";
    private boolean authRegistrationRequested;
    private boolean derivedScopedCredential;
    private boolean rememberScopedCredential;
    private String viewedSystemId = "";
    private String failureMessage = "";
    private String lastTransportFailure = "";
    private String pendingReadyName = "";

    PeerClientSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
        PlayerRegistry.activate(world);
        long now = System.currentTimeMillis();
        attemptStarted = now;
        lastServerPacket = now;
        SessionTokenStore.StoredSession stored = SessionTokenStore.load(config);
        passwordVerifier = stored.authDigest();
        if (passwordVerifier.isBlank()) passwordVerifier = SessionTokenStore.authDigest(config);
        SessionTokenStore.ScopedCredential scoped = SessionTokenStore.scopedCredential(config);
        if (scoped.valid()) {
            scopedPasswordVerifier = scoped.verifier();
            authScopedSalt = scoped.scopedSalt();
            authServerFingerprint = scoped.serverFingerprint();
        }
        if (stored.valid()) {
            localPlayerId = stored.playerId();
            sessionToken = stored.token();
            state = ConnectionState.RECONNECTING;
            world.status = "Resuming saved multiplayer session as " + localPlayerId + ".";
        } else {
            state = ConnectionState.JOINING;
        }
    }

    String statusLine() {
        String label = switch (state) {
            case JOINING -> "joining";
            case SYNCING -> "syncing " + localPlayerId;
            case CONNECTED -> localPlayerId;
            case RECONNECTING -> "reconnecting " + localPlayerId;
            case DISCONNECTED -> "disconnected";
            case FAILED -> "failed";
        };
        return "CLIENT " + label + " -> " + config.serverAddress + " | " + world.activeSystemId()
                + " | " + SkirmishRuntime.settings(world).statusLabel()
                + " | queued " + transport.queuedCount() + (devApproved ? " | dev" : "");
    }

    String localPlayerId() { return localPlayerId; }
    boolean connectionFailed() { return state == ConnectionState.FAILED; }
    boolean devToolsAllowed() { return state == ConnectionState.CONNECTED && devApproved; }
    String failureMessage() { return failureMessage.isBlank() ? "Connection failed." : failureMessage; }
    static long serverSilenceMs() { return SERVER_SILENCE_MS; }
    boolean reconnecting() { return state == ConnectionState.RECONNECTING || state == ConnectionState.SYNCING && syncingResume; }
    boolean connectedState() { return state == ConnectionState.CONNECTED; }
    boolean readyState() { return state == ConnectionState.CONNECTED; }
    long lastSnapshotSequence() { return lastSnapshotSequence; }
    String viewedSystemId() { return viewedSystemId; }
    long pendingViewRevision() { return pendingViewRevision; }
    boolean viewSwitchPending() { return viewRequestPending; }
    boolean serverCertificateTrustRequired() { return transport.serverCertificateTrustRequired(); }
    String serverCertificateTrustPrompt() {
        TlsIdentity.FingerprintChange change = transport.pendingServerFingerprintChange();
        if (change == null || !change.valid()) return "No pending server certificate change.";
        return "The server at " + config.serverAddress + " presented a different TLS certificate.\n\n"
                + "Previously trusted fingerprint:\n" + change.expected() + "\n\n"
                + "Newly presented fingerprint:\n" + change.presented() + "\n\n"
                + "Only trust it if you expected the server identity to change or verified it with the server owner.";
    }
    boolean trustChangedServerCertificate() {
        if (!transport.trustPendingServerCertificate()) return false;
        long now = System.currentTimeMillis();
        failureMessage = "";
        lastTransportFailure = "";
        attemptStarted = now;
        lastHandshake = 0;
        lastPing = 0;
        lastServerPacket = now;
        syncingResume = !sessionToken.isBlank();
        state = sessionToken.isBlank() ? ConnectionState.JOINING : ConnectionState.RECONNECTING;
        world.status = "Trusted the new server certificate. Reconnecting to " + config.serverAddress + ".";
        return true;
    }

    ClientConnectionProgress connectionProgress() {
        long elapsed = Math.max(0, System.currentTimeMillis() - attemptStarted);
        return switch (state) {
            case JOINING -> transport.connected()
                    ? new ClientConnectionProgress(ConnectionPhase.HANDSHAKING, "NEGOTIATING CONNECTION",
                    "TCP connected. Waiting for server approval and compatibility checks.", 2, 4, elapsed)
                    : new ClientConnectionProgress(ConnectionPhase.CONNECTING, "CONNECTING TO SERVER",
                    transportDetail("Opening TCP connection to " + config.serverAddress + "."), 1, 4, elapsed);
            case SYNCING -> new ClientConnectionProgress(ConnectionPhase.SYNCHRONIZING,
                    syncingResume ? "RESTORING SESSION" : "SYNCHRONIZING FLEET",
                    syncingResume ? "Receiving authoritative state for the saved session."
                            : "Receiving and validating authoritative galaxy and fleet state.",
                    3, 4, elapsed);
            case CONNECTED -> new ClientConnectionProgress(ConnectionPhase.READY, "READY",
                    "Authoritative state loaded.", 4, 4, elapsed);
            case RECONNECTING -> transport.connected()
                    ? new ClientConnectionProgress(ConnectionPhase.HANDSHAKING, "RECONNECTING",
                    "TCP restored. Requesting the saved session from the server.", 2, 4, elapsed)
                    : new ClientConnectionProgress(ConnectionPhase.RECONNECTING, "CONNECTION INTERRUPTED",
                    transportDetail("Opening a new TCP connection without dropping player state."), 1, 4, elapsed);
            case FAILED -> new ClientConnectionProgress(ConnectionPhase.FAILED, "CONNECTION FAILED",
                    failureMessage(), 0, 4, elapsed);
            case DISCONNECTED -> new ClientConnectionProgress(ConnectionPhase.DISCONNECTED, "DISCONNECTED",
                    "The multiplayer session is closed.", 0, 4, elapsed);
        };
    }

    void tick(long now) {
        PlayerRegistry.activate(world);
        String transportFailure = transport.consumeClientConnectFailure();
        if (!transportFailure.isBlank()) {
            lastTransportFailure = transportFailure;
            String lower = transportFailure.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("tls fingerprint changed") || lower.contains("refusing to send login secrets")) {
                failConnection("Server identity changed. StarChem blocked login secrets. "
                        + "Verify the fingerprints, then choose TRUST NEW CERTIFICATE to reconnect.");
                return;
            }
        }
        boolean connectionDropped = transport.consumeClientDisconnect();
        if ((state == ConnectionState.CONNECTED || state == ConnectionState.SYNCING) && connectionDropped) {
            beginReconnect(now);
        }
        switch (state) {
            case FAILED, DISCONNECTED -> { return; }
            case CONNECTED -> {
                if (now - lastServerPacket >= SERVER_SILENCE_MS) {
                    beginReconnect(now);
                    return;
                }
                if (now - lastPing >= HEARTBEAT_MS) {
                    sendControlToServer("PING|" + localPlayerId);
                    lastPing = now;
                }
            }
            case SYNCING -> {
                if (now - attemptStarted >= INITIAL_SYNC_TIMEOUT_MS) {
                    beginReconnect(now);
                    return;
                }
                if (now - lastPing >= HEARTBEAT_MS) {
                    sendControlToServer("PING|" + localPlayerId);
                    lastPing = now;
                }
            }
            case JOINING -> {
                if (now - attemptStarted >= JOIN_TIMEOUT_MS) {
                    failConnection(transportDetail("Connection failed: no response from server at " + config.serverAddress + "."));
                    return;
                }
                if (now - lastHandshake >= HEARTBEAT_MS) {
                    sendControlToServer(joinMessage());
                    lastHandshake = now;
                }
            }
            case RECONNECTING -> {
                if (sessionToken.isBlank()) {
                    failConnection("Connection lost and no resumable session is available.");
                    return;
                }
                if (now - attemptStarted >= RECONNECT_TIMEOUT_MS) {
                    failConnection(transportDetail("Connection failed: the saved session could not be resumed."));
                    return;
                }
                if (now - lastHandshake >= HEARTBEAT_MS) {
                    sendControlToServer(resumeMessage());
                    lastHandshake = now;
                }
            }
        }
    }

    void shutdown() {
        PlayerRegistry.activate(world);
        if (state == ConnectionState.CONNECTED || state == ConnectionState.SYNCING) {
            sendControlToServer("LEAVE|" + localPlayerId);
        }
        state = ConnectionState.DISCONNECTED;
        devApproved = false;
        syncingResume = false;
        world.setDevFreeBuild(localPlayerId, false);
    }

    void handle(NetPacket packet, String message) {
        if (!fromConfiguredServer(packet)) return;
        PlayerRegistry.activate(world);
        lastServerPacket = System.currentTimeMillis();
        if (UnitQueueWire.readState(world, message, localPlayerId)) return;
        if (readWorldInfo(message) || readGalaxy(message) || readLeaderboard(message)
                || readDevStatus(message) || readViewDenied(message)) return;
        if (!readAuthRequired(message) && !readAuthChallenge(message) && !readSessionChallenge(message)
                && !readJoinDenied(message) && !readSessionBusy(message) && !readSessionDenied(message)
                && !readSystemDelete(message)) ClientPackets.handle(this, message);
    }

    void handle(String message) {
        handle(new NetPacket(message, config.serverAddress.getAddress(), config.serverAddress.getPort()), message);
    }

    void rejectPacket(RuntimeException ex) {
        if (ex instanceof SnapshotDecodeException snapshotError) {
            rejectSnapshot(snapshotError);
            return;
        }
        world.status = "Rejected malformed server packet.";
        System.err.println(world.status + " " + ex.getClass().getSimpleName());
    }

    void queue(UnitQueueMutation mutation) { sendCommandToServer(UnitQueueWire.mutationPacket(mutation)); }
    void move(MoveCommand command) { sendCommandToServer("MOVE|" + command.playerId() + "|" + command.unitId() + "|" + Calc.round(command.x()) + "|" + Calc.round(command.y())); }
    void work(HarvestCommand command) { ResourceNetDebug.clientWorkSend(world, command); sendCommandToServer("WORK|" + command.playerId() + "|" + command.unitId() + "|" + command.resourceId()); }
    void attack(AttackCommand command) { sendCommandToServer("ATTACK|" + command.playerId() + "|" + command.unitId() + "|" + command.targetKey()); }
    void order(UnitOrderCommand command) {
        sendCommandToServer("ORDER|" + command.playerId() + "|" + command.unitId() + "|" + command.type().name() + "|"
                + Calc.round(command.x1()) + "|" + Calc.round(command.y1()) + "|" + Calc.round(command.x2()) + "|" + Calc.round(command.y2()) + "|"
                + Calc.round(command.radius()) + "|" + cleanPacketPart(command.targetKey()) + "|" + command.phase());
    }
    void respawn(String playerId) { sendCommandToServer("RESPAWN|" + playerId); }
    void build(String playerId, String baseId, String shipTypeId) { sendCommandToServer("BUILD|" + playerId + "|" + baseId + "|" + shipTypeId); }
    void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { sendCommandToServer("PACK|" + playerId + "|" + mode + "|" + baseOrUnitId + "|" + packageType); }
    void production(String playerId, String action, String baseId, String value, String extra) {
        sendCommandToServer("PROD|" + cleanPacketPart(playerId) + "|" + cleanPacketPart(action) + "|"
                + cleanPacketPart(baseId) + "|" + cleanPacketPart(value) + "|" + cleanPacketPart(extra));
    }
    void devSetFreeCrafting(String playerId, boolean enabled) { sendCommandToServer("DEVFREE|" + cleanPacketPart(playerId) + "|" + (enabled ? "1" : "0")); }
    void devAddHangarResource(String playerId, String baseId, Material material, double amount) {
        if (material == null || amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        sendCommandToServer("DEVHANGAR|" + cleanPacketPart(playerId) + "|" + cleanPacketPart(baseId) + "|" + material.name() + "|" + Calc.round(amount));
    }
    void devAiCommand(String playerId, String command) { sendCommandToServer("DEVAI|" + cleanPacketPart(playerId) + "|" + cleanPacketPart(command)); }
    void jump(String playerId, double x, double y) { viewSystem(playerId, world.wormholeTargetAt(x, y)); }
    void jump(String playerId, String targetSystemId, double x, double y) { viewSystem(playerId, targetSystemId); }

    void viewSystem(String playerId, String targetSystemId) {
        if (!canIssueCommands()) { blockCommand(); return; }
        String requestedSystem = cleanSystemId(targetSystemId);
        if (invalidSystemId(requestedSystem)) {
            world.status = "Unable to request that galaxy system.";
            return;
        }
        if (!viewRequestPending) {
            viewRequestFallbackSystemId = world.activeSystemId();
            viewRequestFallbackMode = viewSnapshotMode;
        }
        viewSnapshotMode = true;
        viewRequestPending = true;
        viewedSystemId = requestedSystem;
        pendingViewRevision = nextViewRevision++;
        world.status = "Requesting view of " + requestedSystem + " from the server.";
        sendCommandToServer("VIEW_SYSTEM|" + playerId + "|" + requestedSystem + "|" + pendingViewRevision);
    }
    void wormholeTouch(String playerId) { sendCommandToServer("WHTOUCH|" + playerId); }
    void wormholeTouch(WormholeTouchRequest request) { if (request != null && request.valid()) sendCommandToServer(request.packet()); }

    void readEnv(String[] parts) { if (parts.length >= 4) syncEnv(parts[1], parts[2], parts[3]); else if (parts.length >= 3) syncEnv(world.systemId(), parts[1], parts[2]); }
    void readSeed(String seed) { try { world.useSystemSeed(Long.parseLong(seed)); } catch (NumberFormatException ignored) { } }
    void readWelcome(String[] parts) {
        if (parts.length < 4) return;
        int rgb;
        try { rgb = Integer.parseInt(parts[3]); }
        catch (NumberFormatException ex) { throw new SnapshotDecodeException("Malformed WELCOME packet: player color is not numeric."); }
        if (parts[1].isBlank() || parts[1].length() > 64 || parts[2].isBlank() || parts[2].length() > 128) {
            throw new SnapshotDecodeException("Malformed WELCOME packet: invalid player identity.");
        }
        String newSessionToken = markerValue(parts, "SESSION");
        if (!validSessionToken(newSessionToken)) {
            throw new SnapshotDecodeException("Malformed WELCOME packet: missing or invalid session token.");
        }
        ConnectionState previousState = state;
        if (derivedScopedCredential && PasswordAuth.validVerifier(authServerFingerprint)
                && PasswordAuth.decodeHex(authScopedSalt).length == 16
                && PasswordAuth.validVerifier(scopedPasswordVerifier)) {
            if (rememberScopedCredential) {
                SessionTokenStore.saveScopedCredential(config, authServerFingerprint, authScopedSalt, scopedPasswordVerifier);
            } else {
                SessionTokenStore.clearLegacyAuthDigest(config);
            }
        }
        authChallengeSalt = "";
        authChallengeNonce = "";
        sessionChallengeNonce = "";
        authRegistrationRequested = false;
        localPlayerId = parts[1];
        sessionToken = newSessionToken;
        state = ConnectionState.SYNCING;
        syncingResume = previousState == ConnectionState.RECONNECTING || connectedOnce;
        pendingReadyName = parts[2];
        failureMessage = "";
        lastTransportFailure = "";
        transport.clearOutbound();
        long now = System.currentTimeMillis();
        attemptStarted = now;
        lastServerPacket = now;
        lastPing = now;
        lastHandshake = 0;
        if (parts.length >= 7) syncEnv(parts[4], parts[5], parts[6]); else if (parts.length >= 6) syncEnv(world.systemId(), parts[4], parts[5]); else if (parts.length >= 5) readSeed(parts[4]);
        PlayerRegistry.register(localPlayerId, parts[2], rgb, true);
        world.ensurePlayerHome(localPlayerId, WorldNetAccess.usesPrimaryHome(localPlayerId));
        world.activateSystem(world.playerHomeSystemId(localPlayerId));
        viewedSystemId = world.activeSystemId();
        viewSnapshotMode = false;
        viewRequestPending = false;
        viewRequestFallbackMode = false;
        viewRequestFallbackSystemId = "";
        pendingViewRevision = 0;
        devApproved = flag(markerValue(parts, "DEV"));
        world.setDevFreeBuild(localPlayerId, devApproved);
        SessionTokenStore.save(config, localPlayerId, sessionToken);
        world.status = syncingResume
                ? "Session resumed. Receiving authoritative state for " + pendingReadyName + "."
                : "Server accepted " + pendingReadyName + ". Receiving authoritative state.";
    }

    void readSnapshot(String message) {
        try {
            Snapshot snapshot = SnapshotReader.read(message);
            ResourceNetDebug.clientReceive("REGULAR", snapshot, lastSnapshotSequence, viewSnapshotMode);
            if (holdingDifferentView(snapshot)) return;
            if (stale(snapshot, "REGULAR")) return;
            boolean applyAsView = shouldApplyAsView(snapshot);
            if (applyAsView) WorldNetAccess.applyView(world, snapshot);
            else WorldNetAccess.apply(world, snapshot);
            acceptSnapshot(snapshot);
            updateViewModeFromRegularSnapshot(snapshot);
        } catch (SnapshotDecodeException ex) {
            rejectSnapshot(ex);
        }
    }

    void readFullView(String message) {
        try {
            Snapshot snapshot = SyncFrame.read(message);
            if (SyncFrame.isResourceCorrection(message)) {
                ResourceNetDebug.clientReceive("FULL_CORRECTION", snapshot, lastSnapshotSequence, viewSnapshotMode);
                if (holdingDifferentView(snapshot)) return;
                if (stale(snapshot, "FULL_CORRECTION")) return;
                boolean applyAsView = shouldApplyAsView(snapshot);
                WorldNetAccess.applyResourceCorrection(world, snapshot, applyAsView);
                acceptSnapshot(snapshot);
                updateViewModeFromRegularSnapshot(snapshot);
                return;
            }

            long frameViewRevision = SyncFrame.viewRevision(message);
            boolean requestedView = viewRequestPending;
            if (requestedView && frameViewRevision != pendingViewRevision) {
                ResourceNetDebug.ignoredSnapshot(world, snapshot, "obsolete view revision " + frameViewRevision
                        + " while waiting for " + pendingViewRevision);
                return;
            }
            ResourceNetDebug.clientReceive("FULL_VIEW", snapshot, lastSnapshotSequence, viewSnapshotMode);
            if (holdingDifferentView(snapshot)) return;
            if (stale(snapshot, "FULL_VIEW")) return;
            WorldNetAccess.applyFullView(world, snapshot);
            acceptSnapshot(snapshot);
            acceptAuthoritativeView(snapshot);
            if (requestedView) {
                viewRequestPending = false;
                viewRequestFallbackSystemId = "";
                viewRequestFallbackMode = false;
            }
            completeInitialSync();
        } catch (SnapshotDecodeException ex) {
            rejectSnapshot(ex);
        }
    }

    private void completeInitialSync() {
        if (state != ConnectionState.SYNCING) return;
        boolean resumed = syncingResume;
        String playerName = pendingReadyName.isBlank() ? PlayerRegistry.name(localPlayerId) : pendingReadyName;
        state = ConnectionState.CONNECTED;
        connectedOnce = true;
        syncingResume = false;
        pendingReadyName = "";
        long now = System.currentTimeMillis();
        attemptStarted = now;
        lastServerPacket = now;
        lastPing = now;
        world.status = (resumed ? "Reconnected " : "Joined ") + world.activeSystemId() + " as " + playerName + devStatus(devApproved);
    }

    private boolean readWorldInfo(String message) {
        if (message == null || !message.startsWith("WORLDINFO|")) return false;
        try {
            SkirmishRuntime.bind(world, SkirmishSettings.fromPacket(message));
        } catch (IllegalArgumentException ex) {
            failConnection("Server sent invalid skirmish settings.");
        }
        return true;
    }

    private boolean readGalaxy(String message) {
        if (message == null || !message.startsWith("GALAXY|")) return false;
        GalaxyMapWire.Decoded decoded = GalaxyMapWire.decode(message);
        world.configureGalaxyCopies(decoded.copiesPerTemplate());
        if (decoded.ownerProjection().present()) {
            OwnerFleetLocationRegistry.replace(world, decoded.ownerProjection().ownerId(), decoded.ownerUnitLocations());
        }
        world.applyRemoteGalaxyMapSnapshot(decoded.snapshot());
        return true;
    }

    private boolean readLeaderboard(String message) {
        if (message == null || !message.startsWith("LEADER|")) return false;
        GlobalLeaderboard.set(world, GlobalLeaderboard.decode(message));
        return true;
    }

    private boolean readDevStatus(String message) {
        if (message == null || !message.startsWith("DEVSTATUS|")) return false;
        String[] parts = message.split("\\|", -1);
        devApproved = parts.length > 1 && flag(parts[1]);
        world.setDevFreeBuild(localPlayerId, devApproved);
        world.status = "Dev access " + (devApproved ? "granted by host." : "revoked by host.");
        return true;
    }

    private boolean readViewDenied(String message) {
        if (message == null || !message.startsWith("VIEW_DENIED|")) return false;
        String[] parts = message.split("\\|", 4);
        long deniedRevision = 0;
        if (parts.length > 1) {
            try { deniedRevision = Math.max(0, Long.parseLong(parts[1])); }
            catch (NumberFormatException ignored) { }
        }
        boolean currentRequest = !viewRequestPending || deniedRevision == 0 || deniedRevision == pendingViewRevision;
        if (currentRequest) {
            String retainedView = parts.length > 2 ? cleanSystemId(parts[2]) : "";
            if (!invalidSystemId(retainedView)) {
                viewedSystemId = retainedView;
                viewSnapshotMode = true;
                viewRequestPending = true;
                pendingViewRevision = deniedRevision;
            } else {
                viewRequestPending = false;
                viewSnapshotMode = viewRequestFallbackMode;
                pendingViewRevision = 0;
                viewedSystemId = viewRequestFallbackSystemId == null || viewRequestFallbackSystemId.isBlank()
                        ? world.activeSystemId() : viewRequestFallbackSystemId;
                viewRequestFallbackSystemId = "";
                viewRequestFallbackMode = false;
            }
        }
        String reason = parts.length > 3 ? parts[3].trim() : "The server rejected that system view.";
        world.status = reason.isBlank() ? "The server rejected that system view." : reason;
        return true;
    }

    private boolean readJoinDenied(String message) {
        if (message == null || !message.startsWith("JOIN_DENIED|")) return false;
        String reason = message.length() > 12 ? message.substring(12).trim() : "Join refused by server.";
        if (reason.toLowerCase(java.util.Locale.ROOT).contains("password")) {
            SessionTokenStore.clear(config);
            sessionToken = "";
            passwordVerifier = "";
            scopedPasswordVerifier = "";
            authChallengeSalt = "";
            authScopedSalt = "";
            authServerFingerprint = "";
            authChallengeNonce = "";
            sessionChallengeNonce = "";
            authRegistrationRequested = false;
            derivedScopedCredential = false;
            rememberScopedCredential = false;
        }
        failConnection(reason.isBlank() ? "Join refused by server." : reason);
        return true;
    }

    private boolean readAuthRequired(String message) {
        if (message == null || !message.startsWith("AUTH_REQUIRED|")) return false;
        String[] parts = message.split("\\|", -1);
        if (parts.length < 3 || PasswordAuth.decodeHex(parts[2]).length != 16
                || !preparePasswordMaterial(parts[2])) {
            if (state != ConnectionState.FAILED) failConnection("Server sent an invalid registration challenge.");
            return true;
        }
        authRegistrationRequested = true;
        authChallengeSalt = "";
        authChallengeNonce = "";
        state = ConnectionState.JOINING;
        lastHandshake = 0;
        world.status = "Registering a server-scoped player credential for " + config.playerName + ".";
        return true;
    }

    private boolean readAuthChallenge(String message) {
        if (message == null || !message.startsWith("AUTH_CHALLENGE|")) return false;
        String[] parts = message.split("\\|", -1);
        PasswordAuth.ChallengeSalts salts = parts.length < 4
                ? PasswordAuth.ChallengeSalts.EMPTY : PasswordAuth.decodeChallengeSalts(parts[2]);
        if (!salts.valid() || !PasswordAuth.validNonce(parts[3])) {
            failConnection("Server sent an invalid password challenge.");
            return true;
        }
        String scopedSalt = PasswordAuth.encodeVerifier(salts.scopedSalt());
        if (!preparePasswordMaterial(scopedSalt)) return true;
        authRegistrationRequested = false;
        authChallengeSalt = PasswordAuth.encodeVerifier(salts.currentSalt());
        authScopedSalt = scopedSalt;
        authChallengeNonce = parts[3];
        state = ConnectionState.JOINING;
        lastHandshake = 0;
        world.status = "Answering server-scoped password challenge for " + config.playerName + ".";
        return true;
    }

    private boolean readSessionBusy(String message) {
        if (message == null || !message.startsWith("SESSION_BUSY|")) return false;
        String reason = message.length() > 13 ? message.substring(13).trim() : "Saved session is already active.";
        world.status = (reason.isBlank() ? "Saved session is already active." : reason) + " Waiting to resume.";
        return true;
    }

    private boolean readSessionChallenge(String message) {
        if (message == null || !message.startsWith("SESSION_CHALLENGE|")) return false;
        String[] parts = message.split("\\|", -1);
        if (parts.length < 3 || !parts[1].equals(localPlayerId) || !PasswordAuth.validNonce(parts[2])) {
            failConnection("Server sent an invalid resume challenge.");
            return true;
        }
        sessionChallengeNonce = parts[2];
        state = ConnectionState.RECONNECTING;
        lastHandshake = 0;
        world.status = "Answering saved session challenge for " + localPlayerId + ".";
        return true;
    }

    private boolean readSessionDenied(String message) {
        if (message == null || !message.startsWith("SESSION_DENIED|")) return false;
        String reason = message.length() > 15 ? message.substring(15).trim() : "Saved session was rejected.";
        SessionTokenStore.clear(config);
        sessionToken = "";
        sessionChallengeNonce = "";
        transport.clearOutbound();
        if (!connectedOnce && state == ConnectionState.RECONNECTING) {
            localPlayerId = "SOLO";
            state = ConnectionState.JOINING;
            attemptStarted = System.currentTimeMillis();
            lastHandshake = 0;
            world.status = (reason.isBlank() ? "Saved session was rejected." : reason) + " Joining as a new player.";
            return true;
        }
        failConnection(reason.isBlank() ? "Saved session was rejected." : reason);
        return true;
    }

    private boolean readSystemDelete(String message) {
        if (message == null || !message.startsWith("SYSDEL|")) return false;
        boolean deletedViewedSystem = false;
        boolean deletedActiveSystem = false;
        String body = message.length() > 7 ? message.substring(7) : "";
        for (String raw : body.split(";")) {
            String systemId = cleanSystemId(raw);
            if (systemId.isBlank()) continue;
            deletedViewedSystem |= systemId.equals(viewedSystemId);
            deletedActiveSystem |= systemId.equals(world.activeSystemId());
            String owner = ownerFromHomeSystem(systemId);
            if (owner.isBlank() || owner.equals(localPlayerId)) continue;
            PlayerRegistry.remove(owner);
            world.removePlayerAndPruneEmptySystems(owner);
        }
        if (deletedViewedSystem || deletedActiveSystem) {
            viewSnapshotMode = false;
            viewRequestPending = false;
            viewRequestFallbackMode = false;
            viewRequestFallbackSystemId = "";
            pendingViewRevision = 0;
            world.ensurePlayerHome(localPlayerId);
            world.activateSystem(world.playerHomeSystemId(localPlayerId));
            viewedSystemId = world.activeSystemId();
        }
        world.status = "Removed abandoned system from galaxy map.";
        return true;
    }

    private String ownerFromHomeSystem(String systemId) {
        String prefix = StarSystems.PLAYER_HOME_SYSTEM_ID + "_";
        return systemId != null && systemId.startsWith(prefix) ? systemId.substring(prefix.length()) : "";
    }

    private boolean shouldApplyAsView(Snapshot snapshot) {
        return viewSnapshotMode || currentViewSnapshot(snapshot)
                && !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
    }

    private void updateViewModeFromRegularSnapshot(Snapshot snapshot) {
        if (!currentViewSnapshot(snapshot)) return;
        viewedSystemId = snapshot.systemId();
        viewSnapshotMode = !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
    }

    private void acceptAuthoritativeView(Snapshot snapshot) {
        if (snapshot == null || snapshot.systemId() == null || snapshot.systemId().isBlank()) return;
        viewedSystemId = snapshot.systemId();
        viewSnapshotMode = !WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId);
    }

    private boolean currentViewSnapshot(Snapshot snapshot) {
        if (snapshot == null || snapshot.systemId() == null || snapshot.systemId().isBlank()) return false;
        return snapshot.systemId().equals(viewedSystemId)
                || snapshot.systemId().equals(world.activeSystemId());
    }

    private boolean holdingDifferentView(Snapshot snapshot) {
        if (!viewSnapshotMode || viewedSystemId == null || viewedSystemId.isBlank()) return false;
        if (invalidSystemId(viewedSystemId)) { viewSnapshotMode = false; viewedSystemId = world.activeSystemId(); return false; }
        String systemId = snapshot.systemId();
        if (systemId == null || systemId.isBlank() || systemId.equals(viewedSystemId)) return false;
        ResourceNetDebug.ignoredSnapshot(world, snapshot, "holding requested view " + viewedSystemId);
        return true;
    }

    private boolean stale(Snapshot snapshot, String kind) {
        long snapshotSequence = snapshot.sequence();
        if (snapshotSequence <= 0) return false;
        if (snapshotSequence <= lastSnapshotSequence) {
            ResourceNetDebug.staleSnapshot(kind, snapshot, lastSnapshotSequence);
            return true;
        }
        return false;
    }

    private void acceptSnapshot(Snapshot snapshot) {
        if (snapshot.sequence() > lastSnapshotSequence) lastSnapshotSequence = snapshot.sequence();
    }

    private void rejectSnapshot(SnapshotDecodeException ex) {
        transport.recordSnapshotRejected();
        String message = ex.getMessage();
        world.status = message == null || message.isBlank()
                ? "Snapshot rejected: incompatible or corrupted state."
                : message;
        System.err.println(world.status);
    }

    private void beginReconnect(long now) {
        if (state != ConnectionState.CONNECTED && state != ConnectionState.SYNCING) return;
        if (sessionToken.isBlank()) {
            failConnection("Connection lost and no resumable session is available.");
            return;
        }
        state = ConnectionState.RECONNECTING;
        syncingResume = true;
        pendingReadyName = "";
        sessionChallengeNonce = "";
        attemptStarted = now;
        lastHandshake = 0;
        devApproved = false;
        world.setDevFreeBuild(localPlayerId, false);
        transport.clearOutbound();
        transport.reconnectClient();
        world.status = "Connection interrupted. Reconnecting to " + config.serverAddress + " without dropping player state.";
    }

    private String transportDetail(String message) {
        if (lastTransportFailure.isBlank()) return message;
        return message + " Last transport error: " + lastTransportFailure;
    }

    private void failConnection(String message) {
        state = ConnectionState.FAILED;
        devApproved = false;
        syncingResume = false;
        pendingReadyName = "";
        world.setDevFreeBuild(localPlayerId, false);
        failureMessage = message;
        transport.clearOutbound();
        world.status = failureMessage;
    }

    private boolean fromConfiguredServer(NetPacket packet) {
        return packet != null && config.serverAddress != null && config.serverAddress.getAddress() != null
                && config.serverAddress.getPort() == packet.port()
                && config.serverAddress.getAddress().equals(packet.address());
    }

    private String joinMessage() {
        String request = config.devMode ? "DEV" : "NODEV";
        String token = config.devMode ? config.devToken : "";
        String message = "JOIN|" + cleanPacketPart(config.playerName) + "|" + request + "|" + token;
        if (!authChallengeNonce.isBlank() && !authChallengeSalt.isBlank()) {
            String credential = scopedPasswordVerifier;
            if (!passwordVerifier.isBlank() && !credential.isBlank()) credential += ":" + passwordVerifier;
            return message + (credential.isBlank() ? "" : "|AUTH_REGISTER|" + cleanPacketPart(credential))
                    + "|AUTH_PROOF_NONCE|" + cleanPacketPart(authChallengeNonce);
        }
        if (authRegistrationRequested && !scopedPasswordVerifier.isBlank()) {
            return message + "|AUTH_REGISTER|" + cleanPacketPart(scopedPasswordVerifier);
        }
        return message;
    }

    private boolean preparePasswordMaterial(String scopedSalt) {
        if (config.localHostClientMode()) {
            if (!PasswordAuth.validVerifier(passwordVerifier)
                    || PasswordAuth.decodeHex(scopedSalt).length != 16) {
                failConnection("The graphical HOST credential is unavailable.");
                return false;
            }
            scopedPasswordVerifier = passwordVerifier;
            authScopedSalt = scopedSalt.toLowerCase(java.util.Locale.ROOT);
            derivedScopedCredential = false;
            rememberScopedCredential = false;
            return true;
        }
        String fingerprint = SessionTokenStore.serverFingerprint(config);
        if (!PasswordAuth.validVerifier(fingerprint) || PasswordAuth.decodeHex(scopedSalt).length != 16) {
            failConnection("The verified TLS server identity is unavailable; refusing to derive a login credential.");
            return false;
        }
        SessionTokenStore.ScopedCredential stored = SessionTokenStore.scopedCredential(config);
        if (stored.matches(fingerprint, scopedSalt)) {
            scopedPasswordVerifier = stored.verifier();
            authServerFingerprint = fingerprint.toLowerCase(java.util.Locale.ROOT);
            authScopedSalt = scopedSalt.toLowerCase(java.util.Locale.ROOT);
            derivedScopedCredential = false;
            rememberScopedCredential = false;
            return true;
        }
        if (stored.valid()) SessionTokenStore.clearScopedCredential(config);
        PendingPlayerPassword.Entry pending = PendingPlayerPassword.take(config);
        if (pending == null) {
            failConnection("Re-enter the player password for this verified server identity.");
            return false;
        }
        char[] password = pending.password();
        try {
            passwordVerifier = PasswordAuth.verifier(config.playerName, password);
            scopedPasswordVerifier = PasswordAuth.scopedVerifier(config.playerName, password, fingerprint,
                    PasswordAuth.decodeHex(scopedSalt));
            authServerFingerprint = fingerprint.toLowerCase(java.util.Locale.ROOT);
            authScopedSalt = scopedSalt.toLowerCase(java.util.Locale.ROOT);
            derivedScopedCredential = PasswordAuth.validVerifier(scopedPasswordVerifier);
            rememberScopedCredential = pending.rememberCredential();
            if (!derivedScopedCredential) {
                failConnection("Could not derive the server-scoped player credential.");
                return false;
            }
            return true;
        } finally {
            java.util.Arrays.fill(password, '\0');
            pending.close();
        }
    }

    private String resumeMessage() {
        String request = config.devMode ? "DEV" : "NODEV";
        String devToken = config.devMode ? config.devToken : "";
        return "RESUME|" + cleanPacketPart(localPlayerId) + "|" + cleanPacketPart(sessionToken)
                + "|" + request + "|" + devToken;
    }

    private boolean canIssueCommands() { return state == ConnectionState.CONNECTED; }

    private void blockCommand() {
        world.status = switch (state) {
            case RECONNECTING -> "Command blocked while reconnecting.";
            case JOINING -> "Command blocked until the server accepts the connection.";
            case SYNCING -> "Command blocked until authoritative state finishes loading.";
            case DISCONNECTED -> "Command blocked because the client is disconnected.";
            case FAILED -> "Command blocked because the connection failed.";
            case CONNECTED -> world.status;
        };
    }

    private void sendCommandToServer(String payload) {
        if (!canIssueCommands()) { blockCommand(); return; }
        if (canSendControl()) transport.sendOrdered(payload, config.serverAddress.getAddress(), config.serverAddress.getPort());
    }

    private void sendControlToServer(String message) {
        if (canSendControl()) transport.send(message, config.serverAddress.getAddress(), config.serverAddress.getPort());
    }

    private boolean canSendControl() {
        return config.serverAddress != null && config.serverAddress.getAddress() != null;
    }

    private String markerValue(String[] parts, String marker) {
        if (parts == null || marker == null) return "";
        for (int i = 0; i + 1 < parts.length; i++) if (marker.equals(parts[i])) return parts[i + 1];
        return "";
    }

    private boolean validSessionToken(String value) {
        if (value == null || value.length() < 32 || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') return false;
        }
        return true;
    }

    private String cleanPacketPart(String value) { return value == null ? "" : value.replace("|", "").trim(); }
    private String cleanSystemId(String value) { return value == null ? "" : value.replace("|", "").trim(); }
    private boolean invalidSystemId(String value) { return value == null || value.isBlank() || value.contains("WAIT"); }
    private void syncEnv(String systemId, String seed, String time) { try { world.syncEnvironment(systemId, Long.parseLong(seed), Double.parseDouble(time)); } catch (NumberFormatException ignored) { } }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }
    private String devStatus(boolean allowed) { if (allowed) return " (dev mode enabled by host)"; return config.devMode ? " (dev mode denied by host)" : ""; }

    private enum ConnectionState { JOINING, SYNCING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
}
