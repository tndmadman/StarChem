package com.tndmadman.rts;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

/** Exercises Windows storage fallback plus graphical HOST server and loopback client startup. */
public final class WindowsHostStartupValidator {
    private static final Path FAILURE_DIAGNOSTIC = Path.of("build", "windows-host-failure.txt");

    private WindowsHostStartupValidator() { }

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(FAILURE_DIAGNOSTIC);
        Path root = Files.createTempDirectory("starchem-windows-host-");
        String previousSaveDirectory = System.getProperty(DefaultStoragePaths.SAVE_DIR_PROPERTY);
        LocalHostSession session = null;
        try {
            Path selected = validateUnsafeLaunchDirectoryFallback(root);
            System.setProperty(DefaultStoragePaths.SAVE_DIR_PROPERTY, selected.toString());

            int port = availablePort();
            Config config = Config.host("Windows Host Validator", port, false, false, Set.of(),
                    StarSystems.DEFAULT_SYSTEM_ID, "", 1);
            require(config.saveDir.toAbsolutePath().normalize().equals(selected),
                    "graphical HOST ignored the selected per-user save directory");

            session = LocalHostSession.start(config);
            require(session.clientNetwork != null, "graphical HOST did not create its loopback client");
            verifyLoopbackConnectionRemainsHealthy(session);

            Path keyFile = selected.resolve("server-tls.p12");
            Path passwordFile = selected.resolve("server-tls.password");
            require(Files.isRegularFile(keyFile), "graphical HOST did not create its TLS keystore");
            require(Files.isRegularFile(passwordFile), "graphical HOST did not create its TLS password file");
            PrivateFileSecurity.verifyPrivateDirectory(selected);
            PrivateFileSecurity.verifyPrivateRegularFile(keyFile);
            PrivateFileSecurity.verifyPrivateRegularFile(passwordFile);

            String firstFingerprint = TlsIdentity.serverFingerprint(config);
            session.stop();
            session = null;
            String restartedFingerprint = TlsIdentity.serverFingerprint(config);
            require(firstFingerprint.equals(restartedFingerprint),
                    "graphical HOST TLS fingerprint changed after restart");
            assertNoTemporaryFiles(selected);
            System.out.println("Windows graphical host storage and loopback connection validation passed.");
        } catch (Throwable failure) {
            writeFailureDiagnostic(failure, session);
            throw failure;
        } finally {
            if (session != null) session.stop();
            if (previousSaveDirectory == null) {
                System.clearProperty(DefaultStoragePaths.SAVE_DIR_PROPERTY);
            } else {
                System.setProperty(DefaultStoragePaths.SAVE_DIR_PROPERTY, previousSaveDirectory);
            }
            deleteTree(root);
        }
    }

    private static void verifyLoopbackConnectionRemainsHealthy(LocalHostSession session) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (session.clientNetwork.connectionFailed()) {
                throw new IllegalStateException("graphical HOST loopback client failed: "
                        + session.clientNetwork.failureMessage() + " | world status: " + session.clientWorld.status);
            }
            Thread.sleep(50L);
        }
        require(!session.clientNetwork.connectionFailed(),
                "graphical HOST loopback client failed after the validation interval: "
                        + session.clientNetwork.failureMessage() + " | world status: " + session.clientWorld.status);
        System.out.println("Loopback client remained healthy. World status: " + session.clientWorld.status);
    }

    private static void writeFailureDiagnostic(Throwable failure, LocalHostSession session) {
        String worldStatus = session == null || session.clientWorld == null ? "unavailable" : session.clientWorld.status;
        String networkFailure = session == null || session.clientNetwork == null
                ? "unavailable" : session.clientNetwork.failureMessage();
        String diagnostic = failure.getClass().getName() + ": " + failure.getMessage()
                + System.lineSeparator() + "network failure: " + networkFailure
                + System.lineSeparator() + "world status: " + worldStatus + System.lineSeparator();
        try {
            Files.createDirectories(FAILURE_DIAGNOSTIC.getParent());
            Files.writeString(FAILURE_DIAGNOSTIC, diagnostic, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private static Path validateUnsafeLaunchDirectoryFallback(Path root) throws Exception {
        Path perUser = root.resolve("local-app-data").resolve("StarChem").resolve("saves")
                .toAbsolutePath().normalize();

        Path launchDirectory = root.resolve("protected-launch");
        Files.createDirectories(launchDirectory);
        Path unusablePortablePath = launchDirectory.resolve("saves");
        Files.writeString(unusablePortablePath, "not a directory", StandardCharsets.UTF_8);
        Path selected = DefaultStoragePaths.selectWindowsDirectory(unusablePortablePath, perUser);
        require(selected.equals(perUser),
                "unsafe launch-folder storage did not fall back to the per-user directory");

        Path badTlsDirectory = root.resolve("bad-existing-tls").resolve("saves");
        Files.createDirectories(badTlsDirectory.resolve("server-tls.password"));
        Path tlsFallback = DefaultStoragePaths.selectWindowsDirectory(badTlsDirectory, perUser);
        require(tlsFallback.equals(perUser),
                "unprotectable existing TLS storage did not fall back to the per-user directory");

        Path portable = root.resolve("portable").resolve("saves");
        Files.createDirectories(portable);
        PrivateFileSecurity.ensurePrivateDirectory(portable);
        Path portableSelected = DefaultStoragePaths.selectWindowsDirectory(portable, perUser);
        require(portableSelected.equals(portable.toAbsolutePath().normalize()),
                "secure existing portable storage was not preserved");
        return selected;
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
