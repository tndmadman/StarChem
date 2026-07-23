package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

final class SessionTokenStore {
    private static final String SCOPED_AUTH_PREFIX = "auth-v2.";
    private static final String SESSION_ALIAS_PREFIX = "endpoint-session-alias.";
    private static final String TRUST_ALIAS_PREFIX = "endpoint-trust-alias.";
    private static final Map<String, String> transientAuthDigests = new ConcurrentHashMap<>();

    private SessionTokenStore() { }

    static synchronized StoredSession load(Config config) {
        if (config != null && config.localHostClientMode()) return StoredSession.EMPTY;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return StoredSession.EMPTY;
        return ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            return storedSession(properties, keys.sessionKey());
        });
    }

    static synchronized String serverFingerprint(Config config) {
        if (config == null || config.localHostClientMode()) return "";
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return "";
        return ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            String current = properties.getProperty(serverTlsKey(keys.trustKey()), "");
            if (PasswordAuth.validVerifier(current)) return current.toLowerCase(Locale.ROOT);

            String commander = properties.getProperty(commanderTlsKey(keys.sessionKey()), "");
            if (!PasswordAuth.validVerifier(commander)) return "";
            String migrated = commander.toLowerCase(Locale.ROOT);
            properties.setProperty(serverTlsKey(keys.trustKey()), migrated);
            return migrated;
        });
    }

    static synchronized void saveServerFingerprint(Config config, String fingerprint) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(fingerprint)) return;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            properties.setProperty(serverTlsKey(keys.trustKey()), fingerprint.toLowerCase(Locale.ROOT));
            return null;
        });
    }

    static synchronized boolean replaceServerFingerprint(Config config, String expected, String replacement) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(replacement)) return false;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return false;
        return ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            String storageKey = serverTlsKey(keys.trustKey());
            String current = properties.getProperty(storageKey, "");
            if (!PasswordAuth.validVerifier(current)) {
                String commander = properties.getProperty(commanderTlsKey(keys.sessionKey()), "");
                current = PasswordAuth.validVerifier(commander) ? commander : "";
            }
            if (!current.isBlank() && !current.equalsIgnoreCase(expected)) return false;
            properties.setProperty(storageKey, replacement.toLowerCase(Locale.ROOT));
            return true;
        });
    }

    static synchronized void clearServerFingerprint(Config config) {
        if (config == null || config.localHostClientMode()) return;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            String aliasedTrust = properties.getProperty(trustAliasKey(keys.trustKey()), "");
            String aliasedSession = properties.getProperty(sessionAliasKey(keys.sessionKey()), "");
            removeTrust(properties, keys.trustKey(), keys.sessionKey());
            removeTrust(properties, keys.legacyTrustKey(), keys.legacySessionKey());
            removeTrust(properties, aliasedTrust, aliasedSession);
            properties.remove(trustAliasKey(keys.trustKey()));
            return null;
        });
    }

    static synchronized void save(Config config, String playerId, String token) {
        if (config != null && config.localHostClientMode()) return;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid() || !validPlayerId(playerId) || !validToken(token)) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            String auth = persistedAuthDigest(properties, keys.sessionKey());
            properties.setProperty(keys.sessionKey(), playerId + "|" + token + "|" + auth);
            return null;
        });
    }

    static synchronized String authDigest(Config config) {
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return "";
        String transientAuth = transientAuthDigests.getOrDefault(keys.sessionKey(), "");
        if (PasswordAuth.validVerifier(transientAuth)) return transientAuth;
        if (config != null && config.localHostClientMode()) return "";
        return ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            return persistedAuthDigest(properties, keys.sessionKey());
        });
    }

    static synchronized void saveAuthDigest(Config config, String authDigest) {
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid() || !PasswordAuth.validVerifier(authDigest)) return;
        transientAuthDigests.put(keys.sessionKey(), authDigest);
        if (config != null && config.localHostClientMode()) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            properties.remove(scopedAuthKey(keys.sessionKey()));
            String value = properties.getProperty(keys.sessionKey(), "");
            int first = value.indexOf('|');
            int second = first < 0 ? -1 : value.indexOf('|', first + 1);
            if (first > 0 && second > first) {
                properties.setProperty(keys.sessionKey(), value.substring(0, second + 1) + authDigest);
            } else if (first > 0) {
                properties.setProperty(keys.sessionKey(), value + "|" + authDigest);
            } else {
                properties.setProperty(keys.sessionKey(), "PENDING|PENDING|" + authDigest);
            }
            return null;
        });
    }

    static synchronized void rememberAuthDigestForProcess(Config config, String authDigest) {
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid() || !PasswordAuth.validVerifier(authDigest)) return;
        transientAuthDigests.put(keys.sessionKey(), authDigest);
    }

    static synchronized ScopedCredential scopedCredential(Config config) {
        if (config == null || config.localHostClientMode()) return ScopedCredential.EMPTY;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return ScopedCredential.EMPTY;
        return ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            return scopedCredential(properties, keys.sessionKey());
        });
    }

    static synchronized void saveScopedCredential(Config config, String serverFingerprint,
                                                   String scopedSalt, String verifier) {
        if (config == null || config.localHostClientMode() || !PasswordAuth.validVerifier(serverFingerprint)
                || PasswordAuth.decodeHex(scopedSalt).length != 16 || !PasswordAuth.validVerifier(verifier)) return;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return;
        transientAuthDigests.remove(keys.sessionKey());
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            properties.setProperty(scopedAuthKey(keys.sessionKey()), serverFingerprint.toLowerCase(Locale.ROOT) + "|"
                    + scopedSalt.toLowerCase(Locale.ROOT) + "|" + verifier.toLowerCase(Locale.ROOT));
            clearLegacyAuth(properties, keys.sessionKey());
            return null;
        });
    }

    static synchronized void clearLegacyAuthDigest(Config config) {
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return;
        transientAuthDigests.remove(keys.sessionKey());
        transientAuthDigests.remove(keys.legacySessionKey());
        if (config != null && config.localHostClientMode()) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            clearLegacyAuth(properties, keys.sessionKey());
            clearLegacyAuth(properties, keys.legacySessionKey());
            clearLegacyAuth(properties, properties.getProperty(sessionAliasKey(keys.sessionKey()), ""));
            return null;
        });
    }

    static synchronized void clearScopedCredential(Config config) {
        if (config == null || config.localHostClientMode()) return;
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            properties.remove(scopedAuthKey(keys.sessionKey()));
            properties.remove(scopedAuthKey(keys.legacySessionKey()));
            properties.remove(scopedAuthKey(properties.getProperty(sessionAliasKey(keys.sessionKey()), "")));
            return null;
        });
    }

    private static boolean clearLegacyAuth(Properties properties, String key) {
        if (key == null || key.isBlank()) return false;
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
        EndpointKeys keys = endpointKeys(config);
        if (!keys.valid()) return;
        transientAuthDigests.remove(keys.sessionKey());
        transientAuthDigests.remove(keys.legacySessionKey());
        PendingPlayerPassword.clear(config);
        if (config != null && config.localHostClientMode()) return;
        ClientSessionPropertiesStore.update(properties -> {
            migrateProperties(properties, keys);
            String aliasedSession = properties.getProperty(sessionAliasKey(keys.sessionKey()), "");
            removeSession(properties, keys.sessionKey());
            removeSession(properties, keys.legacySessionKey());
            removeSession(properties, aliasedSession);
            properties.remove(sessionAliasKey(keys.sessionKey()));
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

    private static ScopedCredential scopedCredential(Properties properties, String key) {
        String value = properties.getProperty(scopedAuthKey(key), "");
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3 || !PasswordAuth.validVerifier(parts[0])
                || PasswordAuth.decodeHex(parts[1]).length != 16 || !PasswordAuth.validVerifier(parts[2])) {
            return ScopedCredential.EMPTY;
        }
        return new ScopedCredential(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT),
                parts[2].toLowerCase(Locale.ROOT));
    }

    private static String persistedAuthDigest(Properties properties, String key) {
        String value = properties.getProperty(key, "");
        int first = value.indexOf('|');
        int second = first < 0 ? -1 : value.indexOf('|', first + 1);
        String auth = second < 0 ? "" : value.substring(second + 1);
        return PasswordAuth.validVerifier(auth) ? auth : "";
    }

    private static void migrateProperties(Properties properties, EndpointKeys keys) {
        migrateSession(properties, keys.sessionKey(), keys.legacySessionKey());
        migrateTrust(properties, keys.trustKey(), keys.legacyTrustKey(), keys.sessionKey(), keys.legacySessionKey());
        if (!keys.numericHost() && !properties.containsKey(keys.sessionKey())) {
            HistoricalSession historical = uniqueHistoricalSession(properties, keys);
            if (historical.valid()) {
                migrateSession(properties, keys.sessionKey(), historical.sessionKey());
                migrateTrust(properties, keys.trustKey(), historical.trustKey(),
                        keys.sessionKey(), historical.sessionKey());
            }
        }
    }

    private static void migrateSession(Properties properties, String canonicalKey, String legacyKey) {
        if (canonicalKey.isBlank() || legacyKey.isBlank() || canonicalKey.equals(legacyKey)) return;
        boolean sourcePresent = properties.containsKey(legacyKey) || properties.containsKey(scopedAuthKey(legacyKey));
        copyIfAbsent(properties, canonicalKey, legacyKey);
        copyIfAbsent(properties, scopedAuthKey(canonicalKey), scopedAuthKey(legacyKey));
        if (sourcePresent) properties.setProperty(sessionAliasKey(canonicalKey), legacyKey);
    }

    private static void migrateTrust(Properties properties, String canonicalTrust, String legacyTrust,
                                     String canonicalSession, String legacySession) {
        if (canonicalTrust.isBlank()) return;
        String canonicalStorage = serverTlsKey(canonicalTrust);
        if (PasswordAuth.validVerifier(properties.getProperty(canonicalStorage, ""))) return;
        String fingerprint = firstValidVerifier(properties,
                serverTlsKey(legacyTrust), commanderTlsKey(legacySession), commanderTlsKey(canonicalSession));
        if (!PasswordAuth.validVerifier(fingerprint)) return;
        properties.setProperty(canonicalStorage, fingerprint.toLowerCase(Locale.ROOT));
        if (!legacyTrust.isBlank() && !canonicalTrust.equals(legacyTrust)) {
            properties.setProperty(trustAliasKey(canonicalTrust), legacyTrust);
        }
    }

    private static HistoricalSession uniqueHistoricalSession(Properties properties, EndpointKeys keys) {
        List<HistoricalSession> candidates = new ArrayList<>();
        String playerSuffix = "|" + keys.playerName();
        String portSuffix = ":" + keys.port();
        for (String propertyKey : properties.stringPropertyNames()) {
            if (propertyKey.indexOf('.') >= 0 || propertyKey.equals(keys.sessionKey())
                    || propertyKey.equals(keys.legacySessionKey())) continue;
            String raw = decodeKey(propertyKey);
            if (raw.startsWith("local-host|") || !raw.endsWith(playerSuffix)) continue;
            String endpoint = raw.substring(0, raw.length() - playerSuffix.length());
            if (!endpoint.endsWith(portSuffix)) continue;
            String trust = encodeKey(endpoint);
            candidates.add(new HistoricalSession(propertyKey, trust));
        }
        return candidates.size() == 1 ? candidates.get(0) : HistoricalSession.EMPTY;
    }

    private static void copyIfAbsent(Properties properties, String target, String source) {
        if (target == null || source == null || target.isBlank() || source.isBlank() || target.equals(source)
                || properties.containsKey(target) || !properties.containsKey(source)) return;
        properties.setProperty(target, properties.getProperty(source));
    }

    private static String firstValidVerifier(Properties properties, String... keys) {
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            String value = properties.getProperty(key, "");
            if (PasswordAuth.validVerifier(value)) return value;
        }
        return "";
    }

    private static void removeSession(Properties properties, String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) return;
        properties.remove(sessionKey);
        properties.remove(scopedAuthKey(sessionKey));
    }

    private static void removeTrust(Properties properties, String trustKey, String sessionKey) {
        if (trustKey != null && !trustKey.isBlank()) properties.remove(serverTlsKey(trustKey));
        if (sessionKey != null && !sessionKey.isBlank()) properties.remove(commanderTlsKey(sessionKey));
    }

    private static EndpointKeys endpointKeys(Config config) {
        if (config == null || config.serverAddress == null) return EndpointKeys.EMPTY;
        String canonicalHost = canonicalHost(config);
        if (canonicalHost.isBlank()) return EndpointKeys.EMPTY;
        String legacyHost = resolvedHost(config);
        int port = config.serverAddress.getPort();
        String player = Config.clean(config.playerName).toLowerCase(Locale.ROOT);
        String scope = config.localHostClientMode() ? "local-host|" : "";
        String canonicalEndpoint = canonicalHost + ':' + port;
        String legacyEndpoint = legacyHost + ':' + port;
        return new EndpointKeys(encodeKey(scope + canonicalEndpoint + '|' + player),
                encodeKey(scope + legacyEndpoint + '|' + player), encodeKey(canonicalEndpoint),
                encodeKey(legacyEndpoint), canonicalHost, player, port, numericLiteral(canonicalHost));
    }

    private static String canonicalHost(Config config) {
        String host = config.serverAddress.getHostString();
        if (host == null) return "";
        host = host.trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.length() > 1 && host.endsWith(".")) host = host.substring(0, host.length() - 1);
        host = host.toLowerCase(Locale.ROOT);
        if (numericLiteral(host) && config.serverAddress.getAddress() != null) {
            return config.serverAddress.getAddress().getHostAddress().toLowerCase(Locale.ROOT);
        }
        return host;
    }

    private static String resolvedHost(Config config) {
        return config.serverAddress.getAddress() == null
                ? canonicalHost(config)
                : config.serverAddress.getAddress().getHostAddress().toLowerCase(Locale.ROOT);
    }

    private static boolean numericLiteral(String host) {
        if (host == null || host.isBlank()) return false;
        if (host.indexOf(':') >= 0) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isBlank() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return false;
            try {
                if (Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private static String encodeKey(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKey(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String serverTlsKey(String trustKey) { return "tls-server." + trustKey; }
    private static String commanderTlsKey(String sessionKey) { return "tls." + sessionKey; }
    private static String scopedAuthKey(String key) { return SCOPED_AUTH_PREFIX + key; }
    private static String sessionAliasKey(String key) { return SESSION_ALIAS_PREFIX + key; }
    private static String trustAliasKey(String key) { return TRUST_ALIAS_PREFIX + key; }

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

    private record EndpointKeys(String sessionKey, String legacySessionKey, String trustKey, String legacyTrustKey,
                                String host, String playerName, int port, boolean numericHost) {
        private static final EndpointKeys EMPTY = new EndpointKeys("", "", "", "", "", "", 0, false);
        private boolean valid() { return !sessionKey.isBlank() && !trustKey.isBlank() && port > 0; }
    }

    private record HistoricalSession(String sessionKey, String trustKey) {
        private static final HistoricalSession EMPTY = new HistoricalSession("", "");
        private boolean valid() { return !sessionKey.isBlank() && !trustKey.isBlank(); }
    }
}
