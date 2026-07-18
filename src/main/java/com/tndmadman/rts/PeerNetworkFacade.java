package com.tndmadman.rts;

import java.io.IOException;
import java.util.List;

final class PeerNetwork implements CommandSink {
    private final Config config;
    private final PeerTransport transport;
    private final PeerServerSide server;
    private final PeerClientSide client;
    private final PerfStats perfStats;

    private PeerNetwork(Config config, PeerTransport transport, PeerServerSide server, PeerClientSide client, PerfStats perfStats) {
        this.config = config;
        this.transport = transport;
        this.server = server;
        this.client = client;
        this.perfStats = perfStats;
    }

    static PeerNetwork start(Config config, World world) throws IOException {
        return start(config, world, List.of());
    }

    static PeerNetwork start(Config config, World world, List<PersistentPlayerSession> restoredSessions) throws IOException {
        if (!config.hostMode && !config.clientMode()) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("SOLO", config.devMode);
            ResourceNetDebug.registerClientWorld(world);
            if (config.devMode) world.status = "Solo dev mode enabled.";
            return null;
        }
        if (config.clientMode() && config.serverAddress.isUnresolved()) {
            throw new IOException("Could not resolve server host: " + config.serverAddress.getHostString());
        }
        try {
            MultiplayerCompatibility.local();
        } catch (IllegalStateException ex) {
            throw new IOException("Multiplayer compatibility setup failed: " + ex.getMessage(), ex);
        }
        PerfStats perfStats = new PerfStats();
        PeerTransport transport = config.hostMode
                ? PeerTransport.server(config, perfStats)
                : PeerTransport.client(config, perfStats);
        PeerServerSide server = null;
        PeerClientSide client = null;
        if (config.hostMode) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("SOLO", config.devMode);
            world.status = "Hosting " + world.systemName() + " TCP " + transport.localPort() + (config.devMode ? " with dev mode enabled" : "");
            ResourceNetDebug.registerServerWorld(world);
            server = new PeerServerSide(config, world, transport, restoredSessions);
        } else {
            PlayerRegistry.reset("WAIT", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("WAIT", false);
            world.status = "Joining " + config.serverAddress;
            ResourceNetDebug.registerClientWorld(world);
            client = new PeerClientSide(config, world, transport);
        }
        transport.start();
        return new PeerNetwork(config, transport, server, client, perfStats);
    }

    String statusLine() { return server != null ? server.statusLine() : client.statusLine(); }
    String localPlayerId() { return client != null ? client.localPlayerId() : "SOLO"; }
    boolean connectionFailed() { return client != null && client.connectionFailed(); }
    String failureMessage() { return client != null ? client.failureMessage() : "Connection failed."; }
    PerfSnapshot perfSnapshot() { return transport.perfSnapshot(); }
    boolean clientMode() { return client != null; }
    boolean clientReconnecting() { return client != null && client.reconnecting(); }
    boolean clientConnected() { return client != null && client.connectedState(); }
    boolean clientReady() { return client == null || client.readyState(); }
    ClientConnectionProgress clientConnectionProgress() {
        return client == null
                ? new ClientConnectionProgress(ConnectionPhase.READY, "READY", "Local authoritative session.", 4, 4, 0)
                : client.connectionProgress();
    }
    long clientSnapshotSequence() { return client == null ? 0 : client.lastSnapshotSequence(); }
    String clientViewedSystemId() { return client == null ? "" : client.viewedSystemId(); }
    long clientPendingViewRevision() { return client == null ? 0 : client.pendingViewRevision(); }
    boolean clientViewSwitchPending() { return client != null && client.viewSwitchPending(); }
    boolean serverCertificateTrustRequired() { return client != null && client.serverCertificateTrustRequired(); }
    String serverCertificateTrustPrompt() { return client == null ? "" : client.serverCertificateTrustPrompt(); }
    boolean trustChangedServerCertificate() { return client != null && client.trustChangedServerCertificate(); }
    void forceClientDisconnectForTest() { if (client != null) transport.forceDisconnectClientForTest(); }
    ConnectionId clientConnectionId() { return transport.clientConnectionId(); }
    ConnectionId connectionIdForPlayer(String playerId) { return server == null ? ConnectionId.NONE : server.connectionIdForPlayer(playerId); }
    ConnectionDiagnostics connectionDiagnostics(ConnectionId id) { return transport.diagnostics(id); }
    int serverPeerCount() { return server == null ? 0 : server.peerCount(); }
    boolean serverSessionConnected(String playerId) { return server != null && server.sessionConnected(playerId); }
    List<PersistentPlayerSession> persistentPlayerSessions() { return server == null ? List.of() : server.persistentSessions(); }
    void forceServerResourceCorrectionForTest() { if (server != null) server.forceResourceCorrectionForTest(); }

    void updateServerWorlds(double dt) {
        if (server == null) return;
        long started = System.nanoTime();
        server.updateWorlds(dt);
        perfStats.recordServerUpdate(System.nanoTime() - started);
    }

    boolean devToolsAllowed() { return server != null ? config.devMode : client != null && client.devToolsAllowed(); }
    List<DevPeerAccess> devAccessPeers() { return server == null ? List.of() : server.devAccessPeers(); }
    void setRemoteDevAccess(String playerId, boolean enabled) { if (server != null) server.setDevAccess(playerId, enabled); }

    void devSetFreeCrafting(String playerId, boolean enabled) {
        if (server != null) {
            if (server.localDevAllowed(playerId)) server.applyDevFreeCrafting(playerId, enabled);
        } else if (client != null) client.devSetFreeCrafting(playerId, enabled);
    }

    void devAddHangarResource(String playerId, String baseId, Material material, double amount) {
        if (server != null) {
            if (server.localDevAllowed(playerId)) server.applyDevHangarResource(playerId, baseId, material, amount);
        } else if (client != null) client.devAddHangarResource(playerId, baseId, material, amount);
    }

    void devAiCommand(String playerId, String command) {
        if (server != null) {
            if (server.localDevAllowed(playerId)) server.applyDevAiCommand(playerId, command);
        } else if (client != null) client.devAiCommand(playerId, command);
    }

    void tick() {
        long started = System.nanoTime();
        try {
            long now = System.currentTimeMillis();
            NetPacket packet;
            while ((packet = transport.poll()) != null) {
                if (!transport.accepts(packet)) continue;
                if (transport.isDisconnectEvent(packet)) {
                    if (server != null) server.connectionClosed(packet);
                    continue;
                }
                try {
                    String message = transport.processInbound(packet);
                    if (message == null) continue;
                    if (server != null) server.handle(message, packet);
                    else client.handle(packet, message);
                } catch (RuntimeException ex) {
                    transport.recordMalformedPacket();
                    if (client != null) client.rejectPacket(ex);
                    else System.err.println("Rejected malformed TCP frame: " + ex.getClass().getSimpleName());
                }
            }
            if (server != null) server.tick(now);
            else client.tick(now);
        } finally {
            perfStats.recordNetwork(System.nanoTime() - started);
        }
    }

    void shutdown() {
        if (client != null) client.shutdown();
        transport.shutdown();
    }

    @Override public void move(MoveCommand c) { if (server != null) serverCommand(() -> AUnitMove.apply(server.world, c), c.playerId()); else client.move(c); }
    @Override public void work(HarvestCommand c) { if (server != null) serverCommand(() -> AUnitWork.apply(server.world, c), c.playerId()); else client.work(c); }
    @Override public void attack(AttackCommand c) { if (server != null) serverCommand(() -> AUnitAttack.apply(server.world, c), c.playerId()); else client.attack(c); }
    @Override public void order(UnitOrderCommand c) { if (server != null) serverCommand(() -> AUnitOrder.apply(server.world, c), c.playerId()); else client.order(c); }
    @Override public void respawn(String playerId) { if (server != null) { WorldNetAccess.respawnPlayer(server.world, playerId); server.broadcastNow(); } else client.respawn(playerId); }
    @Override public void build(String playerId, String baseId, String shipTypeId) { if (server != null) serverCommand(() -> { if (CommandAuth.base(server.world, playerId, baseId)) server.world.buildShip(baseId, shipTypeId); }, playerId); else client.build(playerId, baseId, shipTypeId); }
    @Override public void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { if (server != null) serverCommand(() -> { if (CommandAuth.pack(server.world, playerId, mode, baseOrUnitId)) AUnitPack.apply(server.world, mode, baseOrUnitId, packageType); }, playerId); else client.basePackage(playerId, mode, baseOrUnitId, packageType); }
    @Override public void production(String playerId, String action, String baseId, String value, String extra) { if (server != null) serverCommand(() -> { if (CommandAuth.base(server.world, playerId, baseId)) ProductionCommands.apply(server.world, playerId, action, baseId, value, extra); }, playerId); else client.production(playerId, action, baseId, value, extra); }
    void viewSystem(String playerId, String targetSystemId) {
        if (server != null) {
            ConnectionId connectionId = server.connectionIdForPlayer(playerId);
            if (connectionId.valid()) server.requestView(connectionId, playerId, targetSystemId, 0);
            else server.world.viewGalaxySystem(targetSystemId);
        } else client.viewSystem(playerId, targetSystemId);
    }
    void jump(String playerId, double x, double y) { if (client != null) client.jump(playerId, x, y); }
    void jump(String playerId, String targetSystemId, double x, double y) { viewSystem(playerId, targetSystemId); }
    void wormholeTouch(String playerId) { if (server != null) serverCommand(server.world::transferTouchingShips, playerId); else client.wormholeTouch(playerId); }
    void wormholeTouch(WormholeTouchRequest request) { if (request == null || !request.valid()) return; if (server != null) serverCommand(() -> server.world.transferTouchingShips(request.playerId()), request.playerId()); else client.wormholeTouch(request); }

    private void serverCommand(Runnable action, String playerId) {
        server.change(playerId, action);
        server.broadcastNow();
    }
}
