package com.tndmadman.rts;

import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class TlsIdentity {
    private static final char[] KEY_PASSWORD = "starchem-local-tls".toCharArray();
    private static final String KEY_ALIAS = "starchem-server";

    private TlsIdentity() { }

    static ServerSocketFactory serverSocketFactory(Config config) throws IOException {
        try {
            KeyStore keys = loadOrCreateServerKeys(config);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, KEY_PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context.getServerSocketFactory();
        } catch (GeneralSecurityException ex) {
            throw new IOException("Could not initialize server TLS identity: " + ex.getMessage(), ex);
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
            String fingerprint = PasswordAuth.encodeVerifier(
                    MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
            if (config != null && config.localHostClientMode()) return;
            String pinned = SessionTokenStore.serverFingerprint(config);
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

    private static KeyStore loadOrCreateServerKeys(Config config) throws IOException, GeneralSecurityException {
        Path file = serverKeyPath(config);
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                store.load(input, KEY_PASSWORD);
            }
            return store;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = selfSigned(pair);
        store.load(null, KEY_PASSWORD);
        store.setKeyEntry(KEY_ALIAS, pair.getPrivate(), KEY_PASSWORD, new Certificate[]{certificate});
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (var output = Files.newOutputStream(file)) {
            store.store(output, KEY_PASSWORD);
        }
        return store;
    }

    private static Path serverKeyPath(Config config) {
        Path dir = config == null ? Path.of("saves") : config.saveDir;
        String saveName = config == null ? "server" : config.saveName;
        return dir.resolve(saveName + "-tls.p12");
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

    private enum TrustAllManager implements X509TrustManager {
        INSTANCE;
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
