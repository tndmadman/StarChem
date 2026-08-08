package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused deterministic coverage for issue #291 queued waypoints and compound orders. */
public final class Issue291CommandQueueValidator {
    private Issue291CommandQueueValidator() { }

    public static void main(String[] args) {
        validateMovementQueueAndRevisions();
        validateQueueBound();
        validateHarvestAdvancement();
        validateWormholeContinuation();
        validateGalaxySaveRestore();
        validateWireRoundTripAndOwnerIsolation();
        System.out.println("Issue 291 command queue validation passed.");
    }

    private static void validateMovementQueueAndRevisions() {
        World world = world("Queue movement");
        Unit unit = soloUnit(world);
        String key = unit.key();
        double x = unit.x;
        double y = unit.y;

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, x + 45), clampY(world, y)))
                == UnitQueueApplyResult.APPLIED, "initial move should apply");
        for (int i = 1; i <= 8; i++) {
            double tx = clampX(world, x + 45 + i * 28);
            double ty = clampY(world, y + (i % 2 == 0 ? 34 : -34));
            require(apply(world, unit, UnitQueueOperation.APPEND,
                    QueuedUnitCommand.move(world.activeSystemId(), tx, ty)) == UnitQueueApplyResult.APPLIED,
                    "waypoint " + i + " should append");
        }
        require(UnitCommandQueueSystem.commands(world, key).size() == 9,
                "queue should contain the active move plus eight waypoints");

        long current = UnitCommandQueueSystem.revision(world, key);
        UnitQueueMutation stale = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.APPEND,
                Math.max(0, current - 1), QueuedUnitCommand.move(world.activeSystemId(), x, y));
        require(UnitCommandQueueSystem.applyGlobal(world, stale) == UnitQueueApplyResult.STALE,
                "stale queue revision should be rejected without replacing the chain");
        require(UnitCommandQueueSystem.commands(world, key).size() == 9,
                "stale append must not mutate the queue");

        double finalX = clampX(world, x + 130);
        double finalY = clampY(world, y + 90);
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), finalX, finalY)) == UnitQueueApplyResult.APPLIED,
                "normal replacement should apply");
        require(UnitCommandQueueSystem.commands(world, key).size() == 1,
                "replace should clear the queued tail");
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, key).isEmpty(), 800,
                "replacement move did not complete");
        require(Calc.distance(unit.x, unit.y, finalX, finalY) < 16,
                "unit should finish at the replacement destination");
    }

    private static void validateQueueBound() {
        World world = world("Queue bounds");
        Unit unit = soloUnit(world);
        double baseX = unit.x;
        double baseY = unit.y;
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, baseX + 25), baseY))
                == UnitQueueApplyResult.APPLIED, "bounded queue initial move should apply");
        for (int i = 1; i < UnitCommandQueueSystem.MAX_QUEUE; i++) {
            require(apply(world, unit, UnitQueueOperation.APPEND,
                    QueuedUnitCommand.move(world.activeSystemId(),
                            clampX(world, baseX + 25 + i * 6), clampY(world, baseY + (i % 3) * 6)))
                    == UnitQueueApplyResult.APPLIED, "queue should accept entry " + i);
        }
        require(UnitCommandQueueSystem.commands(world, unit.key()).size() == UnitCommandQueueSystem.MAX_QUEUE,
                "queue should stop exactly at the configured bound");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), baseX, baseY)) == UnitQueueApplyResult.REJECTED,
                "queue overflow must be rejected server-side");
    }

    private static void validateHarvestAdvancement() {
        World world = world("Queue harvest");
        Unit unit = soloUnit(world);
        ResourceNode node = null;
        for (ResourceNode candidate : world.resources) {
            if (candidate.active && unit.type().harvestKinds.contains(candidate.kind)) {
                node = candidate;
                break;
            }
        }
        if (node == null) throw new IllegalStateException("No harvestable resource available for queue validation.");
        unit.x = node.x;
        unit.y = node.y;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        double nextX = clampX(world, unit.x + 90);
        double nextY = clampY(world, unit.y + 45);

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.harvest(world.activeSystemId(), node.id)) == UnitQueueApplyResult.APPLIED,
                "harvest should become the active queue step");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), nextX, nextY)) == UnitQueueApplyResult.APPLIED,
                "move should append after harvest");
        node.deplete();
        world.update(0.05);
        List<QueuedUnitCommand> remaining = UnitCommandQueueSystem.commands(world, unit.key());
        require(!remaining.isEmpty() && remaining.get(0).kind() == QueuedCommandKind.MOVE,
                "depleted queued harvest must advance instead of auto-retargeting");
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, unit.key()).isEmpty(), 700,
                "post-harvest movement did not complete");
    }

    private static void validateWormholeContinuation() {
        World world = world("Queue wormhole");
        Unit unit = soloUnit(world);
        require(!world.wormholes.isEmpty(), "test system must expose a wormhole");
        WormholeGate gate = world.wormholes.get(0);
        String source = world.activeSystemId();
        String destination = gate.toSystemId;
        String key = unit.key();

        String previous = world.activeSystemId();
        world.activateSystem(destination);
        double targetX = clampX(world, gate.exitX + 160);
        double targetY = clampY(world, gate.exitY + 80);
        world.activateSystem(previous);

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.wormhole(source, gate.id, destination)) == UnitQueueApplyResult.APPLIED,
                "wormhole step should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(destination, targetX, targetY)) == UnitQueueApplyResult.APPLIED,
                "destination move should append across the wormhole boundary");

        runUntil(world, () -> destination.equals(world.ownerUnitLocations(unit.playerId).get(key)), 1600,
                "ship did not transit the queued wormhole");
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, key).isEmpty(), 1600,
                "destination command did not continue after wormhole transit");
        require(destination.equals(world.ownerUnitLocations(unit.playerId).get(key)),
                "ship should remain in the wormhole destination system");
    }

    private static void validateGalaxySaveRestore() {
        World world = world("Queue save");
        Unit unit = soloUnit(world);
        double x1 = clampX(world, unit.x + 70);
        double y1 = clampY(world, unit.y + 20);
        double x2 = clampX(world, unit.x + 130);
        double y2 = clampY(world, unit.y + 70);
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), x1, y1)) == UnitQueueApplyResult.APPLIED,
                "save queue first step should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), x2, y2)) == UnitQueueApplyResult.APPLIED,
                "save queue second step should append");
        long revision = UnitCommandQueueSystem.revision(world, unit.key());
        Map<String,Object> galaxy = world.captureServerSaveGalaxy();

        World restored = new World("Queue save restored", Set.of(), world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        String systemId = restored.ownerUnitLocations("SOLO").get(unit.key());
        require(systemId != null && !systemId.isBlank(), "restored queue owner unit should exist");
        restored.activateSystem(systemId);
        Unit restoredUnit = restored.units.get(unit.key());
        require(restoredUnit != null, "restored unit should load");
        require(UnitCommandQueueSystem.commands(restored, restoredUnit.key()).size() == 2,
                "queued commands should survive galaxy save/restore");
        require(UnitCommandQueueSystem.revision(restored, restoredUnit.key()) == revision,
                "queue revision should survive save/restore");
    }

    private static void validateWireRoundTripAndOwnerIsolation() {
        World world = world("Queue wire");
        Unit unit = soloUnit(world);
        QueuedUnitCommand command = QueuedUnitCommand.move(world.activeSystemId(),
                clampX(world, unit.x + 40), clampY(world, unit.y + 20));
        UnitQueueMutation mutation = new UnitQueueMutation(unit.playerId, unit.unitId,
                UnitQueueOperation.REPLACE, UnitCommandQueueSystem.revision(world, unit.key()), command);
        String packet = UnitQueueWire.mutationPacket(mutation);
        UnitQueueMutation decoded = UnitQueueWire.parseMutation(packet.split("\\|", -1), unit.playerId);
        require(decoded.unitId() == unit.unitId && decoded.command().kind() == QueuedCommandKind.MOVE,
                "queue mutation wire round-trip should preserve command identity");
        require(UnitCommandQueueSystem.applyGlobal(world, decoded) == UnitQueueApplyResult.APPLIED,
                "wire-decoded mutation should apply");

        List<String> own = UnitCommandQueueSystem.statePackets(world, unit.playerId, true);
        List<String> other = UnitCommandQueueSystem.statePackets(world, "OTHER", true);
        require(!own.isEmpty(), "owner initial sync should contain queue state");
        require(other.isEmpty(), "queue state must not be exposed to another player");

        World client = world("Queue wire client");
        PlayerRegistry.activate(client);
        require(UnitQueueWire.readState(client, own.get(0), unit.playerId),
                "owner queue state packet should decode");
        require(UnitCommandQueueSystem.commands(client, unit.key()).size() == 1,
                "queue state packet should restore the owner's queued command");

        List<QueuedUnitCommand> oversized = new ArrayList<>();
        for (int i = 0; i <= UnitCommandQueueSystem.MAX_QUEUE; i++) oversized.add(command.withStepId(i + 1));
        boolean rejected = false;
        try { UnitQueueWire.statePacket(unit.unitId, 1, false, true, 2, oversized); }
        catch (SnapshotDecodeException expected) { rejected = true; }
        require(rejected, "oversized queue state must be rejected");
    }

    private static UnitQueueApplyResult apply(World world, Unit unit, UnitQueueOperation operation,
                                              QueuedUnitCommand command) {
        UnitQueueMutation mutation = new UnitQueueMutation(unit.playerId, unit.unitId, operation,
                UnitCommandQueueSystem.revision(world, unit.key()), command);
        return UnitCommandQueueSystem.applyGlobal(world, mutation);
    }

    private static World world(String name) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
        PlayerRegistry.activate(world);
        return world;
    }

    private static Unit soloUnit(World world) {
        for (Unit unit : world.units.values()) if ("SOLO".equals(unit.playerId)) return unit;
        throw new IllegalStateException("Solo unit missing.");
    }

    private static double clampX(World world, double value) { return Calc.clamp(value, 20, world.width - 20); }
    private static double clampY(World world, double value) { return Calc.clamp(value, 20, world.height - 20); }

    private static void runUntil(World world, Check condition, int steps, String failure) {
        for (int i = 0; i < steps; i++) {
            if (condition.ok()) return;
            world.update(0.05);
        }
        if (!condition.ok()) throw new IllegalStateException(failure);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Check { boolean ok(); }
}
