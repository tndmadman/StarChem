package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maintains local miner search behavior, radar dispatch, and the authoritative intel tick. */
final class ScoutSystem {
    private final Map<String,Double> radarModePhases = new HashMap<>();
    private double lastIntelUpdateTime = Double.NaN;
    private double lastIntelDelta = 0.05;

    void update(World world) {
        refreshTeamIntelSharing(world);
        updateIntel(world);

        Map<String,Map<Integer,Integer>> assignmentCountsByPlayer = new HashMap<>();
        for (Unit miner : world.units.values()) {
            if (!canLocalMine(miner)) continue;
            Map<Integer,Integer> assigned = assignmentCountsByPlayer.computeIfAbsent(miner.playerId,
                    playerId -> assignmentCounts(world, playerId));
            updateLocalMiner(world, miner, assigned);
        }

        Map<String,VisibilityRules.Frame> visibilityByPlayer = new HashMap<>();
        for (Base radar : world.bases.values()) {
            if (radar.hp <= 0 || !IntelWarfareSystem.isRadar(radar.typeId)) continue;
            if (adaptRadarMode(world, radar)) visibilityByPlayer.remove(radar.playerId);
            VisibilityRules.Frame visibility = visibilityByPlayer.computeIfAbsent(radar.playerId,
                    playerId -> VisibilityRules.frame(world, playerId));
            Map<Integer,Integer> assigned = assignmentCountsByPlayer.computeIfAbsent(radar.playerId,
                    playerId -> assignmentCounts(world, playerId));
            dispatchWorkers(world, radar, visibility, assigned);
        }
    }

    boolean retargetAfterDepletion(World world, Unit miner, ResourceNode oldNode) {
        if (miner.freeCargo() <= 0.05) return false;
        Map<Integer,Integer> assigned = assignmentCounts(world, miner.playerId);
        if (retargetLocalMiner(world, miner, oldNode, assigned)) return true;
        return retargetFromRadar(world, miner, oldNode, assigned,
                VisibilityRules.frame(world, miner.playerId));
    }

    private void refreshTeamIntelSharing(World world) {
        Set<String> owners = new LinkedHashSet<>();
        for (Unit unit : world.units.values()) if (unit != null && unit.hp > 0) owners.add(unit.playerId);
        for (Base base : world.bases.values()) if (base != null && base.hp > 0) owners.add(base.playerId);
        List<String> players = new ArrayList<>(owners);
        for (int i = 0; i < players.size(); i++) {
            String first = players.get(i);
            if (first == null || first.isBlank() || NpcRules.isNpcFaction(first)) continue;
            for (int j = i + 1; j < players.size(); j++) {
                String second = players.get(j);
                if (second == null || second.isBlank() || NpcRules.isNpcFaction(second)) continue;
                if (PlayerRegistry.color(first).getRGB() == PlayerRegistry.color(second).getRGB()) {
                    IntelWarfareSystem.setIntelAlliance(world, first, second, true);
                }
            }
        }
    }

    private void updateIntel(World world) {
        double now = world.systemTime();
        double elapsed = Double.isFinite(lastIntelUpdateTime) ? now - lastIntelUpdateTime : 0.05;
        lastIntelUpdateTime = now;
        if (!Double.isFinite(elapsed) || elapsed <= 0) elapsed = 0.05;
        lastIntelDelta = Math.min(5.0, elapsed);
        double simulationDelta = elapsed > 1.0 ? 0.05 : elapsed;
        IntelWarfareSystem.update(world, simulationDelta);
    }

    private boolean adaptRadarMode(World world, Base radar) {
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
            if (identifiedThreat) break;
        }
        if (!identifiedThreat) {
            for (Base enemy : world.bases.values()) {
                if (enemy == null || enemy.hp <= 0 || IntelWarfareSystem.allied(world, radar.playerId, enemy.playerId)) continue;
                IntelWarfareSystem.DetectionStage stage = VisibilityRules.baseStage(world, radar.playerId, enemy);
                detected |= stage.atLeast(IntelWarfareSystem.DetectionStage.CONTACT);
                identifiedThreat |= stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
                if (identifiedThreat) break;
            }
        }

        IntelWarfareSystem.RadarMode desired;
        if (identifiedThreat) desired = IntelWarfareSystem.RadarMode.FOCUSED;
        else if (detected) desired = IntelWarfareSystem.RadarMode.ACTIVE;
        else desired = phase % 8.0 < 1.5 ? IntelWarfareSystem.RadarMode.ACTIVE
                : IntelWarfareSystem.RadarMode.PASSIVE;
        return setModeSilently(world, radar, desired);
    }

    private boolean setModeSilently(World world, Base radar, IntelWarfareSystem.RadarMode desired) {
        if (IntelWarfareSystem.radarMode(world, radar) == desired) return false;
        String status = world.status;
        IntelWarfareSystem.setRadarMode(world, radar, desired, radar.playerId);
        world.status = status;
        return true;
    }

    private void updateLocalMiner(World world, Unit miner, Map<Integer,Integer> assignedCounts) {
        if (miner.task != UnitTask.IDLE || miner.orderType != UnitOrderType.NONE
                || UnitCommandQueueSystem.hasPlayerIntent(world, miner) || miner.freeCargo() <= 0.05) return;
        anchorToNearbyStation(world, miner);
        ensureMiningAnchor(miner);
        ResourceNode node = leastAssignedLocalResource(world, miner, -1, assignedCounts);
        if (node != null) {
            miner.startAutoHarvest(node.id);
            assignedCounts.merge(node.id, 1, Integer::sum);
            world.status = miner.type().name + " found " + node.name + " nearby.";
        }
    }

    private void anchorToNearbyStation(World world, Unit miner) {
        if (miner.miningAnchorSet) return;
        Base base = world.nearestBase(miner.playerId, miner.x, miner.y);
        if (base == null) return;
        double idleStationRange = base.type().unloadRange + 170;
        double dx = miner.x - base.x;
        double dy = miner.y - base.y;
        if (dx * dx + dy * dy <= idleStationRange * idleStationRange) miner.setMiningAnchor(base.x, base.y);
    }

    private boolean retargetLocalMiner(World world, Unit miner, ResourceNode oldNode,
                                       Map<Integer,Integer> assignedCounts) {
        if (!canLocalMine(miner)) return false;
        ensureMiningAnchor(miner);
        ResourceNode best = leastAssignedLocalResource(world, miner, oldNode == null ? -1 : oldNode.id,
                assignedCounts);
        if (best == null) return false;
        miner.startAutoHarvest(best.id);
        assignedCounts.merge(best.id, 1, Integer::sum);
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
        double localRange = miner.type().scoutRange * SystemModifierRules.sensorRange(world);
        double localRangeSquared = localRange * localRange;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.id == skippedResourceId || !miner.type().harvestKinds.contains(node.kind)) continue;
            double anchorDx = miner.miningAnchorX - node.x;
            double anchorDy = miner.miningAnchorY - node.y;
            if (anchorDx * anchorDx + anchorDy * anchorDy > localRangeSquared) continue;
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

    private boolean retargetFromRadar(World world, Unit miner, ResourceNode oldNode,
                                      Map<Integer,Integer> assignedCounts, VisibilityRules.Frame visibility) {
        if (oldNode == null) return false;
        ResourceNode best = null;
        int bestPriority = Integer.MAX_VALUE;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Base radar : world.bases.values()) {
            if (!miner.playerId.equals(radar.playerId) || radar.hp <= 0 || !IntelWarfareSystem.isRadar(radar.typeId)
                    || StationControls.radarSearchTarget(world, radar) == StationControls.RadarSearchTarget.WORMHOLES) continue;
            double range = VisibilityRules.baseSensorRange(world, radar);
            double rangeSquared = range * range;
            for (ResourceNode node : world.resources) {
                if (!node.active || node.id == oldNode.id || !miner.type().harvestKinds.contains(node.kind)) continue;
                double radarDx = radar.x - node.x;
                double radarDy = radar.y - node.y;
                if (radarDx * radarDx + radarDy * radarDy > rangeSquared) continue;
                if (!visibility.resourceStage(node).atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) continue;
                int priority = StationControls.priorityRank(world, radar, node.material);
                int assigned = assignedCounts.getOrDefault(node.id, 0);
                double distance = Calc.distance(miner.x, miner.y, node.x, node.y);
                if (betterRadarResource(node, priority, assigned, distance, best,
                        bestPriority, bestAssigned, bestDist)) {
                    best = node;
                    bestPriority = priority;
                    bestAssigned = assigned;
                    bestDist = distance;
                }
            }
        }
        if (best == null) return false;
        miner.setMiningAnchor(best.x, best.y);
        miner.startAutoHarvest(best.id);
        assignedCounts.merge(best.id, 1, Integer::sum);
        world.status = "Radar network redirected " + miner.type().name + " to " + best.name + ".";
        return true;
    }

    private void dispatchWorkers(World world, Base radar, VisibilityRules.Frame visibility,
                                 Map<Integer,Integer> assignedCounts) {
        if (StationControls.radarSearchTarget(world, radar) == StationControls.RadarSearchTarget.WORMHOLES) return;
        int limit = IntelWarfareSystem.dispatchLimit(radar.typeId);
        if (limit <= 0) return;

        List<ResourceNode> visibleResources = radarVisibleResources(world, radar, visibility);
        if (visibleResources.isEmpty()) return;

        int assignedInsideRadar = 0;
        for (ResourceNode node : visibleResources) assignedInsideRadar += assignedCounts.getOrDefault(node.id, 0);
        int availableSlots = Math.max(0, limit - assignedInsideRadar);
        if (availableSlots <= 0) return;

        List<Unit> idleWorkers = idleHarvestWorkers(world, radar.playerId);
        int sent = 0;
        while (sent < availableSlots && !idleWorkers.isEmpty()) {
            DispatchChoice choice = bestDispatchChoice(world, radar, idleWorkers, visibleResources, assignedCounts);
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

    private List<ResourceNode> radarVisibleResources(World world, Base radar, VisibilityRules.Frame visibility) {
        List<ResourceNode> visible = new ArrayList<>();
        double range = VisibilityRules.baseSensorRange(world, radar);
        double rangeSquared = range * range;
        for (ResourceNode node : world.resources) {
            double dx = radar.x - node.x;
            double dy = radar.y - node.y;
            if (node.active && dx * dx + dy * dy <= rangeSquared
                    && visibility.resourceStage(node).atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) {
                visible.add(node);
            }
        }
        return visible;
    }

    private List<Unit> idleHarvestWorkers(World world, String playerId) {
        List<Unit> workers = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(playerId) || unit.task != UnitTask.IDLE
                    || unit.orderType != UnitOrderType.NONE
                    || UnitCommandQueueSystem.hasPlayerIntent(world, unit)) continue;
            if (unit.type().harvestKinds.isEmpty() || unit.freeCargo() <= 0.05) continue;
            workers.add(unit);
        }
        return workers;
    }

    private DispatchChoice bestDispatchChoice(World world, Base radar, List<Unit> workers,
                                               List<ResourceNode> visibleResources,
                                               Map<Integer,Integer> assignedCounts) {
        DispatchChoice best = null;
        int bestPriority = Integer.MAX_VALUE;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Unit worker : workers) {
            for (ResourceNode node : visibleResources) {
                if (!worker.type().harvestKinds.contains(node.kind)) continue;
                int priority = StationControls.priorityRank(world, radar, node.material);
                int assigned = assignedCounts.getOrDefault(node.id, 0);
                double distance = Calc.distance(worker.x, worker.y, node.x, node.y);
                if (betterDispatch(node, worker, priority, assigned, distance,
                        best, bestPriority, bestAssigned, bestDist)) {
                    best = new DispatchChoice(node, worker);
                    bestPriority = priority;
                    bestAssigned = assigned;
                    bestDist = distance;
                }
            }
        }
        return best;
    }

    private boolean betterDispatch(ResourceNode node, Unit worker, int priority, int assigned, double distance,
                                    DispatchChoice best, int bestPriority, int bestAssigned, double bestDistance) {
        if (best == null) return true;
        if (priority != bestPriority) return priority < bestPriority;
        if (assigned != bestAssigned) return assigned < bestAssigned;
        if (Math.abs(distance - bestDistance) > 0.001) return distance < bestDistance;
        if (node.id != best.node.id) return node.id < best.node.id;
        return worker.unitId < best.worker.unitId;
    }

    private boolean betterRadarResource(ResourceNode node, int priority, int assigned, double distance,
                                        ResourceNode best, int bestPriority, int bestAssigned,
                                        double bestDistance) {
        if (best == null) return true;
        if (priority != bestPriority) return priority < bestPriority;
        if (assigned != bestAssigned) return assigned < bestAssigned;
        if (Math.abs(distance - bestDistance) > 0.001) return distance < bestDistance;
        return node.id < best.id;
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
