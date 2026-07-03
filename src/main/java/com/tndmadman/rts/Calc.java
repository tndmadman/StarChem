package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.Locale;

final class Calc {
    private Calc() { }
    static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    static double distance(double ax, double ay, double bx, double by) { return Math.hypot(ax - bx, ay - by); }
    static double lerp(double from, double to, double t) { return from + (to - from) * t; }
    static String round(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    static Point2D spawnPoint(int slot) {
        double[][] points = {{220,260},{1840,1020},{1840,260},{220,1020},{1080,240},{1080,1080},{520,700},{1640,700}};
        double[] p = points[Math.floorMod(slot, points.length)];
        int ring = slot / points.length;
        return new Point2D.Double(p[0] + ring * 34, p[1] + ring * 34);
    }
    static Point2D basePoint(int slot) {
        Point2D p = spawnPoint(slot);
        return new Point2D.Double(clamp(p.getX() + (slot % 2 == 0 ? -96 : 96), 90, 2110), clamp(p.getY() + (slot % 3 == 0 ? 92 : -92), 90, 1310));
    }
}
