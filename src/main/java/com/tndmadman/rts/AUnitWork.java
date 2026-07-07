package com.tndmadman.rts;

final class AUnitWork {
    private AUnitWork() { }

    static void apply(World world, HarvestCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u != null) u.startAutoHarvest(c.resourceId());
    }
}
