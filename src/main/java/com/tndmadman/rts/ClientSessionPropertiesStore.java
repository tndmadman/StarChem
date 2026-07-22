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
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Coordinates all updates to the shared client session, authentication, trust, and device store. */
final class ClientSessionPropertiesStore {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";
    private static final String COMMENT = "StarChem multiplayer sessions, authentication, server trust, and client identity";
    private static final ConcurrentMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private ClientSessionPropertiesStore() { }

    static <T> T read(Function<Properties, T> reader) {
        if (reader == null) throw new IllegalArgumentException("Reader is required.");
        Path file = storePath();
        return withFileLock(file, () -> reader.apply(copy(load(file).properties())));
    }

    static <T> T update(Function<Properties, T> mutation) {
        if (mutation == null) throw new IllegalArgumentException("Mutation is required.");
        Path file = storePath();
        return withFileLock(file, () -> {
            Loaded loaded = load(file);
            Properties properties = copy(loaded.properties());
            T result = mutation.apply(properties);
            if (loaded.recovered() || !properties.equals(loaded.properties())) {
                persist(file, properties, loaded.currentValid());
            }
            return result;
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
                Files.createDirectories(parent);
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    return operation.run();
                }
            } catch (IOException ex) {
                throw failure("Could not coordinate saved multiplayer data", file, ex);
            }
        }
    }

    private static Loaded load(Path file) {
        if (!Files.exists(file)) return new Loaded(new Properties(), false, false);
        try {
            return new Loaded(loadProperties(file), true, false);
        } catch (IOException | IllegalArgumentException currentFailure) {
            Path previous = sibling(file, ".previous");
            if (Files.isRegularFile(previous)) {
                try {
                    Properties recovered = loadProperties(previous);
                    System.err.println("Recovered saved multiplayer data from " + previous
                            + " because " + file + " could not be read ("
                            + currentFailure.getClass().getSimpleName() + ").");
                    return new Loaded(recovered, false, true);
                } catch (IOException | IllegalArgumentException previousFailure) {
                    currentFailure.addSuppressed(previousFailure);
                }
            }
            throw failure("Could not read saved multiplayer data or its recovery copy", file, currentFailure);
        }
    }

    private static Properties loadProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void persist(Path file, Properties properties, boolean preserveCurrent) {
        Path previous = sibling(file, ".previous");
        if (properties.isEmpty()) {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(previous);
                return;
            } catch (IOException ex) {
                throw failure("Could not clear saved multiplayer data", file, ex);
            }
        }

        Path parent = file.getParent();
        String prefix = safePrefix(file.getFileName().toString());
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, prefix + "-", ".tmp");
            writeAndSync(temporary, properties);
            loadProperties(temporary);
            if (preserveCurrent && Files.isRegularFile(file)) publishPrevious(file, previous, parent, prefix);
            moveReplace(temporary, file);
            temporary = null;
        } catch (IOException | IllegalArgumentException ex) {
            throw failure("Could not save multiplayer data", file, ex);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static void publishPrevious(Path file, Path previous, Path parent, String prefix) throws IOException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, prefix + "-previous-", ".tmp");
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING);
            loadProperties(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveReplace(temporary, previous);
            temporary = null;
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

    private record Loaded(Properties properties, boolean currentValid, boolean recovered) { }
}
