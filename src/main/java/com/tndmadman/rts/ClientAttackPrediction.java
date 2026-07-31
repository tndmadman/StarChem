package com.tndmadman.rts;

final class ClientAttackPrediction {
    private ClientAttackPrediction() { }

    static void apply(World world, Unit unit) {
        if (unit.task != UnitTask.ATTACK) return;
        if (!CombatTarget.enemy(world, unit, unit.attackTarget)) return;
        double range = WeaponRules.maxRange(unit);
        if (range <= 0) return;
        double tx = CombatTarget.x(world, unit.attackTarget);
        double ty = CombatTarget.y(world, unit.attackTarget);
        if (Calc.distance(unit.x, unit.y, tx, ty) > range * 0.92) world.moveTowardOrbit(unit, tx, ty, range * 0.82);
    }
}
