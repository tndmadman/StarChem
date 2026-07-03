package com.tndmadman.rts;

final class SnapshotSmoother {
    private SnapshotSmoother() { }

    static void apply(Unit unit, UnitState state) {
        double error = Calc.distance(unit.x, unit.y, state.x(), state.y());
        boolean local = PlayerRegistry.isLocal(unit.playerId);
        double snap = local ? 260 : 180;
        double blend = local ? 0.12 : 0.32;
        if (error > snap) {
            unit.x = state.x();
            unit.y = state.y();
        } else {
            unit.x = Calc.lerp(unit.x, state.x(), blend);
            unit.y = Calc.lerp(unit.y, state.y(), blend);
        }
        unit.targetX = state.targetX();
        unit.targetY = state.targetY();
        unit.heading = state.heading();
        unit.shipTypeId = state.shipTypeId();
        unit.task = UnitTask.valueOf(state.task());
        unit.automationResourceId = state.resourceId();
        unit.basePackageType = state.packageType();
        CargoCodec.readInto(state.cargo(), unit.inventory);
    }
}
