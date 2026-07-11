package com.tndmadman.rts;

import java.net.InetAddress;

public final class DevAccessPolicyValidator {
    private static final String TOKEN = "dev-token-0123456789abcdef";

    private DevAccessPolicyValidator() { }

    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress remote = InetAddress.getByName("203.0.113.8");

        expectDenied("server dev mode disabled", false, false, remote, true, TOKEN, TOKEN);
        expectDenied("client did not request dev access", true, false, remote, false, TOKEN, TOKEN);
        expectAllowed("local graphical host client", true, false, loopback, true, "", "");
        expectDenied("dedicated loopback without credential", true, true, loopback, true, "", "");
        expectDenied("remote client without configured credential", true, false, remote, true, "", TOKEN);
        expectDenied("remote client without supplied credential", true, false, remote, true, TOKEN, "");
        expectDenied("remote client with wrong credential", true, false, remote, true, TOKEN, TOKEN + "x");
        expectAllowed("remote client with matching credential", true, false, remote, true, TOKEN, TOKEN);
        expectAllowed("dedicated remote client with matching credential", true, true, remote, true, TOKEN, TOKEN);

        expectEquals("normalized valid token", TOKEN, DevAccessPolicy.requireToken("  " + TOKEN + "  "));
        expectInvalid("short token", "short");
        expectInvalid("delimiter token", "0123456789abcdef|bad");
        expectInvalid("space token", "0123456789abcdef bad");

        System.out.println("Dev access policy validation passed.");
    }

    private static void expectAllowed(String name, boolean hostDevMode, boolean dedicatedServer, InetAddress address,
                                      boolean requestedDev, String configuredToken, String suppliedToken) {
        if (!DevAccessPolicy.authorize(hostDevMode, dedicatedServer, address, requestedDev, configuredToken, suppliedToken)) {
            throw new IllegalStateException("Expected allowed: " + name);
        }
    }

    private static void expectDenied(String name, boolean hostDevMode, boolean dedicatedServer, InetAddress address,
                                     boolean requestedDev, String configuredToken, String suppliedToken) {
        if (DevAccessPolicy.authorize(hostDevMode, dedicatedServer, address, requestedDev, configuredToken, suppliedToken)) {
            throw new IllegalStateException("Expected denied: " + name);
        }
    }

    private static void expectInvalid(String name, String token) {
        try {
            DevAccessPolicy.requireToken(token);
            throw new IllegalStateException("Expected invalid token: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalStateException(name + ": expected " + expected + ", got " + actual);
    }
}
