package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Line2D;

final class WeaponSystem {
    void update(World world, double dt) {
        for (Unit unit : world.units.values()) {
            unit.weaponCooldown = Math.max(0, unit.weaponCooldown - dt);
            unit.weaponFlashTimer = Math.max(0, unit.weaponFlashTimer - dt);
            if (!WeaponRules.armed(unit.type())) continue;
            if (unit.task == UnitTask.IDLE && unit.attackTarget.isBlank()) acquireTarget(world, unit);
            if (unit.task == UnitTask.ATTACK) updateAttack(world, unit);
        }
    }

    void draw(Graphics2D g2, World world) {
        for (Unit unit : world.units.values()) {
            if (unit.attackTarget.isBlank()) continue;
            if (!CombatTarget.enemy(world, unit, unit.attackTarget)) continue;
            double tx = CombatTarget.x(world, unit.attackTarget);
            double ty = CombatTarget.y(world, unit.attackTarget);
            double dist = Calc.distance(unit.x, unit.y, tx, ty);
            WeaponVolley volley = WeaponRules.volley(unit.type(), dist);
            WeaponType visual = volley.visualWeapon();
            if (visual == null) continue;
            float alpha = (float)(unit.weaponFlashTimer > 0 ? 0.85 : 0.18);
            drawShot(g2, unit.x, unit.y, tx, ty, visual, alpha);
        }
    }

    private void acquireTarget(World world, Unit unit) {
        String best = "";
        double bestDist = Double.MAX_VALUE;
        double range = WeaponRules.maxRange(unit.type());
        for (Unit target : world.units.values()) {
            if (target.playerId.equals(unit.playerId) || target.hp <= 0) continue;
            double d = Calc.distance(unit.x, unit.y, target.x, target.y);
            if (d <= range && d < bestDist) { best = CombatTarget.unit(target); bestDist = d; }
        }
        for (Base target : world.bases.values()) {
            if (target.playerId.equals(unit.playerId) || target.hp <= 0) continue;
            double d = Calc.distance(unit.x, unit.y, target.x, target.y);
            if (d <= range && d < bestDist) { best = CombatTarget.base(target); bestDist = d; }
        }
        if (!best.isBlank()) {
            unit.attackTarget = best;
            unit.task = UnitTask.ATTACK;
        }
    }

    private void updateAttack(World world, Unit unit) {
        if (unit.attackTarget.isBlank() || !CombatTarget.enemy(world, unit, unit.attackTarget)) {
            unit.attackTarget = "";
            unit.task = UnitTask.IDLE;
            return;
        }
        double tx = CombatTarget.x(world, unit.attackTarget);
        double ty = CombatTarget.y(world, unit.attackTarget);
        double dist = Calc.distance(unit.x, unit.y, tx, ty);
        double range = WeaponRules.maxRange(unit.type());
        if (range <= 0) {
            unit.attackTarget = "";
            unit.task = UnitTask.IDLE;
            return;
        }
        if (dist > range * 0.92) {
            world.moveTowardOrbit(unit, tx, ty, range * 0.82);
            return;
        }
        WeaponVolley volley = WeaponRules.volley(unit.type(), dist);
        if (volley.damage() <= 0 || unit.weaponCooldown > 0) return;
        CombatTarget.damage(world, unit.attackTarget, volley.damage());
        unit.weaponCooldown = volley.cooldownSeconds();
        unit.weaponFlashTimer = 0.22;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.heading = Math.atan2(ty - unit.y, tx - unit.x);
    }

    private void drawShot(Graphics2D g2, double x1, double y1, double x2, double y2, WeaponType weapon, float alpha) {
        Graphics2D s = (Graphics2D) g2.create();
        Color c = weapon.color;
        s.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(25, Math.min(230, (int)(alpha * 255)))));
        if (weapon.beam) {
            s.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            s.draw(new Line2D.Double(x1, y1, x2, y2));
        } else {
            s.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{18f, 12f}, 0));
            s.draw(new Line2D.Double(x1, y1, x2, y2));
            double mx = x1 + (x2 - x1) * 0.62;
            double my = y1 + (y2 - y1) * 0.62;
            s.fillOval((int)mx - 4, (int)my - 4, 8, 8);
        }
        s.dispose();
    }
}
