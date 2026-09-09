package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;

/** Renders deterministic authored ship silhouettes plus bounded cosmetic variation. */
final class ShipShape {
    private ShipShape() { }

    static Path2D create(ShipType type) {
        ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
        double scale = type == null ? 1.0 : type.size.scale;
        return visual.createHull(scale);
    }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        if (g2 == null || type == null || playerColor == null) return;
        ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
        Path2D hull = visual.createHull(type.size.scale);
        double s = type.size.scale;

        Graphics2D ship = (Graphics2D)g2.create();
        ship.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ship.setStroke(new BasicStroke((float)Math.max(1.3, s * 1.35),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        ship.setColor(playerColor.darker().darker());
        ship.fill(hull);
        ship.setColor(playerColor);
        ship.draw(hull);

        if (!visual.hasFeature(ShipVisualDefinition.Feature.ANONYMOUS_CONTACT)) {
            Color dim = new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 95);
            ship.setStroke(new BasicStroke((float)Math.max(1, s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawStructuralSpine(ship, hull, s, dim);
            for (ShipVisualDefinition.Mount mount : visual.mounts()) {
                drawMount(ship, mount, s, playerColor);
            }
            drawSurfaceVariation(ship, hull, visual, type.seed, playerColor);
        } else {
            drawAnonymousContact(ship, hull, s, playerColor);
        }
        ship.dispose();
    }

    private static void drawMount(Graphics2D g, ShipVisualDefinition.Mount mount, double s, Color color) {
        double x = mount.x() * s;
        double y = mount.y() * s;
        double w = mount.width() * s;
        double h = mount.height() * s;
        switch (mount.kind()) {
            case ENGINE -> drawEngine(g, x, y, w, h, color);
            case HARDPOINT -> drawHardpoint(g, x, y, w, h);
            case HANGAR -> drawHangar(g, x, y, w, h);
            case CARGO_POD -> drawCargoPod(g, x, y, w, h, color);
            case MINING_HEAD -> drawMiningHead(g, x, y, w, h);
            case GAS_TANK -> drawGasTank(g, x, y, w, h);
            case CONSTRUCTION_ARM -> drawConstructionArm(g, x, y, w, h);
            case SALVAGE_BOOM -> drawSalvageBoom(g, x, y, w, h);
            case SENSOR_ARRAY -> drawSensorArray(g, x, y, w, h);
            case LANCE -> drawLance(g, x, y, w, h);
            case BRIDGE -> drawBridge(g, x, y, w, h);
        }
    }

    private static void drawStructuralSpine(Graphics2D g, Path2D hull, double s, Color color) {
        Rectangle2D b = hull.getBounds2D();
        double left = Math.max(b.getMinX() + 5 * s, -18 * s);
        double right = Math.min(b.getMaxX() - 5 * s, 18 * s);
        if (right <= left) return;
        g.setColor(color);
        g.drawLine((int)Math.round(left), 0, (int)Math.round(right), 0);
    }

    private static void drawSurfaceVariation(Graphics2D g, Path2D hull, ShipVisualDefinition visual,
                                             int seed, Color color) {
        Graphics2D detail = (Graphics2D)g.create();
        detail.clip(hull);
        Rectangle2D b = hull.getBounds2D();
        Random random = new Random(seed ^ 0x5EEDBEEF);
        int count = 2 + Math.min(6, visual.complexityRank() / 2);
        detail.setColor(new Color(Math.min(255, color.getRed() + 55),
                Math.min(255, color.getGreen() + 55), Math.min(255, color.getBlue() + 55), 42));
        detail.setStroke(new BasicStroke(1f));
        for (int i = 0; i < count; i++) {
            double x = b.getMinX() + b.getWidth() * (0.16 + random.nextDouble() * 0.68);
            double y = b.getMinY() + b.getHeight() * (0.18 + random.nextDouble() * 0.64);
            double length = Math.max(3, b.getWidth() * (0.05 + random.nextDouble() * 0.08));
            if ((i & 1) == 0) {
                detail.drawLine((int)x, (int)y, (int)(x + length), (int)y);
            } else {
                detail.drawLine((int)x, (int)y, (int)x, (int)(y + length * 0.65));
            }
        }
        detail.dispose();
    }

    private static void drawAnonymousContact(Graphics2D g, Path2D hull, double s, Color color) {
        Rectangle2D b = hull.getBounds2D();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g.drawLine((int)b.getMinX(), 0, (int)b.getMaxX(), 0);
        int r = (int)Math.max(2, 2.5 * s);
        g.fillOval(-r, -r, r * 2, r * 2);
    }

    private static void drawEngine(Graphics2D g, double x, double y, double width, double height, Color color) {
        int w = (int)Math.max(4, Math.abs(width));
        int h = (int)Math.max(3, Math.abs(height));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 150));
        g.fillRoundRect((int)Math.round(x - w / 2.0), (int)Math.round(y - h / 2.0), w, h, h, h);
        g.setColor(new Color(150, 230, 255, 185));
        int coreW = Math.max(2, w / 2);
        int coreH = Math.max(2, h / 2);
        g.fillOval((int)Math.round(x - coreW / 2.0), (int)Math.round(y - coreH / 2.0), coreW, coreH);
    }

    private static void drawHardpoint(Graphics2D g, double x, double y, double diameter, double barrelLength) {
        int d = (int)Math.max(4, Math.abs(diameter));
        g.setColor(new Color(240, 235, 205, 175));
        g.fillOval((int)Math.round(x - d / 2.0), (int)Math.round(y - d / 2.0), d, d);
        g.drawLine((int)Math.round(x - Math.abs(barrelLength)), (int)Math.round(y),
                (int)Math.round(x - d / 3.0), (int)Math.round(y));
    }

    private static void drawHangar(Graphics2D g, double x, double y, double width, double height) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(4, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(new Color(2, 8, 14, 185));
        g.fillRoundRect(px, py, w, h, 4, 4);
        g.setColor(new Color(165, 225, 255, 150));
        g.drawRoundRect(px, py, w, h, 4, 4);
    }

    private static void drawCargoPod(Graphics2D g, double x, double y, double width, double height, Color color) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(4, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(new Color(205, 215, 222, 105));
        g.fillRoundRect(px, py, w, h, 3, 3);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 150));
        g.drawRoundRect(px, py, w, h, 3, 3);
    }

    private static void drawMiningHead(Graphics2D g, double x, double y, double width, double height) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(5, Math.abs(height));
        g.setColor(new Color(255, 205, 105, 170));
        g.fillOval((int)Math.round(x - w / 2.0), (int)Math.round(y - h / 2.0), w, h);
        g.drawLine((int)Math.round(x - w / 2.0), (int)Math.round(y),
                (int)Math.round(x - w), (int)Math.round(y - h / 2.0));
        g.drawLine((int)Math.round(x - w / 2.0), (int)Math.round(y),
                (int)Math.round(x - w), (int)Math.round(y + h / 2.0));
    }

    private static void drawGasTank(Graphics2D g, double x, double y, double width, double height) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(4, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(new Color(105, 225, 205, 105));
        g.fillOval(px, py, w, h);
        g.setColor(new Color(160, 250, 225, 165));
        g.drawOval(px, py, w, h);
    }

    private static void drawConstructionArm(Graphics2D g, double x, double y, double dx, double dy) {
        double ex = x + dx;
        double ey = y + dy;
        g.setColor(new Color(255, 205, 100, 165));
        g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(ex), (int)Math.round(ey));
        int r = Math.max(2, (int)Math.round(Math.hypot(dx, dy) * 0.09));
        g.fillOval((int)Math.round(ex) - r, (int)Math.round(ey) - r, r * 2, r * 2);
    }

    private static void drawSalvageBoom(Graphics2D g, double x, double y, double dx, double dy) {
        double ex = x + dx;
        double ey = y + dy;
        g.setColor(new Color(175, 230, 245, 160));
        g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(ex), (int)Math.round(ey));
        double claw = Math.max(3, Math.hypot(dx, dy) * 0.14);
        g.drawLine((int)Math.round(ex), (int)Math.round(ey),
                (int)Math.round(ex - claw), (int)Math.round(ey - claw));
        g.drawLine((int)Math.round(ex), (int)Math.round(ey),
                (int)Math.round(ex - claw), (int)Math.round(ey + claw));
    }

    private static void drawSensorArray(Graphics2D g, double x, double y, double width, double height) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(5, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(new Color(170, 235, 255, 170));
        g.drawOval(px, py, w, h);
        g.drawLine((int)Math.round(x - w / 2.0), (int)Math.round(y),
                (int)Math.round(x + w / 2.0), (int)Math.round(y));
        g.drawLine((int)Math.round(x), (int)Math.round(y - h / 2.0),
                (int)Math.round(x), (int)Math.round(y + h / 2.0));
    }

    private static void drawLance(Graphics2D g, double x, double y, double length, double thickness) {
        g.setColor(new Color(255, 225, 155, 175));
        g.setStroke(new BasicStroke((float)Math.max(1.5, Math.abs(thickness)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(x + length), (int)Math.round(y));
    }

    private static void drawBridge(Graphics2D g, double x, double y, double width, double height) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(3, Math.abs(height));
        g.setColor(new Color(210, 242, 255, 155));
        g.fillOval((int)Math.round(x - w / 2.0), (int)Math.round(y - h / 2.0), w, h);
    }
}
