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
    private static final Map<Key, Sprite> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Sprite> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ShipSpriteCache() { }

    static Sprite sprite(Unit unit, Color color) {
        if (unit == null || color == null) return null;
        int bucket = headingBucket(unit.heading);
        int variant = ShipVisualStyle.variantIndex(unit);
        Key key = new Key(unit.shipTypeId, color.getRGB(), variant, bucket);
        synchronized (CACHE) {
            Sprite cached = CACHE.get(key);
            if (cached != null) return cached;
            Sprite sprite = render(unit, color, variant, bucket);
            CACHE.put(key, sprite);
            return sprite;
        }
    }

    static int imageSize() { return IMAGE_SIZE; }

    static double rasterScale(ShipType type) {
        if (type == null) return 1.0;
        double radius = ShipVisualCatalog.forType(type).renderRadius(type.size.scale);
        double safeRadius = IMAGE_SIZE / 2.0 - SPRITE_PADDING;
        if (!Double.isFinite(radius) || radius <= 0 || radius <= safeRadius) return 1.0;
        return Math.max(0.20, safeRadius / radius);
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

    record Sprite(BufferedImage image, int worldSize) { }
    private record Key(String typeId, int rgb, int visualVariant, int headingBucket) { }
}
