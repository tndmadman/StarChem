package com.tndmadman.rts;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

final class PeerNetwork implements CommandSink {
    private static final long HEARTBEAT_MS = 1000, SNAPSHOT_MS = 100, RELIABLE_MS = 450, TIMEOUT_MS = 4000, ENV_SYNC_MS = 1000;
    private final Config config;
    private final World world;
    private final DatagramSocket socket;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, ServerPeer> peers = new LinkedHashMap<>();
    private final Map<String, PendingReliable> pending = new LinkedHashMap<>();
    private final Set<String> delivered = new LinkedHashSet<>();
    private final String reliablePrefix = Integer.toHexString(new SecureRandom().nextInt()).replace('-', 'N');
    private boolean running = true;
    private boolean joined;
    private int nextPlayer = 1;
    private long nextReliable = 1, sequence = 1, lastJoin, lastPing, lastSnapshot, lastEnvSync;
    private String localPlayerId = "SOLO";

    private PeerNetwork(Config config, World world, DatagramSocket socket) { this.config = config; this.world = world; this.socket = socket; }

    static PeerNetwork start(Config config, World world) throws IOException {
        if (!config.hostMode && !config.clientMode()) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            return null;
        }
        DatagramSocket socket = config.hostMode ? new DatagramSocket(config.port) : new DatagramSocket();
        socket.setSoTimeout(250);
        PeerNetwork network = new PeerNetwork(config, world, socket);
        if (config.hostMode) {
            network.joined = true;
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.status = "Hosting UDP " + socket.getLocalPort();
        } else {
            PlayerRegistry.reset("WAIT", config.playerName, 0x50BEFF);
            world.status = "Joining " + config.serverAddress;
        }
        Thread thread = new Thread(network::listenLoop, "starchem-udp");
        thread.setDaemon(true);
        thread.start();
        return network;
    }

    String statusLine() { return config.hostMode ? "HOST UDP " + socket.getLocalPort() + " | clients " + peers.size() + " | pending " + pending.size() : "CLIENT " + (joined ? localPlayerId : "joining") + " -> " + config.serverAddress + " | pending " + pending.size(); }
    String localPlayerId() { return localPlayerId; }

    void tick() {
        long now = System.currentTimeMillis();
        NetPacket packet;
        while ((packet = inbox.poll()) != null) handle(packet);
        resend(now);
        if (config.hostMode) {
            removeTimedOutPeers(now);
            if (now - lastSnapshot >= SNAPSHOT_MS) { broadcast(SnapshotWriter.write(WorldNetAccess.snapshot(world, sequence++))); lastSnapshot = now; }
            if (now - lastEnvSync >= ENV_SYNC_MS) { broadcast(envMessage()); lastEnvSync = now; }
        } else {
            if (!joined && now - lastJoin >= HEARTBEAT_MS) { reliableToServer("JOIN|" + config.playerName); lastJoin = now; }
            if (joined && now - lastPing >= HEARTBEAT_MS) { sendToServer("PING|" + localPlayerId); lastPing = now; }
        }
    }

    void shutdown() {
        if (!config.hostMode && joined) for (int i = 0; i < 3; i++) sendToServer("LEAVE|" + localPlayerId);
        running = false;
        socket.close();
    }

    @Override public void move(MoveCommand c) { if (config.hostMode) { applyMove(c); broadcastNow(); } else sendToServer("MOVE|" + c.playerId() + "|" + c.unitId() + "|" + Calc.round(c.x()) + "|" + Calc.round(c.y())); }
    @Override public void work(HarvestCommand c) { if (config.hostMode) { applyWork(c); broadcastNow(); } else sendToServer("WORK|" + c.playerId() + "|" + c.unitId() + "|" + c.resourceId()); }
    @Override public void build(String playerId, String baseId, String shipTypeId) { if (config.hostMode) { if (CommandAuth.base(world, playerId, baseId)) world.buildShip(baseId, shipTypeId); broadcastNow(); } else sendToServer("BUILD|" + playerId + "|" + baseId + "|" + shipTypeId); }
    @Override public void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { if (config.hostMode) { if (CommandAuth.pack(world, playerId, mode, baseOrUnitId)) applyPack(mode, baseOrUnitId, packageType); broadcastNow(); } else sendToServer("PACK|" + playerId + "|" + mode + "|" + baseOrUnitId + "|" + packageType); }

    private void applyMove(MoveCommand c) { Unit u = world.units.get(Unit.key(c.playerId(), c.unitId())); if (u != null) u.moveTo(c.x(), c.y()); }
    private void applyWork(HarvestCommand c) { Unit u = world.units.get(Unit.key(c.playerId(), c.unitId())); if (u != null) u.startAutoHarvest(c.resourceId()); }
    private void applyPack(String mode, String id, String packageType) { if ("LOAD".equals(mode)) world.loadBasePackage(id, packageType); else world.placePackage(world.units.get(id)); }
    private void removePlayer(String playerId) { world.units.values().removeIf(u -> u.playerId.equals(playerId)); world.bases.values().removeIf(b -> b.playerId.equals(playerId)); PlayerRegistry.remove(playerId); }
    private void broadcastNow() { broadcast(SnapshotWriter.write(WorldNetAccess.snapshot(world, sequence++))); }

    private void handle(NetPacket packet) {
        String m = packet.message();
        if (m.startsWith("ACK|")) { pending.remove(m.substring(4)); return; }
        if (m.startsWith("REL|")) {
            String[] p = m.split("\\|", 3);
            if (p.length < 3) return;
            send("ACK|" + p[1], packet.address(), packet.port());
            String key = packet.address().getHostAddress() + ':' + packet.port() + '|' + p[1];
            if (!delivered.add(key)) return;
            while (delivered.size() > 512) delivered.remove(delivered.iterator().next());
            m = p[2];
        }
        if (config.hostMode) hostPacket(m, packet); else clientPacket(m);
    }

    private void hostPacket(String m, NetPacket packet) {
        String[] p = m.split("\\|", -1);
        String ep = endpoint(packet.address(), packet.port());
        try {
            switch (p[0]) {
                case "JOIN" -> joinPeer(ep, packet.address(), packet.port(), p.length > 1 ? p[1] : "Player");
                case "PING" -> touch(ep);
                case "MOVE" -> { touch(ep); if (owns(ep, p[1])) applyMove(new MoveCommand(p[1], Integer.parseInt(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]))); }
                case "WORK" -> { touch(ep); if (owns(ep, p[1])) applyWork(new HarvestCommand(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]))); }
                case "BUILD" -> { touch(ep); if (owns(ep, p[1]) && CommandAuth.base(world, p[1], p[2])) world.buildShip(p[2], p[3]); }
                case "PACK" -> { touch(ep); if (owns(ep, p[1]) && CommandAuth.pack(world, p[1], p[2], p[3])) applyPack(p[2], p[3], p[4]); }
                case "LEAVE" -> removePeer(ep);
            }
        } catch (Exception ex) { System.err.println("Bad packet: " + m + " / " + ex.getMessage()); }
    }

    private boolean owns(String endpoint, String playerId) {
        ServerPeer peer = peers.get(endpoint);
        return peer != null && playerId != null && playerId.equals(peer.playerId());
    }

    private void clientPacket(String m) {
        String[] p = m.split("\\|", -1);
        if (p[0].equals("ENV") && p.length >= 3) {
            syncEnv(p[1], p[2]);
            return;
        }
        if (p[0].equals("SEED") && p.length >= 2) {
            try { world.useSystemSeed(Long.parseLong(p[1])); } catch (NumberFormatException ignored) { }
            return;
        }
        if (p[0].equals("WELCOME") && p.length >= 4) {
            localPlayerId = p[1];
            joined = true;
            if (p.length >= 6) syncEnv(p[4], p[5]);
            else if (p.length >= 5) try { world.useSystemSeed(Long.parseLong(p[4])); } catch (NumberFormatException ignored) { }
            PlayerRegistry.register(localPlayerId, p[2], Integer.parseInt(p[3]), true);
            world.status = "Joined as " + p[2];
            return;
        }
        if (p[0].equals("SNAPSHOT")) WorldNetAccess.apply(world, SnapshotReader.read(m));
    }

    private void joinPeer(String ep, InetAddress address, int port, String name) {
        ServerPeer old = peers.get(ep);
        if (old != null) { reliable(welcome(old.playerId(), name, colorFor(peers.size())), address, port); return; }
        String id = "P" + nextPlayer++;
        int rgb = colorFor(peers.size() + 1);
        String cleanName = Config.clean(name);
        peers.put(ep, new ServerPeer(id, address, port, System.currentTimeMillis()));
        PlayerRegistry.register(id, cleanName, rgb, false);
        WorldNetAccess.addPeerGroup(world, id);
        reliable(welcome(id, cleanName, rgb), address, port);
        reliable(envMessage(), address, port);
        broadcastNow();
    }

    private void syncEnv(String seed, String time) {
        try { world.syncEnvironment(Long.parseLong(seed), Double.parseDouble(time)); } catch (NumberFormatException ignored) { }
    }

    private String envMessage() { return "ENV|" + world.systemSeed() + "|" + Calc.round(world.systemTime()); }
    private String welcome(String id, String name, int rgb) { return "WELCOME|" + id + "|" + Config.clean(name) + "|" + rgb + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()); }

    private void removeTimedOutPeers(long now) { for (String ep : new ArrayList<>(peers.keySet())) if (now - peers.get(ep).lastSeen() > TIMEOUT_MS) removePeer(ep); }
    private void touch(String ep) { ServerPeer p = peers.get(ep); if (p != null) peers.put(ep, new ServerPeer(p.playerId(), p.address(), p.port(), System.currentTimeMillis())); }
    private void removePeer(String ep) { ServerPeer p = peers.remove(ep); if (p != null) removePlayer(p.playerId()); }
    private int colorFor(int i) { int[] c = {0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C}; return c[Math.floorMod(i, c.length)]; }
    private void broadcast(String message) { for (ServerPeer p : peers.values()) send(message, p.address(), p.port()); }
    private void sendToServer(String message) { send(message, config.serverAddress.getAddress(), config.serverAddress.getPort()); }
    private void reliableToServer(String payload) { reliable(payload, config.serverAddress.getAddress(), config.serverAddress.getPort()); }
    private void reliable(String payload, InetAddress address, int port) { String id = reliablePrefix + '-' + nextReliable++; PendingReliable p = new PendingReliable(id, payload, address, port, 0, 0); pending.put(id, p); sendReliable(p); }
    private void sendReliable(PendingReliable p) { send("REL|" + p.id() + "|" + p.payload(), p.address(), p.port()); pending.put(p.id(), new PendingReliable(p.id(), p.payload(), p.address(), p.port(), System.currentTimeMillis(), p.attempts() + 1)); }
    private void resend(long now) { for (PendingReliable p : new ArrayList<>(pending.values())) { if (p.attempts() > 40) pending.remove(p.id()); else if (now - p.lastSent() >= RELIABLE_MS) sendReliable(p); } }
    private void send(String message, InetAddress address, int port) { try { byte[] bytes = message.getBytes(StandardCharsets.UTF_8); socket.send(new DatagramPacket(bytes, bytes.length, address, port)); } catch (IOException ex) { if (running) System.err.println("Send failed: " + ex.getMessage()); } }
    private void listenLoop() { byte[] buf = new byte[65535]; while (running) { DatagramPacket p = new DatagramPacket(buf, buf.length); try { socket.receive(p); inbox.add(new NetPacket(new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8), p.getAddress(), p.getPort())); } catch (SocketTimeoutException ignored) { } catch (Exception ex) { if (running) System.err.println("UDP failed: " + ex.getMessage()); } } }
    private String endpoint(InetAddress address, int port) { return address.getHostAddress() + ':' + port; }
}
