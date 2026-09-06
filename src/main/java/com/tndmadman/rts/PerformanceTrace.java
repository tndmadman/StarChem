package com.tndmadman.rts;

import java.util.concurrent.atomic.LongAdder;

/** Low-overhead subsystem timings and candidate counters for the developer performance overlay. */
final class PerformanceTrace {
    private static final LongAdder movementNanos = new LongAdder();
    private static final LongAdder movementSamples = new LongAdder();
    private static final LongAdder moduleNanos = new LongAdder();
    private static final LongAdder moduleSamples = new LongAdder();
    private static final LongAdder weaponNanos = new LongAdder();
    private static final LongAdder weaponSamples = new LongAdder();
    private static final LongAdder acquisitionNanos = new LongAdder();
    private static final LongAdder acquisitionSamples = new LongAdder();
    private static final LongAdder pointDefenseNanos = new LongAdder();
    private static final LongAdder pointDefenseSamples = new LongAdder();
    private static final LongAdder projectileNanos = new LongAdder();
    private static final LongAdder projectileSamples = new LongAdder();
    private static final LongAdder worldDrawNanos = new LongAdder();
    private static final LongAdder worldDrawSamples = new LongAdder();
    private static final LongAdder weaponDrawNanos = new LongAdder();
    private static final LongAdder weaponDrawSamples = new LongAdder();
    private static final LongAdder fogDrawNanos = new LongAdder();
    private static final LongAdder fogDrawSamples = new LongAdder();
    private static final LongAdder spatialRebuildNanos = new LongAdder();
    private static final LongAdder spatialRebuildSamples = new LongAdder();
    private static final LongAdder spatialEntities = new LongAdder();
    private static final LongAdder spatialCandidates = new LongAdder();
    private static final LongAdder targetCandidates = new LongAdder();
    private static final LongAdder pointDefenseCandidates = new LongAdder();
    private static final LongAdder snapshotFilterNanos = new LongAdder();
    private static final LongAdder snapshotFilterSamples = new LongAdder();
    private static final LongAdder snapshotFilterEntities = new LongAdder();
    private static volatile TraceSnapshot last = TraceSnapshot.empty();
    private static volatile long lastSnapshotNanos = System.nanoTime();

    private PerformanceTrace() { }

    static void recordMovement(long nanos) { add(movementNanos, movementSamples, nanos); }
    static void recordModules(long nanos) { add(moduleNanos, moduleSamples, nanos); }
    static void recordWeapons(long nanos) { add(weaponNanos, weaponSamples, nanos); }
    static void recordAcquisition(long nanos, int candidates) {
        add(acquisitionNanos, acquisitionSamples, nanos);
        targetCandidates.add(Math.max(0, candidates));
    }
    static void recordPointDefense(long nanos, int candidates) {
        add(pointDefenseNanos, pointDefenseSamples, nanos);
        pointDefenseCandidates.add(Math.max(0, candidates));
    }
    static void recordProjectiles(long nanos) { add(projectileNanos, projectileSamples, nanos); }
    static void recordWorldDraw(long nanos) { add(worldDrawNanos, worldDrawSamples, nanos); }
    static void recordWeaponDraw(long nanos) { add(weaponDrawNanos, weaponDrawSamples, nanos); }
    static void recordFogDraw(long nanos) { add(fogDrawNanos, fogDrawSamples, nanos); }
    static void recordSpatialRebuild(long nanos, int entities) {
        add(spatialRebuildNanos, spatialRebuildSamples, nanos);
        spatialEntities.add(Math.max(0, entities));
    }
    static void recordSpatialCandidates(int candidates) { spatialCandidates.add(Math.max(0, candidates)); }
    static void recordSnapshotFilter(long nanos, int entities) {
        add(snapshotFilterNanos, snapshotFilterSamples, nanos);
        snapshotFilterEntities.add(Math.max(0, entities));
    }

    static TraceSnapshot snapshot() {
        long now = System.nanoTime();
        long elapsed = now - lastSnapshotNanos;
        if (elapsed < 250_000_000L) return last;
        synchronized (PerformanceTrace.class) {
            now = System.nanoTime();
            elapsed = now - lastSnapshotNanos;
            if (elapsed < 250_000_000L) return last;
            double seconds = Math.max(0.001, elapsed / 1_000_000_000.0);
            last = new TraceSnapshot(
                    averageMs(movementNanos, movementSamples),
                    averageMs(moduleNanos, moduleSamples),
                    averageMs(weaponNanos, weaponSamples),
                    averageMs(acquisitionNanos, acquisitionSamples),
                    averageMs(pointDefenseNanos, pointDefenseSamples),
                    averageMs(projectileNanos, projectileSamples),
                    averageMs(worldDrawNanos, worldDrawSamples),
                    averageMs(weaponDrawNanos, weaponDrawSamples),
                    averageMs(fogDrawNanos, fogDrawSamples),
                    averageMs(spatialRebuildNanos, spatialRebuildSamples),
                    rate(spatialEntities, seconds),
                    rate(spatialCandidates, seconds),
                    rate(targetCandidates, seconds),
                    rate(pointDefenseCandidates, seconds),
                    averageMs(snapshotFilterNanos, snapshotFilterSamples),
                    rate(snapshotFilterEntities, seconds));
            reset();
            lastSnapshotNanos = now;
            return last;
        }
    }

    private static void add(LongAdder nanos, LongAdder samples, long value) {
        nanos.add(Math.max(0, value));
        samples.increment();
    }

    private static double averageMs(LongAdder nanos, LongAdder samples) {
        long count = samples.sum();
        return count <= 0 ? 0 : nanos.sum() / (double)count / 1_000_000.0;
    }

    private static double rate(LongAdder value, double seconds) {
        return value.sum() / seconds;
    }

    private static void reset() {
        movementNanos.reset(); movementSamples.reset();
        moduleNanos.reset(); moduleSamples.reset();
        weaponNanos.reset(); weaponSamples.reset();
        acquisitionNanos.reset(); acquisitionSamples.reset();
        pointDefenseNanos.reset(); pointDefenseSamples.reset();
        projectileNanos.reset(); projectileSamples.reset();
        worldDrawNanos.reset(); worldDrawSamples.reset();
        weaponDrawNanos.reset(); weaponDrawSamples.reset();
        fogDrawNanos.reset(); fogDrawSamples.reset();
        spatialRebuildNanos.reset(); spatialRebuildSamples.reset(); spatialEntities.reset();
        spatialCandidates.reset(); targetCandidates.reset(); pointDefenseCandidates.reset();
        snapshotFilterNanos.reset(); snapshotFilterSamples.reset(); snapshotFilterEntities.reset();
    }

    record TraceSnapshot(
            double movementMs,
            double modulesMs,
            double weaponsMs,
            double acquisitionMs,
            double pointDefenseMs,
            double projectilesMs,
            double worldDrawMs,
            double weaponDrawMs,
            double fogDrawMs,
            double spatialRebuildMs,
            double indexedEntitiesPerSecond,
            double spatialCandidatesPerSecond,
            double targetCandidatesPerSecond,
            double pointDefenseCandidatesPerSecond,
            double snapshotFilterMs,
            double snapshotFilterEntitiesPerSecond) {
        static TraceSnapshot empty() {
            return new TraceSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
