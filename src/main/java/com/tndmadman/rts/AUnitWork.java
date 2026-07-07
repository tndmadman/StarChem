package com.tndmadman.rts;

final class AUnitWork {
    private AUnitWork() { }

    static void apply(World world, HarvestCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        ResourceNode node = world.findResource(c.resourceId());
        ResourceNetDebug.hostWorkOrder(world, c, u, node);
        if (u != null) {
            AWorkAnchor.apply(world, u, c.resourceId());
            u.startAutoHarvest(c.resourceId());
        }
    }
}
