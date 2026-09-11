package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;

/** Renders authored hull identities with physical materials and bounded cosmetic variation. */
final class ShipShape {
    private ShipShape() { }

    static Path2D create(ShipType type) {
        ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
        double scale = type == null ? 1.0 : type.size.scale;
        return visual.createHull(scale);
    }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        draw(g2, type, playerColor, 0);
    }

    static void draw(Graphics2D g2, ShipType type, Color playerColor, int visualVariant) {
        if (g2 == null || type == null || playerColor == null) return;
        ShipVisualDefinition visual = ShipVisualCatalog.forType(type);
        Path2D hull = visual.createHull(type.size.scale);
        double s = type.size.scale;

        Graphics2D ship = (Graphics2D)g2.create();
        ship.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ship.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        if (visual.hasFeature(ShipVisualDefinition.Feature.ANONYMOUS_CONTACT)) {
            ship.setStroke(new BasicStroke((float)Math.max(1.2, s * 1.15),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawAnonymousContact(ship, hull, s, playerColor);
            ship.dispose();
            return;
        }

        ShipVisualStyle.Style style = ShipVisualStyle.resolve(type, visual, playerColor, visualVariant);
        drawMaterialSurface(ship, hull, type, visual, style);

        ship.setStroke(new BasicStroke((float)Math.max(0.9, s),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawStructuralSpine(ship, hull, s, ShipVisualStyle.withAlpha(style.seam(), 205));
        for (ShipVisualDefinition.Mount mount : visual.mounts()) {
            drawMount(ship, mount, s, style);
        }
        drawOwnershipAccents(ship, hull, type, style);
        drawRunningLights(ship, hull, type, visual, style);

        ship.setStroke(new BasicStroke((float)Math.max(1.15, s * 1.20),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        ship.setColor(style.edge());
        ship.draw(hull);
        ship.dispose();
    }

    private static void drawMaterialSurface(Graphics2D g, Path2D hull, ShipType type,
                                            ShipVisualDefinition visual, ShipVisualStyle.Style style) {
        Rectangle2D bounds = hull.getBounds2D();
        Graphics2D surface = (Graphics2D)g.create();
        surface.clip(hull);

        surface.setColor(style.hullDark());
        surface.fill(hull);
        surface.setPaint(new GradientPaint(
                (float)bounds.getMinX(), (float)bounds.getMinY(), style.hullLight(),
                (float)bounds.getMaxX(), (float)bounds.getMaxY(), style.hullBase()));
        surface.fill(hull);

        double s = type.size.scale;
        int tier = detailTier(s, visual.complexityRank());
        double width = bounds.getWidth();
        double height = bounds.getHeight();
        Random structural = new Random(style.structuralSeed());
        Random variation = new Random(style.variationSeed());

        surface.setColor(ShipVisualStyle.withAlpha(style.armor(), 108 + tier * 10));
        int armorSections = Math.min(5, 1 + tier);
        double sectionW = width / (armorSections + 2.3);
        double sectionH = Math.max(2.6 * s, height * (0.10 + tier * 0.014));
        for (int i = 0; i < armorSections; i++) {
            double t = (i + 1.0) / (armorSections + 1.0);
            double x = bounds.getMinX() + width * (0.12 + 0.70 * t)
                    + (style.variantIndex() - 1.5) * s * 0.45;
            double y = height * (0.17 + (i & 1) * 0.09);
            int arc = Math.max(2, (int)Math.round(2.5 * s));
            surface.fillRoundRect((int)Math.round(x - sectionW / 2), (int)Math.round(-y - sectionH / 2),
                    Math.max(2, (int)Math.round(sectionW)), Math.max(2, (int)Math.round(sectionH)), arc, arc);
            surface.fillRoundRect((int)Math.round(x - sectionW / 2), (int)Math.round(y - sectionH / 2),
                    Math.max(2, (int)Math.round(sectionW)), Math.max(2, (int)Math.round(sectionH)), arc, arc);
        }

        surface.setStroke(new BasicStroke((float)Math.max(0.60, s * 0.68),
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        surface.setColor(style.seam());
        int seamCount = 2 + tier;
        for (int i = 1; i <= seamCount; i++) {
            double x = bounds.getMinX() + width * i / (seamCount + 1.0);
            double halfSpan = height * (0.20 + 0.10 * structural.nextDouble());
            double skew = (structural.nextDouble() - 0.5) * 4 * s;
            surface.drawLine((int)Math.round(x), (int)Math.round(-halfSpan),
                    (int)Math.round(x + skew), (int)Math.round(halfSpan));
        }

        if (tier >= 2) {
            surface.setStroke(new BasicStroke((float)Math.max(0.85, s * 0.95),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            surface.setColor(ShipVisualStyle.withAlpha(style.edge(), 105));
            int ribCount = Math.min(4, tier);
            for (int i = 0; i < ribCount; i++) {
                double x = bounds.getCenterX() - width * 0.18 + i * width * 0.12;
                double y = height * (0.17 + i * 0.025);
                double len = Math.max(6 * s, width * 0.11);
                surface.drawLine((int)Math.round(x - len / 2), (int)Math.round(-y),
                        (int)Math.round(x + len / 2), (int)Math.round(-y));
                surface.drawLine((int)Math.round(x - len / 2), (int)Math.round(y),
                        (int)Math.round(x + len / 2), (int)Math.round(y));
            }
        }

        drawRecessedMachinery(surface, bounds, type, style, tier);
        if (tier >= 2 || visual.hasFeature(ShipVisualDefinition.Feature.CAPITAL)
                || visual.hasFeature(ShipVisualDefinition.Feature.SIEGE_WEAPON)) {
            drawRadiators(surface, bounds, type, style, tier);
        }
        drawWarningMarkings(surface, bounds, type, style);
        drawWear(surface, bounds, type, style, variation, tier);
        drawDecal(surface, bounds, type, style);
        surface.dispose();
    }

    private static void drawRecessedMachinery(Graphics2D g, Rectangle2D bounds, ShipType type,
                                              ShipVisualStyle.Style style, int tier) {
        double s = type.size.scale;
        double w = Math.max(7 * s, bounds.getWidth() * 0.13);
        double h = Math.max(3 * s, bounds.getHeight() * 0.07);
        double x = bounds.getCenterX() - w / 2 + (style.variantIndex() - 1.5) * s * 0.55;
        g.setColor(ShipVisualStyle.withAlpha(style.recess(), 215));
        g.fillRoundRect((int)Math.round(x), (int)Math.round(-h / 2),
                Math.max(2, (int)Math.round(w)), Math.max(2, (int)Math.round(h)),
                Math.max(2, (int)Math.round(2 * s)), Math.max(2, (int)Math.round(2 * s)));
        if (tier >= 3) {
            double sideY = bounds.getHeight() * 0.26;
            double sideW = w * 0.72;
            g.fillRoundRect((int)Math.round(x - sideW * 0.08), (int)Math.round(-sideY - h / 2),
                    Math.max(2, (int)Math.round(sideW)), Math.max(2, (int)Math.round(h)), 3, 3);
            g.fillRoundRect((int)Math.round(x - sideW * 0.08), (int)Math.round(sideY - h / 2),
                    Math.max(2, (int)Math.round(sideW)), Math.max(2, (int)Math.round(h)), 3, 3);
        }
    }

    private static void drawRadiators(Graphics2D g, Rectangle2D bounds, ShipType type,
                                      ShipVisualStyle.Style style, int tier) {
        double s = type.size.scale;
        double x1 = bounds.getCenterX() - bounds.getWidth() * 0.12;
        double x2 = x1 + bounds.getWidth() * 0.22;
        double y = bounds.getHeight() * 0.32;
        g.setColor(ShipVisualStyle.withAlpha(style.radiator(), 175));
        g.setStroke(new BasicStroke((float)Math.max(0.60, 0.68 * s)));
        int fins = 3 + Math.min(3, tier);
        for (int i = 0; i < fins; i++) {
            double t = fins == 1 ? 0 : i / (double)(fins - 1);
            double x = x1 + (x2 - x1) * t;
            g.drawLine((int)Math.round(x), (int)Math.round(-y - 3 * s),
                    (int)Math.round(x), (int)Math.round(-y + 3 * s));
            g.drawLine((int)Math.round(x), (int)Math.round(y - 3 * s),
                    (int)Math.round(x), (int)Math.round(y + 3 * s));
        }
    }

    private static void drawWarningMarkings(Graphics2D g, Rectangle2D bounds, ShipType type,
                                            ShipVisualStyle.Style style) {
        double s = type.size.scale;
        double x = bounds.getMinX() + bounds.getWidth() * (0.58 + style.variantIndex() * 0.025);
        double y = bounds.getHeight() * 0.16;
        g.setColor(ShipVisualStyle.withAlpha(style.warning(), 185));
        g.setStroke(new BasicStroke((float)Math.max(0.75, s * 0.82)));
        for (int i = 0; i < 3; i++) {
            double dx = i * 3.0 * s;
            g.drawLine((int)Math.round(x + dx), (int)Math.round(-y - 2.6 * s),
                    (int)Math.round(x + dx + 3.6 * s), (int)Math.round(-y + 2.6 * s));
        }
    }

    private static void drawWear(Graphics2D g, Rectangle2D bounds, ShipType type,
                                 ShipVisualStyle.Style style, Random random, int tier) {
        double s = type.size.scale;
        int marks = Math.min(7, 1 + tier + style.variantIndex());
        for (int i = 0; i < marks; i++) {
            double x = bounds.getMinX() + bounds.getWidth() * (0.16 + random.nextDouble() * 0.68);
            double y = (random.nextDouble() - 0.5) * bounds.getHeight() * 0.56;
            double w = (4 + random.nextDouble() * 7) * s;
            double h = (1.3 + random.nextDouble() * 3.0) * s;
            int alpha = 18 + random.nextInt(26);
            g.setColor(new Color(12, 14, 16, alpha));
            g.fillOval((int)Math.round(x - w / 2), (int)Math.round(y - h / 2),
                    Math.max(1, (int)Math.round(w)), Math.max(1, (int)Math.round(h)));
        }
        if (style.variantIndex() >= 2) {
            g.setColor(ShipVisualStyle.withAlpha(style.edge(), 55));
            g.setStroke(new BasicStroke((float)Math.max(0.50, 0.55 * s)));
            double x = bounds.getCenterX() - 7 * s + style.variantIndex() * 1.8 * s;
            g.drawLine((int)Math.round(x), (int)Math.round(-4 * s),
                    (int)Math.round(x + 9 * s), (int)Math.round(2 * s));
        }
    }

    private static void drawDecal(Graphics2D g, Rectangle2D bounds, ShipType type,
                                  ShipVisualStyle.Style style) {
        double s = type.size.scale;
        double x = bounds.getMinX() + bounds.getWidth() * (0.32 + 0.04 * style.variantIndex());
        double y = bounds.getHeight() * 0.10;
        g.setColor(ShipVisualStyle.withAlpha(style.edge(), 150));
        g.setStroke(new BasicStroke((float)Math.max(0.65, 0.70 * s),
                BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        int bars = 1 + style.variantIndex();
        for (int i = 0; i < bars; i++) {
            double dx = i * 2.6 * s;
            g.drawLine((int)Math.round(x + dx), (int)Math.round(-y),
                    (int)Math.round(x + dx), (int)Math.round(-y + 3.6 * s));
        }
    }

    private static void drawMount(Graphics2D g, ShipVisualDefinition.Mount mount, double s,
                                  ShipVisualStyle.Style style) {
        double x = mount.x() * s;
        double y = mount.y() * s;
        double w = mount.width() * s;
        double h = mount.height() * s;
        switch (mount.kind()) {
            case ENGINE -> drawEngine(g, x, y, w, h, style);
            case HARDPOINT -> drawHardpoint(g, x, y, w, h, style);
            case HANGAR -> drawHangar(g, x, y, w, h, style);
            case CARGO_POD -> drawCargoPod(g, x, y, w, h, style);
            case MINING_HEAD -> drawMiningHead(g, x, y, w, h, style);
            case GAS_TANK -> drawGasTank(g, x, y, w, h, style);
            case CONSTRUCTION_ARM -> drawConstructionArm(g, x, y, w, h, style);
            case SALVAGE_BOOM -> drawSalvageBoom(g, x, y, w, h, style);
            case SENSOR_ARRAY -> drawSensorArray(g, x, y, w, h, style);
            case LANCE -> drawLance(g, x, y, w, h, style);
            case BRIDGE -> drawBridge(g, x, y, w, h, style);
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

    private static void drawOwnershipAccents(Graphics2D g, Path2D hull, ShipType type,
                                             ShipVisualStyle.Style style) {
        double s = type.size.scale;
        Rectangle2D b = hull.getBounds2D();
        Graphics2D accents = (Graphics2D)g.create();
        accents.clip(hull);
        accents.setColor(style.accent());
        accents.setStroke(new BasicStroke((float)Math.max(2.0, 1.9 * s),
                BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));

        double x1 = b.getMinX() + b.getWidth() * 0.27;
        double x2 = b.getMinX() + b.getWidth() * 0.55;
        double y = b.getHeight() * (0.15 + style.variantIndex() * 0.015);
        accents.drawLine((int)Math.round(x1), (int)Math.round(-y),
                (int)Math.round(x2), (int)Math.round(-y));
        accents.drawLine((int)Math.round(x1), (int)Math.round(y),
                (int)Math.round(x2), (int)Math.round(y));

        double panelW = Math.max(6 * s, b.getWidth() * 0.14);
        double panelH = Math.max(3 * s, b.getHeight() * 0.09);
        double panelX = b.getMinX() + b.getWidth() * (0.56 + 0.02 * style.variantIndex());
        accents.fillRoundRect((int)Math.round(panelX - panelW / 2), (int)Math.round(-panelH / 2),
                Math.max(2, (int)Math.round(panelW)), Math.max(2, (int)Math.round(panelH)), 2, 2);
        accents.dispose();
    }

    private static void drawRunningLights(Graphics2D g, Path2D hull, ShipType type,
                                          ShipVisualDefinition visual, ShipVisualStyle.Style style) {
        Rectangle2D b = hull.getBounds2D();
        double s = type.size.scale;
        int light = Math.max(2, (int)Math.round(2.2 * s));
        int x = (int)Math.round(b.getCenterX() - b.getWidth() * 0.03);
        int y = (int)Math.round(b.getHeight() * 0.36);
        g.setColor(new Color(225, 73, 66, 220));
        g.fillOval(x - light / 2, -y - light / 2, light, light);
        g.setColor(new Color(86, 220, 135, 220));
        g.fillOval(x - light / 2, y - light / 2, light, light);
        if (visual.hasFeature(ShipVisualDefinition.Feature.CAPITAL) || detailTier(s, visual.complexityRank()) >= 3) {
            g.setColor(new Color(style.window().getRed(), style.window().getGreen(), style.window().getBlue(), 205));
            int aftX = (int)Math.round(b.getMaxX() - b.getWidth() * 0.08);
            g.fillOval(aftX - light / 2, -light / 2, light, light);
        }
    }

    private static void drawAnonymousContact(Graphics2D g, Path2D hull, double s, Color color) {
        Rectangle2D b = hull.getBounds2D();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g.draw(hull);
        g.drawLine((int)Math.round(b.getMinX()), 0, (int)Math.round(b.getMaxX()), 0);
        int r = (int)Math.max(2, 2.5 * s);
        g.fillOval(-r, -r, r * 2, r * 2);
    }

    private static int detailTier(double scale, int complexityRank) {
        int sizeTier = scale < 0.90 ? 1 : scale < 1.45 ? 2 : 3;
        int complexityTier = 1 + Math.min(3, Math.max(0, complexityRank) / 3);
        return Math.min(4, Math.max(sizeTier, complexityTier));
    }

    private static void drawEngine(Graphics2D g, double x, double y, double width, double height,
                                   ShipVisualStyle.Style style) {
        int w = (int)Math.max(4, Math.abs(width));
        int h = (int)Math.max(3, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(ShipVisualStyle.withAlpha(style.recess(), 235));
        g.fillRoundRect(px, py, w, h, h, h);
        g.setColor(ShipVisualStyle.withAlpha(style.edge(), 145));
        g.drawRoundRect(px, py, w, h, h, h);
        g.setColor(style.engineGlow());
        int coreW = Math.max(2, w / 2);
        int coreH = Math.max(2, h / 2);
        g.fillOval((int)Math.round(x - coreW / 2.0), (int)Math.round(y - coreH / 2.0), coreW, coreH);
    }

    private static void drawHardpoint(Graphics2D g, double x, double y, double diameter, double barrelLength,
                                      ShipVisualStyle.Style style) {
        int d = (int)Math.max(4, Math.abs(diameter));
        g.setColor(ShipVisualStyle.withAlpha(style.armor(), 230));
        g.fillOval((int)Math.round(x - d / 2.0), (int)Math.round(y - d / 2.0), d, d);
        g.setColor(ShipVisualStyle.withAlpha(style.edge(), 215));
        g.drawOval((int)Math.round(x - d / 2.0), (int)Math.round(y - d / 2.0), d, d);
        g.drawLine((int)Math.round(x - Math.abs(barrelLength)), (int)Math.round(y),
                (int)Math.round(x - d / 3.0), (int)Math.round(y));
    }

    private static void drawHangar(Graphics2D g, double x, double y, double width, double height,
                                   ShipVisualStyle.Style style) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(4, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(ShipVisualStyle.withAlpha(style.recess(), 235));
        g.fillRoundRect(px, py, w, h, 4, 4);
        g.setColor(ShipVisualStyle.withAlpha(style.window(), 180));
        g.drawRoundRect(px, py, w, h, 4, 4);
    }

    private static void drawCargoPod(Graphics2D g, double x, double y, double width, double height,
                                     ShipVisualStyle.Style style) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(4, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(ShipVisualStyle.withAlpha(style.armor(), 205));
        g.fillRoundRect(px, py, w, h, 3, 3);
        g.setColor(ShipVisualStyle.withAlpha(style.seam(), 220));
        g.drawRoundRect(px, py, w, h, 3, 3);
    }

    private static void drawMiningHead(Graphics2D g, double x, double y, double width, double height,
                                       ShipVisualStyle.Style style) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(5, Math.abs(height));
        g.setColor(ShipVisualStyle.withAlpha(style.warning(), 190));
        g.fillOval((int)Math.round(x - w / 2.0), (int)Math.round(y - h / 2.0), w, h);
        g.setColor(ShipVisualStyle.withAlpha(style.edge(), 200));
        g.drawLine((int)Math.round(x - w / 2.0), (int)Math.round(y),
                (int)Math.round(x - w), (int)Math.round(y - h / 2.0));
        g.drawLine((int)Math.round(x - w / 2.0), (int)Math.round(y),
                (int)Math.round(x - w), (int)Math.round(y + h / 2.0));
    }

    private static void drawGasTank(Graphics2D g, double x, double y, double width, double height,
                                    ShipVisualStyle.Style style) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(4, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(ShipVisualStyle.withAlpha(style.radiator(), 165));
        g.fillOval(px, py, w, h);
        g.setColor(ShipVisualStyle.withAlpha(style.window(), 155));
        g.drawOval(px, py, w, h);
    }

    private static void drawConstructionArm(Graphics2D g, double x, double y, double dx, double dy,
                                            ShipVisualStyle.Style style) {
        double ex = x + dx;
        double ey = y + dy;
        g.setColor(ShipVisualStyle.withAlpha(style.warning(), 185));
        g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(ex), (int)Math.round(ey));
        int r = Math.max(2, (int)Math.round(Math.hypot(dx, dy) * 0.09));
        g.setColor(ShipVisualStyle.withAlpha(style.recess(), 235));
        g.fillOval((int)Math.round(ex) - r, (int)Math.round(ey) - r, r * 2, r * 2);
    }

    private static void drawSalvageBoom(Graphics2D g, double x, double y, double dx, double dy,
                                        ShipVisualStyle.Style style) {
        double ex = x + dx;
        double ey = y + dy;
        g.setColor(ShipVisualStyle.withAlpha(style.edge(), 190));
        g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(ex), (int)Math.round(ey));
        double claw = Math.max(3, Math.hypot(dx, dy) * 0.14);
        g.drawLine((int)Math.round(ex), (int)Math.round(ey),
                (int)Math.round(ex - claw), (int)Math.round(ey - claw));
        g.drawLine((int)Math.round(ex), (int)Math.round(ey),
                (int)Math.round(ex - claw), (int)Math.round(ey + claw));
    }

    private static void drawSensorArray(Graphics2D g, double x, double y, double width, double height,
                                        ShipVisualStyle.Style style) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(5, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(ShipVisualStyle.withAlpha(style.window(), 190));
        g.drawOval(px, py, w, h);
        g.drawLine((int)Math.round(x - w / 2.0), (int)Math.round(y),
                (int)Math.round(x + w / 2.0), (int)Math.round(y));
        g.drawLine((int)Math.round(x), (int)Math.round(y - h / 2.0),
                (int)Math.round(x), (int)Math.round(y + h / 2.0));
    }

    private static void drawLance(Graphics2D g, double x, double y, double length, double thickness,
                                  ShipVisualStyle.Style style) {
        g.setColor(ShipVisualStyle.withAlpha(style.warning(), 190));
        g.setStroke(new BasicStroke((float)Math.max(1.5, Math.abs(thickness)),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int)Math.round(x), (int)Math.round(y),
                (int)Math.round(x + length), (int)Math.round(y));
    }

    private static void drawBridge(Graphics2D g, double x, double y, double width, double height,
                                   ShipVisualStyle.Style style) {
        int w = (int)Math.max(5, Math.abs(width));
        int h = (int)Math.max(3, Math.abs(height));
        int px = (int)Math.round(x - w / 2.0);
        int py = (int)Math.round(y - h / 2.0);
        g.setColor(style.window());
        g.fillOval(px, py, w, h);
        g.setColor(ShipVisualStyle.withAlpha(style.edge(), 105));
        g.drawOval(px, py, w, h);
    }
}