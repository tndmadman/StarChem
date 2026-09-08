package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame hook for selected fleets. Selection is intentionally HUD-only: world-space ships
 * remain hull-only regardless of selection size.
 */
final class FleetSelectionOverlay {
    private static final AtomicLong FRAME_PASSES = new AtomicLong();
    private static volatile int lastMarkerCount;
    private static volatile int lastGroupCount;

    private FleetSelectionOverlay() { }

    static void drawFrame(Graphics2D g2, World world, SelectionRenderPolicy.Frame selection) {
        long started = System.nanoTime();
        FRAME_PASSES.incrementAndGet();
        lastMarkerCount = 0;
        lastGroupCount = 0;
        PerformanceTrace.recordSelectionDraw(System.nanoTime() - started, 0, 0);
    }

    static long framePassCountForTest() { return FRAME_PASSES.get(); }
    static int lastMarkerCountForTest() { return lastMarkerCount; }
    static int lastGroupCountForTest() { return lastGroupCount; }
}
