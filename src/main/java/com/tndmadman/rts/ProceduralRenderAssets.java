package com.tndmadman.rts;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Coordinates client-only eager generation of finite procedural render assets.
 *
 * The authoritative simulation never depends on this class. Dedicated/headless processes skip
 * prewarming entirely, and every consuming renderer retains a live-generation fallback.
 */
final class ProceduralRenderAssets {
    private static final Set<Integer> PREWARMED_OWNER_COLORS = new LinkedHashSet<>();
    private static final Set<Integer> PREWARMING_OWNER_COLORS = new LinkedHashSet<>();

    private ProceduralRenderAssets() { }

    /**
     * Pre-generate finite static ship/station art for a registered owner color.
     * Failures are presentation-only: the normal renderer will generate/fallback on demand.
     */
    static void prewarmOwnerColor(int rgb) {
        if (GraphicsEnvironment.isHeadless()) return;
        Color color = new Color(rgb & 0x00FFFFFF);
        int key = color.getRGB();

        synchronized (PREWARMED_OWNER_COLORS) {
            if (PREWARMED_OWNER_COLORS.contains(key) || !PREWARMING_OWNER_COLORS.add(key)) return;
        }

        boolean shipSuccess = false;
        boolean stationSuccess = false;
        try {
            try {
                ShipSpriteCache.prewarm(color);
                shipSuccess = true;
            } catch (RuntimeException ex) {
                logFailure("ship", key, ex);
            }
            try {
                StationSpriteCache.prewarm(color);
                stationSuccess = true;
            } catch (RuntimeException ex) {
                logFailure("station", key, ex);
            }
        } finally {
            synchronized (PREWARMED_OWNER_COLORS) {
                PREWARMING_OWNER_COLORS.remove(key);
                if (shipSuccess && stationSuccess) PREWARMED_OWNER_COLORS.add(key);
            }
        }
    }

    static Snapshot snapshot() {
        synchronized (PREWARMED_OWNER_COLORS) {
            return new Snapshot(
                    PREWARMED_OWNER_COLORS.size(),
                    ShipSpriteCache.snapshot(),
                    StationSpriteCache.snapshot());
        }
    }

    static void resetForTest() {
        synchronized (PREWARMED_OWNER_COLORS) {
            PREWARMED_OWNER_COLORS.clear();
            PREWARMING_OWNER_COLORS.clear();
        }
        ShipSpriteCache.resetForTest();
        StationSpriteCache.resetForTest();
    }

    private static void logFailure(String kind, int argb, RuntimeException ex) {
        System.err.printf(
                "StarChem render asset prewarm skipped %s sprites for owner #%06X: %s%n",
                kind,
                argb & 0x00FFFFFF,
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    record Snapshot(
            int prewarmedOwnerColors,
            ShipSpriteCache.Snapshot ships,
            StationSpriteCache.Snapshot stations) { }
}
