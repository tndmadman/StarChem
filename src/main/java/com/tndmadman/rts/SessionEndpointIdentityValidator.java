package com.tndmadman.rts;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

/** Regression coverage for canonical hostname session, authentication, and TLS trust storage. */
public final class SessionEndpointIdentityValidator {
    private SessionEndpointIdentityValidator() { }

    public static void main(String[] args) throws Exception {
        Path store = Files.createTempFile("starchem-endpoint-identity-", ".properties");
        Files.deleteIfExists(store);
        System.setProperty("starchem.sessionStore", store.toString());
        try {
            validateCanonicalHostnameStorage(store);
            validateCurrentAddressMigration(store);
            validateHistoricalAddressMigration(store);
            validateAmbiguousHistoryIsNotGuessed(store);
            System.out.println("StarChem canonical endpoint identity validation passed.");
        } finally {
            System.clearProperty("starchem.sessionStore");
            Files.deleteIfExists(store);
            Files.deleteIfExists(store.resolveSibling(store.getFileName() + ".previous"));
            Files.deleteIfExists(store.resolveSibling(store.getFileName() + ".lock"));
        }
    }

    private static void validateCanonicalHostnameStorage(Path store) throws Exception {
        clearStore(store);
        int port = 51234;
        Config upper = Config.join("Endpoint Commander", "LOCALHOST", port, false);
        Config lower = Config.join("Endpoint Commander", "localhost", port, false);
        Config direct = Config.join("Endpoint Commander", "127.0.0.1", port, false);
        String token = "A".repeat(43);
        String fingerprint = "11".repeat(32);
        String salt = "22".repeat(16);
        String verifier = "33".repeat(32);

        SessionTokenStore.save(upper, "P7", token);
        SessionTokenStore.saveServerFingerprint(upper, fingerprint);
        SessionTokenStore.saveScopedCredential(upper, fingerprint, salt, verifier);

        Properties saved = read(store);
        String canonicalSession = encode("localhost:" + port + "|endpoint commander");
        String canonicalTrust = encode("localhost:" + port);
        require(saved.containsKey(canonicalSession), "session was not stored under the canonical hostname");
        require(saved.containsKey("auth-v2." + canonicalSession),
                "server-scoped credentials were not stored under the canonical hostname");
        require(fingerprint.equals(saved.getProperty("tls-server." + canonicalTrust)),
                "TLS trust was not stored under the canonical hostname");

        require("P7".equals(SessionTokenStore.load(lower).playerId()),
                "hostname case created a separate saved session");
        require(fingerprint.equals(SessionTokenStore.serverFingerprint(lower)),
                "hostname case created a separate TLS trust identity");
        require(SessionTokenStore.scopedCredential(lower).valid(),
                "hostname case created separate server-scoped credentials");
        require(!SessionTokenStore.load(direct).valid(),
                "a direct IP connection incorrectly shared the hostname session identity");

        SessionTokenStore.clearServerFingerprint(lower);
        SessionTokenStore.clear(lower);
    }

    private static void validateCurrentAddressMigration(Path store) throws Exception {
        clearStore(store);
        int port = 51235;
        Config config = Config.join("Current Address Commander", "localhost", port, false);
        require(config.serverAddress.getAddress() != null, "localhost did not resolve for migration validation");
        String resolved = config.serverAddress.getAddress().getHostAddress().toLowerCase(java.util.Locale.ROOT);
        String legacyEndpoint = resolved + ':' + port;
        String legacySession = encode(legacyEndpoint + "|current address commander");
        String legacyTrust = encode(legacyEndpoint);
        String token = "B".repeat(43);
        String fingerprint = "44".repeat(32);
        String salt = "55".repeat(16);
        String verifier = "66".repeat(32);

        Properties legacy = new Properties();
        legacy.setProperty(legacySession, "P8|" + token + "|");
        legacy.setProperty("auth-v2." + legacySession, fingerprint + '|' + salt + '|' + verifier);
        legacy.setProperty("tls-server." + legacyTrust, fingerprint);
        write(store, legacy);

        require("P8".equals(SessionTokenStore.load(config).playerId()),
                "current resolved-IP session was not migrated");
        require(fingerprint.equals(SessionTokenStore.serverFingerprint(config)),
                "current resolved-IP TLS pin was not migrated");
        require(SessionTokenStore.scopedCredential(config).valid(),
                "current resolved-IP scoped credentials were not migrated");

        String canonicalSession = encode("localhost:" + port + "|current address commander");
        String canonicalTrust = encode("localhost:" + port);
        Properties migrated = read(store);
        require(migrated.containsKey(canonicalSession), "canonical session was not persisted after migration");
        require(migrated.containsKey("auth-v2." + canonicalSession),
                "canonical scoped credential was not persisted after migration");
        require(fingerprint.equals(migrated.getProperty("tls-server." + canonicalTrust)),
                "canonical TLS pin was not persisted after migration");

        SessionTokenStore.clearServerFingerprint(config);
        SessionTokenStore.clear(config);
        require(!read(store).containsKey(legacySession), "clearing left the migrated resolved-IP session behind");
    }

    private static void validateHistoricalAddressMigration(Path store) throws Exception {
        clearStore(store);
        int port = 51236;
        Config config = Config.join("Historical Commander", "localhost", port, false);
        String oldEndpoint = "192.0.2.44:" + port;
        String oldSession = encode(oldEndpoint + "|historical commander");
        String oldTrust = encode(oldEndpoint);
        String token = "C".repeat(43);
        String fingerprint = "77".repeat(32);
        String salt = "88".repeat(16);
        String verifier = "99".repeat(32);

        Properties legacy = new Properties();
        legacy.setProperty(oldSession, "P9|" + token + "|");
        legacy.setProperty("auth-v2." + oldSession, fingerprint + '|' + salt + '|' + verifier);
        legacy.setProperty("tls-server." + oldTrust, fingerprint);
        write(store, legacy);

        require("P9".equals(SessionTokenStore.load(config).playerId()),
                "unique historical-IP session was not migrated after address rotation");
        require(fingerprint.equals(SessionTokenStore.serverFingerprint(config)),
                "historical-IP TLS pin was not retained after address rotation");
        require(SessionTokenStore.scopedCredential(config).valid(),
                "historical-IP scoped credentials were not retained after address rotation");

        SessionTokenStore.clear(config);
        SessionTokenStore.clearServerFingerprint(config);
        require(!SessionTokenStore.load(config).valid(), "cleared historical session was resurrected");
        Properties cleared = read(store);
        require(!cleared.containsKey(oldSession), "historical session alias survived an explicit clear");
        require(!cleared.containsKey("tls-server." + oldTrust),
                "historical TLS alias survived an explicit trust clear");
    }

    private static void validateAmbiguousHistoryIsNotGuessed(Path store) throws Exception {
        clearStore(store);
        int port = 51237;
        Config config = Config.join("Ambiguous Commander", "localhost", port, false);
        Properties history = new Properties();
        history.setProperty(encode("192.0.2.10:" + port + "|ambiguous commander"),
                "P10|" + "D".repeat(43) + "|");
        history.setProperty(encode("192.0.2.11:" + port + "|ambiguous commander"),
                "P11|" + "E".repeat(43) + "|");
        write(store, history);
        require(!SessionTokenStore.load(config).valid(),
                "ambiguous historical endpoints were guessed instead of requiring a new identity decision");
    }

    private static Properties read(Path store) throws Exception {
        Properties properties = new Properties();
        if (!Files.exists(store)) return properties;
        try (InputStream input = Files.newInputStream(store)) {
            properties.load(input);
        }
        return properties;
    }

    private static void write(Path store, Properties properties) throws Exception {
        Files.createDirectories(store.toAbsolutePath().getParent());
        try (OutputStream output = Files.newOutputStream(store)) {
            properties.store(output, "StarChem endpoint identity validator");
        }
    }

    private static void clearStore(Path store) throws Exception {
        Files.deleteIfExists(store);
        Files.deleteIfExists(store.resolveSibling(store.getFileName() + ".previous"));
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
