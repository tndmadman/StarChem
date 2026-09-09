package com.tndmadman.rts;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Loads renderer-owned image assets from the application classpath.
 *
 * <p>The cache is deliberately bounded and also remembers missing/invalid assets so a broken
 * visual definition cannot trigger disk/JAR lookups and image decoding every frame. Renderers
 * must treat a {@code null} result as a request to use their deterministic fallback renderer.</p>
 */
final class ArtAssetManager {
    private static final int MAX_IMAGES = 256;
    private static final int MAX_MISSING = 512;

    private static long cacheHits;
    private static long cacheMisses;
    private static long decodeFailures;
    private static long evictions;

    private static final Map<String, BufferedImage> IMAGES =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    boolean remove = size() > MAX_IMAGES;
                    if (remove) evictions++;
                    return remove;
                }
            };

    private static final Map<String, Boolean> MISSING =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_MISSING;
                }
            };

    private ArtAssetManager() { }

    static BufferedImage image(String resourcePath) {
        String path = normalize(resourcePath);
        if (path == null) return null;

        synchronized (IMAGES) {
            BufferedImage cached = IMAGES.get(path);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
            if (MISSING.containsKey(path)) {
                cacheHits++;
                return null;
            }
            cacheMisses++;

            BufferedImage loaded = load(path);
            if (loaded == null) {
                MISSING.put(path, Boolean.TRUE);
                return null;
            }
            IMAGES.put(path, loaded);
            return loaded;
        }
    }

    private static BufferedImage load(String path) {
        try (InputStream stream = ArtAssetManager.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            BufferedImage image = ImageIO.read(stream);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                decodeFailures++;
                return null;
            }
            return image;
        } catch (IOException | RuntimeException ex) {
            decodeFailures++;
            return null;
        }
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null) return null;
        String path = resourcePath.trim().replace('\\', '/');
        if (path.isEmpty()) return null;
        return path.startsWith("/") ? path : "/" + path;
    }

    static Stats stats() {
        synchronized (IMAGES) {
            long estimatedBytes = 0;
            for (BufferedImage image : IMAGES.values()) {
                estimatedBytes += (long) image.getWidth() * image.getHeight() * 4L;
            }
            return new Stats(IMAGES.size(), MISSING.size(), cacheHits, cacheMisses,
                    decodeFailures, evictions, estimatedBytes);
        }
    }

    static void clear() {
        synchronized (IMAGES) {
            IMAGES.clear();
            MISSING.clear();
            cacheHits = 0;
            cacheMisses = 0;
            decodeFailures = 0;
            evictions = 0;
        }
    }

    record Stats(int imageCount, int missingCount, long cacheHits, long cacheMisses,
                 long decodeFailures, long evictions, long estimatedBytes) { }
}
