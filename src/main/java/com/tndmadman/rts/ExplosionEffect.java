package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class ExplosionEffect {
    private final double x;
    private final double y;
    private final double radius;
    private final double life;
    private final Color playerColor;
    private final List<Particle> particles = new ArrayList<>();
    private double age;

    private ExplosionEffect(double x, double y, double scale, Color playerColor, long seed) {
        this.x = x;
        this.y = y;
        this.playerColor = playerColor;
        Random random = new Random(seed);
        this.life = 1.15 + scale * 0.18 + random.nextDouble() * 0.45;
        this.radius = 58 + scale * 48 + random.nextDouble() * 26;
        int count = 34 + (int)Math.round(scale * 20) + random.nextInt(14);
        for (int i = 0; i < count; i++) particles.add(makeParticle(random, scale));
    }

    static ExplosionEffect fromUnit(Unit unit) {
        double scale = Math.max(1.1, unit.type().size.scale * 1.45);
        long seed = System.nanoTime()
                ^ Double.doubleToLongBits(unit.x * 31.0 + unit.y * 17.0)
                ^ ((long)unit.key().hashCode() << 32)
                ^ unit.shipTypeId.hashCode();
        return new ExplosionEffect(unit.x, unit.y, scale, PlayerRegistry.color(unit.playerId), seed);
    }

    static ExplosionEffect fromBase(Base base) {
        double scale = Math.max(3.0, base.type().maxHp / 420.0);
        long seed = System.nanoTime()
                ^ Double.doubleToLongBits(base.x * 19.0 + base.y * 23.0)
                ^ ((long)base.id.hashCode() << 32)
                ^ base.typeId.hashCode();
        return new ExplosionEffect(base.x, base.y, scale, PlayerRegistry.color(base.playerId), seed);
    }

    boolean update(double dt) {
        age += dt;
        for (Particle p : particles) p.update(dt);
        return age < life;
    }

    void draw(Graphics2D g2) {
        double t = Math.max(0, Math.min(1, age / life));
        double fade = 1.0 - t;
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawShockwave(g, t, fade);
        drawCoreFlash(g, t, fade);
        for (Particle p : particles) p.draw(g, fade);
        g.dispose();
    }

    private Particle makeParticle(Random random, double scale) {
        double angle = random.nextDouble() * Math.PI * 2;
        double speed = (95 + random.nextDouble() * 310) * (0.72 + scale * 0.18);
        double drag = 1.2 + random.nextDouble() * 1.0;
        double size = 2.0 + random.nextDouble() * (4.2 + scale * 1.6);
        double ttl = life * (0.42 + random.nextDouble() * 0.58);
        Color color = particleColor(random);
        return new Particle(
                x + (random.nextDouble() - 0.5) * 18,
                y + (random.nextDouble() - 0.5) * 18,
                Math.cos(angle) * speed,
                Math.sin(angle) * speed,
                drag,
                size,
                ttl,
                color);
    }

    private Color particleColor(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.18) return playerColor;
        if (roll < 0.55) return new Color(255, 205, 95);
        if (roll < 0.84) return new Color(255, 118, 48);
        return new Color(185, 200, 210);
    }

    private void drawShockwave(Graphics2D g, double t, double fade) {
        double eased = 1.0 - Math.pow(1.0 - t, 2.2);
        double r = radius * (0.28 + eased * 1.25);
        int alpha = alpha(140 * fade);
        g.setColor(new Color(255, 220, 135, alpha));
        g.setStroke(new BasicStroke((float)(7.0 * fade + 0.9), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));

        double outer = r * 1.34;
        g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), alpha(92 * fade)));
        g.setStroke(new BasicStroke((float)(3.4 * fade + 0.5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(x - outer, y - outer, outer * 2, outer * 2));
    }

    private void drawCoreFlash(Graphics2D g, double t, double fade) {
        double core = radius * (0.32 + t * 0.24);
        g.setColor(new Color(255, 245, 185, alpha(230 * fade)));
        g.fill(new Ellipse2D.Double(x - core * 0.45, y - core * 0.45, core * 0.9, core * 0.9));
        g.setColor(new Color(255, 118, 48, alpha(150 * fade)));
        g.fill(new Ellipse2D.Double(x - core, y - core, core * 2, core * 2));
    }

    private static int alpha(double value) { return Math.max(0, Math.min(255, (int)Math.round(value))); }

    private static final class Particle {
        private double x, y, lastX, lastY, vx, vy, age;
        private final double drag, size, ttl;
        private final Color color;

        private Particle(double x, double y, double vx, double vy, double drag, double size, double ttl, Color color) {
            this.x = x; this.y = y; this.lastX = x; this.lastY = y;
            this.vx = vx; this.vy = vy; this.drag = drag; this.size = size; this.ttl = ttl; this.color = color;
        }

        private void update(double dt) {
            age += dt;
            lastX = x; lastY = y;
            x += vx * dt; y += vy * dt;
            double damp = Math.max(0, 1.0 - drag * dt);
            vx *= damp; vy *= damp;
        }

        private void draw(Graphics2D g, double explosionFade) {
            double p = Math.max(0, Math.min(1, age / ttl));
            double fade = Math.max(0, 1.0 - p) * explosionFade;
            if (fade <= 0.02) return;
            int a = alpha(230 * fade);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), a));
            g.setStroke(new BasicStroke((float)Math.max(1.0, size * 0.42), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(lastX, lastY, x, y));
            double r = size * (0.65 + fade * 0.55);
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
    }
}
