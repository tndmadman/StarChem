package com.tndmadman.rts;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Coordinates client trust/identity metadata while routing reusable authentication secrets to a protected vault. */
final class ClientSessionPropertiesStore {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";
    private static final String COMMENT = "StarChem multiplayer server trust and client identity metadata";
    private static final String VAULT_MARKER = "@credential-vault";
    private static final String SCOPED_AUTH_PREFIX = "auth-v2.";
    private static final String SESSION_ALIAS_PREFIX = "endpoint-session-alias.";
    private static final ConcurrentMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private ClientSessionPropertiesStore() { }

    static <T> T read(Function<Properties, T> reader) {
        if (reader == null) throw new IllegalArgumentException("Reader is required.");
        Path file = storePath();
        return withFileLock(file, () -> {
            Loaded loaded = load(file);
            if (loaded.migrated()) persist(file, loaded.properties(), loaded.currentValid(), loaded.secretKeys());
            return reader.apply(copy(loaded.properties()));
        });
    }

    static <T> T update(Function<Properties, T> mutation) {
        if (mutation == null) throw new IllegalArgumentException("Mutation is required.");
        Path file = storePath();
        return withFileLock(file, () -> {
            Loaded loaded = load(file);
            Properties properties = copy(loaded.properties());
            T result = mutation.apply(properties);
            if (loaded.recovered() || loaded.migrated() || !properties.equals(loaded.properties())) {
                persist(file, properties, loaded.currentValid(), loaded.secretKeys());
            }
            return result;
        });
    }

    static int clearSavedCredentials() {
        return update(properties -> {
            int before = properties.size();
            properties.stringPropertyNames().stream()
                    .filter(key -> isSecretProperty(key) || key.startsWith(SESSION_ALIAS_PREFIX))
                    .toList().forEach(properties::remove);
            return before - properties.size();
        });
    }

    static Path storePath() {
        String override = System.getProperty(STORE_OVERRIDE, "").trim();
        Path path = override.isBlank()
                ? Path.of(System.getProperty("user.home", "."), ".starchem", "sessions.properties")
                : Path.of(override);
        return path.toAbsolutePath().normalize();
    }

    private static <T> T withFileLock(Path file, LockedOperation<T> operation) {
        Object jvmLock = JVM_LOCKS.computeIfAbsent(file, ignored -> new Object());
        synchronized (jvmLock) {
            Path parent = file.getParent();
            if (parent == null) throw new IllegalStateException("Saved multiplayer data has no parent directory: " + file);
            Path lockFile = sibling(file, ".lock");
            try {
                ClientCredentialVault.ensureOwnerOnlyDirectory(parent);
                ClientCredentialVault.ensureOwnerOnlyFile(lockFile);
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    return operation.run();
                }
            } catch (IOException ex) {
                throw failure("Could not coordinate saved multiplayer data", file, ex);
            }
        }
    }

    private static Loaded load(Path file) {
        if (!Files.exists(file)) return new Loaded(new Properties(), false, false, false, Set.of());
        try {
            ClientCredentialVault.ensureOwnerOnlyFile(file);
            return hydrate(loadProperties(file), true, false);
        } catch (IOException | IllegalArgumentException currentFailure) {
            Path previous = sibling(file, ".previous");
            if (Files.isRegularFile(previous)) {
                try {
                    ClientCredentialVault.ensureOwnerOnlyFile(previous);
                    Loaded recovered = hydrate(loadProperties(previous), false, true);
                    System.err.println("Recovered saved multiplayer data from " + previous
                            + " because " + file + " could not be read ("
                            + currentFailure.getClass().getSimpleName() + ").");
                    return recovered;
                } catch (IOException | IllegalArgumentException previousFailure) {
                    currentFailure.addSuppressed(previousFailure);
                }
            }
            throw failure("Could not read saved multiplayer data or its recovery copy", file, currentFailure);
        }
    }

    private static Loaded hydrate(Properties stored, boolean currentValid, boolean recovered) {
        Properties logical = copy(stored);
        Set<String> secretKeys = new LinkedHashSet<>();
        boolean migrated = false;
        for (String key : stored.stringPropertyNames()) {
            if (!isSecretProperty(key)) continue;
            String value = stored.getProperty(key, "");
            if (isVaultMarker(key, value)) {
                secretKeys.add(key);
                String secret = ClientCredentialVault.load(key);
                if (hasSensitiveValue(key, secret)) logical.setProperty(key, secret);
                else logical.remove(key);
            } else if (hasSensitiveValue(key, value)) {
                ClientCredentialVault.save(key, value);
                secretKeys.add(key);
                migrated = true;
            }
        }
        return new Loaded(logical, currentValid, recovered, migrated, Set.copyOf(secretKeys));
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void persist(Path file, Properties properties, boolean preserveCurrent, Set<String> oldSecretKeys) {
        Path previous = sibling(file, ".previous");
        Split split = split(properties);
        Set<String> staleKeys = new LinkedHashSet<>(oldSecretKeys == null ? Set.of() : oldSecretKeys);
        staleKeys.removeAll(split.secretKeys());
        if (split.metadata().isEmpty()) {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(previous);
                for (String key : staleKeys) ClientCredentialVault.delete(key);
                return;
            } catch (IOException ex) {
                throw failure("Could not clear saved multiplayer data", file, ex);
            }
        }

        Path parent = file.getParent();
        String prefix = safePrefix(file.getFileName().toString());
        Path temporary = null;
        try {
            temporary = ClientCredentialVault.createOwnerOnlyTempFile(parent, prefix + "-", ".tmp");
            writeAndSync(temporary, split.metadata());
            loadProperties(temporary);
            if (preserveCurrent && Files.isRegularFile(file)) publishPrevious(file, previous, parent, prefix);
            moveReplace(temporary, file);
            temporary = null;
            ClientCredentialVault.ensureOwnerOnlyFile(file);
            sanitizeExisting(previous, parent, prefix, staleKeys);
            for (String key : staleKeys) ClientCredentialVault.delete(key);
        } catch (IOException | IllegalArgumentException ex) {
            throw failure("Could not save multiplayer data", file, ex);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static Split split(Properties logical) {
        Properties metadata = copy(logical);
        Set<String> secretKeys = new LinkedHashSet<>();
        for (String key : logical.stringPropertyNames()) {
            if (!isSecretProperty(key)) continue;
            String value = logical.getProperty(key, "");
            if (hasSensitiveValue(key, value)) {
                if (PendingPlayerPassword.shouldPersistCredentialProperty(key)) {
                    ClientCredentialVault.save(key, value);
                    metadata.setProperty(key, markerValue(key, value));
                    secretKeys.add(key);
                } else {
                    metadata.remove(key);
                }
            } else if (isVaultMarker(key, value)) {
                secretKeys.add(key);
            }
        }
        return new Split(metadata, Set.copyOf(secretKeys));
    }

    private static void publishPrevious(Path file, Path previous, Path parent, String prefix) throws IOException {
        Properties current = loadProperties(file);
        Properties safe = sanitizedMetadata(current);
        writeReplacement(previous, parent, prefix + "-previous-", safe);
    }

    private static void sanitizeExisting(Path previous, Path parent, String prefix, Set<String> staleKeys) throws IOException {
        if (!Files.isRegularFile(previous)) return;
        Properties stored = loadProperties(previous);
        Properties safe = sanitizedMetadata(stored);
        if (staleKeys != null) for (String key : staleKeys) safe.remove(key);
        if (safe.isEmpty()) {
            Files.deleteIfExists(previous);
        } else if (!safe.equals(stored)) {
            writeReplacement(previous, parent, prefix + "-previous-safe-", safe);
        } else {
            ClientCredentialVault.ensureOwnerOnlyFile(previous);
        }
    }

    private static Properties sanitizedMetadata(Properties source) {
        Properties safe = copy(source);
        for (String key : source.stringPropertyNames()) {
            String value = source.getProperty(key, "");
            if (isSecretProperty(key) && hasSensitiveValue(key, value)) safe.setProperty(key, markerValue(key, value));
        }
        return safe;
    }

    private static void writeReplacement(Path target, Path parent, String prefix, Properties properties) throws IOException {
        Path temporary = null;
        try {
            temporary = ClientCredentialVault.createOwnerOnlyTempFile(parent, prefix, ".tmp");
            writeAndSync(temporary, properties);
            loadProperties(temporary);
            moveReplace(temporary, target);
            temporary = null;
            ClientCredentialVault.ensureOwnerOnlyFile(target);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static void writeAndSync(Path path, Properties properties) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            properties.store(output, COMMENT);
            output.getFD().sync();
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isSecretProperty(String key) {
        return key != null && (key.startsWith(SCOPED_AUTH_PREFIX) || key.indexOf('.') < 0);
    }

    private static boolean isVaultMarker(String key, String value) {
        if (key == null || value == null) return false;
        if (key.startsWith(SCOPED_AUTH_PREFIX)) return VAULT_MARKER.equals(value);
        int separator = value.indexOf('|');
        return separator > 0 && VAULT_MARKER.equals(value.substring(separator + 1));
    }

    private static String markerValue(String key, String value) {
        if (key.startsWith(SCOPED_AUTH_PREFIX)) return VAULT_MARKER;
        int separator = value.indexOf('|');
        String player = separator > 0 ? value.substring(0, separator) : value;
        return player + '|' + VAULT_MARKER;
    }

    private static boolean hasSensitiveValue(String key, String value) {
        if (key == null || value == null || value.isBlank()) return false;
        if (key.startsWith(SCOPED_AUTH_PREFIX)) {
            String[] parts = value.split("\\|", -1);
            return parts.length == 3 && validHex(parts[0], 64) && validHex(parts[1], 32) && validHex(parts[2], 64);
        }
        int first = value.indexOf('|');
        if (first <= 0) return false;
        int second = value.indexOf('|', first + 1);
        String token = second < 0 ? value.substring(first + 1) : value.substring(first + 1, second);
        String auth = second < 0 ? "" : value.substring(second + 1);
        return validToken(token) || validHex(auth, 64);
    }

    private static boolean validToken(String value) {
        if (value == null || value.length() < 32 || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') return false;
        }
        return true;
    }

    private static boolean validHex(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c) && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) return false;
        }
        return true;
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString() + suffix);
    }

    private static String safePrefix(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() >= 3 ? cleaned : "sessions";
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private static IllegalStateException failure(String message, Path file, Exception cause) {
        return new IllegalStateException(message + ": " + file + " (" + cause.getClass().getSimpleName() + ")", cause);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) { }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run();
    }

    private record Loaded(Properties properties, boolean currentValid, boolean recovered,
                          boolean migrated, Set<String> secretKeys) { }
    private record Split(Properties metadata, Set<String> secretKeys) { }
}
