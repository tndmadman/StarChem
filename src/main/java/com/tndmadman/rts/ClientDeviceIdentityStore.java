package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/** Persists a random client identifier used as a best-effort moderation signal. */
final class ClientDeviceIdentityStore {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";
    private static final String DEVICE_ID_KEY = "client.device.id";

    private ClientDeviceIdentityStore() { }

    static synchronized String deviceId() {
        Properties properties = readProperties();
        String existing = properties.getProperty(DEVICE_ID_KEY, "").trim();
        if (ServerDeviceIdentity.valid(existing)) return existing;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        properties.setProperty(DEVICE_ID_KEY, generated);
        writeProperties(properties);
        return generated;
    }

    private static Properties readProperties() {
        Properties properties = new Properties();
        Path file = storePath();
        if (!Files.isRegularFile(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException ex) {
            System.err.println("Could not read client device identity: " + ex.getMessage());
        }
        return properties;
    }

    private static void writeProperties(Properties properties) {
        Path file = storePath().toAbsolutePath();
        Path parent = file.getParent();
        if (parent == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "sessions-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "StarChem multiplayer sessions and client device identity");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("Could not save client device identity: " + ex.getMessage());
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static Path storePath() {
        String override = System.getProperty(STORE_OVERRIDE, "").trim();
        if (!override.isBlank()) return Path.of(override);
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".starchem", "sessions.properties");
    }
}
