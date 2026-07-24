from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one exact match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text, pattern, replacement, label):
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: expected one regex match, found {count}")
    return updated


# Centralize comparisons so persisted digests are verifiers, never protocol proof keys.
path = "src/main/java/com/tndmadman/rts/PasswordAuth.java"
text = read(path)
old = '''    static byte[] serverDigest(byte[] verifier, byte[] salt) {
        if (verifier == null || verifier.length == 0 || salt == null || salt.length == 0) return new byte[0];
        try {
            PBEKeySpec spec = new PBEKeySpec(encodeVerifier(verifier).toCharArray(), salt, KEY_ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable.", ex);
        }
    }
'''
new = old + '''
    static boolean passwordCredentialMatches(byte[] storedDigest, byte[] suppliedVerifier, byte[] salt) {
        if (storedDigest == null || storedDigest.length == 0 || suppliedVerifier == null
                || suppliedVerifier.length == 0 || salt == null || salt.length == 0) return false;
        byte[] candidate = serverDigest(suppliedVerifier, salt);
        try {
            return MessageDigest.isEqual(storedDigest, candidate);
        } finally {
            Arrays.fill(candidate, (byte)0);
        }
    }

    static boolean sessionTokenMatches(byte[] storedDigest, String suppliedToken) {
        if (storedDigest == null || storedDigest.length == 0 || suppliedToken == null || suppliedToken.isBlank()) {
            return false;
        }
        byte[] candidate = tokenDigest(suppliedToken);
        try {
            return MessageDigest.isEqual(storedDigest, candidate);
        } finally {
            Arrays.fill(candidate, (byte)0);
        }
    }
'''
text = replace_once(text, old, new, "PasswordAuth verifier helpers")
write(path, text)


# Send the client-held verifier and random token only after pinned TLS is established.
path = "src/main/java/com/tndmadman/rts/PeerClientSide.java"
text = read(path)
old = '''        if (!authChallengeNonce.isBlank() && !authChallengeSalt.isBlank()) {
            byte[] salt = PasswordAuth.decodeHex(authChallengeSalt);
            String scopedProof = scopedPasswordVerifier.isBlank() ? ""
                    : PasswordAuth.challengeProof(PasswordAuth.serverDigest(
                    PasswordAuth.decodeVerifier(scopedPasswordVerifier), salt), config.playerName, authChallengeNonce);
            String legacyProof = "";
            if (!passwordVerifier.isBlank()) {
                byte[] legacyKey = PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(passwordVerifier), salt);
                legacyProof = !scopedPasswordVerifier.isBlank() && PasswordAuth.validVerifier(authServerFingerprint)
                        ? PasswordAuth.upgradeProof(legacyKey, config.playerName, authChallengeNonce,
                        authServerFingerprint, scopedPasswordVerifier)
                        : PasswordAuth.challengeProof(legacyKey, config.playerName, authChallengeNonce);
            }
            String proof = scopedProof.isBlank() ? legacyProof : scopedProof;
            String registration = scopedPasswordVerifier;
            if (!legacyProof.isBlank() && !registration.isBlank()) registration += ":" + legacyProof;
            return message + (registration.isBlank() ? "" : "|AUTH_REGISTER|" + cleanPacketPart(registration))
                    + "|AUTH_PROOF_NONCE|" + cleanPacketPart(authChallengeNonce)
                    + "|AUTH_PROOF|" + cleanPacketPart(proof);
        }
'''
new = '''        if (!authChallengeNonce.isBlank() && !authChallengeSalt.isBlank()) {
            String credential = scopedPasswordVerifier;
            if (!passwordVerifier.isBlank() && !credential.isBlank()) credential += ":" + passwordVerifier;
            return message + (credential.isBlank() ? "" : "|AUTH_REGISTER|" + cleanPacketPart(credential))
                    + "|AUTH_PROOF_NONCE|" + cleanPacketPart(authChallengeNonce);
        }
'''
text = replace_once(text, old, new, "PeerClientSide password challenge response")
old = '''    private String resumeMessage() {
        String request = config.devMode ? "DEV" : "NODEV";
        String devToken = config.devMode ? config.devToken : "";
        String message = "RESUME|" + cleanPacketPart(localPlayerId) + "||" + request + "|" + devToken;
        if (!sessionChallengeNonce.isBlank() && !sessionToken.isBlank()) {
            String proof = PasswordAuth.sessionProof(PasswordAuth.tokenDigest(sessionToken), localPlayerId, sessionChallengeNonce);
            return message + "|SESSION_PROOF_NONCE|" + cleanPacketPart(sessionChallengeNonce)
                    + "|SESSION_PROOF|" + cleanPacketPart(proof);
        }
        return message;
    }
'''
new = '''    private String resumeMessage() {
        String request = config.devMode ? "DEV" : "NODEV";
        String devToken = config.devMode ? config.devToken : "";
        return "RESUME|" + cleanPacketPart(localPlayerId) + "|" + cleanPacketPart(sessionToken)
                + "|" + request + "|" + devToken;
    }
'''
text = replace_once(text, old, new, "PeerClientSide raw token resume")
write(path, text)


# Verify supplied client material by hashing it again; never use save fields as HMAC keys.
path = "src/main/java/com/tndmadman/rts/PeerServerSide.java"
text = read(path)
old = '''        if (PasswordAuth.validVerifier(proof) && PasswordAuth.validNonce(proofNonce)
                && authChallenges.containsKey(connectionId)) {
            reclaimByProof(connectionId, address, port, cleanName, passwordVerifier, proofNonce, proof, requestedDev, suppliedDevToken);
            return;
        }
'''
new = '''        CredentialResponse credential = CredentialResponse.parse(passwordVerifier);
        if (PasswordAuth.validNonce(proofNonce) && authChallenges.containsKey(connectionId)
                && PasswordAuth.validVerifier(credential.scopedVerifier)) {
            reclaimByCredential(connectionId, address, port, cleanName, passwordVerifier, proofNonce,
                    requestedDev, suppliedDevToken);
            return;
        }
'''
text = replace_once(text, old, new, "PeerServerSide credential dispatch")
old = '''        CredentialResponse credential = CredentialResponse.parse(passwordVerifier);
        byte[] passwordVerifierBytes = PasswordAuth.decodeVerifier(credential.scopedVerifier);
'''
new = '''        byte[] passwordVerifierBytes = PasswordAuth.decodeVerifier(credential.scopedVerifier);
'''
text = replace_once(text, old, new, "PeerServerSide registration credential reuse")

new_issue = '''    private void issueAuthChallenge(ConnectionId connectionId, String cleanName, PlayerSession session) {
        if (connectionId == null || !connectionId.valid()) return;
        byte[] decoySalt = authDecoySalts.saltFor(cleanName);
        boolean retained = session != null
                && session.passwordSalt != null && session.passwordSalt.length > 0
                && session.passwordDigest != null && session.passwordDigest.length > 0;
        int authVersion = retained ? PasswordAuth.passwordVersion(session.passwordSalt) : PasswordAuth.AUTH_VERSION_V2;
        byte[] currentSalt = retained ? PasswordAuth.digestSalt(session.passwordSalt) : decoySalt;
        byte[] scopedSalt = authVersion >= PasswordAuth.AUTH_VERSION_V2
                ? currentSalt.clone() : PasswordAuth.upgradeSalt(currentSalt);
        String playerId = retained ? session.playerId : "";
        if (retained) Arrays.fill(decoySalt, (byte)0);
        String nonce = PasswordAuth.newNonce();
        authChallenges.put(connectionId, new AuthChallenge(cleanName, nonce, playerId, authVersion,
                scopedSalt, System.currentTimeMillis()));
        transport.sendOrdered("AUTH_CHALLENGE|" + packetPart(cleanName) + "|"
                + PasswordAuth.encodeChallengeSalts(currentSalt, scopedSalt) + "|" + nonce, connectionId);
    }
'''
text = replace_regex_once(text,
    r'    private void issueAuthChallenge\(ConnectionId connectionId, String cleanName, PlayerSession session\) \{.*?\n    \}\n(?=\n    private void reclaimByProof)',
    new_issue,
    "PeerServerSide challenge state")

new_reclaim = '''    private void reclaimByCredential(ConnectionId connectionId, InetAddress address, int port, String cleanName,
                                     String credentialMaterial, String challengeNonce,
                                     boolean requestedDev, String suppliedDevToken) {
        long now = System.currentTimeMillis();
        AuthChallenge challenge = authChallenges.remove(connectionId);
        CredentialResponse credential = CredentialResponse.parse(credentialMaterial);
        boolean contextMatches = challenge != null
                && challenge.nonce.equals(challengeNonce)
                && normalizedName(cleanName).equals(normalizedName(challenge.name))
                && now - challenge.createdAt <= AUTH_CHALLENGE_MS;
        String playerId = challenge == null ? "" : challenge.playerId;
        PlayerSession session = sessions.get(playerId);
        boolean sessionMatches = session != null && !playerId.isBlank()
                && normalizedName(session.name).equals(normalizedName(challenge.name));
        byte[] suppliedVerifier = challenge != null && challenge.authVersion < PasswordAuth.AUTH_VERSION_V2
                ? PasswordAuth.decodeVerifier(credential.legacyVerifier)
                : PasswordAuth.decodeVerifier(credential.scopedVerifier);
        byte[] digestSalt = session == null ? new byte[0] : PasswordAuth.digestSalt(session.passwordSalt);
        boolean credentialMatches = contextMatches && sessionMatches
                && PasswordAuth.passwordCredentialMatches(session.passwordDigest, suppliedVerifier, digestSalt);
        Arrays.fill(suppliedVerifier, (byte)0);
        Arrays.fill(digestSalt, (byte)0);
        if (!credentialMatches) {
            denyAuthentication(connectionId, "Password rejected.");
            return;
        }
        if (challenge.authVersion < PasswordAuth.AUTH_VERSION_V2) {
            byte[] scopedVerifier = PasswordAuth.decodeVerifier(credential.scopedVerifier);
            if (scopedVerifier.length == 0 || challenge.scopedSalt.length != 16) {
                Arrays.fill(scopedVerifier, (byte)0);
                denyAuthentication(connectionId, "Password upgrade required.");
                return;
            }
            session.passwordSalt = PasswordAuth.versionedPasswordSalt(challenge.scopedSalt);
            session.passwordDigest = PasswordAuth.serverDigest(scopedVerifier, challenge.scopedSalt);
            Arrays.fill(scopedVerifier, (byte)0);
        }
        bindReclaimedSession(connectionId, address, port, session, requestedDev, suppliedDevToken);
    }
'''
text = replace_regex_once(text,
    r'    private void reclaimByProof\(ConnectionId connectionId, InetAddress address, int port, String cleanName,.*?\n    \}\n(?=\n    private void reclaimByPassword)',
    new_reclaim,
    "PeerServerSide credential verification")

new_resume = '''    boolean resume(ConnectionId connectionId, InetAddress address, int port, String playerId, String token,
                   String proofNonce, String proof, boolean requestedDev, String suppliedDevToken) {
        return resume(connectionId, address, port, playerId, token, requestedDev, suppliedDevToken);
    }
'''
text = replace_regex_once(text,
    r'    boolean resume\(ConnectionId connectionId, InetAddress address, int port, String playerId, String token,\n\s+String proofNonce, String proof, boolean requestedDev, String suppliedDevToken\) \{.*?\n    \}\n(?=\n    private void issueSessionChallenge)',
    new_resume,
    "PeerServerSide raw token dispatch")

old = '''    private boolean tokenMatches(PlayerSession session, String token, ConnectionId connectionId, long now) {
        if (session == null || token == null || token.isBlank()) return false;
        byte[] candidate = digestToken(token);
        if (MessageDigest.isEqual(session.tokenDigest, candidate)) return true;
        return session.connected && connectionId != null && connectionId.equals(session.connectionId)
                && session.previousTokenDigest != null && now <= session.previousTokenValidUntil
                && MessageDigest.isEqual(session.previousTokenDigest, candidate);
    }
'''
new = '''    private boolean tokenMatches(PlayerSession session, String token, ConnectionId connectionId, long now) {
        if (session == null || token == null || token.isBlank()) return false;
        if (PasswordAuth.sessionTokenMatches(session.tokenDigest, token)) return true;
        boolean sameConnection = session.connected && connectionId != null && connectionId.equals(session.connectionId);
        return session.previousTokenDigest != null && now <= session.previousTokenValidUntil
                && (!session.connected || sameConnection)
                && PasswordAuth.sessionTokenMatches(session.previousTokenDigest, token);
    }
'''
text = replace_once(text, old, new, "PeerServerSide token verifier")
old = '''    private record AuthChallenge(String name, String nonce, byte[] proofKey, String playerId, int authVersion,
                                 byte[] scopedSalt, long createdAt) { }
'''
new = '''    private record AuthChallenge(String name, String nonce, String playerId, int authVersion,
                                 byte[] scopedSalt, long createdAt) { }
'''
text = replace_once(text, old, new, "PeerServerSide challenge record")
old = '''    private record CredentialResponse(String scopedVerifier, String legacyProof) {
        static CredentialResponse parse(String value) {
            String raw = value == null ? "" : value.trim();
            int separator = raw.indexOf(':');
            String scoped = separator < 0 ? raw : raw.substring(0, separator);
            String legacy = separator < 0 ? "" : raw.substring(separator + 1);
            return new CredentialResponse(PasswordAuth.validVerifier(scoped) ? scoped.toLowerCase(Locale.ROOT) : "",
                    PasswordAuth.validVerifier(legacy) ? legacy.toLowerCase(Locale.ROOT) : "");
        }
    }
'''
new = '''    private record CredentialResponse(String scopedVerifier, String legacyVerifier) {
        static CredentialResponse parse(String value) {
            String raw = value == null ? "" : value.trim();
            int separator = raw.indexOf(':');
            String scoped = separator < 0 ? raw : raw.substring(0, separator);
            String legacy = separator < 0 ? "" : raw.substring(separator + 1);
            return new CredentialResponse(PasswordAuth.validVerifier(scoped) ? scoped.toLowerCase(Locale.ROOT) : "",
                    PasswordAuth.validVerifier(legacy) ? legacy.toLowerCase(Locale.ROOT) : "");
        }
    }
'''
text = replace_once(text, old, new, "PeerServerSide credential record")
write(path, text)


# Advance compatibility because authentication packet semantics changed.
path = "src/main/java/com/tndmadman/rts/MultiplayerCompatibility.java"
text = read(path)
text = replace_once(text, "    static final int PROTOCOL_VERSION = 7;\n",
                    "    static final int PROTOCOL_VERSION = 8;\n", "protocol version")
write(path, text)


# Update authentication regression coverage to send raw TLS-protected verifiers.
path = "src/main/java/com/tndmadman/rts/AuthenticationEnumerationValidator.java"
text = read(path)
new_validate = '''    private static void validateServerScopedVerifier() {
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
'''
text = replace_regex_once(text,
    r'    private static void validateServerScopedVerifier\(\) \{.*?\n    \}\n(?=\n    private static void validateAttemptLimiter)',
    new_validate,
    "AuthenticationEnumerationValidator credential validation")
text = replace_once(text,
'''                        correct.registrationMaterial, repeatedExisting.nonce, correct.proof, false, "");
''',
'''                        correct.registrationMaterial, repeatedExisting.nonce, "", false, "");
''',
"AuthenticationEnumerationValidator correct credential")
text = replace_once(text,
'''                response.registrationMaterial, challenge.nonce, response.proof, false, "");
''',
'''                response.registrationMaterial, challenge.nonce, "", false, "");
''',
"AuthenticationEnumerationValidator rejected credential")
new_response = '''    private static AuthResponse response(String name, String password, Challenge challenge) {
        byte[] scopedSalt = PasswordAuth.decodeHex(challenge.scopedSalt);
        String legacyVerifier = PasswordAuth.verifier(name, password);
        String scopedVerifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT, scopedSalt);
        return new AuthResponse(scopedVerifier + ":" + legacyVerifier);
    }
'''
text = replace_regex_once(text,
    r'    private static AuthResponse response\(String name, String password, Challenge challenge\) \{.*?\n    \}\n(?=\n    private static TestConnection connect)',
    new_response,
    "AuthenticationEnumerationValidator response")
text = replace_once(text,
'''    private record AuthResponse(String registrationMaterial, String proof) { }
''',
'''    private record AuthResponse(String registrationMaterial) { }
''',
"AuthenticationEnumerationValidator response record")
write(path, text)


# Replace the old proof-reconnect validator with direct raw-token recovery coverage.
path = "src/main/java/com/tndmadman/rts/PreviousTokenProofRecoveryValidator.java"
write(path, r'''package com.tndmadman.rts;

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
''')


# Validate directly against values extracted from a real save archive.
path = "src/main/java/com/tndmadman/rts/SavedCredentialReplayValidator.java"
write(path, r'''package com.tndmadman.rts;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Proves that authentication fields copied from a save cannot authenticate as a player. */
public final class SavedCredentialReplayValidator {
    private static final String FINGERPRINT = "77".repeat(32);
    private static final String PLAYER_NAME = "Saved Credential Player";
    private static final String PASSWORD = "saved-credential-password";
    private static final String TOKEN = "saved-token-" + "x".repeat(48);

    private SavedCredentialReplayValidator() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("starchem-saved-credential-");
        try {
            Config config = Config.dedicatedServer("Saved Credential Host", 0, false, false, Set.of(),
                    StarSystems.DEFAULT_SYSTEM_ID, "", 1, directory, "credential-replay", 0, 1, true);
            World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);

            byte[] storedSalt = PasswordAuth.newVersionedPasswordSalt();
            byte[] digestSalt = PasswordAuth.digestSalt(storedSalt);
            String scopedVerifier = PasswordAuth.scopedVerifier(PLAYER_NAME, PASSWORD, FINGERPRINT, digestSalt);
            byte[] passwordDigest = PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(scopedVerifier), digestSalt);
            byte[] tokenDigest = PasswordAuth.tokenDigest(TOKEN);
            PersistentPlayerSession session = new PersistentPlayerSession("P1", PLAYER_NAME, 0x50BEFF,
                    storedSalt, passwordDigest, tokenDigest, new byte[0], 0);

            ServerSaveStore store = new ServerSaveStore(directory, "credential-replay", 1);
            store.save(world, config, "credential-replay-validator", List.of(session));
            Path save = directory.resolve("credential-replay-current.starchem-save");
            Map<String,Object> savedSession = readSavedSession(save);
            byte[] extractedPasswordDigest = PasswordAuth.decodeVerifier(
                    ServerSaveStore.string(savedSession, "passwordVerifierSha256", ""));
            byte[] extractedTokenDigest = Base64.getUrlDecoder().decode(
                    ServerSaveStore.string(savedSession, "tokenDigestSha256", ""));
            byte[] extractedSalt = PasswordAuth.digestSalt(PasswordAuth.decodeHex(
                    ServerSaveStore.string(savedSession, "passwordSalt", "")));

            require(PasswordAuth.passwordCredentialMatches(extractedPasswordDigest,
                            PasswordAuth.decodeVerifier(scopedVerifier), extractedSalt),
                    "correct scoped credential was rejected");
            require(!PasswordAuth.passwordCredentialMatches(extractedPasswordDigest,
                            extractedPasswordDigest, extractedSalt),
                    "password digest copied from the save authenticated as a client credential");
            require(PasswordAuth.sessionTokenMatches(extractedTokenDigest, TOKEN),
                    "correct raw session token was rejected");
            String copiedDigestAsToken = Base64.getUrlEncoder().withoutPadding().encodeToString(extractedTokenDigest);
            require(!PasswordAuth.sessionTokenMatches(extractedTokenDigest, copiedDigestAsToken),
                    "session digest copied from the save authenticated as a raw token");

            System.out.println("StarChem saved credential replay validation passed.");
        } finally {
            deleteTree(directory);
        }
    }

    private static Map<String,Object> readSavedSession(Path save) throws Exception {
        try (ZipFile zip = new ZipFile(save.toFile(), StandardCharsets.UTF_8)) {
            var entry = zip.getEntry("players.json");
            require(entry != null, "players.json was missing from the save");
            try (InputStream input = zip.getInputStream(entry)) {
                Object parsed = MiniJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                Map<String,Object> players = ServerSaveStore.object(parsed);
                List<Object> sessions = ServerSaveStore.list(players.get("sessions"));
                require(sessions.size() == 1, "save did not contain exactly one retained session");
                return ServerSaveStore.object(sessions.get(0));
            }
        }
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
}
''')


path = "build.gradle"
text = read(path)
addition = '''

tasks.register('validateSavedCredentialReplay', JavaExec) {
    group = 'verification'
    description = 'Validate that authentication fields copied from a server save cannot authenticate.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.SavedCredentialReplayValidator'
}

tasks.named('check') {
    dependsOn tasks.named('validateSavedCredentialReplay')
}
'''
if "validateSavedCredentialReplay" in text:
    raise RuntimeError("build.gradle already contains validateSavedCredentialReplay")
write(path, text.rstrip() + addition + "\n")


# Fail the patch if the active client/server paths still derive proofs from saved digests.
client = read("src/main/java/com/tndmadman/rts/PeerClientSide.java")
server = read("src/main/java/com/tndmadman/rts/PeerServerSide.java")
if "PasswordAuth.sessionProof(" in client or "PasswordAuth.challengeProof(" in client:
    raise RuntimeError("client authentication path still creates digest-keyed proofs")
if "session.passwordDigest.clone()" in server or "reclaimByProof(" in server:
    raise RuntimeError("server authentication path still treats saved password digests as proof keys")
if "PROTOCOL_VERSION = 8" not in read("src/main/java/com/tndmadman/rts/MultiplayerCompatibility.java"):
    raise RuntimeError("multiplayer protocol was not advanced")

print("Applied saved-authentication-key repair.")
