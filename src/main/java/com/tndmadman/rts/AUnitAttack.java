package com.tndmadman.rts;

final class AUnitAttack {
    private AUnitAttack() { }

    static boolean apply(World world, AttackCommand c) {
        if (world == null || c == null) return false;
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u == null || ProductionSystem.refitReserved(world, u.key())
                || !VisibilityRules.targetVisible(world, c.playerId(), c.targetKey())
                || !CombatTarget.enemy(world, u, c.targetKey()) || !WeaponRules.armed(u)) return false;
        LogisticsRouteSystem.releaseForManualCommand(world, u.key());
        UnitCommandQueueSystem.legacyReplace(world, u);
        CombatPolicySystem.markExplicitAttack(world, u);
        u.issueAttack(c.targetKey());
        return true;
    }
}
