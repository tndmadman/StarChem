package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Durable server-side tactical fog sidecar with atomic replacement and previous-file recovery. */
final class ServerFogOfWarStore {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 262_144;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRY_LENGTH = 2 * 1024 * 1024;
    private static final String VERSION_KEY = "formatVersion";
    private static final String ENTRY_PREFIX = "entry.";
    private static final ServerFogOfWarStore DISABLED = new ServerFogOfWarStore(null);

    private final Path path;

    private ServerFogOfWarStore(Path path) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
    }

    static ServerFogOfWarStore disabled() { return DISABLED; }

    static ServerFogOfWarStore forTest(Path directory, String saveName) {
        Path base = directory == null ? Path.of(".") : directory;
        return new ServerFogOfWarStore(base.resolve(Config.cleanSaveName(saveName) + "-fog.properties"));
    }

    static ServerFogOfWarStore fromProcessArguments() {
        String[] arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        boolean dedicated = false;
        boolean reset = false;
        Path saveDir = null;
        String saveName = "server";
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if ("--server".equals(argument)) dedicated = true;
            else if ("--new-world".equals(argument)) reset = true;
            else if ("--save-dir".equals(argument) && index + 1 < arguments.length) saveDir = Path.of(arguments[++index]);
            else if ("--save-name".equals(argument) && index + 1 < arguments.length) saveName = arguments[++index];
        }
        if (!dedicated) return DISABLED;
        if (saveDir == null) {
            String environment = System.getenv("STARCHEM_SERVER_SAVE_DIR");
            saveDir = environment == null || environment.isBlank() ? Path.of("saves") : Path.of(environment);
        }
        ServerFogOfWarStore store = new ServerFogOfWarStore(
                saveDir.resolve(Config.cleanSaveName(saveName) + "-fog.properties"));
        if (reset) store.clear();
        return store;
    }

    boolean enabled() { return path != null; }

    synchronized List<ServerFogOfWarState.Stored> load() {
        if (!enabled()) return List.of();
        try {
            return decode(loadProperties(path));
        } catch (IOException | IllegalArgumentException currentFailure) {
            Path previous = previousPath();
            if (Files.isRegularFile(previous)) {
                try {
                    List<ServerFogOfWarState.Stored> recovered = decode(loadProperties(previous));
                    System.err.println("Recovered server fog state from " + previous + " because " + path
                            + " could not be read (" + currentFailure.getClass().getSimpleName() + ").");
                    return recovered;
                } catch (IOException | IllegalArgumentException previousFailure) {
                    currentFailure.addSuppressed(previousFailure);
                }
            }
            System.err.println("Could not load server fog state from " + path + ": " + currentFailure.getMessage());
            return List.of();
        }
    }

    synchronized void save(List<ServerFogOfWarState.Stored> states) {
        if (!enabled()) return;
        Properties properties = encode(states);
        Path parent = path.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, safePrefix(path.getFileName().toString()) + '-', ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "StarChem server-owned tactical fog");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                channel.force(true);
            }
            decode(loadProperties(temporary));
            if (Files.isRegularFile(path)) Files.copy(path, previousPath(), StandardCopyOption.REPLACE_EXISTING);
            moveReplace(temporary, path);
            temporary = null;
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Could not save server fog state to " + path + ": " + ex.getMessage());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }

    synchronized void clear() {
        if (!enabled()) return;
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(previousPath());
        } catch (IOException ex) {
            System.err.println("Could not clear server fog state at " + path + ": " + ex.getMessage());
        }
    }

    private Properties encode(List<ServerFogOfWarState.Stored> states) {
        Properties properties = new Properties();
        properties.setProperty(VERSION_KEY, Integer.toString(FORMAT_VERSION));
        if (states == null || states.isEmpty()) return properties;
        List<ServerFogOfWarState.Stored> sorted = new ArrayList<>(states);
        sorted.removeIf(state -> state == null);
        sorted.sort(Comparator.comparing(ServerFogOfWarState.Stored::playerId)
                .thenComparing(ServerFogOfWarState.Stored::systemId)
                .thenComparingLong(ServerFogOfWarState.Stored::generation));
        int index = 0;
        for (ServerFogOfWarState.Stored state : sorted) {
            if (index >= MAX_ENTRIES) break;
            String encoded = ServerFogOfWarState.encode(state);
            if (encoded.isBlank() || encoded.length() > MAX_ENTRY_LENGTH) continue;
            properties.setProperty(ENTRY_PREFIX + index++, encoded);
        }
        return properties;
    }

    private List<ServerFogOfWarState.Stored> decode(Properties properties) {
        int version;
        try { version = Integer.parseInt(properties.getProperty(VERSION_KEY, "0")); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid server fog format version.", ex); }
        if (version != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported server fog format version: " + version);
        List<String> keys = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(ENTRY_PREFIX)).sorted().toList();
        List<ServerFogOfWarState.Stored> result = new ArrayList<>();
        for (String key : keys) {
            if (result.size() >= MAX_ENTRIES) break;
            String encoded = properties.getProperty(key, "");
            if (encoded.length() > MAX_ENTRY_LENGTH) continue;
            ServerFogOfWarState.Stored state = ServerFogOfWarState.decode(encoded);
            if (state != null) result.add(state);
        }
        return List.copyOf(result);
    }

    private Properties loadProperties(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            Properties empty = new Properties();
            empty.setProperty(VERSION_KEY, Integer.toString(FORMAT_VERSION));
            return empty;
        }
        long size = Files.size(file);
        if (size < 0 || size > MAX_FILE_BYTES) throw new IOException("Server fog file exceeds the size limit.");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private Path previousPath() {
        return path.resolveSibling(path.getFileName() + ".previous");
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safePrefix(String value) {
        String clean = value == null ? "starchem-fog" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        while (clean.length() < 3) clean += '_';
        return clean.substring(0, Math.min(80, clean.length()));
    }
}
