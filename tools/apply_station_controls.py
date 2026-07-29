from pathlib import Path
import json

ROOT = Path('.')

def replace(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'Expected source block not found in {path}')
    p.write_text(text.replace(old, new, 1))

# Add explicit interaction roles and non-production flags.
config_path = ROOT / 'config/stations.json'
data = json.loads(config_path.read_text())
stations = data['stationTypes']
for type_id in ('outpost', 'shipyard', 'laboratory', 'manufacturing'):
    stations[type_id]['role'] = 'production'
    stations[type_id]['nonProduction'] = False
for type_id in ('radar_picket', 'radar_array', 'radar_nexus', 'signal_jammer', 'radar_decoy', 'sensor_contact_station'):
    stations[type_id]['nonProduction'] = True
stations['radar_decoy']['decoyProfile'] = 'radar_nexus'
stations['radar_decoy']['decoyProfiles'] = [
    'radar_nexus', 'radar_array', 'shipyard', 'manufacturing', 'laboratory', 'outpost'
]
config_path.write_text(json.dumps(data, indent=2) + '\n')

replace('src/main/java/com/tndmadman/rts/GamePanel.java',
'''        if (base != null) {
            ProceduralAudio.play(SoundCue.SELECT);
            if (PlayerRegistry.isLocal(base.playerId)) buildMenu.showForBase(world, network, base, e.getX(), e.getY());
            else world.status = "Enemy base: " + PlayerRegistry.name(base.playerId) + " | " + base.type().name + " | " + base.id;
            return;
        }
''',
'''        if (base != null) {
            ProceduralAudio.play(SoundCue.SELECT);
            if (PlayerRegistry.isLocal(base.playerId)) {
                if (!StationControlMenu.showIfHandled(this, world, network, base, e.getX(), e.getY())) {
                    buildMenu.showForBase(world, network, base, e.getX(), e.getY());
                }
            } else world.status = "Enemy base: " + PlayerRegistry.name(base.playerId) + " | " + base.type().name + " | " + base.id;
            return;
        }
''')

replace('src/main/java/com/tndmadman/rts/ProductionSystem.java',
'''        Base base = world.bases.get(baseId);
        if (base == null || !playerId.equals(base.playerId)) return false;
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "ENQUEUE" -> enqueue(world, value, baseId, extra);
            case "CANCEL" -> ProductionSystem.cancel(world, playerId, baseId, value);
            case "MOVE" -> ProductionSystem.move(world, playerId, baseId, value, parseInt(extra));
            default -> false;
        };
''',
'''        Base base = world.bases.get(baseId);
        if (base == null || !playerId.equals(base.playerId)) return false;
        String normalized = action.toUpperCase(Locale.ROOT);
        if (!"CONTROL".equals(normalized) && StationControls.nonProduction(base.typeId)) {
            world.status = base.type().name + " is a non-production station.";
            return false;
        }
        return switch (normalized) {
            case "CONTROL" -> StationControlCommands.apply(world, playerId, baseId, value, extra);
            case "ENQUEUE" -> enqueue(world, value, baseId, extra);
            case "CANCEL" -> ProductionSystem.cancel(world, playerId, baseId, value);
            case "MOVE" -> ProductionSystem.move(world, playerId, baseId, value, parseInt(extra));
            default -> false;
        };
''')

replace('src/main/java/com/tndmadman/rts/ScoutSystem.java',
'''    private boolean retargetFromRadar(World world, Unit miner, ResourceNode oldNode) {
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
''',
'''    private boolean retargetFromRadar(World world, Unit miner, ResourceNode oldNode) {
        if (oldNode == null) return false;
        Map<Integer,Integer> assignedCounts = assignmentCounts(world, miner.playerId);
        ResourceNode best = null;
        int bestPriority = Integer.MAX_VALUE;
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
        world.status = "Radar network redirected " + miner.type().name + " to " + best.name + ".";
        return true;
    }
''')

replace('src/main/java/com/tndmadman/rts/ScoutSystem.java',
'''            DispatchChoice choice = bestDispatchChoice(idleWorkers, visibleResources, assignedCounts);
''',
'''            DispatchChoice choice = bestDispatchChoice(world, radar, idleWorkers, visibleResources, assignedCounts);
''')

replace('src/main/java/com/tndmadman/rts/ScoutSystem.java',
'''    private DispatchChoice bestDispatchChoice(List<Unit> workers, List<ResourceNode> visibleResources,
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
''',
'''    private DispatchChoice bestDispatchChoice(World world, Base radar, List<Unit> workers,
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
''')

replace('src/main/java/com/tndmadman/rts/FogSnapshotFilter.java',
'''        boolean ownOrAllied = IntelWarfareSystem.allied(world, playerId, state.playerId());
        if (ownOrAllied) return state;
        String key = "B:" + state.id();
        if (!stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) {
            double x = IntelWarfareSystem.approximateX(world, key, stage, state.x());
            double y = IntelWarfareSystem.approximateY(world, key, stage, state.y());
            return new BaseState("CONTACT-" + Integer.toUnsignedString(key.hashCode()), UNKNOWN_OWNER,
                    IntelWarfareSystem.CONTACT_STATION, x, y, 1, 0, "", "");
        }
        if (stage == IntelWarfareSystem.DetectionStage.IDENTIFIED) {
            double hp = approximateCondition(state.hp(), base == null ? state.hp() : base.type().maxHp);
            double shield = approximateCondition(state.shield(), base == null ? state.shield() : base.type().maxShield);
            return new BaseState(state.id(), state.playerId(), state.typeId(), state.x(), state.y(),
                    hp, shield, "", "");
        }
''',
'''        boolean ownOrAllied = IntelWarfareSystem.allied(world, playerId, state.playerId());
        if (ownOrAllied) return state;
        String key = "B:" + state.id();
        boolean spoofing = base != null && IntelWarfareSystem.isDecoy(base.typeId)
                && stage.atLeast(IntelWarfareSystem.DetectionStage.CLASSIFIED)
                && stage != IntelWarfareSystem.DetectionStage.DETAILED;
        String visibleType = spoofing ? StationControls.decoySpoofType(world, base) : state.typeId();
        if (!stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)) {
            double x = IntelWarfareSystem.approximateX(world, key, stage, state.x());
            double y = IntelWarfareSystem.approximateY(world, key, stage, state.y());
            return new BaseState("CONTACT-" + Integer.toUnsignedString(key.hashCode()), UNKNOWN_OWNER,
                    spoofing ? visibleType : IntelWarfareSystem.CONTACT_STATION, x, y, 1, 0, "", "");
        }
        if (stage == IntelWarfareSystem.DetectionStage.IDENTIFIED) {
            double hp = approximateCondition(state.hp(), base == null ? state.hp() : base.type().maxHp);
            double shield = approximateCondition(state.shield(), base == null ? state.shield() : base.type().maxShield);
            return new BaseState(state.id(), state.playerId(), visibleType, state.x(), state.y(),
                    hp, shield, "", "");
        }
''')

replace('.github/workflows/ci.yml',
'''      - name: Validate intel and counterintel warfare
        run: java -cp build/classes/java/main com.tndmadman.rts.IntelWarfareValidator

      - name: Validate shipyard scroll hotfix
''',
'''      - name: Validate intel and counterintel warfare
        run: java -cp build/classes/java/main com.tndmadman.rts.IntelWarfareValidator

      - name: Validate station control menus
        run: java -cp build/classes/java/main com.tndmadman.rts.StationControlValidator

      - name: Validate shipyard scroll hotfix
''')
