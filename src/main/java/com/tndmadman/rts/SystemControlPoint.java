package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;

final class SystemControlPoint {
    final double x;
    final double y;
    final double radius;

    SystemControlPoint(StarSystemDefinition definition) {
        int width = definition == null ? 16000 : definition.width();
        int height = definition == null ? 14000 : definition.height();
        x = width * 0.5;
        y = height * 0.5;
        radius = Math.max(900, Math.min(width, height) * 0.24);
    }

    boolean contains(double px, double py) {
        return Calc.distance(x, y, px, py) <= radius;
    }

    void draw(Graphics2D g2, SystemControlState state, int rgb) {
        if (g2 == null || state == null || state.status() == SystemControlStatus.PROTECTED) return;
        Graphics2D g = (Graphics2D) g2.create();
        Color color = new Color(rgb & 0xFFFFFF);
        int alpha = state.status() == SystemControlStatus.CONTESTED ? 145 : 85;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        g.setStroke(new BasicStroke(state.status() == SystemControlStatus.CAPTURING ? 4f : 2f));
        g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        double beacon = 46;
        g.fill(new Ellipse2D.Double(x - beacon / 2, y - beacon / 2, beacon, beacon));
        g.setColor(new Color(230, 244, 255, 190));
        g.drawString("SYSTEM CONTROL", (int)x - 48, (int)y - 60);
        g.dispose();
    }
}
