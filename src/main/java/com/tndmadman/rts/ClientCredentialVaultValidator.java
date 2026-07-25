package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Regression coverage for protected client-credential storage and plaintext migration. */
public final class ClientCredentialVaultValidator {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";
    private static final String VAULT_MODE = "starchem.credentialVault";
    private static final String VAULT_PATH = "starchem.credentialVaultPath";
    private static final String AUTH = "11".repeat(32);
    private static final String FINGERPRINT = "22".repeat(32);
    private static final String SALT = "33".repeat(16);
    private static final String SCOPED = "44".repeat(32);
    private static final String TOKEN = "A".repeat(43);

    private ClientCredentialVaultValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            runChild(args);
            return;
        }
        validate();
        System.out.println("Client credential vault validation passed.");
    }

    static void validate() throws Exception {
        Path root = Files.createTempDirectory("starchem-credential-vault-");
        String previousStore = System.getProperty(STORE_OVERRIDE);
        String previousMode = System.getProperty(VAULT_MODE);
        String previousPath = System.getProperty(VAULT_PATH);
        PrintStream previousErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        try {
            validateProtectedPersistence(root.resolve("protected"));
            validateLegacyMigration(root.resolve("migration"));
            validateRememberOptOut(root.resolve("opt-out"));
            validateClearAll(root.resolve("clear"));
            validatePermissionFailure(root.resolve("failure"));
            String diagnostics = capturedErr.toString(StandardCharsets.UTF_8);
            require(!diagnostics.contains(TOKEN) && !diagnostics.contains(AUTH) && !diagnostics.contains(SCOPED),
                    "credential values leaked into diagnostics");
        } finally {
            System.setErr(previousErr);
            restore(STORE_OVERRIDE, previousStore);
            restore(VAULT_MODE, previousMode);
            restore(VAULT_PATH, previousPath);
            PendingPlayerPassword.clearAll();
            ClientCredentialVault.resetForTests();
            deleteTree(root);
        }
    }

    private static void validateProtectedPersistence(Path directory) throws Exception {
        Paths paths = configure(directory);
        Config config = Config.join("Vault Client", "127.0.0.1", 51111, false);
        SessionTokenStore.saveAuthDigest(config, AUTH);
        SessionTokenStore.save(config, "P7", TOKEN);
        SessionTokenStore.saveServerFingerprint(config, FINGERPRINT);
        SessionTokenStore.saveScopedCredential(config, FINGERPRINT, SALT, SCOPED);
        String device = ClientDeviceIdentityStore.deviceId();

        assertNoPlaintextSecrets(paths.store());
        Properties metadata = loadProperties(paths.store());
        require(metadata.containsValue(FINGERPRINT), "TLS fingerprint was not retained as non-secret metadata");
        require(metadata.containsValue(device), "client device identifier was not retained as metadata");
        require("owner-only file fallback".equals(ClientCredentialVault.backendName()),
                "validator did not use the deterministic owner-only backend");
        assertOwnerOnly(paths.store().getParent(), true);
        assertOwnerOnly(paths.store(), false);
        assertOwnerOnly(paths.store().resolveSibling(paths.store().getFileName() + ".lock"), false);
        Path previous = paths.store().resolveSibling(paths.store().getFileName() + ".previous");
        if (Files.exists(previous)) assertOwnerOnly(previous, false);
        assertOwnerOnly(paths.vault(), true);
        try (var files = Files.list(paths.vault())) {
            for (Path file : files.toList()) assertOwnerOnly(file, false);
        }
        assertNoTemporaryFiles(directory);
        requireChild(paths, "present", "Vault Client", 51111, device);
    }

    private static void validateLegacyMigration(Path directory) throws Exception {
        Paths paths = configure(directory);
        ClientCredentialVault.ensureOwnerOnlyDirectory(paths.store().getParent());
        Properties legacy = new Properties();
        String key = "bGVnYWN5LXNlc3Npb24";
        legacy.setProperty(key, "P9|" + TOKEN + '|' + AUTH);
        legacy.setProperty("auth-v2." + key, FINGERPRINT + '|' + SALT + '|' + SCOPED);
        legacy.setProperty("tls-server.legacy", FINGERPRINT);
        try (FileOutputStream output = new FileOutputStream(paths.store().toFile())) {
            legacy.store(output, "legacy plaintext test");
        }

        Properties logical = ClientSessionPropertiesStore.read(properties -> properties);
        require(("P9|" + TOKEN + '|' + AUTH).equals(logical.getProperty(key)),
                "legacy session was not available during migration");
        require((FINGERPRINT + '|' + SALT + '|' + SCOPED).equals(logical.getProperty("auth-v2." + key)),
                "legacy scoped credential was not available during migration");
        assertNoPlaintextSecrets(paths.store());
        Path previous = paths.store().resolveSibling(paths.store().getFileName() + ".previous");
        if (Files.exists(previous)) assertNoPlaintextSecrets(previous);
        ClientCredentialVault.resetForTests();
        Properties reloaded = ClientSessionPropertiesStore.read(properties -> properties);
        require(logical.getProperty(key).equals(reloaded.getProperty(key)),
                "migrated session did not survive a vault restart");
    }

    private static void validateRememberOptOut(Path directory) throws Exception {
        Paths paths = configure(directory);
        Config config = Config.join("Do Not Remember", "127.0.0.1", 51112, false);
        char[] password = "temporary-password".toCharArray();
        try {
            PendingPlayerPassword.remember(config, password, false);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        SessionTokenStore.saveAuthDigest(config, AUTH);
        SessionTokenStore.saveScopedCredential(config, FINGERPRINT, SALT, SCOPED);
        SessionTokenStore.save(config, "P8", TOKEN);
        if (Files.exists(paths.store())) assertNoPlaintextSecrets(paths.store());
        requireChild(paths, "empty", "Do Not Remember", 51112, "");
    }

    private static void validateClearAll(Path directory) throws Exception {
        Paths paths = configure(directory);
        Config config = Config.join("Clear Client", "127.0.0.1", 51113, false);
        SessionTokenStore.saveAuthDigest(config, AUTH);
        SessionTokenStore.save(config, "P10", TOKEN);
        SessionTokenStore.saveScopedCredential(config, FINGERPRINT, SALT, SCOPED);
        SessionTokenStore.saveServerFingerprint(config, FINGERPRINT);
        String device = ClientDeviceIdentityStore.deviceId();
        int removed = ClientSessionPropertiesStore.clearSavedCredentials();
        PendingPlayerPassword.clearAll();
        require(removed >= 2, "clear saved sign-ins did not remove credential entries");
        require(FINGERPRINT.equals(SessionTokenStore.serverFingerprint(config)),
                "clearing sign-ins removed the trusted TLS fingerprint");
        require(device.equals(ClientDeviceIdentityStore.deviceId()),
                "clearing sign-ins changed the client device identifier");
        assertNoPlaintextSecrets(paths.store());
        requireChild(paths, "empty", "Clear Client", 51113, device);
    }

    private static void validatePermissionFailure(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path blocked = directory.resolve("blocked-vault");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        Path store = directory.resolve("sessions.properties");
        System.setProperty(STORE_OVERRIDE, store.toString());
        System.setProperty(VAULT_MODE, "file");
        System.setProperty(VAULT_PATH, blocked.toString());
        ClientCredentialVault.resetForTests();
        Properties legacy = new Properties();
        legacy.setProperty("bG9ja2Vk", "P11|" + TOKEN + '|' + AUTH);
        try (FileOutputStream output = new FileOutputStream(store.toFile())) {
            legacy.store(output, "permission failure");
        }
        boolean failed = false;
        try {
            ClientSessionPropertiesStore.read(properties -> properties.size());
        } catch (IllegalStateException expected) {
            failed = true;
        }
        require(failed, "credential migration did not fail closed when the vault path was unusable");
        require(Files.readString(store, StandardCharsets.ISO_8859_1).contains(TOKEN),
                "failed migration erased the recoverable legacy credential");
    }

    private static Paths configure(Path directory) throws Exception {
        deleteTree(directory);
        Files.createDirectories(directory);
        Path store = directory.resolve("sessions.properties");
        Path vault = directory.resolve("credentials");
        System.setProperty(STORE_OVERRIDE, store.toString());
        System.setProperty(VAULT_MODE, "file");
        System.setProperty(VAULT_PATH, vault.toString());
        PendingPlayerPassword.clearAll();
        ClientCredentialVault.resetForTests();
        return new Paths(store, vault);
    }

    private static void requireChild(Paths paths, String expectation, String playerName,
                                     int port, String device) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                ClientCredentialVaultValidator.class.getName(), "child", paths.store().toString(),
                paths.vault().toString(), expectation, playerName, Integer.toString(port), device)
                .redirectErrorStream(true).start();
        byte[] output;
        try (InputStream input = process.getInputStream()) {
            output = input.readAllBytes();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("credential restart validation timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("credential restart validation failed: "
                    + new String(output, StandardCharsets.UTF_8).trim());
        }
    }

    private static void runChild(String[] args) {
        if (args.length != 7) throw new IllegalArgumentException("Expected child, store, vault, expectation, player, port, and device.");
        System.setProperty(STORE_OVERRIDE, args[1]);
        System.setProperty(VAULT_MODE, "file");
        System.setProperty(VAULT_PATH, args[2]);
        ClientCredentialVault.resetForTests();
        String expectation = args[3];
        Config config = Config.join(args[4], "127.0.0.1", Integer.parseInt(args[5]), false);
        SessionTokenStore.StoredSession session = SessionTokenStore.load(config);
        SessionTokenStore.ScopedCredential scoped = SessionTokenStore.scopedCredential(config);
        if ("present".equals(expectation)) {
            require(session.valid() && TOKEN.equals(session.token()), "saved session was not restored after restart");
            require(scoped.valid() && SCOPED.equals(scoped.verifier()),
                    "saved scoped credential was not restored after restart");
            require(FINGERPRINT.equals(SessionTokenStore.serverFingerprint(config)),
                    "TLS trust metadata was not restored after restart");
        } else {
            require(!session.valid(), "session persisted despite clear or remember opt-out");
            require(!scoped.valid(), "scoped credential persisted despite clear or remember opt-out");
        }
        if (!args[6].isBlank()) require(args[6].equals(ClientDeviceIdentityStore.deviceId()),
                "client device identity did not survive restart");
    }

    private static void assertNoPlaintextSecrets(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        require(!text.contains(TOKEN), "session token remained in " + file.getFileName());
        require(!text.contains(AUTH), "password-equivalent authenticator remained in " + file.getFileName());
        require(!text.contains(SCOPED), "scoped password verifier remained in " + file.getFileName());
    }

    private static void assertOwnerOnly(Path path, boolean directory) throws Exception {
        if (!Files.exists(path)) return;
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) return;
        String actual = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
        String expected = directory ? "rwx------" : "rw-------";
        require(expected.equals(actual), path.getFileName() + " permissions were " + actual + " instead of " + expected);
    }

    private static void assertNoTemporaryFiles(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            require(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "credential persistence left a temporary file behind");
        }
    }

    private static Properties loadProperties(Path store) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(store)) {
            properties.load(input);
        }
        return properties;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (Exception ignored) { }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Paths(Path store, Path vault) { }
}
