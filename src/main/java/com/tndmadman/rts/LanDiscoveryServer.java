package com.tndmadman.rts;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

final class LanDiscoveryServer implements Closeable {
    private static final long MIN_RESPONSE_INTERVAL_MILLIS = 250L;

    private final String serverName;
    private final int gamePort;
    private final Map<String, Long> lastResponseByAddress = new HashMap<>();
    private volatile DatagramSocket socket;
    private volatile Thread thread;

    LanDiscoveryServer(String serverName, int gamePort) {
        this.serverName = serverName == null || serverName.isBlank() ? "StarChem Server" : serverName.trim();
        this.gamePort = gamePort;
    }

    synchronized boolean start() {
        if (socket != null && !socket.isClosed()) return true;
        try {
            DatagramSocket created = new DatagramSocket(null);
            created.setReuseAddress(true);
            created.bind(new InetSocketAddress(LanDiscoveryProtocol.DISCOVERY_PORT));
            socket = created;
            thread = new Thread(this::runLoop, "starchem-lan-discovery-server");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (IOException ex) {
            close();
            System.err.println("LAN discovery disabled: " + ex.getClass().getSimpleName());
            return false;
        }
    }

    private void runLoop() {
        byte[] buffer = new byte[LanDiscoveryProtocol.MAX_PACKET_BYTES];
        while (true) {
            DatagramSocket active = socket;
            if (active == null || active.isClosed()) return;
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                active.receive(request);
                if (!LanDiscoveryProtocol.isQuery(request.getData(), request.getLength())) continue;
                if (!allowResponse(request.getAddress().getHostAddress())) continue;
                byte[] response = LanDiscoveryProtocol.responseBytes(serverName, gamePort);
                active.send(new DatagramPacket(response, response.length, request.getAddress(), request.getPort()));
            } catch (SocketException ex) {
                return;
            } catch (IOException | RuntimeException ignored) { }
        }
    }

    private boolean allowResponse(String address) {
        long now = System.currentTimeMillis();
        synchronized (lastResponseByAddress) {
            Long previous = lastResponseByAddress.put(address, now);
            if (lastResponseByAddress.size() > 256) {
                long cutoff = now - 60_000L;
                lastResponseByAddress.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            }
            return previous == null || now - previous >= MIN_RESPONSE_INTERVAL_MILLIS;
        }
    }

    @Override public synchronized void close() {
        DatagramSocket active = socket;
        socket = null;
        if (active != null) active.close();
        Thread activeThread = thread;
        thread = null;
        if (activeThread != null) activeThread.interrupt();
        synchronized (lastResponseByAddress) { lastResponseByAddress.clear(); }
    }
}
