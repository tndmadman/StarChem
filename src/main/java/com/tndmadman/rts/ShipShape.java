package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.Random;

final class ShipShape {
    private ShipShape() { }

    static Path2D create(ShipType type) {
        return hull(type, family(type), new Random(type.seed));
    }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        VisualFamily family = family(type);
        Random r = new Random(type.seed);
        Path2D hull = hull(type, family, r);
        double s = type.size.scale;
        Graphics2D ship = (Graphics2D) g2.create();
        ship.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ship.setStroke(new BasicStroke((float)Math.max(1.3, s * 1.35), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        ship.setColor(playerColor.darker().darker());
        ship.fill(hull);
        ship.setColor(playerColor);
        ship.draw(hull);
        drawDetails(ship, type, family, playerColor, new Random(type.seed ^ 0x5EEDBEEF));
        ship.dispose();
    }

    private static VisualFamily family(ShipType type) {
        String id = type.id.toLowerCase();
        if (id.contains("monolith")) return VisualFamily.MONOLITH;
        if (type.baseBuilder || id.contains("builder") || id.contains("deployer")) return VisualFamily.BUILDER;
        if (id.contains("scout") || type.scoutRange > 0) return VisualFamily.SCOUT;
        if (id.contains("gas")) return VisualFamily.GAS;
        if (type.harvestRange > 0 && type.harvestKinds.contains(NodeKind.SILICATE_ROCK)) return VisualFamily.MINER;
        if (id.contains("hauler") || id.contains("freighter") || type.cargoCapacity >= 300) return VisualFamily.CARGO;
        if (id.contains("carrier")) return VisualFamily.CARRIER;
        if (id.contains("dread") || id.contains("titan")) return VisualFamily.SIEGE;
        return VisualFamily.COMBAT;
    }

    private static Path2D hull(ShipType type, VisualFamily family, Random r) {
        double s = type.size.scale;
        double length = 38 * s * (0.92 + r.nextDouble() * 0.34);
        double width = 21 * s * (0.86 + r.nextDouble() * 0.28);
        return switch (family) {
            case SCOUT -> needle(length * 1.18, width * 0.72, r);
            case MINER -> miner(length * 1.02, width * 1.08, r);
            case GAS -> gas(length * 0.98, width * 1.18, r);
            case CARGO -> cargo(length * 1.20, width * 1.03, r);
            case BUILDER -> builder(length * 1.05, width * 1.20, r);
            case CARRIER -> carrier(length * 1.26, width * 1.26, r);
            case SIEGE -> siege(length * 1.32, width * 1.16, r);
            case MONOLITH -> monolith(length * 1.44, width * 1.38, r);
            case COMBAT -> combat(length * 1.08, width, r);
        };
    }

    private static Path2D needle(double length, double width, Random r) {
        double tail = length * 0.58;
        double wing = width * (1.05 + r.nextDouble() * 0.35);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.74, 0);
        p.lineTo(-length * 0.20, -width * 0.30);
        p.lineTo(length * 0.08, -wing);
        p.lineTo(length * 0.34, -width * 0.38);
        p.lineTo(tail, -width * 0.20);
        p.lineTo(length * 0.46, 0);
        p.lineTo(tail, width * 0.20);
        p.lineTo(length * 0.34, width * 0.38);
        p.lineTo(length * 0.08, wing);
        p.lineTo(-length * 0.20, width * 0.30);
        p.closePath();
        return p;
    }

    private static Path2D miner(double length, double width, Random r) {
        double jaw = width * (0.44 + r.nextDouble() * 0.20);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.62, -jaw);
        p.lineTo(-length * 0.28, -width * 0.78);
        p.lineTo(length * 0.16, -width * 0.68);
        p.lineTo(length * 0.58, -width * 0.28);
        p.lineTo(length * 0.42, 0);
        p.lineTo(length * 0.58, width * 0.28);
        p.lineTo(length * 0.16, width * 0.68);
        p.lineTo(-length * 0.28, width * 0.78);
        p.lineTo(-length * 0.62, jaw);
        p.lineTo(-length * 0.45, 0);
        p.closePath();
        return p;
    }

    private static Path2D gas(double length, double width, Random r) {
        double scoop = width * (1.05 + r.nextDouble() * 0.22);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.60, 0);
        p.curveTo(-length * 0.42, -scoop, length * 0.18, -scoop, length * 0.50, -width * 0.38);
        p.lineTo(length * 0.66, -width * 0.14);
        p.lineTo(length * 0.46, 0);
        p.lineTo(length * 0.66, width * 0.14);
        p.curveTo(length * 0.18, scoop, -length * 0.42, scoop, -length * 0.60, 0);
        p.closePath();
        return p;
    }

    private static Path2D cargo(double length, double width, Random r) {
        double box = width * (0.58 + r.nextDouble() * 0.16);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.62, -width * 0.24);
        p.lineTo(-length * 0.40, -box);
        p.lineTo(length * 0.42, -box);
        p.lineTo(length * 0.66, -width * 0.22);
        p.lineTo(length * 0.50, 0);
        p.lineTo(length * 0.66, width * 0.22);
        p.lineTo(length * 0.42, box);
        p.lineTo(-length * 0.40, box);
        p.lineTo(-length * 0.62, width * 0.24);
        p.closePath();
        return p;
    }

    private static Path2D builder(double length, double width, Random r) {
        double arm = width * (0.95 + r.nextDouble() * 0.28);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.58, -width * 0.40);
        p.lineTo(-length * 0.22, -arm);
        p.lineTo(length * 0.24, -arm * 0.78);
        p.lineTo(length * 0.58, -width * 0.48);
        p.lineTo(length * 0.58, width * 0.48);
        p.lineTo(length * 0.24, arm * 0.78);
        p.lineTo(-length * 0.22, arm);
        p.lineTo(-length * 0.58, width * 0.40);
        p.closePath();
        return p;
    }

    private static Path2D combat(double length, double width, Random r) {
        double wing = width * (0.86 + r.nextDouble() * 0.34);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.68, 0);
        p.lineTo(-length * 0.34, -width * 0.54);
        p.lineTo(-length * 0.02, -wing);
        p.lineTo(length * 0.28, -width * 0.58);
        p.lineTo(length * 0.66, -width * 0.22);
        p.lineTo(length * 0.44, 0);
        p.lineTo(length * 0.66, width * 0.22);
        p.lineTo(length * 0.28, width * 0.58);
        p.lineTo(-length * 0.02, wing);
        p.lineTo(-length * 0.34, width * 0.54);
        p.closePath();
        return p;
    }

    private static Path2D carrier(double length, double width, Random r) {
        double deck = width * (0.82 + r.nextDouble() * 0.18);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.62, -width * 0.30);
        p.lineTo(-length * 0.44, -deck);
        p.lineTo(length * 0.48, -deck);
        p.lineTo(length * 0.70, -width * 0.38);
        p.lineTo(length * 0.52, 0);
        p.lineTo(length * 0.70, width * 0.38);
        p.lineTo(length * 0.48, deck);
        p.lineTo(-length * 0.44, deck);
        p.lineTo(-length * 0.62, width * 0.30);
        p.closePath();
        return p;
    }

    private static Path2D siege(double length, double width, Random r) {
        double armor = width * (0.68 + r.nextDouble() * 0.22);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.72, 0);
        p.lineTo(-length * 0.52, -width * 0.38);
        p.lineTo(-length * 0.10, -armor);
        p.lineTo(length * 0.52, -armor * 0.88);
        p.lineTo(length * 0.76, -width * 0.24);
        p.lineTo(length * 0.60, 0);
        p.lineTo(length * 0.76, width * 0.24);
        p.lineTo(length * 0.52, armor * 0.88);
        p.lineTo(-length * 0.10, armor);
        p.lineTo(-length * 0.52, width * 0.38);
        p.closePath();
        return p;
    }

    private static Path2D monolith(double length, double width, Random r) {
        double bevel = Math.min(length, width) * (0.12 + r.nextDouble() * 0.06);
        Path2D p = new Path2D.Double();
        p.moveTo(-length * 0.66 + bevel, -width * 0.72);
        p.lineTo(length * 0.64 - bevel, -width * 0.72);
        p.lineTo(length * 0.64, -width * 0.72 + bevel);
        p.lineTo(length * 0.64, width * 0.72 - bevel);
        p.lineTo(length * 0.64 - bevel, width * 0.72);
        p.lineTo(-length * 0.66 + bevel, width * 0.72);
        p.lineTo(-length * 0.66, width * 0.72 - bevel);
        p.lineTo(-length * 0.66, -width * 0.72 + bevel);
        p.closePath();
        return p;
    }

    private static void drawDetails(Graphics2D g, ShipType type, VisualFamily family, Color color, Random r) {
        double s = type.size.scale;
        Color glow = new Color(220, 245, 255, 150);
        Color dim = new Color(color.getRed(), color.getGreen(), color.getBlue(), 95);
        g.setStroke(new BasicStroke((float)Math.max(1, s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawCenterSpine(g, s, dim);
        switch (family) {
            case SCOUT -> {
                drawCockpit(g, -12 * s, 0, 15 * s, 7 * s, glow);
                drawSymLine(g, 6 * s, 10 * s, 36 * s, 19 * s, dim);
                drawEngine(g, 25 * s, 0, 12 * s, color);
            }
            case MINER -> {
                drawSymLine(g, -20 * s, 10 * s, -38 * s, 22 * s, glow);
                drawSymOval(g, -33 * s, 13 * s, 9 * s, 7 * s, new Color(255, 210, 120, 145));
                drawPodRow(g, -2 * s, 3, 16 * s, 9 * s, dim);
                drawEngine(g, 24 * s, 0, 16 * s, color);
            }
            case GAS -> {
                drawSymOval(g, -15 * s, 15 * s, 14 * s, 8 * s, new Color(120, 245, 220, 130));
                drawSymLine(g, -28 * s, 21 * s, 28 * s, 27 * s, dim);
                drawCockpit(g, -7 * s, 0, 18 * s, 8 * s, glow);
                drawEngine(g, 26 * s, 0, 14 * s, color);
            }
            case CARGO -> {
                int pods = 3 + r.nextInt(3);
                drawPodRow(g, -22 * s, pods, 15 * s, 11 * s, new Color(210, 220, 230, 95));
                drawSymLine(g, -32 * s, 13 * s, 34 * s, 13 * s, dim);
                drawEngine(g, 31 * s, 0, 18 * s, color);
            }
            case BUILDER -> {
                drawSymLine(g, -18 * s, 18 * s, 24 * s, 28 * s, new Color(255, 220, 125, 130));
                drawCockpit(g, -8 * s, 0, 18 * s, 9 * s, glow);
                drawPodRow(g, 3 * s, 2, 18 * s, 13 * s, dim);
                drawEngine(g, 30 * s, 0, 17 * s, color);
            }
            case COMBAT -> {
                drawTurrets(g, s, Math.max(1, Math.min(4, 1 + (int)(type.maxHp / 350))), glow);
                drawSymLine(g, -12 * s, 14 * s, 28 * s, 21 * s, dim);
                drawEngine(g, 29 * s, 0, 15 * s, color);
            }
            case CARRIER -> {
                drawHangar(g, -10 * s, -18 * s, 44 * s, 11 * s, glow);
                drawHangar(g, -10 * s, 7 * s, 44 * s, 11 * s, glow);
                drawTurrets(g, s, 3, new Color(255, 240, 180, 140));
                drawEngine(g, 40 * s, 0, 22 * s, color);
            }
            case SIEGE -> {
                drawTurrets(g, s, 5, new Color(255, 225, 155, 150));
                g.setColor(new Color(255, 235, 170, 145));
                g.drawLine((int)(-43 * s), 0, (int)(-12 * s), 0);
                drawSymLine(g, 6 * s, 18 * s, 38 * s, 25 * s, dim);
                drawEngine(g, 45 * s, 0, 20 * s, color);
            }
            case MONOLITH -> {
                drawHangar(g, -36 * s, -24 * s, 72 * s, 12 * s, new Color(170, 235, 255, 120));
                drawHangar(g, -36 * s, 12 * s, 72 * s, 12 * s, new Color(170, 235, 255, 120));
                drawPodRow(g, -40 * s, 5, 21 * s, 15 * s, dim);
                drawEngine(g, 64 * s, 0, 30 * s, color);
            }
        }
    }

    private static void drawCenterSpine(Graphics2D g, double s, Color color) {
        g.setColor(color);
        g.drawLine((int)(-25 * s), 0, (int)(28 * s), 0);
    }

    private static void drawCockpit(Graphics2D g, double x, double y, double w, double h, Color color) {
        g.setColor(color);
        g.fillOval((int)(x - w / 2), (int)(y - h / 2), (int)w, (int)h);
    }

    private static void drawHangar(Graphics2D g, double x, double y, double w, double h, Color color) {
        g.setColor(new Color(0, 0, 0, 125));
        g.fillRoundRect((int)x, (int)y, (int)w, (int)h, 4, 4);
        g.setColor(color);
        g.drawRoundRect((int)x, (int)y, (int)w, (int)h, 4, 4);
    }

    private static void drawTurrets(Graphics2D g, double s, int count, Color color) {
        g.setColor(color);
        double start = -18 * s;
        for (int i = 0; i < count; i++) {
            double x = start + i * 14 * s;
            g.fillOval((int)(x - 4 * s), (int)(-4 * s), (int)(8 * s), (int)(8 * s));
            g.drawLine((int)x, 0, (int)(x - 12 * s), 0);
        }
    }

    private static void drawPodRow(Graphics2D g, double startX, int count, double gap, double size, Color color) {
        g.setColor(color);
        for (int i = 0; i < count; i++) {
            int x = (int)(startX + i * gap - size / 2);
            int y = (int)(-size / 2);
            g.fillRoundRect(x, y, (int)size, (int)size, 4, 4);
        }
    }

    private static void drawSymLine(Graphics2D g, double x1, double y1, double x2, double y2, Color color) {
        g.setColor(color);
        g.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        g.drawLine((int)x1, (int)-y1, (int)x2, (int)-y2);
    }

    private static void drawSymOval(Graphics2D g, double x, double y, double w, double h, Color color) {
        g.setColor(color);
        g.fillOval((int)(x - w / 2), (int)(y - h / 2), (int)w, (int)h);
        g.fillOval((int)(x - w / 2), (int)(-y - h / 2), (int)w, (int)h);
    }

    private static void drawEngine(Graphics2D g, double x, double y, double size, Color color) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 145));
        g.fillOval((int)(x - size / 2), (int)(y - size / 4), (int)size, (int)(size / 2));
        g.setColor(new Color(120, 220, 255, 150));
        g.fillOval((int)(x - size / 4), (int)(y - size / 6), (int)(size / 2), (int)(size / 3));
    }

    private enum VisualFamily { SCOUT, MINER, GAS, CARGO, BUILDER, COMBAT, CARRIER, SIEGE, MONOLITH }
}
