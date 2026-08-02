package com.tndmadman.rts;

import java.util.Iterator;

final class ClientPrediction {
    private ClientPrediction() { }

    static void update(World world, double dt) {
        for (Unit unit : world.units.values()) {
            unit.wormholeCooldown = Math.max(0, unit.wormholeCooldown - dt);
            if (PlayerRegistry.isLocal(unit.playerId)) predictTarget(world, unit, dt);
            ShipModuleRules.update(world, unit, dt);
            unit.updatePosition(dt * SystemModifierRules.movementSpeed(world), world.width, world.height);
        }
        updateExplosions(world, dt);
    }

    private static void updateExplosions(World world, double dt) {
        Iterator<ExplosionEffect> it = world.explosions.iterator();
        while (it.hasNext()) if (!it.next().update(dt)) it.remove();
    }

    private static void predictTarget(World world, Unit unit, double dt) {
        if (unit.task != UnitTask.ATTACK) return;
        if (!CombatTarget.enemy(world, unit, unit.attackTarget)) return;
        double range = WeaponRules.maxRange(unit);
        if (range <= 0) return;
        double tx = CombatTarget.x(world, unit.attackTarget);
        double ty = CombatTarget.y(world, unit.attackTarget);
        if (Calc.distance(unit.x, unit.y, tx, ty) > range * 0.92) world.moveTowardOrbit(unit, tx, ty, range * 0.82);
    }
}
