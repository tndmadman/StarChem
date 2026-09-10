package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies small reusable repository-backed textures to authored ship hull geometry. */
final class ShipSurfaceArt {
    private static final BufferedImage HULL_PANELS = ArtAssetCache.image("materials/hull-panels-01.png");
    private static final int MAX_HULLS = 128;
    private static final int MAX_SURFACE_DIMENSION = 512;
    private static final Map<String, Surface> SURFACES = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Surface> eldest) {
            return size() > MAX_HULLS;
        }
    };

    private ShipSurfaceArt() { }

    static void draw(Graphics2D g2, ShipType type) {
        if (g2 == null || type == null || ArtAssetCache.isFallback(HULL_PANELS)) return;
        Surface surface = surface(type);
        if (surface == null) return;
        g2.drawImage(surface.image(), surface.x(), surface.y(), null);
    }

    private static Surface surface(ShipType type) {
        synchronized (SURFACES) {
            return SURFACES.computeIfAbsent(type.id, ignored -> createSurface(type));
        }
    }

    private static Surface createSurface(ShipType type) {
        Shape hull = ShipShape.create(type);
        Rectangle2D bounds = hull.getBounds2D();
        if (bounds.isEmpty()) return null;

        // #393 remains authoritative for the authored silhouette/hardpoint geometry. The generic
        // visual catalog only drives deterministic secondary surface variation, keeping the #388
        // rule intact: authored identity first, procedural variation second.
        CatalogShipVisualDefinition metadata = VisualCatalog.ship(type.id);
        int detail = metadata.detailCount();
        long seed = metadata.seed();
        double tile = Math.max(12.0, (25.0 - detail * 1.25) * type.size.scale);
        double offsetX = Math.floorMod(seed, 23L) / 23.0 * tile;
        double offsetY = Math.floorMod(seed >>> 8, 29L) / 29.0 * tile;
        double anchorX = Math.floor(bounds.getX() / tile) * tile + offsetX;
        double anchorY = Math.floor(bounds.getY() / tile) * tile + offsetY;
        float opacity = (float)Math.max(0.52, Math.min(0.76, 0.54 + detail * 0.035));

        int x = (int)Math.floor(bounds.getX()) - 2;
        int y = (int)Math.floor(bounds.getY()) - 2;
        int width = (int)Math.ceil(bounds.getMaxX()) - x + 2;
        int height = (int)Math.ceil(bounds.getMaxY()) - y + 2;
        // Keep the secondary-art cache bounded even if malformed/future hull geometry becomes huge.
        if (width <= 0 || height <= 0 || width > MAX_SURFACE_DIMENSION || height > MAX_SURFACE_DIMENSION) {
            return null;
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D art = image.createGraphics();
        try {
            art.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            art.translate(-x, -y);
            art.clip(hull);
            art.setComposite(AlphaComposite.SrcOver.derive(opacity));
            art.setPaint(new TexturePaint(HULL_PANELS,
                    new Rectangle2D.Double(anchorX, anchorY, tile, tile)));
            art.fill(hull);
        } finally {
            art.dispose();
        }
        return new Surface(image, x, y);
    }

    private record Surface(BufferedImage image, int x, int y) { }
}
