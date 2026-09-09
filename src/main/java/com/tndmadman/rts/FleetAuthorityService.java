package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side entry point for fleet mutations that touch multiple ships.
 *
 * Transport code must supply the authenticated player id as actorId; requests do
 * not carry a trusted owner id. Multi-ship command changes are snapshotted and
 * rolled back if any member rejects the mutation, so a fleet command is atomic
 * from the player's point of view.
 */
final class FleetAuthorityService {
    private FleetAuthorityService() { }

    static synchronized FleetCommandResult moveToSystem(World world, String actorId, long fleetId,
                                                         long expectedFleetRevision, String destinationSystemId) {
        return transactional(world, actorId, fleetId, expectedFleetRevision,
                () -> FleetCommandService.moveToSystem(world, actorId, fleetId, destinationSystemId));
    }

    static synchronized FleetCommandResult rally(World world, String actorId, long fleetId,
                                                  long expectedFleetRevision, String destinationSystemId,
                                                  double x, double y) {
        return transactional(world, actorId, fleetId, expectedFleetRevision,
                () -> FleetCommandService.rally(world, actorId, fleetId, destinationSystemId, x, y));
    }

    static synchronized FleetCommandResult retreatRegroup(World world, String actorId, long fleetId,
                                                           long expectedFleetRevision, String destinationSystemId,
                                                           double x, double y) {
        return transactional(world, actorId, fleetId, expectedFleetRevision,
                () -> FleetCommandService.retreatRegroup(world, actorId, fleetId, destinationSystemId, x, y));
    }

    static synchronized FleetCommandResult applyCombatPolicy(World world, String actorId, long fleetId,
                                                              long expectedFleetRevision,
                                                              CombatStance stance,
                                                              TargetPriorityPolicy priority) {
        return transactional(world, actorId, fleetId, expectedFleetRevision,
                () -> FleetCommandService.applyCombatPolicy(world, actorId, fleetId, stance, priority));
    }

    private static FleetCommandResult transactional(World world, String actorId, long fleetId,
                                                     long expectedFleetRevision, Command command) {
        if (world == null || actorId == null || actorId.isBlank() || command == null) {
            return FleetCommandResult.rejected("Invalid fleet command request.");
        }
        Optional<FleetView> maybe = FleetManager.view(world, actorId, fleetId);
        if (maybe.isEmpty()) return FleetCommandResult.rejected("Fleet not found or not owned by player.");
        FleetView fleet = maybe.get();
        if (expectedFleetRevision >= 0 && fleet.revision() != expectedFleetRevision) {
            return FleetCommandResult.rejected("Fleet changed; refresh before issuing another order.");
        }

        List<MemberSnapshot> before = snapshotMembers(world, fleet);
        if (before.size() != fleet.memberKeys().size()) {
            return FleetCommandResult.rejected("A fleet member is unavailable.");
        }

        FleetCommandResult result;
        try {
            result = command.apply();
        } catch (RuntimeException ex) {
            restoreMembers(world, before);
            throw ex;
        }
        if (result == null || !result.applied()) restoreMembers(world, before);
        return result == null ? FleetCommandResult.rejected("Fleet command failed.") : result;
    }

    private static List<MemberSnapshot> snapshotMembers(World world, FleetView fleet) {
        Map<String,String> locations = world.ownerUnitLocations(fleet.ownerId());
        List<MemberSnapshot> out = new ArrayList<>();
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            for (String key : fleet.memberKeys()) {
                String systemId = locations.get(key);
                if (systemId == null || systemId.isBlank()) return List.of();
                world.activateSystem(systemId);
                Unit unit = world.units.get(key);
                if (unit == null || !fleet.ownerId().equals(unit.playerId) || unit.hp <= 0) return List.of();
                Map<String,Object> queue = new LinkedHashMap<>(UnitCommandQueueSystem.capture(world, unit));
                out.add(new MemberSnapshot(systemId, key, queue, UnitRuntimeSnapshot.capture(unit)));
            }
            return out;
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static void restoreMembers(World world, List<MemberSnapshot> snapshots) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            for (MemberSnapshot snapshot : snapshots) {
                world.activateSystem(snapshot.systemId());
                Unit unit = world.units.get(snapshot.unitKey());
                if (unit == null) continue;
                if (snapshot.queue().isEmpty()) UnitCommandQueueSystem.remove(world, snapshot.unitKey());
                else UnitCommandQueueSystem.restore(world, unit, snapshot.queue());
                snapshot.runtime().restore(unit);
                UnitCommandQueueSystem.forceDirty(world, snapshot.unitKey());
                world.saveActiveSystem();
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private interface Command { FleetCommandResult apply(); }

    private record MemberSnapshot(String systemId, String unitKey, Map<String,Object> queue,
                                  UnitRuntimeSnapshot runtime) { }

    private record UnitRuntimeSnapshot(
            UnitTask task,
            double targetX,
            double targetY,
            String attackTarget,
            String logisticsTargetBaseId,
            String logisticsRequestId,
            int automationResourceId,
            UnitOrderType orderType,
            double orderX1,
            double orderY1,
            double orderX2,
            double orderY2,
            double orderRadius,
            String orderTarget,
            int orderPhase) {

        static UnitRuntimeSnapshot capture(Unit unit) {
            return new UnitRuntimeSnapshot(unit.task, unit.targetX, unit.targetY, unit.attackTarget,
                    unit.logisticsTargetBaseId, unit.logisticsRequestId, unit.automationResourceId,
                    unit.orderType, unit.orderX1, unit.orderY1, unit.orderX2, unit.orderY2,
                    unit.orderRadius, unit.orderTarget, unit.orderPhase);
        }

        void restore(Unit unit) {
            unit.task = task;
            unit.targetX = targetX;
            unit.targetY = targetY;
            unit.attackTarget = attackTarget;
            unit.logisticsTargetBaseId = logisticsTargetBaseId;
            unit.logisticsRequestId = logisticsRequestId;
            unit.automationResourceId = automationResourceId;
            unit.orderType = orderType;
            unit.orderX1 = orderX1;
            unit.orderY1 = orderY1;
            unit.orderX2 = orderX2;
            unit.orderY2 = orderY2;
            unit.orderRadius = orderRadius;
            unit.orderTarget = orderTarget;
            unit.orderPhase = orderPhase;
        }
    }
}