package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.AffineTransform;

final class CelestialRenderer {
    private CelestialRenderer() { }

    static void draw(Graphics2D g2, String name, double x, double y, double radius,
                     double lightX, double lightY, CelestialVisualDefinition visual, long detailSeed) {
        if (g2 == null || visual == null || radius <= 0 || !RenderCulling.visible(g2, x, y, radius * 3.2)) return;
        Graphics2D c = (Graphics2D)g2.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        c.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (visual.visualClass() == CelestialVisualClass.STAR) drawCorona(c, x, y, radius, visual);
        if (visual.hasRings()) drawRingHalf(c, x, y, radius, visual, true);

        CelestialSpriteCache.Sprite sprite = CelestialSpriteCache.sprite(visual, detailSeed);
        int d = Math.max(2, (int)Math.round(radius * 2));
        int left = (int)Math.round(x - radius);
        int top = (int)Math.round(y - radius);
        if (sprite != null) c.drawImage(sprite.surface(), left, top, d, d, null);

        if (visual.visualClass() != CelestialVisualClass.STAR) drawLighting(c, x, y, radius, lightX, lightY);
        if (sprite != null && visual.emissive()) c.drawImage(sprite.emissive(), left, top, d, d, null);
        if (visual.hasAtmosphere()) drawAtmosphere(c, x, y, radius, visual);
        if (visual.hasRings()) drawRingHalf(c, x, y, radius, visual, false);
        drawLabel(c, name, x, y, radius);
        c.dispose();
    }

    private static void drawCorona(Graphics2D g2, double x, double y, double radius, CelestialVisualDefinition visual) {
        float outer = (float)(radius * 2.15);
        RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Double(x, y), outer,
                new float[]{0f, 0.42f, 0.72f, 1f},
                new Color[]{withAlpha(visual.accent(), 90), withAlpha(visual.primary(), 52),
                        withAlpha(visual.secondary(), 22), withAlpha(visual.secondary(), 0)});
        g2.setPaint(glow);
        g2.fill(new Ellipse2D.Double(x - outer, y - outer, outer * 2, outer * 2));

        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke((float)Math.max(1.2, radius * 0.025), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(withAlpha(visual.accent(), 76));
        for (int i = 0; i < 5; i++) {
            double a = (detailAngle(visual.id(), i) + i * 1.31) % (Math.PI * 2);
            double start = radius * (1.04 + (i % 2) * 0.06);
            double reach = radius * (1.22 + (i % 3) * 0.11);
            int x1 = (int)Math.round(x + Math.cos(a) * start);
            int y1 = (int)Math.round(y + Math.sin(a) * start);
            int x2 = (int)Math.round(x + Math.cos(a + 0.14) * reach);
            int y2 = (int)Math.round(y + Math.sin(a + 0.14) * reach);
            g2.drawLine(x1, y1, x2, y2);
        }
        g2.setStroke(old);
    }

    private static double detailAngle(String id, int index) {
        long value = 0x9E3779B97F4A7C15L ^ (id == null ? 0 : id.hashCode()) ^ ((long)index << 33);
        value ^= value >>> 29;
        return ((value & 0xFFFF) / 65535.0) * Math.PI * 2;
    }

    private static void drawLighting(Graphics2D g2, double x, double y, double radius, double lightX, double lightY) {
        double dx = lightX - x;
        double dy = lightY - y;
        double length = Math.hypot(dx, dy);
        if (length < 0.001) { dx = -1; dy = -0.35; length = Math.hypot(dx, dy); }
        double lx = dx / length;
        double ly = dy / length;
        Ellipse2D body = new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2);
        Graphics2D shade = (Graphics2D)g2.create();
        shade.clip(body);
        Point2D start = new Point2D.Double(x + lx * radius, y + ly * radius);
        Point2D end = new Point2D.Double(x - lx * radius, y - ly * radius);
        LinearGradientPaint night = new LinearGradientPaint(
                start, end,
                new float[]{0f, 0.38f, 0.56f, 0.76f, 1f},
                new Color[]{new Color(255, 255, 255, 22), new Color(255, 255, 255, 0),
                        new Color(0, 0, 0, 36), new Color(0, 0, 0, 118), new Color(0, 0, 0, 188)});
        shade.setPaint(night);
        shade.fill(body);
        RadialGradientPaint limb = new RadialGradientPaint(
                new Point2D.Double(x + lx * radius * 0.28, y + ly * radius * 0.28),
                (float)(radius * 1.18),
                new float[]{0f, 0.66f, 1f},
                new Color[]{new Color(255, 255, 255, 18), new Color(0, 0, 0, 0), new Color(0, 0, 0, 82)});
        shade.setPaint(limb);
        shade.fill(body);
        shade.dispose();
    }

    private static void drawAtmosphere(Graphics2D g2, double x, double y, double radius, CelestialVisualDefinition visual) {
        int alpha = (int)Math.round(150 * visual.atmosphereStrength());
        Stroke old = g2.getStroke();
        for (int i = 0; i < 3; i++) {
            double expand = radius * (0.035 + i * 0.035);
            int layerAlpha = Math.max(12, alpha / (i + 1));
            g2.setColor(withAlpha(visual.atmosphere(), layerAlpha));
            g2.setStroke(new BasicStroke((float)Math.max(1.0, radius * (0.035 - i * 0.006))));
            g2.draw(new Ellipse2D.Double(x - radius - expand, y - radius - expand,
                    (radius + expand) * 2, (radius + expand) * 2));
        }
        g2.setStroke(old);
    }

    private static void drawRingHalf(Graphics2D g2, double x, double y, double radius,
                                     CelestialVisualDefinition visual, boolean back) {
        double outer = radius * visual.ringOuterRadius();
        double inner = radius * visual.ringInnerRadius();
        double middle = (outer + inner) * 0.5;
        double thickness = Math.max(1.2, outer - inner);
        Graphics2D ring = (Graphics2D)g2.create();
        ring.translate(x, y);
        ring.rotate(visual.ringAngle());
        ring.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ring.setStroke(new BasicStroke((float)thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        ring.setColor(withAlpha(visual.ringColor(), back ? 92 : 160));
        double h = middle * 2 * visual.ringFlattening();
        Arc2D arc = new Arc2D.Double(-middle, -h / 2, middle * 2, h,
                back ? 0 : 180, 180, Arc2D.OPEN);
        ring.draw(arc);
        ring.setStroke(new BasicStroke((float)Math.max(1, thickness * 0.18)));
        ring.setColor(withAlpha(visual.accent(), back ? 48 : 82));
        ring.draw(arc);
        ring.dispose();
    }

    private static void drawLabel(Graphics2D g2, String name, double x, double y, double radius) {
        if (name == null || name.isBlank()) return;
        double scale = renderScale(g2);
        if (scale < 0.43 && radius < 30) return;
        float screenPx = radius > 100 ? 13f : 11.5f;
        float worldSize = (float)Math.max(10, Math.min(34, screenPx / Math.max(0.18, scale)));
        Font old = g2.getFont();
        g2.setFont(old.deriveFont(Font.BOLD, worldSize));
        float tx = (float)(x + radius + 9 / Math.max(0.2, scale));
        float ty = (float)(y - radius * 0.55);
        g2.setColor(new Color(0, 0, 0, 185));
        g2.drawString(name, tx + (float)(1.5 / Math.max(0.2, scale)), ty + (float)(1.5 / Math.max(0.2, scale)));
        g2.setColor(new Color(225, 237, 246, 205));
        g2.drawString(name, tx, ty);
        g2.setFont(old);
    }

    private static double renderScale(Graphics2D g2) {
        AffineTransform tx = g2.getTransform();
        double sx = Math.hypot(tx.getScaleX(), tx.getShearY());
        return Double.isFinite(sx) && sx > 0 ? sx : 1;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
