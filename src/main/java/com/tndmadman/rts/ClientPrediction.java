package com.tndmadman.rts;

final class ClientPrediction {
    private ClientPrediction() { }

    static void update(World world, double dt) {
        for (Unit unit : world.units.values()) {
            predictTarget(world, unit, dt);
            unit.updatePosition(dt, world.width, world.height);
        }
    }

    private static void predictTarget(World world, Unit unit, double dt) {
        if (unit.task == UnitTask.AUTO_HARVEST) {
            ResourceNode node = world.findResource(unit.automationResourceId);
            if (node == null || !node.active) return;
            double orbit = node.radius + unit.type().orbitRadius;
            if (MiningBeam.visible(unit, node)) world.orbitAround(unit, node.x, node.y, orbit, dt, 0.7);
            else world.moveTowardOrbit(unit, node.x, node.y, orbit);
            return;
        }
        if (unit.task == UnitTask.IDLE) {
            Base base = world.nearestBase(unit.playerId, unit.x, unit.y);
            if (base != null && Calc.distance(unit.x, unit.y, base.x, base.y) < base.type().unloadRange + 170) {
                world.orbitAround(unit, base.x, base.y, unit.type().idleOrbitRadius, dt, 0.35);
            }
        }
    }
}
