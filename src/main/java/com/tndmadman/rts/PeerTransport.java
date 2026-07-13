package com.tndmadman.rts;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Framed TCP transport used by both the authoritative server and clients.
 *
 * The game thread never performs socket I/O. Reader threads publish complete
 * frames to the inbox and one writer thread per connection drains bounded
 * outbound queues. Regular snapshots are replaceable so a slow client receives
 * the newest state instead of an ever-growing backlog of obsolete snapshots.
 */
final class PeerTransport {
    static final String DISCONNECT_EVENT = "\u0000TCP_DISCONNECT";
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_INBOUND_FRAMES = 256;
    private static final int MAX_CONTROL_FRAMES = 256;
    private static final int MAX_OUTBOUND_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int SOCKET_IDLE_TIMEOUT_MS = 15_000;
    private static final long RECONNECT_DELAY_MS = 250;
    private static final int MAX_COMPATIBLE_ENDPOINTS = 512;

    private final boolean serverMode;
    private final ServerSocket serverSocket;
    private final InetSocketAddress expectedRemote;
    private final PerfStats perfStats;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inboxSize = new AtomicInteger();
    private final Map<String, TcpConnection> connections = new ConcurrentHashMap<>();
    private final Set<String> compatibleEndpoints = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean clientDisconnectPending = new AtomicBoolean();
    private volatile TcpConnection clientConnection;
    private volatile boolean compatibilityAccepted;
    private volatile boolean running = true;

    private PeerTransport(boolean serverMode, ServerSocket serverSocket, InetSocketAddress expectedRemote,
                          PerfStats perfStats) {
        this.serverMode = serverMode;
        this.serverSocket = serverSocket;
        this.expectedRemote = expectedRemote;
        this.perfStats = perfStats == null ? new PerfStats() : perfStats;
        this.compatibilityAccepted = serverMode;
    }

    static PeerTransport server(int port, PerfStats perfStats) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return new PeerTransport(true, socket, null, perfStats);
    }

    static PeerTransport client(InetSocketAddress remote, PerfStats perfStats) throws IOException {
        if (remote == null || remote.getAddress() == null) {
            throw new IOException("TCP server endpoint must be resolved before connecting.");
        }
        InetSocketAddress pinned = new InetSocketAddress(remote.getAddress(), remote.getPort());
        return new PeerTransport(false, null, pinned, perfStats);
    }

    void start() {
        Thread thread = new Thread(serverMode ? this::acceptLoop : this::connectLoop,
                serverMode ? "starchem-tcp-accept" : "starchem-tcp-connect");
        thread.setDaemon(true);
        thread.start();
    }

    NetPacket poll() {
        NetPacket packet = inbox.poll();
        if (packet != null) inboxSize.updateAndGet(value -> Math.max(0, value - 1));
        return packet;
    }

    int queuedCount() {
        int pending = 0;
        if (serverMode) {
            for (TcpConnection connection : connections.values()) pending += connection.pendingFrames();
        } else {
            TcpConnection connection = clientConnection;
            if (connection != null) pending = connection.pendingFrames();
        }
        return pending;
    }

    int localPort() {
        if (serverMode) return serverSocket == null ? 0 : serverSocket.getLocalPort();
        TcpConnection connection = clientConnection;
        return connection == null ? 0 : connection.localPort();
    }

    boolean connected() {
        if (serverMode) return running && serverSocket != null && !serverSocket.isClosed();
        TcpConnection connection = clientConnection;
        return connection != null && connection.open();
    }

    boolean consumeClientDisconnect() { return clientDisconnectPending.getAndSet(false); }
    boolean isDisconnectEvent(NetPacket packet) { return packet != null && DISCONNECT_EVENT.equals(packet.message()); }

    PerfSnapshot perfSnapshot() {
        int queuedFrames = 0;
        long queuedBytes = 0;
        int activeConnections = 0;
        if (serverMode) {
            for (TcpConnection connection : connections.values()) {
                if (!connection.open()) continue;
                activeConnections++;
                queuedFrames += connection.pendingFrames();
                queuedBytes += connection.pendingBytes();
            }
        } else {
            TcpConnection connection = clientConnection;
            if (connection != null && connection.open()) {
                activeConnections = 1;
                queuedFrames = connection.pendingFrames();
                queuedBytes = connection.pendingBytes();
            }
        }
        perfStats.setTransportState(activeConnections, queuedFrames, queuedBytes);
        return perfStats.snapshot();
    }

    boolean accepts(NetPacket packet) {
        if (packet == null || packet.address() == null || packet.port() < 1 || packet.port() > 65535) return false;
        if (serverMode) return true;
        return expectedRemote != null && sameEndpoint(packet.address(), packet.port(),
                expectedRemote.getAddress(), expectedRemote.getPort());
    }

    void recordSnapshotRejected() { perfStats.recordSnapshotDecodeFailure(); }
    void recordMalformedPacket() { perfStats.recordMalformedPacket(); }

    void send(String message, InetAddress address, int port) {
        if (message == null || address == null || port < 1 || port > 65535) return;
        String prepared = prepareDirectMessage(message);
        if (prepared != null) enqueue(prepared, address, port);
    }

    /** Enqueues an ordered control message that must not be coalesced. */
    void sendOrdered(String payload, InetAddress address, int port) {
        if (payload == null || address == null || port < 1 || port > 65535) return;
        String prepared = prepareReliablePayload(payload, address, port);
        if (prepared != null) enqueue(prepared, address, port);
    }

    void clearOutbound() {
        if (serverMode) {
            for (TcpConnection connection : connections.values()) connection.clearOutbound();
        } else {
            TcpConnection connection = clientConnection;
            if (connection != null) connection.clearOutbound();
        }
    }

    /** Clears queued writes and closes the matching TCP connection. */
    void closeConnection(InetAddress address, int port) {
        if (address == null) return;
        TcpConnection connection = connections.get(endpointKey(address, port));
        if (connection != null) {
            connection.clearOutbound();
            connection.close();
        }
    }

    void reconnectClient() {
        if (serverMode) return;
        TcpConnection connection = clientConnection;
        if (connection != null) connection.close();
        clientConnection = null;
    }

    String processInbound(NetPacket packet) {
        if (packet == null || packet.message() == null) return null;
        return inspectCompatibility(packet.message(), packet);
    }

    boolean hasConnection(InetAddress address, int port) {
        TcpConnection connection = connections.get(endpointKey(address, port));
        return connection != null && connection.open();
    }

    void shutdown() {
        running = false;
        closeQuietly(serverSocket);
        if (serverMode) {
            for (TcpConnection connection : List.copyOf(connections.values())) connection.close();
            connections.clear();
        } else {
            TcpConnection connection = clientConnection;
            if (connection != null) connection.drainAndClose(300);
            clientConnection = null;
        }
        compatibleEndpoints.clear();
        inbox.clear();
        inboxSize.set(0);
    }

    private void enqueue(String message, InetAddress address, int port) {
        TcpConnection connection = targetConnection(address, port);
        if (connection == null || !connection.enqueue(message)) return;
    }

    private TcpConnection targetConnection(InetAddress address, int port) {
        if (serverMode) return connections.get(endpointKey(address, port));
        if (!sameEndpoint(address, port, expectedRemote.getAddress(), expectedRemote.getPort())) return null;
        return clientConnection;
    }

    private String prepareDirectMessage(String message) {
        if (serverMode) return message;
        if (message.startsWith("JOIN|") || message.startsWith("RESUME|")) {
            compatibilityAccepted = false;
            return MultiplayerCompatibility.versionClientHandshake(message);
        }
        return message;
    }

    private String prepareReliablePayload(String payload, InetAddress address, int port) {
        if (!serverMode || !payload.startsWith("WELCOME|")) return payload;
        String prepared = MultiplayerCompatibility.versionServerWelcome(payload);
        markCompatible(address, port);
        return prepared;
    }

    private String inspectCompatibility(String message, NetPacket packet) {
        return serverMode ? inspectServerInbound(message, packet) : inspectClientInbound(message);
    }

    private String inspectServerInbound(String message, NetPacket packet) {
        String endpoint = endpointKey(packet.address(), packet.port());
        if (isHandshakeAttempt(message)) compatibleEndpoints.remove(endpoint);

        MultiplayerCompatibility.WireResult result = MultiplayerCompatibility.inspectClientHandshake(message);
        if (result.action() == MultiplayerCompatibility.WireAction.REJECT) {
            sendOrdered(result.detail(), packet.address(), packet.port());
            return null;
        }
        if (result.action() == MultiplayerCompatibility.WireAction.ACCEPT) return result.message();
        if (!compatibleEndpoints.contains(endpoint)) return null;
        return result.message();
    }

    private String inspectClientInbound(String message) {
        MultiplayerCompatibility.WireResult result = MultiplayerCompatibility.inspectServerWelcome(message);
        if (result.action() == MultiplayerCompatibility.WireAction.REJECT) {
            compatibilityAccepted = false;
            return "JOIN_DENIED|" + packetPart(result.detail());
        }
        if (result.action() == MultiplayerCompatibility.WireAction.ACCEPT) {
            compatibilityAccepted = true;
            return result.message();
        }
        if (!compatibilityAccepted && !preHandshakeControl(message)) return null;
        return result.message();
    }

    private boolean preHandshakeControl(String message) {
        return message != null && (message.startsWith("JOIN_DENIED|") || message.startsWith("SESSION_BUSY|")
                || message.startsWith("SESSION_DENIED|") || message.startsWith("COMPAT_DENIED|"));
    }

    private void markCompatible(InetAddress address, int port) {
        if (address == null) return;
        compatibleEndpoints.add(endpointKey(address, port));
        while (compatibleEndpoints.size() > MAX_COMPATIBLE_ENDPOINTS) {
            Iterator<String> iterator = compatibleEndpoints.iterator();
            if (!iterator.hasNext()) break;
            compatibleEndpoints.remove(iterator.next());
        }
    }

    private boolean isHandshakeAttempt(String message) {
        return message != null && (message.startsWith("JOIN|") || message.startsWith("JOIN_V1|")
                || message.startsWith("RESUME|") || message.startsWith("RESUME_V1|"));
    }

    private String packetPart(String value) {
        return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (connections.size() >= MAX_CONNECTIONS) {
                    closeQuietly(socket);
                    perfStats.recordConnectionRejected();
                    continue;
                }
                configure(socket);
                TcpConnection connection = new TcpConnection(socket);
                String endpoint = connection.endpoint();
                TcpConnection old = connections.put(endpoint, connection);
                if (old != null) old.close();
                perfStats.recordConnectionOpened();
                connection.start();
            } catch (SocketException ex) {
                if (running) System.err.println("TCP accept failed: " + ex.getMessage());
            } catch (IOException ex) {
                if (running) System.err.println("TCP accept failed: " + ex.getMessage());
            }
        }
    }

    private void connectLoop() {
        while (running) {
            TcpConnection existing = clientConnection;
            if (existing != null && existing.open()) {
                sleep(100);
                continue;
            }
            try {
                Socket socket = new Socket();
                socket.connect(expectedRemote, CONNECT_TIMEOUT_MS);
                configure(socket);
                TcpConnection connection = new TcpConnection(socket);
                if (!running) {
                    connection.close();
                    break;
                }
                clientConnection = connection;
                perfStats.recordConnectionOpened();
                connection.start();
            } catch (IOException ex) {
                sleep(RECONNECT_DELAY_MS);
            }
        }
    }

    private void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS);
        socket.setReceiveBufferSize(256 * 1024);
        socket.setSendBufferSize(256 * 1024);
    }

    private void receive(TcpConnection connection, TcpFrameCodec.DecodedFrame frame) {
        if (frame == null || frame.message() == null) return;
        int next = inboxSize.incrementAndGet();
        if (next > MAX_INBOUND_FRAMES) {
            inboxSize.decrementAndGet();
            perfStats.recordInboundOverflow();
            connection.close();
            return;
        }
        perfStats.recordPacketReceived(frame.wireBytes());
        if (isSnapshot(frame.message())) perfStats.recordSnapshotReceived(frame.wireBytes());
        inbox.add(new NetPacket(frame.message(), connection.address(), connection.remotePort()));
    }

    private void connectionClosed(TcpConnection connection) {
        perfStats.recordConnectionClosed();
        String endpoint = connection.endpoint();
        compatibleEndpoints.remove(endpoint);
        if (serverMode) {
            if (connections.remove(endpoint, connection) && running) {
                inboxSize.incrementAndGet();
                inbox.add(new NetPacket(DISCONNECT_EVENT, connection.address(), connection.remotePort()));
            }
        } else if (clientConnection == connection) {
            clientConnection = null;
            if (running) clientDisconnectPending.set(true);
        }
    }

    private String endpointKey(InetAddress address, int port) {
        return (address == null ? "unknown" : address.getHostAddress()) + ':' + port;
    }

    private boolean sameEndpoint(InetAddress leftAddress, int leftPort, InetAddress rightAddress, int rightPort) {
        return leftPort == rightPort && Objects.equals(leftAddress, rightAddress);
    }

    private boolean isSnapshot(String message) {
        return message != null && (message.startsWith("SNAPSHOT|") || SyncFrame.matches(message));
    }

    private String replaceableKey(String message) {
        if (message == null) return null;
        if (message.startsWith("SNAPSHOT|")) return "SNAPSHOT";
        if (message.startsWith("LEADER|")) return "LEADER";
        if (message.startsWith("GALAXY|")) return "GALAXY";
        return null;
    }

    private int utf8Length(String message) {
        return message == null ? 0 : message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private final class TcpConnection {
        private final Socket socket;
        private final InetAddress address;
        private final int remotePort;
        private final String endpoint;
        private final Deque<OutboundFrame> outboundFrames = new ArrayDeque<>();
        private final Map<String, OutboundFrame> replaceableFrames = new LinkedHashMap<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private int controlFrameCount;
        private long queuedBytes;

        TcpConnection(Socket socket) {
            this.socket = Objects.requireNonNull(socket, "socket");
            this.address = socket.getInetAddress();
            this.remotePort = socket.getPort();
            this.endpoint = endpointKey(address, remotePort);
        }

        void start() {
            Thread reader = new Thread(this::readerLoop, "starchem-tcp-read-" + endpoint);
            Thread writer = new Thread(this::writerLoop, "starchem-tcp-write-" + endpoint);
            reader.setDaemon(true);
            writer.setDaemon(true);
            reader.start();
            writer.start();
        }

        boolean enqueue(String message) {
            byte[] bytes;
            try {
                bytes = TcpFrameCodec.encode(message);
            } catch (IOException ex) {
                perfStats.recordMalformedPacket();
                return false;
            }
            String replaceableKey = replaceableKey(message);
            OutboundFrame frame = new OutboundFrame(bytes, isSnapshot(message), replaceableKey);
            boolean closeSlow = false;
            synchronized (this) {
                if (!open.get()) return false;
                OutboundFrame previous = replaceableKey == null ? null : replaceableFrames.get(replaceableKey);
                long projectedBytes = queuedBytes - (previous == null ? 0 : previous.bytes.length) + bytes.length;
                if (projectedBytes > MAX_OUTBOUND_BYTES
                        || replaceableKey == null && controlFrameCount >= MAX_CONTROL_FRAMES) {
                    closeSlow = true;
                } else {
                    if (previous != null) {
                        removeIdentity(previous);
                        queuedBytes -= previous.bytes.length;
                        if ("SNAPSHOT".equals(replaceableKey)) perfStats.recordSnapshotCoalesced();
                    }
                    outboundFrames.addLast(frame);
                    queuedBytes += bytes.length;
                    if (replaceableKey == null) controlFrameCount++;
                    else replaceableFrames.put(replaceableKey, frame);
                    notifyAll();
                }
            }
            if (closeSlow) {
                perfStats.recordSlowConnectionClosed();
                close();
                return false;
            }
            return true;
        }

        private void removeIdentity(OutboundFrame wanted) {
            Iterator<OutboundFrame> iterator = outboundFrames.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == wanted) {
                    iterator.remove();
                    return;
                }
            }
        }

        synchronized void clearOutbound() {
            outboundFrames.clear();
            replaceableFrames.clear();
            controlFrameCount = 0;
            queuedBytes = 0;
            notifyAll();
        }

        synchronized int pendingFrames() { return outboundFrames.size(); }

        synchronized long pendingBytes() { return queuedBytes; }
        boolean open() { return open.get(); }
        InetAddress address() { return address; }
        int remotePort() { return remotePort; }
        int localPort() { return socket.getLocalPort(); }
        String endpoint() { return endpoint; }

        void drainAndClose(long timeoutMs) {
            long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
            while (open.get() && pendingFrames() > 0 && System.currentTimeMillis() < deadline) sleep(5);
            close();
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            closeQuietly(socket);
            synchronized (this) { notifyAll(); }
            connectionClosed(this);
        }

        private void readerLoop() {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                while (open.get()) {
                    TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(input);
                    if (frame == null) break;
                    receive(this, frame);
                }
            } catch (SocketTimeoutException ignored) {
                // Heartbeats and snapshots keep healthy connections active.
            } catch (IOException ex) {
                if (open.get()) perfStats.recordMalformedPacket();
            } finally {
                close();
            }
        }

        private void writerLoop() {
            try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
                while (open.get()) {
                    OutboundFrame frame = takeNext();
                    if (frame == null) continue;
                    output.write(frame.bytes);
                    output.flush();
                    perfStats.recordPacketSent(frame.bytes.length);
                    if (frame.snapshot) perfStats.recordSnapshotSent(frame.bytes.length);
                }
            } catch (IOException ex) {
                // Closing the connection is the recovery signal; do not block the game thread.
            } finally {
                close();
            }
        }

        private OutboundFrame takeNext() {
            synchronized (this) {
                while (open.get() && outboundFrames.isEmpty()) {
                    try { wait(500); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
                }
                if (!open.get()) return null;
                OutboundFrame frame = outboundFrames.pollFirst();
                if (frame != null) {
                    queuedBytes = Math.max(0, queuedBytes - frame.bytes.length);
                    if (frame.replaceableKey == null) controlFrameCount = Math.max(0, controlFrameCount - 1);
                    else replaceableFrames.remove(frame.replaceableKey, frame);
                }
                notifyAll();
                return frame;
            }
        }
    }

    private record OutboundFrame(byte[] bytes, boolean snapshot, String replaceableKey) { }
}
