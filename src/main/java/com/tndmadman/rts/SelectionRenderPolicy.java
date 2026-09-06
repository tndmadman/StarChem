package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Central policy for selected-fleet rendering. Large selections must not turn
 * every ship into an expensive detailed UI surface.
 *
 * The snapshot is intentionally short lived: selection changes are interactive,
 * but counting every selected unit from every renderer call creates O(N^2)
 * work on large fleets. A short cache keeps the render hot path O(N) while any
 * tier/primary change is visible within a couple of frames.
 */
final class SelectionRenderPolicy {
    static final int FULL_LIMIT = 8;
    static final int COMPACT_LIMIT = 24;
    static final int FLEET_LIMIT = 96;
    static final int MAX_AGGREGATE_GROUPS = 8;

    private static final long SNAPSHOT_NANOS = 50_000_000L;
    private static final Map<World, CachedSnapshot> CACHE = new WeakHashMap<>();
    private static volatile World fastWorld;
    private static volatile CachedSnapshot fastSnapshot;

    enum Tier { FULL, COMPACT, FLEET, MASS }

    private SelectionRenderPolicy() { }

    static Snapshot snapshot(World world) {
        if (world == null) return Snapshot.EMPTY;
        long now = System.nanoTime();
        CachedSnapshot fast = fastSnapshot;
        if (fastWorld == world && valid(world, fast, now)) return fast.snapshot;

        synchronized (CACHE) {
            CachedSnapshot cached = CACHE.get(world);
            if (!valid(world, cached, now)) {
                Snapshot snapshot = build(world);
                cached = new CachedSnapshot(world.units.size(), now + SNAPSHOT_NANOS, snapshot);
                CACHE.put(world, cached);
            }
            fastWorld = world;
            fastSnapshot = cached;
            return cached.snapshot;
        }
    }

    static Tier tier(World world) { return snapshot(world).tier(); }

    static int selectedCount(World world) { return snapshot(world).selectedCount(); }

    static boolean aggregate(World world) { return selectedCount(world) > FULL_LIMIT; }

    static boolean primary(World world, Unit unit) {
        return unit != null && snapshot(world).primary() == unit;
    }

    /** Exact per-ship text/range/order detail is deliberately bounded. */
    static boolean exactSelectedDetail(World world, Unit unit) {
        Snapshot snapshot = snapshot(world);
        return snapshot.selectedCount() <= FULL_LIMIT || snapshot.primary() == unit;
    }

    /**
     * At fleet scale selected ships use cached sprites even when zoomed in.
     * Selection is a UI overlay, not a reason to force vector hull rendering.
     */
    static boolean forceCheapSelectedHull(World world, Unit unit) {
        if (unit == null || !unit.selected || !PlayerRegistry.isLocal(unit.playerId)) return false;
        Snapshot snapshot = snapshot(world);
        return snapshot.selectedCount() > COMPACT_LIMIT && snapshot.primary() != unit;
    }

    static boolean compactMarker(World world) {
        return snapshot(world).selectedCount() > COMPACT_LIMIT;
    }

    static double scale(Graphics2D g2) {
        if (g2 == null) return 1.0;
        return Math.max(Math.abs(g2.getTransform().getScaleX()), Math.abs(g2.getTransform().getScaleY()));
    }

    static void invalidate(World world) {
        if (world == null) return;
        synchronized (CACHE) {
            CACHE.remove(world);
            if (fastWorld == world) {
                fastWorld = null;
                fastSnapshot = null;
            }
        }
    }

    private static boolean valid(World world, CachedSnapshot cached, long now) {
        return cached != null && cached.unitCount == world.units.size() && now < cached.expiresAtNanos;
    }

    private static Snapshot build(World world) {
        int count = 0;
        Unit primary = null;
        for (Unit unit : world.units.values()) {
            if (!unit.selected || !PlayerRegistry.isLocal(unit.playerId)) continue;
            if (primary == null) primary = unit;
            count++;
        }
        Tier tier = count <= FULL_LIMIT ? Tier.FULL
                : count <= COMPACT_LIMIT ? Tier.COMPACT
                : count <= FLEET_LIMIT ? Tier.FLEET : Tier.MASS;
        return new Snapshot(count, primary, tier);
    }

    record Snapshot(int selectedCount, Unit primary, Tier tier) {
        private static final Snapshot EMPTY = new Snapshot(0, null, Tier.FULL);
    }

    private record CachedSnapshot(int unitCount, long expiresAtNanos, Snapshot snapshot) { }
}
