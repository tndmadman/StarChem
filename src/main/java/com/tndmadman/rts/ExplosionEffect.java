package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Cosmetic destruction lifecycle. Gameplay death, loot, targeting and network authority remain in World.
 * This object snapshots everything it needs before the destroyed entity is removed.
 */
final class ExplosionEffect {
    private static final double MAX_EFFECT_SCALE = 7.0;
    private static final double MAX_VISUAL_RADIUS = 560.0;

    private final double x;
    private final double y;
    private final double heading;
    private final double scale;
    private final double life;
    private final double wreckStart;
    private final double visualRadius;
    private final Color playerColor;
    private final DestructionProfile profile;
    private final World ownerWorld;
    private final String systemId;
    private final List<Burst> bursts = new ArrayList<>();
    private final List<BurstPlan> burstPlans = new ArrayList<>();
    private final List<DebrisFragment> debris = new ArrayList<>();
    private final List<Vent> vents = new ArrayList<>();
    private double age;
    private int nextBurstPlan;

    private ExplosionEffect(double x, double y, double heading, double scale, Color playerColor,
                            DestructionProfile profile, World ownerWorld, String systemId, long seed) {
        this.x = x;
        this.y = y;
        this.heading = Double.isFinite(heading) ? heading : 0;
        this.scale = Math.max(0.8, Math.min(MAX_EFFECT_SCALE, Double.isFinite(scale) ? scale : 1.0));
        this.playerColor = playerColor == null ? Color.CYAN : playerColor;
        this.profile = profile == null ? DestructionProfile.QUICK : profile;
        this.ownerWorld = ownerWorld;
        this.systemId = systemId == null ? "" : systemId;
        Random random = new Random(seed);
        this.life = this.profile.lifetimeSeconds * (0.97 + random.nextDouble() * 0.06);
        this.wreckStart = this.profile.persistentWreck ? this.profile.wreckStartSeconds : Double.POSITIVE_INFINITY;
        this.visualRadius = Math.min(MAX_VISUAL_RADIUS, 92 + this.scale * 72);
        buildSequence(random);
    }

    static ExplosionEffect fromUnit(Unit unit) {
        double scale = Math.max(1.1, unit.type().size.scale * 1.45);
        long seed = System.nanoTime()
                ^ Double.doubleToLongBits(unit.x * 31.0 + unit.y * 17.0)
                ^ ((long)unit.key().hashCode() << 32)
                ^ unit.shipTypeId.hashCode();
        World world = PlayerRegistry.activeWorld();
        String systemId = world == null ? "" : world.activeSystemId();
        return new ExplosionEffect(unit.x, unit.y, unit.heading, scale, PlayerRegistry.color(unit.playerId),
                DestructionProfile.forShipSize(unit.type().size), world, systemId, seed);
    }

    static ExplosionEffect fromBase(Base base) {
        double scale = Math.min(6.5, Math.max(3.0, base.type().maxHp / 420.0));
        long seed = System.nanoTime()
                ^ Double.doubleToLongBits(base.x * 19.0 + base.y * 23.0)
                ^ ((long)base.id.hashCode() << 32)
                ^ base.typeId.hashCode();
        World world = PlayerRegistry.activeWorld();
        String systemId = world == null ? "" : world.activeSystemId();
        return new ExplosionEffect(base.x, base.y, 0, scale, PlayerRegistry.color(base.playerId),
                DestructionProfile.STATION, world, systemId, seed);
    }

    static ExplosionEffect validationEffect(DestructionProfile profile) {
        return new ExplosionEffect(240, 180, -Math.PI / 2, 3.2, new Color(80, 190, 255),
                profile, null, "", 405L);
    }

    boolean update(double dt) {
        if (!activeInCapturedSystem()) return true;
        if (!Double.isFinite(dt) || dt <= 0) return age < life;
        age += dt;
        while (nextBurstPlan < burstPlans.size() && burstPlans.get(nextBurstPlan).time <= age) {
            BurstPlan plan = burstPlans.get(nextBurstPlan++);
            bursts.add(new Burst(plan.x, plan.y, plan.scale, playerColor, plan.seed, plan.particleCount, plan.core));
        }
        Iterator<Burst> burstIt = bursts.iterator();
        while (burstIt.hasNext()) if (!burstIt.next().update(dt)) burstIt.remove();
        for (DebrisFragment fragment : debris) fragment.update(dt);
        return age < life;
    }

    void draw(Graphics2D g2) {
        if (g2 == null || !activeInCapturedSystem() || !RenderCulling.visible(g2, x, y, visualRadius)) return;
        Lod lod = lod(g2);
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawWreckFrame(g, lod);
        drawDebris(g, lod);
        if (lod != Lod.LOW) drawVents(g, lod);
        for (Burst burst : bursts) burst.draw(g, lod);
        g.dispose();
    }

    private boolean activeInCapturedSystem() {
        if (ownerWorld == null || systemId.isBlank()) return true;
        return systemId.equals(ownerWorld.activeSystemId());
    }

    private void buildSequence(Random random) {
        int budget = profile.particleBudget;
        int primaryCount = Math.min(72, Math.min(budget, 30 + (int)Math.round(scale * 8)));
        bursts.add(new Burst(x, y, scale, playerColor, random.nextLong(), primaryCount, false));
        budget -= primaryCount;

        int coreReserve = profile.coreFlash ? Math.min(52, Math.max(28, budget / 4)) : 0;
        budget -= coreReserve;
        int secondaryCount = profile.secondaryBursts;
        for (int i = 0; i < secondaryCount; i++) {
            double fraction = (i + 1.0) / (secondaryCount + 1.0);
            double time = 0.16 + fraction * profile.secondarySpanSeconds + (random.nextDouble() - 0.5) * 0.10;
            double[] offset = sectionOffset(random, profile == DestructionProfile.STATION ? 1.25 : 1.0);
            int remainingEvents = secondaryCount - i;
            int count = Math.min(38, Math.max(8, budget / Math.max(1, remainingEvents)));
            budget -= count;
            double burstScale = scale * (0.42 + random.nextDouble() * 0.26);
            burstPlans.add(new BurstPlan(time, x + offset[0], y + offset[1], burstScale,
                    random.nextLong(), count, false));
        }
        if (profile.coreFlash) {
            double coreScale = Math.min(MAX_EFFECT_SCALE, scale * (profile == DestructionProfile.STATION ? 1.10 : 0.92));
            burstPlans.add(new BurstPlan(profile.coreTimeSeconds, x, y, coreScale,
                    random.nextLong(), coreReserve, true));
        }
        burstPlans.sort(Comparator.comparingDouble(plan -> plan.time));

        for (int i = 0; i < profile.debrisFragments; i++) debris.add(makeDebris(random, i));
        for (int i = 0; i < profile.vents; i++) vents.add(makeVent(random, i));
    }

    private double[] sectionOffset(Random random, double spread) {
        if (profile == DestructionProfile.STATION) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = (20 + random.nextDouble() * (25 + scale * 15)) * spread;
            return new double[]{Math.cos(angle) * distance, Math.sin(angle) * distance};
        }
        double along = (random.nextDouble() * 2 - 1) * (18 + scale * 22) * spread;
        double across = (random.nextDouble() * 2 - 1) * (10 + scale * 11) * spread;
        double cos = Math.cos(heading), sin = Math.sin(heading);
        return new double[]{along * cos - across * sin, along * sin + across * cos};
    }

    private DebrisFragment makeDebris(Random random, int index) {
        double[] offset = sectionOffset(random, profile == DestructionProfile.QUICK ? 0.45 : 0.82);
        double radial = Math.atan2(offset[1], offset[0]);
        if (!Double.isFinite(radial)) radial = random.nextDouble() * Math.PI * 2;
        double speed = (profile.persistentWreck ? 42 : 72) + random.nextDouble() * (70 + scale * 14);
        speed *= 0.72 + Math.min(1.5, scale * 0.18);
        double direction = radial + (random.nextDouble() - 0.5) * 0.82;
        double size = Math.min(34, 4.0 + random.nextDouble() * (5.0 + scale * 3.3));
        double ttl = profile.persistentWreck ? life : 2.0 + random.nextDouble() * 3.4;
        return new DebrisFragment(x + offset[0] * 0.28, y + offset[1] * 0.28,
                Math.cos(direction) * speed, Math.sin(direction) * speed,
                random.nextDouble() * Math.PI * 2, (random.nextDouble() - 0.5) * 4.8,
                size, 0.55 + random.nextDouble() * 1.10, ttl, index);
    }

    private Vent makeVent(Random random, int index) {
        double[] offset = sectionOffset(random, 0.58);
        double outward = Math.atan2(offset[1], offset[0]);
        if (!Double.isFinite(outward)) outward = random.nextDouble() * Math.PI * 2;
        double start = 0.12 + random.nextDouble() * Math.max(0.35, Math.min(profile.wreckStartSeconds, 2.7));
        double duration = 0.55 + random.nextDouble() * (profile == DestructionProfile.STATION ? 1.8 : 1.15);
        double length = 24 + random.nextDouble() * (30 + scale * 18);
        return new Vent(x + offset[0] * 0.55, y + offset[1] * 0.55, outward,
                start, duration, length, 1.2 + random.nextDouble() * 2.3, index % 3 == 0);
    }

    private void drawWreckFrame(Graphics2D g, Lod lod) {
        if (!profile.persistentWreck || age < wreckStart) return;
        double appear = clamp01((age - wreckStart) / 0.75);
        double fade = endFade() * appear;
        if (fade <= 0.01) return;
        Graphics2D w = (Graphics2D)g.create();
        w.translate(x, y);
        w.rotate(heading);
        double halfLength = Math.min(92, 14 + scale * 18);
        double halfWidth = Math.min(58, 8 + scale * 10);
        int alpha = alpha(150 * fade);
        w.setStroke(new BasicStroke((float)Math.max(1.2, scale * 0.72), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        w.setColor(new Color(16, 22, 30, alpha));
        w.draw(new Line2D.Double(-halfLength, -halfWidth * 0.20, halfLength * 0.38, halfWidth * 0.28));
        w.draw(new Line2D.Double(-halfLength * 0.42, halfWidth * 0.62, halfLength * 0.72, -halfWidth * 0.58));
        w.setStroke(new BasicStroke((float)Math.max(0.8, scale * 0.34), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        w.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), alpha(82 * fade)));
        w.draw(new Line2D.Double(-halfLength * 0.72, -halfWidth * 0.35, -halfLength * 0.12, -halfWidth * 0.02));
        if (lod == Lod.HIGH) {
            w.setColor(new Color(255, 112, 46, alpha(42 * fade * heatFade())));
            w.fill(new Ellipse2D.Double(-scale * 4.0, -scale * 3.0, scale * 8.0, scale * 6.0));
        }
        w.dispose();
    }

    private void drawDebris(Graphics2D g, Lod lod) {
        int stride = lod == Lod.LOW ? 4 : lod == Lod.MEDIUM ? 2 : 1;
        for (int i = 0; i < debris.size(); i += stride) {
            debris.get(i).draw(g, age, life, profile.persistentWreck, playerColor, lod);
        }
    }

    private void drawVents(Graphics2D g, Lod lod) {
        int stride = lod == Lod.MEDIUM ? 2 : 1;
        for (int i = 0; i < vents.size(); i += stride) vents.get(i).draw(g, age);
    }

    private double endFade() {
        double remaining = life - age;
        if (remaining >= 9.0) return 1.0;
        return clamp01(remaining / 9.0);
    }

    private double heatFade() { return clamp01(1.0 - Math.max(0, age - wreckStart) / 8.0); }

    private static Lod lod(Graphics2D g2) {
        double zoom = SelectionRenderPolicy.scale(g2);
        if (zoom < 0.22) return Lod.LOW;
        if (zoom < 0.72) return Lod.MEDIUM;
        return Lod.HIGH;
    }

    private static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }
    private static int alpha(double value) { return Math.max(0, Math.min(255, (int)Math.round(value))); }

    private enum Lod { LOW, MEDIUM, HIGH }

    private record BurstPlan(double time, double x, double y, double scale, long seed, int particleCount, boolean core) { }

    private static final class Burst {
        private final double x;
        private final double y;
        private final double radius;
        private final double life;
        private final Color playerColor;
        private final boolean core;
        private final List<Particle> particles = new ArrayList<>();
        private double age;

        private Burst(double x, double y, double scale, Color playerColor, long seed, int particleCount, boolean core) {
            this.x = x;
            this.y = y;
            this.playerColor = playerColor;
            this.core = core;
            Random random = new Random(seed);
            this.life = (core ? 1.45 : 0.88) + scale * (core ? 0.12 : 0.10) + random.nextDouble() * 0.32;
            this.radius = Math.min(core ? 390 : 285,
                    (core ? 72 : 42) + scale * (core ? 42 : 34) + random.nextDouble() * 18);
            int count = Math.max(0, particleCount);
            for (int i = 0; i < count; i++) particles.add(makeParticle(random, scale));
        }

        private boolean update(double dt) {
            age += dt;
            for (Particle p : particles) p.update(dt);
            return age < life;
        }

        private void draw(Graphics2D g, Lod lod) {
            double t = clamp01(age / life);
            double fade = 1.0 - t;
            drawShockwave(g, t, fade);
            drawCoreFlash(g, t, fade);
            if (lod == Lod.LOW) return;
            int stride = lod == Lod.MEDIUM ? 2 : 1;
            for (int i = 0; i < particles.size(); i += stride) particles.get(i).draw(g, fade);
        }

        private Particle makeParticle(Random random, double scale) {
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = (85 + random.nextDouble() * 245) * (0.70 + scale * 0.15);
            double drag = 1.15 + random.nextDouble() * 1.15;
            double size = 1.8 + random.nextDouble() * (3.5 + scale * 1.15);
            double ttl = life * (0.40 + random.nextDouble() * 0.60);
            Color color = particleColor(random);
            return new Particle(
                    x + (random.nextDouble() - 0.5) * 14,
                    y + (random.nextDouble() - 0.5) * 14,
                    Math.cos(angle) * speed,
                    Math.sin(angle) * speed,
                    drag, size, ttl, color);
        }

        private Color particleColor(Random random) {
            double roll = random.nextDouble();
            if (roll < 0.14) return playerColor;
            if (core && roll < 0.34) return new Color(185, 235, 255);
            if (roll < 0.56) return new Color(255, 205, 95);
            if (roll < 0.86) return new Color(255, 118, 48);
            return new Color(185, 200, 210);
        }

        private void drawShockwave(Graphics2D g, double t, double fade) {
            double eased = 1.0 - Math.pow(1.0 - t, 2.2);
            double r = radius * (0.22 + eased * (core ? 1.38 : 1.10));
            g.setColor(new Color(255, 220, 135, alpha((core ? 165 : 120) * fade)));
            g.setStroke(new BasicStroke((float)((core ? 8.0 : 5.0) * fade + 0.8), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            double outer = r * 1.28;
            g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), alpha((core ? 105 : 75) * fade)));
            g.setStroke(new BasicStroke((float)((core ? 3.8 : 2.6) * fade + 0.45), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Ellipse2D.Double(x - outer, y - outer, outer * 2, outer * 2));
        }

        private void drawCoreFlash(Graphics2D g, double t, double fade) {
            double coreRadius = radius * ((core ? 0.28 : 0.22) + t * (core ? 0.18 : 0.12));
            g.setColor(new Color(255, 108, 42, alpha((core ? 155 : 118) * fade)));
            g.fill(new Ellipse2D.Double(x - coreRadius, y - coreRadius, coreRadius * 2, coreRadius * 2));
            double white = coreRadius * (core ? 0.46 : 0.38);
            g.setColor(new Color(core ? 220 : 255, core ? 244 : 240, 255, alpha((core ? 245 : 220) * fade)));
            g.fill(new Ellipse2D.Double(x - white, y - white, white * 2, white * 2));
        }
    }

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
            double damp = Math.exp(-drag * dt);
            vx *= damp; vy *= damp;
        }

        private void draw(Graphics2D g, double explosionFade) {
            double p = clamp01(age / Math.max(0.001, ttl));
            double fade = Math.max(0, 1.0 - p) * explosionFade;
            if (fade <= 0.02) return;
            int a = alpha(225 * fade);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), a));
            g.setStroke(new BasicStroke((float)Math.max(1.0, size * 0.38), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(lastX, lastY, x, y));
            double r = size * (0.55 + fade * 0.48);
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
    }

    private static final class DebrisFragment {
        private double x, y, vx, vy, angle, spin, age;
        private final double size, aspect, ttl;
        private final int variant;

        private DebrisFragment(double x, double y, double vx, double vy, double angle, double spin,
                               double size, double aspect, double ttl, int variant) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.angle = angle; this.spin = spin;
            this.size = size; this.aspect = aspect; this.ttl = ttl; this.variant = variant;
        }

        private void update(double dt) {
            age += dt;
            if (age > 8.0) {
                vx = 0;
                vy = 0;
                spin = 0;
                return;
            }
            x += vx * dt;
            y += vy * dt;
            angle += spin * dt;
            double damp = Math.exp(-0.92 * dt);
            vx *= damp;
            vy *= damp;
            spin *= Math.exp(-0.38 * dt);
        }

        private void draw(Graphics2D g, double sequenceAge, double sequenceLife, boolean persistent,
                          Color playerColor, Lod lod) {
            if (!persistent && age >= ttl) return;
            double fade = persistent ? 1.0 : clamp01(1.0 - age / Math.max(0.001, ttl));
            if (persistent && sequenceLife - sequenceAge < 9.0) fade *= clamp01((sequenceLife - sequenceAge) / 9.0);
            if (fade <= 0.02) return;
            Graphics2D d = (Graphics2D)g.create();
            d.translate(x, y);
            d.rotate(angle);
            double w = size * aspect;
            double h = size * 0.58;
            Path2D shard = new Path2D.Double();
            shard.moveTo(-w * 0.55, -h * 0.28);
            shard.lineTo(w * 0.50, -h * 0.48);
            shard.lineTo(w * (variant % 2 == 0 ? 0.34 : 0.08), h * 0.55);
            shard.lineTo(-w * 0.48, h * 0.20);
            shard.closePath();
            d.setColor(new Color(20 + variant % 3 * 7, 27 + variant % 4 * 5, 35 + variant % 2 * 8, alpha(205 * fade)));
            d.fill(shard);
            d.setStroke(new BasicStroke((float)Math.max(0.7, size * 0.08)));
            d.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), alpha(95 * fade)));
            d.draw(shard);
            if (lod == Lod.HIGH && age < 3.4) {
                double heat = clamp01(1.0 - age / 3.4);
                d.setColor(new Color(255, 126, 48, alpha(125 * fade * heat)));
                d.draw(new Line2D.Double(-w * 0.40, -h * 0.18, w * 0.28, -h * 0.34));
            }
            d.dispose();
        }
    }

    private record Vent(double x, double y, double angle, double start, double duration,
                        double length, double width, boolean gas) {
        private void draw(Graphics2D g, double age) {
            if (age < start || age >= start + duration) return;
            double t = clamp01((age - start) / duration);
            double fade = Math.sin(t * Math.PI);
            double reach = length * (0.30 + t * 0.70);
            double ex = x + Math.cos(angle) * reach;
            double ey = y + Math.sin(angle) * reach;
            Color color = gas ? new Color(155, 225, 235, alpha(78 * fade))
                    : new Color(105, 215, 255, alpha(125 * fade));
            g.setColor(color);
            g.setStroke(new BasicStroke((float)Math.max(0.8, width * (1.0 - t * 0.45)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x, y, ex, ey));
            if (!gas) {
                g.setColor(new Color(255, 186, 74, alpha(175 * fade)));
                double side = width * 2.2;
                g.draw(new Line2D.Double(ex, ey, ex - Math.sin(angle) * side, ey + Math.cos(angle) * side));
            }
        }
    }
}
