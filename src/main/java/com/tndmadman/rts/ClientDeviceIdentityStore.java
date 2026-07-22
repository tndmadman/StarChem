package com.tndmadman.rts;

import java.security.SecureRandom;
import java.util.Base64;

/** Persists a random client identifier used as a best-effort moderation signal. */
final class ClientDeviceIdentityStore {
    private static final String DEVICE_ID_KEY = "client.device.id";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientDeviceIdentityStore() { }

    static String deviceId() {
        return ClientSessionPropertiesStore.update(properties -> {
            String existing = properties.getProperty(DEVICE_ID_KEY, "").trim();
            if (ServerDeviceIdentity.valid(existing)) return existing;
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            properties.setProperty(DEVICE_ID_KEY, generated);
            return generated;
        });
    }
}
