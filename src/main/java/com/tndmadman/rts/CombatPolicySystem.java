package com.tndmadman.rts;

enum CombatStance {
    PASSIVE("Passive"),
    DEFENSIVE("Defensive"),
    AGGRESSIVE("Aggressive"),
    HOLD_FIRE("Hold Fire");

    final String label;

    CombatStance(String label) {
        this.label = label;
    }
}

enum TargetPriorityPolicy {
    NEAREST_THREAT("Nearest Threat"),
    PROTECT_ASSIGNED_TARGET("Protect Assigned"),
    SCREENING("Screening"),
    COMBAT_FIRST("Combat First"),
    LOGISTICS_FIRST("Workers / Logistics First"),
    STRUCTURES_FIRST("Structures First"),
    STRUCTURES_LAST("Structures Last");

    final String label;

    TargetPriorityPolicy(String label) {
        this.label = label;
    }
}

enum AttackIntentSource { NONE, AUTOMATIC, EXPLICIT }

/**
 * Server-authoritative combat policy layered on top of tactical orders.
 *
 * Tactical orders continue to own geometry. This class only decides whether an autonomous
 * attack may start/continue, how targets are ranked, and how far an automatic engagement may
 * pull a ship away from its current order or engagement anchor.
 */
final class CombatPolicySystem {
    private static final double MAX_AGGRESSIVE_IDLE_LEASH = 1_400;
    private static final double MAX_DEFENSIVE_IDLE_LEASH = 700;

    private CombatPolicySystem() { }

    static CombatStance stance(World world, Unit unit) {
        return unit == null ? CombatStance.AGGRESSIVE
                : UnitCommandQueueSystem.combatStance(world, unit.key());
    }

    static TargetPriorityPolicy priority(World world, Unit unit) {
        return unit == null ? TargetPriorityPolicy.NEAREST_THREAT
                : UnitCommandQueueSystem.targetPriority(world, unit.key());
    }

    static AttackIntentSource attackIntent(World world, Unit unit) {
        if (unit == null || unit.task != UnitTask.ATTACK || unit.attackTarget == null || unit.attackTarget.isBlank()) {
            return AttackIntentSource.NONE;
        }
        AttackIntentSource source = UnitCommandQueueSystem.attackIntent(world, unit.key());
        if (source != AttackIntentSource.NONE) return source;
        // Direct attack() callers predate combat-policy metadata and represent deliberate
        // strategic/order intent. Only WeaponSystem autonomous acquisition is marked AUTOMATIC.
        UnitCommandQueueSystem.setAttackIntent(world, unit, AttackIntentSource.EXPLICIT, false);
        return AttackIntentSource.EXPLICIT;
    }

    static void markExplicitAttack(World world, Unit unit) {
        if (world == null || unit == null) return;
        UnitCommandQueueSystem.setAttackIntent(world, unit, AttackIntentSource.EXPLICIT, false);
    }

    static void markAutomaticAttack(World world, Unit unit) {
        if (world == null || unit == null) return;
        UnitCommandQueueSystem.setAttackIntent(world, unit, AttackIntentSource.AUTOMATIC, true);
    }

    static void clearAttackIntent(World world, Unit unit) {
        if (world == null || unit == null) return;
        UnitCommandQueueSystem.clearAttackIntent(world, unit.key());
    }

    static boolean mayAutoAcquire(World world, Unit unit) {
        CombatStance stance = stance(world, unit);
        return stance == CombatStance.AGGRESSIVE || stance == CombatStance.DEFENSIVE;
    }

    static double acquisitionRange(World world, Unit unit) {
        if (world == null || unit == null || !mayAutoAcquire(world, unit)) return 0;
        double sensorScale = Math.max(0, SystemModifierRules.sensorRange(world));
        double weaponRange = Math.max(0, AttackRangeRules.effectiveWeaponRange(world, unit));
        double base = stance(world, unit) == CombatStance.DEFENSIVE
                ? weaponRange * 1.10 : UnitOrderSystem.acquisitionRange(world, unit);
        return Math.max(0, base * sensorScale);
    }

    static boolean eligibleAutomaticTarget(World world, Unit unit, String targetKey) {
        if (world == null || unit == null || targetKey == null || targetKey.isBlank()) return false;
        if (!mayAutoAcquire(world, unit)
                || !CombatTarget.enemy(world, unit, targetKey)
                || !VisibilityRules.targetVisible(world, unit.playerId, targetKey)) return false;
        double tx = CombatTarget.x(world, targetKey);
        double ty = CombatTarget.y(world, targetKey);
        if (!UnitOrderSystem.canEngage(world, unit, tx, ty)) return false;
        double distance = Calc.distance(unit.x, unit.y, tx, ty);
        if (distance > acquisitionRange(world, unit)) return false;
        if (stance(world, unit) != CombatStance.DEFENSIVE) return true;
        if (threatensProtectedTarget(world, unit, targetKey)) return true;
        return distance <= Math.max(1, AttackRangeRules.effectiveWeaponRange(world, unit));
    }

    static double scoreTarget(World world, Unit unit, String targetKey) {
        if (!eligibleAutomaticTarget(world, unit, targetKey)) return Double.POSITIVE_INFINITY;
        double tx = CombatTarget.x(world, targetKey);
        double ty = CombatTarget.y(world, targetKey);
        double distance = Calc.distance(unit.x, unit.y, tx, ty);
        if (!Double.isFinite(distance)) return Double.POSITIVE_INFINITY;

        Unit targetUnit = CombatTarget.unit(world, targetKey);
        Base targetBase = CombatTarget.base(world, targetKey);
        boolean structure = targetBase != null;
        boolean combat = targetUnit != null && WeaponRules.armed(world, targetUnit);
        boolean logistics = targetUnit != null && logisticsOrWorker(targetUnit, combat);
        boolean smallCraft = targetUnit != null && targetUnit.type().size.scale <= 1.0;
        boolean threat = threatensProtectedTarget(world, unit, targetKey);

        // Keep the existing preference for actively firing contacts and high-value EW structures
        // inside whichever policy bucket the player selected.
        if (targetUnit != null && targetUnit.weaponFlashTimer > 0) distance *= 0.88;
        if (targetBase != null) {
            if (IntelWarfareSystem.isJammer(targetBase.typeId)) distance *= 0.45;
            else if (IntelWarfareSystem.isRadar(targetBase.typeId)) distance *= 0.62;
        }

        int bucket = switch (priority(world, unit)) {
            case NEAREST_THREAT -> threat ? 0 : 1;
            case PROTECT_ASSIGNED_TARGET -> threatToAssignedTarget(world, unit, targetKey) ? 0 : threat ? 1 : 2;
            case SCREENING -> smallCraft ? 0 : combat ? 1 : structure ? 2 : 3;
            case COMBAT_FIRST -> combat ? 0 : structure ? 1 : logistics ? 2 : 3;
            case LOGISTICS_FIRST -> logistics ? 0 : combat ? 1 : structure ? 2 : 3;
            case STRUCTURES_FIRST -> structure ? 0 : 1;
            case STRUCTURES_LAST -> structure ? 1 : 0;
        };
        // Bucket dominates distance. Stable-key tie breaking is handled by WeaponSystem.
        return bucket * 10_000_000.0 + distance;
    }

    static boolean retainCurrentAttack(World world, Unit unit) {
        if (world == null || unit == null || unit.attackTarget == null || unit.attackTarget.isBlank()) return false;
        if (!CombatTarget.enemy(world, unit, unit.attackTarget)
                || !VisibilityRules.targetVisible(world, unit.playerId, unit.attackTarget)) return false;
        AttackIntentSource source = attackIntent(world, unit);
        if (source == AttackIntentSource.EXPLICIT) return true;
        if (source != AttackIntentSource.AUTOMATIC || !mayAutoAcquire(world, unit)) return false;
        return automaticTargetInsideLeash(world, unit, unit.attackTarget);
    }

    static boolean mayFire(World world, Unit unit) {
        return world != null && unit != null && stance(world, unit) != CombatStance.HOLD_FIRE;
    }

    static boolean mayPursue(World world, Unit unit, double targetX, double targetY) {
        if (world == null || unit == null || !GameplayCommandNumbers.finite(targetX, targetY)) return false;
        CombatStance stance = stance(world, unit);
        if (stance == CombatStance.HOLD_FIRE) return false;
        AttackIntentSource source = attackIntent(world, unit);
        if (source == AttackIntentSource.EXPLICIT) return true;
        if (source != AttackIntentSource.AUTOMATIC || !mayAutoAcquire(world, unit)) return false;
        if (!UnitOrderSystem.mayChase(unit) || !UnitOrderSystem.canEngage(world, unit, targetX, targetY)) return false;
        if (unit.orderType != UnitOrderType.NONE) return true;

        if (!UnitCommandQueueSystem.engagementAnchorSet(world, unit.key())) return false;
        double anchorX = UnitCommandQueueSystem.engagementAnchorX(world, unit.key());
        double anchorY = UnitCommandQueueSystem.engagementAnchorY(world, unit.key());
        double weaponRange = Math.max(1, AttackRangeRules.effectiveWeaponRange(world, unit));
        double leash = stance == CombatStance.DEFENSIVE
                ? Math.min(MAX_DEFENSIVE_IDLE_LEASH, Math.max(240, weaponRange * 1.25))
                : Math.min(MAX_AGGRESSIVE_IDLE_LEASH, Math.max(700, weaponRange * 2.5));
        return Calc.distance(anchorX, anchorY, targetX, targetY) <= leash;
    }

    static boolean holdExplicitTargetWithoutPursuit(World world, Unit unit) {
        return stance(world, unit) == CombatStance.HOLD_FIRE
                && attackIntent(world, unit) == AttackIntentSource.EXPLICIT;
    }

    static boolean pointDefenseAllowed(World world, Unit unit) {
        // Hold Fire intentionally suppresses offensive weapons only. Point defense remains active.
        return world != null && unit != null;
    }

    static double screenScore(World world, Unit unit, ProjectileShot shot, double distance) {
        if (world == null || unit == null || shot == null || !Double.isFinite(distance)) {
            return Double.POSITIVE_INFINITY;
        }
        if (priority(world, unit) != TargetPriorityPolicy.SCREENING) return distance;
        String protectedKey = protectedTarget(world, unit);
        String selfKey = CombatTarget.unit(unit);
        int bucket = !protectedKey.isBlank() && protectedKey.equals(shot.targetKey) ? 0
                : selfKey.equals(shot.targetKey) ? 1 : 2;
        return bucket * 10_000_000.0 + distance;
    }

    static String protectedTarget(World world, Unit unit) {
        if (world == null || unit == null) return "";
        if (unit.orderType != UnitOrderType.GUARD && unit.orderType != UnitOrderType.ESCORT) return "";
        String key = unit.orderTarget == null ? "" : unit.orderTarget.trim();
        if (key.isBlank() || !CombatTarget.alive(world, key)) return "";
        String owner = CombatTarget.owner(world, key);
        return DiplomacySystem.allied(world, unit.playerId, owner) ? key : "";
    }

    private static boolean automaticTargetInsideLeash(World world, Unit unit, String targetKey) {
        double tx = CombatTarget.x(world, targetKey);
        double ty = CombatTarget.y(world, targetKey);
        if (!UnitOrderSystem.canEngage(world, unit, tx, ty)) return false;
        if (unit.orderType != UnitOrderType.NONE) return true;
        return mayPursue(world, unit, tx, ty)
                || Calc.distance(unit.x, unit.y, tx, ty) <= Math.max(1, AttackRangeRules.effectiveWeaponRange(world, unit));
    }

    private static boolean threatensProtectedTarget(World world, Unit unit, String targetKey) {
        Unit hostile = CombatTarget.unit(world, targetKey);
        if (hostile == null || hostile.attackTarget == null || hostile.attackTarget.isBlank()) return false;
        if (CombatTarget.unit(unit).equals(hostile.attackTarget)) return true;
        String protectedKey = protectedTarget(world, unit);
        return !protectedKey.isBlank() && protectedKey.equals(hostile.attackTarget);
    }

    private static boolean threatToAssignedTarget(World world, Unit unit, String targetKey) {
        String protectedKey = protectedTarget(world, unit);
        if (protectedKey.isBlank()) return false;
        Unit hostile = CombatTarget.unit(world, targetKey);
        return hostile != null && protectedKey.equals(hostile.attackTarget);
    }

    private static boolean logisticsOrWorker(Unit unit, boolean combat) {
        if (unit == null) return false;
        ShipType type = unit.type();
        return !type.harvestKinds.isEmpty()
                || type.cargoCapacity >= 300
                || type.tractorBeamCount > 0
                || type.baseBuilder
                || type.scoutRange > 0 && !combat;
    }
}
