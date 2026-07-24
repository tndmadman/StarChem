package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounds unauthenticated transport work before a peer owns a retained player identity. */
final class PreAuthConnectionGate {
    private static final int MAX_TRACKED_ATTEMPT_KEYS = 4096;

    enum Rejection {
        NONE,
        GLOBAL_LIMIT,
        ADDRESS_LIMIT,
        SUBNET_LIMIT,
        ADDRESS_RATE,
        SUBNET_RATE
    }

    record Decision(boolean allowed, Rejection rejection) {
        static Decision allowedDecision() { return new Decision(true, Rejection.NONE); }
        static Decision rejected(Rejection rejection) { return new Decision(false, rejection); }
    }

    record Limits(int globalLimit, int perAddressLimit, int perSubnetLimit,
                  int addressAttempts, int subnetAttempts, long attemptWindowMs,
                  int authenticationTimeoutMs) {
        Limits {
            if (globalLimit < 1 || perAddressLimit < 1 || perSubnetLimit < 1
                    || addressAttempts < 1 || subnetAttempts < 1
                    || attemptWindowMs < 1 || authenticationTimeoutMs < 1) {
                throw new IllegalArgumentException("Pre-authentication limits must be positive.");
            }
            if (perAddressLimit > globalLimit || perSubnetLimit > globalLimit) {
                throw new IllegalArgumentException("Per-source limits cannot exceed the global limit.");
            }
        }

        static Limits defaults() {
            int global = intProperty("starchem.net.unauthenticatedLimit", 32, 1, 128);
            int perAddress = intProperty("starchem.net.unauthenticatedPerAddress", 8, 1, global);
            int perSubnet = intProperty("starchem.net.unauthenticatedPerSubnet", 24, 1, global);
            int addressAttempts = intProperty("starchem.net.connectionAttemptsPerAddress", 60, 1, 10_000);
            int subnetAttempts = intProperty("starchem.net.connectionAttemptsPerSubnet", 240, 1, 40_000);
            int timeout = intProperty("starchem.net.authenticationTimeoutMs", 10_000, 1_000, 60_000);
            return new Limits(global, perAddress, perSubnet, addressAttempts, subnetAttempts, 60_000, timeout);
        }

        private static int intProperty(String name, int fallback, int min, int max) {
            String value = System.getProperty(name, "").trim();
            if (value.isEmpty()) return fallback;
            try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
            catch (NumberFormatException ignored) { return fallback; }
        }
    }

    record Snapshot(int active, int globalLimit, long accepted, long authenticated,
                    long released, long globalRejected, long addressRejected,
                    long subnetRejected, long addressRateRejected, long subnetRateRejected) { }

    private final Limits limits;
    private final Map<ConnectionId, Lease> leases = new LinkedHashMap<>();
    private final Map<String, Integer> activeByAddress = new LinkedHashMap<>();
    private final Map<String, Integer> activeBySubnet = new LinkedHashMap<>();
    private final Map<String, AttemptWindow> addressAttempts = new LinkedHashMap<>();
    private final Map<String, AttemptWindow> subnetAttempts = new LinkedHashMap<>();
    private long accepted;
    private long authenticated;
    private long released;
    private long globalRejected;
    private long addressRejected;
    private long subnetRejected;
    private long addressRateRejected;
    private long subnetRateRejected;

    PreAuthConnectionGate(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    synchronized Decision tryAcquire(ConnectionId connectionId, InetAddress address, long now) {
        if (connectionId == null || !connectionId.valid()) return Decision.rejected(Rejection.GLOBAL_LIMIT);
        if (leases.containsKey(connectionId)) return Decision.allowedDecision();
        pruneAttempts(now);
        String addressKey = addressKey(address);
        String subnetKey = subnetKey(address);
        AttemptWindow addressWindow = current(addressAttempts, addressKey, now);
        AttemptWindow subnetWindow = current(subnetAttempts, subnetKey, now);
        if (addressWindow.count >= limits.addressAttempts) {
            addressRateRejected++;
            return Decision.rejected(Rejection.ADDRESS_RATE);
        }
        if (subnetWindow.count >= limits.subnetAttempts) {
            subnetRateRejected++;
            return Decision.rejected(Rejection.SUBNET_RATE);
        }
        putBounded(addressAttempts, addressKey, addressWindow.incremented());
        putBounded(subnetAttempts, subnetKey, subnetWindow.incremented());
        if (leases.size() >= limits.globalLimit) {
            globalRejected++;
            return Decision.rejected(Rejection.GLOBAL_LIMIT);
        }
        if (activeByAddress.getOrDefault(addressKey, 0) >= limits.perAddressLimit) {
            addressRejected++;
            return Decision.rejected(Rejection.ADDRESS_LIMIT);
        }
        if (activeBySubnet.getOrDefault(subnetKey, 0) >= limits.perSubnetLimit) {
            subnetRejected++;
            return Decision.rejected(Rejection.SUBNET_LIMIT);
        }
        leases.put(connectionId, new Lease(addressKey, subnetKey, now));
        increment(activeByAddress, addressKey);
        increment(activeBySubnet, subnetKey);
        accepted++;
        return Decision.allowedDecision();
    }

    synchronized boolean authenticate(ConnectionId connectionId) {
        boolean removed = removeLease(connectionId);
        if (removed) authenticated++;
        return removed;
    }

    synchronized boolean release(ConnectionId connectionId) {
        boolean removed = removeLease(connectionId);
        if (removed) released++;
        return removed;
    }

    synchronized boolean expired(ConnectionId connectionId, long now) {
        Lease lease = leases.get(connectionId);
        return lease != null && now - lease.acceptedAt >= limits.authenticationTimeoutMs;
    }

    synchronized int authenticationTimeoutMs() { return limits.authenticationTimeoutMs; }

    synchronized Snapshot snapshot() {
        return new Snapshot(leases.size(), limits.globalLimit, accepted, authenticated, released,
                globalRejected, addressRejected, subnetRejected, addressRateRejected, subnetRateRejected);
    }

    private boolean removeLease(ConnectionId connectionId) {
        Lease lease = leases.remove(connectionId);
        if (lease == null) return false;
        decrement(activeByAddress, lease.addressKey);
        decrement(activeBySubnet, lease.subnetKey);
        return true;
    }

    private void pruneAttempts(long now) {
        prune(addressAttempts, now);
        prune(subnetAttempts, now);
    }

    private void prune(Map<String, AttemptWindow> values, long now) {
        values.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= limits.attemptWindowMs);
    }

    private AttemptWindow current(Map<String, AttemptWindow> values, String key, long now) {
        AttemptWindow current = values.get(key);
        return current == null || now - current.startedAt >= limits.attemptWindowMs
                ? new AttemptWindow(now, 0) : current;
    }

    private void putBounded(Map<String, AttemptWindow> values, String key, AttemptWindow value) {
        if (!values.containsKey(key) && values.size() >= MAX_TRACKED_ATTEMPT_KEYS) {
            Iterator<String> iterator = values.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        values.put(key, value);
    }

    private void increment(Map<String, Integer> values, String key) {
        values.put(key, values.getOrDefault(key, 0) + 1);
    }

    private void decrement(Map<String, Integer> values, String key) {
        int next = values.getOrDefault(key, 0) - 1;
        if (next <= 0) values.remove(key); else values.put(key, next);
    }

    private String addressKey(InetAddress address) {
        return address == null ? "unknown" : address.getHostAddress();
    }

    private String subnetKey(InetAddress address) {
        if (address == null) return "unknown";
        byte[] bytes = address.getAddress();
        int prefixBytes = bytes.length == 4 ? 3 : Math.min(8, bytes.length);
        return bytes.length + ":" + hex(Arrays.copyOf(bytes, prefixBytes));
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private record Lease(String addressKey, String subnetKey, long acceptedAt) { }
    private record AttemptWindow(long startedAt, int count) {
        AttemptWindow incremented() { return new AttemptWindow(startedAt, count + 1); }
    }
}
