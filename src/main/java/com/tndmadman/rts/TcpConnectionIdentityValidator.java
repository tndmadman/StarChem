package com.tndmadman.rts;

import java.io.DataInputStream;
import java.net.*;
import java.util.Set;

/** Validates that stale close events cannot detach a newer connection even with reused endpoint metadata. */
public final class TcpConnectionIdentityValidator {
    private TcpConnectionIdentityValidator() { }

    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport transport = PeerTransport.server(0, new PerfStats());
        transport.start();
        try (Socket first = connect(loopback, transport.localPort()); Socket second = connect(loopback, transport.localPort())) {
            waitConnection(transport, loopback, first.getLocalPort());
            waitConnection(transport, loopback, second.getLocalPort());
            ConnectionId firstId = transport.connectionId(loopback, first.getLocalPort());
            ConnectionId secondId = transport.connectionId(loopback, second.getLocalPort());
            TcpIntegrationHarness.require(firstId.valid() && secondId.valid() && !firstId.equals(secondId),
                    "accepted sockets did not receive unique connection IDs");

            Config config = Config.host("Identity Host", transport.localPort(), false);
            World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            PeerServerSide server = new PeerServerSide(config, world, transport);
            server.join(firstId, loopback, first.getLocalPort(), "Identity Client",
                    PasswordAuth.verifier("Identity Client", "validator-password"), false, "");
            String firstWelcome = receive(first, "WELCOME|");
            String token = marker(firstWelcome, "SESSION");
            TcpIntegrationHarness.require(!token.isBlank(), "initial session token was missing");

            server.connectionClosed(new NetPacket(PeerTransport.DISCONNECT_EVENT, firstId, loopback, first.getLocalPort()));
            TcpIntegrationHarness.require(!server.owns(firstId, "P1"), "first connection remained attached after close");
            TcpIntegrationHarness.require(server.resume(secondId, loopback, second.getLocalPort(), "P1", token, false, ""),
                    "session did not attach to the replacement connection");
            receive(second, "WELCOME|");
            TcpIntegrationHarness.require(server.owns(secondId, "P1"), "replacement connection did not own the session");

            // Deliberately give the stale event the new socket's endpoint metadata. Identity must still come from firstId.
            server.connectionClosed(new NetPacket(PeerTransport.DISCONNECT_EVENT, firstId,
                    second.getInetAddress(), second.getLocalPort()));
            TcpIntegrationHarness.require(server.owns(secondId, "P1") && server.sessionConnected("P1"),
                    "stale close event detached the newer connection");

            PacketSideA.handle(server, "MOVE|P1|1|999|999",
                    new NetPacket("MOVE|P1|1|999|999", firstId, second.getInetAddress(), second.getLocalPort()));
            TcpIntegrationHarness.require(server.owns(secondId, "P1"), "stale connection frame affected replacement ownership");
            System.out.println("StarChem TCP connection identity validation passed.");
        } finally {
            transport.shutdown();
        }
    }

    private static Socket connect(InetAddress address, int port) throws Exception {
        Socket socket = new Socket(address, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(4_000);
        return socket;
    }

    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 4_000;
        while (!transport.hasConnection(address, port) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        TcpIntegrationHarness.require(transport.hasConnection(address, port), "server did not register TCP connection");
    }

    private static String receive(Socket socket, String prefix) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        while (true) {
            TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(input);
            if (frame == null) throw new IllegalStateException("socket closed before " + prefix);
            String normalized = MultiplayerCompatibility.inspectServerWelcome(frame.message()).message();
            String message = normalized == null ? frame.message() : normalized;
            if (message.startsWith(prefix)) return message;
        }
    }

    private static String marker(String message, String marker) {
        String[] parts = message.split("\\|", -1);
        for (int i = 0; i + 1 < parts.length; i++) if (marker.equals(parts[i])) return parts[i + 1];
        return "";
    }
}
