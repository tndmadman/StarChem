package com.tndmadman.rts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Persists a server-only secret used to make stable, unrecognizable salts for nonexistent identities. */
final class AuthDecoySaltStore {
    private static final int SECRET_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] secret;

    AuthDecoySaltStore(Config config) {
        Path directory = config == null ? Path.of("saves") : config.saveDir;
        String saveName = config == null ? "server" : config.saveName;
        Path file = directory.resolve(saveName + "-auth-decoy.key");
        try {
            secret = loadOrCreate(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not initialize authentication decoy secret: " + ex.getMessage(), ex);
        }
    }

    byte[] saltFor(String playerName) {
        String normalized = Config.clean(playerName).toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(("StarChem authentication decoy salt v1|" + normalized)
                    .getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, SALT_BYTES);
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", ex);
        }
    }

    private static byte[] loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) return readSecret(file);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] generated = new byte[SECRET_BYTES];
        RANDOM.nextBytes(generated);
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, generated);
            restrict(temp);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file);
            } catch (FileAlreadyExistsException ex) {
                Files.deleteIfExists(temp);
            }
            restrict(file);
            return readSecret(file);
        } finally {
            Files.deleteIfExists(temp);
            Arrays.fill(generated, (byte)0);
        }
    }

    private static byte[] readSecret(Path file) throws IOException {
        byte[] value = Files.readAllBytes(file);
        if (value.length != SECRET_BYTES) {
            Arrays.fill(value, (byte)0);
            throw new IOException("authentication decoy secret has invalid length");
        }
        return value;
    }

    private static void restrict(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) { }
    }
}
