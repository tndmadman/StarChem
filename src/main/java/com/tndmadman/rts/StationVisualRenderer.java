package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Presentation-only structural modules for station types configured in the rich visual catalog. */
final class StationVisualRenderer {
    private StationVisualRenderer() { }

    static boolean drawCore(Graphics2D g2, Base base, Color playerColor) {
        StationVisualDefinition visual = VisualDefinitionCatalog.station(base.typeId);
        if (visual == null) return false;

        Graphics2D s = (Graphics2D)g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        s.translate(base.x, base.y);
        Color accent = new Color(visual.accentRgb());
        double scale = visual.scale();

        switch (visual.preset()) {
            case OUTPOST -> drawOutpost(s, visual, playerColor, accent, scale);
            case SHIPYARD -> drawShipyard(s, visual, playerColor, accent, scale);
            case LABORATORY -> drawLaboratory(s, visual, playerColor, accent, scale);
            case FACTORY -> drawFactory(s, visual, playerColor, accent, scale);
        }
        s.dispose();
        return true;
    }

    private static void drawOutpost(Graphics2D g, StationVisualDefinition v, Color player, Color accent, double s) {
        double core = 23 * s;
        g.setColor(new Color(8, 17, 27, 235));
        g.fill(new Ellipse2D.Double(-core, -core, core * 2, core * 2));
        g.setColor(alpha(accent, 210));
        g.setStroke(new BasicStroke((float)(2.0 * s)));
        g.draw(new Ellipse2D.Double(-core, -core, core * 2, core * 2));

        int arms = Math.max(1, v.armCount());
        for (int i = 0; i < arms; i++) {
            double angle = i * Math.PI * 2 / arms + Math.PI / 6;
            Graphics2D arm = (Graphics2D)g.create();
            arm.rotate(angle);
            arm.setColor(new Color(48, 63, 78));
            arm.fillRoundRect((int)(core * .72), (int)(-4 * s), (int)(42 * s), (int)(8 * s), (int)(4 * s), (int)(4 * s));
            arm.setColor(alpha(player, 205));
            arm.drawRoundRect((int)(core * .72), (int)(-4 * s), (int)(42 * s), (int)(8 * s), (int)(4 * s), (int)(4 * s));
            arm.fill(new Ellipse2D.Double(55 * s, -7 * s, 14 * s, 14 * s));
            arm.dispose();
        }
        drawRings(g, v.ringCount(), 30 * s, accent, s);
        drawLights(g, v.lightCount(), 31 * s, accent, s);
    }

    private static void drawShipyard(Graphics2D g, StationVisualDefinition v, Color player, Color accent, double s) {
        double halfLength = 72 * s;
        double lane = 24 * s;
        double gantryWidth = 12 * s;

        g.setColor(new Color(7, 14, 23, 245));
        g.fill(new Rectangle2D.Double(-halfLength * .42, -15 * s, halfLength * .84, 30 * s));
        g.setColor(alpha(accent, 210));
        g.setStroke(new BasicStroke((float)(2.2 * s)));
        g.draw(new Rectangle2D.Double(-halfLength * .42, -15 * s, halfLength * .84, 30 * s));

        for (int side : new int[]{-1, 1}) {
            double y = side * lane;
            g.setColor(new Color(46, 60, 74));
            g.fill(new Rectangle2D.Double(-halfLength, y - gantryWidth * .5, halfLength * 2, gantryWidth));
            g.setColor(alpha(player, 205));
            g.draw(new Rectangle2D.Double(-halfLength, y - gantryWidth * .5, halfLength * 2, gantryWidth));
            for (int i = -2; i <= 2; i++) {
                double x = i * halfLength * .32;
                g.drawLine((int)x, (int)(y - gantryWidth * .7), (int)x, (int)(y + gantryWidth * .7));
            }
        }

        g.setColor(new Color(2, 6, 10, 235));
        g.fill(new Rectangle2D.Double(-halfLength * .54, -lane * .55, halfLength * 1.08, lane * 1.10));
        g.setColor(alpha(accent, 175));
        g.draw(new Rectangle2D.Double(-halfLength * .54, -lane * .55, halfLength * 1.08, lane * 1.10));

        int modules = Math.max(2, v.moduleCount());
        for (int i = 0; i < modules; i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            double x = -halfLength * .55 + (i / 2) * 22 * s;
            double y = side * (lane + 12 * s);
            g.setColor(new Color(62, 73, 84));
            g.fillRoundRect((int)(x - 8 * s), (int)(y - 5 * s), (int)(16 * s), (int)(10 * s), 4, 4);
            g.setColor(alpha(accent, 180));
            g.drawRoundRect((int)(x - 8 * s), (int)(y - 5 * s), (int)(16 * s), (int)(10 * s), 4, 4);
        }
        drawRings(g, v.ringCount(), 20 * s, accent, s);
        drawLights(g, v.lightCount(), 61 * s, accent, s);
    }

    private static void drawLaboratory(Graphics2D g, StationVisualDefinition v, Color player, Color accent, double s) {
        double core = 18 * s;
        g.setColor(new Color(7, 21, 28, 240));
        g.fill(new Ellipse2D.Double(-core, -core, core * 2, core * 2));
        g.setColor(alpha(accent, 220));
        g.setStroke(new BasicStroke((float)(1.8 * s)));
        g.draw(new Ellipse2D.Double(-core, -core, core * 2, core * 2));

        // The lab deliberately extends beyond the common station footprint: a broad instrument ring,
        // radial experiment pods, and a long sensor mast make its silhouette readable at a glance.
        drawRings(g, Math.max(2, v.ringCount()), 39 * s, accent, s);
        int modules = Math.max(3, v.moduleCount());
        for (int i = 0; i < modules; i++) {
            double angle = i * Math.PI * 2 / modules + .35;
            double r = 68 * s;
            double x = Math.cos(angle) * r;
            double y = Math.sin(angle) * r;
            g.setColor(new Color(35, 63, 74));
            g.fill(new Ellipse2D.Double(x - 9 * s, y - 7 * s, 18 * s, 14 * s));
            g.setColor(alpha(player, 190));
            g.draw(new Ellipse2D.Double(x - 9 * s, y - 7 * s, 18 * s, 14 * s));
            g.setStroke(new BasicStroke((float)Math.max(1.2, 1.7 * s)));
            g.drawLine((int)(Math.cos(angle) * core), (int)(Math.sin(angle) * core), (int)x, (int)y);
        }

        Graphics2D dish = (Graphics2D)g.create();
        dish.rotate(-.55);
        dish.setColor(new Color(91, 122, 139));
        dish.fill(new Arc2D.Double(-15 * s, -78 * s, 30 * s, 18 * s, 180, 180, Arc2D.PIE));
        dish.setColor(alpha(accent, 230));
        dish.draw(new Arc2D.Double(-15 * s, -78 * s, 30 * s, 18 * s, 180, 180, Arc2D.OPEN));
        dish.setStroke(new BasicStroke((float)Math.max(1.2, 1.6 * s)));
        dish.drawLine(0, (int)(-64 * s), 0, (int)(-34 * s));
        dish.fill(new Ellipse2D.Double(-3 * s, -69 * s, 6 * s, 6 * s));
        dish.dispose();
        drawLights(g, v.lightCount(), 60 * s, accent, s);
    }

    private static void drawFactory(Graphics2D g, StationVisualDefinition v, Color player, Color accent, double s) {
        double w = 58 * s;
        double h = 40 * s;
        g.setColor(new Color(18, 19, 22, 245));
        g.fill(new Rectangle2D.Double(-w * .5, -h * .5, w, h));
        g.setColor(alpha(accent, 210));
        g.setStroke(new BasicStroke((float)(2.0 * s)));
        g.draw(new Rectangle2D.Double(-w * .5, -h * .5, w, h));

        int modules = Math.max(4, v.moduleCount());
        for (int i = 0; i < modules; i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            double x = -w * .44 + (i / 2) * 16 * s;
            double y = side * (h * .55 + (i % 3) * 3 * s);
            g.setColor(new Color(65, 61, 57));
            g.fill(new Rectangle2D.Double(x - 6 * s, y - 7 * s, 12 * s, 14 * s));
            g.setColor(alpha(player, 165));
            g.draw(new Rectangle2D.Double(x - 6 * s, y - 7 * s, 12 * s, 14 * s));
        }

        // Long service pylons and refinery stacks break the inherited hex outline and make the
        // manufacturing plant read as an industrial complex rather than another generic station.
        for (int i = 0; i < Math.max(2, v.armCount()); i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            double y = side * (h * .5 + 32 * s);
            double x0 = (i / 2 - .5) * 30 * s;
            g.setColor(new Color(78, 71, 62));
            g.fill(new Rectangle2D.Double(x0 - 5 * s, Math.min(0, y), 10 * s, Math.abs(y)));
            g.setColor(alpha(accent, 150));
            g.draw(new Rectangle2D.Double(x0 - 5 * s, Math.min(0, y), 10 * s, Math.abs(y)));
        }

        for (int side : new int[]{-1, 1}) {
            double x = side * 34 * s;
            double y = -70 * s;
            g.setColor(new Color(58, 54, 50));
            g.fill(new Rectangle2D.Double(x - 6 * s, y, 12 * s, 42 * s));
            g.setColor(alpha(player, 150));
            g.draw(new Rectangle2D.Double(x - 6 * s, y, 12 * s, 42 * s));
            g.setColor(alpha(accent, 210));
            g.fill(new Ellipse2D.Double(x - 8 * s, y - 4 * s, 16 * s, 8 * s));
        }

        Path2D conveyor = new Path2D.Double();
        conveyor.moveTo(-78 * s, 8 * s);
        conveyor.lineTo(-38 * s, 8 * s);
        conveyor.lineTo(-24 * s, 15 * s);
        conveyor.lineTo(24 * s, 15 * s);
        conveyor.lineTo(38 * s, 8 * s);
        conveyor.lineTo(78 * s, 8 * s);
        g.setColor(new Color(84, 76, 64));
        g.setStroke(new BasicStroke((float)(5 * s)));
        g.draw(conveyor);
        g.setColor(alpha(accent, 190));
        g.setStroke(new BasicStroke((float)(1.1 * s)));
        g.draw(conveyor);
        drawLights(g, v.lightCount(), 58 * s, accent, s);
    }

    private static void drawRings(Graphics2D g, int count, double baseRadius, Color accent, double s) {
        if (count <= 0) return;
        g.setStroke(new BasicStroke((float)Math.max(.9, 1.2 * s)));
        for (int i = 0; i < count; i++) {
            double r = baseRadius + i * 9 * s;
            g.setColor(alpha(accent, Math.max(70, 155 - i * 25)));
            g.draw(new Ellipse2D.Double(-r, -r * .58, r * 2, r * 1.16));
        }
    }

    private static void drawLights(Graphics2D g, int count, double radius, Color accent, double s) {
        if (count <= 0) return;
        double dot = Math.max(2.2, 2.8 * s);
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            double x = Math.cos(angle) * radius;
            double y = Math.sin(angle) * radius * .72;
            g.setColor(alpha(accent, 55));
            g.fill(new Ellipse2D.Double(x - dot * 1.8, y - dot * 1.8, dot * 3.6, dot * 3.6));
            g.setColor(alpha(accent, 230));
            g.fill(new Ellipse2D.Double(x - dot * .5, y - dot * .5, dot, dot));
        }
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
