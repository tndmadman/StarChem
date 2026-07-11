package com.tndmadman.rts;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class NetworkSecurityValidator {
    private NetworkSecurityValidator() { }

    public static void main(String[] args) throws Exception {
        validateEndpointFiltering();
        validateReliableAckSource();
        validateChunkAssemblyBounds();
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
            require(transport.pendingCount() == 0, "valid ACK did not clear the pending reliable packet");
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
