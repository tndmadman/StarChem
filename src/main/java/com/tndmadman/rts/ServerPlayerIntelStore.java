package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Durable server-owned galaxy discovery and viewed-system state for retained players. */
final class ServerPlayerIntelStore {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PLAYERS = 4_096;
    private static final int MAX_SYSTEMS_PER_PLAYER = 4_096;
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final String VERSION_KEY = "formatVersion";
    private static final String PLAYER_PREFIX = "player.";
    private static final ServerPlayerIntelStore DISABLED = new ServerPlayerIntelStore(null);
    private static ServerPlayerIntelStore pending = DISABLED;

    record PlayerIntel(String viewedSystemId, Set<String> knownSystemIds) {
        PlayerIntel {
            viewedSystemId = cleanId(viewedSystemId);
            LinkedHashSet<String> clean = new LinkedHashSet<>();
            if (knownSystemIds != null) {
                for (String systemId : knownSystemIds) {
                    String value = cleanId(systemId);
                    if (!value.isBlank() && clean.size() < MAX_SYSTEMS_PER_PLAYER) clean.add(value);
                }
            }
            if (!viewedSystemId.isBlank()) clean.add(viewedSystemId);
            knownSystemIds = Set.copyOf(clean);
        }
    }

    private final Path path;

    private ServerPlayerIntelStore(Path path) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
    }

    static synchronized void configureForNextCache(Path saveDir, String saveName, boolean reset) {
        Path directory = saveDir == null ? Path.of("saves") : saveDir;
        String name = Config.cleanSaveName(saveName);
        ServerPlayerIntelStore store = new ServerPlayerIntelStore(directory.resolve(name + "-intel.properties"));
        if (reset) store.clear();
        pending = store;
    }

    static synchronized ServerPlayerIntelStore consumeConfigured() {
        ServerPlayerIntelStore configured = pending;
        pending = DISABLED;
        return configured;
    }

    static ServerPlayerIntelStore forTest(Path directory, String saveName) {
        Path base = directory == null ? Path.of(".") : directory;
        return new ServerPlayerIntelStore(base.resolve(Config.cleanSaveName(saveName) + "-intel.properties"));
    }

    synchronized Map<String, PlayerIntel> load() {
        if (!enabled()) return Map.of();
        try {
            return decode(loadProperties(path));
        } catch (IOException | IllegalArgumentException currentFailure) {
            Path previous = previousPath();
            if (Files.isRegularFile(previous)) {
                try {
                    Map<String, PlayerIntel> recovered = decode(loadProperties(previous));
                    System.err.println("Recovered player intel from " + previous + " because " + path
                            + " could not be read (" + currentFailure.getClass().getSimpleName() + ").");
                    return recovered;
                } catch (IOException | IllegalArgumentException previousFailure) {
                    currentFailure.addSuppressed(previousFailure);
                }
            }
            System.err.println("Could not load player intel from " + path + ": " + currentFailure.getMessage());
            return Map.of();
        }
    }

    synchronized void save(Map<String, PlayerIntel> states) {
        if (!enabled()) return;
        Properties properties = encode(states);
        Path parent = path.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, safePrefix(path.getFileName().toString()) + '-', ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "StarChem server-owned player galaxy intel");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                channel.force(true);
            }
            loadProperties(temporary);
            if (Files.isRegularFile(path)) Files.copy(path, previousPath(), StandardCopyOption.REPLACE_EXISTING);
            moveReplace(temporary, path);
            temporary = null;
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Could not save player intel to " + path + ": " + ex.getMessage());
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
            System.err.println("Could not clear player intel at " + path + ": " + ex.getMessage());
        }
    }

    private boolean enabled() { return path != null; }

    private Path previousPath() {
        return path.resolveSibling(path.getFileName() + ".previous");
    }

    private Properties encode(Map<String, PlayerIntel> states) {
        Properties properties = new Properties();
        properties.setProperty(VERSION_KEY, Integer.toString(FORMAT_VERSION));
        if (states == null || states.isEmpty()) return properties;
        List<String> players = new ArrayList<>(states.keySet());
        players.sort(String::compareTo);
        int count = 0;
        for (String rawPlayerId : players) {
            if (count >= MAX_PLAYERS) break;
            String playerId = cleanId(rawPlayerId);
            PlayerIntel state = states.get(rawPlayerId);
            if (playerId.isBlank() || state == null) continue;
            List<String> systems = new ArrayList<>(state.knownSystemIds());
            systems.sort(Comparator.naturalOrder());
            if (systems.size() > MAX_SYSTEMS_PER_PLAYER) systems = systems.subList(0, MAX_SYSTEMS_PER_PLAYER);
            StringBuilder value = new StringBuilder(encodeText(state.viewedSystemId())).append('|');
            for (String systemId : systems) {
                if (value.charAt(value.length() - 1) != '|') value.append(',');
                value.append(encodeText(systemId));
            }
            properties.setProperty(PLAYER_PREFIX + encodeText(playerId), value.toString());
            count++;
        }
        return properties;
    }

    private Map<String, PlayerIntel> decode(Properties properties) {
        int version;
        try { version = Integer.parseInt(properties.getProperty(VERSION_KEY, "0")); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid player intel format version.", ex); }
        if (version != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported player intel format version: " + version);
        Map<String, PlayerIntel> result = new LinkedHashMap<>();
        List<String> keys = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(PLAYER_PREFIX)).sorted().toList();
        for (String key : keys) {
            if (result.size() >= MAX_PLAYERS) break;
            String playerId = cleanId(decodeText(key.substring(PLAYER_PREFIX.length())));
            if (playerId.isBlank()) continue;
            String raw = properties.getProperty(key, "");
            if (raw.length() > 512 * 1024) continue;
            int separator = raw.indexOf('|');
            if (separator < 0) continue;
            String viewed = cleanId(decodeText(raw.substring(0, separator)));
            LinkedHashSet<String> known = new LinkedHashSet<>();
            String list = raw.substring(separator + 1);
            if (!list.isBlank()) {
                for (String encoded : list.split(",", -1)) {
                    String systemId = cleanId(decodeText(encoded));
                    if (!systemId.isBlank()) known.add(systemId);
                    if (known.size() >= MAX_SYSTEMS_PER_PLAYER) break;
                }
            }
            result.put(playerId, new PlayerIntel(viewed, known));
        }
        return Map.copyOf(result);
    }

    private Properties loadProperties(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            Properties empty = new Properties();
            empty.setProperty(VERSION_KEY, Integer.toString(FORMAT_VERSION));
            return empty;
        }
        long size = Files.size(file);
        if (size < 0 || size > MAX_FILE_BYTES) throw new IOException("Player intel file exceeds the size limit.");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String encodeText(String value) {
        String clean = cleanId(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(clean.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        if (value == null || value.isBlank() || value.length() > 1_024) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        String clean = value.replace("|", "").replace("\n", "").replace("\r", "").trim();
        return clean.length() <= 256 ? clean : clean.substring(0, 256);
    }

    private static String safePrefix(String value) {
        String clean = value == null ? "starchem-intel" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        while (clean.length() < 3) clean += '_';
        return clean.substring(0, Math.min(80, clean.length()));
    }
}