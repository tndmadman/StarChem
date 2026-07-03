package com.tndmadman.rts;

final class ScoutSystem {
    void update(World world) {
        for (Unit scout : world.units.values()) {
            if (scout.type().scoutRange <= 0) continue;
            int sent = 0;
            for (ResourceNode node : world.resources) {
                if (!node.active) continue;
                if (Calc.distance(scout.x, scout.y, node.x, node.y) > scout.type().scoutRange) continue;
                Unit worker = findIdleWorker(world, scout.playerId, node);
                if (worker == null) continue;
                worker.startAutoHarvest(node.id);
                sent++;
                if (sent >= scout.type().scoutDispatchLimit) break;
            }
        }
    }

    boolean retargetAfterDepletion(World world, Unit miner, ResourceNode oldNode) {
        if (miner.freeCargo() <= 0.05) return false;
        ResourceNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit scout : world.units.values()) {
            if (!scout.playerId.equals(miner.playerId) || scout.type().scoutRange <= 0) continue;
            for (ResourceNode node : world.resources) {
                if (!node.active || node.id == oldNode.id || !miner.type().harvestKinds.contains(node.kind)) continue;
                if (Calc.distance(scout.x, scout.y, node.x, node.y) > scout.type().scoutRange) continue;
                double d = Calc.distance(miner.x, miner.y, node.x, node.y);
                if (d < bestDist) { best = node; bestDist = d; }
            }
        }
        if (best == null) return false;
        miner.startAutoHarvest(best.id);
        world.status = "Scout redirected " + miner.type().name + " to " + best.name + ".";
        return true;
    }

    private Unit findIdleWorker(World world, String playerId, ResourceNode node) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(playerId) || unit.task != UnitTask.IDLE) continue;
            if (!unit.type().harvestKinds.contains(node.kind) || unit.freeCargo() <= 0.05) continue;
            double d = Calc.distance(unit.x, unit.y, node.x, node.y);
            if (d < bestDist) { best = unit; bestDist = d; }
        }
        return best;
    }
}
