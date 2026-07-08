package com.tndmadman.rts;

import java.io.IOException;
import java.net.DatagramSocket;

final class PeerNetwork implements CommandSink {
    private final Config config;
    private final PeerTransport transport;
    private final PeerServerSide server;
    private final PeerClientSide client;

    private PeerNetwork(Config config, PeerTransport transport, PeerServerSide server, PeerClientSide client) {
        this.config = config;
        this.transport = transport;
        this.server = server;
        this.client = client;
    }

    static PeerNetwork start(Config config, World world) throws IOException {
        if (!config.hostMode && !config.clientMode()) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("SOLO", config.devMode);
            ResourceNetDebug.registerClientWorld(world);
            if (config.devMode) world.status = "Solo dev mode enabled.";
            return null;
        }
        DatagramSocket socket = config.hostMode ? new DatagramSocket(config.port) : new DatagramSocket();
        PeerTransport transport = new PeerTransport(socket);
        PeerServerSide server = null;
        PeerClientSide client = null;
        if (config.hostMode) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("SOLO", config.devMode);
            world.status = "Hosting " + world.systemName() + " UDP " + transport.localPort() + (config.devMode ? " with dev mode enabled" : "");
            ResourceNetDebug.registerServerWorld(world);
            server = new PeerServerSide(config, world, transport);
        } else {
            PlayerRegistry.reset("WAIT", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("WAIT", false);
            world.status = "Joining " + config.serverAddress;
            ResourceNetDebug.registerClientWorld(world);
            client = new PeerClientSide(config, world, transport);
        }
        transport.start();
        return new PeerNetwork(config, transport, server, client);
    }

    String statusLine() { return server != null ? server.statusLine() : client.statusLine(); }
    String localPlayerId() { return client != null ? client.localPlayerId() : "SOLO"; }
    void updateServerWorlds(double dt) { if (server != null) server.updateWorlds(dt); }

    void tick() {
        long now = System.currentTimeMillis();
        NetPacket packet;
        while ((packet = transport.poll()) != null) {
            String message = transport.unwrapReliable(packet);
            if (message == null) continue;
            if (server != null) server.handle(message, packet);
            else client.handle(message);
        }
        transport.resend(now);
        if (server != null) server.tick(now);
        else client.tick(now);
    }

    void shutdown() {
        if (client != null) client.shutdown();
        transport.shutdown();
    }

    @Override public void move(MoveCommand c) { if (server != null) serverCommand(() -> AUnitMove.apply(server.world, c), c.playerId()); else client.move(c); }
    @Override public void work(HarvestCommand c) { if (server != null) serverCommand(() -> AUnitWork.apply(server.world, c), c.playerId()); else client.work(c); }
    @Override public void attack(AttackCommand c) { if (server != null) serverCommand(() -> AUnitAttack.apply(server.world, c), c.playerId()); else client.attack(c); }
    @Override public void respawn(String playerId) { if (server != null) { WorldNetAccess.respawnPlayer(server.world, playerId); server.broadcastNow(); } else client.respawn(playerId); }
    @Override public void build(String playerId, String baseId, String shipTypeId) { if (server != null) serverCommand(() -> { if (CommandAuth.base(server.world, playerId, baseId)) server.world.buildShip(baseId, shipTypeId); }, playerId); else client.build(playerId, baseId, shipTypeId); }
    @Override public void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { if (server != null) serverCommand(() -> { if (CommandAuth.pack(server.world, playerId, mode, baseOrUnitId)) AUnitPack.apply(server.world, mode, baseOrUnitId, packageType); }, playerId); else client.basePackage(playerId, mode, baseOrUnitId, packageType); }
    void jump(String playerId, double x, double y) { jump(playerId, "", x, y); }
    void jump(String playerId, String targetSystemId, double x, double y) { if (server != null) serverCommand(() -> { if (!server.world.viewSystemThroughWormhole(targetSystemId)) server.world.jumpThroughWormholeAt(x, y); }, playerId); else client.jump(playerId, targetSystemId, x, y); }
    void wormholeTouch(String playerId) { if (server != null) serverCommand(server.world::transferTouchingShips, playerId); else client.wormholeTouch(playerId); }

    private void serverCommand(Runnable action, String playerId) {
        server.change(playerId, action);
        server.broadcastNow();
    }
}
