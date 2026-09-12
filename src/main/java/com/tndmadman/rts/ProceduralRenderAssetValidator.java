package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Focused headless regression coverage for RAM-resident procedural render assets. */
public final class ProceduralRenderAssetValidator {
    private static final Color OWNER = new Color(80, 190, 255);

    private ProceduralRenderAssetValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        validateCanonicalShipHeadingSharing();
        validateShipPrewarmAndBudget();
        validateStationStaticSharing();
        validateStationAnimationRemainsLive();
        validateStationPrewarmAndBudget();
        validateResourceSpriteCachingAndBounds();
        System.out.println("Procedural render asset validation passed.");
    }

    private static void validateCanonicalShipHeadingSharing() {
        ShipSpriteCache.resetForTest();
        Unit unit = new Unit("P1", 17, Rules.STARTING_SHIP, 0, 0);
        unit.heading = 0;
        ShipSpriteCache.Sprite first = ShipSpriteCache.sprite(unit, OWNER);
        unit.heading = Math.PI * 0.73;
        ShipSpriteCache.Sprite rotated = ShipSpriteCache.sprite(unit, OWNER);

        require(first != null && rotated != null, "canonical ship sprite generation returned null");
        require(first.image() == rotated.image(),
                "ship heading created a second raster instead of sharing one canonical sprite");
        ShipSpriteCache.Snapshot snapshot = ShipSpriteCache.snapshot();
        require(snapshot.entries() == 1, "one ship visual definition should occupy exactly one canonical cache entry");
        require(snapshot.requests() == 2 && snapshot.hits() == 1 && snapshot.misses() == 1
                        && snapshot.generations() == 1,
                "canonical ship cache request/generation accounting is inconsistent");
    }

    private static void validateShipPrewarmAndBudget() {
        ShipSpriteCache.resetForTest();
        ShipSpriteCache.prewarm(OWNER);
        ShipSpriteCache.Snapshot snapshot = ShipSpriteCache.snapshot();
        int expected = Rules.SHIPS.size() * ShipVisualStyle.VARIANT_COUNT;
        require(snapshot.prewarmedSprites() == expected,
                "ship prewarm did not generate every configured type/variant: expected " + expected
                        + " but got " + snapshot.prewarmedSprites());
        require(snapshot.entries() <= snapshot.maxEntries(), "ship prewarm exceeded its entry bound");
        require(snapshot.estimatedBytes() <= ShipSpriteCache.maxEstimatedBytes(),
                "ship prewarm exceeded its raw pixel memory budget");
        require(snapshot.requests() == 0 && snapshot.misses() == 0 && snapshot.generations() == 0,
                "eager ship prewarm polluted runtime hit/miss/generation accounting");
    }

    private static void validateStationStaticSharing() {
        StationSpriteCache.resetForTest();
        BufferedImage image = new BufferedImage(480, 320, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            Base first = new Base("CACHE-A", "P1", Rules.DEFAULT_BASE, 140, 160);
            Base second = new Base("CACHE-B", "P1", Rules.DEFAULT_BASE, 340, 160);
            require(StationSpriteCache.drawStatic(g, first, OWNER, StationSpriteCache.Lod.DETAILED),
                    "first station static sprite could not be generated");
            require(StationSpriteCache.drawStatic(g, second, OWNER, StationSpriteCache.Lod.DETAILED),
                    "second station static sprite could not be reused");
        } finally {
            g.dispose();
        }

        StationSpriteCache.Snapshot snapshot = StationSpriteCache.snapshot();
        require(snapshot.entries() == 1,
                "station IDs multiplied static sprite entries even though their type/color/LOD matched");
        require(snapshot.requests() == 2 && snapshot.hits() == 1 && snapshot.misses() == 1
                        && snapshot.runtimeGenerations() == 1,
                "station static cache request/generation accounting is inconsistent");
    }

    private static void validateStationAnimationRemainsLive() {
        Base base = new Base("ANIMATED-STATION", "P1", Rules.DEFAULT_BASE, 180, 180);
        BufferedImage first = renderStation(base, 2.0);
        BufferedImage later = renderStation(base, 6.0);
        require(pixelDifference(first, later) > 8,
                "station static caching froze time-driven station presentation");
    }

    private static BufferedImage renderStation(Base base, double time) {
        BufferedImage image = new BufferedImage(360, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            StationRenderer.drawAtTime(g, base, OWNER, time, 1.0);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void validateStationPrewarmAndBudget() {
        StationSpriteCache.resetForTest();
        StationSpriteCache.prewarm(OWNER);
        StationSpriteCache.Snapshot snapshot = StationSpriteCache.snapshot();
        int expected = Rules.BASES.size() * StationSpriteCache.Lod.values().length;
        require(snapshot.prewarmedSprites() == expected,
                "station prewarm did not generate every configured type/LOD: expected " + expected
                        + " but got " + snapshot.prewarmedSprites());
        require(snapshot.estimatedBytes() <= snapshot.maxEstimatedBytes(),
                "station prewarm exceeded its raw pixel memory budget");
        require(snapshot.requests() == 0 && snapshot.misses() == 0 && snapshot.runtimeGenerations() == 0,
                "eager station prewarm polluted runtime hit/miss/generation accounting");
    }

    private static void validateResourceSpriteCachingAndBounds() {
        ResourceFieldRenderer.clearCacheForTesting();
        require(ResourceFieldRenderer.cacheSizeForTesting() == 0, "resource backdrop cache reset failed");
        require(ResourceFieldRenderer.nodeCacheSizeForTesting() == 0, "resource body cache reset failed");
        require(ResourceFieldRenderer.maxCacheEntriesForTesting() > 0,
                "resource backdrop cache lost its hard entry bound");

        ResourceNode rock = new ResourceNode(
                9917, "Validator Iron", NodeKind.SILICATE_ROCK, Material.IRON,
                128, 128, 1000, 20, 30);
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            ResourceFieldRenderer.draw(g, rock, false);
            int firstEntries = ResourceFieldRenderer.nodeCacheSizeForTesting();
            require(firstEntries == 1, "first harvestable resource did not create exactly one body sprite");
            ResourceFieldRenderer.draw(g, rock, true);
            require(ResourceFieldRenderer.nodeCacheSizeForTesting() == firstEntries,
                    "selection state incorrectly multiplied resource body sprites");
            rock.amount = rock.maxAmount * 0.50;
            ResourceFieldRenderer.draw(g, rock, false);
            require(ResourceFieldRenderer.nodeCacheSizeForTesting() == firstEntries + 1,
                    "resource depletion bucket did not produce a reusable updated body sprite");
        } finally {
            g.dispose();
        }

        require(ResourceFieldRenderer.nodeCacheBytesForTesting() <= ResourceFieldRenderer.maxNodeCacheBytesForTesting(),
                "resource body sprite cache exceeded its raw pixel memory budget");
    }

    private static int pixelDifference(BufferedImage first, BufferedImage second) {
        int[] a = first.getRGB(0, 0, first.getWidth(), first.getHeight(), null, 0, first.getWidth());
        int[] b = second.getRGB(0, 0, second.getWidth(), second.getHeight(), null, 0, second.getWidth());
        require(a.length == b.length, "station animation buffers differ in size");
        if (Arrays.equals(a, b)) return 0;
        int changed = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) changed++;
        return changed;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
