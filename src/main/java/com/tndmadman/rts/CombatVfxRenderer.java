package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.List;

/** Renderer-only propulsion, muzzle, shield and damage effects. */
final class CombatVfxRenderer {
    private CombatVfxRenderer() { }

    static void drawUnit(Graphics2D g2, World world, Unit unit, Color playerColor, double cameraScale) {
        if (g2 == null || unit == null || cameraScale < 0.24) return;
        Graphics2D fx = (Graphics2D) g2.create();
        fx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        fx.translate(unit.x, unit.y);
        fx.rotate(unit.heading);

        drawEngines(fx, world, unit, cameraScale);
        if (cameraScale >= 0.42) {
            drawMuzzleFlash(fx, world, unit);
            drawImpactState(fx, world, unit, playerColor);
            drawDamageState(fx, world, unit);
        }
        fx.dispose();
    }

    private static void drawEngines(Graphics2D g, World world, Unit unit, double cameraScale) {
        double[][] mounts = ShipVisualRenderer.engineMounts(unit.type());
        double distance = Math.hypot(unit.targetX - unit.x, unit.targetY - unit.y);
        double thrust = distance > 3 ? 1.0 : 0.34;
        if (unit.afterburnerActive) thrust = 1.7;
        double time = world == null ? 0.0 : world.systemTime();
        double pulse = 0.88 + Math.sin(time * 13.0 + unit.unitId * 0.71) * 0.12;
        double s = unit.type().size.scale;
        double plume = (7.0 + 9.0 * s) * thrust * pulse;
        if (cameraScale < 0.5) plume *= 0.72;

        for (double[] mount : mounts) {
            double x = mount[0], y = mount[1];
            double aperture = Math.max(1.8, 2.1 * s);
            g.setColor(new Color(90, 195, 255, unit.afterburnerActive ? 90 : 55));
            g.fill(new Ellipse2D.Double(x - aperture * 1.6, y - aperture * 1.6, aperture * 3.2, aperture * 3.2));
            Path2D exhaust = new Path2D.Double();
            exhaust.moveTo(x, y - aperture * 0.8);
            exhaust.lineTo(x + plume, y);
            exhaust.lineTo(x, y + aperture * 0.8);
            exhaust.closePath();
            g.setColor(new Color(65, 155, 255, unit.afterburnerActive ? 150 : 100));
            g.fill(exhaust);
            g.setColor(new Color(225, 250, 255, 215));
            g.fill(new Ellipse2D.Double(x - aperture * 0.6, y - aperture * 0.6, aperture * 1.2, aperture * 1.2));
        }
    }

    private static void drawMuzzleFlash(Graphics2D g, World world, Unit unit) {
        if (unit.weaponFlashTimer <= 0) return;
        List<WeaponType> weapons = WeaponRules.loadout(world, unit);
        if (weapons.isEmpty()) return;
        double s = unit.type().size.scale;
        double nose = -34.0 * s;
        int beam = 0, kinetic = 0, missile = 0;
        Color beamColor = new Color(115, 225, 255);
        Color kineticColor = new Color(255, 211, 115);
        Color missileColor = new Color(255, 132, 76);
        for (WeaponType weapon : weapons) {
            if (weapon == null || weapon.screenWeapon) continue;
            if (weapon.beam) { beam++; beamColor = weapon.color; }
            else if (weapon.movingShot) { missile++; missileColor = weapon.color; }
            else { kinetic++; kineticColor = weapon.color; }
        }
        if (beam > 0) drawBeamMuzzle(g, nose, -7*s, beamColor, s, beam);
        if (kinetic > 0) drawKineticMuzzle(g, nose - 1*s, 0, kineticColor, s, kinetic);
        if (missile > 0) drawMissileMuzzle(g, nose + 4*s, 7*s, missileColor, s, missile);
    }

    private static void drawBeamMuzzle(Graphics2D g, double x, double y, Color c, double s, int count) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke((float)Math.max(1.2, s * 1.3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 210));
        g.drawLine((int)(x - (5 + count * 2)*s), (int)y, (int)(x + 4*s), (int)y);
        g.setColor(new Color(235, 255, 255, 235));
        g.fill(new Ellipse2D.Double(x - 2.5*s, y - 2.5*s, 5*s, 5*s));
        g.setStroke(old);
    }

    private static void drawKineticMuzzle(Graphics2D g, double x, double y, Color c, double s, int count) {
        double r = (3.0 + Math.min(3, count) * 0.7) * s;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 185));
        Path2D star = new Path2D.Double();
        star.moveTo(x - r * 2.0, y);
        star.lineTo(x, y - r);
        star.lineTo(x + r, y);
        star.lineTo(x, y + r);
        star.closePath();
        g.fill(star);
        g.setColor(new Color(255, 245, 205, 220));
        g.fill(new Ellipse2D.Double(x - r * .45, y - r * .45, r * .9, r * .9));
    }

    private static void drawMissileMuzzle(Graphics2D g, double x, double y, Color c, double s, int count) {
        int ports = Math.min(3, count);
        for (int i = 0; i < ports; i++) {
            double yy = y + (i - (ports - 1) / 2.0) * 4.5 * s;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 165));
            g.fill(new Ellipse2D.Double(x - 3*s, yy - 2*s, 7*s, 4*s));
            g.setColor(new Color(255, 225, 175, 205));
            g.fill(new Ellipse2D.Double(x - 1.5*s, yy - 1.2*s, 3*s, 2.4*s));
        }
    }

    private static void drawImpactState(Graphics2D g, World world, Unit unit, Color playerColor) {
        if (unit.shieldDelayTimer <= 0) return;
        double s = unit.type().size.scale;
        double r = 28 * s;
        double maxShield = Math.max(0, unit.type().maxShield);
        if (unit.shield > 0 && maxShield > 0) {
            double pct = Math.max(0, Math.min(1, unit.shield / maxShield));
            double time = world == null ? 0 : world.systemTime();
            double start = Math.floorMod(unit.unitId * 47 + (int)Math.round(time * 33), 360);
            Color c = playerColor == null ? new Color(90, 190, 255) : playerColor;
            g.setColor(new Color(c.getRed(), Math.min(255, c.getGreen() + 65), 255, (int)(70 + pct * 70)));
            g.setStroke(new BasicStroke((float)Math.max(1.1, s * 1.25), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(-r, -r, r*2, r*2, start, 58 + pct * 40, Arc2D.OPEN));
        } else {
            g.setColor(new Color(255, 166, 74, 155));
            g.setStroke(new BasicStroke((float)Math.max(1.0, s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int)(-7*s), (int)(-5*s), (int)(-16*s), (int)(-12*s));
            g.drawLine((int)(-7*s), (int)(-5*s), (int)(-19*s), (int)(-2*s));
        }
    }

    private static void drawDamageState(Graphics2D g, World world, Unit unit) {
        double maxHp = Math.max(1, unit.type().maxHp);
        double hpPct = Math.max(0, Math.min(1, unit.hp / maxHp));
        if (hpPct > 0.42) return;
        double s = unit.type().size.scale;
        double time = world == null ? 0 : world.systemTime();
        int sparks = hpPct < 0.2 ? 3 : 1;
        for (int i = 0; i < sparks; i++) {
            double phase = time * (7.0 + i) + unit.unitId * 1.9 + i * 2.3;
            if (Math.sin(phase) < 0.45) continue;
            double px = (-6 + i * 9) * s;
            double py = ((i & 1) == 0 ? -9 : 10) * s;
            g.setColor(new Color(255, 145 + i * 20, 70, 170));
            g.setStroke(new BasicStroke((float)Math.max(.8, .8*s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int)px, (int)py, (int)(px - 7*s), (int)(py + (i-1)*5*s));
        }
    }
}
