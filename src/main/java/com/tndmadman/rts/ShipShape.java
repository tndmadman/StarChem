package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.Random;

final class ShipShape {
    private ShipShape() { }

    static Path2D create(ShipType type) {
        Random r = new Random(type.seed);
        double scale = type.size.scale;
        double length = 38 * scale * (0.9 + r.nextDouble() * 0.35);
        double width = 22 * scale * (0.85 + r.nextDouble() * 0.35);
        double nose = -length * 0.62;
        double tail = length * 0.52;
        double waist = width * (0.35 + r.nextDouble() * 0.20);
        double wing = width * (0.85 + r.nextDouble() * 0.35);
        double tailWing = width * (0.45 + r.nextDouble() * 0.30);
        Path2D p = new Path2D.Double();
        p.moveTo(nose, 0);
        p.lineTo(-length * 0.18, -waist);
        p.lineTo(length * 0.05, -wing);
        p.lineTo(length * 0.35, -tailWing);
        p.lineTo(tail, -width * 0.28);
        p.lineTo(length * 0.42, 0);
        p.lineTo(tail, width * 0.28);
        p.lineTo(length * 0.35, tailWing);
        p.lineTo(length * 0.05, wing);
        p.lineTo(-length * 0.18, waist);
        p.closePath();
        return p;
    }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        Path2D hull = create(type);
        g2.setColor(playerColor.darker());
        g2.fill(hull);
        g2.setColor(playerColor);
        g2.setStroke(new BasicStroke((float)Math.max(1.3, type.size.scale * 1.5)));
        g2.draw(hull);
        g2.setColor(new Color(225, 245, 255, 140));
        double s = type.size.scale;
        g2.fillOval((int)(-8*s), (int)(-4*s), (int)(16*s), (int)(8*s));
    }
}
