package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maintains local miner search behavior, radar dispatch, and the authoritative intel tick. */
final class ScoutSystem {
    private final Map<String,Double> radarModePhases = new HashMap<>();
    private double lastIntelUpdateTime = Double.NaN;
    private double lastIntelDelta = 0.05;

    void update(World world) {
        updateIntel(world);
        for (Unit miner : world.units.values()) {
            if (canLocalMine(miner)) updateLocalMiner(world, miner);
        }
        for (Base radar : world.bases.values()) {
            if (radar.hp <= 0 || !IntelWarfareSystem.isRadar(radar.typeId)) continue;
            adaptRadarMode(world, radar);
            dispatchWorkers(world, radar);
        }
    }

    boolean retargetAfterDepletion(World world, Unit miner, ResourceNode oldNode) {
        if (miner.freeCargo() <= 0.05) return false;
        if (retargetLocalMiner(world, miner, oldNode)) return true;
        return retargetFromRadar(world, miner, oldNode);
    }

    private void updateIntel(World world) {
        double now = world.systemTime();
        double dt = Double.isFinite(lastIntelUpdateTime) ? now - lastIntelUpdateTime : 0.05;
        lastIntelUpdateTime = now;
        if (!Double.isFinite(dt) || dt <= 0 || dt > 1.0) dt = 0.05;
        lastIntelDelta = dt;
        IntelWarfareSystem.update(world, dt);
    }

    private void adaptRadarMode(World world, Base radar) {
        String key = world.activeSystemId() + '|' + radar.id;
        double phase = radarModePhases.getOrDefault(key, 0.0) + lastIntelDelta;
        radarModePhases.put(key, phase);

        boolean detected = false;
        boolean identifiedThreat = false;
        for (Unit enemy : world.units.values()) {
            if (enemy == null || enemy.hp <= 0 || IntelWarfareSystem.allied(world, radar.playerId, enemy.playerId)) continue;
            IntelWarfareSystem.DetectionStage stage = VisibilityRules.unitStage(world, radar.playerId, enemy);
            detected |= stage.atLeast(IntelWarfareSystem.DetectionStage.CONTACT);
            identifiedThreat |= stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
        }
        for (Base enemy : world.bases.values()) {
            if (enemy == null || enemy.hp <= 0 || IntelWarfareSystem.allied(world, radar.playerId, enemy.playerId)) continue;
            IntelWarfareSystem.DetectionStage stage = VisibilityRules.baseStage(world, radar.playerId, enemy);
            detected |= stage.atLeast(IntelWarfareSystem.DetectionStage.CONTACT);
            identifiedThreat |= stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
        }

        IntelWarfareSystem.RadarMode desired;
        if (identifiedThreat) desired = IntelWarfareSystem.RadarMode.FOCUSED;
        else if (detected) desired = IntelWarfareSystem.RadarMode.ACTIVE;
        else desired = phase % 8.0 < 1.5 ? IntelWarfareSystem.RadarMode.ACTIVE
                : IntelWarfareSystem.RadarMode.PASSIVE;
        setModeSilently(world, radar, desired);
    }

    private void setModeSilently(World world, Base radar, IntelWarfareSystem.RadarMode desired) {
        String status = world.status;
        for (int i = 0; i < 3 && IntelWarfareSystem.radarMode(world, radar) != desired; i++) {
            IntelWarfareSystem.cycleRadarMode(world, radar, radar.playerId);
        }
        world.status = status;
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
        ResourceNode best = leastAssignedLocalResource(world, miner, oldNode == null ? -1 : oldNode.id,
                assignmentCounts(world, miner.playerId));
        if (best == null) return false;
        miner.startAutoHarvest(best.id);
        world.status = miner.type().name + " redirected itself to " + best.name + ".";
        return true;
    }

    private ResourceNode leastAssignedLocalResource(World world, Unit miner, int skippedResourceId,
                                                     Map<Integer,Integer> assignedCounts) {
        if (!canLocalMine(miner)) return null;
        ensureMiningAnchor(miner);
        ResourceNode best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.id == skippedResourceId || !miner.type().harvestKinds.contains(node.kind)) continue;
            if (Calc.distance(miner.miningAnchorX, miner.miningAnchorY, node.x, node.y)
                    > miner.type().scoutRange * SystemModifierRules.sensorRange(world)) continue;
            int assigned = assignedCounts.getOrDefault(node.id, 0);
            double distance = Calc.distance(miner.x, miner.y, node.x, node.y);
            if (betterResource(node, assigned, distance, best, bestAssigned, bestDist)) {
                best = node;
                bestAssigned = assigned;
                bestDist = distance;
            }
        }
        return best;
    }

    private boolean retargetFromRadar(World world, Unit miner, ResourceNode oldNode) {
        if (oldNode == null) return false;
        Map<Integer,Integer> assignedCounts = assignmentCounts(world, miner.playerId);
        ResourceNode best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Base radar : world.bases.values()) {
            if (!miner.playerId.equals(radar.playerId) || radar.hp <= 0 || !IntelWarfareSystem.isRadar(radar.typeId)) continue;
            double range = VisibilityRules.baseSensorRange(world, radar);
            for (ResourceNode node : world.resources) {
                if (!node.active || node.id == oldNode.id || !miner.type().harvestKinds.contains(node.kind)) continue;
                if (Calc.distance(radar.x, radar.y, node.x, node.y) > range) continue;
                if (!VisibilityRules.resourceStage(world, miner.playerId, node)
                        .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) continue;
                int assigned = assignedCounts.getOrDefault(node.id, 0);
                double distance = Calc.distance(miner.x, miner.y, node.x, node.y);
                if (betterResource(node, assigned, distance, best, bestAssigned, bestDist)) {
                    best = node;
                    bestAssigned = assigned;
                    bestDist = distance;
                }
            }
        }
        if (best == null) return false;
        miner.setMiningAnchor(best.x, best.y);
        miner.startAutoHarvest(best.id);
        world.status = "Radar network redirected " + miner.type().name + " to " + best.name + ".";
        return true;
    }

    private void dispatchWorkers(World world, Base radar) {
        int limit = IntelWarfareSystem.dispatchLimit(radar.typeId);
        if (limit <= 0) return;

        List<ResourceNode> visibleResources = radarVisibleResources(world, radar);
        if (visibleResources.isEmpty()) return;

        Map<Integer,Integer> assignedCounts = assignmentCounts(world, radar.playerId);
        int assignedInsideRadar = 0;
        for (ResourceNode node : visibleResources) assignedInsideRadar += assignedCounts.getOrDefault(node.id, 0);
        int availableSlots = Math.max(0, limit - assignedInsideRadar);
        if (availableSlots <= 0) return;

        List<Unit> idleWorkers = idleHarvestWorkers(world, radar.playerId);
        int sent = 0;
        while (sent < availableSlots && !idleWorkers.isEmpty()) {
            DispatchChoice choice = bestDispatchChoice(idleWorkers, visibleResources, assignedCounts);
            if (choice == null) break;
            choice.worker.setMiningAnchor(choice.node.x, choice.node.y);
            choice.worker.startAutoHarvest(choice.node.id);
            assignedCounts.merge(choice.node.id, 1, Integer::sum);
            idleWorkers.remove(choice.worker);
            sent++;
        }
        if (sent > 0) {
            world.status = radar.type().name + " dispatched " + sent + " worker" + (sent == 1 ? "" : "s") + ".";
        }
    }

    private List<ResourceNode> radarVisibleResources(World world, Base radar) {
        List<ResourceNode> visible = new ArrayList<>();
        double range = VisibilityRules.baseSensorRange(world, radar);
        for (ResourceNode node : world.resources) {
            if (node.active && Calc.distance(radar.x, radar.y, node.x, node.y) <= range
                    && VisibilityRules.resourceStage(world, radar.playerId, node)
                    .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) visible.add(node);
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

    private DispatchChoice bestDispatchChoice(List<Unit> workers, List<ResourceNode> visibleResources,
                                               Map<Integer,Integer> assignedCounts) {
        DispatchChoice best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Unit worker : workers) {
            for (ResourceNode node : visibleResources) {
                if (!worker.type().harvestKinds.contains(node.kind)) continue;
                int assigned = assignedCounts.getOrDefault(node.id, 0);
                double distance = Calc.distance(worker.x, worker.y, node.x, node.y);
                if (betterDispatch(node, worker, assigned, distance, best, bestAssigned, bestDist)) {
                    best = new DispatchChoice(node, worker);
                    bestAssigned = assigned;
                    bestDist = distance;
                }
            }
        }
        return best;
    }

    private boolean betterDispatch(ResourceNode node, Unit worker, int assigned, double distance,
                                    DispatchChoice best, int bestAssigned, double bestDistance) {
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

    private boolean betterResource(ResourceNode node, int assigned, double distance, ResourceNode best,
                                    int bestAssigned, double bestDistance) {
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
