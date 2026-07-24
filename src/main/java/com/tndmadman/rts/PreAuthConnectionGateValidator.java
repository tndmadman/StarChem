package com.tndmadman.rts;

import java.net.InetAddress;

/** Deterministic validation for unauthenticated connection limits and deadlines. */
final class PreAuthConnectionGateValidator {
    private PreAuthConnectionGateValidator() { }

    static void validate() throws Exception {
        validateConcurrentLimitsAndRelease();
        validateAttemptRateLimits();
        validateIpv6SubnetLimit();
    }

    private static void validateConcurrentLimitsAndRelease() throws Exception {
        PreAuthConnectionGate gate = new PreAuthConnectionGate(
                new PreAuthConnectionGate.Limits(3, 2, 3, 20, 40, 1_000, 100));
        InetAddress first = InetAddress.getByName("192.0.2.10");
        InetAddress second = InetAddress.getByName("192.0.2.11");
        InetAddress otherSubnet = InetAddress.getByName("198.51.100.10");
        long now = 10_000;
        ConnectionId c1 = new ConnectionId(1);
        ConnectionId c2 = new ConnectionId(2);
        ConnectionId c3 = new ConnectionId(3);
        ConnectionId c4 = new ConnectionId(4);

        require(gate.tryAcquire(c1, first, now).allowed(), "first pre-auth connection was rejected");
        require(gate.tryAcquire(c2, first, now).allowed(), "second NAT-shared connection was rejected");
        require(gate.tryAcquire(c3, first, now).rejection() == PreAuthConnectionGate.Rejection.ADDRESS_LIMIT,
                "per-address concurrent limit was not enforced");
        require(gate.tryAcquire(c3, second, now).allowed(), "same-subnet connection below its cap was rejected");
        require(gate.tryAcquire(c4, otherSubnet, now).rejection() == PreAuthConnectionGate.Rejection.GLOBAL_LIMIT,
                "global unauthenticated cap was not enforced");
        require(gate.expired(c2, now + 100), "absolute authentication deadline was not enforced");
        require(gate.authenticate(c1), "authentication did not release the pre-auth permit");
        require(gate.tryAcquire(c4, otherSubnet, now + 1).allowed(),
                "released authentication permit did not admit another connection");
        require(gate.release(c2), "closing an unauthenticated connection did not release its permit");
        require(gate.release(c3), "same-subnet permit was not released");
        require(gate.release(c4), "replacement permit was not released");
        PreAuthConnectionGate.Snapshot snapshot = gate.snapshot();
        require(snapshot.active() == 0, "pre-auth permits leaked after release");
        require(snapshot.authenticated() == 1, "authenticated transition was not diagnosed");
        require(snapshot.globalRejected() == 1 && snapshot.addressRejected() == 1,
                "pre-auth rejection diagnostics were incorrect");
    }

    private static void validateAttemptRateLimits() throws Exception {
        InetAddress address = InetAddress.getByName("203.0.113.20");
        PreAuthConnectionGate addressGate = new PreAuthConnectionGate(
                new PreAuthConnectionGate.Limits(8, 8, 8, 2, 20, 1_000, 1_000));
        require(addressGate.tryAcquire(new ConnectionId(10), address, 1_000).allowed(),
                "first address attempt was rejected");
        addressGate.release(new ConnectionId(10));
        require(addressGate.tryAcquire(new ConnectionId(11), address, 1_001).allowed(),
                "second address attempt was rejected");
        addressGate.release(new ConnectionId(11));
        require(addressGate.tryAcquire(new ConnectionId(12), address, 1_002).rejection()
                        == PreAuthConnectionGate.Rejection.ADDRESS_RATE,
                "per-address connection-attempt rate was not enforced");
        require(addressGate.tryAcquire(new ConnectionId(13), address, 2_001).allowed(),
                "expired address attempt window did not recover");

        PreAuthConnectionGate subnetGate = new PreAuthConnectionGate(
                new PreAuthConnectionGate.Limits(8, 8, 8, 20, 2, 1_000, 1_000));
        require(subnetGate.tryAcquire(new ConnectionId(20), InetAddress.getByName("198.18.1.1"), 5_000).allowed(),
                "first subnet attempt was rejected");
        subnetGate.release(new ConnectionId(20));
        require(subnetGate.tryAcquire(new ConnectionId(21), InetAddress.getByName("198.18.1.2"), 5_001).allowed(),
                "second subnet attempt was rejected");
        subnetGate.release(new ConnectionId(21));
        require(subnetGate.tryAcquire(new ConnectionId(22), InetAddress.getByName("198.18.1.3"), 5_002).rejection()
                        == PreAuthConnectionGate.Rejection.SUBNET_RATE,
                "subnet connection-attempt rate was not enforced");
    }

    private static void validateIpv6SubnetLimit() throws Exception {
        PreAuthConnectionGate gate = new PreAuthConnectionGate(
                new PreAuthConnectionGate.Limits(4, 4, 1, 20, 20, 1_000, 1_000));
        require(gate.tryAcquire(new ConnectionId(30), InetAddress.getByName("2001:db8:1:2::1"), 8_000).allowed(),
                "first IPv6 /64 connection was rejected");
        require(gate.tryAcquire(new ConnectionId(31), InetAddress.getByName("2001:db8:1:2::2"), 8_001).rejection()
                        == PreAuthConnectionGate.Rejection.SUBNET_LIMIT,
                "IPv6 /64 concurrent subnet limit was not enforced");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
