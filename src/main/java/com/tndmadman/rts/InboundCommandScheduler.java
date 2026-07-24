package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Fair, bounded scheduler for transport frames entering the authoritative thread.
 * One contiguous poll/drain cycle represents one network tick. Returning null at a
 * budget boundary ends that cycle; the next poll begins a fresh budget window.
 */
final class InboundCommandScheduler {
    enum OfferResult {
        ACCEPTED,
        COALESCED,
        THROTTLED,
        GLOBAL_FULL,
        CONNECTION_OVERFLOW,
        ABUSIVE;

        boolean accepted() { return this == ACCEPTED || this == COALESCED; }
        boolean closeConnection() { return this == CONNECTION_OVERFLOW || this == ABUSIVE; }
    }

    record Limits(
            int maxFrames,
            int maxFramesPerConnection,
            int maxFramesPerDrain,
            long maxDrainNanos,
            double tokensPerSecond,
            double burstTokens,
            int maxThrottleStrikes,
            long throttleWindowNanos
    ) {
        Limits {
            maxFrames = Math.max(1, maxFrames);
            maxFramesPerConnection = Math.max(1, Math.min(maxFrames, maxFramesPerConnection));
            maxFramesPerDrain = Math.max(1, maxFramesPerDrain);
            maxDrainNanos = Math.max(1L, maxDrainNanos);
            tokensPerSecond = Math.max(0.0, tokensPerSecond);
            burstTokens = Math.max(1.0, burstTokens);
            maxThrottleStrikes = Math.max(1, maxThrottleStrikes);
            throttleWindowNanos = Math.max(1L, throttleWindowNanos);
        }

        static Limits defaults() {
            int maxFrames = intProperty("starchem.network.inbound.maxFrames", 256, 32, 16_384);
            int perConnection = intProperty("starchem.network.inbound.perConnectionFrames", 64, 8, maxFrames);
            int perDrain = intProperty("starchem.network.inbound.maxPerTick", 64, 1, 4_096);
            long drainMillis = intProperty("starchem.network.inbound.maxTickMillis", 2, 1, 100);
            double sustained = doubleProperty("starchem.network.inbound.tokensPerSecond", 120.0, 1.0, 100_000.0);
            double burst = doubleProperty("starchem.network.inbound.burstTokens", 240.0, 1.0, 200_000.0);
            int strikes = intProperty("starchem.network.inbound.maxThrottleStrikes", 64, 1, 10_000);
            long strikeSeconds = intProperty("starchem.network.inbound.throttleWindowSeconds", 10, 1, 600);
            return new Limits(maxFrames, perConnection, perDrain, drainMillis * 1_000_000L,
                    sustained, burst, strikes, strikeSeconds * 1_000_000_000L);
        }
    }

    record ConnectionDiagnostics(
            int queuedFrames,
            long acceptedFrames,
            long processedFrames,
            long coalescedFrames,
            long throttledFrames,
            long overflowFrames,
            long processingNanos,
            long maxProcessingNanos
    ) {
        static ConnectionDiagnostics empty() {
            return new ConnectionDiagnostics(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    record Snapshot(int queuedFrames, int activeConnections, long budgetExhaustions) { }

    private final Limits limits;
    private final boolean enforceClientLimits;
    private final LongSupplier nanoTime;
    private final Map<ConnectionId, Lane> lanes = new LinkedHashMap<>();
    private final Deque<ConnectionId> readyConnections = new ArrayDeque<>();
    private final Deque<NetPacket> disconnectEvents = new ArrayDeque<>();
    private int queuedFrames;
    private int drainProcessed;
    private long drainStartedNanos;
    private boolean drainBoundary;
    private ConnectionId processingConnection = ConnectionId.NONE;
    private long processingStartedNanos;
    private long budgetExhaustions;

    InboundCommandScheduler(boolean enforceClientLimits) {
        this(Limits.defaults(), enforceClientLimits, System::nanoTime);
    }

    InboundCommandScheduler(Limits limits, boolean enforceClientLimits, LongSupplier nanoTime) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.enforceClientLimits = enforceClientLimits;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    synchronized OfferResult offer(NetPacket packet) {
        if (packet == null || packet.connectionId() == null || !packet.connectionId().valid()
                || packet.message() == null) {
            return OfferResult.GLOBAL_FULL;
        }
        long now = nanoTime.getAsLong();
        Lane lane = lanes.computeIfAbsent(packet.connectionId(), ignored -> new Lane(limits.burstTokens(), now));
        if (enforceClientLimits && !lane.admit(packet.message(), now)) {
            lane.throttledFrames++;
            if (lane.throttleStrike(now, limits)) return OfferResult.ABUSIVE;
            return OfferResult.THROTTLED;
        }

        String replaceableKey = enforceClientLimits ? replaceableKey(packet.message()) : null;
        if (replaceableKey != null) {
            QueuedFrame previous = lane.replaceable.get(replaceableKey);
            if (previous != null) {
                lane.frames.remove(previous);
                QueuedFrame replacement = new QueuedFrame(packet, replaceableKey);
                lane.frames.addLast(replacement);
                lane.replaceable.put(replaceableKey, replacement);
                lane.coalescedFrames++;
                lane.acceptedFrames++;
                return OfferResult.COALESCED;
            }
        }

        if (lane.frames.size() >= limits.maxFramesPerConnection()) {
            lane.overflowFrames++;
            return OfferResult.CONNECTION_OVERFLOW;
        }
        if (queuedFrames >= limits.maxFrames()) {
            lane.overflowFrames++;
            return OfferResult.GLOBAL_FULL;
        }

        if (lane.frames.isEmpty()) readyConnections.addLast(packet.connectionId());
        QueuedFrame queued = new QueuedFrame(packet, replaceableKey);
        lane.frames.addLast(queued);
        if (replaceableKey != null) lane.replaceable.put(replaceableKey, queued);
        queuedFrames++;
        lane.acceptedFrames++;
        return OfferResult.ACCEPTED;
    }

    synchronized void offerDisconnect(NetPacket packet) {
        if (packet == null || packet.connectionId() == null || !packet.connectionId().valid()) return;
        disconnectEvents.addLast(packet);
    }

    synchronized NetPacket poll() {
        long now = nanoTime.getAsLong();
        completeProcessing(now);

        if (drainBoundary) {
            drainBoundary = false;
            drainProcessed = 0;
            drainStartedNanos = 0;
        }
        if (disconnectEvents.isEmpty() && queuedFrames == 0) {
            resetDrain();
            return null;
        }
        if (drainStartedNanos == 0) drainStartedNanos = now;
        if (drainProcessed >= limits.maxFramesPerDrain()
                || drainProcessed > 0 && now - drainStartedNanos >= limits.maxDrainNanos()) {
            budgetExhaustions++;
            drainBoundary = true;
            return null;
        }

        NetPacket packet = disconnectEvents.pollFirst();
        if (packet == null) packet = pollFairFrame();
        if (packet == null) {
            resetDrain();
            return null;
        }

        drainProcessed++;
        if (!PeerTransport.DISCONNECT_EVENT.equals(packet.message())) {
            processingConnection = packet.connectionId();
            processingStartedNanos = nanoTime.getAsLong();
        }
        return packet;
    }

    synchronized void discard(ConnectionId connectionId) {
        if (connectionId == null || !connectionId.valid()) return;
        completeProcessing(nanoTime.getAsLong());
        Lane lane = lanes.remove(connectionId);
        if (lane != null) queuedFrames = Math.max(0, queuedFrames - lane.frames.size());
        readyConnections.removeIf(connectionId::equals);
    }

    synchronized void clear() {
        lanes.clear();
        readyConnections.clear();
        disconnectEvents.clear();
        queuedFrames = 0;
        resetDrain();
        processingConnection = ConnectionId.NONE;
        processingStartedNanos = 0;
    }

    synchronized int queuedCount() { return queuedFrames + disconnectEvents.size(); }

    synchronized int queuedFor(ConnectionId connectionId) {
        Lane lane = lanes.get(connectionId);
        return lane == null ? 0 : lane.frames.size();
    }

    synchronized ConnectionDiagnostics diagnostics(ConnectionId connectionId) {
        Lane lane = lanes.get(connectionId);
        return lane == null ? ConnectionDiagnostics.empty() : lane.diagnostics();
    }

    synchronized Snapshot snapshot() {
        int active = 0;
        for (Lane lane : lanes.values()) if (!lane.frames.isEmpty()) active++;
        return new Snapshot(queuedCount(), active, budgetExhaustions);
    }

    private NetPacket pollFairFrame() {
        while (!readyConnections.isEmpty()) {
            ConnectionId connectionId = readyConnections.pollFirst();
            Lane lane = lanes.get(connectionId);
            if (lane == null || lane.frames.isEmpty()) continue;
            QueuedFrame queued = lane.frames.pollFirst();
            queuedFrames = Math.max(0, queuedFrames - 1);
            if (queued.replaceableKey() != null) lane.replaceable.remove(queued.replaceableKey(), queued);
            if (!lane.frames.isEmpty()) readyConnections.addLast(connectionId);
            return queued.packet();
        }
        return null;
    }

    private void completeProcessing(long now) {
        if (processingConnection == null || !processingConnection.valid() || processingStartedNanos == 0) return;
        Lane lane = lanes.get(processingConnection);
        if (lane != null) {
            long elapsed = Math.max(0, now - processingStartedNanos);
            lane.processedFrames++;
            lane.processingNanos += elapsed;
            lane.maxProcessingNanos = Math.max(lane.maxProcessingNanos, elapsed);
        }
        processingConnection = ConnectionId.NONE;
        processingStartedNanos = 0;
    }

    private void resetDrain() {
        drainProcessed = 0;
        drainStartedNanos = 0;
        drainBoundary = false;
    }

    private String replaceableKey(String message) {
        if (message == null || !message.startsWith("MOVE|")) return null;
        String[] parts = message.split("\\|", 5);
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) return null;
        return parts[1] + ':' + parts[2];
    }

    private static double commandCost(String message) {
        if (message == null || message.isBlank()) return 1.0;
        int separator = message.indexOf('|');
        String type = separator < 0 ? message : message.substring(0, separator);
        return switch (type) {
            case "MOVE" -> 1.0;
            case "WORK", "ATTACK", "ORDER", "VIEW_SYSTEM" -> 2.0;
            case "BUILD", "PACK", "PROD", "WHTOUCH", "RESPAWN" -> 4.0;
            default -> type.startsWith("DEV") ? 6.0 : 1.0;
        };
    }

    private static int intProperty(String name, int fallback, int min, int max) {
        try {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) return fallback;
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double doubleProperty(String name, double fallback, double min, double max) {
        try {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) return fallback;
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) return fallback;
            return Math.max(min, Math.min(max, parsed));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static final class Lane {
        private final Deque<QueuedFrame> frames = new ArrayDeque<>();
        private final Map<String, QueuedFrame> replaceable = new LinkedHashMap<>();
        private double tokens;
        private long lastRefillNanos;
        private long throttleWindowStartedNanos;
        private int throttleStrikes;
        private long acceptedFrames;
        private long processedFrames;
        private long coalescedFrames;
        private long throttledFrames;
        private long overflowFrames;
        private long processingNanos;
        private long maxProcessingNanos;

        private Lane(double burstTokens, long now) {
            tokens = burstTokens;
            lastRefillNanos = now;
            throttleWindowStartedNanos = now;
        }

        private boolean admit(String message, long now) {
            Limits limits = InboundCommandScheduler.this.limits;
            long elapsed = Math.max(0, now - lastRefillNanos);
            if (elapsed > 0 && limits.tokensPerSecond() > 0) {
                tokens = Math.min(limits.burstTokens(),
                        tokens + elapsed / 1_000_000_000.0 * limits.tokensPerSecond());
                lastRefillNanos = now;
            }
            double cost = commandCost(message);
            if (tokens < cost) return false;
            tokens -= cost;
            return true;
        }

        private boolean throttleStrike(long now, Limits limits) {
            if (now - throttleWindowStartedNanos >= limits.throttleWindowNanos()) {
                throttleWindowStartedNanos = now;
                throttleStrikes = 0;
            }
            throttleStrikes++;
            return throttleStrikes >= limits.maxThrottleStrikes();
        }

        private ConnectionDiagnostics diagnostics() {
            return new ConnectionDiagnostics(frames.size(), acceptedFrames, processedFrames, coalescedFrames,
                    throttledFrames, overflowFrames, processingNanos, maxProcessingNanos);
        }
    }

    private record QueuedFrame(NetPacket packet, String replaceableKey) { }
}