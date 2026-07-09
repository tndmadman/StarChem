package com.tndmadman.rts;

final class AUnitAttack {
    private AUnitAttack() { }

    static void apply(World world, AttackCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u != null && CombatTarget.enemy(world, u, c.targetKey()) && WeaponRules.armed(u.type())) u.attack(c.targetKey());
    }
}
