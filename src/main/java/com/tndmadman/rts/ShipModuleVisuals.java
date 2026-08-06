package com.tndmadman.rts;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Random;

/** Deterministic, seed-driven vector artwork for JSON-authored ship modules. */
final class ShipModuleVisuals {
    private ShipModuleVisuals() { }

    static Icon icon(ShipModuleDefinition module, int size) {
        return new ModuleIcon(module, Math.max(22, size));
    }

    static Icon emptyIcon(int size) {
        return new EmptySocketIcon(Math.max(22, size));
    }

    private static final class ModuleIcon implements Icon {
        private final ShipModuleDefinition module;
        private final int size;

        private ModuleIcon(ShipModuleDefinition module, int size) {
            this.module = module;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = prepared(graphics);
            drawFrame(g, x, y, size, module.color(), module.seed());
            int pad = Math.max(4, size / 8);
            int gx = x + pad;
            int gy = y + pad;
            int gs = size - pad * 2;
            Random random = new Random(module.seed());
            switch (module.visualStyle()) {
                case THRUSTER -> drawThruster(g, gx, gy, gs, module.color(), random);
                case JUMP_CORE -> drawJumpCore(g, gx, gy, gs, module.color(), random);
                case DISRUPTOR -> drawDisruptor(g, gx, gy, gs, module.color(), random);
            }
            g.dispose();
        }
    }

    private static final class EmptySocketIcon implements Icon {
        private final int size;

        private EmptySocketIcon(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = prepared(graphics);
            g.setPaint(new GradientPaint(x, y, new Color(17, 32, 46), x + size, y + size, new Color(5, 12, 20)));
            g.fill(new RoundRectangle2D.Double(x, y, size - 1, size - 1, size / 4.0, size / 4.0));
            g.setColor(new Color(70, 104, 128));
            g.setStroke(new BasicStroke(Math.max(1f, size / 24f)));
            g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, size - 2, size - 2, size / 4.0, size / 4.0));
            int ring = size * 3 / 5;
            int rx = x + (size - ring) / 2;
            int ry = y + (size - ring) / 2;
            g.setColor(new Color(47, 71, 89));
            g.drawOval(rx, ry, ring, ring);
            g.drawLine(x + size / 2, ry + 3, x + size / 2, ry + ring - 3);
            g.drawLine(rx + 3, y + size / 2, rx + ring - 3, y + size / 2);
            g.dispose();
        }
    }

    private static void drawFrame(Graphics2D g, int x, int y, int size, Color color, int seed) {
        Color bright = brighten(color, 1.30);
        Color dark = darken(color, 0.20);
        g.setPaint(new GradientPaint(x, y, withAlpha(bright, 180), x + size, y + size, new Color(4, 10, 17)));
        g.fill(new RoundRectangle2D.Double(x, y, size - 1, size - 1, size / 4.0, size / 4.0));
        g.setColor(withAlpha(bright, 220));
        g.setStroke(new BasicStroke(Math.max(1.2f, size / 24f)));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, size - 2, size - 2, size / 4.0, size / 4.0));

        Random random = new Random(seed ^ 0x41C64E6D);
        g.setColor(withAlpha(dark, 100));
        for (int i = 0; i < 3; i++) {
            int inset = 3 + random.nextInt(Math.max(1, size / 7));
            int py = y + 3 + random.nextInt(Math.max(1, size - 7));
            g.drawLine(x + inset, py, x + size - inset, py);
        }
    }

    private static void drawThruster(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int cx = x + size / 2;
        int nozzleWidth = Math.max(7, size / 3);
        int top = y + size / 7;
        int bottom = y + size * 3 / 5;
        Polygon body = new Polygon();
        body.addPoint(cx - nozzleWidth / 2, top);
        body.addPoint(cx + nozzleWidth / 2, top);
        body.addPoint(cx + nozzleWidth * 2 / 3, bottom);
        body.addPoint(cx - nozzleWidth * 2 / 3, bottom);
        g.setPaint(new GradientPaint(x, top, darken(color, 0.45), x + size, bottom, brighten(color, 1.10)));
        g.fillPolygon(body);
        g.setColor(withAlpha(Color.WHITE, 170));
        g.drawPolygon(body);

        int chamber = Math.max(5, size / 5);
        g.setColor(withAlpha(brighten(color, 1.45), 220));
        g.fillOval(cx - chamber / 2, top + chamber / 3, chamber, chamber);
        g.setColor(withAlpha(Color.WHITE, 160));
        g.drawOval(cx - chamber / 2, top + chamber / 3, chamber, chamber);

        Path2D flame = new Path2D.Double();
        double wobble = 0.12 + random.nextDouble() * 0.16;
        flame.moveTo(cx - nozzleWidth * 0.42, bottom - 1);
        flame.curveTo(cx - nozzleWidth * wobble, y + size * 0.72,
                cx - nozzleWidth * 0.20, y + size * 0.90, cx, y + size * 0.97);
        flame.curveTo(cx + nozzleWidth * 0.23, y + size * 0.86,
                cx + nozzleWidth * wobble, y + size * 0.72, cx + nozzleWidth * 0.42, bottom - 1);
        flame.closePath();
        g.setPaint(new GradientPaint(cx, bottom, Color.WHITE, cx, y + size, withAlpha(color, 20)));
        g.fill(flame);

        g.setStroke(new BasicStroke(Math.max(1.2f, size / 22f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(color, 160));
        g.drawLine(x + size / 8, y + size / 3, cx - nozzleWidth / 2, y + size / 3);
        g.drawLine(cx + nozzleWidth / 2, y + size / 3, x + size * 7 / 8, y + size / 3);
    }

    private static void drawJumpCore(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int outer = Math.max(8, size * 4 / 5);
        int inner = Math.max(5, size / 3);
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(Math.max(1.4f, size / 18f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(brighten(color, 1.25), 190));
        double start = random.nextInt(360);
        for (int i = 0; i < 3; i++) {
            int diameter = outer - i * Math.max(4, size / 8);
            int offset = diameter / 2;
            g.draw(new Arc2D.Double(cx - offset, cy - offset, diameter, diameter,
                    start + i * 72, 210 + random.nextInt(80), Arc2D.OPEN));
        }
        g.setStroke(old);

        g.setPaint(new GradientPaint(cx - inner, cy - inner, Color.WHITE,
                cx + inner, cy + inner, darken(color, 0.42)));
        g.fill(new Ellipse2D.Double(cx - inner / 2.0, cy - inner / 2.0, inner, inner));
        g.setColor(withAlpha(brighten(color, 1.45), 200));
        g.fillOval(cx - inner, cy - inner, inner * 2, inner * 2);
        g.setColor(new Color(4, 12, 20, 210));
        g.fillOval(cx - inner / 2, cy - inner / 2, inner, inner);
        g.setColor(withAlpha(Color.WHITE, 215));
        g.drawOval(cx - inner / 2, cy - inner / 2, inner, inner);

        g.setColor(withAlpha(color, 130));
        for (int i = 0; i < 4; i++) {
            double angle = Math.toRadians(start + i * 90);
            int x1 = (int)Math.round(cx + Math.cos(angle) * inner);
            int y1 = (int)Math.round(cy + Math.sin(angle) * inner);
            int x2 = (int)Math.round(cx + Math.cos(angle) * outer * 0.47);
            int y2 = (int)Math.round(cy + Math.sin(angle) * outer * 0.47);
            g.drawLine(x1, y1, x2, y2);
        }
    }

    private static void drawDisruptor(Graphics2D g, int x, int y, int size, Color color, Random random) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int radius = Math.max(6, size / 3);
        g.setColor(withAlpha(darken(color, 0.42), 225));
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setColor(withAlpha(brighten(color, 1.25), 220));
        g.setStroke(new BasicStroke(Math.max(1.5f, size / 19f)));
        g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

        int teeth = 6 + random.nextInt(4);
        Polygon wave = new Polygon();
        for (int i = 0; i < teeth * 2; i++) {
            double angle = Math.PI * 2 * i / (teeth * 2.0) - Math.PI / 2;
            double rr = (i % 2 == 0) ? radius * 0.88 : radius * 0.43;
            wave.addPoint((int)Math.round(cx + Math.cos(angle) * rr),
                    (int)Math.round(cy + Math.sin(angle) * rr));
        }
        g.setColor(withAlpha(brighten(color, 1.35), 180));
        g.fillPolygon(wave);
        g.setColor(new Color(7, 13, 20, 230));
        int core = Math.max(4, size / 7);
        g.fillOval(cx - core, cy - core, core * 2, core * 2);

        g.setColor(withAlpha(color, 170));
        g.setStroke(new BasicStroke(Math.max(1.2f, size / 24f)));
        int arc = radius + Math.max(3, size / 9);
        g.drawArc(cx - arc, cy - arc, arc * 2, arc * 2, 210, 105);
        g.drawArc(cx - arc, cy - arc, arc * 2, arc * 2, 30, 105);
    }

    private static Graphics2D prepared(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    private static Color brighten(Color color, double factor) {
        return new Color(clamp((int)Math.round(color.getRed() * factor)),
                clamp((int)Math.round(color.getGreen() * factor)),
                clamp((int)Math.round(color.getBlue() * factor)));
    }

    private static Color darken(Color color, double factor) {
        return new Color(clamp((int)Math.round(color.getRed() * factor)),
                clamp((int)Math.round(color.getGreen() * factor)),
                clamp((int)Math.round(color.getBlue() * factor)));
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
