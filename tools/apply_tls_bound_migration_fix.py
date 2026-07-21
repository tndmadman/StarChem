from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if new in text:
        print(f"Already patched: {path}")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/com/tndmadman/rts/TlsIdentity.java",
    '''    static SocketFactory clientSocketFactory() throws IOException {''',
    '''    static String serverFingerprint(Config config) throws IOException {
        try {
            KeyStore keys = loadOrCreateServerKeys(config);
            Certificate certificate = keys.getCertificate(KEY_ALIAS);
            if (certificate == null) throw new IOException("Server TLS certificate is missing.");
            return PasswordAuth.encodeVerifier(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not read server TLS identity: " + ex.getMessage(), ex);
        }
    }

    static SocketFactory clientSocketFactory() throws IOException {''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''    private final PerfStats perfStats;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();''',
    '''    private final PerfStats perfStats;
    private final String serverFingerprint;
    private final ConcurrentLinkedQueue<NetPacket> inbox = new ConcurrentLinkedQueue<>();''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''    private PeerTransport(boolean serverMode, ServerSocket serverSocket, InetSocketAddress expectedRemote,
                          Config config, SocketFactory clientSocketFactory, PerfStats perfStats) {
        this.serverMode = serverMode;
        this.serverSocket = serverSocket;
        this.expectedRemote = expectedRemote;
        this.config = config;
        this.clientSocketFactory = clientSocketFactory;
        this.perfStats = perfStats == null ? new PerfStats() : perfStats;
        this.compatibilityAccepted = serverMode;
    }''',
    '''    private PeerTransport(boolean serverMode, ServerSocket serverSocket, InetSocketAddress expectedRemote,
                          Config config, SocketFactory clientSocketFactory, PerfStats perfStats,
                          String serverFingerprint) {
        this.serverMode = serverMode;
        this.serverSocket = serverSocket;
        this.expectedRemote = expectedRemote;
        this.config = config;
        this.clientSocketFactory = clientSocketFactory;
        this.perfStats = perfStats == null ? new PerfStats() : perfStats;
        this.serverFingerprint = PasswordAuth.validVerifier(serverFingerprint)
                ? serverFingerprint.toLowerCase(Locale.ROOT) : "";
        this.compatibilityAccepted = serverMode;
    }''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''    static PeerTransport server(int port, PerfStats perfStats) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return new PeerTransport(true, socket, null, null, null, perfStats);
    }''',
    '''    static PeerTransport server(int port, PerfStats perfStats) throws IOException {
        return server(port, perfStats, "");
    }

    static PeerTransport server(int port, PerfStats perfStats, String serverFingerprint) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        return new PeerTransport(true, socket, null, null, null, perfStats, serverFingerprint);
    }''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''        ServerSocketFactory factory = TlsIdentity.serverSocketFactory(config);
        ServerSocket socket = factory.createServerSocket();''',
    '''        String serverFingerprint = TlsIdentity.serverFingerprint(config);
        ServerSocketFactory factory = TlsIdentity.serverSocketFactory(config);
        ServerSocket socket = factory.createServerSocket();''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''        return new PeerTransport(true, socket, null, config, null, perfStats);''',
    '''        return new PeerTransport(true, socket, null, config, null, perfStats, serverFingerprint);''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''        return new PeerTransport(false, null,
                new InetSocketAddress(remote.getAddress(), remote.getPort()), null, null, perfStats);''',
    '''        return new PeerTransport(false, null,
                new InetSocketAddress(remote.getAddress(), remote.getPort()), null, null, perfStats, "");''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''        return new PeerTransport(false, null,
                new InetSocketAddress(config.serverAddress.getAddress(), config.serverAddress.getPort()),
                config, TlsIdentity.clientSocketFactory(), perfStats);''',
    '''        return new PeerTransport(false, null,
                new InetSocketAddress(config.serverAddress.getAddress(), config.serverAddress.getPort()),
                config, TlsIdentity.clientSocketFactory(), perfStats, "");''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerTransport.java",
    '''    boolean connected() {''',
    '''    String serverFingerprint() { return serverFingerprint; }

    boolean connected() {''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PasswordAuth.java",
    '''    static String sessionProof(byte[] tokenDigest, String playerId, String nonce) {
        return keyedProof("StarChem session resume v1", tokenDigest, Config.clean(playerId), nonce);
    }''',
    '''    static String upgradeProof(byte[] legacyKey, String playerName, String nonce,
                               String serverFingerprint, String scopedVerifier) {
        if (legacyKey == null || legacyKey.length == 0 || !validNonce(nonce)
                || !validVerifier(serverFingerprint) || !validVerifier(scopedVerifier)) return "";
        String material = "StarChem auth upgrade v2|"
                + Config.clean(playerName).toLowerCase(java.util.Locale.ROOT) + "|" + nonce + "|"
                + serverFingerprint.toLowerCase(java.util.Locale.ROOT) + "|"
                + scopedVerifier.toLowerCase(java.util.Locale.ROOT);
        return keyedProofMaterial(legacyKey, material);
    }

    static boolean upgradeProofMatches(byte[] legacyKey, String playerName, String nonce,
                                       String serverFingerprint, String scopedVerifier, String proof) {
        if (!validVerifier(proof)) return false;
        String expected = upgradeProof(legacyKey, playerName, nonce, serverFingerprint, scopedVerifier);
        return !expected.isBlank() && MessageDigest.isEqual(decodeVerifier(expected), decodeVerifier(proof));
    }

    static String sessionProof(byte[] tokenDigest, String playerId, String nonce) {
        return keyedProof("StarChem session resume v1", tokenDigest, Config.clean(playerId), nonce);
    }''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PasswordAuth.java",
    '''    private static String keyedProof(String domain, byte[] key, String identity, String nonce) {
        if (key == null || key.length == 0 || !validNonce(nonce)) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((domain + "|" + identity + "|" + nonce)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", ex);
        }
    }''',
    '''    private static String keyedProof(String domain, byte[] key, String identity, String nonce) {
        if (key == null || key.length == 0 || !validNonce(nonce)) return "";
        return keyedProofMaterial(key, domain + "|" + identity + "|" + nonce);
    }

    private static String keyedProofMaterial(byte[] key, String material) {
        if (key == null || key.length == 0 || material == null || material.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", ex);
        }
    }''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerClientSide.java",
    '''            String legacyProof = passwordVerifier.isBlank() ? ""
                    : PasswordAuth.challengeProof(PasswordAuth.serverDigest(
                    PasswordAuth.decodeVerifier(passwordVerifier), salt), config.playerName, authChallengeNonce);''',
    '''            String legacyProof = "";
            if (!passwordVerifier.isBlank()) {
                byte[] legacyKey = PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(passwordVerifier), salt);
                legacyProof = !scopedPasswordVerifier.isBlank() && PasswordAuth.validVerifier(authServerFingerprint)
                        ? PasswordAuth.upgradeProof(legacyKey, config.playerName, authChallengeNonce,
                        authServerFingerprint, scopedPasswordVerifier)
                        : PasswordAuth.challengeProof(legacyKey, config.playerName, authChallengeNonce);
            }''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerServerSide.java",
    '''    private final AuthDecoySaltStore authDecoySalts;
    private ServerAdmissionGate admissionGate = ServerAdmissionGate.open();''',
    '''    private final AuthDecoySaltStore authDecoySalts;
    private final String authenticationServerFingerprint;
    private ServerAdmissionGate admissionGate = ServerAdmissionGate.open();''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerServerSide.java",
    '''        this.transport = transport;
        this.authDecoySalts = new AuthDecoySaltStore(config);
        PlayerRegistry.activate(world);''',
    '''        this.transport = transport;
        this.authDecoySalts = new AuthDecoySaltStore(config);
        this.authenticationServerFingerprint = transport == null ? "" : transport.serverFingerprint();
        PlayerRegistry.activate(world);''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerServerSide.java",
    '''        boolean legacyProofMatches = PasswordAuth.proofMatches(proofKey, challengeName, proofNonce, legacyProof);
        String playerId = challenge == null ? "" : challenge.playerId;''',
    '''        boolean boundUpgradeProof = challenge != null && challenge.authVersion < PasswordAuth.AUTH_VERSION_V2
                && PasswordAuth.validVerifier(authenticationServerFingerprint)
                && PasswordAuth.validVerifier(credential.scopedVerifier);
        boolean localLegacyProof = !config.dedicatedServerMode() && address != null && address.isLoopbackAddress();
        boolean legacyProofMatches = boundUpgradeProof
                ? PasswordAuth.upgradeProofMatches(proofKey, challengeName, proofNonce,
                authenticationServerFingerprint, credential.scopedVerifier, legacyProof)
                : localLegacyProof && PasswordAuth.proofMatches(proofKey, challengeName, proofNonce, legacyProof);
        String playerId = challenge == null ? "" : challenge.playerId;''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/PeerServerSide.java",
    '''        if (challenge.authVersion < PasswordAuth.AUTH_VERSION_V2 && config.dedicatedServerMode()) {
            byte[] scopedVerifier = PasswordAuth.decodeVerifier(credential.scopedVerifier);
            if (scopedVerifier.length == 0 || challenge.scopedSalt.length != 16) {
                denyAuthentication(connectionId, "Password upgrade required.");
                return;
            }
            session.passwordSalt = PasswordAuth.versionedPasswordSalt(challenge.scopedSalt);
            session.passwordDigest = PasswordAuth.serverDigest(scopedVerifier, challenge.scopedSalt);
        }''',
    '''        if (challenge.authVersion < PasswordAuth.AUTH_VERSION_V2 && boundUpgradeProof) {
            byte[] scopedVerifier = PasswordAuth.decodeVerifier(credential.scopedVerifier);
            if (scopedVerifier.length == 0 || challenge.scopedSalt.length != 16) {
                denyAuthentication(connectionId, "Password upgrade required.");
                return;
            }
            session.passwordSalt = PasswordAuth.versionedPasswordSalt(challenge.scopedSalt);
            session.passwordDigest = PasswordAuth.serverDigest(scopedVerifier, challenge.scopedSalt);
        } else if (challenge.authVersion < PasswordAuth.AUTH_VERSION_V2 && config.dedicatedServerMode()) {
            denyAuthentication(connectionId, "Password upgrade required.");
            return;
        }''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/AuthenticationEnumerationValidator.java",
    '''        PeerTransport transport = PeerTransport.server(0, new PerfStats());''',
    '''        PeerTransport transport = PeerTransport.server(0, new PerfStats(), TEST_SERVER_FINGERPRINT);''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/AuthenticationEnumerationValidator.java",
    '''        require(!first.equals(second), "two TLS server identities produced interchangeable password verifiers");
        byte[] upgraded = PasswordAuth.upgradeSalt(salt);''',
    '''        require(!first.equals(second), "two TLS server identities produced interchangeable password verifiers");
        byte[] legacyKey = PasswordAuth.serverDigest(
                PasswordAuth.decodeVerifier(PasswordAuth.verifier("Scoped Player", "same-password")), salt);
        String nonce = PasswordAuth.newNonce();
        String firstProof = PasswordAuth.upgradeProof(legacyKey, "Scoped Player", nonce,
                TEST_SERVER_FINGERPRINT, first);
        require(PasswordAuth.upgradeProofMatches(legacyKey, "Scoped Player", nonce,
                        TEST_SERVER_FINGERPRINT, first, firstProof),
                "server A did not accept its TLS-bound migration proof");
        require(!PasswordAuth.upgradeProofMatches(legacyKey, "Scoped Player", nonce,
                        OTHER_SERVER_FINGERPRINT, first, firstProof),
                "server A migration proof replayed against server B");
        require(!PasswordAuth.upgradeProofMatches(legacyKey, "Scoped Player", nonce,
                        TEST_SERVER_FINGERPRINT, second, firstProof),
                "migration proof accepted a different server-scoped verifier");
        byte[] upgraded = PasswordAuth.upgradeSalt(salt);''',
)

replace_once(
    "src/main/java/com/tndmadman/rts/AuthenticationEnumerationValidator.java",
    '''        String legacyProof = PasswordAuth.challengeProof(PasswordAuth.serverDigest(
                PasswordAuth.decodeVerifier(legacyVerifier), currentSalt), name, challenge.nonce);''',
    '''        String legacyProof = PasswordAuth.upgradeProof(PasswordAuth.serverDigest(
                PasswordAuth.decodeVerifier(legacyVerifier), currentSalt), name, challenge.nonce,
                TEST_SERVER_FINGERPRINT, scopedVerifier);''',
)

print("Applied TLS-bound legacy migration proof fix.")
