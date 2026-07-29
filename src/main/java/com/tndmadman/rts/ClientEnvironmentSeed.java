package com.tndmadman.rts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ClientEnvironmentSeed {
    private static final byte[] DOMAIN = "StarChemClientEnvironment/v1".getBytes(StandardCharsets.UTF_8);

    private ClientEnvironmentSeed() { }

    static long forActiveSystem(World world) {
        if (world == null) return 0;
        return forSystem(world.systemSeed(), world.activeSystemId());
    }

    static long forSystem(long authoritativeSeed, String systemId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update((byte)0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(authoritativeSeed).array());
            digest.update((byte)0);
            digest.update((systemId == null ? "" : systemId).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
