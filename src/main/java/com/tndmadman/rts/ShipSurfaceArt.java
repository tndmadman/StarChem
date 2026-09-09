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
    private static final Map<String, Shape> HULLS = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Shape> eldest) {
            return size() > MAX_HULLS;
        }
    };

    private ShipSurfaceArt() { }

    static void draw(Graphics2D g2, ShipType type) {
        if (g2 == null || type == null || ArtAssetCache.isFallback(HULL_PANELS)) return;
        Shape hull = hull(type);
        Rectangle2D bounds = hull.getBounds2D();
        if (bounds.isEmpty()) return;

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

        Graphics2D art = (Graphics2D)g2.create();
        art.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        art.clip(hull);
        art.setComposite(AlphaComposite.SrcOver.derive(opacity));
        art.setPaint(new TexturePaint(HULL_PANELS,
                new Rectangle2D.Double(anchorX, anchorY, tile, tile)));
        art.fill(hull);
        art.dispose();
    }

    private static Shape hull(ShipType type) {
        synchronized (HULLS) {
            return HULLS.computeIfAbsent(type.id, ignored -> ShipShape.create(type));
        }
    }
}
