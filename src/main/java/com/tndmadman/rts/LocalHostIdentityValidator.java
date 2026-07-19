package com.tndmadman.rts;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;

/** Focused regression validation for graphical HOST identity bootstrap. */
public final class LocalHostIdentityValidator {
    private LocalHostIdentityValidator() { }

    public static void main(String[] args) throws Exception {
        validateRandomProcessVerifier();
        validateReservedHostAuthentication();
        System.out.println("StarChem graphical host identity validation passed.");
    }

    private static void validateRandomProcessVerifier() {
        String name = "Graphical Host";
        String first = PasswordAuth.newProcessVerifier(name);
        String second = PasswordAuth.newProcessVerifier(name);
        String legacy = PasswordAuth.verifier(name, "local-host");

        require(PasswordAuth.validVerifier(first), "first process verifier was invalid");
        require(PasswordAuth.validVerifier(second), "second process verifier was invalid");
        require(!first.equals(second), "two graphical host launches reused the same verifier");
        require(!first.equals(legacy) && !second.equals(legacy),
                "graphical host verifier still matched the legacy fixed credential");
    }

    private static void validateReservedHostAuthentication() throws Exception {
        String name = "Reserved Graphical Host";
        String processVerifier = PasswordAuth.newProcessVerifier(name);
        PersistentPlayerSession reserved = LocalHostSession.processHostSession(name, processVerifier);
        require("P1".equals(reserved.playerId()), "graphical host was not reserved as P1");
        require(reserved.passwordSalt().length == 16, "reserved host password salt was invalid");
        require(reserved.passwordDigest().length > 0, "reserved host password digest was missing");
        require(reserved.tokenDigest().length > 0, "reserved host placeholder session digest was missing");

        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport transport = PeerTransport.server(0, new PerfStats());
        transport.start();
        try (Socket legacyClient = connect(loopback, transport.localPort());
             Socket processClient = connect(loopback, transport.localPort())) {
            waitConnection(transport, loopback, legacyClient.getLocalPort());
            waitConnection(transport, loopback, processClient.getLocalPort());

            Config config = Config.host(name, transport.localPort(), false);
            World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", name, 0x50BEFF);
            PeerServerSide server = new PeerServerSide(config, world, transport, List.of(reserved));
            PlayerRegistry.activate(world);
            if (!world.hasLiveAssets("P1")) WorldNetAccess.addPeerGroup(world, "P1");

            ConnectionId legacyConnection = transport.connectionId(loopback, legacyClient.getLocalPort());
            server.join(legacyConnection, loopback, legacyClient.getLocalPort(), name,
                    PasswordAuth.verifier(name, "local-host"), false, "");
            receivePayload(legacyClient, "JOIN_DENIED|");
            require(!server.owns(legacyConnection, "P1"),
                    "legacy fixed credential reclaimed the graphical host identity");

            ConnectionId processConnection = transport.connectionId(loopback, processClient.getLocalPort());
            server.join(processConnection, loopback, processClient.getLocalPort(), name,
                    processVerifier, false, "");
            String welcome = receivePayload(processClient, "WELCOME|");
            require(welcome.startsWith("WELCOME|P1|"), "process credential did not reclaim reserved P1");
            require(server.owns(processConnection, "P1"), "reserved graphical host connection did not own P1");
            require(world.hasLiveAssets("P1"), "reserved graphical host did not retain its initial assets");
        } finally {
            transport.shutdown();
        }
    }

    private static Socket connect(InetAddress address, int port) throws Exception {
        Socket socket = new Socket(address, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!transport.hasConnection(address, port) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        require(transport.hasConnection(address, port), "TCP connection was not registered");
    }

    private static String receivePayload(Socket socket, String prefix) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        for (int attempt = 0; attempt < 200; attempt++) {
            TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(input);
            if (frame == null) break;
            if (frame.message().startsWith(prefix)) return frame.message();
        }
        throw new IllegalStateException("Did not receive TCP frame starting with " + prefix);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
