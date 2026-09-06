package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

final class WorldItem {
    static final double BASE_RADIUS = 14;
    final int id;
    final Material material;
    double amount;
    double x, y, vx, vy, angle, spin;

    WorldItem(int id, Material material, double amount, double x, double y, double vx, double vy, double angle, double spin) {
        this.id = id;
        this.material = material;
        this.amount = amount;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.angle = angle;
        this.spin = spin;
    }

    boolean empty() { return amount <= 0.05; }
    double radius() { return BASE_RADIUS + Math.min(10, Math.sqrt(Math.max(0, amount)) * 0.65); }
    double pickupRange(Unit unit) { return radius() + 28 * unit.type().size.scale; }

    void update(double dt, int mapW, int mapH) {
        x = Calc.clamp(x + vx * dt, 0, mapW);
        y = Calc.clamp(y + vy * dt, 0, mapH);
        angle += spin * dt;
        double damp = Math.max(0, 1.0 - 1.55 * dt);
        vx *= damp;
        vy *= damp;
        spin *= Math.max(0, 1.0 - 1.2 * dt);
        if (Math.abs(vx) + Math.abs(vy) < 2.5) { vx = 0; vy = 0; }
        if (Math.abs(spin) < 0.04) spin = 0;
    }

    double take(double requested) {
        double take = Math.min(amount, requested);
        amount -= take;
        return take;
    }

    void draw(Graphics2D g2) {
        if (empty() || g2 == null) return;
        double r = radius();
        if (!RenderCulling.visible(g2, x, y, r * 2.0 + 80)) return;
        Color c = material.color;
        Graphics2D g = (Graphics2D)g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 45));
        g.fill(new Ellipse2D.Double(x - r * 1.8, y - r * 1.8, r * 3.6, r * 3.6));
        g.translate(x, y);
        g.rotate(angle);
        g.setColor(new Color(12, 17, 22, 230));
        Shape shape = shape(r);
        g.fill(shape);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 225));
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 165));
        g.fill(new Rectangle2D.Double(-r * 0.32, -r * 0.32, r * 0.64, r * 0.64));
        g.dispose();
        g2.setColor(new Color(235, 245, 255, 215));
        g2.drawString(Calc.round(amount) + " " + material.label, (float)(x + r + 5), (float)(y - r));
    }

    private Shape shape(double r) {
        Path2D p = new Path2D.Double();
        p.moveTo(0, -r);
        p.lineTo(r * 0.82, -r * 0.24);
        p.lineTo(r * 0.55, r * 0.88);
        p.lineTo(-r * 0.55, r * 0.88);
        p.lineTo(-r * 0.82, -r * 0.24);
        p.closePath();
        return p;
    }
}
