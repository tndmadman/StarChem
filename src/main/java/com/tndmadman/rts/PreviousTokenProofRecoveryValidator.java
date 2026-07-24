package com.tndmadman.rts;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Set;

/** Validates previous-token recovery after a rotated WELCOME response is lost. */
public final class PreviousTokenProofRecoveryValidator {
    private static final String TEST_SERVER_FINGERPRINT = "66".repeat(32);
    private static final String PLAYER_NAME = "Previous Token Client";
    private static final String PLAYER_PASSWORD = "previous-token-validator-password";
    private static final String PLAYER_ID = "P1";

    private PreviousTokenProofRecoveryValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem previous-token recovery validation passed.");
    }

    static void validate() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PeerTransport transport = PeerTransport.server(0, new PerfStats(), TEST_SERVER_FINGERPRINT);
        transport.start();
        try (Socket initial = connect(loopback, transport.localPort());
             Socket rotated = connect(loopback, transport.localPort());
             Socket activeProbe = connect(loopback, transport.localPort());
             Socket recovery = connect(loopback, transport.localPort());
             Socket reuse = connect(loopback, transport.localPort());
             Socket expiry = connect(loopback, transport.localPort())) {
            waitConnection(transport, loopback, initial.getLocalPort());
            waitConnection(transport, loopback, rotated.getLocalPort());
            waitConnection(transport, loopback, activeProbe.getLocalPort());
            waitConnection(transport, loopback, recovery.getLocalPort());
            waitConnection(transport, loopback, reuse.getLocalPort());
            waitConnection(transport, loopback, expiry.getLocalPort());

            Config config = Config.host("Previous Token Host", transport.localPort(), false);
            World world = new World("Previous Token Host", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", "Previous Token Host", 0x50BEFF);
            PeerServerSide server = new PeerServerSide(config, world, transport);

            ConnectionId initialEndpoint = transport.connectionId(loopback, initial.getLocalPort());
            String initialWelcome = register(server, initialEndpoint, loopback, initial.getLocalPort(), initial);
            String tokenA = markerValue(initialWelcome, "SESSION");
            require(validToken(tokenA), "initial registration did not issue token A");
            server.removePeer(initialEndpoint);

            ConnectionId rotatedEndpoint = transport.connectionId(loopback, rotated.getLocalPort());
            require(server.resume(rotatedEndpoint, loopback, rotated.getLocalPort(), PLAYER_ID, tokenA, false, ""),
                    "token A did not perform the first reconnect");
            require(server.owns(rotatedEndpoint, PLAYER_ID), "first reconnect did not bind the rotated connection");
            // Intentionally do not read the WELCOME containing token B.

            ConnectionId activeProbeEndpoint = transport.connectionId(loopback, activeProbe.getLocalPort());
            require(!server.resume(activeProbeEndpoint, loopback, activeProbe.getLocalPort(), PLAYER_ID, tokenA, false, ""),
                    "the previous token displaced a separately active connection");
            receivePayload(activeProbe, "SESSION_DENIED|");
            require(server.owns(rotatedEndpoint, PLAYER_ID), "stale previous token displaced the active owner");

            server.removePeer(rotatedEndpoint);
            require(!server.owns(rotatedEndpoint, PLAYER_ID), "lost-WELCOME connection remained bound after disconnect");

            ConnectionId recoveryEndpoint = transport.connectionId(loopback, recovery.getLocalPort());
            require(server.resume(recoveryEndpoint, loopback, recovery.getLocalPort(), PLAYER_ID, tokenA, false, ""),
                    "previous token was rejected inside the rotation grace window");
            String recoveredWelcome = receivePayload(recovery, "WELCOME|");
            String tokenC = markerValue(recoveredWelcome, "SESSION");
            require(validToken(tokenC) && !tokenA.equals(tokenC),
                    "previous-token recovery did not issue a replacement token");
            server.removePeer(recoveryEndpoint);

            ConnectionId reuseEndpoint = transport.connectionId(loopback, reuse.getLocalPort());
            require(!server.resume(reuseEndpoint, loopback, reuse.getLocalPort(), PLAYER_ID, tokenA, false, ""),
                    "successfully consumed previous token remained reusable");
            receivePayload(reuse, "SESSION_DENIED|");

            require(server.resume(reuseEndpoint, loopback, reuse.getLocalPort(), PLAYER_ID, tokenC, false, ""),
                    "current replacement token failed after old-token rejection");
            String currentWelcome = receivePayload(reuse, "WELCOME|");
            String tokenD = markerValue(currentWelcome, "SESSION");
            require(validToken(tokenD) && !tokenC.equals(tokenD),
                    "current-token reconnect did not rotate to token D");
            server.removePeer(reuseEndpoint);

            expirePreviousToken(server, PLAYER_ID);
            ConnectionId expiryEndpoint = transport.connectionId(loopback, expiry.getLocalPort());
            require(!server.resume(expiryEndpoint, loopback, expiry.getLocalPort(), PLAYER_ID, tokenC, false, ""),
                    "expired previous token reclaimed the session");
            receivePayload(expiry, "SESSION_DENIED|");

            require(server.resume(expiryEndpoint, loopback, expiry.getLocalPort(), PLAYER_ID, tokenD, false, ""),
                    "current token failed after previous-token expiry");
            String latestWelcome = receivePayload(expiry, "WELCOME|");
            require(validToken(markerValue(latestWelcome, "SESSION")),
                    "current-token recovery after expiry did not issue a new token");
            require(server.owns(expiryEndpoint, PLAYER_ID),
                    "current-token recovery after expiry did not bind the player");
        } finally {
            transport.shutdown();
        }
    }

    private static String register(PeerServerSide server, ConnectionId connectionId, InetAddress address,
                                   int port, Socket socket) throws Exception {
        server.join(connectionId, address, port, PLAYER_NAME, false, "");
        String required = receivePayload(socket, "AUTH_REQUIRED|");
        String[] parts = required.split("\\|", -1);
        require(parts.length == 3 && PasswordAuth.decodeHex(parts[2]).length == 16,
                "server did not issue a scoped registration salt");
        String verifier = PasswordAuth.scopedVerifier(PLAYER_NAME, PLAYER_PASSWORD,
                TEST_SERVER_FINGERPRINT, PasswordAuth.decodeHex(parts[2]));
        server.join(connectionId, address, port, PLAYER_NAME, verifier, false, "");
        return receivePayload(socket, "WELCOME|");
    }

    private static void expirePreviousToken(PeerServerSide server, String playerId) throws Exception {
        Field sessionsField = PeerServerSide.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Object rawSessions = sessionsField.get(server);
        require(rawSessions instanceof Map<?, ?>, "server session registry was unavailable");
        Object session = ((Map<?, ?>)rawSessions).get(playerId);
        require(session != null, "retained player session was unavailable");
        Field previousDigestField = session.getClass().getDeclaredField("previousTokenDigest");
        previousDigestField.setAccessible(true);
        require(previousDigestField.get(session) instanceof byte[],
                "previous token digest was unavailable before expiry validation");
        Field deadlineField = session.getClass().getDeclaredField("previousTokenValidUntil");
        deadlineField.setAccessible(true);
        deadlineField.setLong(session, System.currentTimeMillis() - 1);
    }

    private static Socket connect(InetAddress address, int port) throws Exception {
        Socket socket = new Socket(address, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
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

    private static String markerValue(String message, String marker) {
        if (message == null || marker == null) return "";
        String[] parts = message.split("\\|", -1);
        for (int i = 0; i + 1 < parts.length; i++) if (marker.equals(parts[i])) return parts[i + 1];
        return "";
    }

    private static boolean validToken(String token) {
        return token != null && token.length() >= 32 && token.length() <= 256;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
