package com.tndmadman.rts;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Process-only password handoff from the lobby to the authenticated TLS connection. */
final class PendingPlayerPassword {
    private static final Map<String, Entry> VALUES = new LinkedHashMap<>();

    private PendingPlayerPassword() { }

    static synchronized void remember(Config config, char[] password, boolean rememberCredential) {
        String key = key(config);
        if (key.isBlank() || password == null || password.length == 0) return;
        Entry previous = VALUES.put(key, new Entry(password, rememberCredential));
        if (previous != null) previous.close();
    }

    static synchronized Entry take(Config config) {
        String key = key(config);
        return key.isBlank() ? null : VALUES.remove(key);
    }

    static synchronized void clear(Config config) {
        Entry value = VALUES.remove(key(config));
        if (value != null) value.close();
    }

    private static String key(Config config) {
        if (config == null || config.serverAddress == null) return "";
        String host = config.serverAddress.getHostString().toLowerCase(Locale.ROOT);
        return host + ':' + config.serverAddress.getPort() + '|'
                + Config.clean(config.playerName).toLowerCase(Locale.ROOT);
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
