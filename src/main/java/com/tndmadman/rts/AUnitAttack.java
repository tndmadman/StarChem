package com.tndmadman.rts;

final class AUnitAttack {
    private AUnitAttack() { }

    static void apply(World world, AttackCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u != null && !ProductionSystem.refitLocked(world, u.key())
                && VisibilityRules.targetVisible(world, c.playerId(), c.targetKey())
                && CombatTarget.enemy(world, u, c.targetKey()) && WeaponRules.armed(u)) {
            u.issueAttack(c.targetKey());
        }
    }
}
