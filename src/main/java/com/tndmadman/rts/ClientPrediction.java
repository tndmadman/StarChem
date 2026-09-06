package com.tndmadman.rts;

import java.util.Iterator;

final class ClientPrediction {
    private ClientPrediction() { }

    static void update(World world, double dt) {
        // A zero-dt prediction pass is intentional: callers use it to refresh local
        // attack/orbit targets immediately without advancing simulation time. Preserve
        // that behavior while still rejecting invalid/negative time steps.
        if (world == null || !Double.isFinite(dt) || dt < 0) return;
        ShipModuleRules.beginUpdateCycle(world);
        long moduleNanos = 0;
        long movementNanos = 0;
        double movementScale = SystemModifierRules.movementSpeed(world);
        for (Unit unit : world.units.values()) {
            unit.wormholeCooldown = Math.max(0, unit.wormholeCooldown - dt);
            if (PlayerRegistry.isLocal(unit.playerId)) AttackRangeRules.predictAttackMovement(world, unit);
            long started = System.nanoTime();
            ShipModuleRules.update(world, unit, dt);
            moduleNanos += System.nanoTime() - started;
            started = System.nanoTime();
            unit.updatePosition(dt * movementScale, world.width, world.height);
            movementNanos += System.nanoTime() - started;
        }
        PerformanceTrace.recordModules(moduleNanos);
        PerformanceTrace.recordMovement(movementNanos);
        // The render and proximity-query paths can now consume a current, shared O(N) index.
        WorldSpatialIndex.rebuild(world);
        updateExplosions(world, dt);
    }

    private static void updateExplosions(World world, double dt) {
        Iterator<ExplosionEffect> it = world.explosions.iterator();
        while (it.hasNext()) if (!it.next().update(dt)) it.remove();
    }
}
