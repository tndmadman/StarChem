package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/** Near-final authored silhouettes for the five ships selected by issue #407. */
final class ShowcaseShipRenderer {
    private ShowcaseShipRenderer() { }

    static boolean supports(ShipType type) {
        if (type == null || type.id == null) return false;
        return switch (type.id.toLowerCase()) {
            case "prospector", "frigate", "cruiser", "battleship", "titan" -> true;
            default -> false;
        };
    }

    static Path2D create(ShipType type) {
        if (!supports(type)) return null;
        double s = type.size.scale;
        return switch (type.id.toLowerCase()) {
            case "prospector" -> prospector(s);
            case "frigate" -> frigate(s);
            case "cruiser" -> cruiser(s);
            case "battleship" -> battleship(s);
            case "titan" -> titan(s);
            default -> null;
        };
    }

    static void draw(Graphics2D source, ShipType type, Color playerColor) {
        Path2D hull = create(type);
        if (source == null || hull == null || playerColor == null) return;
        double s = type.size.scale;
        Graphics2D g = (Graphics2D)source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke((float)Math.max(1.3, s * 1.35), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(playerColor.darker().darker());
        g.fill(hull);
        g.setColor(playerColor);
        g.draw(hull);
        drawDetails(g, type, playerColor, s);
        g.dispose();
    }

    private static Path2D prospector(double s) {
        Path2D p = new Path2D.Double();
        // Twin forward mining jaws make the Prospector readable even at medium zoom.
        p.moveTo(-43*s, -13*s); p.lineTo(-27*s, -8*s); p.lineTo(-16*s, -20*s);
        p.lineTo(8*s, -18*s); p.lineTo(27*s, -10*s); p.lineTo(35*s, -5*s);
        p.lineTo(27*s, 0); p.lineTo(35*s, 5*s); p.lineTo(27*s, 10*s);
        p.lineTo(8*s, 18*s); p.lineTo(-16*s, 20*s); p.lineTo(-27*s, 8*s);
        p.lineTo(-43*s, 13*s); p.lineTo(-34*s, 0); p.closePath();
        return p;
    }

    private static Path2D frigate(double s) {
        Path2D p = new Path2D.Double();
        // Fast escort: long spear nose, clipped swept wings, narrow engine block.
        p.moveTo(-47*s, 0); p.lineTo(-18*s, -8*s); p.lineTo(-3*s, -24*s);
        p.lineTo(15*s, -13*s); p.lineTo(31*s, -11*s); p.lineTo(38*s, -5*s);
        p.lineTo(30*s, 0); p.lineTo(38*s, 5*s); p.lineTo(31*s, 11*s);
        p.lineTo(15*s, 13*s); p.lineTo(-3*s, 24*s); p.lineTo(-18*s, 8*s);
        p.closePath();
        return p;
    }

    private static Path2D cruiser(double s) {
        Path2D p = new Path2D.Double();
        // Broad shoulders and a visible central keel distinguish it from the frigate.
        p.moveTo(-49*s, 0); p.lineTo(-31*s, -13*s); p.lineTo(-12*s, -16*s);
        p.lineTo(-2*s, -30*s); p.lineTo(18*s, -24*s); p.lineTo(32*s, -14*s);
        p.lineTo(42*s, -9*s); p.lineTo(35*s, 0); p.lineTo(42*s, 9*s);
        p.lineTo(32*s, 14*s); p.lineTo(18*s, 24*s); p.lineTo(-2*s, 30*s);
        p.lineTo(-12*s, 16*s); p.lineTo(-31*s, 13*s); p.closePath();
        return p;
    }

    private static Path2D battleship(double s) {
        Path2D p = new Path2D.Double();
        // Heavy stepped armor with a blunt artillery prow and paired engine shoulders.
        p.moveTo(-55*s, -8*s); p.lineTo(-44*s, -18*s); p.lineTo(-24*s, -20*s);
        p.lineTo(-13*s, -31*s); p.lineTo(12*s, -31*s); p.lineTo(23*s, -24*s);
        p.lineTo(41*s, -23*s); p.lineTo(50*s, -12*s); p.lineTo(43*s, -4*s);
        p.lineTo(49*s, 0); p.lineTo(43*s, 4*s); p.lineTo(50*s, 12*s);
        p.lineTo(41*s, 23*s); p.lineTo(23*s, 24*s); p.lineTo(12*s, 31*s);
        p.lineTo(-13*s, 31*s); p.lineTo(-24*s, 20*s); p.lineTo(-44*s, 18*s);
        p.lineTo(-55*s, 8*s); p.closePath();
        return p;
    }

    private static Path2D titan(double s) {
        Path2D p = new Path2D.Double();
        // Fortress silhouette: split prow, deep side bastions, and a massive stern block.
        p.moveTo(-62*s, -12*s); p.lineTo(-45*s, -20*s); p.lineTo(-34*s, -34*s);
        p.lineTo(-10*s, -31*s); p.lineTo(2*s, -43*s); p.lineTo(27*s, -39*s);
        p.lineTo(43*s, -28*s); p.lineTo(57*s, -25*s); p.lineTo(65*s, -13*s);
        p.lineTo(55*s, -5*s); p.lineTo(63*s, 0); p.lineTo(55*s, 5*s);
        p.lineTo(65*s, 13*s); p.lineTo(57*s, 25*s); p.lineTo(43*s, 28*s);
        p.lineTo(27*s, 39*s); p.lineTo(2*s, 43*s); p.lineTo(-10*s, 31*s);
        p.lineTo(-34*s, 34*s); p.lineTo(-45*s, 20*s); p.lineTo(-62*s, 12*s);
        p.lineTo(-49*s, 4*s); p.lineTo(-58*s, 0); p.lineTo(-49*s, -4*s);
        p.closePath();
        return p;
    }

    private static void drawDetails(Graphics2D g, ShipType type, Color color, double s) {
        Color cool = new Color(195, 238, 255, 175);
        Color dim = new Color(color.getRed(), color.getGreen(), color.getBlue(), 105);
        Color warm = new Color(255, 205, 110, 175);
        g.setStroke(new BasicStroke((float)Math.max(1.0, s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (type.id.toLowerCase()) {
            case "prospector" -> {
                symLine(g, -37*s, 12*s, -23*s, 5*s, warm);
                symOval(g, -25*s, 10*s, 8*s, 6*s, warm);
                pod(g, -4*s, -8*s, 18*s, 16*s, dim);
                cockpit(g, -15*s, 0, 13*s, 7*s, cool);
                engine(g, 27*s, 0, 13*s, color);
            }
            case "frigate" -> {
                cockpit(g, -19*s, 0, 15*s, 6*s, cool);
                symLine(g, -5*s, 9*s, 20*s, 15*s, dim);
                engine(g, 29*s, 0, 12*s, color);
            }
            case "cruiser" -> {
                cockpit(g, -22*s, 0, 18*s, 8*s, cool);
                turret(g, -8*s, 0, s, cool); turret(g, 9*s, 0, s, cool);
                symLine(g, -3*s, 16*s, 26*s, 17*s, dim);
                engine(g, 34*s, -8*s, 12*s, color); engine(g, 34*s, 8*s, 12*s, color);
            }
            case "battleship" -> {
                turret(g, -27*s, 0, 1.15*s, warm); turret(g, -8*s, 0, 1.15*s, warm);
                turret(g, 12*s, 0, 1.15*s, warm);
                symLine(g, -22*s, 17*s, 32*s, 19*s, dim);
                cockpit(g, -2*s, 0, 15*s, 7*s, cool);
                engine(g, 42*s, -11*s, 15*s, color); engine(g, 42*s, 11*s, 15*s, color);
            }
            case "titan" -> {
                turret(g, -35*s, 0, 1.35*s, warm); turret(g, -15*s, 0, 1.35*s, warm);
                turret(g, 7*s, 0, 1.35*s, warm); turret(g, 28*s, 0, 1.35*s, warm);
                symLine(g, -28*s, 24*s, 42*s, 27*s, dim);
                pod(g, -4*s, -11*s, 38*s, 22*s, new Color(20, 30, 45, 170));
                cockpit(g, -25*s, 0, 20*s, 8*s, cool);
                engine(g, 54*s, -14*s, 18*s, color); engine(g, 54*s, 14*s, 18*s, color);
            }
        }
    }

    private static void cockpit(Graphics2D g, double x, double y, double w, double h, Color color) {
        g.setColor(color); g.fill(new Ellipse2D.Double(x - w/2, y - h/2, w, h));
    }

    private static void pod(Graphics2D g, double x, double y, double w, double h, Color color) {
        g.setColor(color); g.fillRoundRect((int)x, (int)y, (int)w, (int)h, 4, 4);
    }

    private static void turret(Graphics2D g, double x, double y, double s, Color color) {
        double r = 4.2*s;
        g.setColor(color); g.fill(new Ellipse2D.Double(x-r, y-r, r*2, r*2));
        g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(x-12*s), (int)Math.round(y));
    }

    private static void symLine(Graphics2D g, double x1, double y1, double x2, double y2, Color color) {
        g.setColor(color); g.drawLine((int)x1, (int)y1, (int)x2, (int)y2); g.drawLine((int)x1, (int)-y1, (int)x2, (int)-y2);
    }

    private static void symOval(Graphics2D g, double x, double y, double w, double h, Color color) {
        g.setColor(color);
        g.fill(new Ellipse2D.Double(x-w/2, y-h/2, w, h));
        g.fill(new Ellipse2D.Double(x-w/2, -y-h/2, w, h));
    }

    private static void engine(Graphics2D g, double x, double y, double size, Color color) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 155));
        g.fill(new Ellipse2D.Double(x-size/2, y-size/4, size, size/2));
        g.setColor(new Color(130, 220, 255, 175));
        g.fill(new Ellipse2D.Double(x-size/4, y-size/7, size/2, size/3.5));
    }
}
