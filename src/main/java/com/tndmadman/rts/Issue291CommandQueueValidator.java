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
        validateAttackMoveChaining();
        validateDestroyedTargetAdvancement();
        validateRemovedWormholeHaltsChain();
        validateReplacementDuringExecution();
        validateStopAndHoldSemantics();
        validateMalformedAndUnauthorizedMutations();
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

    private static void validateAttackMoveChaining() {
        World world = world("Queue attack move");
        Unit unit = soloUnit(world);
        double attackX = clampX(world, unit.x + 150);
        double attackY = clampY(world, unit.y + 70);
        double finalX = clampX(world, attackX + 120);
        double finalY = clampY(world, attackY - 95);

        QueuedUnitCommand attackMove = QueuedUnitCommand.tactical(world.activeSystemId(),
                UnitOrderType.ATTACK_MOVE, unit.x, unit.y, attackX, attackY,
                UnitOrderSystem.defaultRadius(UnitOrderType.ATTACK_MOVE), "");
        require(apply(world, unit, UnitQueueOperation.REPLACE, attackMove) == UnitQueueApplyResult.APPLIED,
                "attack-move should become the active queue step");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), finalX, finalY)) == UnitQueueApplyResult.APPLIED,
                "move should append after attack-move");

        runUntil(world, () -> UnitCommandQueueSystem.commands(world, unit.key()).isEmpty(), 1400,
                "attack-move chain did not complete");
        require(Calc.distance(unit.x, unit.y, finalX, finalY) < 16,
                "move after attack-move did not execute");
    }

    private static void validateDestroyedTargetAdvancement() {
        World world = world("Queue destroyed target");
        Unit attacker = armedSoloUnit(world);
        Unit target = new Unit(Config.CORSAIRS_ID, 9901, "destroyer",
                clampX(world, attacker.x + 70), clampY(world, attacker.y + 15));
        target.loadoutId = "destroyer";
        world.units.put(target.key(), target);
        world.saveActiveSystem();

        double finalX = clampX(world, attacker.x + 180);
        double finalY = clampY(world, attacker.y - 90);
        String targetKey = CombatTarget.unit(target);
        require(apply(world, attacker, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.attack(world.activeSystemId(), targetKey)) == UnitQueueApplyResult.APPLIED,
                "attack target should become the active queue step");
        require(apply(world, attacker, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), finalX, finalY)) == UnitQueueApplyResult.APPLIED,
                "move should append after attack");

        target.hp = 0;
        world.update(0.05);
        List<QueuedUnitCommand> remaining = UnitCommandQueueSystem.commands(world, attacker.key());
        require(!remaining.isEmpty() && remaining.get(0).kind() == QueuedCommandKind.MOVE,
                "destroyed attack target did not advance to the next queued step");
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, attacker.key()).isEmpty(), 1000,
                "post-destruction queued move did not complete");
        require(Calc.distance(attacker.x, attacker.y, finalX, finalY) < 16,
                "destroyed target progression did not reach the next waypoint");
    }

    private static void validateRemovedWormholeHaltsChain() {
        World world = world("Queue removed wormhole");
        Unit unit = soloUnit(world);
        require(!world.wormholes.isEmpty(), "removed-wormhole fixture requires a gate");
        WormholeGate gate = world.wormholes.get(0);
        String source = world.activeSystemId();
        String destination = gate.toSystemId;
        String key = unit.key();
        double stagingX = clampX(world, unit.x + 110);
        double stagingY = clampY(world, unit.y + 65);

        String previous = world.activeSystemId();
        world.activateSystem(destination);
        double destinationX = clampX(world, gate.exitX + 120);
        double destinationY = clampY(world, gate.exitY + 70);
        world.activateSystem(previous);

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(source, stagingX, stagingY)) == UnitQueueApplyResult.APPLIED,
                "staging move should become active before removed-gate test");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.wormhole(source, gate.id, destination)) == UnitQueueApplyResult.APPLIED,
                "wormhole should append before it is removed");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(destination, destinationX, destinationY)) == UnitQueueApplyResult.APPLIED,
                "destination move should append behind the wormhole");

        world.wormholes.removeIf(candidate -> gate.id.equals(candidate.id));
        world.saveActiveSystem();
        for (int i = 0; i < 1200 && !UnitCommandQueueSystem.commands(world, key).isEmpty(); i++) {
            world.updateCurrentSystem(0.05);
        }
        require(UnitCommandQueueSystem.commands(world, key).isEmpty(),
                "removed wormhole did not halt the queued cross-system chain");
        require(source.equals(world.ownerUnitLocations(unit.playerId).get(key)),
                "removed wormhole unexpectedly transferred the unit");
        require(unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE,
                "removed wormhole left stale runtime intent after halting the chain");
    }

    private static void validateReplacementDuringExecution() {
        World world = world("Queue mid-execution replacement");
        Unit unit = soloUnit(world);
        double startX = unit.x;
        double startY = unit.y;
        double firstX = unit.x < world.width * 0.5 ? world.width - 140 : 140;
        double firstY = clampY(world, unit.y + 160);
        double staleTailX = unit.x < world.width * 0.5 ? 140 : world.width - 140;
        double staleTailY = clampY(world, unit.y - 180);

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), firstX, firstY)) == UnitQueueApplyResult.APPLIED,
                "long-running move should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), staleTailX, staleTailY)) == UnitQueueApplyResult.APPLIED,
                "old tail should append before replacement");

        for (int i = 0; i < 300 && Calc.distance(startX, startY, unit.x, unit.y) < 24; i++) world.update(0.05);
        require(Calc.distance(startX, startY, unit.x, unit.y) >= 24,
                "replacement fixture did not begin executing its first move");
        require(UnitCommandQueueSystem.commands(world, unit.key()).size() == 2,
                "old chain advanced before mid-execution replacement was issued");

        double replacementX = clampX(world, unit.x + (unit.x < world.width * 0.5 ? 150 : -150));
        double replacementY = clampY(world, unit.y + 120);
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), replacementX, replacementY)) == UnitQueueApplyResult.APPLIED,
                "mid-execution replacement should apply");
        List<QueuedUnitCommand> replacement = UnitCommandQueueSystem.commands(world, unit.key());
        require(replacement.size() == 1 && Math.abs(replacement.get(0).x1() - replacementX) < 0.001
                        && Math.abs(replacement.get(0).y1() - replacementY) < 0.001,
                "mid-execution replacement retained stale queued work");

        runUntil(world, () -> UnitCommandQueueSystem.commands(world, unit.key()).isEmpty(), 1000,
                "replacement issued during movement did not complete");
        require(Calc.distance(unit.x, unit.y, replacementX, replacementY) < 16,
                "unit did not finish the replacement issued during execution");
        require(Calc.distance(unit.x, unit.y, staleTailX, staleTailY) > 40,
                "stale tail resumed after mid-execution replacement");
    }

    private static void validateStopAndHoldSemantics() {
        World world = world("Queue stop and hold");
        Unit unit = soloUnit(world);
        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 150), unit.y))
                == UnitQueueApplyResult.APPLIED, "stop fixture first move should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 260), clampY(world, unit.y + 90)))
                == UnitQueueApplyResult.APPLIED, "stop fixture tail should append");

        UnitQueueMutation clear = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.CLEAR,
                UnitCommandQueueSystem.revision(world, unit.key()), null);
        require(UnitCommandQueueSystem.applyGlobal(world, clear) == UnitQueueApplyResult.APPLIED,
                "stop/clear mutation should apply");
        require(UnitCommandQueueSystem.commands(world, unit.key()).isEmpty()
                        && unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE,
                "stop/clear did not remove active and queued work");

        require(apply(world, unit, UnitQueueOperation.REPLACE,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 130), unit.y))
                == UnitQueueApplyResult.APPLIED, "hold fixture move should apply");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 230), clampY(world, unit.y - 90)))
                == UnitQueueApplyResult.APPLIED, "hold fixture tail should append");
        QueuedUnitCommand hold = QueuedUnitCommand.tactical(world.activeSystemId(), UnitOrderType.HOLD,
                0, 0, 0, 0, UnitOrderSystem.defaultRadius(UnitOrderType.HOLD), "");
        require(apply(world, unit, UnitQueueOperation.REPLACE, hold) == UnitQueueApplyResult.APPLIED,
                "hold should replace the existing queue");
        List<QueuedUnitCommand> held = UnitCommandQueueSystem.commands(world, unit.key());
        require(held.size() == 1 && held.get(0).tacticalType() == UnitOrderType.HOLD,
                "hold did not clear the old queued tail");
        require(apply(world, unit, UnitQueueOperation.APPEND,
                QueuedUnitCommand.move(world.activeSystemId(), clampX(world, unit.x + 80), unit.y))
                == UnitQueueApplyResult.REJECTED,
                "terminal hold incorrectly allowed a later queued step");
    }

    private static void validateMalformedAndUnauthorizedMutations() {
        World world = world("Queue mutation security");
        Unit unit = soloUnit(world);
        String systemId = world.activeSystemId();
        UnitQueueMutation wrongOwner = new UnitQueueMutation("OTHER", unit.unitId, UnitQueueOperation.REPLACE, 0,
                QueuedUnitCommand.move(systemId, unit.x, unit.y));
        require(UnitCommandQueueSystem.applyGlobal(world, wrongOwner) == UnitQueueApplyResult.REJECTED,
                "queue mutation accepted an unauthorized player/unit pair");
        UnitQueueMutation missingUnit = new UnitQueueMutation(unit.playerId, Integer.MAX_VALUE, UnitQueueOperation.REPLACE, 0,
                QueuedUnitCommand.move(systemId, unit.x, unit.y));
        require(UnitCommandQueueSystem.applyGlobal(world, missingUnit) == UnitQueueApplyResult.REJECTED,
                "queue mutation accepted an unauthorized unit ID");
        UnitQueueMutation malformedCoordinate = new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.REPLACE,
                UnitCommandQueueSystem.revision(world, unit.key()), QueuedUnitCommand.move(systemId, Double.NaN, unit.y));
        require(UnitCommandQueueSystem.applyGlobal(world, malformedCoordinate) == UnitQueueApplyResult.REJECTED,
                "queue mutation accepted a malformed coordinate");

        boolean oversizedRejected = false;
        try {
            UnitQueueWire.mutationPacket(new UnitQueueMutation(unit.playerId, unit.unitId, UnitQueueOperation.REPLACE,
                    UnitCommandQueueSystem.revision(world, unit.key()),
                    QueuedUnitCommand.attack(systemId, "X".repeat(5000))));
        } catch (SnapshotDecodeException expected) {
            oversizedRejected = true;
        }
        require(oversizedRejected, "oversized queue command packet was not rejected");
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
        try {
            UnitQueueWire.statePacket(unit.unitId, 1, false, true, 2,
                    CombatStance.AGGRESSIVE, TargetPriorityPolicy.NEAREST_THREAT, oversized);
        } catch (SnapshotDecodeException expected) { rejected = true; }
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

    private static Unit armedSoloUnit(World world) {
        Unit unit = new Unit("SOLO", 9001, "destroyer", world.width * 0.45, world.height * 0.48);
        unit.loadoutId = "destroyer_rail_escort";
        world.units.put(unit.key(), unit);
        world.saveActiveSystem();
        return unit;
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
