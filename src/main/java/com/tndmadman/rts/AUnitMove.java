package com.tndmadman.rts;

final class AUnitMove {
    private AUnitMove() { }

    static void apply(World world, MoveCommand c) {
        Unit u = world.units.get(Unit.key(c.playerId(), c.unitId()));
        if (u != null) u.moveTo(c.x(), c.y());
    }
}
