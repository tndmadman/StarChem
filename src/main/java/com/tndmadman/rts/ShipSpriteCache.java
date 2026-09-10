package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pre-renders medium-LOD ship hulls into bounded orientation and visual-variant buckets. */
final class ShipSpriteCache {
    private static final int BUCKETS = 48;
    private static final int IMAGE_SIZE = 144;
    private static final int SPRITE_PADDING = 6;
    private static final int MAX_ENTRIES = 1536;
    private static final long ESTIMATED_BYTES_PER_IMAGE = (long) IMAGE_SIZE * IMAGE_SIZE * Integer.BYTES;

    private static long requests;
    private static long hits;
    private static long misses;
    private static long generations;
    private static long generationNanos;
    private static long evictions;
    private static int peakEntries;

    private static final Map<Key, Sprite> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Sprite> eldest) {
            boolean remove = size() > MAX_ENTRIES;
            if (remove) evictions++;
            return remove;
        }
    };

    private ShipSpriteCache() { }

    static Sprite sprite(Unit unit, Color color) {
        if (unit == null || color == null) return null;
        int bucket = headingBucket(unit.heading);
        int variant = ShipVisualStyle.variantIndex(unit);
        Key key = new Key(unit.shipTypeId, color.getRGB(), variant, bucket);
        synchronized (CACHE) {
            requests++;
            Sprite cached = CACHE.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }

            misses++;
            long started = System.nanoTime();
            Sprite sprite = render(unit, color, variant, bucket);
            generationNanos += Math.max(0L, System.nanoTime() - started);
            generations++;
            CACHE.put(key, sprite);
            peakEntries = Math.max(peakEntries, CACHE.size());
            return sprite;
        }
    }

    static int imageSize() { return IMAGE_SIZE; }
    static int maxEntries() { return MAX_ENTRIES; }

    static double rasterScale(ShipType type) {
        if (type == null) return 1.0;
        double radius = ShipVisualCatalog.forType(type).renderRadius(type.size.scale);
        double safeRadius = IMAGE_SIZE / 2.0 - SPRITE_PADDING;
        if (!Double.isFinite(radius) || radius <= 0 || radius <= safeRadius) return 1.0;
        return Math.max(0.20, safeRadius / radius);
    }

    static Snapshot snapshot() {
        synchronized (CACHE) {
            long totalRequests = requests;
            double hitRate = totalRequests <= 0 ? 0.0 : hits / (double) totalRequests;
            double generationMs = generationNanos / 1_000_000.0;
            double averageGenerationMs = generations <= 0 ? 0.0 : generationMs / generations;
            return new Snapshot(
                    CACHE.size(),
                    peakEntries,
                    MAX_ENTRIES,
                    totalRequests,
                    hits,
                    misses,
                    generations,
                    evictions,
                    hitRate,
                    generationMs,
                    averageGenerationMs,
                    CACHE.size() * ESTIMATED_BYTES_PER_IMAGE);
        }
    }

    /** Clears cached sprites and counters so deterministic performance validators can measure cold/warm behavior. */
    static void resetForTest() {
        synchronized (CACHE) {
            CACHE.clear();
            requests = 0;
            hits = 0;
            misses = 0;
            generations = 0;
            generationNanos = 0;
            evictions = 0;
            peakEntries = 0;
        }
    }

    private static Sprite render(Unit unit, Color color, int variant, int bucket) {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.translate(IMAGE_SIZE / 2.0, IMAGE_SIZE / 2.0);
        g.rotate(bucket * Math.PI * 2.0 / BUCKETS);
        double rasterScale = rasterScale(unit.type());
        g.scale(rasterScale, rasterScale);
        // Medium LOD preserves authored silhouette, material palette, ownership accent and bounded
        // deterministic variation from ShipShape. Micro-panel texture is close-LOD-only because it
        // is not readable at this scale and needlessly increases cached-sprite compositing cost.
        ShipShape.draw(g, unit.type(), color, variant);
        g.dispose();
        int worldSize = Math.max(IMAGE_SIZE, (int)Math.ceil(IMAGE_SIZE / rasterScale));
        return new Sprite(image, worldSize);
    }

    private static int headingBucket(double heading) {
        if (!Double.isFinite(heading)) return 0;
        double turns = heading / (Math.PI * 2.0);
        return Math.floorMod((int)Math.round(turns * BUCKETS), BUCKETS);
    }

    record Snapshot(
            int entries,
            int peakEntries,
            int maxEntries,
            long requests,
            long hits,
            long misses,
            long generations,
            long evictions,
            double hitRate,
            double generationMs,
            double averageGenerationMs,
            long estimatedBytes) { }

    record Sprite(BufferedImage image, int worldSize) { }
    private record Key(String typeId, int rgb, int visualVariant, int headingBucket) { }
}
