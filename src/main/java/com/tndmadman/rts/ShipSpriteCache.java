package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pre-renders medium-LOD ship hulls into bounded orientation buckets. */
final class ShipSpriteCache {
    private static final int BUCKETS = 48;
    private static final int IMAGE_SIZE = 144;
    private static final int MAX_ENTRIES = 1536;
    private static final Map<Key, BufferedImage> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, BufferedImage> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ShipSpriteCache() { }

    static BufferedImage sprite(Unit unit, Color color) {
        if (unit == null || color == null) return null;
        int bucket = headingBucket(unit.heading);
        Key key = new Key(unit.shipTypeId, color.getRGB(), bucket);
        synchronized (CACHE) {
            BufferedImage cached = CACHE.get(key);
            if (cached != null) return cached;
            BufferedImage image = render(unit, color, bucket);
            CACHE.put(key, image);
            return image;
        }
    }

    static int imageSize() { return IMAGE_SIZE; }

    private static BufferedImage render(Unit unit, Color color, int bucket) {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.translate(IMAGE_SIZE / 2.0, IMAGE_SIZE / 2.0);
        g.rotate(bucket * Math.PI * 2.0 / BUCKETS);
        ShipShape.draw(g, unit.type(), color);
        g.dispose();
        return image;
    }

    private static int headingBucket(double heading) {
        if (!Double.isFinite(heading)) return 0;
        double turns = heading / (Math.PI * 2.0);
        return Math.floorMod((int)Math.round(turns * BUCKETS), BUCKETS);
    }

    private record Key(String typeId, int rgb, int headingBucket) { }
}
