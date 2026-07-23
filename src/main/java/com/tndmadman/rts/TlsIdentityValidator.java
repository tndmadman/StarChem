package com.tndmadman.rts;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

public final class TlsIdentityValidator {
    private static final String ALIAS = "starchem-server";
    private static final char[] LEGACY_PASSWORD = "starchem-local-tls".toCharArray();

    private TlsIdentityValidator() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("starchem-tls-identity-validator-");
        try {
            validateManagedGeneration(root.resolve("managed"));
            validateLegacyMigration(root.resolve("migration"));
            validateExternalIdentity(root.resolve("external"));
            validateCorruptIdentityFailsClosed(root.resolve("corrupt"));
            System.out.println("StarChem TLS identity validation passed.");
        } finally {
            clearConfiguration();
            deleteTree(root);
        }
    }

    private static void validateManagedGeneration(Path directory) throws Exception {
        Config config = serverConfig(directory, "managed");
        String firstFingerprint = TlsIdentity.serverFingerprint(config);
        String secondFingerprint = TlsIdentity.serverFingerprint(config);
        require(PasswordAuth.validVerifier(firstFingerprint), "generated TLS fingerprint is invalid");
        require(firstFingerprint.equals(secondFingerprint), "TLS fingerprint changed across restart");

        Path keyFile = directory.resolve("managed-tls.p12");
        Path passwordFile = directory.resolve("managed-tls.password");
        require(Files.isRegularFile(keyFile), "managed TLS keystore was not created");
        require(Files.isRegularFile(passwordFile), "managed TLS password file was not created");
        assertOwnerOnly(keyFile);
        assertOwnerOnly(passwordFile);

        char[] password = password(passwordFile);
        try {
            require(password.length >= 40, "generated TLS password is unexpectedly short");
            require(!Arrays.equals(password, LEGACY_PASSWORD), "generated TLS password reused the legacy constant");
            KeyStore store = load(keyFile, password);
            require(store.isKeyEntry(ALIAS), "generated TLS private key is missing");
            require(store.getKey(ALIAS, password) != null, "generated TLS private key cannot be recovered");
        } finally {
            Arrays.fill(password, '\0');
        }
        assertNoTemporaryFiles(directory);
    }

    private static void validateLegacyMigration(Path directory) throws Exception {
        Config config = serverConfig(directory, "legacy");
        String originalFingerprint = TlsIdentity.serverFingerprint(config);
        Path keyFile = directory.resolve("legacy-tls.p12");
        Path passwordFile = directory.resolve("legacy-tls.password");

        char[] currentPassword = password(passwordFile);
        try {
            KeyStore store = load(keyFile, currentPassword);
            Key key = store.getKey(ALIAS, currentPassword);
            Certificate[] chain = store.getCertificateChain(ALIAS);
            require(key != null && chain != null && chain.length > 0, "could not prepare legacy migration fixture");
            store.setKeyEntry(ALIAS, key, LEGACY_PASSWORD, chain);
            try (FileOutputStream output = new FileOutputStream(keyFile.toFile())) {
                store.store(output, LEGACY_PASSWORD);
                output.getFD().sync();
            }
        } finally {
            Arrays.fill(currentPassword, '\0');
        }
        Files.delete(passwordFile);

        String migratedFingerprint = TlsIdentity.serverFingerprint(config);
        require(originalFingerprint.equals(migratedFingerprint), "legacy migration changed the TLS fingerprint");
        require(Files.isRegularFile(passwordFile), "legacy migration did not create a protected password file");
        char[] migratedPassword = password(passwordFile);
        try {
            require(!Arrays.equals(migratedPassword, LEGACY_PASSWORD), "legacy migration retained the shared password");
            KeyStore migrated = load(keyFile, migratedPassword);
            require(migrated.getKey(ALIAS, migratedPassword) != null,
                    "migrated TLS private key cannot be recovered with the new password");
        } finally {
            Arrays.fill(migratedPassword, '\0');
        }
        assertOwnerOnly(keyFile);
        assertOwnerOnly(passwordFile);
        assertNoTemporaryFiles(directory);
    }

    private static void validateExternalIdentity(Path root) throws Exception {
        Path source = root.resolve("source");
        Config sourceConfig = serverConfig(source, "operator");
        String expectedFingerprint = TlsIdentity.serverFingerprint(sourceConfig);
        Path keyFile = source.resolve("operator-tls.p12");
        Path passwordFile = source.resolve("operator-tls.password");

        System.setProperty(TlsIdentity.KEYSTORE_PROPERTY, keyFile.toString());
        System.setProperty(TlsIdentity.PASSWORD_FILE_PROPERTY, passwordFile.toString());
        System.setProperty(TlsIdentity.KEY_ALIAS_PROPERTY, ALIAS);
        try {
            String externalFingerprint = TlsIdentity.serverFingerprint(serverConfig(root.resolve("unused"), "unused"));
            require(expectedFingerprint.equals(externalFingerprint),
                    "operator-provided TLS keystore selected the wrong certificate");
        } finally {
            clearConfiguration();
        }

        System.setProperty(TlsIdentity.KEYSTORE_PROPERTY, keyFile.toString());
        System.setProperty(TlsIdentity.PASSWORD_FILE_PROPERTY, root.resolve("missing-password").toString());
        try {
            expectIOException(() -> TlsIdentity.serverFingerprint(serverConfig(root.resolve("unused-2"), "unused")),
                    "operator keystore was accepted without a readable password file");
        } finally {
            clearConfiguration();
        }
    }

    private static void validateCorruptIdentityFailsClosed(Path directory) throws Exception {
        Config config = serverConfig(directory, "corrupt");
        TlsIdentity.serverFingerprint(config);
        Path keyFile = directory.resolve("corrupt-tls.p12");
        byte[] corrupt = new byte[]{0x53, 0x54, 0x41, 0x52, 0x43, 0x48, 0x45, 0x4d};
        Files.write(keyFile, corrupt, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        expectIOException(() -> TlsIdentity.serverFingerprint(config),
                "corrupt TLS identity was silently replaced");
        require(Arrays.equals(corrupt, Files.readAllBytes(keyFile)),
                "corrupt TLS identity was modified instead of failing closed");
        assertNoTemporaryFiles(directory);
    }

    private static Config serverConfig(Path directory, String saveName) {
        return Config.dedicatedServer("TLS Validator", 0, false, false, Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, "", 1, directory, saveName, 60, 5, false);
    }

    private static KeyStore load(Path file, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) {
            store.load(input, password);
        }
        return store;
    }

    private static char[] password(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
        else if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        return text.toCharArray();
    }

    private static void assertOwnerOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix == null) return;
        Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
        require(permissions.contains(PosixFilePermission.OWNER_READ), "private file is not owner-readable: " + file);
        require(permissions.contains(PosixFilePermission.OWNER_WRITE), "private file is not owner-writable: " + file);
        for (PosixFilePermission permission : permissions) {
            require(!permission.name().startsWith("GROUP_") && !permission.name().startsWith("OTHERS_"),
                    "private file exposes group/other permissions: " + file + " " + permissions);
        }
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "TLS temporary file was left behind in " + directory);
        }
    }

    private static void expectIOException(IoOperation operation, String message) throws Exception {
        try {
            operation.run();
            throw new IllegalStateException(message);
        } catch (IOException expected) {
            // Expected fail-closed behavior.
        }
    }

    private static void clearConfiguration() {
        System.clearProperty(TlsIdentity.KEYSTORE_PROPERTY);
        System.clearProperty(TlsIdentity.PASSWORD_FILE_PROPERTY);
        System.clearProperty(TlsIdentity.KEY_ALIAS_PROPERTY);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }
}
