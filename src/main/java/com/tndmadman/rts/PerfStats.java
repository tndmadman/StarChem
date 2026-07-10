package com.tndmadman.rts;

final class PerfStats {
    private static final long SAMPLE_NANOS = 250_000_000L;

    private long windowStartNanos = System.nanoTime();
    private long lastFrameNanos;
    private long frames;
    private long frameIntervals;
    private long frameIntervalTotalNanos;
    private long frameIntervalMaxNanos;
    private long drawSamples;
    private long drawTotalNanos;
    private long drawMaxNanos;
    private long updateSamples;
    private long updateTotalNanos;
    private long updateMaxNanos;
    private long serverUpdateSamples;
    private long serverUpdateTotalNanos;
    private long serverUpdateMaxNanos;
    private long networkSamples;
    private long networkTotalNanos;
    private long networkMaxNanos;
    private long packetsSent;
    private long packetBytesSent;
    private long packetsReceived;
    private long packetBytesReceived;
    private long snapshotsSent;
    private long snapshotBytesSent;
    private long snapshotsReceived;
    private long snapshotBytesReceived;
    private long reliableResends;
    private long lastSnapshotNanos;
    private int pendingReliable;
    private double rttMs = -1.0;
    private PerfSnapshot cached = PerfSnapshot.empty();

    synchronized void frameStarted(long nowNanos) {
        frames++;
        if (lastFrameNanos > 0) {
            long elapsed = Math.max(0, nowNanos - lastFrameNanos);
            frameIntervals++;
            frameIntervalTotalNanos += elapsed;
            frameIntervalMaxNanos = Math.max(frameIntervalMaxNanos, elapsed);
        }
        lastFrameNanos = nowNanos;
    }

    synchronized void recordDraw(long nanos) {
        drawSamples++;
        drawTotalNanos += Math.max(0, nanos);
        drawMaxNanos = Math.max(drawMaxNanos, nanos);
    }

    synchronized void recordUpdate(long nanos) {
        updateSamples++;
        updateTotalNanos += Math.max(0, nanos);
        updateMaxNanos = Math.max(updateMaxNanos, nanos);
    }

    synchronized void recordServerUpdate(long nanos) {
        serverUpdateSamples++;
        serverUpdateTotalNanos += Math.max(0, nanos);
        serverUpdateMaxNanos = Math.max(serverUpdateMaxNanos, nanos);
    }

    synchronized void recordNetwork(long nanos) {
        networkSamples++;
        networkTotalNanos += Math.max(0, nanos);
        networkMaxNanos = Math.max(networkMaxNanos, nanos);
    }

    synchronized void recordPacketSent(int bytes) {
        packetsSent++;
        packetBytesSent += Math.max(0, bytes);
    }

    synchronized void recordPacketReceived(int bytes) {
        packetsReceived++;
        packetBytesReceived += Math.max(0, bytes);
    }

    synchronized void recordSnapshotSent(int bytes) {
        snapshotsSent++;
        snapshotBytesSent += Math.max(0, bytes);
    }

    synchronized void recordSnapshotReceived(int bytes) {
        snapshotsReceived++;
        snapshotBytesReceived += Math.max(0, bytes);
        lastSnapshotNanos = System.nanoTime();
    }

    synchronized void recordReliableResend() { reliableResends++; }
    synchronized void setPendingReliable(int pending) { pendingReliable = Math.max(0, pending); }

    synchronized void recordRtt(long nanos) {
        double sampleMs = nanos / 1_000_000.0;
        if (sampleMs < 0 || Double.isNaN(sampleMs) || Double.isInfinite(sampleMs)) return;
        rttMs = rttMs < 0 ? sampleMs : rttMs * 0.8 + sampleMs * 0.2;
    }

    synchronized PerfSnapshot snapshot() {
        long now = System.nanoTime();
        long elapsedNanos = Math.max(1, now - windowStartNanos);
        if (elapsedNanos < SAMPLE_NANOS) return liveSnapshot(cached, now);

        double seconds = elapsedNanos / 1_000_000_000.0;
        cached = new PerfSnapshot(
                frames / seconds,
                averageMs(frameIntervalTotalNanos, frameIntervals),
                nanosToMs(frameIntervalMaxNanos),
                averageMs(drawTotalNanos, drawSamples),
                nanosToMs(drawMaxNanos),
                averageMs(updateTotalNanos, updateSamples),
                nanosToMs(updateMaxNanos),
                averageMs(serverUpdateTotalNanos, serverUpdateSamples),
                nanosToMs(serverUpdateMaxNanos),
                averageMs(networkTotalNanos, networkSamples),
                nanosToMs(networkMaxNanos),
                packetsSent / seconds,
                packetBytesSent / seconds,
                packetsReceived / seconds,
                packetBytesReceived / seconds,
                snapshotsSent / seconds,
                averageBytes(snapshotBytesSent, snapshotsSent),
                snapshotsReceived / seconds,
                averageBytes(snapshotBytesReceived, snapshotsReceived),
                reliableResends / seconds,
                pendingReliable,
                rttMs,
                snapshotAgeMs(now)
        );
        resetWindow(now);
        return cached;
    }

    private PerfSnapshot liveSnapshot(PerfSnapshot base, long now) {
        return new PerfSnapshot(
                base.fps(), base.frameAvgMs(), base.frameMaxMs(), base.drawAvgMs(), base.drawMaxMs(),
                base.updateAvgMs(), base.updateMaxMs(), base.serverUpdateAvgMs(), base.serverUpdateMaxMs(),
                base.networkAvgMs(), base.networkMaxMs(), base.packetsSentPerSecond(), base.bytesSentPerSecond(),
                base.packetsReceivedPerSecond(), base.bytesReceivedPerSecond(), base.snapshotsSentPerSecond(),
                base.averageSnapshotBytesSent(), base.snapshotsReceivedPerSecond(), base.averageSnapshotBytesReceived(),
                base.reliableResendsPerSecond(), pendingReliable, rttMs, snapshotAgeMs(now)
        );
    }

    private void resetWindow(long now) {
        windowStartNanos = now;
        frames = frameIntervals = frameIntervalTotalNanos = frameIntervalMaxNanos = 0;
        drawSamples = drawTotalNanos = drawMaxNanos = 0;
        updateSamples = updateTotalNanos = updateMaxNanos = 0;
        serverUpdateSamples = serverUpdateTotalNanos = serverUpdateMaxNanos = 0;
        networkSamples = networkTotalNanos = networkMaxNanos = 0;
        packetsSent = packetBytesSent = packetsReceived = packetBytesReceived = 0;
        snapshotsSent = snapshotBytesSent = snapshotsReceived = snapshotBytesReceived = 0;
        reliableResends = 0;
    }

    private double snapshotAgeMs(long now) {
        return lastSnapshotNanos <= 0 ? -1.0 : Math.max(0, now - lastSnapshotNanos) / 1_000_000.0;
    }

    private double averageMs(long totalNanos, long count) { return count <= 0 ? 0.0 : nanosToMs(totalNanos / (double)count); }
    private double averageBytes(long totalBytes, long count) { return count <= 0 ? 0.0 : totalBytes / (double)count; }
    private double nanosToMs(double nanos) { return nanos / 1_000_000.0; }
}

record PerfSnapshot(
        double fps,
        double frameAvgMs,
        double frameMaxMs,
        double drawAvgMs,
        double drawMaxMs,
        double updateAvgMs,
        double updateMaxMs,
        double serverUpdateAvgMs,
        double serverUpdateMaxMs,
        double networkAvgMs,
        double networkMaxMs,
        double packetsSentPerSecond,
        double bytesSentPerSecond,
        double packetsReceivedPerSecond,
        double bytesReceivedPerSecond,
        double snapshotsSentPerSecond,
        double averageSnapshotBytesSent,
        double snapshotsReceivedPerSecond,
        double averageSnapshotBytesReceived,
        double reliableResendsPerSecond,
        int pendingReliable,
        double rttMs,
        double snapshotAgeMs
) {
    static PerfSnapshot empty() {
        return new PerfSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1);
    }
}
