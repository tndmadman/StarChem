package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class WeaponSystem {
    private static final double AUTO_ACQUIRE_INTERVAL_SECONDS = 0.16;
    private static final BasicStroke MOVING_SHOT_STROKE =
            new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke BEAM_STROKE =
            new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke PROJECTILE_GUIDE_STROKE =
            new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{18f, 12f}, 0);

    private final Map<Unit, Double> acquisitionCooldowns = new WeakHashMap<>();
    private final List<Unit> unitCandidates = new ArrayList<>();
    private final List<Base> baseCandidates = new ArrayList<>();
    private final List<ProjectileShot> shotCandidates = new ArrayList<>();
    private final List<Unit> visibleUnits = new ArrayList<>();

    void update(World world, double dt) {
        long weaponStarted = System.nanoTime();
        ShieldSystem.update(world, dt);
        for (Unit unit : world.units.values()) {
            unit.weaponCooldown = Math.max(0, unit.weaponCooldown - dt);
            unit.weaponFlashTimer = Math.max(0, unit.weaponFlashTimer - dt);
        }
        AiDevSettings settings = world.aiDevSettings;
        if (settings.disableAttacks) {
            PerformanceTrace.recordWeapons(System.nanoTime() - weaponStarted);
            return;
        }

        // Movement has already completed when WeaponSystem runs. Build one shared grid for every
        // combat proximity query instead of making each ship rescan the complete world.
        WorldSpatialIndex spatial = WorldSpatialIndex.rebuild(world);
        Set<ProjectileShot> consumedShots = Collections.newSetFromMap(new IdentityHashMap<>());

        long pointDefenseStarted = System.nanoTime();
        int pointDefenseCandidateCount = 0;
        for (Unit unit : world.units.values()) {
            if (settings.freezeNpcCombat && NpcRules.isNpcFaction(unit.playerId)) continue;
            if (ProductionSystem.refitReserved(world, unit.key())) continue;
            if (!CombatPolicySystem.pointDefenseAllowed(world, unit)) continue;
            List<WeaponType> screens = WeaponRules.screenWeapons(world, unit);
            if (screens.isEmpty()) continue;
            pointDefenseCandidateCount += screenShots(world, spatial, unit, screens.get(0), consumedShots);
        }
        PerformanceTrace.recordPointDefense(System.nanoTime() - pointDefenseStarted, pointDefenseCandidateCount);

        for (Unit unit : world.units.values()) {
            if (settings.freezeNpcCombat && NpcRules.isNpcFaction(unit.playerId)) continue;
            if (ProductionSystem.refitReserved(world, unit.key())) {
                clearIllegalAttack(world, unit);
                continue;
            }
            if (!WeaponRules.armed(world, unit)) {
                clearIllegalAttack(world, unit);
                continue;
            }
            if (UnitOrderSystem.canAcquire(unit) && CombatPolicySystem.mayAutoAcquire(world, unit)
                    && acquisitionDue(unit, dt)) {
                acquireTarget(world, spatial, unit);
            }
            if (unit.task == UnitTask.ATTACK) updateAttack(world, unit);
        }

        long projectileStarted = System.nanoTime();
        updateShots(world, dt);
        PerformanceTrace.recordProjectiles(System.nanoTime() - projectileStarted);
        PerformanceTrace.recordWeapons(System.nanoTime() - weaponStarted);
    }

    void draw(Graphics2D g2, World world) {
        long started = System.nanoTime();
        WorldSpatialIndex spatial = WorldSpatialIndex.forWorld(world);
        Rectangle clip = g2.getClipBounds();
        Iterable<Unit> unitsToDraw = world.units.values();
        if (clip != null && spatial.matches(world)) {
            unitsToDraw = spatial.unitsIn(clip, 160, visibleUnits);
        }

        for (Unit unit : unitsToDraw) {
            if (!RenderCulling.visible(g2, unit.x, unit.y, 90)) continue;
            drawUnitShieldBar(g2, unit);
        }
        for (ProjectileShot shot : world.shots) {
            if (!RenderCulling.segmentVisible(g2, shot.lastX, shot.lastY, shot.x, shot.y, 18)) continue;
            drawMovingShot(g2, shot);
        }
        for (Unit unit : unitsToDraw) {
            if (unit.attackTarget.isBlank()) continue;
            if (!CombatTarget.enemy(world, unit, unit.attackTarget)) continue;
            double tx = CombatTarget.x(world, unit.attackTarget);
            double ty = CombatTarget.y(world, unit.attackTarget);
            if (!RenderCulling.segmentVisible(g2, unit.x, unit.y, tx, ty, 24)) continue;
            double dx = tx - unit.x;
            double dy = ty - unit.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            WeaponVolley volley = WeaponRules.directVolley(world, unit, AttackRangeRules.definitionDistance(world, dist));
            WeaponType visual = volley.visualWeapon();
            if (visual == null) continue;
            float alpha = (float)(unit.weaponFlashTimer > 0 ? 0.85 : 0.18);
            drawShot(g2, unit.x, unit.y, tx, ty, visual, alpha);
        }
        PerformanceTrace.recordWeaponDraw(System.nanoTime() - started);
    }

    private boolean acquisitionDue(Unit unit, double dt) {
        Double remaining = acquisitionCooldowns.get(unit);
        if (remaining == null) {
            // Deterministic phase offset spreads a newly-created fleet over the acquisition interval.
            int phase = Math.floorMod(unit.unitId * 31 + unit.playerId.hashCode(), 1000);
            remaining = AUTO_ACQUIRE_INTERVAL_SECONDS * phase / 1000.0;
        }
        remaining -= dt;
        if (remaining > 0) {
            acquisitionCooldowns.put(unit, remaining);
            return false;
        }
        acquisitionCooldowns.put(unit, AUTO_ACQUIRE_INTERVAL_SECONDS);
        return true;
    }

    private void clearIllegalAttack(World world, Unit unit) {
        if (unit.task != UnitTask.ATTACK && unit.attackTarget.isBlank()) return;
        unit.attackTarget = "";
        if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE;
        CombatPolicySystem.clearAttackIntent(world, unit);
    }

    private void acquireTarget(World world, WorldSpatialIndex spatial, Unit unit) {
        long started = System.nanoTime();
        double range = CombatPolicySystem.acquisitionRange(world, unit);
        if (!(range > 0) || !Double.isFinite(range)) {
            PerformanceTrace.recordAcquisition(System.nanoTime() - started, 0);
            return;
        }

        String best = "";
        double bestScore = Double.POSITIVE_INFINITY;
        spatial.unitsWithin(unit.x, unit.y, range, unitCandidates);
        spatial.basesWithin(unit.x, unit.y, range, baseCandidates);
        int candidates = unitCandidates.size() + baseCandidates.size();

        for (Unit target : unitCandidates) {
            if (target == unit || target.hp <= 0) continue;
            String key = CombatTarget.unit(target);
            double score = CombatPolicySystem.scoreTarget(world, unit, key);
            if (better(score, key, bestScore, best)) {
                best = key;
                bestScore = score;
            }
        }
        for (Base target : baseCandidates) {
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
        PerformanceTrace.recordAcquisition(System.nanoTime() - started, candidates);
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
        double dx = tx - unit.x;
        double dy = ty - unit.y;
        double dist = Math.sqrt(dx * dx + dy * dy);
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
            double dx = tx - shot.x;
            double dy = ty - shot.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
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
            double a = Math.atan2(dy, dx);
            shot.x += Math.cos(a) * step;
            shot.y += Math.sin(a) * step;
        }
    }

    private int screenShots(World world, WorldSpatialIndex spatial, Unit unit, WeaponType screen,
                            Set<ProjectileShot> consumedShots) {
        if (unit.weaponCooldown > 0 || screen == null) return 0;
        double range = AttackRangeRules.effectiveRange(world, screen.range);
        spatial.shotsWithin(unit.x, unit.y, range, shotCandidates);
        int candidateCount = shotCandidates.size();
        ProjectileShot best = null;
        double bestDist = Double.MAX_VALUE;
        double bestScore = Double.POSITIVE_INFINITY;
        for (ProjectileShot shot : shotCandidates) {
            if (consumedShots.contains(shot)) continue;
            WeaponType weapon = shot.weapon();
            if (weapon == null || !weapon.stoppable
                    || !DiplomacySystem.hostile(world, unit.playerId, shot.ownerId)) continue;
            double dx = shot.x - unit.x;
            double dy = shot.y - unit.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            double score = CombatPolicySystem.screenScore(world, unit, shot, d);
            if (score < bestScore) {
                best = shot;
                bestDist = d;
                bestScore = score;
            }
        }
        if (best == null) return candidateCount;
        consumedShots.add(best);
        world.shots.remove(best);
        SystemAudio.playWeaponFire(world, screen, bestDist);
        SystemAudio.playWeaponImpact(world, best.weapon());
        unit.weaponCooldown = screen.cooldownSeconds;
        unit.weaponFlashTimer = 0.12;
        unit.heading = Math.atan2(best.y - unit.y, best.x - unit.x);
        return candidateCount;
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
        // At fleet-scale zoom the bar costs more pixels than the ship and adds no useful information.
        double scale = Math.abs(g2.getTransform().getScaleX());
        if (!unit.selected && scale < 0.42) return;
        if (!unit.selected && unit.shield >= unit.type().maxShield * 0.995 && scale < 0.8) return;
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
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        Color c = weapon.color;
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 230));
        g2.setStroke(MOVING_SHOT_STROKE);
        g2.drawLine((int)shot.lastX, (int)shot.lastY, (int)shot.x, (int)shot.y);
        int r = weapon.damage >= 200 ? 7 : weapon.damage >= 100 ? 5 : 4;
        g2.fillOval((int)shot.x - r, (int)shot.y - r, r * 2, r * 2);
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    private void drawShot(Graphics2D g2, double x1, double y1, double x2, double y2, WeaponType weapon, float alpha) {
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        Color c = weapon.color;
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(25, Math.min(230, (int)(alpha * 255)))));
        if (weapon.beam) {
            g2.setStroke(BEAM_STROKE);
            g2.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
        } else {
            g2.setStroke(PROJECTILE_GUIDE_STROKE);
            g2.drawLine((int)x1, (int)y1, (int)x2, (int)y2);
            double mx = x1 + (x2 - x1) * 0.62;
            double my = y1 + (y2 - y1) * 0.62;
            g2.fillOval((int)mx - 4, (int)my - 4, 8, 8);
        }
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }
}
