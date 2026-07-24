package com.tndmadman.rts;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Focused regression validation for registered-player enumeration resistance. */
public final class AuthenticationEnumerationValidator {
    private static final String EXISTING_NAME = "Retained Authentication";
    private static final String EXISTING_PASSWORD = "retained-password";
    private static final String MISSING_NAME = "Unknown Authentication";
    private static final String MISSING_PASSWORD = "unknown-password";
    private static final String TEST_SERVER_FINGERPRINT = "11".repeat(32);
    private static final String OTHER_SERVER_FINGERPRINT = "22".repeat(32);

    private AuthenticationEnumerationValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem authentication enumeration validation passed.");
    }

    static void validate() throws Exception {
        validateAttemptLimiter();
        validateServerScopedVerifier();
        Path saveDir = Files.createTempDirectory("starchem-auth-enumeration-");
        PeerTransport transport = PeerTransport.server(0, new PerfStats(), TEST_SERVER_FINGERPRINT);
        transport.start();
        try {
            Config config = Config.dedicatedServer("Authentication Enumeration Host", transport.localPort(),
                    false, false, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "", 1,
                    saveDir, "auth-enumeration", 0, 1, true);
            validateStableDecoySalt(config);

            byte[] accountSalt = PasswordAuth.newSalt();
            String existingVerifier = PasswordAuth.verifier(EXISTING_NAME, EXISTING_PASSWORD);
            PersistentPlayerSession retained = new PersistentPlayerSession("P1", EXISTING_NAME, 0x50BEFF,
                    accountSalt, PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(existingVerifier), accountSalt),
                    PasswordAuth.tokenDigest(PasswordAuth.newNonce()), new byte[0], 0);
            World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            PeerServerSide server = new PeerServerSide(config, world, transport, List.of(retained));
            if (!world.hasLiveAssets("P1")) WorldNetAccess.addPeerGroup(world, "P1");

            InetAddress loopback = InetAddress.getLoopbackAddress();
            InetAddress remote = InetAddress.getByName("198.51.100.23");
            try (TestConnection existing = connect(transport, loopback);
                 TestConnection missing = connect(transport, loopback);
                 TestConnection missingRepeat = connect(transport, loopback);
                 TestConnection existingRepeat = connect(transport, loopback);
                 TestConnection localRegistration = connect(transport, loopback)) {

                Challenge existingChallenge = challenge(server, existing, remote, EXISTING_NAME);
                Challenge missingChallenge = challenge(server, missing, remote, MISSING_NAME);
                require(existingChallenge.payloadParts == missingChallenge.payloadParts,
                        "existing and unknown identities received different challenge shapes");
                require(existingChallenge.currentSalt.length() == 32 && existingChallenge.scopedSalt.length() == 32
                        && missingChallenge.currentSalt.length() == 32 && missingChallenge.scopedSalt.length() == 32,
                        "authentication challenge salt shape was invalid");

                Challenge repeatedMissing = challenge(server, missingRepeat, remote, MISSING_NAME);
                require(missingChallenge.currentSalt.equals(repeatedMissing.currentSalt)
                        && missingChallenge.scopedSalt.equals(repeatedMissing.scopedSalt),
                        "unknown identity did not receive a stable server-secret decoy salt");
                require(!missingChallenge.nonce.equals(repeatedMissing.nonce),
                        "unknown identity reused a challenge nonce");

                Challenge repeatedExisting = challenge(server, existingRepeat, remote, EXISTING_NAME);
                require(existingChallenge.currentSalt.equals(repeatedExisting.currentSalt)
                        && existingChallenge.scopedSalt.equals(repeatedExisting.scopedSalt),
                        "retained identity challenge salt unexpectedly changed");
                require(!existingChallenge.nonce.equals(repeatedExisting.nonce),
                        "retained identity reused a challenge nonce");

                String remoteRegisterVerifier = PasswordAuth.verifier(MISSING_NAME, MISSING_PASSWORD);
                server.join(missingRepeat.connectionId, remote, missingRepeat.socket.getLocalPort(), MISSING_NAME,
                        remoteRegisterVerifier, false, "");
                String remoteRegisterResponse = receivePayload(missingRepeat, "AUTH_CHALLENGE|");
                require(remoteRegisterResponse.startsWith("AUTH_CHALLENGE|"),
                        "remote AUTH_REGISTER bypassed the uniform challenge path");
                require(server.persistentSessions().size() == 1,
                        "remote registration material created an unknown identity");

                String existingDenial = answerChallenge(server, existing, remote, EXISTING_NAME,
                        "wrong-password", existingChallenge);
                String missingDenial = answerChallenge(server, missing, remote, MISSING_NAME,
                        MISSING_PASSWORD, missingChallenge);
                require("JOIN_DENIED|Password rejected.".equals(existingDenial),
                        "wrong retained password returned a non-generic denial");
                require(existingDenial.equals(missingDenial),
                        "existing and unknown identity proof failures were distinguishable");
                require(server.persistentSessions().size() == 1,
                        "fake authentication challenge created a player session");

                AuthResponse correct = response(EXISTING_NAME, EXISTING_PASSWORD, repeatedExisting);
                server.join(existingRepeat.connectionId, remote, existingRepeat.socket.getLocalPort(), EXISTING_NAME,
                        correct.registrationMaterial, repeatedExisting.nonce, "", false, "");
                String welcome = receivePayload(existingRepeat, "WELCOME|");
                require(welcome.startsWith("WELCOME|P1|"),
                        "retained identity did not authenticate through the real challenge");
                require(server.owns(existingRepeat.connectionId, "P1"),
                        "authenticated retained connection did not own P1");

                String localName = "Local Provisioning";
                server.join(localRegistration.connectionId, loopback, localRegistration.socket.getLocalPort(),
                        localName, false, "");
                String registrationChallenge = receivePayload(localRegistration, "AUTH_REQUIRED|");
                String[] registrationParts = registrationChallenge.split("\\|", -1);
                require(registrationParts.length == 3 && PasswordAuth.decodeHex(registrationParts[2]).length == 16,
                        "loopback provisioning did not expose a scoped registration salt");
                String registrationVerifier = PasswordAuth.scopedVerifier(localName, "local-password",
                        TEST_SERVER_FINGERPRINT, PasswordAuth.decodeHex(registrationParts[2]));
                server.join(localRegistration.connectionId, loopback, localRegistration.socket.getLocalPort(),
                        localName, registrationVerifier, false, "");
                String localWelcome = receivePayload(localRegistration, "WELCOME|");
                require(localWelcome.startsWith("WELCOME|P2|"),
                        "trusted loopback provisioning did not create the next player identity");
            }
        } finally {
            transport.shutdown();
            deleteTree(saveDir);
        }
    }

    private static void validateStableDecoySalt(Config config) {
        AuthDecoySaltStore first = new AuthDecoySaltStore(config);
        AuthDecoySaltStore second = new AuthDecoySaltStore(config);
        require(java.security.MessageDigest.isEqual(first.saltFor(MISSING_NAME), second.saltFor(MISSING_NAME)),
                "persisted decoy secret did not reproduce the same unknown-name salt");
        require(!java.security.MessageDigest.isEqual(first.saltFor(MISSING_NAME), first.saltFor("Another Missing Name")),
                "decoy salt did not remain scoped to the requested identity");
    }

    private static void validateServerScopedVerifier() {
        byte[] salt = PasswordAuth.newSalt();
        String first = PasswordAuth.scopedVerifier("Scoped Player", "same-password", TEST_SERVER_FINGERPRINT, salt);
        String second = PasswordAuth.scopedVerifier("Scoped Player", "same-password", OTHER_SERVER_FINGERPRINT, salt);
        require(PasswordAuth.validVerifier(first) && PasswordAuth.validVerifier(second),
                "server-scoped verifier derivation failed");
        require(!first.equals(second), "two TLS server identities produced interchangeable password verifiers");
        byte[] firstVerifier = PasswordAuth.decodeVerifier(first);
        byte[] storedDigest = PasswordAuth.serverDigest(firstVerifier, salt);
        require(PasswordAuth.passwordCredentialMatches(storedDigest, firstVerifier, salt),
                "correct server-scoped verifier was rejected");
        require(!PasswordAuth.passwordCredentialMatches(storedDigest, storedDigest, salt),
                "stored save digest authenticated as a client credential");
        byte[] upgraded = PasswordAuth.upgradeSalt(salt);
        require(upgraded.length == 16 && !java.security.MessageDigest.isEqual(salt, upgraded),
                "password upgrade salt was not domain separated");
        java.util.Arrays.fill(firstVerifier, (byte)0);
        java.util.Arrays.fill(storedDigest, (byte)0);
    }

    private static void validateAttemptLimiter() throws Exception {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(2, 1, 1_000);
        InetAddress firstSource = InetAddress.getByName("198.51.100.10");
        InetAddress secondSource = InetAddress.getByName("198.51.100.11");
        require(limiter.allow(firstSource, "Alpha", 1_000), "first authentication attempt was rejected");
        require(!limiter.allow(secondSource, "Alpha", 1_001),
                "per-identity authentication limit did not cover another source");
        require(limiter.allow(firstSource, "Beta", 1_002), "second source-scoped attempt was rejected early");
        require(!limiter.allow(firstSource, "Gamma", 1_003),
                "per-source authentication limit did not cover many identities");
        require(limiter.allow(firstSource, "Alpha", 2_001),
                "authentication attempt window did not expire");
    }

    private static Challenge challenge(PeerServerSide server, TestConnection connection,
                                       InetAddress reportedAddress, String name) throws Exception {
        server.join(connection.connectionId, reportedAddress, connection.socket.getLocalPort(), name, false, "");
        String message = receivePayload(connection, "AUTH_CHALLENGE|");
        String[] parts = message.split("\\|", -1);
        require(parts.length == 4, "authentication challenge field count was invalid");
        require(Config.clean(name).equals(parts[1]), "authentication challenge changed the requested name");
        PasswordAuth.ChallengeSalts salts = PasswordAuth.decodeChallengeSalts(parts[2]);
        require(salts.valid(), "authentication challenge salts were invalid");
        require(PasswordAuth.validNonce(parts[3]), "authentication challenge nonce was invalid");
        return new Challenge(PasswordAuth.encodeVerifier(salts.currentSalt()),
                PasswordAuth.encodeVerifier(salts.scopedSalt()), parts[3], parts.length);
    }

    private static String answerChallenge(PeerServerSide server, TestConnection connection,
                                          InetAddress reportedAddress, String name, String password,
                                          Challenge challenge) throws Exception {
        AuthResponse response = response(name, password, challenge);
        server.join(connection.connectionId, reportedAddress, connection.socket.getLocalPort(), name,
                response.registrationMaterial, challenge.nonce, "", false, "");
        return receivePayload(connection, "JOIN_DENIED|");
    }

    private static AuthResponse response(String name, String password, Challenge challenge) {
        byte[] scopedSalt = PasswordAuth.decodeHex(challenge.scopedSalt);
        String legacyVerifier = PasswordAuth.verifier(name, password);
        String scopedVerifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT, scopedSalt);
        return new AuthResponse(scopedVerifier + ":" + legacyVerifier);
    }

    private static TestConnection connect(PeerTransport transport, InetAddress loopback) throws Exception {
        Socket socket = new Socket(loopback, transport.localPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(3_000);
        long deadline = System.currentTimeMillis() + 3_000;
        while (!transport.hasConnection(loopback, socket.getLocalPort()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        require(transport.hasConnection(loopback, socket.getLocalPort()),
                "TCP authentication test connection was not registered");
        return new TestConnection(socket, new DataInputStream(socket.getInputStream()),
                transport.connectionId(loopback, socket.getLocalPort()));
    }

    private static String receivePayload(TestConnection connection, String prefix) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(connection.input);
            if (frame == null) break;
            if (frame.message().startsWith(prefix)) return frame.message();
        }
        throw new IllegalStateException("Did not receive TCP frame starting with " + prefix);
    }

    private static void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Challenge(String currentSalt, String scopedSalt, String nonce, int payloadParts) { }
    private record AuthResponse(String registrationMaterial) { }

    private static final class TestConnection implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final ConnectionId connectionId;

        TestConnection(Socket socket, DataInputStream input, ConnectionId connectionId) {
            this.socket = socket;
            this.input = input;
            this.connectionId = connectionId;
        }

        @Override public void close() throws Exception {
            socket.close();
        }
    }
}
