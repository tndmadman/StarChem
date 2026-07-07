package com.tndmadman.rts;

final class AUnitWork {
    private AUnitWork() { }

    static void apply(World world, HarvestCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u != null) {
            AWorkAnchor.apply(world, u, c.resourceId());
            u.startAutoHarvest(c.resourceId());
        }
    }
}
