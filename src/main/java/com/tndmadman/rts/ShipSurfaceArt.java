package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Paint;
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

        // This path is close-LOD only. Precompute hull/paint/composite once per authored hull and
        // restore the caller state in-place instead of allocating a child Graphics2D per ship.
        Shape oldClip = g2.getClip();
        Composite oldComposite = g2.getComposite();
        Paint oldPaint = g2.getPaint();
        try {
            g2.clip(surface.hull());
            g2.setComposite(surface.composite());
            g2.setPaint(surface.paint());
            g2.fill(surface.hull());
        } finally {
            g2.setPaint(oldPaint);
            g2.setComposite(oldComposite);
            g2.setClip(oldClip);
        }
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
        TexturePaint paint = new TexturePaint(HULL_PANELS,
                new Rectangle2D.Double(anchorX, anchorY, tile, tile));
        return new Surface(hull, paint, AlphaComposite.SrcOver.derive(opacity));
    }

    private record Surface(Shape hull, TexturePaint paint, AlphaComposite composite) { }
}
