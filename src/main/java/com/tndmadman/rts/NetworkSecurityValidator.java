package com.tndmadman.rts;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class NetworkSecurityValidator {
    private NetworkSecurityValidator() { }

    public static void main(String[] args) throws Exception {
        validateEndpointFiltering();
        validateReliableAckSource();
        validateChunkAssemblyBounds();
        validateCompatibilityHandshake();
        System.out.println("StarChem network security validation passed.");
    }

    private static void validateEndpointFiltering() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket clientSocket = new DatagramSocket(0, loopback);
             DatagramSocket serverSocket = new DatagramSocket(0, loopback);
             DatagramSocket attackerSocket = new DatagramSocket(0, loopback)) {
            PeerTransport transport = new PeerTransport(clientSocket, new PerfStats(),
                    new InetSocketAddress(loopback, serverSocket.getLocalPort()));
            transport.start();
            try {
                send(attackerSocket, clientSocket.getLocalPort(), "WELCOME|P1|Attacker|1");
                Thread.sleep(80);
                require(transport.poll() == null, "foreign endpoint packet reached the client inbox");

                send(serverSocket, clientSocket.getLocalPort(), "WELCOME|P1|Server|1");
                NetPacket accepted = poll(transport, 1000);
                require(accepted != null, "configured server packet was not accepted");
                require(accepted.port() == serverSocket.getLocalPort(), "accepted packet lost its source port");

                require(transport.accepts(new NetPacket("PING", loopback, serverSocket.getLocalPort())),
                        "configured endpoint failed the dispatch check");
                require(!transport.accepts(new NetPacket("PING", loopback, attackerSocket.getLocalPort())),
                        "foreign endpoint passed the dispatch check");
            } finally {
                transport.shutdown();
            }
        }
    }

    private static void validateReliableAckSource() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket senderSocket = new DatagramSocket(0, loopback);
             DatagramSocket serverSocket = new DatagramSocket(0, loopback);
             DatagramSocket attackerSocket = new DatagramSocket(0, loopback)) {
            serverSocket.setSoTimeout(1000);
            PeerTransport transport = new PeerTransport(senderSocket, new PerfStats(),
                    new InetSocketAddress(loopback, serverSocket.getLocalPort()));
            transport.reliable("PING", loopback, serverSocket.getLocalPort());
            require(transport.pendingCount() == 1, "reliable packet was not tracked");

            byte[] buffer = new byte[PacketChunks.MAX_DATAGRAM_BYTES];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            serverSocket.receive(packet);
            String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 3);
            require(parts.length == 3 && "REL".equals(parts[0]), "reliable packet format was unexpected");
            String id = parts[1];

            transport.unwrapReliable(new NetPacket("ACK|" + id, loopback, attackerSocket.getLocalPort()));
            require(transport.pendingCount() == 1, "spoofed ACK removed a pending reliable packet");

            transport.unwrapReliable(new NetPacket("ACK|" + id, loopback, serverSocket.getLocalPort()));
            require(transport.pendingCount() == 0, "valid ACK did not clear a pending reliable packet");
            transport.shutdown();
        }
    }

    private static void validateChunkAssemblyBounds() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PacketChunks chunks = new PacketChunks("validator", new PerfStats());
        require(chunks.receive("CHUNK|valid|0|2|hello", loopback, 50000) == null,
                "partial chunk assembly completed early");
        require("helloworld".equals(chunks.receive("CHUNK|valid|1|2|world", loopback, 50000)),
                "valid chunk assembly failed");

        require(chunks.receive("CHUNK|conflict|0|2|left", loopback, 50000) == null,
                "partial conflicting assembly completed early");
        require(chunks.receive("CHUNK|conflict|0|2|right", loopback, 50000) == null,
                "conflicting duplicate chunk was accepted");
        require(chunks.receive("CHUNK|conflict|1|2|tail", loopback, 50000) == null,
                "invalidated conflicting assembly completed");

        String oversizedId = "x".repeat(97);
        require(chunks.receive("CHUNK|" + oversizedId + "|0|1|data", loopback, 50000) == null,
                "oversized assembly ID was accepted");
        require(chunks.receive("CHUNK|bad|0|641|data", loopback, 50000) == null,
                "excessive chunk count was accepted");
    }

    private static void validateCompatibilityHandshake() throws Exception {
        MultiplayerCompatibility.Descriptor local = MultiplayerCompatibility.local();
        String exactJoin = joinPacket(local);
        MultiplayerCompatibility.WireResult exact = MultiplayerCompatibility.inspectClientHandshake(exactJoin);
        require(exact.action() == MultiplayerCompatibility.WireAction.ACCEPT,
                "exact compatibility match was rejected");
        require("JOIN|Compatibility Client|NODEV|".equals(exact.message()),
                "accepted handshake was not normalized for existing join handling");

        MultiplayerCompatibility.Descriptor protocolMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion() + 1, local.applicationVersion(), local.buildCommit(),
                local.rulesVersion(), local.configHash());
        expectCompatibilityReject(joinPacket(protocolMismatch), "PROTOCOL_MISMATCH");

        MultiplayerCompatibility.Descriptor applicationMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion(), local.applicationVersion() + "-other", local.buildCommit(),
                local.rulesVersion(), local.configHash());
        expectCompatibilityReject(joinPacket(applicationMismatch), "APPLICATION_MISMATCH");

        MultiplayerCompatibility.Descriptor buildMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion(), local.applicationVersion(), local.buildCommit() + "-other",
                local.rulesVersion(), local.configHash());
        expectCompatibilityReject(joinPacket(buildMismatch), "BUILD_MISMATCH");

        MultiplayerCompatibility.Descriptor rulesMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion(), local.applicationVersion(), local.buildCommit(),
                local.rulesVersion() + 1, local.configHash());
        expectCompatibilityReject(joinPacket(rulesMismatch), "RULES_MISMATCH");

        String changedHash = (local.configHash().startsWith("0") ? "1" : "0") + local.configHash().substring(1);
        MultiplayerCompatibility.Descriptor configMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion(), local.applicationVersion(), local.buildCommit(),
                local.rulesVersion(), changedHash);
        expectCompatibilityReject(joinPacket(configMismatch), "CONFIG_MISMATCH");

        expectCompatibilityReject("JOIN_V1|Compatibility Client|NODEV|", "MISSING_FIELDS");
        expectCompatibilityReject("JOIN|Compatibility Client|NODEV|", "LEGACY_HANDSHAKE");

        String baseWelcome = "WELCOME|P1|Compatibility Client|1|sol_standard|1|0|DEV|0|SESSION|"
                + "compatibility-validator-session-token-000000000000";
        MultiplayerCompatibility.WireResult welcome = MultiplayerCompatibility.inspectServerWelcome(
                MultiplayerCompatibility.versionServerWelcome(baseWelcome));
        require(welcome.action() == MultiplayerCompatibility.WireAction.ACCEPT,
                "matching server welcome was rejected");
        require(baseWelcome.equals(welcome.message()), "accepted welcome was not normalized");
        require(MultiplayerCompatibility.inspectServerWelcome(baseWelcome).action()
                        == MultiplayerCompatibility.WireAction.REJECT,
                "legacy server welcome was accepted");

        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket serverSocket = new DatagramSocket(0, loopback);
             DatagramSocket clientSocket = new DatagramSocket(0, loopback)) {
            clientSocket.setSoTimeout(1500);
            Config config = Config.host("Compatibility Host", serverSocket.getLocalPort(), false);
            World world = new World("Compatibility Host", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", "Compatibility Host", 0x50BEFF);
            PeerTransport transport = new PeerTransport(serverSocket);
            PeerServerSide server = new PeerServerSide(config, world, transport);
            String endpoint = server.endpoint(loopback, clientSocket.getLocalPort());

            NetPacket mismatchPacket = new NetPacket(joinPacket(protocolMismatch), loopback, clientSocket.getLocalPort());
            require(transport.unwrapReliable(mismatchPacket) == null,
                    "protocol mismatch reached server packet dispatch");
            String mismatchDenial = receivePayload(clientSocket, "COMPAT_DENIED|");
            require(mismatchDenial.contains("PROTOCOL_MISMATCH"),
                    "protocol mismatch denial did not identify the mismatch");
            require(!server.owns(endpoint, "P1") && !world.hasLiveAssets("P1"),
                    "incompatible handshake mutated server player state");

            NetPacket missingPacket = new NetPacket("JOIN_V1|Compatibility Client|NODEV|", loopback,
                    clientSocket.getLocalPort());
            require(transport.unwrapReliable(missingPacket) == null,
                    "missing compatibility fields reached server packet dispatch");
            require(receivePayload(clientSocket, "COMPAT_DENIED|").contains("MISSING_FIELDS"),
                    "missing compatibility fields were not explained");
            require(!server.owns(endpoint, "P1") && !world.hasLiveAssets("P1"),
                    "missing compatibility fields mutated server player state");

            NetPacket legacyPacket = new NetPacket("JOIN|Compatibility Client|NODEV|", loopback,
                    clientSocket.getLocalPort());
            require(transport.unwrapReliable(legacyPacket) == null,
                    "legacy handshake reached server packet dispatch");
            require(receivePayload(clientSocket, "COMPAT_DENIED|").contains("LEGACY_HANDSHAKE"),
                    "legacy handshake was not explicitly rejected");
            require(!server.owns(endpoint, "P1") && !world.hasLiveAssets("P1"),
                    "legacy handshake mutated server player state");

            NetPacket exactPacket = new NetPacket(exactJoin, loopback, clientSocket.getLocalPort());
            String normalized = transport.unwrapReliable(exactPacket);
            require("JOIN|Compatibility Client|NODEV|".equals(normalized),
                    "exact handshake did not reach existing join dispatch");
            require(SideAJoin.handle(server, normalized.split("\\|", -1), endpoint, exactPacket),
                    "normalized handshake was not handled as a join");
            String versionedWelcome = receivePayload(clientSocket, "WELCOME|");
            require(MultiplayerCompatibility.inspectServerWelcome(versionedWelcome).action()
                            == MultiplayerCompatibility.WireAction.ACCEPT,
                    "server did not send a compatible versioned welcome");
            require(server.owns(endpoint, "P1") && world.hasLiveAssets("P1"),
                    "compatible handshake did not create the player normally");
            transport.shutdown();
        }
    }

    private static String joinPacket(MultiplayerCompatibility.Descriptor descriptor) {
        return "JOIN_V1|Compatibility Client|NODEV||" + descriptor.wireFields();
    }

    private static void expectCompatibilityReject(String packet, String expectedCode) {
        MultiplayerCompatibility.WireResult result = MultiplayerCompatibility.inspectClientHandshake(packet);
        require(result.action() == MultiplayerCompatibility.WireAction.REJECT,
                "expected compatibility rejection for " + expectedCode);
        require(result.detail().contains(expectedCode),
                "compatibility rejection did not contain " + expectedCode + ": " + result.detail());
    }

    private static String receivePayload(DatagramSocket socket, String prefix) throws Exception {
        byte[] buffer = new byte[PacketChunks.MAX_DATAGRAM_BYTES + 1];
        for (int attempt = 0; attempt < 200; attempt++) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            String payload = raw;
            if (raw.startsWith("REL|")) {
                String[] reliable = raw.split("\\|", 3);
                if (reliable.length < 3) continue;
                payload = reliable[2];
            }
            if (payload.startsWith(prefix)) return payload;
        }
        throw new IllegalStateException("Did not receive packet starting with " + prefix);
    }

    private static void send(DatagramSocket socket, int port, String message) throws Exception {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    private static NetPacket poll(PeerTransport transport, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        NetPacket packet;
        while ((packet = transport.poll()) == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        return packet;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
