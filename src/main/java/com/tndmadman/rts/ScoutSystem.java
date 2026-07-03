package com.tndmadman.rts;

final class ScoutSystem {
    void update(World world) {
        for (Unit scout : world.units.values()) {
            if (scout.type().scoutRange <= 0) continue;
            int sent = 0;
            for (ResourceNode node : world.resources) {
                if (!node.active) continue;
                if (Calc.distance(scout.x, scout.y, node.x, node.y) > scout.type().scoutRange) continue;
                Unit worker = findIdleWorker(world, node);
                if (worker == null) continue;
                worker.startAutoHarvest(node.id);
                sent++;
                if (sent >= scout.type().scoutDispatchLimit) break;
            }
        }
    }

    private Unit findIdleWorker(World world, ResourceNode node) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (unit.task != UnitTask.IDLE) continue;
            if (!unit.type().harvestKinds.contains(node.kind)) continue;
            double d = Calc.distance(unit.x, unit.y, node.x, node.y);
            if (d < bestDist) {
                best = unit;
                bestDist = d;
            }
        }
        return best;
    }
}
