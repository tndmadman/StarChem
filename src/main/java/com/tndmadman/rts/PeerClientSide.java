package com.tndmadman.rts;

final class PeerClientSide {
    private static final long HEARTBEAT_MS = 1000;
    private static final long JOIN_TIMEOUT_MS = 8000;
    final Config config;
    final World world;
    final PeerTransport transport;
    private final long joinStarted = System.currentTimeMillis();
    private boolean joined;
    private boolean joinFailed;
    private boolean devApproved;
    private boolean viewSnapshotMode;
    private long lastJoin, lastPing, lastSnapshotSequence;
    private String localPlayerId = "SOLO";
    private String viewedSystemId = "";
    private String failureMessage = "";

    PeerClientSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
        PlayerRegistry.activate(world);
    }

    String statusLine() {
        String state = joinFailed ? "failed" : joined ? localPlayerId : "joining";
        return "CLIENT " + state + " -> " + config.serverAddress + " | " + world.activeSystemId() + " | pending " + transport.pendingCount() + (devApproved ? " | dev" : "");
    }

    String localPlayerId() { return localPlayerId; }
    boolean connectionFailed() { return joinFailed; }
    boolean devToolsAllowed() { return joined && devApproved; }
    String failureMessage() { return failureMessage.isBlank() ? "Connection failed." : failureMessage; }

    void tick(long now) {
        PlayerRegistry.activate(world);
        if (joinFailed) return;
        if (!joined && now - joinStarted >= JOIN_TIMEOUT_MS) { failJoin(); return; }
        if (!joined && now - lastJoin >= HEARTBEAT_MS) { reliableToServer(joinMessage()); lastJoin = now; }
        if (joined && now - lastPing >= HEARTBEAT_MS) { sendToServer("PING|" + localPlayerId); lastPing = now; }
    }

    void shutdown() {
        PlayerRegistry.activate(world);
        if (joined) for (int i = 0; i < 3; i++) sendToServer("LEAVE|" + localPlayerId);
    }

    void handle(String message) {
        PlayerRegistry.activate(world);
        if (readLeaderboard(message)) return;
        if (!readJoinDenied(message) && !readSystemDelete(message)) ClientPackets.handle(this, message);
    }
    void move(MoveCommand c) { reliableToServer("MOVE|" + c.playerId() + "|" + c.unitId() + "|" + Calc.round(c.x()) + "|" + Calc.round(c.y())); }
    void work(HarvestCommand c) { ResourceNetDebug.clientWorkSend(world, c); reliableToServer("WORK|" + c.playerId() + "|" + c.unitId() + "|" + c.resourceId()); }
    void attack(AttackCommand c) { reliableToServer("ATTACK|" + c.playerId() + "|" + c.unitId() + "|" + c.targetKey()); }
    void order(UnitOrderCommand c) {
        reliableToServer("ORDER|" + c.playerId() + "|" + c.unitId() + "|" + c.type().name() + "|"
                + Calc.round(c.x1()) + "|" + Calc.round(c.y1()) + "|" + Calc.round(c.x2()) + "|" + Calc.round(c.y2()) + "|"
                + Calc.round(c.radius()) + "|" + cleanPacketPart(c.targetKey()) + "|" + c.phase());
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
        viewSnapshotMode = true;
        viewedSystemId = cleanSystemId(targetSystemId);
        if (invalidSystemId(viewedSystemId)) { viewSnapshotMode = false; viewedSystemId = world.activeSystemId(); return; }
        reliableToServer("JUMP|" + playerId + "|" + viewedSystemId + "|" + Calc.round(x) + "|" + Calc.round(y));
    }
    void wormholeTouch(String playerId) { reliableToServer("WHTOUCH|" + playerId); }
    void wormholeTouch(WormholeTouchRequest request) { if (request != null && request.valid()) reliableToServer(request.packet()); }

    void readEnv(String[] p) { if (p.length >= 4) syncEnv(p[1], p[2], p[3]); else if (p.length >= 3) syncEnv(world.systemId(), p[1], p[2]); }
    void readSeed(String seed) { try { world.useSystemSeed(Long.parseLong(seed)); } catch (NumberFormatException ignored) { } }
    void readWelcome(String[] p) {
        if (p.length < 4) return;
        localPlayerId = p[1];
        joined = true;
        joinFailed = false;
        failureMessage = "";
        if (p.length >= 7) syncEnv(p[4], p[5], p[6]); else if (p.length >= 6) syncEnv(world.systemId(), p[4], p[5]); else if (p.length >= 5) readSeed(p[4]);
        PlayerRegistry.register(localPlayerId, p[2], Integer.parseInt(p[3]), true);
        world.ensurePlayerHome(localPlayerId);
        world.activateSystem(world.playerHomeSystemId(localPlayerId));
        viewedSystemId = world.activeSystemId();
        viewSnapshotMode = false;
        devApproved = p.length >= 9 && "DEV".equals(p[7]) && flag(p[8]);
        world.setDevFreeBuild(localPlayerId, devApproved);
        world.status = "Joined " + world.activeSystemId() + " as " + p[2] + devStatus(devApproved);
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

    private boolean readLeaderboard(String message) {
        if (message == null || !message.startsWith("LEADER|")) return false;
        GlobalLeaderboard.set(world, GlobalLeaderboard.decode(message));
        return true;
    }

    private boolean readJoinDenied(String message) {
        if (message == null || !message.startsWith("JOIN_DENIED|")) return false;
        String reason = message.length() > 12 ? message.substring(12).trim() : "Join refused by server.";
        failJoin(reason.isBlank() ? "Join refused by server." : reason);
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
        long sequence = snapshot.sequence();
        if (sequence <= 0) return false;
        if (sequence <= lastSnapshotSequence) {
            ResourceNetDebug.staleSnapshot(kind, snapshot, lastSnapshotSequence);
            return true;
        }
        return false;
    }

    private void acceptSnapshot(Snapshot snapshot) {
        if (snapshot.sequence() > lastSnapshotSequence) lastSnapshotSequence = snapshot.sequence();
    }

    private void rejectSnapshot(SnapshotDecodeException ex) {
        String message = ex.getMessage();
        world.status = message == null || message.isBlank()
                ? "Snapshot rejected: incompatible or corrupted state."
                : message;
        System.err.println(world.status);
    }

    private void failJoin() { failJoin("Connection failed: no response from server at " + config.serverAddress + "."); }

    private void failJoin(String message) {
        joinFailed = true;
        joined = false;
        failureMessage = message;
        transport.clearPending();
        world.status = failureMessage;
    }

    private String joinMessage() {
        String request = config.devMode ? "DEV" : "NODEV";
        String token = config.devMode ? config.devToken : "";
        return "JOIN|" + cleanPacketPart(config.playerName) + "|" + request + "|" + token;
    }
    private boolean canSendToServer() { return !joinFailed && config.serverAddress != null && config.serverAddress.getAddress() != null; }
    private String cleanPacketPart(String value) { return value == null ? "" : value.replace("|", "").trim(); }
    private String cleanSystemId(String value) { return value == null ? "" : value.replace("|", "").trim(); }
    private boolean invalidSystemId(String value) { return value == null || value.isBlank() || value.contains("WAIT"); }
    private void syncEnv(String systemId, String seed, String time) { try { world.syncEnvironment(systemId, Long.parseLong(seed), Double.parseDouble(time)); } catch (NumberFormatException ignored) { } }
    private void sendToServer(String message) { if (canSendToServer()) transport.send(message, config.serverAddress.getAddress(), config.serverAddress.getPort()); }
    private void reliableToServer(String payload) { if (canSendToServer()) transport.reliable(payload, config.serverAddress.getAddress(), config.serverAddress.getPort()); }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }
    private String devStatus(boolean allowed) { if (allowed) return " (dev mode enabled by host)"; return config.devMode ? " (dev mode denied by host)" : ""; }
}
