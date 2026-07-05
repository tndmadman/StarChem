package com.tndmadman.rts;

final class ScoutSystem {
    void update(World world) {
        for (Unit scout : world.units.values()) {
            if (scout.type().scoutRange <= 0) continue;
            if (canLocalMine(scout)) updateLocalMiner(world, scout);
            else dispatchWorkers(world, scout);
        }
    }

    boolean retargetAfterDepletion(World world, Unit miner, ResourceNode oldNode) {
        if (miner.freeCargo() <= 0.05) return false;
        if (retargetLocalMiner(world, miner, oldNode)) return true;
        return retargetFromScout(world, miner, oldNode);
    }

    private void updateLocalMiner(World world, Unit miner) {
        if (miner.task != UnitTask.IDLE || miner.freeCargo() <= 0.05) return;
        ensureMiningAnchor(miner);
        ResourceNode node = nearestLocalResource(world, miner, -1);
        if (node != null) {
            miner.startAutoHarvest(node.id);
            world.status = miner.type().name + " found " + node.name + " nearby.";
        }
    }

    private boolean retargetLocalMiner(World world, Unit miner, ResourceNode oldNode) {
        if (!canLocalMine(miner)) return false;
        ensureMiningAnchor(miner);
        ResourceNode best = nearestLocalResource(world, miner, oldNode == null ? -1 : oldNode.id);
        if (best == null) return false;
        miner.startAutoHarvest(best.id);
        world.status = miner.type().name + " redirected itself to " + best.name + ".";
        return true;
    }

    private ResourceNode nearestLocalResource(World world, Unit miner, int skippedResourceId) {
        if (!canLocalMine(miner)) return null;
        ensureMiningAnchor(miner);
        ResourceNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.id == skippedResourceId || !miner.type().harvestKinds.contains(node.kind)) continue;
            if (Calc.distance(miner.miningAnchorX, miner.miningAnchorY, node.x, node.y) > miner.type().scoutRange) continue;
            double d = Calc.distance(miner.x, miner.y, node.x, node.y);
            if (d < bestDist) { best = node; bestDist = d; }
        }
        return best;
    }

    private boolean retargetFromScout(World world, Unit miner, ResourceNode oldNode) {
        if (oldNode == null) return false;
        ResourceNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit scout : world.units.values()) {
            if (!scout.playerId.equals(miner.playerId) || scout.type().scoutRange <= 0 || canLocalMine(scout)) continue;
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

    private void dispatchWorkers(World world, Unit scout) {
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

    private boolean canLocalMine(Unit unit) {
        return unit.type().scoutRange > 0 && !unit.type().harvestKinds.isEmpty();
    }

    private void ensureMiningAnchor(Unit miner) {
        if (!miner.miningAnchorSet) miner.setMiningAnchor(miner.x, miner.y);
    }
}
