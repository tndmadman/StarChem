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
        AiDevSettings settings = world.aiDevSettings;
        if (settings.disableAttacks) return;
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (settings.freezeNpcCombat && NpcRules.isNpcFaction(unit.playerId)) continue;
            if (ProductionSystem.refitReserved(world, unit.key())) continue;
            if (CombatPolicySystem.pointDefenseAllowed(world, unit)
                    && !WeaponRules.screenWeapons(world, unit).isEmpty()) screenShots(world, unit);
        }
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (settings.freezeNpcCombat && NpcRules.isNpcFaction(unit.playerId)) continue;
            if (ProductionSystem.refitReserved(world, unit.key())) {
                clearIllegalAttack(world, unit);
                continue;
            }
            if (!WeaponRules.armed(world, unit)) {
                clearIllegalAttack(world, unit);
                continue;
            }
            if (UnitOrderSystem.canAcquire(unit) && CombatPolicySystem.mayAutoAcquire(world, unit)) {
                acquireTarget(world, unit);
            }
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
            WeaponVolley volley = WeaponRules.directVolley(world, unit, AttackRangeRules.definitionDistance(world, dist));
            WeaponType visual = volley.visualWeapon();
            if (visual == null) continue;
            float alpha = (float)(unit.weaponFlashTimer > 0 ? 0.85 : 0.18);
            drawShot(g2, unit.x, unit.y, tx, ty, visual, alpha);
        }
    }

    private void clearIllegalAttack(World world, Unit unit) {
        if (unit.task != UnitTask.ATTACK && unit.attackTarget.isBlank()) return;
        unit.attackTarget = "";
        if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE;
        CombatPolicySystem.clearAttackIntent(world, unit);
    }

    private void acquireTarget(World world, Unit unit) {
        String best = "";
        double bestScore = Double.POSITIVE_INFINITY;
        for (Unit target : world.units.values()) {
            if (target == unit || target.hp <= 0) continue;
            String key = CombatTarget.unit(target);
            double score = CombatPolicySystem.scoreTarget(world, unit, key);
            if (better(score, key, bestScore, best)) {
                best = key;
                bestScore = score;
            }
        }
        for (Base target : world.bases.values()) {
            if (target.hp <= 0) continue;
            String key = CombatTarget.base(target);
            double score = CombatPolicySystem.scoreTarget(world, unit, key);
            if (better(score, key, bestScore, best)) {
                best = key;
                bestScore = score;
            }
        }
        if (!best.isBlank()) {
            CombatPolicySystem.markAutomaticAttack(world, unit);
            unit.attackTarget = best;
            unit.task = UnitTask.ATTACK;
        }
    }

    private boolean better(double score, String key, double bestScore, String bestKey) {
        if (!Double.isFinite(score)) return false;
        if (score < bestScore - 0.000001) return true;
        return Math.abs(score - bestScore) <= 0.000001
                && (bestKey == null || bestKey.isBlank() || key.compareTo(bestKey) < 0);
    }

    private void updateAttack(World world, Unit unit) {
        if (!CombatPolicySystem.retainCurrentAttack(world, unit)) {
            clearIllegalAttack(world, unit);
            return;
        }
        double tx = CombatTarget.x(world, unit.attackTarget);
        double ty = CombatTarget.y(world, unit.attackTarget);
        double dist = Calc.distance(unit.x, unit.y, tx, ty);
        double effectiveRange = AttackRangeRules.effectiveWeaponRange(world, unit);
        double approachRange = AttackRangeRules.approachThreshold(world, unit);
        double orbitRange = AttackRangeRules.orbitRange(world, unit);
        if (effectiveRange <= 0 || approachRange <= 0 || orbitRange <= 0) {
            clearIllegalAttack(world, unit);
            return;
        }
        if (!CombatPolicySystem.mayFire(world, unit)) {
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            return;
        }
        if (dist > approachRange) {
            if (!CombatPolicySystem.mayPursue(world, unit, tx, ty)) {
                if (CombatPolicySystem.holdExplicitTargetWithoutPursuit(world, unit)) return;
                clearIllegalAttack(world, unit);
                return;
            }
            world.moveTowardOrbit(unit, tx, ty, orbitRange);
            return;
        }
        if (unit.weaponCooldown > 0) return;
        double effectiveDistance = AttackRangeRules.definitionDistance(world, dist);
        WeaponVolley direct = WeaponRules.directVolley(world, unit, effectiveDistance);
        List<WeaponType> moving = WeaponRules.movingWeapons(world, unit, effectiveDistance);
        if (direct.damage() <= 0 && moving.isEmpty()) return;
        if (direct.damage() > 0) CombatTarget.damage(world, unit.playerId, unit.attackTarget, direct.damage());
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
        SystemAudio.playWeaponFire(world, audible, dist);
    }

    private void updateShots(World world, double dt) {
        Iterator<ProjectileShot> it = world.shots.iterator();
        while (it.hasNext()) {
            ProjectileShot shot = it.next();
            WeaponType weapon = shot.weapon();
            if (weapon == null || !CombatTarget.alive(world, shot.targetKey)
                    || !CombatTarget.mayDamage(world, shot.ownerId, shot.targetKey)) { it.remove(); continue; }
            double tx = CombatTarget.x(world, shot.targetKey);
            double ty = CombatTarget.y(world, shot.targetKey);
            double dist = Calc.distance(shot.x, shot.y, tx, ty);
            double step = Math.max(1, weapon.shotSpeed * dt);
            shot.lastX = shot.x;
            shot.lastY = shot.y;
            if (dist <= step + 10) {
                SystemAudio.playWeaponImpact(world, weapon);
                CombatTarget.damage(world, shot.ownerId, shot.targetKey,
                        weapon.damage * hitScale(world, shot.targetKey, weapon));
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
        List<WeaponType> screens = WeaponRules.screenWeapons(world, unit);
        if (screens.isEmpty()) return;
        WeaponType screen = screens.get(0);
        ProjectileShot best = null;
        double bestDist = Double.MAX_VALUE;
        double bestScore = Double.POSITIVE_INFINITY;
        for (ProjectileShot shot : world.shots) {
            WeaponType weapon = shot.weapon();
            if (weapon == null || !weapon.stoppable
                    || !DiplomacySystem.hostile(world, unit.playerId, shot.ownerId)) continue;
            double d = Calc.distance(unit.x, unit.y, shot.x, shot.y);
            if (d > AttackRangeRules.effectiveRange(world, screen.range)) continue;
            double score = CombatPolicySystem.screenScore(world, unit, shot, d);
            if (score < bestScore) {
                best = shot;
                bestDist = d;
                bestScore = score;
            }
        }
        if (best == null) return;
        world.shots.remove(best);
        SystemAudio.playWeaponFire(world, screen, bestDist);
        SystemAudio.playWeaponImpact(world, best.weapon());
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
