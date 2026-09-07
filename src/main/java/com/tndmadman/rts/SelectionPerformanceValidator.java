package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Regression gate for selected-fleet rendering at the counts that exposed issue #372. */
public final class SelectionPerformanceValidator {
    private static final int WARMUP_FRAMES = 4;
    private static final int SAMPLE_FRAMES = 9;
    private static final int IMAGE_WIDTH = 1400;
    private static final int IMAGE_HEIGHT = 900;

    private SelectionPerformanceValidator() { }

    public static void main(String[] args) {
        validateFleet(400);
        validateFleet(1000);
        System.out.println("Selection performance validator passed.");
    }

    private static void validateFleet(int count) {
        World world = new World("Selection performance validator", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();

        Unit primary = null;
        Unit secondary = null;
        for (int i = 0; i < count; i++) {
            double x = 100 + (i % 40) * 26.0;
            double y = 100 + (i / 40) * 26.0;
            Unit unit = new Unit("P1", i + 1, "frigate", x, y);
            unit.task = UnitTask.MOVE;
            unit.targetX = 1200;
            unit.targetY = 760;
            world.units.put(unit.key(), unit);
            if (primary == null) primary = unit;
            else if (secondary == null) secondary = unit;
        }

        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g2 = image.createGraphics();
        g2.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        try {
            setSelected(world, false);
            warmUp(world, g2);
            double unselectedMs = medianDrawMs(world, g2);

            setSelected(world, true);
            warmUp(world, g2);
            validateSingleFrameStructure(world, g2, count, primary, secondary);
            double selectedMs = medianDrawMs(world, g2);

            // The old per-unit cache/overlay path measured roughly 10.8x at 400 and
            // 42x at 1000. Allow broad VM/runner noise while still rejecting that shape.
            double limitMs = unselectedMs * 9.0 + 6.0;
            require(selectedMs <= limitMs,
                    String.format(Locale.ROOT,
                            "%d selected ships regressed World.draw: unselected %.3f ms, selected %.3f ms, limit %.3f ms",
                            count, unselectedMs, selectedMs, limitMs));

            System.out.printf(Locale.ROOT,
                    "Selection render %d: unselected %.3f ms, selected %.3f ms, ratio %.2fx%n",
                    count, unselectedMs, selectedMs, selectedMs / Math.max(0.001, unselectedMs));
        } finally {
            g2.dispose();
        }
    }

    private static void validateSingleFrameStructure(World world, Graphics2D g2, int count,
                                                     Unit primary, Unit secondary) {
        long contextBefore = SelectionRenderPolicy.frameBuildCountForTest();
        long overlayBefore = FleetSelectionOverlay.framePassCountForTest();
        world.draw(g2);
        long contextDelta = SelectionRenderPolicy.frameBuildCountForTest() - contextBefore;
        long overlayDelta = FleetSelectionOverlay.framePassCountForTest() - overlayBefore;

        require(contextDelta == 1,
                "World.draw built selection context " + contextDelta + " times instead of once for " + count + " ships.");
        require(overlayDelta == 1,
                "World.draw ran selection overlay " + overlayDelta + " times instead of once for " + count + " ships.");

        SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.current(world);
        require(frame != null, "Selection frame was not available after World.draw.");
        require(frame.selectedCount() == count,
                "Selection frame count mismatch: expected " + count + ", got " + frame.selectedCount());
        require(frame.visibleSelectedUnits().size() == count,
                "Visible selected count mismatch: expected " + count + ", got " + frame.visibleSelectedUnits().size());
        require(frame.primary() == primary, "Primary selected ship changed during frame context build.");
        require(frame.exactSelectedDetail(primary), "Primary ship lost detailed selection rendering.");
        require(!frame.exactSelectedDetail(secondary), "Fleet secondary incorrectly retained detailed selection rendering.");
        require(FleetSelectionOverlay.lastMarkerCountForTest() == count - 1,
                "Expected one batched marker per visible secondary ship.");
        require(FleetSelectionOverlay.lastGroupCountForTest() == 1,
                "Expected the shared MOVE intent to collapse into one aggregate order group.");
    }

    private static void setSelected(World world, boolean selected) {
        for (Unit unit : world.units.values()) unit.selected = selected;
    }

    private static void warmUp(World world, Graphics2D g2) {
        for (int i = 0; i < WARMUP_FRAMES; i++) world.draw(g2);
    }

    private static double medianDrawMs(World world, Graphics2D g2) {
        long[] samples = new long[SAMPLE_FRAMES];
        for (int i = 0; i < samples.length; i++) {
            long started = System.nanoTime();
            world.draw(g2);
            samples[i] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2] / 1_000_000.0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
