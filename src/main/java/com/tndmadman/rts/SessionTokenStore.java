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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

final class SessionTokenStore {
    private static final String STORE_OVERRIDE = "starchem.sessionStore";
    private static final Map<String, String> transientAuthDigests = new ConcurrentHashMap<>();

    private SessionTokenStore() { }

    static synchronized StoredSession load(Config config) {
        if (config != null && config.localHostClientMode()) return StoredSession.EMPTY;
        String key = key(config);
        if (key.isBlank()) return StoredSession.EMPTY;
        Properties properties = readProperties();
        String value = properties.getProperty(key, "");
        int separator = value.indexOf('|');
        if (separator <= 0 || separator >= value.length() - 1) return StoredSession.EMPTY;
        String playerId = value.substring(0, separator);
        int authSeparator = value.indexOf('|', separator + 1);
        String token = authSeparator < 0 ? value.substring(separator + 1) : value.substring(separator + 1, authSeparator);
        String storedAuth = authSeparator < 0 ? "" : value.substring(authSeparator + 1);
        String auth = PasswordAuth.validVerifier(storedAuth) ? storedAuth : transientAuthDigests.getOrDefault(key, "");
        return validPlayerId(playerId) && validToken(token)
      ? new StoredSession(playerId, token, PasswordAuth.validVerifier(auth) ? auth : "")
      : StoredSession.EMPTY;
    }

    static synchronized String serverFingerprint(Config config) {
        if (config == null || config.localHostClientMode()) return "";
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return "";
        Properties properties = readProperties();
        String current = properties.getProperty(serverTlsKey(trustKey), "");
        if (PasswordAuth.validVerifier(current)) return current.toLowerCase(Locale.ROOT);

        String legacy = properties.getProperty(legacyTlsKey(config), "");
        if (!PasswordAuth.validVerifier(legacy)) return "";
        String migrated = legacy.toLowerCase(Locale.ROOT);
        properties.setProperty(serverTlsKey(trustKey), migrated);
        writeProperties(properties);
        return migrated;
    }

    static synchronized void saveServerFingerprint(Config config, String fingerprint) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(fingerprint)) return;
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return;
        Properties properties = readProperties();
        properties.setProperty(serverTlsKey(trustKey), fingerprint.toLowerCase(Locale.ROOT));
        writeProperties(properties);
    }

    static synchronized boolean replaceServerFingerprint(Config config, String expected, String replacement) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(replacement)) return false;
        String current = serverFingerprint(config);
        if (!current.isBlank() && !current.equalsIgnoreCase(expected)) return false;
        saveServerFingerprint(config, replacement);
        return replacement.equalsIgnoreCase(serverFingerprint(config));
    }

    static synchronized void clearServerFingerprint(Config config) {
        if (config == null || config.localHostClientMode()) return;
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return;
        Properties properties = readProperties();
        boolean changed = properties.remove(serverTlsKey(trustKey)) != null;
        changed |= properties.remove(legacyTlsKey(config)) != null;
        if (changed) persistOrDelete(properties);
    }

    static synchronized void save(Config config, String playerId, String token) {
        if (config != null && config.localHostClientMode()) return;
        String key = key(config);
        if (key.isBlank() || !validPlayerId(playerId) || !validToken(token)) return;
        Properties properties = readProperties();
        String auth = persistedAuthDigest(config);
        properties.setProperty(key, playerId + "|" + token + "|" + auth);
        writeProperties(properties);
    }

    static synchronized String authDigest(Config config) {
        String key = key(config);
        if (key.isBlank()) return "";
        String transientAuth = transientAuthDigests.getOrDefault(key, "");
        if (PasswordAuth.validVerifier(transientAuth)) return transientAuth;
        if (config != null && config.localHostClientMode()) return "";
        Properties properties = readProperties();
        String value = properties.getProperty(key, "");
        int first = value.indexOf('|');
        int second = first < 0 ? -1 : value.indexOf('|', first + 1);
        String auth = second < 0 ? "" : value.substring(second + 1);
        return PasswordAuth.validVerifier(auth) ? auth : "";
    }

    private static synchronized String persistedAuthDigest(Config config) {
        if (config != null && config.localHostClientMode()) return "";
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
        transientAuthDigests.put(key, authDigest);
        if (config != null && config.localHostClientMode()) return;
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

    static synchronized void rememberAuthDigestForProcess(Config config, String authDigest) {
        String key = key(config);
        if (key.isBlank() || !PasswordAuth.validVerifier(authDigest)) return;
        transientAuthDigests.put(key, authDigest);
    }

    static synchronized void clear(Config config) {
        String key = key(config);
        if (key.isBlank()) return;
        transientAuthDigests.remove(key);
        if (config != null && config.localHostClientMode()) return;
        Properties properties = readProperties();
        if (properties.remove(key) == null) return;
        persistOrDelete(properties);
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

    private static void persistOrDelete(Properties properties) {
        if (properties.isEmpty()) {
  try { Files.deleteIfExists(storePath()); }
  catch (IOException ex) { System.err.println("Could not clear saved multiplayer data: " + ex.getMessage()); }
        } else {
  writeProperties(properties);
        }
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
      properties.store(output, "StarChem multiplayer sessions and server trust");
  }
  try {
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  } catch (AtomicMoveNotSupportedException ex) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
  }
        } catch (IOException ex) {
  System.err.println("Could not save multiplayer data: " + ex.getMessage());
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
        String host = resolvedHost(config);
        String scope = config.localHostClientMode() ? "local-host|" : "";
        String raw = scope + host + ':' + config.serverAddress.getPort() + '|'
      + Config.clean(config.playerName).toLowerCase(Locale.ROOT);
        return encodeKey(raw);
    }

    private static String trustKey(Config config) {
        if (config == null || config.serverAddress == null) return "";
        return encodeKey(resolvedHost(config) + ':' + config.serverAddress.getPort());
    }

    private static String resolvedHost(Config config) {
        return config.serverAddress.getAddress() == null
      ? config.serverAddress.getHostString().toLowerCase(Locale.ROOT)
      : config.serverAddress.getAddress().getHostAddress().toLowerCase(Locale.ROOT);
    }

    private static String encodeKey(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String serverTlsKey(String trustKey) { return "tls-server." + trustKey; }
    private static String legacyTlsKey(Config config) { return "tls." + key(config); }

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
