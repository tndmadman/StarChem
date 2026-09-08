package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Regression gate for hull-only selected-fleet rendering at issue #372 fleet sizes. */
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
        for (int i = 0; i < count; i++) {
            double x = 100 + (i % 40) * 26.0;
            double y = 100 + (i / 40) * 26.0;
            Unit unit = new Unit("P1", i + 1, "frigate", x, y);
            unit.task = UnitTask.MOVE;
            unit.targetX = 1200;
            unit.targetY = 760;
            world.units.put(unit.key(), unit);
            if (primary == null) primary = unit;
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
            validateSingleFrameStructure(world, g2, count, primary);
            validateDifferentRegistryWorld(world, g2, count, primary);
            double selectedMs = medianDrawMs(world, g2);

            double limitMs = unselectedMs * 9.0 + 6.0;
            require(selectedMs <= limitMs,
                    String.format(Locale.ROOT,
                            "%d selected ships regressed World.draw: unselected %.3f ms, selected %.3f ms, limit %.3f ms",
                            count, unselectedMs, selectedMs, limitMs));

            System.out.printf(Locale.ROOT,
                    "Selection render %d: unselected %.3f ms, selected %.3f ms, ratio %.2fx%n",
                    count, unselectedMs, selectedMs, selectedMs / Math.max(0.001, unselectedMs));
        } finally {
            PlayerRegistry.activate(world);
            g2.dispose();
        }
    }

    private static void validateSingleFrameStructure(World world, Graphics2D g2, int count, Unit primary) {
        long contextBefore = SelectionRenderPolicy.frameBuildCountForTest();
        long overlayBefore = FleetSelectionOverlay.framePassCountForTest();
        world.draw(g2);
        long contextDelta = SelectionRenderPolicy.frameBuildCountForTest() - contextBefore;
        long overlayDelta = FleetSelectionOverlay.framePassCountForTest() - overlayBefore;

        require(contextDelta == 1,
                "World.draw built selection context " + contextDelta + " times instead of once for " + count + " ships.");
        require(overlayDelta == 1,
                "World.draw ran selection frame hook " + overlayDelta + " times instead of once for " + count + " ships.");

        SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.current(world);
        require(frame != null, "Selection frame was not available after World.draw.");
        require(frame.selectedCount() == count,
                "Selection frame count mismatch: expected " + count + ", got " + frame.selectedCount());
        require(frame.visibleSelectedUnits().size() == count,
                "Visible selected count mismatch: expected " + count + ", got " + frame.visibleSelectedUnits().size());
        require(frame.primary() == primary, "Primary selected ship changed during frame context build.");
        require(frame.detailedSelectedDraws() == 0,
                "Hull-only selection unexpectedly rendered detailed per-ship UI.");
        require(frame.fleetSecondaryDraws() == count,
                "Expected every selected ship to use the hull-only path; got "
                        + frame.fleetSecondaryDraws() + " of " + count + ".");
        require(FleetSelectionOverlay.lastMarkerCountForTest() == 0,
                "Hull-only selection rendered world-space selection markers.");
        require(FleetSelectionOverlay.lastGroupCountForTest() == 0,
                "Hull-only selection rendered world-space aggregate order geometry.");

        SelectionSummaryHud.Summary summary = SelectionSummaryHud.summarize(world, frame);
        require(summary.shipCount() == count,
                "Aggregate selection HUD count mismatch: expected " + count + ", got " + summary.shipCount());
        require(summary.hpMax() > 0 && summary.hpNow() > 0,
                "Aggregate selection HUD did not total ship HP.");
        require(summary.shieldMax() >= 0 && summary.shieldNow() >= 0,
                "Aggregate selection HUD returned invalid shield totals.");
        require(summary.dps() >= 0 && Double.isFinite(summary.dps()),
                "Aggregate selection HUD returned invalid DPS.");
    }

    /** Ensure client rendering remains tied to the rendered World, not activeWorld(). */
    private static void validateDifferentRegistryWorld(World renderedWorld, Graphics2D g2,
                                                       int count, Unit primary) {
        World registryWorld = new World("Selection registry decoy", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(registryWorld);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        try {
            renderedWorld.draw(g2);
            SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.current(renderedWorld);
            require(frame != null, "Render frame disappeared when active registry world differed.");
            require(frame.primary() == primary, "Primary selection changed with a different registry world.");
            require(frame.detailedSelectedDraws() == 0,
                    "Different registry world re-enabled detailed per-ship selection UI.");
            require(frame.fleetSecondaryDraws() == count,
                    "Different registry world bypassed hull-only selection rendering: expected "
                            + count + ", got " + frame.fleetSecondaryDraws());
            SelectionSummaryHud.Summary summary = SelectionSummaryHud.summarize(renderedWorld, frame);
            require(summary.shipCount() == count,
                    "Aggregate selection HUD lost ships when registry world differed.");
        } finally {
            PlayerRegistry.activate(renderedWorld);
        }
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
