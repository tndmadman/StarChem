package com.tndmadman.rts;

import java.util.Iterator;

final class ClientPrediction {
    private ClientPrediction() { }

    static void update(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt <= 0) return;
        ShipModuleRules.beginUpdateCycle(world);
        for (Unit unit : world.units.values()) {
            unit.wormholeCooldown = Math.max(0, unit.wormholeCooldown - dt);
            if (PlayerRegistry.isLocal(unit.playerId)) AttackRangeRules.predictAttackMovement(world, unit);
            ShipModuleRules.update(world, unit, dt);
            unit.updatePosition(dt * SystemModifierRules.movementSpeed(world), world.width, world.height);
        }
        updateExplosions(world, dt);
    }

    private static void updateExplosions(World world, double dt) {
        Iterator<ExplosionEffect> it = world.explosions.iterator();
        while (it.hasNext()) if (!it.next().update(dt)) it.remove();
    }
}
