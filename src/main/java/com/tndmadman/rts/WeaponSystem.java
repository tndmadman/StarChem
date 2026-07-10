package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class WeaponSystem {
    void update(World world, double dt) {
        ShieldSystem.update(world, dt);
        for (Unit unit : world.units.values()) {
            unit.weaponCooldown = Math.max(0, unit.weaponCooldown - dt);
            unit.weaponFlashTimer = Math.max(0, unit.weaponFlashTimer - dt);
        }
        if (AiDevSettings.disableAttacks) return;
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (AiDevSettings.freezeNpcCombat && NpcRules.isNpcFaction(unit.playerId)) continue;
            if (!WeaponRules.screenWeapons(unit.type()).isEmpty()) screenShots(world, unit);
        }
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (AiDevSettings.freezeNpcCombat && NpcRules.isNpcFaction(unit.playerId)) continue;
            if (!WeaponRules.armed(unit.type())) {
                clearIllegalAttack(unit);
                continue;
            }
            if (UnitOrderSystem.canAcquire(unit)) acquireTarget(world, unit);
            if (unit.task == UnitTask.ATTACK) updateAttack(world, unit);
        }
        updateShots(world, dt);
    }

    void draw(Graphics2D g2, World world) {
        for (Unit unit : world.units.values()) drawUnitShieldBar(g2, unit);
        for (ProjectileShot shot : world.shots) drawMovingShot(g2, shot);
        for (Unit unit : world.units.values()) {
            if (unit.attackTarget.isBlank()) continue;
            if (!CombatTarget.enemy(world, unit, unit.attackTarget)) continue;
            double tx = CombatTarget.x(world, unit.attackTarget);
            double ty = CombatTarget.y(world, unit.attackTarget);
            double dist = Calc.distance(unit.x, unit.y, tx, ty);
            WeaponVolley volley = WeaponRules.directVolley(unit.type(), dist);
            WeaponType visual = volley.visualWeapon();
            if (visual == null) continue;
            float alpha = (float)(unit.weaponFlashTimer > 0 ? 0.85 : 0.18);
            drawShot(g2, unit.x, unit.y, tx, ty, visual, alpha);
        }
    }

    private void clearIllegalAttack(Unit unit) {
        if (unit.task != UnitTask.ATTACK && unit.attackTarget.isBlank()) return;
        unit.attackTarget = "";
        if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE;
    }

    private void acquireTarget(World world, Unit unit) {
        String best = "";
        double bestDist = Double.MAX_VALUE;
        double range = UnitOrderSystem.acquisitionRange(unit);
        for (Unit target : world.units.values()) {
            if (target.playerId.equals(unit.playerId) || target.hp <= 0) continue;
            double d = Calc.distance(unit.x, unit.y, target.x, target.y);
            if (d <= range && d < bestDist && UnitOrderSystem.canEngage(world, unit, target.x, target.y)) { best = CombatTarget.unit(target); bestDist = d; }
        }
        for (Base target : world.bases.values()) {
            if (target.playerId.equals(unit.playerId) || target.hp <= 0) continue;
            double d = Calc.distance(unit.x, unit.y, target.x, target.y);
            if (d <= range && d < bestDist && UnitOrderSystem.canEngage(world, unit, target.x, target.y)) { best = CombatTarget.base(target); bestDist = d; }
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
        if (!UnitOrderSystem.canEngage(world, unit, tx, ty)) {
            unit.attackTarget = "";
            unit.task = UnitTask.IDLE;
            return;
        }
        double dist = Calc.distance(unit.x, unit.y, tx, ty);
        double range = WeaponRules.maxRange(unit.type());
        if (range <= 0) {
            unit.attackTarget = "";
            unit.task = UnitTask.IDLE;
            return;
        }
        double approachRange = UnitOrderSystem.mayChase(unit) ? range * 0.92 : range;
        if (dist > approachRange) {
            if (!UnitOrderSystem.mayChase(unit)) {
                unit.attackTarget = "";
                unit.task = UnitTask.IDLE;
                return;
            }
            world.moveTowardOrbit(unit, tx, ty, range * 0.82);
            return;
        }
        if (unit.weaponCooldown > 0) return;
        WeaponVolley direct = WeaponRules.directVolley(unit.type(), dist);
        List<WeaponType> moving = WeaponRules.movingWeapons(unit.type(), dist);
        if (direct.damage() <= 0 && moving.isEmpty()) return;
        if (direct.damage() > 0) CombatTarget.damage(world, unit.attackTarget, direct.damage());
        double cooldown = direct.damage() > 0 ? direct.cooldownSeconds() : 0;
        for (WeaponType weapon : moving) {
            world.addShot(unit.playerId, weapon.id, unit.attackTarget, unit.x, unit.y);
            cooldown = Math.max(cooldown, weapon.cooldownSeconds);
        }
        unit.weaponCooldown = Math.max(0.2, cooldown);
        unit.weaponFlashTimer = 0.22;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        unit.heading = Math.atan2(ty - unit.y, tx - unit.x);
        WeaponType audible = direct.visualWeapon();
        if (audible == null && !moving.isEmpty()) audible = moving.get(0);
        ProceduralAudio.playWeaponFire(audible, dist);
    }

    private void updateShots(World world, double dt) {
        Iterator<ProjectileShot> it = world.shots.iterator();
        while (it.hasNext()) {
            ProjectileShot shot = it.next();
            WeaponType weapon = shot.weapon();
            if (weapon == null || !CombatTarget.alive(world, shot.targetKey)) { it.remove(); continue; }
            double tx = CombatTarget.x(world, shot.targetKey);
            double ty = CombatTarget.y(world, shot.targetKey);
            double dist = Calc.distance(shot.x, shot.y, tx, ty);
            double step = Math.max(1, weapon.shotSpeed * dt);
            shot.lastX = shot.x;
            shot.lastY = shot.y;
            if (dist <= step + 10) {
                ProceduralAudio.playWeaponImpact(weapon);
                CombatTarget.damage(world, shot.targetKey, weapon.damage * hitScale(world, shot.targetKey, weapon));
                it.remove();
                continue;
            }
            double a = Math.atan2(ty - shot.y, tx - shot.x);
            shot.x += Math.cos(a) * step;
            shot.y += Math.sin(a) * step;
        }
    }

    private void screenShots(World world, Unit unit) {
        if (unit.weaponCooldown > 0) return;
        List<WeaponType> screens = WeaponRules.screenWeapons(unit.type());
        if (screens.isEmpty()) return;
        WeaponType screen = screens.get(0);
        ProjectileShot best = null;
        double bestDist = Double.MAX_VALUE;
        for (ProjectileShot shot : world.shots) {
            WeaponType weapon = shot.weapon();
            if (weapon == null || !weapon.stoppable || shot.ownerId.equals(unit.playerId)) continue;
            double d = Calc.distance(unit.x, unit.y, shot.x, shot.y);
            if (d <= screen.range && d < bestDist) { best = shot; bestDist = d; }
        }
        if (best == null) return;
        world.shots.remove(best);
        ProceduralAudio.playWeaponFire(screen, bestDist);
        ProceduralAudio.playWeaponImpact(best.weapon());
        unit.weaponCooldown = screen.cooldownSeconds;
        unit.weaponFlashTimer = 0.12;
        unit.heading = Math.atan2(best.y - unit.y, best.x - unit.x);
    }

    private double hitScale(World world, String key, WeaponType weapon) {
        Base base = CombatTarget.base(world, key);
        if (base != null) return 1.35;
        Unit unit = CombatTarget.unit(world, key);
        if (unit == null) return 1.0;
        double sizeFactor = 0.75 + unit.type().size.scale * 0.20;
        double speedFactor = Math.max(0.35, Math.min(1.15, 1.2 - unit.type().speed / 360.0));
        double trackedSpeed = weapon.tracking + (1.0 - weapon.tracking) * speedFactor;
        return Math.max(0.45, Math.min(1.65, sizeFactor * trackedSpeed));
    }

    private void drawUnitShieldBar(Graphics2D g2, Unit unit) {
        if (unit.type().maxShield <= 0) return;
        int w = 36;
        int x = (int)unit.x - w / 2;
        int y = (int)unit.y - 36;
        g2.setColor(new Color(20,20,20));
        g2.fillRect(x, y, w, 4);
        g2.setColor(new Color(80,180,255));
        g2.fillRect(x, y, (int)(w * Math.max(0, unit.shield) / Math.max(1, unit.type().maxShield)), 4);
    }

    private void drawMovingShot(Graphics2D g2, ProjectileShot shot) {
        WeaponType weapon = shot.weapon();
        if (weapon == null) return;
        Graphics2D s = (Graphics2D) g2.create();
        Color c = weapon.color;
        s.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 230));
        s.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        s.draw(new Line2D.Double(shot.lastX, shot.lastY, shot.x, shot.y));
        int r = weapon.damage >= 200 ? 7 : weapon.damage >= 100 ? 5 : 4;
        s.fillOval((int)shot.x - r, (int)shot.y - r, r * 2, r * 2);
        s.dispose();
    }

    private void drawShot(Graphics2D s, double x1, double y1, double x2, double y2, WeaponType weapon, float alpha) {
        Graphics2D shot = (Graphics2D) s.create();
        Color c = weapon.color;
        shot.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(25, Math.min(230, (int)(alpha * 255)))));
        if (weapon.beam) {
            shot.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            shot.draw(new Line2D.Double(x1, y1, x2, y2));
        } else {
            shot.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{18f, 12f}, 0));
            shot.draw(new Line2D.Double(x1, y1, x2, y2));
            double mx = x1 + (x2 - x1) * 0.62;
            double my = y1 + (y2 - y1) * 0.62;
            shot.fillOval((int)mx - 4, (int)my - 4, 8, 8);
        }
        shot.dispose();
    }
}
