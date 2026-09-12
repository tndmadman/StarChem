package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded RAM cache for deterministic station architecture.
 *
 * Moving machinery, pulses, navigation lights, production glow and other state/time-dependent
 * presentation stay in {@link StationRenderer}; only the expensive static structure is rasterized.
 */
final class StationSpriteCache {
    static final long MAX_ESTIMATED_BYTES = 48L * 1024L * 1024L;
    private static final int PADDING = 14;
    private static final int MIN_IMAGE_SIZE = 64;

    enum Lod { FAR, MEDIUM, DETAILED }

    private static final Map<Key, Sprite> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static long estimatedBytes;
    private static long requests;
    private static long hits;
    private static long misses;
    private static long runtimeGenerations;
    private static long prewarmedSprites;
    private static long generationNanos;
    private static long evictions;
    private static int peakEntries;
    private static long peakEstimatedBytes;

    private StationSpriteCache() { }

    static boolean drawStatic(Graphics2D source, Base base, Color owner, Lod lod) {
        if (source == null || base == null || owner == null || lod == null) return false;
        Sprite sprite = sprite(base, owner, lod, true);
        if (sprite == null) return false;
        int size = sprite.worldSize();
        int x = (int)Math.round(base.x - size / 2.0);
        int y = (int)Math.round(base.y - size / 2.0);
        source.drawImage(sprite.image(), x, y, size, size, null);
        return true;
    }

    /** Pre-generates all finite configured station static layers for one owner color. */
    static void prewarm(Color owner) {
        if (owner == null) return;
        for (String typeId : Rules.BASES.keySet()) {
            Base representative;
            try {
                representative = new Base("SPRITE-" + typeId, "SPRITE", typeId, 0, 0);
            } catch (RuntimeException ex) {
                continue;
            }
            for (Lod lod : Lod.values()) {
                sprite(representative, owner, lod, false);
            }
        }
    }

    private static Sprite sprite(Base base, Color owner, Lod lod, boolean runtimeRequest) {
        Key key = new Key(base.typeId, owner.getRGB(), lod);
        synchronized (CACHE) {
            if (runtimeRequest) requests++;
            Sprite cached = CACHE.get(key);
            if (cached != null) {
                if (runtimeRequest) hits++;
                return cached;
            }
            if (runtimeRequest) misses++;

            long started = System.nanoTime();
            Sprite created;
            try {
                created = render(base, owner, lod);
            } catch (RuntimeException ex) {
                return null;
            }
            generationNanos += Math.max(0L, System.nanoTime() - started);
            if (runtimeRequest) runtimeGenerations++;
            else prewarmedSprites++;
            putBounded(key, created);
            return created;
        }
    }

    private static Sprite render(Base base, Color owner, Lod lod) {
        double extent = Math.max(24.0, StationRenderer.visualExtent(base));
        int size = Math.max(MIN_IMAGE_SIZE, (int)Math.ceil(extent * 2.0 + PADDING * 2.0));
        // Keep an even center so origin-aligned authored geometry lands consistently.
        if ((size & 1) != 0) size++;

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.translate(size / 2.0, size / 2.0);
            if (lod == Lod.FAR) StationRenderer.drawFarStaticAtOrigin(g, base, owner);
            else StationRenderer.drawStaticAtOrigin(g, base, owner, lod == Lod.DETAILED);
        } finally {
            g.dispose();
        }
        return new Sprite(image, size, rawBytes(image));
    }

    private static void putBounded(Key key, Sprite sprite) {
        Sprite previous = CACHE.put(key, sprite);
        if (previous != null) estimatedBytes -= previous.rawBytes();
        estimatedBytes += sprite.rawBytes();

        Iterator<Map.Entry<Key, Sprite>> it = CACHE.entrySet().iterator();
        while (estimatedBytes > MAX_ESTIMATED_BYTES && CACHE.size() > 1 && it.hasNext()) {
            Map.Entry<Key, Sprite> eldest = it.next();
            estimatedBytes -= eldest.getValue().rawBytes();
            it.remove();
            evictions++;
        }
        peakEntries = Math.max(peakEntries, CACHE.size());
        peakEstimatedBytes = Math.max(peakEstimatedBytes, estimatedBytes);
    }

    private static long rawBytes(BufferedImage image) {
        return image == null ? 0L : (long)image.getWidth() * image.getHeight() * Integer.BYTES;
    }

    static Snapshot snapshot() {
        synchronized (CACHE) {
            long total = requests;
            return new Snapshot(
                    CACHE.size(), estimatedBytes, peakEntries, peakEstimatedBytes,
                    requests, hits, misses, runtimeGenerations, prewarmedSprites, evictions,
                    total == 0 ? 0.0 : hits / (double)total,
                    generationNanos / 1_000_000.0,
                    MAX_ESTIMATED_BYTES);
        }
    }

    static void resetForTest() {
        synchronized (CACHE) {
            CACHE.clear();
            estimatedBytes = 0;
            requests = 0;
            hits = 0;
            misses = 0;
            runtimeGenerations = 0;
            prewarmedSprites = 0;
            generationNanos = 0;
            evictions = 0;
            peakEntries = 0;
            peakEstimatedBytes = 0;
        }
    }

    record Snapshot(
            int entries,
            long estimatedBytes,
            int peakEntries,
            long peakEstimatedBytes,
            long requests,
            long hits,
            long misses,
            long runtimeGenerations,
            long prewarmedSprites,
            long evictions,
            double hitRate,
            double generationMs,
            long maxEstimatedBytes) { }

    private record Key(String typeId, int rgb, Lod lod) { }
    private record Sprite(BufferedImage image, int worldSize, long rawBytes) { }
}
