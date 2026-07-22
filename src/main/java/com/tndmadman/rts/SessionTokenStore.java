package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

final class SessionTokenStore {
    private static final String SCOPED_AUTH_PREFIX = "auth-v2.";
    private static final Map<String, String> transientAuthDigests = new ConcurrentHashMap<>();

    private SessionTokenStore() { }

    static synchronized StoredSession load(Config config) {
        if (config != null && config.localHostClientMode()) return StoredSession.EMPTY;
        String key = key(config);
        if (key.isBlank()) return StoredSession.EMPTY;
        return ClientSessionPropertiesStore.read(properties -> storedSession(properties, key));
    }

    static synchronized String serverFingerprint(Config config) {
        if (config == null || config.localHostClientMode()) return "";
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return "";
        return ClientSessionPropertiesStore.update(properties -> {
            String current = properties.getProperty(serverTlsKey(trustKey), "");
            if (PasswordAuth.validVerifier(current)) return current.toLowerCase(Locale.ROOT);

            String legacy = properties.getProperty(legacyTlsKey(config), "");
            if (!PasswordAuth.validVerifier(legacy)) return "";
            String migrated = legacy.toLowerCase(Locale.ROOT);
            properties.setProperty(serverTlsKey(trustKey), migrated);
            return migrated;
        });
    }

    static synchronized void saveServerFingerprint(Config config, String fingerprint) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(fingerprint)) return;
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return;
        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty(serverTlsKey(trustKey), fingerprint.toLowerCase(Locale.ROOT));
            return null;
        });
    }

    static synchronized boolean replaceServerFingerprint(Config config, String expected, String replacement) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(replacement)) return false;
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return false;
        return ClientSessionPropertiesStore.update(properties -> {
            String storageKey = serverTlsKey(trustKey);
            String current = properties.getProperty(storageKey, "");
            if (!PasswordAuth.validVerifier(current)) {
                String legacy = properties.getProperty(legacyTlsKey(config), "");
                current = PasswordAuth.validVerifier(legacy) ? legacy : "";
            }
            if (!current.isBlank() && !current.equalsIgnoreCase(expected)) return false;
            properties.setProperty(storageKey, replacement.toLowerCase(Locale.ROOT));
            return true;
        });
    }

    static synchronized void clearServerFingerprint(Config config) {
        if (config == null || config.localHostClientMode()) return;
        String trustKey = trustKey(config);
        if (trustKey.isBlank()) return;
        ClientSessionPropertiesStore.update(properties -> {
            properties.remove(serverTlsKey(trustKey));
            properties.remove(legacyTlsKey(config));
            return null;
        });
    }

    static synchronized void save(Config config, String playerId, String token) {
        if (config != null && config.localHostClientMode()) return;
        String key = key(config);
        if (key.isBlank() || !validPlayerId(playerId) || !validToken(token)) return;
        ClientSessionPropertiesStore.update(properties -> {
            String auth = persistedAuthDigest(properties, key);
            properties.setProperty(key, playerId + "|" + token + "|" + auth);
            return null;
        });
    }

    static synchronized String authDigest(Config config) {
        String key = key(config);
        if (key.isBlank()) return "";
        String transientAuth = transientAuthDigests.getOrDefault(key, "");
        if (PasswordAuth.validVerifier(transientAuth)) return transientAuth;
        if (config != null && config.localHostClientMode()) return "";
        return ClientSessionPropertiesStore.read(properties -> persistedAuthDigest(properties, key));
    }

    static synchronized void saveAuthDigest(Config config, String authDigest) {
        String key = key(config);
        if (key.isBlank() || !PasswordAuth.validVerifier(authDigest)) return;
        transientAuthDigests.put(key, authDigest);
        if (config != null && config.localHostClientMode()) return;
        ClientSessionPropertiesStore.update(properties -> {
            properties.remove(scopedAuthKey(key));
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
            return null;
        });
    }

    static synchronized void rememberAuthDigestForProcess(Config config, String authDigest) {
        String key = key(config);
        if (key.isBlank() || !PasswordAuth.validVerifier(authDigest)) return;
        transientAuthDigests.put(key, authDigest);
    }

    static synchronized ScopedCredential scopedCredential(Config config) {
        if (config == null || config.localHostClientMode()) return ScopedCredential.EMPTY;
        String key = key(config);
        if (key.isBlank()) return ScopedCredential.EMPTY;
        return ClientSessionPropertiesStore.read(properties -> {
            String value = properties.getProperty(scopedAuthKey(key), "");
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3 || !PasswordAuth.validVerifier(parts[0])
                    || PasswordAuth.decodeHex(parts[1]).length != 16 || !PasswordAuth.validVerifier(parts[2])) {
                return ScopedCredential.EMPTY;
            }
            return new ScopedCredential(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT),
                    parts[2].toLowerCase(Locale.ROOT));
        });
    }

    static synchronized void saveScopedCredential(Config config, String serverFingerprint,
                                                   String scopedSalt, String verifier) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(serverFingerprint)
                || PasswordAuth.decodeHex(scopedSalt).length != 16 || !PasswordAuth.validVerifier(verifier)) return;
        String key = key(config);
        if (key.isBlank()) return;
        transientAuthDigests.remove(key);
        ClientSessionPropertiesStore.update(properties -> {
            properties.setProperty(scopedAuthKey(key), serverFingerprint.toLowerCase(Locale.ROOT) + "|"
                    + scopedSalt.toLowerCase(Locale.ROOT) + "|" + verifier.toLowerCase(Locale.ROOT));
            clearLegacyAuth(properties, key);
            return null;
        });
    }

    static synchronized void clearLegacyAuthDigest(Config config) {
        String key = key(config);
        if (key.isBlank()) return;
        transientAuthDigests.remove(key);
        if (config != null && config.localHostClientMode()) return;
        ClientSessionPropertiesStore.update(properties -> {
            clearLegacyAuth(properties, key);
            return null;
        });
    }

    static synchronized void clearScopedCredential(Config config) {
        if (config == null || config.localHostClientMode()) return;
        String key = key(config);
        if (key.isBlank()) return;
        ClientSessionPropertiesStore.update(properties -> {
            properties.remove(scopedAuthKey(key));
            return null;
        });
    }

    private static boolean clearLegacyAuth(Properties properties, String key) {
        String value = properties.getProperty(key, "");
        int first = value.indexOf('|');
        if (first <= 0) return false;
        int second = value.indexOf('|', first + 1);
        if (second < 0) return false;
        String replacement = value.substring(0, second + 1);
        if (replacement.equals(value)) return false;
        properties.setProperty(key, replacement);
        return true;
    }

    static synchronized void clear(Config config) {
        String key = key(config);
        if (key.isBlank()) return;
        transientAuthDigests.remove(key);
        PendingPlayerPassword.clear(config);
        if (config != null && config.localHostClientMode()) return;
        ClientSessionPropertiesStore.update(properties -> {
            properties.remove(key);
            properties.remove(scopedAuthKey(key));
            return null;
        });
    }

    private static StoredSession storedSession(Properties properties, String key) {
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

    private static String persistedAuthDigest(Properties properties, String key) {
        String value = properties.getProperty(key, "");
        int first = value.indexOf('|');
        int second = first < 0 ? -1 : value.indexOf('|', first + 1);
        String auth = second < 0 ? "" : value.substring(second + 1);
        return PasswordAuth.validVerifier(auth) ? auth : "";
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
    private static String scopedAuthKey(String key) { return SCOPED_AUTH_PREFIX + key; }

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

    record ScopedCredential(String serverFingerprint, String scopedSalt, String verifier) {
        static final ScopedCredential EMPTY = new ScopedCredential("", "", "");
        boolean valid() {
            return PasswordAuth.validVerifier(serverFingerprint)
                    && PasswordAuth.decodeHex(scopedSalt).length == 16
                    && PasswordAuth.validVerifier(verifier);
        }
        boolean matches(String fingerprint, String salt) {
            return valid() && serverFingerprint.equalsIgnoreCase(fingerprint) && scopedSalt.equalsIgnoreCase(salt);
        }
    }
}
