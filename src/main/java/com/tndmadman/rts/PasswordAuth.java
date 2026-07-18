package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

final class PasswordAuth {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordAuth() { }

    static String verifier(String playerName, char[] password) {
        String pass = password == null ? "" : new String(password);
        return verifier(playerName, pass);
    }

    static String verifier(String playerName, String password) {
        String cleanName = Config.clean(playerName).toLowerCase(java.util.Locale.ROOT);
        String material = "StarChem server player password v1|" + cleanName + "|" + (password == null ? "" : password);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    static boolean validVerifier(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!hex) return false;
        }
        return true;
    }

    static byte[] decodeVerifier(String value) {
        if (!validVerifier(value)) return new byte[0];
        return HexFormat.of().parseHex(value.toLowerCase(java.util.Locale.ROOT));
    }

    static String encodeVerifier(byte[] value) {
        return value == null || value.length == 0 ? "" : HexFormat.of().formatHex(value);
    }

    static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    static byte[] serverDigest(byte[] verifier, byte[] salt) {
        if (verifier == null || verifier.length == 0 || salt == null || salt.length == 0) return new byte[0];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(verifier);
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    static boolean matchesServerDigest(byte[] verifier, byte[] salt, byte[] expected) {
        if (expected == null || expected.length == 0) return false;
        byte[] actual = serverDigest(verifier, salt);
        return actual.length > 0 && MessageDigest.isEqual(expected, actual);
    }
}
