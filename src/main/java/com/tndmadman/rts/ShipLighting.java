package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.concurrent.ConcurrentHashMap;

/** Directional presentation-only lighting shared by detailed and cached ship rendering. */
final class ShipLighting {
    private static final ConcurrentHashMap<String, LightingMask> CACHE = new ConcurrentHashMap<>();

    private ShipLighting() { }

    static void draw(Graphics2D g2, ShipType type) {
        if (g2 == null || type == null) return;
        LightingMask mask = CACHE.computeIfAbsent(type.id, ignored -> build(type));
        Shape oldClip = g2.getClip();
        Paint oldPaint = g2.getPaint();
        g2.clip(mask.hull());
        g2.setPaint(mask.paint());
        Rectangle2D b = mask.bounds();
        g2.fill(b);
        g2.setPaint(oldPaint);
        g2.setClip(oldClip);
    }

    private static LightingMask build(ShipType type) {
        Path2D hull = ShowcaseShipRenderer.supports(type) ? ShowcaseShipRenderer.create(type) : ShipShape.create(type);
        Rectangle2D bounds = hull.getBounds2D();
        double span = Math.max(1.0, Math.max(bounds.getWidth(), bounds.getHeight()));
        Point2D start = new Point2D.Double(bounds.getMinX() - span * 0.10, bounds.getMinY() - span * 0.20);
        Point2D end = new Point2D.Double(bounds.getMaxX() + span * 0.12, bounds.getMaxY() + span * 0.18);
        LinearGradientPaint paint = new LinearGradientPaint(start, end,
                new float[]{0f, 0.24f, 0.56f, 1f},
                new Color[]{new Color(255, 255, 255, 76), new Color(210, 235, 255, 34),
                        new Color(0, 0, 0, 18), new Color(0, 0, 0, 96)});
        return new LightingMask(hull, bounds, paint);
    }

    private record LightingMask(Path2D hull, Rectangle2D bounds, Paint paint) { }
}
