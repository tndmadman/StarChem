package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

final class RenderCulling {
    private static final double DEFAULT_PAD = 48.0;

    private RenderCulling() { }

    static boolean visible(Graphics2D g, double x, double y, double radius) {
        if (g == null || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        Rectangle clip = g.getClipBounds();
        if (clip == null) return true;
        double r = Math.max(DEFAULT_PAD, Double.isFinite(radius) ? Math.max(0, radius) : DEFAULT_PAD);
        return x + r >= clip.getMinX() && x - r <= clip.getMaxX()
                && y + r >= clip.getMinY() && y - r <= clip.getMaxY();
    }

    static boolean segmentVisible(Graphics2D g, double x1, double y1, double x2, double y2, double padding) {
        if (g == null || !GameplayCommandNumbers.finite(x1, y1, x2, y2)) return false;
        Rectangle clip = g.getClipBounds();
        if (clip == null) return true;
        double pad = Double.isFinite(padding) ? Math.max(0, padding) : 0;
        double minX = clip.getMinX() - pad;
        double minY = clip.getMinY() - pad;
        double maxX = clip.getMaxX() + pad;
        double maxY = clip.getMaxY() + pad;
        if (Math.max(x1, x2) < minX || Math.min(x1, x2) > maxX
                || Math.max(y1, y2) < minY || Math.min(y1, y2) > maxY) return false;
        Rectangle2D expanded = new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
        return expanded.contains(x1, y1) || expanded.contains(x2, y2)
                || expanded.intersectsLine(x1, y1, x2, y2);
    }
}
