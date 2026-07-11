package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        anchorToNearbyStation(world, miner);
        ensureMiningAnchor(miner);
        ResourceNode node = leastAssignedLocalResource(world, miner, -1, assignmentCounts(world, miner.playerId));
        if (node != null) {
            miner.startAutoHarvest(node.id);
            world.status = miner.type().name + " found " + node.name + " nearby.";
        }
    }

    private void anchorToNearbyStation(World world, Unit miner) {
        if (miner.miningAnchorSet) return;
        Base base = world.nearestBase(miner.playerId, miner.x, miner.y);
        if (base == null) return;
        double idleStationRange = base.type().unloadRange + 170;
        if (Calc.distance(miner.x, miner.y, base.x, base.y) <= idleStationRange) miner.setMiningAnchor(base.x, base.y);
    }

    private boolean retargetLocalMiner(World world, Unit miner, ResourceNode oldNode) {
        if (!canLocalMine(miner)) return false;
        ensureMiningAnchor(miner);
        ResourceNode best = leastAssignedLocalResource(world, miner, oldNode == null ? -1 : oldNode.id, assignmentCounts(world, miner.playerId));
        if (best == null) return false;
        miner.startAutoHarvest(best.id);
        world.status = miner.type().name + " redirected itself to " + best.name + ".";
        return true;
    }

    private ResourceNode leastAssignedLocalResource(World world, Unit miner, int skippedResourceId, Map<Integer,Integer> assignedCounts) {
        if (!canLocalMine(miner)) return null;
        ensureMiningAnchor(miner);
        ResourceNode best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.id == skippedResourceId || !miner.type().harvestKinds.contains(node.kind)) continue;
            if (Calc.distance(miner.miningAnchorX, miner.miningAnchorY, node.x, node.y) > miner.type().scoutRange * SystemModifierRules.sensorRange(world)) continue;
            int assigned = assignedCounts.getOrDefault(node.id, 0);
            double d = Calc.distance(miner.x, miner.y, node.x, node.y);
            if (betterResource(node, assigned, d, best, bestAssigned, bestDist)) {
                best = node;
                bestAssigned = assigned;
                bestDist = d;
            }
        }
        return best;
    }

    private boolean retargetFromScout(World world, Unit miner, ResourceNode oldNode) {
        if (oldNode == null) return false;
        Map<Integer,Integer> assignedCounts = assignmentCounts(world, miner.playerId);
        ResourceNode best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Unit scout : world.units.values()) {
            if (!scout.playerId.equals(miner.playerId) || scout.type().scoutRange <= 0 || canLocalMine(scout)) continue;
            for (ResourceNode node : world.resources) {
                if (!node.active || node.id == oldNode.id || !miner.type().harvestKinds.contains(node.kind)) continue;
                if (Calc.distance(scout.x, scout.y, node.x, node.y) > scout.type().scoutRange * SystemModifierRules.sensorRange(world)) continue;
                int assigned = assignedCounts.getOrDefault(node.id, 0);
                double d = Calc.distance(miner.x, miner.y, node.x, node.y);
                if (betterResource(node, assigned, d, best, bestAssigned, bestDist)) {
                    best = node;
                    bestAssigned = assigned;
                    bestDist = d;
                }
            }
        }
        if (best == null) return false;
        miner.setMiningAnchor(best.x, best.y);
        miner.startAutoHarvest(best.id);
        world.status = "Scout redirected " + miner.type().name + " to " + best.name + ".";
        return true;
    }

    private void dispatchWorkers(World world, Unit scout) {
        int limit = Math.max(0, scout.type().scoutDispatchLimit);
        if (limit <= 0) return;

        List<ResourceNode> visibleResources = scoutVisibleResources(world, scout);
        if (visibleResources.isEmpty()) return;

        List<Unit> idleWorkers = idleHarvestWorkers(world, scout.playerId);
        if (idleWorkers.isEmpty()) return;

        Map<Integer,Integer> assignedCounts = assignmentCounts(world, scout.playerId);
        int sent = 0;
        while (sent < limit && !idleWorkers.isEmpty()) {
            DispatchChoice choice = bestDispatchChoice(idleWorkers, visibleResources, assignedCounts);
            if (choice == null) break;
            choice.worker.setMiningAnchor(choice.node.x, choice.node.y);
            choice.worker.startAutoHarvest(choice.node.id);
            assignedCounts.merge(choice.node.id, 1, Integer::sum);
            idleWorkers.remove(choice.worker);
            sent++;
        }
    }

    private List<ResourceNode> scoutVisibleResources(World world, Unit scout) {
        List<ResourceNode> visible = new ArrayList<>();
        for (ResourceNode node : world.resources) {
            if (!node.active) continue;
            if (Calc.distance(scout.x, scout.y, node.x, node.y) <= scout.type().scoutRange * SystemModifierRules.sensorRange(world)) visible.add(node);
        }
        return visible;
    }

    private List<Unit> idleHarvestWorkers(World world, String playerId) {
        List<Unit> workers = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(playerId) || unit.task != UnitTask.IDLE) continue;
            if (unit.type().harvestKinds.isEmpty() || unit.freeCargo() <= 0.05) continue;
            workers.add(unit);
        }
        return workers;
    }

    private DispatchChoice bestDispatchChoice(List<Unit> workers, List<ResourceNode> visibleResources, Map<Integer,Integer> assignedCounts) {
        DispatchChoice best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Unit worker : workers) {
            for (ResourceNode node : visibleResources) {
                if (!worker.type().harvestKinds.contains(node.kind)) continue;
                int assigned = assignedCounts.getOrDefault(node.id, 0);
                double d = Calc.distance(worker.x, worker.y, node.x, node.y);
                if (betterDispatch(node, worker, assigned, d, best, bestAssigned, bestDist)) {
                    best = new DispatchChoice(node, worker);
                    bestAssigned = assigned;
                    bestDist = d;
                }
            }
        }
        return best;
    }

    private boolean betterDispatch(ResourceNode node, Unit worker, int assigned, double distance, DispatchChoice best, int bestAssigned, double bestDistance) {
        if (best == null) return true;
        if (assigned != bestAssigned) return assigned < bestAssigned;
        if (Math.abs(distance - bestDistance) > 0.001) return distance < bestDistance;
        if (node.id != best.node.id) return node.id < best.node.id;
        return worker.unitId < best.worker.unitId;
    }

    private Map<Integer,Integer> assignmentCounts(World world, String playerId) {
        Map<Integer,Integer> counts = new HashMap<>();
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(playerId)) continue;
            if (unit.task != UnitTask.AUTO_HARVEST && unit.task != UnitTask.RETURN_TO_STATION) continue;
            ResourceNode node = world.findResource(unit.automationResourceId);
            if (node == null || !node.active) continue;
            counts.merge(node.id, 1, Integer::sum);
        }
        return counts;
    }

    private boolean betterResource(ResourceNode node, int assigned, double distance, ResourceNode best, int bestAssigned, double bestDistance) {
        if (best == null) return true;
        if (assigned != bestAssigned) return assigned < bestAssigned;
        if (Math.abs(distance - bestDistance) > 0.001) return distance < bestDistance;
        return node.id < best.id;
    }

    private boolean canLocalMine(Unit unit) {
        return unit.type().scoutRange > 0 && !unit.type().harvestKinds.isEmpty();
    }

    private void ensureMiningAnchor(Unit miner) {
        if (!miner.miningAnchorSet) miner.setMiningAnchor(miner.x, miner.y);
    }

    private record DispatchChoice(ResourceNode node, Unit worker) { }
}
