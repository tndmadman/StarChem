package com.tndmadman.rts;

import java.awt.*;

final class WormholeGate {
    final String id;
    final String fromSystemId;
    final String toSystemId;
    final double x;
    final double y;
    final double exitX;
    final double exitY;
    final double radius;

    WormholeGate(String id, String fromSystemId, String toSystemId, double x, double y, double exitX, double exitY) {
        this.id = id;
        this.fromSystemId = fromSystemId;
        this.toSystemId = toSystemId;
        this.x = x;
        this.y = y;
        this.exitX = exitX;
        this.exitY = exitY;
        this.radius = 76;
    }

    boolean contains(double wx, double wy) {
        return Calc.distance(wx, wy, x, y) <= radius * 0.68;
    }

    String label() {
        return toSystemId;
    }

    void draw(Graphics2D g2) {
        Graphics2D w = (Graphics2D) g2.create();
        w.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        w.setStroke(new BasicStroke(2.2f));
        w.setColor(new Color(105, 210, 255, 48));
        w.fillOval((int)(x - radius * 1.7), (int)(y - radius * 1.7), (int)(radius * 3.4), (int)(radius * 3.4));
        w.setColor(new Color(170, 105, 255, 120));
        w.drawOval((int)(x - radius), (int)(y - radius), (int)(radius * 2), (int)(radius * 2));
        w.setColor(new Color(80, 220, 255, 190));
        w.drawOval((int)(x - radius * 0.58), (int)(y - radius * 0.58), (int)(radius * 1.16), (int)(radius * 1.16));
        String text = label();
        FontMetrics fm = w.getFontMetrics();
        int tw = fm.stringWidth(text);
        int tx = (int)x - tw / 2;
        int ty = (int)(y + radius + 20);
        w.setColor(new Color(0, 0, 0, 145));
        w.fillRoundRect(tx - 5, ty - 13, tw + 10, 17, 8, 8);
        w.setColor(new Color(230, 245, 255, 220));
        w.drawString(text, tx, ty);
        w.dispose();
    }
}
