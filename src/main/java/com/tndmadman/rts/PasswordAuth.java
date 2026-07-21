package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class PasswordAuth {
    static final int KEY_ITERATIONS = 160_000;
    static final int CLIENT_KEY_ITERATIONS = 210_000;
    static final int AUTH_VERSION_V2 = 2;
    private static final int KEY_BITS = 256;
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

    static String scopedVerifier(String playerName, char[] password, String serverFingerprint, byte[] scopedSalt) {
        if (!validVerifier(serverFingerprint) || scopedSalt == null || scopedSalt.length != 16) return "";
        String cleanName = Config.clean(playerName).toLowerCase(java.util.Locale.ROOT);
        char[] safePassword = password == null ? new char[0] : password.clone();
        byte[] derivationSalt = new byte[0];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("StarChem server-scoped player password v2|".getBytes(StandardCharsets.UTF_8));
            digest.update(decodeVerifier(serverFingerprint));
            digest.update((byte)'|');
            digest.update(cleanName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)'|');
            digest.update(scopedSalt);
            derivationSalt = digest.digest();
            PBEKeySpec spec = new PBEKeySpec(safePassword, derivationSalt, CLIENT_KEY_ITERATIONS, KEY_BITS);
            try {
                return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable.", ex);
        } finally {
            Arrays.fill(safePassword, '\0');
            Arrays.fill(derivationSalt, (byte)0);
        }
    }

    static String scopedVerifier(String playerName, String password, String serverFingerprint, byte[] scopedSalt) {
        char[] chars = password == null ? new char[0] : password.toCharArray();
        try {
            return scopedVerifier(playerName, chars, serverFingerprint, scopedSalt);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    static byte[] newVersionedPasswordSalt() {
        byte[] stored = new byte[17];
        stored[0] = AUTH_VERSION_V2;
        byte[] salt = newSalt();
        System.arraycopy(salt, 0, stored, 1, salt.length);
        Arrays.fill(salt, (byte)0);
        return stored;
    }

    static byte[] versionedPasswordSalt(byte[] digestSalt) {
        if (digestSalt == null || digestSalt.length != 16) return new byte[0];
        byte[] stored = new byte[17];
        stored[0] = AUTH_VERSION_V2;
        System.arraycopy(digestSalt, 0, stored, 1, digestSalt.length);
        return stored;
    }

    static int passwordVersion(byte[] storedSalt) {
        return storedSalt != null && storedSalt.length == 17 && storedSalt[0] == AUTH_VERSION_V2
                ? AUTH_VERSION_V2 : 1;
    }

    static byte[] digestSalt(byte[] storedSalt) {
        if (passwordVersion(storedSalt) == AUTH_VERSION_V2) return Arrays.copyOfRange(storedSalt, 1, 17);
        return storedSalt == null ? new byte[0] : storedSalt.clone();
    }

    static byte[] upgradeSalt(byte[] currentSalt) {
        if (currentSalt == null || currentSalt.length != 16) return new byte[0];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("StarChem password upgrade salt v2|".getBytes(StandardCharsets.UTF_8));
            digest.update(currentSalt);
            return Arrays.copyOf(digest.digest(), 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    static String encodeChallengeSalts(byte[] currentSalt, byte[] scopedSalt) {
        if (currentSalt == null || currentSalt.length != 16 || scopedSalt == null || scopedSalt.length != 16) return "";
        byte[] combined = new byte[32];
        System.arraycopy(currentSalt, 0, combined, 0, 16);
        System.arraycopy(scopedSalt, 0, combined, 16, 16);
        try {
            return HexFormat.of().formatHex(combined);
        } finally {
            Arrays.fill(combined, (byte)0);
        }
    }

    static ChallengeSalts decodeChallengeSalts(String encoded) {
        byte[] combined = decodeHex(encoded);
        if (combined.length != 32) return ChallengeSalts.EMPTY;
        try {
            return new ChallengeSalts(Arrays.copyOfRange(combined, 0, 16), Arrays.copyOfRange(combined, 16, 32));
        } finally {
            Arrays.fill(combined, (byte)0);
        }
    }

    record ChallengeSalts(byte[] currentSalt, byte[] scopedSalt) {
        static final ChallengeSalts EMPTY = new ChallengeSalts(new byte[0], new byte[0]);
        ChallengeSalts {
            currentSalt = currentSalt == null ? new byte[0] : currentSalt.clone();
            scopedSalt = scopedSalt == null ? new byte[0] : scopedSalt.clone();
        }
        boolean valid() { return currentSalt.length == 16 && scopedSalt.length == 16; }
        @Override public byte[] currentSalt() { return currentSalt.clone(); }
        @Override public byte[] scopedSalt() { return scopedSalt.clone(); }
    }

    static String newProcessVerifier(String playerName) {
        String cleanName = Config.clean(playerName).toLowerCase(java.util.Locale.ROOT);
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("StarChem graphical host credential v1|".getBytes(StandardCharsets.UTF_8));
            digest.update(cleanName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)'|');
            digest.update(secret);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        } finally {
            Arrays.fill(secret, (byte)0);
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

    static byte[] decodeHex(String value) {
        if (value == null || value.isBlank() || value.length() % 2 != 0) return new byte[0];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!hex) return new byte[0];
        }
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
            PBEKeySpec spec = new PBEKeySpec(encodeVerifier(verifier).toCharArray(), salt, KEY_ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable.", ex);
        }
    }

    static String newNonce() {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    }

    static boolean validNonce(String value) {
        if (value == null || value.length() < 32 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') return false;
        }
        return true;
    }

    static String challengeProof(byte[] key, String playerName, String nonce) {
        return keyedProof("StarChem auth challenge v1", key, Config.clean(playerName).toLowerCase(java.util.Locale.ROOT), nonce);
    }

    static String sessionProof(byte[] tokenDigest, String playerId, String nonce) {
        return keyedProof("StarChem session resume v1", tokenDigest, Config.clean(playerId), nonce);
    }

    static byte[] tokenDigest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static String keyedProof(String domain, byte[] key, String identity, String nonce) {
        if (key == null || key.length == 0 || !validNonce(nonce)) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((domain + "|" + identity + "|" + nonce)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", ex);
        }
    }

    static boolean proofMatches(byte[] key, String playerName, String nonce, String proof) {
        if (!validVerifier(proof)) return false;
        String expected = challengeProof(key, playerName, nonce);
        return !expected.isBlank() && MessageDigest.isEqual(decodeVerifier(expected), decodeVerifier(proof));
    }

    static boolean sessionProofMatches(byte[] tokenDigest, String playerId, String nonce, String proof) {
        if (!validVerifier(proof)) return false;
        String expected = sessionProof(tokenDigest, playerId, nonce);
        return !expected.isBlank() && MessageDigest.isEqual(decodeVerifier(expected), decodeVerifier(proof));
    }
}
