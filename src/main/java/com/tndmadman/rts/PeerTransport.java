package com.tndmadman.rts;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

final class PeerTransport {
    private static final long RELIABLE_MS = 450;
    private final DatagramSocket socket;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, PendingReliable> pending = new LinkedHashMap<>();
    private final Set<String> delivered = new LinkedHashSet<>();
    private final String prefix = Integer.toHexString(new SecureRandom().nextInt()).replace('-', 'N');
    private boolean running = true;
    private long nextReliable = 1;

    PeerTransport(DatagramSocket socket) throws SocketException {
        this.socket = socket;
        this.socket.setSoTimeout(250);
    }

    void start() {
        Thread thread = new Thread(this::listenLoop, "starchem-udp");
        thread.setDaemon(true);
        thread.start();
    }

    NetPacket poll() { return inbox.poll(); }
    int pendingCount() { return pending.size(); }
    int localPort() { return socket.getLocalPort(); }

    void send(String message, InetAddress address, int port) {
        try { PacketChunks.send(socket, message, address, port); }
        catch (IOException ex) { if (running) System.err.println("Send failed: " + ex.getMessage()); }
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
            else if (now - p.lastSent() >= RELIABLE_MS) sendReliable(p);
        }
    }

    String unwrapReliable(NetPacket packet) {
        String message = packet.message();
        if (message.startsWith("ACK|")) { pending.remove(message.substring(4)); return null; }
        if (!message.startsWith("REL|")) return message;
        String[] parts = message.split("\\|", 3);
        if (parts.length < 3) return null;
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
        byte[] buf = new byte[65535];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
                String raw = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                String message = PacketChunks.receive(raw, p.getAddress(), p.getPort());
                if (message != null) inbox.add(new NetPacket(message, p.getAddress(), p.getPort()));
            } catch (SocketTimeoutException ignored) { }
            catch (Exception ex) { if (running) System.err.println("UDP failed: " + ex.getMessage()); }
        }
    }
}
