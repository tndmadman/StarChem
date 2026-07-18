package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Recovers station deployers whose visible package survived while their
 * in-memory Phase 7 construction plan did not.
 *
 * Loaded orphan deployers are adopted back into the timed construction
 * pipeline when capacity permits. Unassigned deployers are deliberately
 * parked instead of using the generic idle orbit, so a waiting builder does
 * not look like it is aimlessly wandering around a station.
 */
final class NpcStationDeployerRecoverySystem {
    private NpcStationDeployerRecoverySystem() { }

    static void update(World world, NpcFaction faction, NpcStrategicState strategy) {
        if (world == null || faction == null
                || faction.behavior() != NpcBehavior.FACTION) return;
        if (NpcStationConstructionSystem.hasActivePlan(world, faction)) return;

        List<Unit> deployers = availableDeployers(world, faction);
        if (deployers.isEmpty()) return;

        Unit loaded = null;
        for (Unit deployer : deployers) {
            if (!deployer.basePackageType.isBlank()) {
                loaded = deployer;
                break;
            }
        }

        if (loaded != null) {
            park(loaded);
            if (strategy == NpcStrategicState.RETREAT
                    || strategy == NpcStrategicState.DEFEATED
                    || faction.maxStations() <= 0) return;

            BaseType packageType = Rules.findBase(loaded.basePackageType);
            if (packageType == null) {
                AiDevLog.add(world, faction,
                        "discarded invalid orphan station package "
                                + loaded.basePackageType + " on deployer #"
                                + loaded.unitId);
                loaded.basePackageType = "";
                return;
            }

            NpcFactionCapacitySnapshot capacity =
                    NpcFactionCapacitySystem.snapshot(world, faction);
            if (capacity.stationCommitments() >= faction.maxStations()) return;

            if (NpcStationConstructionSystem.startLoaded(
                    world, faction, loaded, packageType.id)) {
                AiDevLog.add(world, faction,
                        "recovered orphan deployer #" + loaded.unitId
                                + " carrying " + packageType.name);
            }
            return;
        }

        // An empty deployer waiting for demand should remain visibly parked.
        // Expedition or construction orders execute later and can override this.
        for (Unit deployer : deployers) park(deployer);
    }

    private static List<Unit> availableDeployers(World world, NpcFaction faction) {
        List<Unit> result = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || !unit.type().baseBuilder) continue;
            if (NpcStationConstructionSystem.ownsBuilder(world, unit.key())) continue;
            if (NpcExpeditionSystem.ownsUnit(world, unit.key())) continue;
            if (NpcRecoverySystem.ownsUnit(world, unit)) continue;
            if (NpcRepairEvacuationSystem.ownsUnit(world, unit)) continue;
            result.add(unit);
        }
        result.sort(Comparator.comparingInt(unit -> unit.unitId));
        return result;
    }

    static void park(Unit deployer) {
        if (deployer == null || deployer.hp <= 0) return;
        deployer.clearOrder();
        deployer.attackTarget = "";
        deployer.automationResourceId = -1;
        deployer.targetX = deployer.x;
        deployer.targetY = deployer.y;
        // MOVE-at-current-position prevents World.idleNearBase from assigning
        // an orbit during this tick. World converts it back to IDLE afterward.
        deployer.task = UnitTask.MOVE;
    }
}
