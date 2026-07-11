package com.tndmadman.rts;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class DevAccessPolicy {
    private static final int MIN_TOKEN_LENGTH = 16;
    private static final int MAX_TOKEN_LENGTH = 128;

    private DevAccessPolicy() { }

    static boolean authorize(boolean hostDevMode, boolean dedicatedServer, InetAddress address,
                             boolean requestedDev, String configuredToken, String suppliedToken) {
        if (!hostDevMode || !requestedDev || address == null) return false;
        if (!dedicatedServer && address.isLoopbackAddress()) return true;
        String expected = normalizeToken(configuredToken);
        String supplied = normalizeToken(suppliedToken);
        if (expected.isEmpty() || supplied.isEmpty()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    static String requireToken(String value) {
        String token = normalizeToken(value);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Dev token must be 16-128 characters using letters, numbers, '.', '_', '~', or '-'.");
        }
        return token;
    }

    static String normalizeToken(String value) {
        if (value == null) return "";
        String token = value.trim();
        if (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH) return "";
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '~' || c == '-';
            if (!allowed) return "";
        }
        return token;
    }
}
