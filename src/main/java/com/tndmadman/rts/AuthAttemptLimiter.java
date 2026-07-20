package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded sliding-window admission limiter for unauthenticated player-name probes. */
final class AuthAttemptLimiter {
    private static final int DEFAULT_SOURCE_LIMIT = 60;
    private static final int DEFAULT_IDENTITY_LIMIT = 10;
    private static final long DEFAULT_WINDOW_MS = 60_000;
    private static final int MAX_TRACKED_KEYS = 4096;

    private final int sourceLimit;
    private final int identityLimit;
    private final long windowMs;
    private final Map<String, AttemptWindow> sources = new LinkedHashMap<>();
    private final Map<String, AttemptWindow> identities = new LinkedHashMap<>();

    AuthAttemptLimiter() {
        this(DEFAULT_SOURCE_LIMIT, DEFAULT_IDENTITY_LIMIT, DEFAULT_WINDOW_MS);
    }

    AuthAttemptLimiter(int sourceLimit, int identityLimit, long windowMs) {
        if (sourceLimit < 1 || identityLimit < 1 || windowMs < 1) {
            throw new IllegalArgumentException("Authentication limits must be positive.");
        }
        this.sourceLimit = sourceLimit;
        this.identityLimit = identityLimit;
        this.windowMs = windowMs;
    }

    boolean allow(InetAddress address, String playerName, long now) {
        prune(now);
        String sourceKey = address == null ? "unknown" : address.getHostAddress();
        String identityKey = Config.clean(playerName).toLowerCase(Locale.ROOT);
        AttemptWindow source = current(sources, sourceKey, now);
        AttemptWindow identity = current(identities, identityKey, now);
        if (source.count >= sourceLimit || identity.count >= identityLimit) return false;
        putBounded(sources, sourceKey, new AttemptWindow(source.startedAt, source.count + 1));
        putBounded(identities, identityKey, new AttemptWindow(identity.startedAt, identity.count + 1));
        return true;
    }

    void prune(long now) {
        prune(sources, now);
        prune(identities, now);
    }

    private AttemptWindow current(Map<String, AttemptWindow> values, String key, long now) {
        AttemptWindow current = values.get(key);
        if (current == null || now - current.startedAt >= windowMs) return new AttemptWindow(now, 0);
        return current;
    }

    private void prune(Map<String, AttemptWindow> values, long now) {
        values.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= windowMs);
    }

    private void putBounded(Map<String, AttemptWindow> values, String key, AttemptWindow window) {
        if (!values.containsKey(key) && values.size() >= MAX_TRACKED_KEYS) {
            Iterator<String> iterator = values.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        values.put(key, window);
    }

    private record AttemptWindow(long startedAt, int count) { }
}
