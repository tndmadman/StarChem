package com.tndmadman.rts;

final class SnapshotSmoother {
    private SnapshotSmoother() { }

    static void apply(Unit unit, UnitState state) {
        boolean local = PlayerRegistry.isLocal(unit.playerId);
        UnitTask serverTask = safeTask(state.task(), unit.task);
        boolean snapped = correctPosition(unit, state, local);
        applyTarget(unit, state, serverTask, local);
        if (!local || snapped) unit.heading = state.heading();
        unit.shipTypeId = state.shipTypeId();
        unit.task = serverTask;
        unit.automationResourceId = state.resourceId();
        unit.basePackageType = state.packageType();
        CargoCodec.readInto(state.cargo(), unit.inventory);
    }

    private static boolean correctPosition(Unit unit, UnitState state, boolean local) {
        double error = Calc.distance(unit.x, unit.y, state.x(), state.y());
        double deadZone = local ? 34 : 10;
        double snap = local ? 320 : 220;
        double blend = local ? 0.06 : 0.18;
        if (error <= deadZone) return false;
        if (error > snap) {
            unit.x = state.x();
            unit.y = state.y();
            return true;
        }
        unit.x = Calc.lerp(unit.x, state.x(), blend);
        unit.y = Calc.lerp(unit.y, state.y(), blend);
        return false;
    }

    private static void applyTarget(Unit unit, UnitState state, UnitTask serverTask, boolean local) {
        if (local && (serverTask == UnitTask.AUTO_HARVEST || serverTask == UnitTask.IDLE)) return;
        unit.targetX = state.targetX();
        unit.targetY = state.targetY();
    }

    private static UnitTask safeTask(String task, UnitTask fallback) {
        try { return UnitTask.valueOf(task); }
        catch (Exception ignored) { return fallback; }
    }
}
