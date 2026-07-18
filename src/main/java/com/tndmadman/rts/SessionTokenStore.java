package com.tndmadman.rts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;

final class SessionTokenStore {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";

    private SessionTokenStore() { }

    static synchronized StoredSession load(Config config) {
        String key = key(config);
        if (key.isBlank()) return StoredSession.EMPTY;
        Properties properties = readProperties();
        String value = properties.getProperty(key, "");
        int separator = value.indexOf('|');
        if (separator <= 0 || separator >= value.length() - 1) return StoredSession.EMPTY;
        String playerId = value.substring(0, separator);
        int authSeparator = value.indexOf('|', separator + 1);
        String token = authSeparator < 0 ? value.substring(separator + 1) : value.substring(separator + 1, authSeparator);
        String auth = authSeparator < 0 ? "" : value.substring(authSeparator + 1);
        return validPlayerId(playerId) && validToken(token)
                ? new StoredSession(playerId, token, PasswordAuth.validVerifier(auth) ? auth : "")
                : StoredSession.EMPTY;
    }

    static synchronized void save(Config config, String playerId, String token) {
        String key = key(config);
        if (key.isBlank() || !validPlayerId(playerId) || !validToken(token)) return;
        Properties properties = readProperties();
        String auth = authDigest(config);
        properties.setProperty(key, playerId + "|" + token + "|" + auth);
        writeProperties(properties);
    }

    static synchronized String authDigest(Config config) {
        String key = key(config);
        if (key.isBlank()) return "";
        Properties properties = readProperties();
        String value = properties.getProperty(key, "");
        int first = value.indexOf('|');
        int second = first < 0 ? -1 : value.indexOf('|', first + 1);
        String auth = second < 0 ? "" : value.substring(second + 1);
        return PasswordAuth.validVerifier(auth) ? auth : "";
    }

    static synchronized void saveAuthDigest(Config config, String authDigest) {
        String key = key(config);
        if (key.isBlank() || !PasswordAuth.validVerifier(authDigest)) return;
        Properties properties = readProperties();
        String value = properties.getProperty(key, "");
        int first = value.indexOf('|');
        int second = first < 0 ? -1 : value.indexOf('|', first + 1);
        if (first > 0 && second > first) {
            properties.setProperty(key, value.substring(0, second + 1) + authDigest);
        } else if (first > 0) {
            properties.setProperty(key, value + "|" + authDigest);
        } else {
            properties.setProperty(key, "PENDING|PENDING|" + authDigest);
        }
        writeProperties(properties);
    }

    static synchronized void clear(Config config) {
        String key = key(config);
        if (key.isBlank()) return;
        Properties properties = readProperties();
        if (properties.remove(key) == null) return;
        Path file = storePath();
        if (properties.isEmpty()) {
            try { Files.deleteIfExists(file); }
            catch (IOException ex) { System.err.println("Could not clear saved multiplayer session: " + ex.getMessage()); }
            return;
        }
        writeProperties(properties);
    }

    private static Properties readProperties() {
        Properties properties = new Properties();
        Path file = storePath();
        if (!Files.isRegularFile(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException ex) {
            System.err.println("Could not read saved multiplayer sessions: " + ex.getMessage());
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
                properties.store(output, "StarChem multiplayer resume sessions");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("Could not save multiplayer session: " + ex.getMessage());
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static Path storePath() {
        String override = System.getProperty(STORE_OVERRIDE, "").trim();
        if (!override.isBlank()) return Path.of(override);
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".starchem", "sessions.properties");
    }

    private static String key(Config config) {
        if (config == null || config.serverAddress == null) return "";
        String host = config.serverAddress.getAddress() == null
                ? config.serverAddress.getHostString()
                : config.serverAddress.getAddress().getHostAddress();
        String raw = host + ':' + config.serverAddress.getPort() + '|'
                + Config.clean(config.playerName).toLowerCase(Locale.ROOT);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean validPlayerId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.indexOf('|') < 0;
    }

    private static boolean validToken(String value) {
        if (value == null || value.length() < 32 || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') return false;
        }
        return true;
    }

    record StoredSession(String playerId, String token, String authDigest) {
        static final StoredSession EMPTY = new StoredSession("", "", "");
        boolean valid() { return validPlayerId(playerId) && validToken(token); }
    }
}
