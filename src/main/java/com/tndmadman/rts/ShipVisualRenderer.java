package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Data-directed vector ship rendering with ShipShape as the compatibility fallback. */
final class ShipVisualRenderer {
    private ShipVisualRenderer() { }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        ShipVisualDefinition visual = VisualDefinitionCatalog.ship(type.id);
        if (visual == null) {
            ShipShape.draw(g2, type, playerColor);
            return;
        }

        double s = type.size.scale;
        double length = 40.0 * s * visual.lengthScale();
        double width = 20.0 * s * visual.widthScale();
        Path2D hull = hull(visual, length, width);

        Graphics2D ship = (Graphics2D) g2.create();
        ship.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ship.setStroke(new BasicStroke((float)Math.max(1.25, s * 1.3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Color accent = new Color(visual.accentRgb());
        Color glow = new Color(visual.glowRgb());
        Color hullFill = blend(new Color(9, 14, 22), playerColor.darker(), 0.34);
        Color panel = blend(hullFill, accent, 0.24);

        drawEngineGlow(ship, visual, length, width, glow, s);
        ship.setColor(hullFill);
        ship.fill(hull);
        ship.setColor(blend(playerColor, accent, 0.28));
        ship.draw(hull);

        drawArmorSections(ship, visual, length, width, panel, accent, s);
        drawPods(ship, visual, length, width, hullFill, accent, s);
        drawHardpoints(ship, visual, length, width, playerColor, s);
        drawHangars(ship, visual, length, width, glow, s);
        drawAntennae(ship, visual, length, width, accent, s);
        drawMarking(ship, visual, length, width, accent, s);
        ship.dispose();
    }

    private static Path2D hull(ShipVisualDefinition visual, double length, double width) {
        double asym = visual.asymmetry();
        return switch (visual.preset()) {
            case MINING_CLAW -> miningClaw(length, width, asym);
            case DEPLOYER -> deployer(length, width, asym);
            case CARGO_FRAME -> cargoFrame(length, width, asym);
            case INDUSTRIAL -> industrial(length, width, asym);
            case NEEDLE -> needle(length, width, asym);
            case WEDGE -> wedge(length, width, asym);
            case SPINE -> spine(length, width, asym);
            case BATTLESHIP -> battleship(length, width, asym);
            case FLIGHT_DECK -> flightDeck(length, width, asym);
            case DREAD_SLAB -> dreadSlab(length, width, asym);
            case TITAN_SPINE -> titanSpine(length, width, asym);
        };
    }

    private static Path2D miningClaw(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .72, -w * .52);
        p.lineTo(-l * .45, -w * .92);
        p.lineTo(-l * .12, -w * .62);
        p.lineTo(l * .20, -w * (.72 + a));
        p.lineTo(l * .58, -w * .36);
        p.lineTo(l * .68, 0);
        p.lineTo(l * .58, w * .36);
        p.lineTo(l * .20, w * (.72 - a));
        p.lineTo(-l * .12, w * .62);
        p.lineTo(-l * .45, w * .92);
        p.lineTo(-l * .72, w * .52);
        p.lineTo(-l * .50, w * .20);
        p.lineTo(-l * .28, 0);
        p.lineTo(-l * .50, -w * .20);
        p.closePath();
        return p;
    }

    private static Path2D deployer(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .70, 0);
        p.lineTo(-l * .36, -w * .52);
        p.lineTo(-l * .05, -w * .70);
        p.lineTo(l * .10, -w * (1.0 + a));
        p.lineTo(l * .48, -w * .84);
        p.lineTo(l * .66, -w * .38);
        p.lineTo(l * .66, w * .38);
        p.lineTo(l * .48, w * .84);
        p.lineTo(l * .10, w * (1.0 - a));
        p.lineTo(-l * .05, w * .70);
        p.lineTo(-l * .36, w * .52);
        p.closePath();
        return p;
    }

    private static Path2D cargoFrame(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .70, 0);
        p.lineTo(-l * .48, -w * .44);
        p.lineTo(-l * .18, -w * .54);
        p.lineTo(-l * .02, -w * (.86 + a));
        p.lineTo(l * .44, -w * .86);
        p.lineTo(l * .64, -w * .44);
        p.lineTo(l * .64, w * .44);
        p.lineTo(l * .44, w * .86);
        p.lineTo(-l * .02, w * (.86 - a));
        p.lineTo(-l * .18, w * .54);
        p.lineTo(-l * .48, w * .44);
        p.closePath();
        return p;
    }

    private static Path2D industrial(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .68, -w * .18);
        p.lineTo(-l * .42, -w * .68);
        p.lineTo(l * .04, -w * (.78 + a));
        p.lineTo(l * .22, -w * 1.04);
        p.lineTo(l * .58, -w * .76);
        p.lineTo(l * .66, -w * .22);
        p.lineTo(l * .60, w * .56);
        p.lineTo(l * .24, w * (.76 - a));
        p.lineTo(-l * .12, w * .60);
        p.lineTo(-l * .46, w * .42);
        p.closePath();
        return p;
    }

    private static Path2D needle(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .82, 0);
        p.lineTo(-l * .24, -w * .26);
        p.lineTo(l * .02, -w * (.90 + a));
        p.lineTo(l * .30, -w * .34);
        p.lineTo(l * .62, -w * .18);
        p.lineTo(l * .52, 0);
        p.lineTo(l * .62, w * .18);
        p.lineTo(l * .30, w * .34);
        p.lineTo(l * .02, w * (.90 - a));
        p.lineTo(-l * .24, w * .26);
        p.closePath();
        return p;
    }

    private static Path2D wedge(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .76, 0);
        p.lineTo(l * .46, -w * (1.0 + a));
        p.lineTo(l * .64, -w * .44);
        p.lineTo(l * .55, 0);
        p.lineTo(l * .64, w * .44);
        p.lineTo(l * .46, w * (1.0 - a));
        p.closePath();
        return p;
    }

    private static Path2D spine(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .76, 0);
        p.lineTo(-l * .34, -w * .34);
        p.lineTo(-l * .08, -w * (.90 + a));
        p.lineTo(l * .12, -w * .58);
        p.lineTo(l * .50, -w * .72);
        p.lineTo(l * .66, -w * .28);
        p.lineTo(l * .60, 0);
        p.lineTo(l * .66, w * .28);
        p.lineTo(l * .50, w * .72);
        p.lineTo(l * .12, w * .58);
        p.lineTo(-l * .08, w * (.90 - a));
        p.lineTo(-l * .34, w * .34);
        p.closePath();
        return p;
    }

    private static Path2D battleship(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .74, 0);
        p.lineTo(-l * .52, -w * .42);
        p.lineTo(-l * .20, -w * .62);
        p.lineTo(-l * .04, -w * (1.0 + a));
        p.lineTo(l * .40, -w * .88);
        p.lineTo(l * .64, -w * .50);
        p.lineTo(l * .64, w * .50);
        p.lineTo(l * .40, w * .88);
        p.lineTo(-l * .04, w * (1.0 - a));
        p.lineTo(-l * .20, w * .62);
        p.lineTo(-l * .52, w * .42);
        p.closePath();
        return p;
    }

    private static Path2D flightDeck(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .70, -w * .20);
        p.lineTo(-l * .52, -w * .58);
        p.lineTo(l * .44, -w * (1.0 + a));
        p.lineTo(l * .66, -w * .72);
        p.lineTo(l * .66, -w * .16);
        p.lineTo(l * .48, 0);
        p.lineTo(l * .66, w * .16);
        p.lineTo(l * .66, w * .72);
        p.lineTo(l * .44, w * (1.0 - a));
        p.lineTo(-l * .52, w * .58);
        p.lineTo(-l * .70, w * .20);
        p.closePath();
        return p;
    }

    private static Path2D dreadSlab(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .76, -w * .18);
        p.lineTo(-l * .58, -w * .58);
        p.lineTo(-l * .20, -w * .78);
        p.lineTo(l * .30, -w * (1.0 + a));
        p.lineTo(l * .66, -w * .68);
        p.lineTo(l * .68, w * .68);
        p.lineTo(l * .30, w * (1.0 - a));
        p.lineTo(-l * .20, w * .78);
        p.lineTo(-l * .58, w * .58);
        p.lineTo(-l * .76, w * .18);
        p.closePath();
        return p;
    }

    private static Path2D titanSpine(double l, double w, double a) {
        Path2D p = new Path2D.Double();
        p.moveTo(-l * .86, 0);
        p.lineTo(-l * .52, -w * .28);
        p.lineTo(-l * .28, -w * .62);
        p.lineTo(l * .06, -w * (.82 + a));
        p.lineTo(l * .22, -w * 1.08);
        p.lineTo(l * .58, -w * .72);
        p.lineTo(l * .70, -w * .26);
        p.lineTo(l * .64, 0);
        p.lineTo(l * .70, w * .26);
        p.lineTo(l * .58, w * .72);
        p.lineTo(l * .22, w * 1.08);
        p.lineTo(l * .06, w * (.82 - a));
        p.lineTo(-l * .28, w * .62);
        p.lineTo(-l * .52, w * .28);
        p.closePath();
        return p;
    }

    private static void drawEngineGlow(Graphics2D g, ShipVisualDefinition v, double l, double w, Color glow, double s) {
        int count = v.engineCount();
        double span = Math.min(w * 1.35, Math.max(0, count - 1) * w * .34);
        for (int i = 0; i < count; i++) {
            double y = count == 1 ? 0 : -span * .5 + span * i / (count - 1.0);
            double r = Math.max(2.3, 2.8 * s);
            g.setColor(alpha(glow, 52));
            g.fill(new Ellipse2D.Double(l * .55, y - r * 1.7, r * 4.0, r * 3.4));
            g.setColor(alpha(glow, 205));
            g.fill(new Ellipse2D.Double(l * .55, y - r * .72, r * 1.8, r * 1.44));
        }
    }

    private static void drawArmorSections(Graphics2D g, ShipVisualDefinition v, double l, double w, Color panel, Color accent, double s) {
        if (v.armorSections() <= 0) return;
        g.setStroke(new BasicStroke((float)Math.max(.8, s * .72f)));
        for (int i = 0; i < v.armorSections(); i++) {
            double f = (i + 1.0) / (v.armorSections() + 1.0);
            double x = -l * .45 + f * l * .86;
            double half = w * (.22 + .34 * Math.sin(Math.PI * f));
            g.setColor(alpha(panel, 120));
            g.fill(new Rectangle2D.Double(x - 1.4 * s, -half, 2.8 * s, half * 2));
            g.setColor(alpha(accent, 115));
            g.drawLine((int)Math.round(x), (int)Math.round(-half), (int)Math.round(x), (int)Math.round(half));
        }
    }

    private static void drawPods(Graphics2D g, ShipVisualDefinition v, double l, double w, Color hull, Color accent, double s) {
        int count = v.podCount();
        for (int i = 0; i < count; i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            int row = i / 2;
            double x = -l * .08 + (row % 4) * l * .16;
            double y = side * w * (.78 + .08 * (row % 2));
            double pw = Math.max(5, l * .14);
            double ph = Math.max(3.5, 4.2 * s);
            g.setColor(hull.brighter());
            g.fillRoundRect((int)(x - pw * .5), (int)(y - ph * .5), (int)pw, (int)ph, (int)Math.max(2, s * 2), (int)Math.max(2, s * 2));
            g.setColor(alpha(accent, 185));
            g.drawRoundRect((int)(x - pw * .5), (int)(y - ph * .5), (int)pw, (int)ph, (int)Math.max(2, s * 2), (int)Math.max(2, s * 2));
        }
    }

    private static void drawHardpoints(Graphics2D g, ShipVisualDefinition v, double l, double w, Color player, double s) {
        int count = v.hardpointCount();
        double r = Math.max(1.8, 2.1 * s);
        for (int i = 0; i < count; i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            int row = i / 2;
            double x = -l * .35 + (row % 6) * l * .13;
            double y = side * w * (.34 + .08 * (row % 3));
            g.setColor(new Color(8, 12, 18));
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            g.setColor(player.brighter());
            g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
    }

    private static void drawHangars(Graphics2D g, ShipVisualDefinition v, double l, double w, Color glow, double s) {
        for (int i = 0; i < v.hangarCount(); i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            double x = -l * .02 + (i / 2) * l * .13;
            double y = side * w * .52;
            double hw = Math.max(7, l * .17);
            double hh = Math.max(2.5, 3.2 * s);
            g.setColor(new Color(2, 6, 10, 230));
            g.fill(new Rectangle2D.Double(x - hw * .5, y - hh * .5, hw, hh));
            g.setColor(alpha(glow, 185));
            g.draw(new Rectangle2D.Double(x - hw * .5, y - hh * .5, hw, hh));
        }
    }

    private static void drawAntennae(Graphics2D g, ShipVisualDefinition v, double l, double w, Color accent, double s) {
        if (v.antennaCount() <= 0) return;
        g.setStroke(new BasicStroke((float)Math.max(.7, s * .62), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < v.antennaCount(); i++) {
            int side = (i & 1) == 0 ? -1 : 1;
            double x = -l * .22 + (i / 2) * l * .10;
            double y0 = side * w * .44;
            double y1 = side * w * (.82 + .09 * (i % 3));
            g.setColor(alpha(accent, 190));
            g.drawLine((int)x, (int)y0, (int)(x - l * .05), (int)y1);
        }
    }

    private static void drawMarking(Graphics2D g, ShipVisualDefinition v, double l, double w, Color accent, double s) {
        g.setColor(alpha(accent, 205));
        g.setStroke(new BasicStroke((float)Math.max(1.0, s * 1.2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (v.marking()) {
            case NONE -> { }
            case STRIPE -> g.drawLine((int)(-l * .34), 0, (int)(l * .30), 0);
            case CHEVRON -> {
                g.drawLine((int)(-l * .26), (int)(-w * .26), (int)(-l * .42), 0);
                g.drawLine((int)(-l * .42), 0, (int)(-l * .26), (int)(w * .26));
            }
            case INDUSTRIAL -> {
                for (int i = 0; i < 4; i++) {
                    double x = -l * .18 + i * l * .09;
                    g.drawLine((int)x, (int)(-w * .23), (int)(x + l * .05), (int)(w * .23));
                }
            }
            case COMMAND -> {
                g.drawLine((int)(-l * .24), (int)(-w * .22), (int)(l * .18), (int)(-w * .22));
                g.drawLine((int)(-l * .24), (int)(w * .22), (int)(l * .18), (int)(w * .22));
            }
        }
    }

    private static Color blend(Color a, Color b, double t) {
        double u = Math.max(0, Math.min(1, t));
        return new Color(
                (int)Math.round(a.getRed() * (1 - u) + b.getRed() * u),
                (int)Math.round(a.getGreen() * (1 - u) + b.getGreen() * u),
                (int)Math.round(a.getBlue() * (1 - u) + b.getBlue() * u));
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
