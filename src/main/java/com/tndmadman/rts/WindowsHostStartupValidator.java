package com.tndmadman.rts;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

/** Exercises the graphical HOST server and loopback client path with managed TLS storage. */
public final class WindowsHostStartupValidator {
    private WindowsHostStartupValidator() { }

    public static void main(String[] args) throws Exception {
        Path saveDirectory = Path.of("saves").toAbsolutePath().normalize();
        deleteTree(saveDirectory);
        int port = availablePort();
        Config config = Config.host("Windows Host Validator", port, false, false, Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, "", 1);
        LocalHostSession session = null;
        try {
            session = LocalHostSession.start(config);
            Thread.sleep(1_000L);
            require(session.clientNetwork != null, "graphical HOST did not create its loopback client");

            Path keyFile = saveDirectory.resolve("server-tls.p12");
            Path passwordFile = saveDirectory.resolve("server-tls.password");
            require(Files.isRegularFile(keyFile), "graphical HOST did not create its TLS keystore");
            require(Files.isRegularFile(passwordFile), "graphical HOST did not create its TLS password file");
            PrivateFileSecurity.verifyPrivateDirectory(saveDirectory);
            PrivateFileSecurity.verifyPrivateRegularFile(keyFile);
            PrivateFileSecurity.verifyPrivateRegularFile(passwordFile);

            String firstFingerprint = TlsIdentity.serverFingerprint(config);
            session.stop();
            session = null;
            String restartedFingerprint = TlsIdentity.serverFingerprint(config);
            require(firstFingerprint.equals(restartedFingerprint),
                    "graphical HOST TLS fingerprint changed after restart");
            assertNoTemporaryFiles(saveDirectory);
            System.out.println("Windows graphical host startup validation passed.");
        } finally {
            if (session != null) session.stop();
            deleteTree(saveDirectory);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.walk(directory)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "graphical HOST left a temporary file behind");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
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
}
