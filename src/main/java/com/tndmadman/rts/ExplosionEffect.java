package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Bounded multi-stage destruction effect with deterministic debris and temporary wreckage. */
final class ExplosionEffect {
    private static final int MAX_PARTICLES = 88;
    private static final int MAX_DEBRIS = 24;
    private static final int MAX_SECONDARY_BLASTS = 7;

    private final double x;
    private final double y;
    private final double radius;
    private final double blastLife;
    private final double totalLife;
    private final double scale;
    private final Color playerColor;
    private final boolean major;
    private final List<Particle> particles = new ArrayList<>();
    private final List<Debris> debris = new ArrayList<>();
    private final List<SecondaryBlast> secondaryBlasts = new ArrayList<>();
    private double age;

    private ExplosionEffect(double x, double y, double scale, Color playerColor, long seed) {
        this.x = x;
        this.y = y;
        this.scale = scale;
        this.playerColor = playerColor == null ? new Color(120, 170, 220) : playerColor;
        this.major = scale >= 2.75;
        Random random = new Random(seed);
        this.blastLife = Math.min(4.4, 1.05 + scale * 0.22 + random.nextDouble() * 0.35);
        double wreckLife = major ? Math.min(16.0, 5.0 + scale * 1.25) : Math.min(2.5, scale * 0.38);
        this.totalLife = blastLife + wreckLife;
        this.radius = Math.min(430, 54 + scale * 45 + random.nextDouble() * 24);

        int particleCount = Math.min(MAX_PARTICLES, 28 + (int)Math.round(scale * 13) + random.nextInt(9));
        for (int i = 0; i < particleCount; i++) particles.add(makeParticle(random));

        int debrisCount = Math.min(MAX_DEBRIS, (major ? 8 : 3) + (int)Math.round(scale * 2.8));
        for (int i = 0; i < debrisCount; i++) debris.add(makeDebris(random, i));

        int secondaryCount = Math.min(MAX_SECONDARY_BLASTS, major ? 2 + (int)Math.floor(scale / 2.2) : 1);
        for (int i = 0; i < secondaryCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = radius * (0.10 + random.nextDouble() * 0.42);
            secondaryBlasts.add(new SecondaryBlast(
                    Math.cos(angle) * dist,
                    Math.sin(angle) * dist,
                    blastLife * (0.18 + random.nextDouble() * 0.48),
                    radius * (0.14 + random.nextDouble() * 0.18)));
        }
    }

    static ExplosionEffect fromUnit(Unit unit) {
        double scale = Math.max(1.1, unit.type().size.scale * 1.45);
        long seed = Double.doubleToLongBits(unit.x * 31.0 + unit.y * 17.0)
                ^ ((long)unit.key().hashCode() << 32)
                ^ unit.shipTypeId.hashCode();
        return new ExplosionEffect(unit.x, unit.y, scale, PlayerRegistry.color(unit.playerId), seed);
    }

    static ExplosionEffect fromBase(Base base) {
        double scale = Math.max(3.0, base.type().maxHp / 420.0);
        long seed = Double.doubleToLongBits(base.x * 19.0 + base.y * 23.0)
                ^ ((long)base.id.hashCode() << 32)
                ^ base.typeId.hashCode();
        return new ExplosionEffect(base.x, base.y, scale, PlayerRegistry.color(base.playerId), seed);
    }

    boolean update(double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return age < totalLife;
        age += dt;
        for (Particle p : particles) p.update(dt);
        for (Debris d : debris) d.update(dt);
        return age < totalLife;
    }

    void draw(Graphics2D g2) {
        if (g2 == null) return;
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double blastT = Math.max(0, Math.min(1, age / blastLife));
        double blastFade = Math.max(0, 1.0 - blastT);

        if (age <= blastLife) {
            drawShockwave(g, blastT, blastFade);
            drawCoreFlash(g, blastT, blastFade);
            drawSecondaryBlasts(g);
        }
        for (Particle p : particles) p.draw(g, age <= blastLife ? blastFade : 0);
        drawDebrisAndWreck(g);
        g.dispose();
    }

    private Particle makeParticle(Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double speed = Math.min(650, (90 + random.nextDouble() * 285) * (0.70 + scale * 0.17));
        double drag = 1.0 + random.nextDouble() * 1.1;
        double size = Math.min(14, 1.7 + random.nextDouble() * (3.8 + scale * 0.95));
        double ttl = blastLife * (0.38 + random.nextDouble() * 0.62);
        return new Particle(
                x + (random.nextDouble() - 0.5) * 18,
                y + (random.nextDouble() - 0.5) * 18,
                Math.cos(angle) * speed,
                Math.sin(angle) * speed,
                drag, size, ttl, particleColor(random));
    }

    private Debris makeDebris(Random random, int index) {
        double angle = random.nextDouble() * Math.PI * 2;
        double speed = (24 + random.nextDouble() * 115) * Math.min(2.0, .8 + scale * .08);
        double size = Math.min(34, 4.0 + random.nextDouble() * (5.0 + scale * 1.15));
        return new Debris(x, y, Math.cos(angle) * speed, Math.sin(angle) * speed,
                size, random.nextDouble() * Math.PI * 2, (random.nextDouble() - .5) * 2.4,
                index % 5 == 0);
    }

    private Color particleColor(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.12) return playerColor;
        if (roll < 0.46) return new Color(255, 215, 110);
        if (roll < 0.78) return new Color(255, 115, 45);
        return new Color(178, 191, 201);
    }

    private void drawShockwave(Graphics2D g, double t, double fade) {
        double eased = 1.0 - Math.pow(1.0 - t, 2.2);
        double r = radius * (0.24 + eased * 1.18);
        g.setColor(new Color(255, 222, 148, alpha(130 * fade)));
        g.setStroke(new BasicStroke((float)Math.max(.8, 6.5 * fade + .7), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));

        double outer = r * 1.32;
        g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), alpha(72 * fade)));
        g.setStroke(new BasicStroke((float)Math.max(.6, 3.0 * fade + .5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(x - outer, y - outer, outer * 2, outer * 2));
    }

    private void drawCoreFlash(Graphics2D g, double t, double fade) {
        double core = radius * (0.28 + t * 0.22);
        g.setColor(new Color(255, 247, 198, alpha(225 * fade)));
        g.fill(new Ellipse2D.Double(x - core * .42, y - core * .42, core * .84, core * .84));
        g.setColor(new Color(255, 116, 46, alpha(132 * fade)));
        g.fill(new Ellipse2D.Double(x - core, y - core, core * 2, core * 2));
    }

    private void drawSecondaryBlasts(Graphics2D g) {
        for (SecondaryBlast blast : secondaryBlasts) {
            double localAge = age - blast.start;
            if (localAge < 0 || localAge > .72) continue;
            double t = localAge / .72;
            double fade = 1 - t;
            double rr = blast.radius * (.25 + t * 1.2);
            double bx = x + blast.dx, by = y + blast.dy;
            g.setColor(new Color(255, 167, 66, alpha(155 * fade)));
            g.fill(new Ellipse2D.Double(bx - rr, by - rr, rr * 2, rr * 2));
            g.setColor(new Color(255, 242, 190, alpha(190 * fade)));
            g.fill(new Ellipse2D.Double(bx - rr * .32, by - rr * .32, rr * .64, rr * .64));
        }
    }

    private void drawDebrisAndWreck(Graphics2D g) {
        double wreckT = age <= blastLife ? 0 : Math.min(1, (age - blastLife) / Math.max(.01, totalLife - blastLife));
        double fade = major ? Math.max(.12, 1.0 - wreckT * .88) : Math.max(0, 1.0 - wreckT);
        for (Debris d : debris) d.draw(g, fade, age, blastLife, major);

        if (major && age > blastLife * .72) {
            double reveal = Math.min(1, (age - blastLife * .72) / Math.max(.15, blastLife * .28));
            double wreckRadius = Math.min(radius * .33, 28 + scale * 5.0);
            g.setColor(new Color(22, 27, 30, alpha(170 * reveal * fade)));
            Path2D wreck = new Path2D.Double();
            wreck.moveTo(x - wreckRadius, y - wreckRadius * .18);
            wreck.lineTo(x - wreckRadius * .38, y - wreckRadius * .68);
            wreck.lineTo(x + wreckRadius * .62, y - wreckRadius * .44);
            wreck.lineTo(x + wreckRadius, y + wreckRadius * .10);
            wreck.lineTo(x + wreckRadius * .25, y + wreckRadius * .48);
            wreck.lineTo(x - wreckRadius * .55, y + wreckRadius * .62);
            wreck.closePath();
            g.fill(wreck);
            g.setColor(new Color(255, 99, 40, alpha(55 * reveal * fade)));
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine((int)(x - wreckRadius * .42), (int)(y - wreckRadius * .18),
                    (int)(x + wreckRadius * .38), (int)(y + wreckRadius * .22));
        }
    }

    private static int alpha(double value) { return Math.max(0, Math.min(255, (int)Math.round(value))); }

    private record SecondaryBlast(double dx, double dy, double start, double radius) { }

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
            if (fade <= .02) return;
            int a = alpha(220 * fade);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), a));
            g.setStroke(new BasicStroke((float)Math.max(1.0, size * .38), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(lastX, lastY, x, y));
            double r = size * (.60 + fade * .48);
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
    }

    private static final class Debris {
        private double x, y, vx, vy, angle;
        private final double size, spin;
        private final boolean hot;

        private Debris(double x, double y, double vx, double vy, double size, double angle, double spin, boolean hot) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.size = size;
            this.angle = angle; this.spin = spin; this.hot = hot;
        }

        private void update(double dt) {
            x += vx * dt; y += vy * dt; angle += spin * dt;
            double damp = Math.max(0, 1.0 - .42 * dt);
            vx *= damp; vy *= damp;
        }

        private void draw(Graphics2D g, double fade, double effectAge, double blastLife, boolean major) {
            if (fade <= .02) return;
            Graphics2D d = (Graphics2D)g.create();
            d.translate(x, y); d.rotate(angle);
            int a = alpha((major ? 190 : 155) * fade);
            d.setColor(new Color(42, 48, 51, a));
            Path2D shard = new Path2D.Double();
            shard.moveTo(-size, -size * .22);
            shard.lineTo(size * .72, -size * .48);
            shard.lineTo(size, size * .18);
            shard.lineTo(-size * .36, size * .52);
            shard.closePath();
            d.fill(shard);
            d.setColor(new Color(118, 128, 132, alpha(90 * fade)));
            d.draw(shard);
            if (hot && effectAge < blastLife * 1.7) {
                d.setColor(new Color(255, 104, 42, alpha(120 * fade)));
                d.fill(new Ellipse2D.Double(-size * .15, -size * .15, size * .30, size * .30));
            }
            d.dispose();
        }
    }
}
