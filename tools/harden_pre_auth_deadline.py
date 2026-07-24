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
    "    private final Set<Socket> pendingServerSockets = ConcurrentHashMap.newKeySet();\n",
    "    private final Map<ConnectionId, Socket> pendingServerSockets = new ConcurrentHashMap<>();\n",
    "pending socket map",
)

transport = replace_once(
    transport,
    "    void start() {\n"
    "        Thread thread = new Thread(serverMode ? this::acceptLoop : this::connectLoop,\n"
    "                serverMode ? \"starchem-tcp-accept\" : \"starchem-tcp-connect\");\n"
    "        thread.setDaemon(true);\n"
    "        thread.start();\n"
    "    }\n",
    "    void start() {\n"
    "        Thread thread = new Thread(serverMode ? this::acceptLoop : this::connectLoop,\n"
    "                serverMode ? \"starchem-tcp-accept\" : \"starchem-tcp-connect\");\n"
    "        thread.setDaemon(true);\n"
    "        thread.start();\n"
    "        if (serverMode) {\n"
    "            Thread deadlines = new Thread(this::authenticationDeadlineLoop,\n"
    "                    \"starchem-tcp-auth-deadlines\");\n"
    "            deadlines.setDaemon(true);\n"
    "            deadlines.start();\n"
    "        }\n"
    "    }\n",
    "transport start",
)

transport = replace_once(
    transport,
    "            for (Socket socket : List.copyOf(pendingServerSockets)) closeQuietly(socket);\n",
    "            for (Socket socket : List.copyOf(pendingServerSockets.values())) closeQuietly(socket);\n",
    "pending shutdown",
)

transport = replace_once(transport, "                pendingServerSockets.add(socket);\n",
                         "                pendingServerSockets.put(connectionId, socket);\n", "pending add")
transport = replace_once(transport, "                    pendingServerSockets.remove(socket);\n",
                         "                    pendingServerSockets.remove(connectionId, socket);\n", "pending rejection remove")
transport = replace_once(transport, "            pendingServerSockets.remove(socket);\n",
                         "            pendingServerSockets.remove(connectionId, socket);\n", "pending handoff remove")

transport = replace_once(
    transport,
    "    private void connectLoop() {\n",
    "    private void authenticationDeadlineLoop() {\n"
    "        while (running) {\n"
    "            long now = System.currentTimeMillis();\n"
    "            for (Map.Entry<ConnectionId, Socket> entry : pendingServerSockets.entrySet()) {\n"
    "                if (preAuthGate.expired(entry.getKey(), now)) closeQuietly(entry.getValue());\n"
    "            }\n"
    "            for (TcpConnection connection : List.copyOf(connections.values())) {\n"
    "                if (!connection.authenticated() && preAuthGate.expired(connection.id, now)) {\n"
    "                    perfStats.recordConnectionRejected();\n"
    "                    connection.close();\n"
    "                }\n"
    "            }\n"
    "            sleep(100);\n"
    "        }\n"
    "    }\n\n"
    "    private void connectLoop() {\n",
    "deadline watchdog",
)

transport_path.write_text(transport, encoding="utf-8")

validator_path = Path("src/main/java/com/tndmadman/rts/PreAuthConnectionGateValidator.java")
validator = validator_path.read_text(encoding="utf-8")
validator = replace_once(
    validator,
    "import java.net.InetAddress;\n",
    "import java.io.OutputStream;\n"
    "import java.net.InetAddress;\n"
    "import java.net.Socket;\n",
    "validator imports",
)
validator = replace_once(
    validator,
    "        validateIpv6SubnetLimit();\n",
    "        validateIpv6SubnetLimit();\n"
    "        validateAbsoluteTransportDeadline();\n",
    "validator call",
)
validator = replace_once(
    validator,
    "    private static void require(boolean condition, String message) {\n",
    "    private static void validateAbsoluteTransportDeadline() throws Exception {\n"
    "        String previous = System.getProperty(\"starchem.net.authenticationTimeoutMs\");\n"
    "        System.setProperty(\"starchem.net.authenticationTimeoutMs\", \"1000\");\n"
    "        InetAddress loopback = InetAddress.getLoopbackAddress();\n"
    "        PeerTransport server = PeerTransport.server(0, new PerfStats());\n"
    "        server.start();\n"
    "        try (Socket socket = new Socket(loopback, server.localPort())) {\n"
    "            waitFor(() -> server.hasConnection(loopback, socket.getLocalPort()), 3_000,\n"
    "                    \"server did not register the deadline test connection\");\n"
    "            OutputStream output = socket.getOutputStream();\n"
    "            output.write(0);\n"
    "            output.flush();\n"
    "            Thread.sleep(400);\n"
    "            output.write(0);\n"
    "            output.flush();\n"
    "            Thread.sleep(400);\n"
    "            output.write(0);\n"
    "            output.flush();\n"
    "            waitFor(() -> !server.hasConnection(loopback, socket.getLocalPort()), 3_000,\n"
    "                    \"partial-frame traffic extended the absolute authentication deadline\");\n"
    "        } finally {\n"
    "            server.shutdown();\n"
    "            if (previous == null) System.clearProperty(\"starchem.net.authenticationTimeoutMs\");\n"
    "            else System.setProperty(\"starchem.net.authenticationTimeoutMs\", previous);\n"
    "        }\n"
    "    }\n\n"
    "    private static void waitFor(Check check, long timeoutMs, String message) throws Exception {\n"
    "        long deadline = System.currentTimeMillis() + timeoutMs;\n"
    "        while (System.currentTimeMillis() < deadline) {\n"
    "            if (check.ok()) return;\n"
    "            Thread.sleep(20);\n"
    "        }\n"
    "        throw new IllegalStateException(message);\n"
    "    }\n\n"
    "    private interface Check { boolean ok() throws Exception; }\n\n"
    "    private static void require(boolean condition, String message) {\n",
    "deadline validator",
)
validator_path.write_text(validator, encoding="utf-8")
