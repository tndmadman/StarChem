package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Process-only password handoff from the lobby to the authenticated TLS connection. */
final class PendingPlayerPassword {
    private static final String SCOPED_AUTH_PREFIX = "auth-v2.";
    private static final Map<String, Entry> VALUES = new LinkedHashMap<>();
    private static final Map<String, Boolean> PERSISTENCE = new LinkedHashMap<>();

    private PendingPlayerPassword() { }

    static synchronized void remember(Config config, char[] password, boolean rememberCredential) {
        String key = key(config);
        if (key.isBlank() || password == null || password.length == 0) return;
        Entry previous = VALUES.put(key, new Entry(password, rememberCredential));
        PERSISTENCE.put(key, rememberCredential);
        if (previous != null) previous.close();
    }

    static synchronized Entry take(Config config) {
        String key = key(config);
        return key.isBlank() ? null : VALUES.remove(key);
    }

    static synchronized void clear(Config config) {
        String key = key(config);
        Entry value = VALUES.remove(key);
        PERSISTENCE.remove(key);
        if (value != null) value.close();
    }

    static synchronized void clearAll() {
        VALUES.values().forEach(Entry::close);
        VALUES.clear();
        PERSISTENCE.clear();
    }

    static synchronized boolean shouldPersistCredentialProperty(String propertyKey) {
        String encoded = propertyKey == null ? "" : propertyKey;
        if (encoded.startsWith(SCOPED_AUTH_PREFIX)) encoded = encoded.substring(SCOPED_AUTH_PREFIX.length());
        if (encoded.isBlank() || encoded.indexOf('.') >= 0) return true;
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return true;
        }
        if (raw.startsWith("local-host|")) raw = raw.substring("local-host|".length());
        return PERSISTENCE.getOrDefault(raw, true);
    }

    private static String key(Config config) {
        if (config == null || config.serverAddress == null) return "";
        String host = canonicalHost(config);
        if (host.isBlank()) return "";
        return host + ':' + config.serverAddress.getPort() + '|'
                + Config.clean(config.playerName).toLowerCase(Locale.ROOT);
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

    static final class Entry implements AutoCloseable {
        private final char[] password;
        private final boolean rememberCredential;

        Entry(char[] password, boolean rememberCredential) {
            this.password = password == null ? new char[0] : password.clone();
            this.rememberCredential = rememberCredential;
        }

        char[] password() { return password.clone(); }
        boolean rememberCredential() { return rememberCredential; }

        @Override public void close() { Arrays.fill(password, '\0'); }
    }
}
