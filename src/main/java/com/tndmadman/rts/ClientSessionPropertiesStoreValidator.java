package com.tndmadman.rts;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ClientSessionPropertiesStoreValidator {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";
    private static final String AUTH = "11".repeat(32);
    private static final String FINGERPRINT = "22".repeat(32);
    private static final String TOKEN = "A".repeat(43);

    private ClientSessionPropertiesStoreValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            runChild(args);
            return;
        }
        validate();
        System.out.println("Client session properties coordination validation passed.");
    }

    static void validate() throws Exception {
        Path directory = Files.createTempDirectory("starchem-client-store-");
        Path store = directory.resolve("sessions.properties");
        String previousOverride = System.getProperty(STORE_OVERRIDE);
        System.setProperty(STORE_OVERRIDE, store.toString());
        try {
            validateStorePath(store);
            validatePublicStores(store);
            validateThreadWriters(store);
            validateProcessWriters(store, directory);
            validateRecovery(store);
            validateNoTemporaryFiles(directory);
        } finally {
            if (previousOverride == null) System.clearProperty(STORE_OVERRIDE);
            else System.setProperty(STORE_OVERRIDE, previousOverride);
            deleteTree(directory);
        }
    }

    private static void validateStorePath(Path expected) {
        require(ClientSessionPropertiesStore.storePath().equals(expected.toAbsolutePath().normalize()),
                "store override did not resolve to the configured path");
    }

    private static void validatePublicStores(Path store) throws Exception {
        Config config = Config.join("Coordinated Client", "127.0.0.1", 50123, false);
        SessionTokenStore.saveAuthDigest(config, AUTH);
        SessionTokenStore.save(config, "P7", TOKEN);
        SessionTokenStore.saveServerFingerprint(config, FINGERPRINT);
        String device = ClientDeviceIdentityStore.deviceId();

        SessionTokenStore.StoredSession saved = SessionTokenStore.load(config);
        require(saved.valid(), "session did not persist through the coordinated store");
        require("P7".equals(saved.playerId()) && TOKEN.equals(saved.token()),
                "session identity or token changed during persistence");
        require(AUTH.equals(saved.authDigest()), "authentication digest was lost during session persistence");
        require(FINGERPRINT.equals(SessionTokenStore.serverFingerprint(config)),
                "TLS fingerprint was lost during session persistence");
        require(device.equals(ClientDeviceIdentityStore.deviceId()),
                "device identity was not stable across coordinated reads");

        Properties properties = loadProperties(store);
        require(device.equals(properties.getProperty("client.device.id")),
                "device identity was not stored with session data");
    }

    private static void validateThreadWriters(Path store) throws Exception {
        int count = 16;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    ClientSessionPropertiesStore.update(properties -> {
                        properties.setProperty("thread." + index, "value-" + index);
                        sleep(5);
                        return null;
                    });
                }));
            }
            require(ready.await(10, TimeUnit.SECONDS), "thread writers did not become ready");
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Properties properties = loadProperties(store);
        for (int i = 0; i < count; i++) {
            require(("value-" + i).equals(properties.getProperty("thread." + i)),
                    "thread writer lost property thread." + i);
        }
        require(properties.containsKey("client.device.id"), "thread writers erased the client device identity");
    }

    private static void validateProcessWriters(Path store, Path directory) throws Exception {
        Path ready = directory.resolve("first-child-ready");
        Process first = startChild(store, "first", ready, 500);
        waitForFile(ready);
        List<Process> remaining = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            remaining.add(startChild(store, "writer-" + i, directory.resolve("ready-" + i), 0));
        }
        requireProcess(first, "first coordinated writer");
        for (int i = 0; i < remaining.size(); i++) requireProcess(remaining.get(i), "coordinated writer " + i);

        Properties properties = loadProperties(store);
        require("first".equals(properties.getProperty("process.first")),
                "waiting process writer erased the first process update");
        for (int i = 0; i < 8; i++) {
            require(("writer-" + i).equals(properties.getProperty("process.writer-" + i)),
                    "process writer lost property process.writer-" + i);
        }
        for (int i = 0; i < 16; i++) {
            require(("value-" + i).equals(properties.getProperty("thread." + i)),
                    "process writers erased thread property thread." + i);
        }
        require(properties.containsKey("client.device.id"), "process writers erased the client device identity");
    }

    private static void validateRecovery(Path store) throws Exception {
        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty("recovery.base", "retained");
            return null;
        });
        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty("recovery.latest", "current-only");
            return null;
        });
        Path previous = store.resolveSibling(store.getFileName().toString() + ".previous");
        require(Files.isRegularFile(previous), "verified previous recovery copy was not created");

        Files.writeString(store, "broken=\\u12", StandardCharsets.ISO_8859_1);
        String recovered = ClientSessionPropertiesStore.read(
                properties -> properties.getProperty("recovery.base", ""));
        require("retained".equals(recovered), "malformed current store did not recover from the previous copy");

        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty("recovery.after", "restored");
            return null;
        });
        Properties repaired = loadProperties(store);
        require("retained".equals(repaired.getProperty("recovery.base")),
                "recovery did not preserve the verified previous state");
        require("restored".equals(repaired.getProperty("recovery.after")),
                "recovery update was not published to the current store");
    }

    private static void validateNoTemporaryFiles(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            require(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "coordinated store left a temporary file behind");
        }
    }

    private static void runChild(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected child, store, id, ready path, and hold milliseconds.");
        Path store = Path.of(args[1]);
        String id = args[2];
        Path ready = Path.of(args[3]);
        long holdMillis = Long.parseLong(args[4]);
        System.setProperty(STORE_OVERRIDE, store.toString());
        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty("process." + id, id);
            try {
                Files.writeString(ready, "ready", StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not publish child readiness.", ex);
            }
            sleep(holdMillis);
            return null;
        });
    }

    private static Process startChild(Path store, String id, Path ready, long holdMillis) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                ClientSessionPropertiesStoreValidator.class.getName(), "child", store.toString(), id,
                ready.toString(), Long.toString(holdMillis)).redirectErrorStream(true).start();
    }

    private static void requireProcess(Process process, String name) throws Exception {
        byte[] output;
        try (InputStream input = process.getInputStream()) {
            output = input.readAllBytes();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(name + " timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(name + " failed: " + new String(output, StandardCharsets.UTF_8).trim());
        }
    }

    private static void waitForFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        require(Files.exists(path), "first child did not acquire the store lock");
    }

    private static Properties loadProperties(Path store) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(store)) {
            properties.load(input);
        }
        return properties;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating validation.", ex);
        }
    }

    private static void sleep(long milliseconds) {
        if (milliseconds <= 0) return;
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the validation transaction.", ex);
        }
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
}
