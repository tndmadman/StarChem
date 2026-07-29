package com.tndmadman.rts;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class LanDiscoveryClient implements Closeable {
    private static final int RECEIVE_TIMEOUT_MILLIS = 250;
    private static final long ENTRY_TTL_MILLIS = 8_000L;

    private final CopyOnWriteArrayList<Consumer<List<LanDiscoveryProtocol.DiscoveredServer>>> listeners =
            new CopyOnWriteArrayList<>();
    private final Map<String, LanDiscoveryProtocol.DiscoveredServer> servers = new LinkedHashMap<>();
    private volatile DatagramSocket socket;
    private volatile Thread receiver;

    void addListener(Consumer<List<LanDiscoveryProtocol.DiscoveredServer>> listener) {
        if (listener != null) listeners.add(listener);
    }

    synchronized void refresh() {
        ensureStarted();
        DatagramSocket active = socket;
        if (active == null || active.isClosed()) return;
        byte[] query = LanDiscoveryProtocol.queryBytes();
        for (InetAddress broadcast : broadcastAddresses()) {
            try {
                active.send(new DatagramPacket(query, query.length,
                        new InetSocketAddress(broadcast, LanDiscoveryProtocol.DISCOVERY_PORT)));
            } catch (IOException ignored) { }
        }
        expireAndPublish();
    }

    private synchronized void ensureStarted() {
        if (socket != null && !socket.isClosed()) return;
        try {
            DatagramSocket created = new DatagramSocket();
            created.setBroadcast(true);
            created.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
            socket = created;
            receiver = new Thread(this::receiveLoop, "starchem-lan-discovery-client");
            receiver.setDaemon(true);
            receiver.start();
        } catch (IOException ex) {
            close();
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[LanDiscoveryProtocol.MAX_PACKET_BYTES];
        while (true) {
            DatagramSocket active = socket;
            if (active == null || active.isClosed()) return;
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                active.receive(packet);
                LanDiscoveryProtocol.DiscoveredServer server = LanDiscoveryProtocol.parseResponse(
                        packet.getData(), packet.getLength(), packet.getAddress());
                if (server != null) {
                    synchronized (servers) {
                        servers.put(server.endpoint(), server);
                    }
                    publish();
                }
            } catch (java.net.SocketTimeoutException ignored) {
                expireAndPublish();
            } catch (IOException ex) {
                return;
            }
        }
    }

    private void expireAndPublish() {
        long cutoff = System.currentTimeMillis() - ENTRY_TTL_MILLIS;
        boolean changed;
        synchronized (servers) {
            changed = servers.values().removeIf(server -> server.seenAtMillis() < cutoff);
        }
        if (changed) publish();
    }

    private void publish() {
        List<LanDiscoveryProtocol.DiscoveredServer> snapshot;
        synchronized (servers) {
            snapshot = new ArrayList<>(servers.values());
        }
        snapshot.sort((a, b) -> {
            int compatible = Boolean.compare(b.compatible(), a.compatible());
            return compatible != 0 ? compatible : a.name().compareToIgnoreCase(b.name());
        });
        List<LanDiscoveryProtocol.DiscoveredServer> immutable = List.copyOf(snapshot);
        for (Consumer<List<LanDiscoveryProtocol.DiscoveredServer>> listener : listeners) listener.accept(immutable);
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface network : Collections.list(interfaces)) {
                    if (!network.isUp() || network.isLoopback()) continue;
                    network.getInterfaceAddresses().forEach(address -> {
                        InetAddress broadcast = address.getBroadcast();
                        if (broadcast != null) addresses.add(broadcast);
                    });
                }
            }
        } catch (SocketException ignored) { }
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) { }
        return List.copyOf(addresses);
    }

    @Override public synchronized void close() {
        DatagramSocket active = socket;
        socket = null;
        if (active != null) active.close();
        Thread activeReceiver = receiver;
        receiver = null;
        if (activeReceiver != null) activeReceiver.interrupt();
        synchronized (servers) { servers.clear(); }
    }
}
