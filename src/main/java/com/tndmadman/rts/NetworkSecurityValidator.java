package com.tndmadman.rts;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class NetworkSecurityValidator {
    private NetworkSecurityValidator() { }

    public static void main(String[] args) throws Exception {
        validateFrameCodec();
        validateTransportRoundTrip();
        validateEncryptedTransportAndPinning();
        validateCompatibilityHandshake();
        validateSnapshotCoalescingAndBackpressure();
        System.out.println("StarChem TCP network security validation passed.");
    }

    private static void validateFrameCodec() throws Exception {
        byte[] first = TcpFrameCodec.encode("ONE|alpha");
        byte[] second = TcpFrameCodec.encode("TWO|βeta");
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(first);
        combined.write(second);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(combined.toByteArray()))) {
            require("ONE|alpha".equals(TcpFrameCodec.read(in).message()), "first coalesced TCP frame was not decoded");
            require("TWO|βeta".equals(TcpFrameCodec.read(in).message()), "second coalesced TCP frame was not decoded");
            require(TcpFrameCodec.read(in) == null, "frame decoder did not stop at EOF");
        }

        String large = "LARGE|" + "x".repeat(200_000);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(TcpFrameCodec.encode(large)))) {
            require(large.equals(TcpFrameCodec.read(in).message()), "large framed message did not round-trip");
        }

        expectFrameReject(intFrame(0), "zero-length frame was accepted");
        expectFrameReject(intFrame(TcpFrameCodec.MAX_FRAME_BYTES + 1), "oversized frame was accepted");
        expectFrameReject(new byte[]{0, 0}, "truncated TCP frame header was accepted");

        ByteArrayOutputStream truncatedPayload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(truncatedPayload)) {
            out.writeInt(8);
            out.write(new byte[]{1, 2, 3});
        }
        expectFrameReject(truncatedPayload.toByteArray(), "truncated TCP frame payload was accepted");

        ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(malformed)) {
            out.writeInt(2);
            out.write(new byte[]{(byte) 0xC3, 0x28});
        }
        expectFrameReject(malformed.toByteArray(), "malformed UTF-8 frame was accepted");

        PlayerRegistry.reset("SOLO", "TCP Frame Validator", 0x50BEFF);
        World world = new World("TCP Frame Validator", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        ClientViewCache views = new ClientViewCache();
        String correction = SyncPacketBuilder.build(world, views, "SOLO", 1, SyncKind.REGULAR, true);
        require(SyncFrame.matches(correction), "full corrective snapshot was left replaceable");
    }

    private static void validateTransportRoundTrip() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport server = PeerTransport.server(0, new PerfStats());
        PeerTransport client = PeerTransport.client(new InetSocketAddress(loopback, server.localPort()), new PerfStats());
        server.start();
        client.start();
        try {
            waitFor(client::connected, 3_000, "TCP client did not connect");
            int clientPort = client.localPort();
            waitFor(() -> server.hasConnection(loopback, clientPort), 3_000, "TCP server did not register the client");

            client.send("JOIN|Transport Client|NODEV|", loopback, server.localPort());
            NetPacket joinPacket = poll(server, 3_000);
            require(joinPacket != null, "framed JOIN did not reach the server");
            String normalizedJoin = server.processInbound(joinPacket);
            require("JOIN|Transport Client|NODEV|".equals(normalizedJoin), "JOIN compatibility wrapper was not normalized");

            String token = "transport-validator-session-token-000000000000000";
            String welcome = "WELCOME|P1|Transport Client|1|sol_standard|1|0|DEV|0|SESSION|" + token;
            server.sendOrdered(welcome, loopback, clientPort);
            NetPacket welcomePacket = poll(client, 3_000);
            require(welcomePacket != null, "framed WELCOME did not reach the client");
            require(welcome.equals(client.processInbound(welcomePacket)), "WELCOME compatibility wrapper was not normalized");

            String large = "BULK|" + "z".repeat(200_000);
            client.send(large, loopback, server.localPort());
            NetPacket bulk = poll(server, 3_000);
            require(bulk != null && large.equals(server.processInbound(bulk)), "large TCP payload did not arrive intact");
        } finally {
            client.shutdown();
            server.shutdown();
        }
    }

    private static void validateEncryptedTransportAndPinning() throws Exception {
        java.nio.file.Path store = java.nio.file.Files.createTempFile("starchem-tls-pin-", ".properties");
        java.nio.file.Files.deleteIfExists(store);
        java.nio.file.Path firstDir = java.nio.file.Files.createTempDirectory("starchem-tls-first-");
        java.nio.file.Path secondDir = java.nio.file.Files.createTempDirectory("starchem-tls-second-");
        System.setProperty("starchem.sessionStore", store.toString());
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport firstServer = null;
        PeerTransport firstClient = null;
        PeerTransport secondServer = null;
        PeerTransport secondClient = null;
        try {
            Config firstServerConfig = Config.dedicatedServer("TLS Server", 0, false, false,
                    java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "", 1, firstDir, "server", 60, 5, false);
            firstServer = PeerTransport.server(firstServerConfig, new PerfStats());
            int port = firstServer.localPort();
            Config clientConfig = Config.join("TLS Client", loopback.getHostAddress(), port, false);
            firstClient = PeerTransport.client(clientConfig, new PerfStats());
            firstServer.start();
            firstClient.start();
            waitFor(firstClient::connected, 5_000, "encrypted TCP client did not connect");
            require(PasswordAuth.validVerifier(SessionTokenStore.serverFingerprint(clientConfig)),
                    "client did not pin the server TLS fingerprint");
            firstClient.send("JOIN|TLS Client|NODEV|", loopback, port);
            NetPacket encryptedJoin = poll(firstServer, 5_000);
            require(encryptedJoin != null, "encrypted JOIN did not reach the server");
            require("JOIN|TLS Client|NODEV|".equals(firstServer.processInbound(encryptedJoin)),
                    "encrypted JOIN compatibility wrapper was not normalized");
            firstClient.shutdown();
            firstServer.shutdown();

            Config secondServerConfig = Config.dedicatedServer("TLS Server", port, false, false,
                    java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "", 1, secondDir, "server", 60, 5, false);
            secondServer = PeerTransport.server(secondServerConfig, new PerfStats());
            secondClient = PeerTransport.client(clientConfig, new PerfStats());
            secondServer.start();
            secondClient.start();
            Thread.sleep(1_000);
            require(!secondClient.connected(), "client accepted a changed server TLS fingerprint");
            String pinFailure = "";
            long failureDeadline = System.currentTimeMillis() + 3_000;
            while (!pinFailure.contains("TLS fingerprint changed")
                    && System.currentTimeMillis() < failureDeadline) {
                String nextFailure = secondClient.consumeClientConnectFailure();
                if (!nextFailure.isBlank()) pinFailure = nextFailure;
                if (!pinFailure.contains("TLS fingerprint changed")) Thread.sleep(10);
            }
            require(pinFailure.contains("TLS fingerprint changed"),
                    "changed TLS fingerprint failure was not exposed to the client");
        } finally {
            if (firstClient != null) firstClient.shutdown();
            if (firstServer != null) firstServer.shutdown();
            if (secondClient != null) secondClient.shutdown();
            if (secondServer != null) secondServer.shutdown();
            SessionTokenStore.clear(Config.join("TLS Client", loopback.getHostAddress(), 1, false));
            System.clearProperty("starchem.sessionStore");
            java.nio.file.Files.deleteIfExists(store);
        }
    }

    private static void validateCompatibilityHandshake() throws Exception {
        MultiplayerCompatibility.Descriptor local = MultiplayerCompatibility.local();
        String exactJoin = joinPacket(local);
        MultiplayerCompatibility.WireResult exact = MultiplayerCompatibility.inspectClientHandshake(exactJoin);
        require(exact.action() == MultiplayerCompatibility.WireAction.ACCEPT, "exact compatibility match was rejected");
        require("JOIN|Compatibility Client|NODEV|".equals(exact.message()), "accepted handshake was not normalized");

        MultiplayerCompatibility.Descriptor commitMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion(), local.applicationVersion(), local.buildCommit() + "-different",
                local.rulesVersion(), local.configHash());
        MultiplayerCompatibility.WireResult commitResult = MultiplayerCompatibility.inspectClientHandshake(
                joinPacket(commitMismatch));
        require(commitResult.action() == MultiplayerCompatibility.WireAction.ACCEPT,
                "matching release was rejected solely because the build commit differed");

        MultiplayerCompatibility.Descriptor applicationMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion(), local.applicationVersion() + "-different", local.buildCommit(),
                local.rulesVersion(), local.configHash());
        expectCompatibilityReject(joinPacket(applicationMismatch), "APPLICATION_MISMATCH");

        MultiplayerCompatibility.Descriptor protocolMismatch = new MultiplayerCompatibility.Descriptor(
                local.protocolVersion() + 1, local.applicationVersion(), local.buildCommit(),
                local.rulesVersion(), local.configHash());
        expectCompatibilityReject(joinPacket(protocolMismatch), "PROTOCOL_MISMATCH");
        expectCompatibilityReject("JOIN_V1|Compatibility Client|NODEV|", "MISSING_FIELDS");
        expectCompatibilityReject("JOIN|Compatibility Client|NODEV|", "LEGACY_HANDSHAKE");

        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport server = PeerTransport.server(0, new PerfStats());
        server.start();
        try (Socket socket = new Socket(loopback, server.localPort())) {
            socket.setSoTimeout(3_000);
            waitFor(() -> server.hasConnection(loopback, socket.getLocalPort()), 3_000,
                    "server did not register compatibility test connection");
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            output.write(TcpFrameCodec.encode(joinPacket(protocolMismatch)));
            output.flush();
            NetPacket packet = poll(server, 3_000);
            require(packet != null && server.processInbound(packet) == null,
                    "incompatible TCP handshake reached normal packet dispatch");
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                TcpFrameCodec.DecodedFrame denial = TcpFrameCodec.read(input);
                require(denial != null && denial.message().contains("PROTOCOL_MISMATCH"),
                        "TCP compatibility denial did not identify the mismatch");
            }
        } finally {
            server.shutdown();
        }
    }

    private static void validateSnapshotCoalescingAndBackpressure() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport server = PeerTransport.server(0, new PerfStats());
        server.start();
        try (Socket slowClient = new Socket(loopback, server.localPort())) {
            slowClient.setReceiveBufferSize(1024);
            int port = slowClient.getLocalPort();
            waitFor(() -> server.hasConnection(loopback, port), 3_000, "slow client was not accepted");

            String snapshotBody = "x".repeat(180_000);
            long started = System.nanoTime();
            for (int i = 0; i < 100; i++) server.send("SNAPSHOT|" + i + '|' + snapshotBody, loopback, port);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            require(elapsedMs < 1_000, "snapshot enqueue blocked the game thread");
            require(server.queuedCount() <= 2, "obsolete snapshots accumulated instead of being coalesced");

            String control = "CONTROL|" + "y".repeat(40_000);
            for (int i = 0; i < 400 && server.hasConnection(loopback, port); i++) {
                server.sendOrdered(control + i, loopback, port);
            }
            waitFor(() -> !server.hasConnection(loopback, port), 3_000,
                    "unbounded slow-client control backlog did not close the connection");
        } finally {
            server.shutdown();
        }
    }

    private static byte[] intFrame(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeInt(length); }
        return bytes.toByteArray();
    }

    private static void expectFrameReject(byte[] bytes, String message) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            try {
                TcpFrameCodec.read(in);
                throw new IllegalStateException(message);
            } catch (IOException expected) { }
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

    private static NetPacket poll(PeerTransport transport, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        NetPacket packet;
        while ((packet = transport.poll()) == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        return packet;
    }

    private static void waitFor(Check check, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!check.ok() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        require(check.ok(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Check { boolean ok() throws Exception; }
}
