package com.tndmadman.rts;

final class PeerClientSide {
    private static final long HEARTBEAT_MS = 1000;
    final Config config;
    final World world;
    final PeerTransport transport;
    private boolean joined;
    private boolean viewSnapshotMode;
    private long lastJoin, lastPing, lastSnapshotSequence;
    private String localPlayerId = "SOLO";
    private String viewedSystemId = "";

    PeerClientSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
    }

    String statusLine() {
        return "CLIENT " + (joined ? localPlayerId : "joining") + " -> " + config.serverAddress + " | " + world.activeSystemId() + " | pending " + transport.pendingCount() + (world.devFreeBuild ? " | dev" : "");
    }

    String localPlayerId() { return localPlayerId; }

    void tick(long now) {
        if (!joined && now - lastJoin >= HEARTBEAT_MS) { reliableToServer("JOIN|" + config.playerName + "|" + (config.devMode ? "DEV" : "NODEV")); lastJoin = now; }
        if (joined && now - lastPing >= HEARTBEAT_MS) { sendToServer("PING|" + localPlayerId); lastPing = now; }
    }

    void shutdown() {
        if (joined) for (int i = 0; i < 3; i++) sendToServer("LEAVE|" + localPlayerId);
    }

    void handle(String message) { ClientPackets.handle(this, message); }
    void move(MoveCommand c) { reliableToServer("MOVE|" + c.playerId() + "|" + c.unitId() + "|" + Calc.round(c.x()) + "|" + Calc.round(c.y())); }
    void work(HarvestCommand c) { ResourceNetDebug.clientWorkSend(world, c); reliableToServer("WORK|" + c.playerId() + "|" + c.unitId() + "|" + c.resourceId()); }
    void attack(AttackCommand c) { reliableToServer("ATTACK|" + c.playerId() + "|" + c.unitId() + "|" + c.targetKey()); }
    void respawn(String playerId) { reliableToServer("RESPAWN|" + playerId); }
    void build(String playerId, String baseId, String shipTypeId) { reliableToServer("BUILD|" + playerId + "|" + baseId + "|" + shipTypeId); }
    void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { reliableToServer("PACK|" + playerId + "|" + mode + "|" + baseOrUnitId + "|" + packageType); }
    void jump(String playerId, double x, double y) {
        viewSnapshotMode = true;
        viewedSystemId = world.activeSystemId();
        reliableToServer("JUMP|" + playerId + "|" + Calc.round(x) + "|" + Calc.round(y));
    }
    void wormholeTouch(String playerId) { reliableToServer("WHTOUCH|" + playerId); }

    void readEnv(String[] p) { if (p.length >= 4) syncEnv(p[1], p[2], p[3]); else if (p.length >= 3) syncEnv(world.systemId(), p[1], p[2]); }
    void readSeed(String seed) { try { world.useSystemSeed(Long.parseLong(seed)); } catch (NumberFormatException ignored) { } }
    void readWelcome(String[] p) {
        if (p.length < 4) return;
        localPlayerId = p[1];
        joined = true;
        if (p.length >= 7) syncEnv(p[4], p[5], p[6]); else if (p.length >= 6) syncEnv(world.systemId(), p[4], p[5]); else if (p.length >= 5) readSeed(p[4]);
        PlayerRegistry.register(localPlayerId, p[2], Integer.parseInt(p[3]), true);
        world.ensurePlayerHome(localPlayerId);
        world.activateSystem(world.playerHomeSystemId(localPlayerId));
        viewedSystemId = world.activeSystemId();
        boolean devAllowed = p.length >= 9 && "DEV".equals(p[7]) && flag(p[8]);
        world.setDevFreeBuild(localPlayerId, devAllowed);
        world.status = "Joined " + world.activeSystemId() + " as " + p[2] + devStatus(devAllowed);
    }

    void readSnapshot(String message) {
        Snapshot snapshot = SnapshotReader.read(message);
        ResourceNetDebug.clientReceive("REGULAR", snapshot, lastSnapshotSequence, viewSnapshotMode);
        if (holdingDifferentView(snapshot)) return;
        if (stale(snapshot, "REGULAR")) return;
        if (viewSnapshotMode) WorldNetAccess.applyView(world, snapshot);
        else WorldNetAccess.apply(world, snapshot);
    }

    void readFullView(String message) {
        Snapshot snapshot = SyncFrame.read(message);
        boolean requestedView = viewSnapshotMode;
        ResourceNetDebug.clientReceive("FULL_VIEW", snapshot, lastSnapshotSequence, viewSnapshotMode);
        if (holdingDifferentView(snapshot)) return;
        if (snapshot.sequence() > lastSnapshotSequence) lastSnapshotSequence = snapshot.sequence();
        if (requestedView && snapshot.systemId() != null && !snapshot.systemId().isBlank()) viewedSystemId = snapshot.systemId();
        WorldNetAccess.applyFullView(world, snapshot);
        if (!requestedView && WorldNetAccess.hasPlayerAssets(snapshot, localPlayerId)) viewedSystemId = world.activeSystemId();
    }

    private boolean holdingDifferentView(Snapshot snapshot) {
        if (!viewSnapshotMode || viewedSystemId == null || viewedSystemId.isBlank()) return false;
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
        lastSnapshotSequence = sequence;
        return false;
    }

    private void syncEnv(String systemId, String seed, String time) { try { world.syncEnvironment(systemId, Long.parseLong(seed), Double.parseDouble(time)); } catch (NumberFormatException ignored) { } }
    private void sendToServer(String message) { transport.send(message, config.serverAddress.getAddress(), config.serverAddress.getPort()); }
    private void reliableToServer(String payload) { transport.reliable(payload, config.serverAddress.getAddress(), config.serverAddress.getPort()); }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }
    private String devStatus(boolean allowed) { if (allowed) return " (dev mode enabled by host)"; return config.devMode ? " (dev mode denied by host)" : ""; }
}
