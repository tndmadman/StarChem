package com.tndmadman.rts;

final class PeerClientSide {
    private static final long HEARTBEAT_MS = 1000;
    private static final long JOIN_TIMEOUT_MS = 8000;
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
    private boolean connectedOnce;
    private boolean devApproved;
    private boolean viewSnapshotMode;
    private String localPlayerId = "SOLO";
    private String sessionToken = "";
    private String viewedSystemId = "";
    private String failureMessage = "";

    PeerClientSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
        PlayerRegistry.activate(world);
        long now = System.currentTimeMillis();
        attemptStarted = now;
        lastServerPacket = now;
        SessionTokenStore.StoredSession stored = SessionTokenStore.load(config);
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
            case CONNECTED -> localPlayerId;
            case RECONNECTING -> "reconnecting " + localPlayerId;
            case DISCONNECTED -> "disconnected";
            case FAILED -> "failed";
        };
        return "CLIENT " + label + " -> " + config.serverAddress + " | " + world.activeSystemId()
                + " | pending " + transport.pendingCount() + (devApproved ? " | dev" : "");
    }

    String localPlayerId() { return localPlayerId; }
    boolean connectionFailed() { return state == ConnectionState.FAILED; }
    boolean devToolsAllowed() { return state == ConnectionState.CONNECTED && devApproved; }
    String failureMessage() { return failureMessage.isBlank() ? "Connection failed." : failureMessage; }
    static long serverSilenceMs() { return SERVER_SILENCE_MS; }

    void tick(long now) {
        PlayerRegistry.activate(world);
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
            case JOINING -> {
                if (now - attemptStarted >= JOIN_TIMEOUT_MS) {
                    failConnection("Connection failed: no response from server at " + config.serverAddress + ".");
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
                    failConnection("Connection failed: the saved session could not be resumed before it expired.");
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
        if (state == ConnectionState.CONNECTED) {
            for (int i = 0; i < 3; i++) sendControlToServer("LEAVE|" + localPlayerId);
        }
        state = ConnectionState.DISCONNECTED;
        devApproved = false;
        world.setDevFreeBuild(localPlayerId, false);
    }

    void handle(NetPacket packet, String message) {
        if (!fromConfiguredServer(packet)) return;
        PlayerRegistry.activate(world);
        lastServerPacket = System.currentTimeMillis();
        if (readGalaxy(message) || readLeaderboard(message) || readDevStatus(message)) return;
        if (!readJoinDenied(message) && !readSessionDenied(message) && !readSystemDelete(message)) ClientPackets.handle(this, message);
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

    void move(MoveCommand command) { reliableToServer("MOVE|" + command.playerId() + "|" + command.unitId() + "|" + Calc.round(command.x()) + "|" + Calc.round(command.y())); }
    void work(HarvestCommand command) { ResourceNetDebug.clientWorkSend(world, command); reliableToServer("WORK|" + command.playerId() + "|" + command.unitId() + "|" + command.resourceId()); }
    void attack(AttackCommand command) { reliableToServer("ATTACK|" + command.playerId() + "|" + command.unitId() + "|" + command.targetKey()); }
    void order(UnitOrderCommand command) {
        reliableToServer("ORDER|" + command.playerId() + "|" + command.unitId() + "|" + command.type().name() + "|"
                + Calc.round(command.x1()) + "|" + Calc.round(command.y1()) + "|" + Calc.round(command.x2()) + "|" + Calc.round(command.y2()) + "|"
                + Calc.round(command.radius()) + "|" + cleanPacketPart(command.targetKey()) + "|" + command.phase());
    }
    void respawn(String playerId) { reliableToServer("RESPAWN|" + playerId); }
    void build(String playerId, String baseId, String shipTypeId) { reliableToServer("BUILD|" + playerId + "|" + baseId + "|" + shipTypeId); }
    void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { reliableToServer("PACK|" + playerId + "|" + mode + "|" + baseOrUnitId + "|" + packageType); }
    void production(String playerId, String action, String baseId, String value, String extra) {
        reliableToServer("PROD|" + cleanPacketPart(playerId) + "|" + cleanPacketPart(action) + "|"
                + cleanPacketPart(baseId) + "|" + cleanPacketPart(value) + "|" + cleanPacketPart(extra));
    }
    void devSetFreeCrafting(String playerId, boolean enabled) { reliableToServer("DEVFREE|" + cleanPacketPart(playerId) + "|" + (enabled ? "1" : "0")); }
    void devAddHangarResource(String playerId, String baseId, Material material, double amount) {
        if (material == null || amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        reliableToServer("DEVHANGAR|" + cleanPacketPart(playerId) + "|" + cleanPacketPart(baseId) + "|" + material.name() + "|" + Calc.round(amount));
    }
    void devAiCommand(String playerId, String command) { reliableToServer("DEVAI|" + cleanPacketPart(playerId) + "|" + cleanPacketPart(command)); }
    void jump(String playerId, double x, double y) { jump(playerId, "", x, y); }
    void jump(String playerId, String targetSystemId, double x, double y) {
        if (!canIssueCommands()) { blockCommand(); return; }
        viewSnapshotMode = true;
        viewedSystemId = cleanSystemId(targetSystemId);
        if (invalidSystemId(viewedSystemId)) { viewSnapshotMode = false; viewedSystemId = world.activeSystemId(); return; }
        reliableToServer("JUMP|" + playerId + "|" + viewedSystemId + "|" + Calc.round(x) + "|" + Calc.round(y));
    }
    void wormholeTouch(String playerId) { reliableToServer("WHTOUCH|" + playerId); }
    void wormholeTouch(WormholeTouchRequest request) { if (request != null && request.valid()) reliableToServer(request.packet()); }

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
        localPlayerId = parts[1];
        sessionToken = newSessionToken;
        state = ConnectionState.CONNECTED;
        failureMessage = "";
        transport.clearPending();
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
        devApproved = flag(markerValue(parts, "DEV"));
        world.setDevFreeBuild(localPlayerId, devApproved);
        SessionTokenStore.save(config, localPlayerId, sessionToken);
        boolean resumed = previousState == ConnectionState.RECONNECTING || connectedOnce;
        connectedOnce = true;
        world.status = (resumed ? "Reconnected " : "Joined ") + world.activeSystemId() + " as " + parts[2] + devStatus(devApproved);
    }

    void readSnapshot(String message) {
        try {
            Snapshot snapshot = SnapshotReader.read(message);
            ResourceNetDebug.clientReceive("REGULAR", snapshot, lastSnapshotSequence, viewSnapshotMode);
            if (holdingDifferentView(snapshot)) return;
            if (stale(snapshot, "REGULAR")) return;
            if (viewSnapshotMode) WorldNetAccess.applyView(world, snapshot);
            else WorldNetAccess.apply(world, snapshot);
            acceptSnapshot(snapshot);
        } catch (SnapshotDecodeException ex) {
            rejectSnapshot(ex);
        }
    }

    void readFullView(String message) {
        try {
            Snapshot snapshot = SyncFrame.read(message);
            boolean requestedView = viewSnapshotMode;
            ResourceNetDebug.clientReceive("FULL_VIEW", snapshot, lastSnapshotSequence, viewSnapshotMode);
            if (holdingDifferentView(snapshot)) return;
            if (stale(snapshot, "FULL_VIEW")) return;
            WorldNetAccess.applyFullView(world, snapshot);
            acceptSnapshot(snapshot);
            if (requestedView && snapshot.systemId() != null && !snapshot.systemId().isBlank()) viewedSystemId = snapshot.systemId();
            if (!requestedView && WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId)) viewedSystemId = world.activeSystemId();
        } catch (SnapshotDecodeException ex) {
            rejectSnapshot(ex);
        }
    }

    private boolean readGalaxy(String message) {
        if (message == null || !message.startsWith("GALAXY|")) return false;
        GalaxyMapWire.Decoded decoded = GalaxyMapWire.decode(message);
        world.configureGalaxyCopies(decoded.copiesPerTemplate());
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

    private boolean readJoinDenied(String message) {
        if (message == null || !message.startsWith("JOIN_DENIED|")) return false;
        String reason = message.length() > 12 ? message.substring(12).trim() : "Join refused by server.";
        failConnection(reason.isBlank() ? "Join refused by server." : reason);
        return true;
    }

    private boolean readSessionDenied(String message) {
        if (message == null || !message.startsWith("SESSION_DENIED|")) return false;
        String reason = message.length() > 15 ? message.substring(15).trim() : "Saved session was rejected.";
        SessionTokenStore.clear(config);
        sessionToken = "";
        transport.clearPending();
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
        if (state != ConnectionState.CONNECTED) return;
        if (sessionToken.isBlank()) {
            failConnection("Connection lost and no resumable session is available.");
            return;
        }
        state = ConnectionState.RECONNECTING;
        attemptStarted = now;
        lastHandshake = 0;
        devApproved = false;
        world.setDevFreeBuild(localPlayerId, false);
        transport.clearPending();
        world.status = "Connection interrupted. Reconnecting to " + config.serverAddress + " without dropping player state.";
    }

    private void failConnection(String message) {
        state = ConnectionState.FAILED;
        devApproved = false;
        world.setDevFreeBuild(localPlayerId, false);
        failureMessage = message;
        transport.clearPending();
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
        return "JOIN|" + cleanPacketPart(config.playerName) + "|" + request + "|" + token;
    }

    private String resumeMessage() {
        String request = config.devMode ? "DEV" : "NODEV";
        String devToken = config.devMode ? config.devToken : "";
        return "RESUME|" + cleanPacketPart(localPlayerId) + "|" + sessionToken + "|" + request + "|" + devToken;
    }

    private boolean canIssueCommands() { return state == ConnectionState.CONNECTED; }

    private void blockCommand() {
        world.status = switch (state) {
            case RECONNECTING -> "Command blocked while reconnecting.";
            case JOINING -> "Command blocked until the server finishes joining.";
            case DISCONNECTED -> "Command blocked because the client is disconnected.";
            case FAILED -> "Command blocked because the connection failed.";
            case CONNECTED -> world.status;
        };
    }

    private void reliableToServer(String payload) {
        if (!canIssueCommands()) { blockCommand(); return; }
        if (canSendControl()) transport.reliable(payload, config.serverAddress.getAddress(), config.serverAddress.getPort());
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

    private enum ConnectionState { JOINING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
}
