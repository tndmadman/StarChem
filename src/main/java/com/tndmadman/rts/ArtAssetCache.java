package com.tndmadman.rts;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JAR-safe loader and bounded cache for reusable repository art assets. */
final class ArtAssetCache {
    private static final Logger LOG = Logger.getLogger(ArtAssetCache.class.getName());
    private static final String RESOURCE_ROOT = "/art/";
    private static final int DEFAULT_MAX_ENTRIES = 256;
    private static final long DEFAULT_MAX_BYTES = 24L * 1024L * 1024L;
    private static final int MAX_ENTRIES = Math.max(16,
            Integer.getInteger("starchem.artCacheEntries", DEFAULT_MAX_ENTRIES));
    private static final long MAX_BYTES = Math.max(1024L * 1024L,
            Long.getLong("starchem.artCacheBytes", DEFAULT_MAX_BYTES));
    private static final BufferedImage FALLBACK = createFallback();
    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final Set<String> WARNED = new LinkedHashSet<>();
    private static long cachedBytes;

    private ArtAssetCache() { }

    /**
     * Returns a shared read-only image for an art-relative path such as
     * {@code materials/hull-panels-01.png}. Missing, corrupt, unsupported, or unsafe
     * paths return a deterministic fallback image and log a warning once per key.
     */
    static BufferedImage image(String relativePath) {
        String normalized = normalize(relativePath);
        if (normalized == null) {
            warnOnce("invalid:" + String.valueOf(relativePath),
                    "Rejected invalid art asset path: " + String.valueOf(relativePath));
            return FALLBACK;
        }

        synchronized (CACHE) {
            CacheEntry cached = CACHE.get(normalized);
            if (cached != null) return cached.image;

            BufferedImage loaded = load(normalized);
            if (loaded == null) loaded = FALLBACK;
            long bytes = loaded == FALLBACK ? 0 : estimatedBytes(loaded);
            if (bytes > MAX_BYTES) {
                warnOnce("oversize:" + normalized,
                        "Art asset exceeds cache budget and will use fallback: " + normalized);
                loaded = FALLBACK;
                bytes = 0;
            }
            put(normalized, loaded, bytes);
            return loaded;
        }
    }

    static boolean isFallback(BufferedImage image) {
        return image == FALLBACK;
    }

    static int cacheEntryCountForValidation() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    static long cachedBytesForValidation() {
        synchronized (CACHE) {
            return cachedBytes;
        }
    }

    static long maxBytesForValidation() {
        return MAX_BYTES;
    }

    static void clearForValidation() {
        synchronized (CACHE) {
            CACHE.clear();
            cachedBytes = 0;
        }
        synchronized (WARNED) {
            WARNED.clear();
        }
    }

    private static BufferedImage load(String normalized) {
        String extension = extension(normalized);
        if (!extension.equals("png") && !extension.equals("gif") && !extension.equals("jpg")
                && !extension.equals("jpeg") && !extension.equals("bmp")) {
            warnOnce("format:" + normalized, "Unsupported art asset format: " + normalized);
            return null;
        }

        try (InputStream input = ArtAssetCache.class.getResourceAsStream(RESOURCE_ROOT + normalized)) {
            if (input == null) {
                warnOnce("missing:" + normalized, "Missing art asset: " + RESOURCE_ROOT + normalized);
                return null;
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                warnOnce("decode:" + normalized, "Could not decode art asset: " + normalized);
                return null;
            }
            return image;
        } catch (IOException | RuntimeException ex) {
            warnOnce("error:" + normalized,
                    "Failed to load art asset " + normalized + ": " + ex.getMessage());
            return null;
        }
    }

    private static void put(String key, BufferedImage image, long bytes) {
        CacheEntry previous = CACHE.put(key, new CacheEntry(image, bytes));
        if (previous != null) cachedBytes -= previous.bytes;
        cachedBytes += bytes;

        Iterator<Map.Entry<String, CacheEntry>> iterator = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) && iterator.hasNext()) {
            Map.Entry<String, CacheEntry> eldest = iterator.next();
            cachedBytes -= eldest.getValue().bytes;
            iterator.remove();
        }
    }

    private static long estimatedBytes(BufferedImage image) {
        return (long) image.getWidth() * image.getHeight() * 4L;
    }

    private static String normalize(String relativePath) {
        if (relativePath == null) return null;
        String candidate = relativePath.trim().replace('\\', '/');
        if (candidate.isBlank() || candidate.startsWith("/") || candidate.contains(":")) return null;
        StringBuilder normalized = new StringBuilder(candidate.length());
        for (String segment : candidate.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) return null;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-')) return null;
            }
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
        }
        return normalized.toString();
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void warnOnce(String key, String message) {
        synchronized (WARNED) {
            if (!WARNED.add(key)) return;
            while (WARNED.size() > 256) {
                Iterator<String> iterator = WARNED.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
        LOG.log(Level.WARNING, message);
    }

    private static BufferedImage createFallback() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < 8; y += 4) {
            for (int x = 0; x < 8; x += 4) {
                g.setColor(((x + y) / 4) % 2 == 0 ? new Color(255, 0, 255) : new Color(25, 25, 25));
                g.fillRect(x, y, 4, 4);
            }
        }
        g.dispose();
        return image;
    }

    private record CacheEntry(BufferedImage image, long bytes) { }
}
