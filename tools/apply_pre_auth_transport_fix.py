from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} match, found {count}")
    return text.replace(old, new, 1)


transport_path = Path("src/main/java/com/tndmadman/rts/PeerTransport.java")
transport = transport_path.read_text(encoding="utf-8")

transport = replace_once(
    transport,
    "import java.util.concurrent.ConcurrentHashMap;\n",
    "import java.util.concurrent.ArrayBlockingQueue;\n"
    "import java.util.concurrent.ConcurrentHashMap;\n"
    "import java.util.concurrent.RejectedExecutionException;\n"
    "import java.util.concurrent.ThreadPoolExecutor;\n"
    "import java.util.concurrent.TimeUnit;\n",
    "transport imports",
)

transport = replace_once(
    transport,
    "    private static final long RECONNECT_DELAY_MS = 250;\n",
    "    private static final long RECONNECT_DELAY_MS = 250;\n"
    "    private static final int HANDSHAKE_THREADS = 4;\n"
    "    private static final int HANDSHAKE_QUEUE = 32;\n"
    "    private static final AtomicLong HANDSHAKE_THREAD_IDS = new AtomicLong();\n",
    "transport constants",
)

transport = replace_once(
    transport,
    "    private final InboundCommandScheduler inbound;\n",
    "    private final InboundCommandScheduler inbound;\n"
    "    private final PreAuthConnectionGate preAuthGate;\n"
    "    private final ThreadPoolExecutor handshakeExecutor;\n"
    "    private final Set<Socket> pendingServerSockets = ConcurrentHashMap.newKeySet();\n",
    "transport fields",
)

transport = replace_once(
    transport,
    "        this.compatibilityAccepted = serverMode;\n"
    "        this.inbound = new InboundCommandScheduler(serverMode);\n",
    "        this.compatibilityAccepted = serverMode;\n"
    "        this.inbound = new InboundCommandScheduler(serverMode);\n"
    "        this.preAuthGate = serverMode ? new PreAuthConnectionGate(PreAuthConnectionGate.Limits.defaults()) : null;\n"
    "        this.handshakeExecutor = serverMode ? new ThreadPoolExecutor(\n"
    "                HANDSHAKE_THREADS, HANDSHAKE_THREADS, 0L, TimeUnit.MILLISECONDS,\n"
    "                new ArrayBlockingQueue<>(HANDSHAKE_QUEUE), task -> {\n"
    "                    Thread thread = new Thread(task, \"starchem-tcp-handshake-\"\n"
    "                            + HANDSHAKE_THREAD_IDS.incrementAndGet());\n"
    "                    thread.setDaemon(true);\n"
    "                    return thread;\n"
    "                }, new ThreadPoolExecutor.AbortPolicy()) : null;\n",
    "transport constructor",
)

transport = replace_once(
    transport,
    "    InboundCommandScheduler.Snapshot inboundSnapshot() { return inbound.snapshot(); }\n",
    "    InboundCommandScheduler.Snapshot inboundSnapshot() { return inbound.snapshot(); }\n"
    "    PreAuthConnectionGate.Snapshot preAuthSnapshot() {\n"
    "        return preAuthGate == null ? null : preAuthGate.snapshot();\n"
    "    }\n",
    "pre-auth diagnostics accessor",
)

transport = replace_once(
    transport,
    "        if (serverMode && message.startsWith(\"WELCOME|\") && connection != null) {\n"
    "            compatibleConnections.add(connection.id);\n"
    "            return MultiplayerCompatibility.versionServerWelcome(message);\n"
    "        }\n",
    "        if (serverMode && message.startsWith(\"WELCOME|\") && connection != null) {\n"
    "            connection.markAuthenticated();\n"
    "            compatibleConnections.add(connection.id);\n"
    "            return MultiplayerCompatibility.versionServerWelcome(message);\n"
    "        }\n",
    "authenticated transition",
)

old_accept = '''    private void acceptLoop() {
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
'''
new_accept = '''    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (connections.size() + pendingServerSockets.size() >= MAX_CONNECTIONS) {
                    closeQuietly(socket);
                    perfStats.recordConnectionRejected();
                    continue;
                }
                ConnectionId connectionId = new ConnectionId(nextConnectionId.getAndIncrement());
                PreAuthConnectionGate.Decision decision = preAuthGate.tryAcquire(
                        connectionId, socket.getInetAddress(), System.currentTimeMillis());
                if (!decision.allowed()) {
                    closeQuietly(socket);
                    perfStats.recordConnectionRejected();
                    continue;
                }
                pendingServerSockets.add(socket);
                try {
                    handshakeExecutor.execute(() -> initializeAcceptedSocket(connectionId, socket));
                } catch (RejectedExecutionException ex) {
                    pendingServerSockets.remove(socket);
                    preAuthGate.release(connectionId);
                    closeQuietly(socket);
                    perfStats.recordConnectionRejected();
                }
            } catch (SocketException ex) {
                if (running) System.err.println("TCP accept failed: " + ex.getMessage());
            } catch (IOException ex) {
                if (running) System.err.println("TCP accept failed: " + ex.getMessage());
            }
        }
    }

    private void initializeAcceptedSocket(ConnectionId connectionId, Socket socket) {
        boolean handedOff = false;
        try {
            if (!running) return;
            configure(socket, preAuthGate.authenticationTimeoutMs());
            if (socket instanceof SSLSocket ssl) ssl.startHandshake();
            if (!running || preAuthGate.expired(connectionId, System.currentTimeMillis())) {
                perfStats.recordConnectionRejected();
                return;
            }
            TcpConnection connection = new TcpConnection(connectionId, socket);
            connections.put(connection.id, connection);
            endpointIndex.put(connection.endpoint, connection.id);
            handedOff = true;
            perfStats.recordConnectionOpened();
            connection.start();
        } catch (IOException ex) {
            if (running) perfStats.recordConnectionRejected();
        } finally {
            pendingServerSockets.remove(socket);
            if (!handedOff) {
                preAuthGate.release(connectionId);
                closeQuietly(socket);
            }
        }
    }
'''
transport = replace_once(transport, old_accept, new_accept, "accept loop")

transport = replace_once(
    transport,
    "    private void configure(Socket socket) throws SocketException {\n"
    "        if (socket instanceof SSLSocket ssl) configureTlsSocket(ssl);\n"
    "        socket.setTcpNoDelay(true);\n"
    "        socket.setKeepAlive(true);\n"
    "        socket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS);\n"
    "        socket.setReceiveBufferSize(256 * 1024);\n"
    "        socket.setSendBufferSize(256 * 1024);\n"
    "    }\n",
    "    private void configure(Socket socket) throws SocketException {\n"
    "        configure(socket, SOCKET_IDLE_TIMEOUT_MS);\n"
    "    }\n\n"
    "    private void configure(Socket socket, int timeoutMs) throws SocketException {\n"
    "        if (socket instanceof SSLSocket ssl) configureTlsSocket(ssl);\n"
    "        socket.setTcpNoDelay(true);\n"
    "        socket.setKeepAlive(true);\n"
    "        socket.setSoTimeout(Math.max(1, timeoutMs));\n"
    "        socket.setReceiveBufferSize(256 * 1024);\n"
    "        socket.setSendBufferSize(256 * 1024);\n"
    "    }\n",
    "socket configuration",
)

transport = replace_once(
    transport,
    "    private void receive(TcpConnection connection, TcpFrameCodec.DecodedFrame frame) {\n"
    "        if (frame == null || frame.message() == null) return;\n",
    "    private void receive(TcpConnection connection, TcpFrameCodec.DecodedFrame frame) {\n"
    "        if (frame == null || frame.message() == null) return;\n"
    "        if (serverMode && !connection.authenticated()\n"
    "                && preAuthGate.expired(connection.id, System.currentTimeMillis())) {\n"
    "            perfStats.recordConnectionRejected();\n"
    "            connection.close();\n"
    "            return;\n"
    "        }\n",
    "absolute authentication deadline",
)

transport = replace_once(
    transport,
    "        if (serverMode) {\n"
    "            connections.remove(connection.id, connection);\n",
    "        if (serverMode) {\n"
    "            preAuthGate.release(connection.id);\n"
    "            connections.remove(connection.id, connection);\n",
    "permit cleanup",
)

transport = replace_once(
    transport,
    "        if (serverMode) {\n"
    "            for (TcpConnection connection : List.copyOf(connections.values())) connection.close();\n"
    "            connections.clear();\n"
    "            endpointIndex.clear();\n"
    "        } else {\n",
    "        if (serverMode) {\n"
    "            for (Socket socket : List.copyOf(pendingServerSockets)) closeQuietly(socket);\n"
    "            pendingServerSockets.clear();\n"
    "            if (handshakeExecutor != null) handshakeExecutor.shutdownNow();\n"
    "            for (TcpConnection connection : List.copyOf(connections.values())) connection.close();\n"
    "            connections.clear();\n"
    "            endpointIndex.clear();\n"
    "        } else {\n",
    "transport shutdown",
)

transport = replace_once(
    transport,
    "        private final AtomicBoolean open = new AtomicBoolean(true);\n",
    "        private final AtomicBoolean open = new AtomicBoolean(true);\n"
    "        private final AtomicBoolean authenticated = new AtomicBoolean(!serverMode);\n",
    "connection authentication state",
)

transport = replace_once(
    transport,
    "        boolean open() { return open.get(); }\n"
    "        int localPort() { return socket.getLocalPort(); }\n",
    "        boolean open() { return open.get(); }\n"
    "        boolean authenticated() { return authenticated.get(); }\n"
    "        int localPort() { return socket.getLocalPort(); }\n\n"
    "        void markAuthenticated() {\n"
    "            if (!authenticated.compareAndSet(false, true)) return;\n"
    "            if (preAuthGate != null) preAuthGate.authenticate(id);\n"
    "            try { socket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS); }\n"
    "            catch (SocketException ex) { close(); }\n"
    "        }\n",
    "connection authentication methods",
)

transport = replace_once(
    transport,
    "            } catch (SocketTimeoutException ignored) {\n"
    "                // Application heartbeats and snapshots normally keep healthy connections active.\n",
    "            } catch (SocketTimeoutException ignored) {\n"
    "                if (serverMode && !authenticated()) perfStats.recordConnectionRejected();\n"
    "                // Application heartbeats and snapshots normally keep healthy connections active.\n",
    "pre-auth timeout diagnostics",
)

transport_path.write_text(transport, encoding="utf-8")

validator_path = Path("src/main/java/com/tndmadman/rts/NetworkSecurityValidator.java")
validator = validator_path.read_text(encoding="utf-8")
validator = replace_once(
    validator,
    "        validateFrameCodec();\n",
    "        validateFrameCodec();\n        PreAuthConnectionGateValidator.validate();\n",
    "network validator hook",
)
validator_path.write_text(validator, encoding="utf-8")
