package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.*;

final class PeerServerSide {
    private static final long SNAPSHOT_MS = 100, TIMEOUT_MS = 4000;
    final World world;
    final Config config;
    final PeerTransport transport;
    final ClientViewCache views = new ClientViewCache();
    private final Map<String, ServerPeer> peers = new LinkedHashMap<>();
    private int nextPlayer = 1;
    private long sequence = 1, lastSnapshot;

    PeerServerSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
    }

    String statusLine() {
        return "HOST " + world.systemName() + " UDP " + transport.localPort() + " | clients " + peers.size() + " | pending " + transport.pendingCount() + (config.devMode ? " | dev host" : "");
    }

    void updateWorlds(double dt) {
        String old = world.activeSystemId();
        String[] systems = views.systems(world);
        ResourceNetDebug.serverUpdateSystems(world, systems, dt);
        for (String systemId : systems) {
            world.activateSystem(systemId);
            world.updateCurrentSystem(dt);
        }
        world.activateSystem(old);
    }

    void tick(long now) {
        removeTimedOut(now);
        if (now - lastSnapshot >= SNAPSHOT_MS) { broadcastNow(); lastSnapshot = now; }
    }

    void handle(String message, NetPacket packet) { PeerServerPackets.handle(this, message, packet); }
    void broadcastNow() { sequence = PeerSyncBatch.send(world, views, PeerSyncTargets.array(peers.values()), sequence, transport::send); }
    void sendInitial(ServerPeer peer) { sequence = PeerSyncBatch.sendInitial(world, views, peer, sequence, transport::send); }
    void sendInitialTo(String endpoint) { sendInitial(peers.get(endpoint)); }
    void change(String playerId, Runnable action) { views.applyChange(world, playerId, action); }
    String ownerId(String endpoint, String fallback) { ServerPeer peer = peers.get(endpoint); return peer == null ? fallback : peer.playerId(); }
    boolean owns(String endpoint, String playerId) { ServerPeer peer = peers.get(endpoint); return peer != null && playerId != null && playerId.equals(peer.playerId()); }
    void touch(String endpoint) { ServerPeer p = peers.get(endpoint); if (p != null) peers.put(endpoint, new ServerPeer(p.playerId(), p.address(), p.port(), System.currentTimeMillis(), p.devFreeBuild())); }

    void join(String endpoint, InetAddress address, int port, String name, boolean requestedDev) {
        ServerPeer old = peers.get(endpoint);
        if (old != null) { transport.reliable(welcome(old.playerId(), name, colorFor(peers.size()), old.devFreeBuild()), address, port); return; }
        String id = "P" + nextPlayer++;
        int rgb = colorFor(peers.size() + 1);
        String cleanName = Config.clean(name);
        boolean devAllowed = config.devMode && requestedDev;
        ServerPeer peer = new ServerPeer(id, address, port, System.currentTimeMillis(), devAllowed);
        peers.put(endpoint, peer);
        PlayerRegistry.register(id, cleanName, rgb, false);
        world.setDevFreeBuild(id, devAllowed);
        WorldNetAccess.addPeerGroup(world, id);
        views.setHome(world, id);
        transport.reliable(welcome(id, cleanName, rgb, devAllowed), address, port);
        transport.reliable(envMessage(), address, port);
        sendInitial(peer);
    }

    void removePeer(String endpoint) {
        ServerPeer peer = peers.remove(endpoint);
        if (peer == null) return;
        String playerId = peer.playerId();
        world.setDevFreeBuild(playerId, false);
        PlayerRegistry.remove(playerId);
        views.remove(playerId);
        Set<String> deletedSystems = world.removePlayerAndPruneEmptySystems(playerId);
        views.removeSystems(deletedSystems);
        if (!deletedSystems.isEmpty()) world.status = "Removed " + deletedSystems.size() + " abandoned system(s) after " + playerId + " left.";
        broadcastNow();
    }

    boolean requestedDev(String[] parts) { return parts.length > 2 && flag(parts[2]); }
    String endpoint(InetAddress address, int port) { return address.getHostAddress() + ':' + port; }
    private void removeTimedOut(long now) { for (String ep : new ArrayList<>(peers.keySet())) if (now - peers.get(ep).lastSeen() > TIMEOUT_MS) removePeer(ep); }
    private String envMessage() { return "ENV|" + world.systemId() + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()); }
    private String welcome(String id, String name, int rgb, boolean devAllowed) { return "WELCOME|" + id + "|" + Config.clean(name) + "|" + rgb + "|" + world.systemId() + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()) + "|DEV|" + (devAllowed ? "1" : "0"); }
    private int colorFor(int i) { int[] c = {0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C}; return c[Math.floorMod(i, c.length)]; }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }
}
