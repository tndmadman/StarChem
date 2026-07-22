package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;

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

        Config host = Config.parse(new String[]{"--host", "50000", "--dev", "--dev-token", TOKEN});
        expectTrue("host dev mode", host.devMode);
        expectEquals("host token", TOKEN, host.devToken);
        Config client = Config.parse(new String[]{"--join", "127.0.0.1", "50000", "--dev", "--dev-token", TOKEN});
        expectTrue("client mode", client.clientMode());
        expectEquals("client token", TOKEN, client.devToken);
        expectMissingTokenValueRejected();

        expectTrue("unauthorized client request is pending", DevAccessRequestState.pending(true, false));
        expectFalse("authorized client request is not pending", DevAccessRequestState.pending(true, true));
        expectFalse("operator grant does not fabricate a request", DevAccessRequestState.pending(false, true));

        Set<String> requests = new LinkedHashSet<>();
        requests.add("P1");
        requests.add("P2");
        DevAccessRequestState.resolve(requests, "P1");
        expectFalse("resolved request removed", requests.contains("P1"));
        expectTrue("unrelated pending request retained", requests.contains("P2"));

        DevPeerAccess pending = new DevPeerAccess("P1", "Pending", true, false, false);
        DevPeerAccess granted = new DevPeerAccess("P2", "Granted", true, true, false);
        expectTrue("pending peer reported as requested", pending.requested());
        expectFalse("granted peer not reported as pending", granted.requested());

        System.out.println("Dev access policy and request-state validation passed.");
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

    private static void expectMissingTokenValueRejected() {
        try {
            Config.parse(new String[]{"--host", "50000", "--dev", "--dev-token"});
            throw new IllegalStateException("Expected missing --dev-token value to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectTrue(String name, boolean value) {
        if (!value) throw new IllegalStateException("Expected true: " + name);
    }

    private static void expectFalse(String name, boolean value) {
        if (value) throw new IllegalStateException("Expected false: " + name);
    }

    private static void expectEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalStateException(name + ": expected " + expected + ", got " + actual);
    }
}
