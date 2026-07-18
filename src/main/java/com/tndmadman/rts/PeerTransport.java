package com.tndmadman.rts;

import java.io.*;
import java.net.*;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Framed TCP transport with per-connection identity, bounded queues, and asynchronous I/O. */
final class PeerTransport {
    static final String DISCONNECT_EVENT = "\u0000TCP_DISCONNECT";
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_INBOUND_FRAMES = 256;
    private static final int MAX_CONTROL_FRAMES = 256;
    private static final int MAX_OUTBOUND_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int SOCKET_IDLE_TIMEOUT_MS = 15_000;
    private static final long RECONNECT_DELAY_MS = 250;

    private final boolean serverMode;
    private final ServerSocket serverSocket;
    private final InetSocketAddress expectedRemote;
    private final Config config;
    private final SocketFactory clientSocketFactory;
    private final PerfStats perfStats;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inboxSize = new AtomicInteger();
    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private final Map<ConnectionId, TcpConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, ConnectionId> endpointIndex = new ConcurrentHashMap<>();
    private final Set<ConnectionId> compatibleConnections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean clientDisconnectPending = new AtomicBoolean();
    private final AtomicReference<String> clientConnectFailure = new AtomicReference<>("");
    private final AtomicReference<TlsIdentity.FingerprintChange> pendingServerFingerprintChange = new AtomicReference<>();
    private volatile TcpConnection clientConnection;
    private volatile boolean compatibilityAccepted;
    private volatile boolean running = true;

    private PeerTransport(boolean serverMode, ServerSocket serverSocket, InetSocketAddress expectedRemote,
                          Config config, SocketFactory clientSocketFactory, PerfStats perfStats) {
        this.serverMode = serverMode;
        this.serverSocket = serverSocket;
        this.expectedRemote = expectedRemote;
        this.config = config;
        this.clientSocketFactory = clientSocketFactory;
        this.perfStats = perfStats == null ? new PerfStats() : perfStats;
        this.compatibilityAccepted = serverMode;
    }

    static PeerTransport server(int port, PerfStats perfStats) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return new PeerTransport(true, socket, null, null, null, perfStats);
    }

    static PeerTransport server(Config config, PerfStats perfStats) throws IOException {
        if (config == null) throw new IOException("Server config is required for encrypted transport.");
        ServerSocketFactory factory = TlsIdentity.serverSocketFactory(config);
        ServerSocket socket = factory.createServerSocket();
        socket.setReuseAddress(true);
        configureServerSocket(socket);
        socket.bind(new InetSocketAddress(config.port));
        return new PeerTransport(true, socket, null, config, null, perfStats);
    }

    static PeerTransport client(InetSocketAddress remote, PerfStats perfStats) throws IOException {
        if (remote == null || remote.getAddress() == null) {
            throw new IOException("TCP server endpoint must be resolved before connecting.");
        }
        return new PeerTransport(false, null,
                new InetSocketAddress(remote.getAddress(), remote.getPort()), null, null, perfStats);
    }

    static PeerTransport client(Config config, PerfStats perfStats) throws IOException {
        if (config == null || config.serverAddress == null || config.serverAddress.getAddress() == null) {
            throw new IOException("TCP server endpoint must be resolved before connecting.");
        }
        return new PeerTransport(false, null,
                new InetSocketAddress(config.serverAddress.getAddress(), config.serverAddress.getPort()),
                config, TlsIdentity.clientSocketFactory(), perfStats);
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
    String consumeClientConnectFailure() { return clientConnectFailure.getAndSet(""); }
    boolean serverCertificateTrustRequired() { return pendingServerFingerprintChange.get() != null; }
    TlsIdentity.FingerprintChange pendingServerFingerprintChange() { return pendingServerFingerprintChange.get(); }
    boolean trustPendingServerCertificate() {
        if (serverMode) return false;
        TlsIdentity.FingerprintChange change = pendingServerFingerprintChange.get();
        if (change == null || !change.valid()) return false;
        if (!SessionTokenStore.replaceServerFingerprint(config, change.expected(), change.presented())) return false;
        if (!pendingServerFingerprintChange.compareAndSet(change, null)) return false;
        clientConnectFailure.set("");
        reconnectClient();
        return true;
    }
    boolean isDisconnectEvent(NetPacket packet) { return packet != null && DISCONNECT_EVENT.equals(packet.message()); }

    PerfSnapshot perfSnapshot() {
        int queuedFrames = 0;
        long queuedBytes = 0;
        int activeConnections = 0;
        Collection<TcpConnection> current = serverMode ? connections.values()
                : clientConnection == null ? List.of() : List.of(clientConnection);
        for (TcpConnection connection : current) {
            if (!connection.open()) continue;
            activeConnections++;
            queuedFrames += connection.pendingFrames();
            queuedBytes += connection.pendingBytes();
        }
        perfStats.setTransportState(activeConnections, queuedFrames, queuedBytes);
        return perfStats.snapshot();
    }

    boolean accepts(NetPacket packet) {
        if (packet == null || packet.address() == null || packet.port() < 1 || packet.port() > 65535) return false;
        if (serverMode) {
            TcpConnection connection = connections.get(packet.connectionId());
            return connection != null || isDisconnectEvent(packet);
        }
        return expectedRemote != null && sameEndpoint(packet.address(), packet.port(),
                expectedRemote.getAddress(), expectedRemote.getPort());
    }

    void recordSnapshotRejected() { perfStats.recordSnapshotDecodeFailure(); }
    void recordMalformedPacket() { perfStats.recordMalformedPacket(); }

    /** Client convenience path and compatibility-test path. */
    void send(String message, InetAddress address, int port) {
        enqueuePrepared(prepareClientMessage(message), targetConnection(address, port), inferDelivery(message));
    }

    /** Client ordered path and compatibility-test path. */
    void sendOrdered(String message, InetAddress address, int port) {
        TcpConnection connection = targetConnection(address, port);
        enqueuePrepared(prepareServerControl(message, connection), connection, DeliveryClass.ORDERED);
    }

    void send(String message, ConnectionId connectionId, DeliveryClass deliveryClass) {
        TcpConnection connection = connections.get(connectionId);
        if (!serverMode && clientConnection != null && clientConnection.id.equals(connectionId)) connection = clientConnection;
        String prepared = deliveryClass == DeliveryClass.ORDERED
                ? prepareServerControl(message, connection)
                : message;
        enqueuePrepared(prepared, connection, deliveryClass);
    }

    void sendOrdered(String message, ConnectionId connectionId) {
        send(message, connectionId, DeliveryClass.ORDERED);
    }

    void clearOutbound() {
        if (serverMode) {
            for (TcpConnection connection : connections.values()) connection.clearOutbound();
        } else {
            TcpConnection connection = clientConnection;
            if (connection != null) connection.clearOutbound();
        }
    }

    void closeConnection(ConnectionId connectionId) {
        TcpConnection connection = connections.get(connectionId);
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
        return serverMode ? inspectServerInbound(packet.message(), packet) : inspectClientInbound(packet.message());
    }

    boolean hasConnection(InetAddress address, int port) {
        ConnectionId id = endpointIndex.get(endpointKey(address, port));
        TcpConnection connection = id == null ? null : connections.get(id);
        return connection != null && connection.open();
    }

    ConnectionId connectionId(InetAddress address, int port) {
        ConnectionId id = endpointIndex.get(endpointKey(address, port));
        return id == null ? ConnectionId.NONE : id;
    }

    ConnectionId clientConnectionId() {
        TcpConnection connection = clientConnection;
        return connection == null ? ConnectionId.NONE : connection.id;
    }

    ConnectionDiagnostics diagnostics(ConnectionId id) {
        TcpConnection connection = connections.get(id);
        if (connection == null && clientConnection != null && clientConnection.id.equals(id)) connection = clientConnection;
        return connection == null
                ? new ConnectionDiagnostics(id, false, 0, 0, 0)
                : connection.diagnostics();
    }

    void forceDisconnectClientForTest() { reconnectClient(); }

    void shutdown() {
        running = false;
        closeQuietly(serverSocket);
        if (serverMode) {
            for (TcpConnection connection : List.copyOf(connections.values())) connection.close();
            connections.clear();
            endpointIndex.clear();
        } else {
            TcpConnection connection = clientConnection;
            if (connection != null) connection.drainAndClose(500);
            clientConnection = null;
        }
        compatibleConnections.clear();
        clientConnectFailure.set("");
        pendingServerFingerprintChange.set(null);
        inbox.clear();
        inboxSize.set(0);
    }

    private void enqueuePrepared(String message, TcpConnection connection, DeliveryClass deliveryClass) {
        if (message == null || connection == null || deliveryClass == null) return;
        connection.enqueue(message, deliveryClass);
    }

    private TcpConnection targetConnection(InetAddress address, int port) {
        if (address == null || port < 1 || port > 65535) return null;
        if (!serverMode) {
            return expectedRemote != null && sameEndpoint(address, port, expectedRemote.getAddress(), expectedRemote.getPort())
                    ? clientConnection : null;
        }
        ConnectionId id = endpointIndex.get(endpointKey(address, port));
        return id == null ? null : connections.get(id);
    }

    private String prepareClientMessage(String message) {
        if (message == null) return null;
        if (!serverMode && (message.startsWith("JOIN|") || message.startsWith("RESUME|"))) {
            compatibilityAccepted = false;
            return MultiplayerCompatibility.versionClientHandshake(message);
        }
        return message;
    }

    private String prepareServerControl(String message, TcpConnection connection) {
        if (message == null) return null;
        if (serverMode && message.startsWith("WELCOME|") && connection != null) {
            compatibleConnections.add(connection.id);
            return MultiplayerCompatibility.versionServerWelcome(message);
        }
        return !serverMode ? prepareClientMessage(message) : message;
    }

    private String inspectServerInbound(String message, NetPacket packet) {
        ConnectionId id = packet.connectionId();
        if (isHandshakeAttempt(message)) compatibleConnections.remove(id);
        MultiplayerCompatibility.WireResult result = MultiplayerCompatibility.inspectClientHandshake(message);
        if (result.action() == MultiplayerCompatibility.WireAction.REJECT) {
            sendOrdered(result.detail(), id);
            return null;
        }
        if (result.action() == MultiplayerCompatibility.WireAction.ACCEPT) return result.message();
        return compatibleConnections.contains(id) ? result.message() : null;
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
                || message.startsWith("SESSION_DENIED|") || message.startsWith("COMPAT_DENIED|")
                || message.startsWith("AUTH_REQUIRED|") || message.startsWith("AUTH_CHALLENGE|")
                || message.startsWith("SESSION_CHALLENGE|"));
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
                TcpConnection connection = new TcpConnection(new ConnectionId(nextConnectionId.getAndIncrement()), socket);
                connections.put(connection.id, connection);
                endpointIndex.put(connection.endpoint, connection.id);
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
  if (pendingServerFingerprintChange.get() != null) {
      sleep(100);
      continue;
  }
  TcpConnection existing = clientConnection;
  if (existing != null && existing.open()) {
      sleep(100);
      continue;
  }
  Socket socket = null;
  try {
      socket = clientSocketFactory == null ? new Socket() : clientSocketFactory.createSocket();
      socket.connect(expectedRemote, CONNECT_TIMEOUT_MS);
      configure(socket);
      if (clientSocketFactory != null) TlsIdentity.verifyPinnedServer(socket, config);
      TcpConnection connection = new TcpConnection(new ConnectionId(nextConnectionId.getAndIncrement()), socket);
      socket = null;
      if (!running) {
          connection.close();
          break;
      }
      compatibilityAccepted = false;
      clientConnectFailure.set("");
      pendingServerFingerprintChange.set(null);
      clientConnection = connection;
      perfStats.recordConnectionOpened();
      connection.start();
  } catch (TlsIdentity.FingerprintChangedException ex) {
      closeQuietly(socket);
      pendingServerFingerprintChange.set(ex.change());
      clientConnectFailure.set(ex.getMessage());
  } catch (IOException ex) {
      closeQuietly(socket);
      String detail = ex.getMessage();
      clientConnectFailure.set(detail == null || detail.isBlank()
              ? ex.getClass().getSimpleName()
              : detail.trim());
      sleep(RECONNECT_DELAY_MS);
  }
        }
    }

    private void configure(Socket socket) throws SocketException {
        if (socket instanceof SSLSocket ssl) configureTlsSocket(ssl);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS);
        socket.setReceiveBufferSize(256 * 1024);
        socket.setSendBufferSize(256 * 1024);
    }

    private static void configureServerSocket(ServerSocket socket) {
        if (socket instanceof SSLServerSocket ssl) {
            ssl.setEnabledProtocols(supportedTlsProtocols(ssl.getSupportedProtocols()));
            ssl.setUseClientMode(false);
            ssl.setNeedClientAuth(false);
        }
    }

    private static void configureTlsSocket(SSLSocket socket) {
        socket.setEnabledProtocols(supportedTlsProtocols(socket.getSupportedProtocols()));
    }

    private static String[] supportedTlsProtocols(String[] supported) {
        List<String> protocols = new ArrayList<>();
        for (String wanted : List.of("TLSv1.3", "TLSv1.2")) {
            for (String protocol : supported) if (wanted.equals(protocol)) protocols.add(wanted);
        }
        return protocols.isEmpty() ? supported : protocols.toArray(new String[0]);
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
        inbox.add(new NetPacket(frame.message(), connection.id, connection.address, connection.remotePort));
    }

    private void connectionClosed(TcpConnection connection) {
        perfStats.recordConnectionClosed();
        compatibleConnections.remove(connection.id);
        if (serverMode) {
            connections.remove(connection.id, connection);
            endpointIndex.remove(connection.endpoint, connection.id);
            if (running) publishDisconnect(connection);
        } else if (clientConnection == connection) {
            clientConnection = null;
            if (running) clientDisconnectPending.set(true);
        }
    }

    private void publishDisconnect(TcpConnection connection) {
        int next = inboxSize.incrementAndGet();
        if (next > MAX_INBOUND_FRAMES) {
            inboxSize.decrementAndGet();
            perfStats.recordInboundOverflow();
            return;
        }
        inbox.add(new NetPacket(DISCONNECT_EVENT, connection.id, connection.address, connection.remotePort));
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

    private DeliveryClass inferDelivery(String message) {
        if (message == null) return DeliveryClass.ORDERED;
        if (SyncFrame.isResourceCorrection(message)) return DeliveryClass.FULL_CORRECTION;
        if (SyncFrame.isView(message)) return DeliveryClass.VIEW_SNAPSHOT;
        if (message.startsWith("SNAPSHOT|")) return DeliveryClass.REGULAR_SNAPSHOT;
        if (message.startsWith("LEADER|")) return DeliveryClass.LEADERBOARD;
        if (message.startsWith("GALAXY|")) return DeliveryClass.GALAXY;
        return DeliveryClass.ORDERED;
    }

    private String replaceableKey(DeliveryClass deliveryClass) {
        return switch (deliveryClass) {
            case ORDERED -> null;
            case REGULAR_SNAPSHOT -> "REGULAR_SNAPSHOT";
            case FULL_CORRECTION -> "FULL_CORRECTION";
            case VIEW_SNAPSHOT -> "VIEW_SNAPSHOT";
            case LEADERBOARD -> "LEADERBOARD";
            case GALAXY -> "GALAXY";
        };
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
        private final ConnectionId id;
        private final Socket socket;
        private final InetAddress address;
        private final int remotePort;
        private final String endpoint;
        private final Deque<OutboundFrame> outboundFrames = new ArrayDeque<>();
        private final Map<String, OutboundFrame> replaceableFrames = new LinkedHashMap<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private int controlFrameCount;
        private long queuedBytes;
        private long coalescedSnapshots;
        private boolean writeInFlight;

        TcpConnection(ConnectionId id, Socket socket) {
            this.id = Objects.requireNonNull(id, "id");
            this.socket = Objects.requireNonNull(socket, "socket");
            this.address = socket.getInetAddress();
            this.remotePort = socket.getPort();
            this.endpoint = endpointKey(address, remotePort);
        }

        void start() {
            String suffix = id + "-" + endpoint;
            Thread reader = new Thread(this::readerLoop, "starchem-tcp-read-" + suffix);
            Thread writer = new Thread(this::writerLoop, "starchem-tcp-write-" + suffix);
            reader.setDaemon(true);
            writer.setDaemon(true);
            reader.start();
            writer.start();
        }

        boolean enqueue(String message, DeliveryClass deliveryClass) {
            byte[] bytes;
            try { bytes = TcpFrameCodec.encode(message); }
            catch (IOException ex) {
                perfStats.recordMalformedPacket();
                return false;
            }
            String key = replaceableKey(deliveryClass);
            OutboundFrame frame = new OutboundFrame(bytes,
                    deliveryClass == DeliveryClass.REGULAR_SNAPSHOT
                            || deliveryClass == DeliveryClass.FULL_CORRECTION
                            || deliveryClass == DeliveryClass.VIEW_SNAPSHOT,
                    key);
            boolean closeSlow = false;
            synchronized (this) {
                if (!open.get()) return false;
                OutboundFrame previous = key == null ? null : replaceableFrames.get(key);
                long projectedBytes = queuedBytes - (previous == null ? 0 : previous.bytes.length) + bytes.length;
                if (projectedBytes > MAX_OUTBOUND_BYTES
                        || key == null && controlFrameCount >= MAX_CONTROL_FRAMES) {
                    closeSlow = true;
                } else {
                    if (previous != null) {
                        removeIdentity(previous);
                        queuedBytes = Math.max(0, queuedBytes - previous.bytes.length);
                        if (previous.snapshot) {
                            coalescedSnapshots++;
                            perfStats.recordSnapshotCoalesced();
                        }
                    }
                    outboundFrames.addLast(frame);
                    queuedBytes += bytes.length;
                    if (key == null) controlFrameCount++;
                    else replaceableFrames.put(key, frame);
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

        synchronized int pendingFrames() { return outboundFrames.size() + (writeInFlight ? 1 : 0); }
        synchronized long pendingBytes() { return queuedBytes; }
        boolean open() { return open.get(); }
        int localPort() { return socket.getLocalPort(); }

        synchronized ConnectionDiagnostics diagnostics() {
            return new ConnectionDiagnostics(id, open.get(), pendingFrames(), queuedBytes, coalescedSnapshots);
        }

        void drainAndClose(long timeoutMs) {
            long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
            synchronized (this) {
                while (open.get() && (!outboundFrames.isEmpty() || writeInFlight)
                        && System.currentTimeMillis() < deadline) {
                    try { wait(Math.min(20, Math.max(1, deadline - System.currentTimeMillis()))); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                }
            }
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
                // Application heartbeats and snapshots normally keep healthy connections active.
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
                    try {
                        output.write(frame.bytes);
                        output.flush();
                        perfStats.recordPacketSent(frame.bytes.length);
                        if (frame.snapshot) perfStats.recordSnapshotSent(frame.bytes.length);
                    } finally {
                        completeWrite();
                    }
                }
            } catch (IOException ignored) {
                // Socket closure is the recovery signal.
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
                    writeInFlight = true;
                    queuedBytes = Math.max(0, queuedBytes - frame.bytes.length);
                    if (frame.replaceableKey == null) controlFrameCount = Math.max(0, controlFrameCount - 1);
                    else replaceableFrames.remove(frame.replaceableKey, frame);
                }
                return frame;
            }
        }

        private synchronized void completeWrite() {
            writeInFlight = false;
            notifyAll();
        }
    }

    private record OutboundFrame(byte[] bytes, boolean snapshot, String replaceableKey) { }
}
