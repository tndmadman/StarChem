package com.tndmadman.rts;

import java.awt.*;
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
    private static final Stroke PASSIVE_BEAM_STROKE =
            new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke PASSIVE_PULSE_STROKE =
            new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{10f, 14f}, 0);
    private static final Composite PASSIVE_FIRE_COMPOSITE =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16f);

    private final Map<Unit, Double> acquisitionCooldowns = new WeakHashMap<>();
    private final List<Unit> unitCandidates = new ArrayList<>();
    private final List<Base> baseCandidates = new ArrayList<>();
    private final List<ProjectileShot> shotCandidates = new ArrayList<>();
    private final List<Unit> visibleUnits = new ArrayList<>();
    private final CombatVfxSystem vfx = new CombatVfxSystem();

    void update(World world, double dt) {
        long weaponStarted = System.nanoTime();
        vfx.update(dt);
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

        // Shields are represented in the aggregate SelectionSummaryHud. Rendering one
        // bar per ship was a large source of dense-fleet overdraw and text/UI clutter.
        for (ProjectileShot shot : world.shots) {
            if (!RenderCulling.segmentVisible(g2, shot.lastX, shot.lastY, shot.x, shot.y, 28)) continue;
            vfx.drawProjectile(g2, shot);
        }
        for (Unit unit : unitsToDraw) {
            if (unit.attackTarget.isBlank()) continue;
            if (!CombatTarget.enemy(world, unit, unit.attackTarget)) continue;
            double tx = CombatTarget.x(world, unit.attackTarget);
            double ty = CombatTarget.y(world, unit.attackTarget);
            if (!RenderCulling.segmentVisible(g2, unit.x, unit.y, tx, ty, 30)) continue;
            double dx = tx - unit.x;
            double dy = ty - unit.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            WeaponVolley volley = WeaponRules.directVolley(world, unit, AttackRangeRules.definitionDistance(world, dist));
            WeaponType visual = volley.visualWeapon();
            if (visual == null) continue;
            if (unit.weaponFlashTimer > 0) vfx.drawDirectFire(g2, unit, tx, ty, visual);
            else drawPassiveFireCue(g2, unit, tx, ty, visual);
        }
        vfx.draw(g2);
        PerformanceTrace.recordWeaponDraw(System.nanoTime() - started);
    }

    private void drawPassiveFireCue(Graphics2D g2, Unit unit, double tx, double ty, WeaponType weapon) {
        if (SelectionRenderPolicy.scale(g2) < 0.12) return;
        ShipVfxProfile profile = ShipVfxProfile.forType(unit.type());
        int mount = Math.floorMod(unit.unitId * 31 + (weapon.id == null ? 0 : weapon.id.hashCode()),
                Math.max(1, profile.muzzleCount()));
        double c = Math.cos(unit.heading);
        double s = Math.sin(unit.heading);
        double localY = profile.muzzleY(mount);
        double mx = unit.x + c * profile.muzzleX - s * localY;
        double my = unit.y + s * profile.muzzleX + c * localY;

        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();
        Composite oldComposite = g2.getComposite();
        g2.setComposite(PASSIVE_FIRE_COMPOSITE);
        g2.setColor(weapon.color);
        if (WeaponVfxStyle.forWeapon(weapon) == WeaponVfxStyle.BEAM) {
            g2.setStroke(PASSIVE_BEAM_STROKE);
        } else {
            g2.setStroke(PASSIVE_PULSE_STROKE);
        }
        g2.drawLine((int)Math.round(mx), (int)Math.round(my), (int)Math.round(tx), (int)Math.round(ty));
        g2.setComposite(oldComposite);
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
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

        WeaponType directVisual = direct.visualWeapon();
        if (direct.damage() > 0) {
            double shieldBefore = vfx.targetShield(world, unit.attackTarget);
            double hpBefore = vfx.targetHp(world, unit.attackTarget);
            if (CombatTarget.damage(world, unit.playerId, unit.attackTarget, direct.damage())) {
                vfx.damageApplied(world, unit.attackTarget, unit.x, unit.y, directVisual, shieldBefore, hpBefore);
            }
        }
        double cooldown = direct.damage() > 0 ? direct.cooldownSeconds() : 0;
        unit.heading = Math.atan2(ty - unit.y, tx - unit.x);
        for (WeaponType weapon : moving) {
            world.addShot(unit.playerId, weapon.id, unit.attackTarget, unit.x, unit.y);
            vfx.movingWeaponFired(unit, weapon, tx, ty);
            cooldown = Math.max(cooldown, weapon.cooldownSeconds);
        }
        unit.weaponCooldown = Math.max(0.2, cooldown);
        unit.weaponFlashTimer = 0.22;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        WeaponType audible = directVisual;
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
                double shieldBefore = vfx.targetShield(world, shot.targetKey);
                double hpBefore = vfx.targetHp(world, shot.targetKey);
                if (CombatTarget.damage(world, shot.ownerId, shot.targetKey,
                        weapon.damage * hitScale(world, shot.targetKey, weapon))) {
                    vfx.damageApplied(world, shot.targetKey, shot.lastX, shot.lastY,
                            weapon, shieldBefore, hpBefore);
                }
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
        unit.weaponCooldown = screen.cooldownSeconds;
        unit.weaponFlashTimer = 0.12;
        unit.heading = Math.atan2(best.y - unit.y, best.x - unit.x);
        vfx.pointDefense(unit, screen, best.x, best.y);
        SystemAudio.playWeaponFire(world, screen, bestDist);
        SystemAudio.playWeaponImpact(world, best.weapon());
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
}
