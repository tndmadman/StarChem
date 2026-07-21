from pathlib import Path

path = Path(__file__).resolve().parents[1] / "src/main/java/com/tndmadman/rts/SessionRecoveryValidator.java"
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one SessionRecoveryValidator match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)

replace_once(
    "public final class SessionRecoveryValidator {\n    private SessionRecoveryValidator() { }",
    "public final class SessionRecoveryValidator {\n    private static final String TEST_SERVER_FINGERPRINT = \"33\".repeat(32);\n\n    private SessionRecoveryValidator() { }",
)

replace_once(
    '''            server.join(firstEndpoint, loopback, firstClient.getLocalPort(), "Recovery Client",\n                    PasswordAuth.verifier("Recovery Client", "validator-password"), false, "");\n            String firstWelcome = receivePayload(firstClient, "WELCOME|");''',
    '''            String firstWelcome = register(server, firstEndpoint, loopback, firstClient.getLocalPort(),\n                    "Recovery Client", "validator-password", firstClient);''',
)

replace_once(
    '''    server.join(longOfflineEndpoint, loopback, longOfflineClient.getLocalPort(),\n            "Recovery Client",\n            PasswordAuth.verifier("Recovery Client", "validator-password"), false, "");\n    String longOfflineWelcome = receivePayload(longOfflineClient, "WELCOME|");''',
    '''    String longOfflineWelcome = reclaim(server, longOfflineEndpoint, loopback,\n            longOfflineClient.getLocalPort(), "Recovery Client", "validator-password", longOfflineClient);''',
)

replace_once(
    '''            firstServer.join(firstEndpoint, loopback, firstClient.getLocalPort(), "Persistent Client",\n                    PasswordAuth.verifier("Persistent Client", "validator-password"), false, "");\n            String firstWelcome = receivePayload(firstClient, "WELCOME|");''',
    '''            String firstWelcome = register(firstServer, firstEndpoint, loopback, firstClient.getLocalPort(),\n                    "Persistent Client", "validator-password", firstClient);''',
)

replace_once(
    '''            String[] challengeParts = challenge.split("\\\\|", -1);\n            require(challengeParts.length >= 4 && PasswordAuth.decodeHex(challengeParts[2]).length == 16\n                            && PasswordAuth.validNonce(challengeParts[3]),\n                    "restored server did not issue a valid password challenge");\n            String wrongProof = PasswordAuth.challengeProof(\n                    PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(\n                            PasswordAuth.verifier("Persistent Client", "wrong-password")),\n                            PasswordAuth.decodeHex(challengeParts[2])),\n                    "Persistent Client", challengeParts[3]);''',
    '''            String[] challengeParts = challenge.split("\\\\|", -1);\n            PasswordAuth.ChallengeSalts challengeSalts = challengeParts.length < 4\n                    ? PasswordAuth.ChallengeSalts.EMPTY\n                    : PasswordAuth.decodeChallengeSalts(challengeParts[2]);\n            require(challengeSalts.valid() && PasswordAuth.validNonce(challengeParts[3]),\n                    "restored server did not issue a valid password challenge");\n            String wrongVerifier = PasswordAuth.scopedVerifier("Persistent Client", "wrong-password",\n                    TEST_SERVER_FINGERPRINT, challengeSalts.scopedSalt());\n            String wrongProof = PasswordAuth.challengeProof(\n                    PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(wrongVerifier),\n                            challengeSalts.currentSalt()),\n                    "Persistent Client", challengeParts[3]);''',
)

replace_once(
    '''    private static void applyInitialSync(PeerClientSide client, World world, String playerId, long sequence) {''',
    '''    private static String register(PeerServerSide server, ConnectionId connectionId, InetAddress address,\n                                   int port, String name, String password, Socket socket) throws Exception {\n        server.join(connectionId, address, port, name, false, "");\n        String required = receivePayload(socket, "AUTH_REQUIRED|");\n        String[] parts = required.split("\\\\|", -1);\n        require(parts.length == 3 && PasswordAuth.decodeHex(parts[2]).length == 16,\n                "server did not issue a scoped registration salt");\n        String verifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT,\n                PasswordAuth.decodeHex(parts[2]));\n        server.join(connectionId, address, port, name, verifier, false, "");\n        return receivePayload(socket, "WELCOME|");\n    }\n\n    private static String reclaim(PeerServerSide server, ConnectionId connectionId, InetAddress address,\n                                  int port, String name, String password, Socket socket) throws Exception {\n        server.join(connectionId, address, port, name, false, "");\n        String challenge = receivePayload(socket, "AUTH_CHALLENGE|");\n        String[] parts = challenge.split("\\\\|", -1);\n        PasswordAuth.ChallengeSalts salts = parts.length < 4\n                ? PasswordAuth.ChallengeSalts.EMPTY : PasswordAuth.decodeChallengeSalts(parts[2]);\n        require(salts.valid() && PasswordAuth.validNonce(parts[3]),\n                "server did not issue a scoped authentication challenge");\n        String verifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT,\n                salts.scopedSalt());\n        String proof = PasswordAuth.challengeProof(PasswordAuth.serverDigest(\n                PasswordAuth.decodeVerifier(verifier), salts.currentSalt()), name, parts[3]);\n        server.join(connectionId, address, port, name, "", parts[3], proof, false, "");\n        return receivePayload(socket, "WELCOME|");\n    }\n\n    private static void applyInitialSync(PeerClientSide client, World world, String playerId, long sequence) {''',
)

path.write_text(text, encoding="utf-8")
print("Adapted session recovery validation to the server-scoped authentication protocol.")
