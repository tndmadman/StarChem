package com.tndmadman.rts;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

final class PeerTransport {
    private static final long RELIABLE_MS = 450;
    private static final int MAX_RELIABLE_ID_LENGTH = 128;
    private final DatagramSocket socket;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, PendingReliable> pending = new LinkedHashMap<>();
    private final Set<String> delivered = new LinkedHashSet<>();
    private final String prefix = Integer.toHexString(new SecureRandom().nextInt()).replace('-', 'N');
    private final PacketChunks packetChunks;
    private final PerfStats perfStats;
    private final InetSocketAddress expectedRemote;
    private boolean running = true;
    private long nextReliable = 1;

    PeerTransport(DatagramSocket socket) throws SocketException { this(socket, new PerfStats(), null); }
    PeerTransport(DatagramSocket socket, PerfStats perfStats) throws SocketException { this(socket, perfStats, null); }

    PeerTransport(DatagramSocket socket, PerfStats perfStats, InetSocketAddress expectedRemote) throws SocketException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.perfStats = perfStats == null ? new PerfStats() : perfStats;
        if (expectedRemote != null && expectedRemote.getAddress() == null) {
            throw new IllegalArgumentException("Expected remote endpoint must be resolved.");
        }
        this.expectedRemote = expectedRemote == null
                ? null
                : new InetSocketAddress(expectedRemote.getAddress(), expectedRemote.getPort());
        this.socket.setSoTimeout(250);
        this.packetChunks = new PacketChunks(prefix, this.perfStats);
    }

    void start() {
        Thread thread = new Thread(this::listenLoop, "starchem-udp");
        thread.setDaemon(true);
        thread.start();
    }

    NetPacket poll() { return inbox.poll(); }
    int pendingCount() { return pending.size(); }
    int localPort() { return socket.getLocalPort(); }
    PerfSnapshot perfSnapshot() { perfStats.setPendingReliable(pending.size()); return perfStats.snapshot(); }

    boolean accepts(NetPacket packet) {
        return packet != null && acceptsEndpoint(packet.address(), packet.port(), true);
    }

    void recordSnapshotRejected() { perfStats.recordSnapshotDecodeFailure(); }
    void recordMalformedPacket() { perfStats.recordMalformedPacket(); }

    void send(String message, InetAddress address, int port) {
        if (message == null || address == null || port < 1 || port > 65535) return;
        try {
            if (isSnapshot(message)) perfStats.recordSnapshotSent(utf8Length(message));
            packetChunks.send(socket, message, address, port);
        } catch (IOException ex) {
            if (running) System.err.println("Send failed: " + ex.getMessage());
        }
    }

    void reliable(String payload, InetAddress address, int port) {
        String id = prefix + '-' + nextReliable++;
        PendingReliable pendingReliable = new PendingReliable(id, payload, address, port, 0, 0);
        pending.put(id, pendingReliable);
        sendReliable(pendingReliable);
    }

    void resend(long now) {
        for (PendingReliable p : new ArrayList<>(pending.values())) {
            if (p.attempts() > 40) pending.remove(p.id());
            else if (now - p.lastSent() >= RELIABLE_MS) {
                perfStats.recordReliableResend();
                sendReliable(p);
            }
        }
    }

    void clearPending() { pending.clear(); }

    void clearPendingForEndpoint(InetAddress address, int port) {
        if (address == null) return;
        pending.values().removeIf(p -> sameEndpoint(address, port, p.address(), p.port()));
    }

    String unwrapReliable(NetPacket packet) {
        if (packet == null || packet.message() == null) return null;
        String message = packet.message();
        if (message.startsWith("ACK|")) {
            String id = message.substring(4);
            if (id.isBlank() || id.length() > MAX_RELIABLE_ID_LENGTH) {
                perfStats.recordMalformedPacket();
                return null;
            }
            PendingReliable acknowledged = pending.get(id);
            if (acknowledged == null) return null;
            if (!sameEndpoint(packet.address(), packet.port(), acknowledged.address(), acknowledged.port())) {
                perfStats.recordRejectedReliableAck();
                return null;
            }
            pending.remove(id);
            if (acknowledged.lastSent() > 0) {
                long elapsedMs = Math.max(0, System.currentTimeMillis() - acknowledged.lastSent());
                perfStats.recordRtt(elapsedMs * 1_000_000L);
            }
            return null;
        }
        if (!message.startsWith("REL|")) return message;
        String[] parts = message.split("\\|", 3);
        if (parts.length < 3 || parts[1].isBlank() || parts[1].length() > MAX_RELIABLE_ID_LENGTH) {
            perfStats.recordMalformedPacket();
            return null;
        }
        send("ACK|" + parts[1], packet.address(), packet.port());
        String key = packet.address().getHostAddress() + ':' + packet.port() + '|' + parts[1];
        if (!delivered.add(key)) return null;
        while (delivered.size() > 512) delivered.remove(delivered.iterator().next());
        return parts[2];
    }

    void shutdown() { running = false; socket.close(); }

    private void sendReliable(PendingReliable p) {
        send("REL|" + p.id() + "|" + p.payload(), p.address(), p.port());
        pending.put(p.id(), new PendingReliable(p.id(), p.payload(), p.address(), p.port(), System.currentTimeMillis(), p.attempts() + 1));
    }

    private void listenLoop() {
        byte[] buf = new byte[PacketChunks.MAX_DATAGRAM_BYTES + 1];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
                if (!acceptsEndpoint(p.getAddress(), p.getPort(), true)) continue;
                if (p.getLength() > PacketChunks.MAX_DATAGRAM_BYTES) {
                    perfStats.recordMalformedPacket();
                    continue;
                }
                perfStats.recordPacketReceived(p.getLength());
                String raw = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                String message = packetChunks.receive(raw, p.getAddress(), p.getPort());
                if (message != null) {
                    if (isSnapshot(message)) perfStats.recordSnapshotReceived(utf8Length(message));
                    inbox.add(new NetPacket(message, p.getAddress(), p.getPort()));
                }
            } catch (SocketTimeoutException ignored) { }
            catch (Exception ex) { if (running) System.err.println("UDP failed: " + ex.getMessage()); }
        }
    }

    private boolean acceptsEndpoint(InetAddress address, int port, boolean recordRejection) {
        boolean accepted = expectedRemote == null
                || sameEndpoint(address, port, expectedRemote.getAddress(), expectedRemote.getPort());
        if (!accepted && recordRejection) perfStats.recordRejectedEndpoint();
        return accepted;
    }

    private boolean sameEndpoint(InetAddress leftAddress, int leftPort, InetAddress rightAddress, int rightPort) {
        return leftPort == rightPort && Objects.equals(leftAddress, rightAddress);
    }

    private boolean isSnapshot(String message) {
        return message != null && (message.startsWith("SNAPSHOT|") || SyncFrame.matches(message));
    }

    private int utf8Length(String message) { return message.getBytes(StandardCharsets.UTF_8).length; }
}
