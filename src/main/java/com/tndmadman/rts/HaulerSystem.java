package com.tndmadman.rts;

final class HaulerSystem {
    void update(World world, Unit hauler, double dt) {
        if (!MobileDepot.isHauler(hauler)) return;
        if (hauler.cargoUsed() > 0.05) {
            sendToBase(world, hauler);
            return;
        }
        Unit depot = loadedFreighter(world, hauler);
        if (depot == null) return;
        if (MobileDepot.drainTo(hauler, depot, dt)) {
            sendToBase(world, hauler);
            return;
        }
        world.moveTowardOrbit(hauler, depot.x, depot.y, MobileDepot.range(depot) * 0.55);
        hauler.task = UnitTask.MOVE;
    }

    private Unit loadedFreighter(World world, Unit hauler) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!MobileDepot.isDepot(unit) || unit.cargoUsed() <= 0.05) continue;
            if (!unit.playerId.equals(hauler.playerId)) continue;
            double d = Calc.distance(hauler.x, hauler.y, unit.x, unit.y);
            if (d < bestDist) { best = unit; bestDist = d; }
        }
        return best;
    }

    private void sendToBase(World world, Unit hauler) {
        Base base = world.nearestBase(hauler.playerId, hauler.x, hauler.y);
        if (base == null) return;
        hauler.task = UnitTask.RETURN_TO_STATION;
        world.moveTowardOrbit(hauler, base.x, base.y, base.type().unloadRange * 0.55);
    }
}
