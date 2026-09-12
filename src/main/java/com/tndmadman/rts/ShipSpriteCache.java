package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAM-resident canonical ship hull sprites.
 *
 * Hulls are generated once per ship type / owner color / deterministic visual variant and are
 * rotated by the renderer. Keeping heading out of the cache key removes the former 48-way
 * orientation multiplier while preserving the authored procedural hull as the source of truth.
 */
final class ShipSpriteCache {
    private static final int IMAGE_SIZE = 144;
    private static final int SPRITE_PADDING = 6;
    private static final long ESTIMATED_BYTES_PER_IMAGE = (long) IMAGE_SIZE * IMAGE_SIZE * Integer.BYTES;
    private static final long MAX_ESTIMATED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ENTRIES = Math.max(1, (int)(MAX_ESTIMATED_BYTES / ESTIMATED_BYTES_PER_IMAGE));

    private static long requests;
    private static long hits;
    private static long misses;
    private static long generations;
    private static long generationNanos;
    private static long evictions;
    private static long prewarmedSprites;
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
        if (unit == null) return null;
        return sprite(unit.type(), color, ShipVisualStyle.variantIndex(unit));
    }

    static Sprite sprite(ShipType type, Color color, int visualVariant) {
        if (type == null || color == null) return null;
        int variant = Math.floorMod(visualVariant, ShipVisualStyle.VARIANT_COUNT);
        Key key = new Key(type.id, color.getRGB(), variant);
        synchronized (CACHE) {
            requests++;
            Sprite cached = CACHE.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }

            misses++;
            Sprite created = generate(type, color, variant);
            CACHE.put(key, created);
            peakEntries = Math.max(peakEntries, CACHE.size());
            return created;
        }
    }

    /**
     * Generates all finite ship hull variants for an owner color before ordinary rendering.
     * Repeated calls are cheap because already generated keys are skipped without affecting
     * normal render request/hit/miss accounting.
     */
    static void prewarm(Color color) {
        if (color == null) return;
        synchronized (CACHE) {
            for (ShipType type : Rules.SHIPS.values()) {
                if (type == null) continue;
                for (int variant = 0; variant < ShipVisualStyle.VARIANT_COUNT; variant++) {
                    Key key = new Key(type.id, color.getRGB(), variant);
                    if (CACHE.containsKey(key)) continue;
                    CACHE.put(key, generate(type, color, variant));
                    prewarmedSprites++;
                    peakEntries = Math.max(peakEntries, CACHE.size());
                }
            }
        }
    }

    static int imageSize() { return IMAGE_SIZE; }
    static int maxEntries() { return MAX_ENTRIES; }
    static long maxEstimatedBytes() { return MAX_ESTIMATED_BYTES; }

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
                    CACHE.size() * ESTIMATED_BYTES_PER_IMAGE,
                    prewarmedSprites);
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
            prewarmedSprites = 0;
            peakEntries = 0;
        }
    }

    private static Sprite generate(ShipType type, Color color, int variant) {
        long started = System.nanoTime();
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.translate(IMAGE_SIZE / 2.0, IMAGE_SIZE / 2.0);
        double rasterScale = rasterScale(type);
        g.scale(rasterScale, rasterScale);
        ShipShape.draw(g, type, color, variant);
        g.dispose();

        generationNanos += Math.max(0L, System.nanoTime() - started);
        generations++;
        int worldSize = Math.max(IMAGE_SIZE, (int)Math.ceil(IMAGE_SIZE / rasterScale));
        return new Sprite(image, worldSize);
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
            long estimatedBytes,
            long prewarmedSprites) { }

    record Sprite(BufferedImage image, int worldSize) { }
    private record Key(String typeId, int rgb, int visualVariant) { }
}
