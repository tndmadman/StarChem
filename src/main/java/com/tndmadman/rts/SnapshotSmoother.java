package com.tndmadman.rts;

final class SnapshotSmoother {
    private SnapshotSmoother() { }

    static void apply(Unit unit, UnitState state) {
        apply(unit, state, false);
    }

    static void apply(Unit unit, UnitState state, boolean forceLocalAuthority) {
        boolean local = PlayerRegistry.isLocal(unit.playerId);
        UnitTask serverTask = safeTask(state.task(), unit.task);
        boolean snapped = correctPosition(unit, state, local, forceLocalAuthority);
        applyTarget(unit, state, serverTask, local, forceLocalAuthority);
        if (!local || snapped || forceLocalAuthority) unit.heading = state.heading();
        unit.shipTypeId = state.shipTypeId();
        unit.task = serverTask;
        unit.automationResourceId = state.resourceId();
        unit.basePackageType = state.packageType();
        unit.hp = state.hp();
        unit.shield = state.shield();
        unit.attackTarget = state.attackTarget();
        unit.weaponFlashTimer = state.weaponFlashTimer();
        CargoCodec.readInto(state.cargo(), unit.inventory);
    }

    private static boolean correctPosition(Unit unit, UnitState state, boolean local, boolean forceLocalAuthority) {
        if (local && forceLocalAuthority) {
            unit.x = state.x();
            unit.y = state.y();
            return true;
        }
        double error = Calc.distance(unit.x, unit.y, state.x(), state.y());
        double deadZone = local ? 34 : 24;
        double snap = local ? 320 : 360;
        double blend = local ? 0.06 : 0.08;
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

    private static void applyTarget(Unit unit, UnitState state, UnitTask serverTask, boolean local, boolean forceLocalAuthority) {
        unit.targetX = state.targetX();
        unit.targetY = state.targetY();
    }

    private static UnitTask safeTask(String task, UnitTask fallback) {
        try { return UnitTask.valueOf(task); }
        catch (Exception ignored) { return fallback; }
    }
}
