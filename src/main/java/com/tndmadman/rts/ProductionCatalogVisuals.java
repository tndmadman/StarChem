package com.tndmadman.rts;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Procedural Manufacturing catalog thumbnails backed by the live ship/station sprite caches. */
final class ProductionCatalogVisuals {
    static final int MANUFACTURING_PREVIEW_VARIANT = 0;
    private static final int MAX_ICON_CACHE_ENTRIES = 256;

    private static final Map<Key, Icon> ICON_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Icon> eldest) {
            return size() > MAX_ICON_CACHE_ENTRIES;
        }
    };

    private ProductionCatalogVisuals() { }

    static Icon shipIcon(String hullId, Color ownerColor, int size) {
        if (hullId == null || hullId.isBlank() || ownerColor == null || size <= 0) return null;
        ShipType type = Rules.findShip(hullId);
        if (type == null) return null;
        Key key = new Key(Kind.SHIP, type.id, ownerColor.getRGB(), size, MANUFACTURING_PREVIEW_VARIANT);
        return cachedIcon(key, () -> {
            ShipSpriteCache.Sprite sprite =
                    ShipSpriteCache.sprite(type, ownerColor, MANUFACTURING_PREVIEW_VARIANT);
            return sprite == null ? null : sprite.image();
        });
    }

    static Icon stationIcon(String stationTypeId, Color ownerColor, int size) {
        if (stationTypeId == null || stationTypeId.isBlank() || ownerColor == null || size <= 0
                || Rules.findBase(stationTypeId) == null) return null;
        Key key = new Key(Kind.STATION, stationTypeId, ownerColor.getRGB(), size, 0);
        return cachedIcon(key, () -> StationSpriteCache.previewImage(
                stationTypeId, ownerColor, StationSpriteCache.Lod.DETAILED));
    }

    private static Icon cachedIcon(Key key, Supplier<BufferedImage> sourceSupplier) {
        synchronized (ICON_CACHE) {
            Icon cached = ICON_CACHE.get(key);
            if (cached != null) return cached;
        }

        BufferedImage source = sourceSupplier.get();
        if (source == null) return null;
        Icon created = scaleToIcon(source, key.size());

        synchronized (ICON_CACHE) {
            Icon raced = ICON_CACHE.get(key);
            if (raced != null) return raced;
            ICON_CACHE.put(key, created);
            return created;
        }
    }

    private static Icon scaleToIcon(BufferedImage source, int size) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) return null;

        int padding = Math.max(1, Math.min(3, size / 12));
        int available = Math.max(1, size - padding * 2);
        double scale = Math.min(available / (double)sourceWidth, available / (double)sourceHeight);
        int width = Math.max(1, (int)Math.round(sourceWidth * scale));
        int height = Math.max(1, (int)Math.round(sourceHeight * scale));
        int x = (size - width) / 2;
        int y = (size - height) / 2;

        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(source, x, y, width, height, null);
        } finally {
            g.dispose();
        }
        return new ImageIcon(target);
    }

    static void resetForTest() {
        synchronized (ICON_CACHE) {
            ICON_CACHE.clear();
        }
    }

    static int cachedIconCount() {
        synchronized (ICON_CACHE) {
            return ICON_CACHE.size();
        }
    }

    private enum Kind { SHIP, STATION }

    private record Key(Kind kind, String typeId, int rgb, int size, int visualVariant) { }
}
