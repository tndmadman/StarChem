package com.tndmadman.rts;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

final class TlsIdentity {
    static final String KEYSTORE_PROPERTY = "starchem.tls.keystore";
    static final String PASSWORD_FILE_PROPERTY = "starchem.tls.passwordFile";
    static final String KEY_ALIAS_PROPERTY = "starchem.tls.keyAlias";
    static final String KEYSTORE_ENV = "STARCHEM_TLS_KEYSTORE";
    static final String PASSWORD_FILE_ENV = "STARCHEM_TLS_PASSWORD_FILE";
    static final String KEY_ALIAS_ENV = "STARCHEM_TLS_KEY_ALIAS";

    private static final char[] LEGACY_KEY_PASSWORD = "starchem-local-tls".toCharArray();
    private static final String KEY_ALIAS = "starchem-server";
    private static final int GENERATED_PASSWORD_BYTES = 32;
    private static final int MAX_PASSWORD_FILE_BYTES = 4096;

    private TlsIdentity() { }

    static ServerSocketFactory serverSocketFactory(Config config) throws IOException {
        try (ServerKeys keys = loadOrCreateServerKeys(config)) {
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys.store, keys.password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context.getServerSocketFactory();
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not initialize server TLS identity: " + ex.getMessage(), ex);
        }
    }

    static String serverFingerprint(Config config) throws IOException {
        try (ServerKeys keys = loadOrCreateServerKeys(config)) {
            Certificate certificate = keys.store.getCertificate(keys.alias);
            if (certificate == null) throw new IOException("Server TLS certificate is missing.");
            return certificateFingerprint(certificate);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not read server TLS identity: " + ex.getMessage(), ex);
        }
    }

    static SocketFactory clientSocketFactory() throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TrustAllManager.INSTANCE}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not initialize client TLS: " + ex.getMessage(), ex);
        }
    }

    static void verifyPinnedServer(Socket socket, Config config) throws IOException {
        if (!(socket instanceof SSLSocket ssl)) throw new IOException("Server connection is not encrypted.");
        try {
            ssl.startHandshake();
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain.length == 0) throw new IOException("Server did not present a TLS certificate.");
            String fingerprint = certificateFingerprint(chain[0]);
            if (config != null && config.localHostClientMode()) return;
            String pinned = SessionTokenStore.serverFingerprint(config);
            if (automaticallyTrustLoopbackServer(config)) {
                if (!fingerprint.equalsIgnoreCase(pinned)) {
                    SessionTokenStore.saveServerFingerprint(config, fingerprint);
                }
                return;
            }
            if (pinned.isBlank()) {
                SessionTokenStore.saveServerFingerprint(config, fingerprint);
            } else if (!MessageDigest.isEqual(PasswordAuth.decodeVerifier(pinned), PasswordAuth.decodeVerifier(fingerprint))) {
                throw new FingerprintChangedException(new FingerprintChange(pinned, fingerprint));
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Could not verify server TLS fingerprint: " + ex.getMessage(), ex);
        }
    }

    static boolean automaticallyTrustLoopbackServer(Config config) {
        if (config == null || config.serverAddress == null) return false;
        InetAddress address = config.serverAddress.getAddress();
        return address != null && address.isLoopbackAddress();
    }

    record FingerprintChange(String expected, String presented) {
        FingerprintChange {
            expected = PasswordAuth.validVerifier(expected) ? expected.toLowerCase(java.util.Locale.ROOT) : "";
            presented = PasswordAuth.validVerifier(presented) ? presented.toLowerCase(java.util.Locale.ROOT) : "";
        }
        boolean valid() { return PasswordAuth.validVerifier(expected) && PasswordAuth.validVerifier(presented); }
    }

    static final class FingerprintChangedException extends IOException {
        private final FingerprintChange change;
        FingerprintChangedException(FingerprintChange change) {
            super("Server TLS fingerprint changed. Refusing to send login secrets.");
            this.change = change;
        }
        FingerprintChange change() { return change; }
    }

    static boolean encrypted(Socket socket) {
        return socket instanceof SSLSocket;
    }

    private static ServerKeys loadOrCreateServerKeys(Config config) throws IOException, GeneralSecurityException {
        TlsSettings settings = TlsSettings.resolve(config);
        return settings.external ? loadExternalKeys(settings) : loadManagedKeys(settings);
    }

    private static ServerKeys loadExternalKeys(TlsSettings settings) throws IOException, GeneralSecurityException {
        PrivateFileSecurity.verifyPrivateRegularFile(settings.keyFile);
        PrivateFileSecurity.verifyPrivateRegularFile(settings.passwordFile);
        char[] password = readPassword(settings.passwordFile);
        try {
            KeyStore source = loadStore(settings.keyFile, password);
            String alias = resolveAlias(source, settings.requestedAlias, password);
            KeyStore selected = selectedStore(source, alias, password);
            return new ServerKeys(selected, password, alias);
        } catch (IOException | GeneralSecurityException ex) {
            clear(password);
            throw ex;
        }
    }

    private static ServerKeys loadManagedKeys(TlsSettings settings) throws IOException, GeneralSecurityException {
        Path parent = settings.keyFile.getParent();
        if (parent == null) throw new IOException("Server TLS identity has no parent directory: " + settings.keyFile);
        PrivateFileSecurity.ensurePrivateDirectory(parent);

        char[] password = null;
        try {
            if (Files.exists(settings.passwordFile, LinkOption.NOFOLLOW_LINKS)) {
                PrivateFileSecurity.verifyPrivateRegularFile(settings.passwordFile);
                password = readPassword(settings.passwordFile);
            }

            if (Files.exists(settings.keyFile, LinkOption.NOFOLLOW_LINKS)) {
                PrivateFileSecurity.verifyPrivateRegularFile(settings.keyFile);
                if (password != null) {
                    try {
                        return loadManagedExisting(settings.keyFile, password, settings.requestedAlias);
                    } catch (IOException | GeneralSecurityException currentFailure) {
                        try {
                            return migrateLegacy(settings, password);
                        } catch (IOException | GeneralSecurityException legacyFailure) {
                            currentFailure.addSuppressed(legacyFailure);
                            throw new IOException("Could not load the existing server TLS identity. "
                                    + "The file was left unchanged: " + settings.keyFile, currentFailure);
                        }
                    }
                }

                try {
                    loadStore(settings.keyFile, LEGACY_KEY_PASSWORD);
                } catch (IOException | GeneralSecurityException ex) {
                    throw new IOException("Existing server TLS identity has no protected password file and could not "
                            + "be opened as a legacy StarChem identity. The file was left unchanged: "
                            + settings.keyFile, ex);
                }
                password = generatedPassword();
                persistPassword(settings.passwordFile, password);
                return migrateLegacy(settings, password);
            }

            if (password == null) {
                password = generatedPassword();
                persistPassword(settings.passwordFile, password);
            }
            KeyStore created = createIdentityStore(settings.requestedAlias, password);
            persistKeyStore(settings.keyFile, created, password, settings.requestedAlias, null);
            return loadManagedExisting(settings.keyFile, password, settings.requestedAlias);
        } catch (IOException | GeneralSecurityException ex) {
            clear(password);
            throw ex;
        }
    }

    private static ServerKeys migrateLegacy(TlsSettings settings, char[] replacementPassword)
            throws IOException, GeneralSecurityException {
        KeyStore legacy = loadStore(settings.keyFile, LEGACY_KEY_PASSWORD);
        String alias = resolveAlias(legacy, settings.requestedAlias, LEGACY_KEY_PASSWORD);
        Key key = legacy.getKey(alias, LEGACY_KEY_PASSWORD);
        Certificate[] chain = certificateChain(legacy, alias);
        String fingerprint = certificateFingerprint(chain[0]);
        legacy.setKeyEntry(alias, key, replacementPassword, chain);
        persistKeyStore(settings.keyFile, legacy, replacementPassword, alias, fingerprint);
        ServerKeys migrated = loadManagedExisting(settings.keyFile, replacementPassword, alias);
        String migratedFingerprint = certificateFingerprint(migrated.store.getCertificate(migrated.alias));
        if (!MessageDigest.isEqual(PasswordAuth.decodeVerifier(fingerprint),
                PasswordAuth.decodeVerifier(migratedFingerprint))) {
            migrated.close();
            throw new IOException("Server TLS identity migration changed the certificate fingerprint.");
        }
        System.out.println("Migrated the server TLS identity from the legacy shared password without changing its fingerprint.");
        return migrated;
    }

    private static ServerKeys loadManagedExisting(Path file, char[] password, String requestedAlias)
            throws IOException, GeneralSecurityException {
        KeyStore store = loadStore(file, password);
        String alias = resolveAlias(store, requestedAlias, password);
        return new ServerKeys(store, password, alias);
    }

    private static KeyStore loadStore(Path file, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) {
            store.load(input, password);
        }
        return store;
    }

    private static KeyStore createIdentityStore(String alias, char[] password)
            throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = selfSigned(pair);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry(alias, pair.getPrivate(), password, new Certificate[]{certificate});
        return store;
    }

    private static KeyStore selectedStore(KeyStore source, String alias, char[] password)
            throws IOException, GeneralSecurityException {
        Key key = source.getKey(alias, password);
        Certificate[] chain = certificateChain(source, alias);
        KeyStore selected = KeyStore.getInstance("PKCS12");
        selected.load(null, password);
        selected.setKeyEntry(alias, key, password, chain);
        return selected;
    }

    private static String resolveAlias(KeyStore store, String requestedAlias, char[] password)
            throws GeneralSecurityException, IOException {
        if (requestedAlias != null && !requestedAlias.isBlank()) {
            verifyKeyEntry(store, requestedAlias, password);
            return requestedAlias;
        }
        List<String> aliases = new ArrayList<>();
        Enumeration<String> entries = store.aliases();
        while (entries.hasMoreElements()) {
            String alias = entries.nextElement();
            if (store.isKeyEntry(alias)) aliases.add(alias);
        }
        if (aliases.size() != 1) {
            throw new IOException("TLS keystore must contain exactly one private-key entry when no key alias is configured.");
        }
        verifyKeyEntry(store, aliases.get(0), password);
        return aliases.get(0);
    }

    private static void verifyKeyEntry(KeyStore store, String alias, char[] password)
            throws GeneralSecurityException, IOException {
        if (!store.isKeyEntry(alias)) throw new IOException("TLS private-key alias was not found: " + alias);
        Key key = store.getKey(alias, password);
        if (key == null) throw new IOException("TLS private key could not be loaded for alias: " + alias);
        certificateChain(store, alias);
    }

    private static Certificate[] certificateChain(KeyStore store, String alias)
            throws IOException, GeneralSecurityException {
        Certificate[] chain = store.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            Certificate certificate = store.getCertificate(alias);
            if (certificate == null) throw new IOException("TLS certificate is missing for alias: " + alias);
            chain = new Certificate[]{certificate};
        }
        return chain;
    }

    private static void persistKeyStore(Path file, KeyStore store, char[] password, String alias,
                                        String expectedFingerprint)
            throws IOException, GeneralSecurityException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("Server TLS identity has no parent directory: " + file);
        Path temporary = PrivateFileSecurity.createPrivateTempFile(parent, file.getFileName() + "-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                store.store(output, password);
                output.getFD().sync();
            }
            PrivateFileSecurity.secureFile(temporary);
            KeyStore verified = loadStore(temporary, password);
            String verifiedAlias = resolveAlias(verified, alias, password);
            String fingerprint = certificateFingerprint(verified.getCertificate(verifiedAlias));
            if (expectedFingerprint != null && !MessageDigest.isEqual(
                    PasswordAuth.decodeVerifier(expectedFingerprint), PasswordAuth.decodeVerifier(fingerprint))) {
                throw new IOException("TLS identity verification detected a changed certificate fingerprint.");
            }
            PrivateFileSecurity.moveReplace(temporary, file);
            temporary = null;
            PrivateFileSecurity.secureFile(file);
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static void persistPassword(Path file, char[] password) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("Server TLS password file has no parent directory: " + file);
        PrivateFileSecurity.ensurePrivateDirectory(parent);
        Path temporary = PrivateFileSecurity.createPrivateTempFile(parent, file.getFileName() + "-", ".tmp");
        byte[] encoded = null;
        char[] verified = null;
        try {
            encoded = (new String(password) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(encoded);
                output.getFD().sync();
            }
            PrivateFileSecurity.secureFile(temporary);
            verified = readPassword(temporary);
            if (!Arrays.equals(password, verified)) throw new IOException("TLS password file verification failed.");
            PrivateFileSecurity.moveReplace(temporary, file);
            temporary = null;
            PrivateFileSecurity.secureFile(file);
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            clear(verified);
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static char[] readPassword(Path file) throws IOException {
        byte[] bytes = null;
        try {
            long size = Files.size(file);
            if (size < 1 || size > MAX_PASSWORD_FILE_BYTES) {
                throw new IOException("TLS password file must contain 1-" + MAX_PASSWORD_FILE_BYTES + " bytes: " + file);
            }
            bytes = Files.readAllBytes(file);
            if (bytes.length < 1 || bytes.length > MAX_PASSWORD_FILE_BYTES) {
                throw new IOException("TLS password file must contain 1-" + MAX_PASSWORD_FILE_BYTES + " bytes: " + file);
            }
            String text = decodePassword(bytes, file);
            if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
            else if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            if (text.isBlank() || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0) {
                throw new IOException("TLS password file must contain exactly one non-empty UTF-8 password: " + file);
            }
            return text.toCharArray();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String decodePassword(byte[] bytes, Path file) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("TLS password file is not valid UTF-8: " + file, ex);
        }
    }

    private static char[] generatedPassword() {
        byte[] random = new byte[GENERATED_PASSWORD_BYTES];
        new SecureRandom().nextBytes(random);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
        } finally {
            Arrays.fill(random, (byte) 0);
        }
    }

    private static String certificateFingerprint(Certificate certificate) throws GeneralSecurityException {
        if (certificate == null) throw new GeneralSecurityException("TLS certificate is missing.");
        return PasswordAuth.encodeVerifier(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private static X509Certificate selfSigned(KeyPair pair) throws GeneralSecurityException {
        try {
            byte[] algorithm = sequence(oid("1.2.840.113549.1.1.11"), derNull());
            byte[] name = name("StarChem Server");
            Instant now = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant until = now.plus(3650, ChronoUnit.DAYS);
            byte[] validity = sequence(time(now), time(until));
            byte[] tbs = sequence(
                    tagged(0, integer(BigInteger.valueOf(2))),
                    integer(new BigInteger(159, new SecureRandom()).abs().add(BigInteger.ONE)),
                    algorithm,
                    name,
                    validity,
                    name,
                    pair.getPublic().getEncoded());
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(tbs);
            byte[] cert = sequence(tbs, algorithm, bitString(signature.sign()));
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(cert));
        } catch (IOException ex) {
            throw new GeneralSecurityException("could not encode certificate", ex);
        }
    }

    private static byte[] name(String commonName) throws IOException {
        return sequence(set(sequence(oid("2.5.4.3"), utf8(commonName))));
    }

    private static byte[] time(Instant instant) throws IOException {
        String value = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(instant);
        return tag(0x18, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sequence(byte[]... values) throws IOException { return constructed(0x30, values); }
    private static byte[] set(byte[]... values) throws IOException { return constructed(0x31, values); }
    private static byte[] tagged(int tag, byte[] value) throws IOException { return constructed(0xa0 + tag, value); }
    private static byte[] derNull() { return new byte[]{0x05, 0x00}; }
    private static byte[] utf8(String value) throws IOException { return tag(0x0c, value.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] integer(BigInteger value) throws IOException {
        return tag(0x02, value.toByteArray());
    }

    private static byte[] bitString(byte[] value) throws IOException {
        byte[] body = new byte[value.length + 1];
        System.arraycopy(value, 0, body, 1, value.length);
        return tag(0x03, body);
    }

    private static byte[] oid(String dotted) throws IOException {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        out.write(first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            byte[] stack = new byte[10];
            int count = 0;
            stack[count++] = (byte)(value & 0x7f);
            value >>= 7;
            while (value > 0) {
                stack[count++] = (byte)(0x80 | (value & 0x7f));
                value >>= 7;
            }
            for (int j = count - 1; j >= 0; j--) out.write(stack[j]);
        }
        return tag(0x06, out.toByteArray());
    }

    private static byte[] constructed(int tag, byte[]... values) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] value : values) body.write(value);
        return tag(tag, body.toByteArray());
    }

    private static byte[] tag(int tag, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, body.length);
        out.write(body);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
            return;
        }
        int bytes = 0;
        int value = length;
        byte[] stack = new byte[4];
        while (value > 0) {
            stack[bytes++] = (byte)(value & 0xff);
            value >>= 8;
        }
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) out.write(stack[i]);
    }

    private static final class ServerKeys implements AutoCloseable {
        private final KeyStore store;
        private final char[] password;
        private final String alias;

        private ServerKeys(KeyStore store, char[] password, String alias) {
            this.store = store;
            this.password = password;
            this.alias = alias;
        }

        @Override
        public void close() {
            clear(password);
        }
    }

    private record TlsSettings(Path keyFile, Path passwordFile, String requestedAlias, boolean external) {
        private static TlsSettings resolve(Config config) throws IOException {
            Path saveDirectory = PrivateFileSecurity.normalized(config == null ? Path.of("saves") : config.saveDir);
            String saveName = config == null ? "server" : config.saveName;
            Path managedKey = saveDirectory.resolve(saveName + "-tls.p12").toAbsolutePath().normalize();
            Path managedPassword = saveDirectory.resolve(saveName + "-tls.password").toAbsolutePath().normalize();

            String configuredKey = configured(KEYSTORE_PROPERTY, KEYSTORE_ENV);
            boolean external = !configuredKey.isBlank();
            Path keyFile = external ? configuredPath(configuredKey, "TLS keystore") : managedKey;

            String configuredPassword = configured(PASSWORD_FILE_PROPERTY, PASSWORD_FILE_ENV);
            Path passwordFile = configuredPassword.isBlank()
                    ? external ? null : managedPassword
                    : configuredPath(configuredPassword, "TLS password file");
            if (external && passwordFile == null) {
                throw new IOException("An operator-supplied TLS keystore requires " + PASSWORD_FILE_PROPERTY
                        + " or " + PASSWORD_FILE_ENV + ".");
            }

            String configuredAlias = configured(KEY_ALIAS_PROPERTY, KEY_ALIAS_ENV);
            String requestedAlias = configuredAlias.isBlank() && !external ? KEY_ALIAS : configuredAlias;
            return new TlsSettings(keyFile, passwordFile, requestedAlias, external);
        }

        private static String configured(String property, String environment) {
            String value = System.getProperty(property, "");
            if (value == null || value.isBlank()) value = System.getenv(environment);
            return value == null ? "" : value.trim();
        }

        private static Path configuredPath(String value, String label) throws IOException {
            try {
                return PrivateFileSecurity.normalized(Path.of(value));
            } catch (InvalidPathException ex) {
                throw new IOException(label + " path is invalid: " + value, ex);
            }
        }
    }

    private enum TrustAllManager implements X509TrustManager {
        INSTANCE;
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
