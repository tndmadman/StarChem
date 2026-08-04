package com.tndmadman.rts;

/** Shared authoritative and predicted attack-distance calculations. */
final class AttackRangeRules {
    private static final double CHASE_THRESHOLD_SCALE = 0.92;
    private static final double ORBIT_SCALE = 0.82;

    private AttackRangeRules() { }

    static double effectiveWeaponRange(World world, Unit unit) {
        if (unit == null || unit.hp <= 0) return 0;
        double fitted = WeaponRules.maxRange(unit);
        double modifier = SystemModifierRules.weaponRange(world);
        if (!Double.isFinite(fitted) || fitted <= 0
                || !Double.isFinite(modifier) || modifier <= 0) return 0;
        return fitted * modifier;
    }

    static double preferredAttackRange(World world, Unit unit) {
        double effective = effectiveWeaponRange(world, unit);
        if (effective <= 0) return 0;
        double preferred = ShipModuleRules.preferredApproachRange(unit, effective);
        return Double.isFinite(preferred) && preferred > 0 ? preferred : 0;
    }

    static double approachThreshold(World world, Unit unit) {
        double preferred = preferredAttackRange(world, unit);
        if (preferred <= 0) return 0;
        return UnitOrderSystem.mayChase(unit) ? preferred * CHASE_THRESHOLD_SCALE : preferred;
    }

    static double orbitRange(World world, Unit unit) {
        double preferred = preferredAttackRange(world, unit);
        return preferred <= 0 ? 0 : preferred * ORBIT_SCALE;
    }

    static void predictAttackMovement(World world, Unit unit) {
        if (world == null || unit == null || unit.task != UnitTask.ATTACK
                || !CombatTarget.enemy(world, unit, unit.attackTarget)) return;
        double threshold = approachThreshold(world, unit);
        double orbit = orbitRange(world, unit);
        if (threshold <= 0 || orbit <= 0 || !UnitOrderSystem.mayChase(unit)) return;
        double tx = CombatTarget.x(world, unit.attackTarget);
        double ty = CombatTarget.y(world, unit.attackTarget);
        if (Calc.distance(unit.x, unit.y, tx, ty) > threshold) {
            world.moveTowardOrbit(unit, tx, ty, orbit);
        }
    }
}
