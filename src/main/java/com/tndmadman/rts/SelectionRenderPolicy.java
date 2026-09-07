package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Frame-scoped policy and reusable context for selected-fleet rendering. */
final class SelectionRenderPolicy {
    static final int FULL_LIMIT = 8;
    static final int COMPACT_LIMIT = 24;
    static final int FLEET_LIMIT = 96;
    static final int MAX_AGGREGATE_GROUPS = 8;

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);
    private static final AtomicLong FRAME_BUILDS = new AtomicLong();

    enum Tier { FULL, COMPACT, FLEET, MASS }

    private SelectionRenderPolicy() { }

    /**
     * Build the selection state exactly once for a World.draw pass. The selected list and
     * visible-selected list are scratch-backed and reused by the render thread.
     */
    static Frame beginFrame(World world, Graphics2D g2, Iterable<Unit> visibleUnits) {
        Frame frame = FRAME.get();
        long started = System.nanoTime();
        frame.reset(world, scale(g2));
        if (world != null) {
            for (Unit unit : world.units.values()) {
                if (!unit.selected || !PlayerRegistry.isLocal(unit.playerId)) continue;
                if (frame.primary == null) frame.primary = unit;
                frame.selectedUnits.add(unit);
            }
            frame.selectedCount = frame.selectedUnits.size();
            frame.tier = tierFor(frame.selectedCount);
            if (visibleUnits != null) {
                for (Unit unit : visibleUnits) {
                    if (unit.selected && PlayerRegistry.isLocal(unit.playerId)) {
                        frame.visibleSelectedUnits.add(unit);
                    }
                }
            }
        }
        frame.serial = FRAME_BUILDS.incrementAndGet();
        PerformanceTrace.recordSelectionContext(System.nanoTime() - started,
                frame.selectedCount, frame.visibleSelectedUnits.size());
        return frame;
    }

    /** O(1) lookup used by per-unit renderers after beginFrame. */
    static Frame current(World world) {
        Frame frame = FRAME.get();
        return frame.world == world ? frame : null;
    }

    static double scale(Graphics2D g2) {
        if (g2 == null) return 1.0;
        return Math.max(Math.abs(g2.getTransform().getScaleX()), Math.abs(g2.getTransform().getScaleY()));
    }

    static void invalidate(World world) {
        Frame frame = FRAME.get();
        if (frame.world == world) frame.reset(null, 1.0);
    }

    static long frameBuildCountForTest() { return FRAME_BUILDS.get(); }

    private static Tier tierFor(int count) {
        return count <= FULL_LIMIT ? Tier.FULL
                : count <= COMPACT_LIMIT ? Tier.COMPACT
                : count <= FLEET_LIMIT ? Tier.FLEET : Tier.MASS;
    }

    static final class Frame {
        private World world;
        private int selectedCount;
        private Unit primary;
        private Tier tier = Tier.FULL;
        private double scale = 1.0;
        private long serial;
        private final List<Unit> selectedUnits = new ArrayList<>();
        private final List<Unit> visibleSelectedUnits = new ArrayList<>();

        private Frame() { }

        private void reset(World newWorld, double newScale) {
            world = newWorld;
            selectedCount = 0;
            primary = null;
            tier = Tier.FULL;
            scale = newScale;
            serial = 0;
            selectedUnits.clear();
            visibleSelectedUnits.clear();
        }

        int selectedCount() { return selectedCount; }
        Unit primary() { return primary; }
        Tier tier() { return tier; }
        double scale() { return scale; }
        long serial() { return serial; }
        List<Unit> selectedUnits() { return selectedUnits; }
        List<Unit> visibleSelectedUnits() { return visibleSelectedUnits; }
        boolean aggregate() { return selectedCount > FULL_LIMIT; }
        boolean compactMarkers() { return selectedCount > COMPACT_LIMIT; }
        boolean exactSelectedDetail(Unit unit) {
            return unit != null && unit.selected && PlayerRegistry.isLocal(unit.playerId)
                    && (selectedCount <= FULL_LIMIT || primary == unit);
        }
    }
}
