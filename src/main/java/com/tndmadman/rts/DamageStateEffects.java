package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/** Lightweight client-side damage cues derived only from already-authoritative HP. */
final class DamageStateEffects {
    private static final double DAMAGE_THRESHOLD = 0.48;
    private static final double MIN_SHIP_SCALE = 1.35;

    private DamageStateEffects() { }

    static void drawUnit(Graphics2D g2, Unit unit, double viewScale) {
        if (g2 == null || unit == null || viewScale < 0.58 || unit.hp <= 0) return;
        ShipType type = unit.type();
        if (type.size.scale < MIN_SHIP_SCALE) return;
        double severity = severity(unit.hp, type.maxHp);
        if (severity <= 0 || !RenderCulling.visible(g2, unit.x, unit.y, 72 + type.size.scale * 16)) return;

        Graphics2D g = (Graphics2D)g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double time = System.nanoTime() / 1_000_000_000.0;
        long seed = ((long)type.seed << 32) ^ ((long)unit.unitId * 0x9E3779B97F4A7C15L) ^ unit.playerId.hashCode();
        int sites = Math.min(4, 1 + (int)Math.floor(severity * 3.4));
        for (int i = 0; i < sites; i++) {
            double r0 = noise(seed, i * 4);
            double r1 = noise(seed, i * 4 + 1);
            double r2 = noise(seed, i * 4 + 2);
            double phase = fractional(time * (1.7 + r2 * 2.0) + r0 * 7.0);
            if (phase > 0.42 + severity * 0.34) continue;

            double along = (r0 * 2 - 1) * (9 + type.size.scale * 11);
            double across = (r1 * 2 - 1) * (5 + type.size.scale * 7);
            double cos = Math.cos(unit.heading), sin = Math.sin(unit.heading);
            double px = unit.x + along * cos - across * sin;
            double py = unit.y + along * sin + across * cos;
            double outward = Math.atan2(py - unit.y, px - unit.x);
            if (!Double.isFinite(outward)) outward = unit.heading + r1 * Math.PI * 2;

            drawFailureSite(g, px, py, outward, severity, r1, r2, phase,
                    8 + severity * 18, 1.0 + severity * 1.4);
        }

        if (severity > 0.72) {
            double pulse = 0.45 + Math.sin(time * 6.2 + seed * 0.000001) * 0.18;
            double radius = (2.2 + type.size.scale * 1.3) * Math.max(0.2, pulse);
            g.setColor(new Color(255, 112, 42, alpha(70 + severity * 70)));
            g.fill(new Ellipse2D.Double(unit.x - radius, unit.y - radius, radius * 2, radius * 2));
        }
        g.dispose();
    }

    static void drawBase(Graphics2D g2, Base base, double radius) {
        if (g2 == null || base == null || base.hp <= 0 || !Double.isFinite(radius) || radius <= 0) return;
        double viewScale = SelectionRenderPolicy.scale(g2);
        if (viewScale < 0.48) return;
        BaseType type = base.type();
        double severity = severity(base.hp, type.maxHp);
        if (severity <= 0 || !RenderCulling.visible(g2, base.x, base.y, radius + 46)) return;

        Graphics2D g = (Graphics2D)g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double time = System.nanoTime() / 1_000_000_000.0;
        long seed = ((long)base.typeId.hashCode() << 32) ^ base.id.hashCode() ^ 0x40557A710L;
        int sites = Math.min(6, 2 + (int)Math.floor(severity * 4.2));
        for (int i = 0; i < sites; i++) {
            double r0 = noise(seed, i * 5);
            double r1 = noise(seed, i * 5 + 1);
            double r2 = noise(seed, i * 5 + 2);
            double angle = r0 * Math.PI * 2.0;
            double distance = radius * (0.32 + r1 * 0.56);
            double px = base.x + Math.cos(angle) * distance;
            double py = base.y + Math.sin(angle) * distance;
            double phaseWindow = 0.46 + severity * 0.36;
            double phase = fractional(time * (1.35 + r2 * 1.75) + r1 * 9.0);
            if (phase > phaseWindow) continue;

            double pulse = Math.max(0, Math.sin(Math.PI * phase / Math.max(0.05, phaseWindow)));
            double outward = angle + (r2 - 0.5) * 0.24;
            double ventScale = (11 + severity * 26) * (0.70 + r1 * 0.55);
            drawFailureSite(g, px, py, outward, severity, r1, r2, phase,
                    ventScale, 1.25 + severity * 1.65);

            if (severity > 0.58 && pulse > 0.35) {
                double smokeRadius = (4 + severity * 8) * pulse;
                g.setColor(new Color(105, 120, 130, alpha((35 + severity * 42) * pulse)));
                g.fill(new Ellipse2D.Double(px - smokeRadius, py - smokeRadius,
                        smokeRadius * 2, smokeRadius * 2));
            }
        }

        if (severity > 0.76) {
            double pulse = 0.55 + Math.sin(time * 5.4 + seed * 0.000001) * 0.22;
            double coreRadius = Math.max(4, radius * 0.09) * Math.max(0.25, pulse);
            g.setColor(new Color(255, 104, 38, alpha(62 + severity * 72)));
            g.fill(new Ellipse2D.Double(base.x - coreRadius, base.y - coreRadius,
                    coreRadius * 2, coreRadius * 2));
        }
        g.dispose();
    }

    private static void drawFailureSite(Graphics2D g, double px, double py, double outward,
                                        double severity, double r1, double r2, double phase,
                                        double ventLengthScale, double strokeWidth) {
        double phaseWindow = 0.42 + severity * 0.34;
        double pulse = Math.sin(Math.PI * phase / Math.max(0.05, phaseWindow));
        pulse = Math.max(0, pulse);
        double ventLength = ventLengthScale * (0.65 + r2 * 0.65) * pulse;
        double ex = px + Math.cos(outward) * ventLength;
        double ey = py + Math.sin(outward) * ventLength;
        int plasmaAlpha = alpha((42 + severity * 80) * pulse);
        g.setStroke(new BasicStroke((float)strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(120, 220, 255, plasmaAlpha));
        g.draw(new Line2D.Double(px, py, ex, ey));

        double sparkLength = 4 + severity * 8 + r1 * 5;
        g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 174, 62, alpha((115 + severity * 105) * pulse)));
        for (int spark = 0; spark < (severity > 0.62 ? 2 : 1); spark++) {
            double spread = (spark - 0.5) * 0.34 + (r2 - 0.5) * 0.22;
            double sx = px + Math.cos(outward + spread) * sparkLength;
            double sy = py + Math.sin(outward + spread) * sparkLength;
            g.draw(new Line2D.Double(px, py, sx, sy));
        }
    }

    static double severity(double hp, double maxHp) {
        if (!Double.isFinite(hp) || !Double.isFinite(maxHp) || maxHp <= 0 || hp <= 0) return 0;
        double ratio = Math.max(0, Math.min(1, hp / maxHp));
        if (ratio >= DAMAGE_THRESHOLD) return 0;
        return Math.max(0, Math.min(1, (DAMAGE_THRESHOLD - ratio) / DAMAGE_THRESHOLD));
    }

    private static double noise(long seed, int index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    private static double fractional(double value) { return value - Math.floor(value); }
    private static int alpha(double value) { return Math.max(0, Math.min(255, (int)Math.round(value))); }
}
