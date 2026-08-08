package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

enum UnitQueueOperation { REPLACE, APPEND, CLEAR }
enum QueuedCommandKind { MOVE, ATTACK, HARVEST, TACTICAL, WORMHOLE }
enum UnitQueueApplyResult { APPLIED, STALE, REJECTED }

enum QueueStartResult { STARTED, SKIP, HALT }

record QueuedUnitCommand(long stepId, QueuedCommandKind kind, String systemId,
                         double x1, double y1, double x2, double y2, double radius,
                         String targetKey, int resourceId, String gateId,
                         String destinationSystemId, UnitOrderType tacticalType) {
    QueuedUnitCommand {
        if (kind == null) kind = QueuedCommandKind.MOVE;
        systemId = clean(systemId);
        targetKey = clean(targetKey);
        gateId = clean(gateId);
        destinationSystemId = clean(destinationSystemId);
        if (tacticalType == null) tacticalType = UnitOrderType.NONE;
    }

    static QueuedUnitCommand move(String systemId, double x, double y) {
        return new QueuedUnitCommand(0, QueuedCommandKind.MOVE, systemId,
                x, y, x, y, 0, "", -1, "", "", UnitOrderType.NONE);
    }

    static QueuedUnitCommand attack(String systemId, String targetKey) {
        return new QueuedUnitCommand(0, QueuedCommandKind.ATTACK, systemId,
                0, 0, 0, 0, 0, targetKey, -1, "", "", UnitOrderType.NONE);
    }

    static QueuedUnitCommand harvest(String systemId, int resourceId) {
        return new QueuedUnitCommand(0, QueuedCommandKind.HARVEST, systemId,
                0, 0, 0, 0, 0, "", resourceId, "", "", UnitOrderType.NONE);
    }

    static QueuedUnitCommand tactical(String systemId, UnitOrderType type,
                                      double x1, double y1, double x2, double y2,
                                      double radius, String targetKey) {
        return new QueuedUnitCommand(0, QueuedCommandKind.TACTICAL, systemId,
                x1, y1, x2, y2, radius, targetKey, -1, "", "", type);
    }

    static QueuedUnitCommand wormhole(String sourceSystemId, String gateId, String destinationSystemId) {
        return new QueuedUnitCommand(0, QueuedCommandKind.WORMHOLE, sourceSystemId,
                0, 0, 0, 0, 0, "", -1, gateId, destinationSystemId, UnitOrderType.NONE);
    }

    QueuedUnitCommand withStepId(long id) {
        return new QueuedUnitCommand(id, kind, systemId, x1, y1, x2, y2, radius,
                targetKey, resourceId, gateId, destinationSystemId, tacticalType);
    }

    boolean terminal() {
        if (kind != QueuedCommandKind.TACTICAL) return false;
        return tacticalType == UnitOrderType.PATROL
                || tacticalType == UnitOrderType.GUARD
                || tacticalType == UnitOrderType.ESCORT
                || tacticalType == UnitOrderType.HOLD;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

record UnitQueueMutation(String playerId, int unitId, UnitQueueOperation operation,
                         long expectedRevision, QueuedUnitCommand command) {
    UnitQueueMutation {
        playerId = playerId == null ? "" : playerId.trim();
        if (operation == null) operation = UnitQueueOperation.REPLACE;
        expectedRevision = Math.max(0, expectedRevision);
    }

    String unitKey() { return Unit.key(playerId, unitId); }
}

final class UnitCommandQueueSystem {
    static final int MAX_QUEUE = 16;
    private static final double ARRIVAL_DISTANCE = 7.0;
    private static final double MAX_ABS_COORDINATE = 10_000_000.0;
    private static final Map<World, Map<String, QueueState>> STATES = new WeakHashMap<>();
    private static final Map<World, LinkedHashSet<String>> DIRTY = new WeakHashMap<>();
    private static final Map<World, Map<String, Long>> TOMBSTONES = new WeakHashMap<>();

    private UnitCommandQueueSystem() { }

    static synchronized long revision(World world, String unitKey) {
        QueueState state = lookup(world, unitKey);
        return state == null ? 0 : state.revision;
    }

    static synchronized List<QueuedUnitCommand> commands(World world, String unitKey) {
        QueueState state = lookup(world, unitKey);
        return state == null ? List.of() : List.copyOf(state.queue);
    }

    static synchronized boolean hasPlayerIntent(World world, Unit unit) {
        if (world == null || unit == null) return false;
        QueueState state = lookup(world, unit.key());
        return state != null && !state.queue.isEmpty();
    }

    static synchronized boolean ownsHarvest(World world, Unit unit) {
        if (world == null || unit == null) return false;
        QueueState state = lookup(world, unit.key());
        if (state == null || state.queue.isEmpty()) return false;
        return state.queue.peekFirst().kind() == QueuedCommandKind.HARVEST;
    }

    static synchronized UnitQueueApplyResult applyGlobal(World world, UnitQueueMutation mutation) {
        if (world == null || mutation == null || !validPlayerId(mutation.playerId()) || mutation.unitId() < 0) {
            return UnitQueueApplyResult.REJECTED;
        }
        String key = mutation.unitKey();
        String unitSystem = world.ownerUnitLocations(mutation.playerId()).get(key);
        if (unitSystem == null || unitSystem.isBlank()) return UnitQueueApplyResult.REJECTED;
        String previous = world.activeSystemId();
        world.activateSystem(unitSystem);
        try {
            Unit unit = world.units.get(key);
            if (unit == null || !mutation.playerId().equals(unit.playerId) || unit.hp <= 0
                    || ProductionSystem.refitReserved(world, key)) return UnitQueueApplyResult.REJECTED;
            UnitQueueApplyResult result = applyHere(world, unit, mutation, true);
            world.saveActiveSystem();
            return result;
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
    }

    static synchronized UnitQueueApplyResult predict(World world, UnitQueueMutation mutation) {
        if (world == null || mutation == null || !validPlayerId(mutation.playerId()) || mutation.unitId() < 0) {
            return UnitQueueApplyResult.REJECTED;
        }
        String key = mutation.unitKey();
        Unit unit = world.units.get(key);
        if (unit != null && !mutation.playerId().equals(unit.playerId)) return UnitQueueApplyResult.REJECTED;
        QueueState state = state(world, key);
        if (mutation.expectedRevision() != state.revision) return UnitQueueApplyResult.STALE;
        if (mutation.operation() == UnitQueueOperation.CLEAR) {
            state.queue.clear();
            state.activeStarted = false;
            state.revision++;
            if (unit != null) clearRuntime(unit);
            return UnitQueueApplyResult.APPLIED;
        }
        QueuedUnitCommand command = mutation.command();
        if (!validateStructural(command)) return UnitQueueApplyResult.REJECTED;
        if (mutation.operation() == UnitQueueOperation.APPEND && state.queue.isEmpty() && unit != null) {
            promoteCurrentIntent(world, unit, state);
        }
        if (mutation.operation() == UnitQueueOperation.APPEND) {
            if (state.queue.size() >= MAX_QUEUE || !appendContinuityValid(state, command)) {
                return UnitQueueApplyResult.REJECTED;
            }
        } else {
            state.queue.clear();
            state.activeStarted = false;
            if (unit != null && !command.systemId().equals(world.activeSystemId())) return UnitQueueApplyResult.REJECTED;
        }
        if (state.queue.size() >= MAX_QUEUE) return UnitQueueApplyResult.REJECTED;
        state.queue.addLast(command.withStepId(state.nextStepId++));
        state.revision++;
        if (unit != null && !state.activeStarted) update(world, unit, 0);
        return UnitQueueApplyResult.APPLIED;
    }

    private static UnitQueueApplyResult applyHere(World world, Unit unit, UnitQueueMutation mutation, boolean validateEntities) {
        QueueState state = state(world, unit.key());
        if (mutation.expectedRevision() != state.revision) {
            forceDirty(world, unit.key());
            return UnitQueueApplyResult.STALE;
        }
        if (mutation.operation() == UnitQueueOperation.CLEAR) {
            state.queue.clear();
            state.activeStarted = false;
            state.revision++;
            clearRuntime(unit);
            markDirty(world, unit.key());
            return UnitQueueApplyResult.APPLIED;
        }

        QueuedUnitCommand command = mutation.command();
        if (!validateStructural(command)) return UnitQueueApplyResult.REJECTED;
        if (mutation.operation() == UnitQueueOperation.APPEND && state.queue.isEmpty()) {
            promoteCurrentIntent(world, unit, state);
        }
        if (mutation.operation() == UnitQueueOperation.REPLACE) {
            if (!command.systemId().equals(world.activeSystemId())) return UnitQueueApplyResult.REJECTED;
            if (validateEntities && !validateInCommandSystem(world, unit, command)) return UnitQueueApplyResult.REJECTED;
        } else {
            if (state.queue.size() >= MAX_QUEUE || !appendContinuityValid(state, command)) {
                return UnitQueueApplyResult.REJECTED;
            }
            if (validateEntities && !validateInCommandSystem(world, unit, command)) return UnitQueueApplyResult.REJECTED;
        }
        if (state.queue.size() >= MAX_QUEUE) return UnitQueueApplyResult.REJECTED;

        if (mutation.operation() == UnitQueueOperation.REPLACE) {
            state.queue.clear();
            state.activeStarted = false;
            clearRuntime(unit);
        }
        state.queue.addLast(command.withStepId(state.nextStepId++));
        state.revision++;
        markDirty(world, unit.key());
        if (!state.activeStarted) update(world, unit, 0);
        return UnitQueueApplyResult.APPLIED;
    }

    static synchronized void legacyReplace(World world, Unit unit) {
        if (world == null || unit == null) return;
        QueueState state = state(world, unit.key());
        state.queue.clear();
        state.activeStarted = false;
        state.revision++;
        markDirty(world, unit.key());
    }

    static synchronized void cancelForSystem(World world, Unit unit) {
        if (world == null || unit == null) return;
        QueueState state = lookup(world, unit.key());
        if (state == null || state.queue.isEmpty()) return;
        state.queue.clear();
        state.activeStarted = false;
        state.revision++;
        clearRuntime(unit);
        markDirty(world, unit.key());
    }

    static synchronized void forceDirty(World world, String key) {
        if (world == null || key == null || key.isBlank()) return;
        markDirty(world, key);
    }

    static synchronized void remove(World world, String key) {
        if (world == null || key == null || key.isBlank()) return;
        QueueState removed = states(world).remove(key);
        long revision = removed == null ? 1 : removed.revision + 1;
        tombstones(world).put(key, revision);
        markDirty(world, key);
    }

    static synchronized void removePlayer(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return;
        List<String> keys = new ArrayList<>();
        for (String key : states(world).keySet()) if (playerId.equals(playerFromKey(key))) keys.add(key);
        for (String key : keys) remove(world, key);
    }

    static synchronized void clearWorld(World world) {
        if (world == null) return;
        STATES.remove(world);
        DIRTY.remove(world);
        TOMBSTONES.remove(world);
    }

    static synchronized void update(World world, Unit unit, double dt) {
        if (world == null || unit == null) return;
        QueueState state = lookup(world, unit.key());
        if (state == null || state.queue.isEmpty()) return;
        for (int guard = 0; guard < MAX_QUEUE; guard++) {
            QueuedUnitCommand command = state.queue.peekFirst();
            if (command == null) return;
            if (command.kind() == QueuedCommandKind.WORMHOLE
                    && command.destinationSystemId().equals(world.activeSystemId())) {
                completeHead(world, unit, state, command);
                continue;
            }
            if (!command.systemId().equals(world.activeSystemId())) {
                haltChain(world, unit, state);
                return;
            }
            if (!state.activeStarted) {
                QueueStartResult started = startCommand(world, unit, command);
                if (started == QueueStartResult.HALT) {
                    haltChain(world, unit, state);
                    return;
                }
                if (started == QueueStartResult.SKIP) {
                    completeHead(world, unit, state, command);
                    continue;
                }
                state.activeStarted = true;
                markDirty(world, unit.key());
            }
            maintainActive(world, unit, command);
            if (!complete(world, unit, command)) return;
            completeHead(world, unit, state, command);
        }
    }

    private static QueueStartResult startCommand(World world, Unit unit, QueuedUnitCommand command) {
        return switch (command.kind()) {
            case MOVE -> {
                unit.issueMove(command.x1(), command.y1());
                yield QueueStartResult.STARTED;
            }
            case ATTACK -> {
                if (!CombatTarget.alive(world, command.targetKey())
                        || !CombatTarget.enemy(world, unit, command.targetKey())
                        || !WeaponRules.armed(world, unit)) yield QueueStartResult.SKIP;
                unit.issueAttack(command.targetKey());
                yield unit.task == UnitTask.ATTACK ? QueueStartResult.STARTED : QueueStartResult.SKIP;
            }
            case HARVEST -> {
                ResourceNode node = world.findResource(command.resourceId());
                if (!harvestable(unit, node)) yield QueueStartResult.SKIP;
                unit.setMiningAnchor(node.x, node.y);
                unit.startAutoHarvest(node.id);
                yield QueueStartResult.STARTED;
            }
            case TACTICAL -> startTactical(world, unit, command);
            case WORMHOLE -> {
                WormholeGate gate = gate(world, command.gateId());
                if (gate == null || !command.systemId().equals(gate.fromSystemId)
                        || !command.destinationSystemId().equals(gate.toSystemId)) yield QueueStartResult.HALT;
                unit.issueMove(gate.x, gate.y);
                yield QueueStartResult.STARTED;
            }
        };
    }

    private static QueueStartResult startTactical(World world, Unit unit, QueuedUnitCommand command) {
        UnitOrderType type = command.tacticalType();
        if (type == null || type == UnitOrderType.NONE) return QueueStartResult.SKIP;
        double x1 = command.x1(), y1 = command.y1(), x2 = command.x2(), y2 = command.y2();
        if (type == UnitOrderType.HOLD) {
            x1 = x2 = unit.x;
            y1 = y2 = unit.y;
        } else if (type == UnitOrderType.ATTACK_MOVE) {
            x1 = unit.x;
            y1 = unit.y;
        }
        unit.setOrder(new UnitOrderCommand(unit.playerId, unit.unitId, type,
                x1, y1, x2, y2, command.radius(), command.targetKey(), 0));
        return unit.orderType == type ? QueueStartResult.STARTED : QueueStartResult.SKIP;
    }

    private static void maintainActive(World world, Unit unit, QueuedUnitCommand command) {
        switch (command.kind()) {
            case MOVE -> {
                if (Calc.distance(unit.x, unit.y, command.x1(), command.y1()) > ARRIVAL_DISTANCE
                        && (unit.task != UnitTask.MOVE
                        || Calc.distance(unit.targetX, unit.targetY, command.x1(), command.y1()) > 2)) {
                    unit.moveTo(command.x1(), command.y1());
                }
            }
            case ATTACK -> {
                if (CombatTarget.alive(world, command.targetKey()) && CombatTarget.enemy(world, unit, command.targetKey())
                        && (unit.task != UnitTask.ATTACK || !command.targetKey().equals(unit.attackTarget))) {
                    unit.attack(command.targetKey());
                }
            }
            case HARVEST -> {
                ResourceNode node = world.findResource(command.resourceId());
                if (harvestable(unit, node) && unit.task != UnitTask.AUTO_HARVEST
                        && unit.task != UnitTask.RETURN_TO_STATION) {
                    unit.setMiningAnchor(node.x, node.y);
                    unit.startAutoHarvest(node.id);
                }
            }
            case TACTICAL -> { }
            case WORMHOLE -> {
                WormholeGate gate = gate(world, command.gateId());
                if (gate != null && Calc.distance(unit.x, unit.y, gate.x, gate.y) > ARRIVAL_DISTANCE
                        && (unit.task != UnitTask.MOVE || Calc.distance(unit.targetX, unit.targetY, gate.x, gate.y) > 2)) {
                    unit.moveTo(gate.x, gate.y);
                }
            }
        }
    }

    private static boolean complete(World world, Unit unit, QueuedUnitCommand command) {
        return switch (command.kind()) {
            case MOVE -> Calc.distance(unit.x, unit.y, command.x1(), command.y1()) <= ARRIVAL_DISTANCE;
            case ATTACK -> !CombatTarget.alive(world, command.targetKey())
                    || !CombatTarget.enemy(world, unit, command.targetKey())
                    || unit.task != UnitTask.ATTACK && unit.attackTarget.isBlank();
            case HARVEST -> {
                ResourceNode node = world.findResource(command.resourceId());
                yield node == null || !node.active || node.amount <= 0.05;
            }
            case TACTICAL -> {
                if (command.tacticalType() == UnitOrderType.ATTACK_MOVE) {
                    yield unit.orderType == UnitOrderType.NONE
                            || Calc.distance(unit.x, unit.y, command.x2(), command.y2()) <= ARRIVAL_DISTANCE
                            && unit.task != UnitTask.ATTACK;
                }
                yield unit.orderType != command.tacticalType();
            }
            case WORMHOLE -> command.destinationSystemId().equals(world.activeSystemId());
        };
    }

    private static void completeHead(World world, Unit unit, QueueState state, QueuedUnitCommand command) {
        finishRuntime(unit, command);
        state.queue.pollFirst();
        state.activeStarted = false;
        markDirty(world, unit.key());
    }

    private static void finishRuntime(Unit unit, QueuedUnitCommand command) {
        if (unit == null || command == null) return;
        switch (command.kind()) {
            case MOVE -> {
                if (unit.task == UnitTask.MOVE) unit.task = UnitTask.IDLE;
                unit.targetX = unit.x;
                unit.targetY = unit.y;
            }
            case ATTACK -> {
                if (command.targetKey().equals(unit.attackTarget)) unit.attackTarget = "";
                if (unit.task == UnitTask.ATTACK) unit.task = UnitTask.IDLE;
            }
            case HARVEST -> {
                unit.automationResourceId = -1;
                if (unit.task == UnitTask.AUTO_HARVEST || unit.task == UnitTask.RETURN_TO_STATION || unit.task == UnitTask.MOVE) {
                    unit.task = UnitTask.IDLE;
                    unit.targetX = unit.x;
                    unit.targetY = unit.y;
                }
            }
            case TACTICAL -> {
                if (command.tacticalType() == UnitOrderType.ATTACK_MOVE || unit.orderType != command.tacticalType()) {
                    unit.clearOrder();
                    unit.attackTarget = "";
                    if (unit.task == UnitTask.ATTACK || unit.task == UnitTask.MOVE) unit.task = UnitTask.IDLE;
                }
            }
            case WORMHOLE -> { }
        }
    }

    private static void haltChain(World world, Unit unit, QueueState state) {
        state.queue.clear();
        state.activeStarted = false;
        state.revision++;
        clearRuntime(unit);
        markDirty(world, unit.key());
    }

    private static void clearRuntime(Unit unit) {
        unit.clearOrder();
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.logisticsTargetBaseId = "";
        unit.logisticsRequestId = "";
        unit.task = UnitTask.IDLE;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
    }

    private static void promoteCurrentIntent(World world, Unit unit, QueueState state) {
        if (world == null || unit == null || state == null || !state.queue.isEmpty()) return;
        QueuedUnitCommand command = null;
        if (unit.orderType != UnitOrderType.NONE) {
            command = QueuedUnitCommand.tactical(world.activeSystemId(), unit.orderType,
                    unit.orderX1, unit.orderY1, unit.orderX2, unit.orderY2,
                    unit.orderRadius, unit.orderTarget);
        } else if (unit.task == UnitTask.MOVE) {
            WormholeGate gate = gateNearTarget(world, unit.targetX, unit.targetY);
            command = gate == null
                    ? QueuedUnitCommand.move(world.activeSystemId(), unit.targetX, unit.targetY)
                    : QueuedUnitCommand.wormhole(world.activeSystemId(), gate.id, gate.toSystemId);
        } else if (unit.task == UnitTask.ATTACK && !unit.attackTarget.isBlank()) {
            command = QueuedUnitCommand.attack(world.activeSystemId(), unit.attackTarget);
        } else if ((unit.task == UnitTask.AUTO_HARVEST || unit.task == UnitTask.RETURN_TO_STATION)
                && unit.automationResourceId >= 0) {
            command = QueuedUnitCommand.harvest(world.activeSystemId(), unit.automationResourceId);
        }
        if (command == null) return;
        state.queue.addLast(command.withStepId(state.nextStepId++));
        state.activeStarted = true;
    }

    private static boolean appendContinuityValid(QueueState state, QueuedUnitCommand command) {
        if (state == null || command == null) return false;
        QueuedUnitCommand tail = state.queue.peekLast();
        if (tail == null) return true;
        if (tail.terminal()) return false;
        String expectedSystem = tail.kind() == QueuedCommandKind.WORMHOLE
                ? tail.destinationSystemId() : tail.systemId();
        return expectedSystem.equals(command.systemId());
    }

    private static boolean validateStructural(QueuedUnitCommand command) {
        if (command == null || command.systemId().isBlank() || command.systemId().length() > 128) return false;
        if (!finite(command.x1(), command.y1(), command.x2(), command.y2(), command.radius())) return false;
        if (Math.abs(command.x1()) > MAX_ABS_COORDINATE || Math.abs(command.y1()) > MAX_ABS_COORDINATE
                || Math.abs(command.x2()) > MAX_ABS_COORDINATE || Math.abs(command.y2()) > MAX_ABS_COORDINATE
                || command.radius() < 0 || command.radius() > 1200) return false;
        if (command.targetKey().length() > 256 || command.gateId().length() > 128
                || command.destinationSystemId().length() > 128) return false;
        if (containsControl(command.systemId()) || containsControl(command.targetKey())
                || containsControl(command.gateId()) || containsControl(command.destinationSystemId())) return false;
        if (command.kind() == QueuedCommandKind.HARVEST && command.resourceId() < 0) return false;
        if (command.kind() == QueuedCommandKind.TACTICAL
                && (command.tacticalType() == null || command.tacticalType() == UnitOrderType.NONE)) return false;
        if (command.kind() == QueuedCommandKind.WORMHOLE
                && (command.gateId().isBlank() || command.destinationSystemId().isBlank())) return false;
        return true;
    }

    private static boolean validateInCommandSystem(World world, Unit unit, QueuedUnitCommand command) {
        String previous = world.activeSystemId();
        world.activateSystem(command.systemId());
        try {
            if (!command.systemId().equals(world.activeSystemId())) return false;
            return switch (command.kind()) {
                case MOVE -> GameplayCommandNumbers.worldCoordinate(world, command.x1(), command.y1());
                case ATTACK -> CombatTarget.alive(world, command.targetKey())
                        && CombatTarget.enemy(world, unit, command.targetKey())
                        && WeaponRules.armed(world, unit)
                        && VisibilityRules.targetVisible(world, unit.playerId, command.targetKey());
                case HARVEST -> {
                    ResourceNode node = world.findResource(command.resourceId());
                    yield harvestable(unit, node)
                            && VisibilityRules.resourceStage(world, unit.playerId, node)
                            .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
                }
                case TACTICAL -> validateTactical(world, unit, command);
                case WORMHOLE -> {
                    WormholeGate gate = gate(world, command.gateId());
                    yield gate != null && command.systemId().equals(gate.fromSystemId)
                            && command.destinationSystemId().equals(gate.toSystemId);
                }
            };
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
    }

    private static boolean validateTactical(World world, Unit unit, QueuedUnitCommand command) {
        UnitOrderType type = command.tacticalType();
        if (type == UnitOrderType.HOLD) return true;
        if (!GameplayCommandNumbers.worldCoordinate(world, command.x1(), command.y1())
                || !GameplayCommandNumbers.worldCoordinate(world, command.x2(), command.y2())) return false;
        if (type == UnitOrderType.PATROL
                && Calc.distance(command.x1(), command.y1(), command.x2(), command.y2()) < 20) return false;
        if (type == UnitOrderType.ESCORT) return friendlyTarget(world, unit, command.targetKey(), true);
        if (type == UnitOrderType.GUARD && !command.targetKey().isBlank()) {
            return friendlyTarget(world, unit, command.targetKey(), false);
        }
        return true;
    }

    private static boolean friendlyTarget(World world, Unit unit, String key, boolean unitOnly) {
        Unit targetUnit = CombatTarget.unit(world, key);
        if (targetUnit != null) return targetUnit != unit && targetUnit.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetUnit.playerId);
        if (unitOnly) return false;
        Base targetBase = CombatTarget.base(world, key);
        return targetBase != null && targetBase.hp > 0
                && DiplomacySystem.allied(world, unit.playerId, targetBase.playerId);
    }

    private static boolean harvestable(Unit unit, ResourceNode node) {
        return unit != null && node != null && node.active && node.amount > 0.05
                && unit.type().harvestKinds.contains(node.kind);
    }

    private static WormholeGate gate(World world, String id) {
        if (world == null || id == null || id.isBlank()) return null;
        for (WormholeGate gate : world.wormholes) if (id.equals(gate.id)) return gate;
        return null;
    }

    private static WormholeGate gateNearTarget(World world, double x, double y) {
        if (world == null) return null;
        for (WormholeGate gate : world.wormholes) if (Calc.distance(x, y, gate.x, gate.y) <= 4) return gate;
        return null;
    }

    static synchronized Map<String,Object> capture(World world, Unit unit) {
        QueueState state = world == null || unit == null ? null : lookup(world, unit.key());
        if (state == null) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("revision", state.revision);
        out.put("nextStepId", state.nextStepId);
        out.put("activeStarted", state.activeStarted);
        List<Object> commands = new ArrayList<>();
        for (QueuedUnitCommand command : state.queue) commands.add(captureCommand(command));
        out.put("commands", commands);
        return out;
    }

    static synchronized void restore(World world, Unit unit, Object saved) {
        if (world == null || unit == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        if (data.isEmpty()) return;
        QueueState state = new QueueState();
        state.revision = Math.max(0, ServerSaveStore.longValue(data, "revision", 0));
        state.nextStepId = Math.max(1, ServerSaveStore.longValue(data, "nextStepId", 1));
        state.activeStarted = ServerSaveStore.boolValue(data, "activeStarted", false);
        long maxStep = 0;
        for (Object item : ServerSaveStore.list(data.get("commands"))) {
            if (state.queue.size() >= MAX_QUEUE) break;
            QueuedUnitCommand command = restoreCommand(ServerSaveStore.object(item));
            if (command == null || !validateStructural(command)) continue;
            state.queue.addLast(command);
            maxStep = Math.max(maxStep, command.stepId());
        }
        if (state.queue.isEmpty()) state.activeStarted = false;
        state.nextStepId = Math.max(state.nextStepId, maxStep + 1);
        states(world).put(unit.key(), state);
    }

    private static Map<String,Object> captureCommand(QueuedUnitCommand command) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("stepId", command.stepId());
        out.put("kind", command.kind().name());
        out.put("systemId", command.systemId());
        out.put("x1", command.x1()); out.put("y1", command.y1());
        out.put("x2", command.x2()); out.put("y2", command.y2());
        out.put("radius", command.radius());
        out.put("targetKey", command.targetKey());
        out.put("resourceId", command.resourceId());
        out.put("gateId", command.gateId());
        out.put("destinationSystemId", command.destinationSystemId());
        out.put("tacticalType", command.tacticalType().name());
        return out;
    }

    private static QueuedUnitCommand restoreCommand(Map<String,Object> row) {
        if (row == null || row.isEmpty()) return null;
        QueuedCommandKind kind = ServerSaveStore.enumValue(QueuedCommandKind.class, row.get("kind"), null);
        if (kind == null) return null;
        return new QueuedUnitCommand(
                Math.max(0, ServerSaveStore.longValue(row, "stepId", 0)), kind,
                ServerSaveStore.string(row, "systemId", ""),
                ServerSaveStore.doubleValue(row, "x1", 0), ServerSaveStore.doubleValue(row, "y1", 0),
                ServerSaveStore.doubleValue(row, "x2", 0), ServerSaveStore.doubleValue(row, "y2", 0),
                ServerSaveStore.doubleValue(row, "radius", 0), ServerSaveStore.string(row, "targetKey", ""),
                ServerSaveStore.intValue(row, "resourceId", -1), ServerSaveStore.string(row, "gateId", ""),
                ServerSaveStore.string(row, "destinationSystemId", ""),
                ServerSaveStore.enumValue(UnitOrderType.class, row.get("tacticalType"), UnitOrderType.NONE));
    }

    static synchronized List<String> statePackets(World world, String playerId, boolean initial) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (initial) {
            for (Map.Entry<String,QueueState> entry : states(world).entrySet()) {
                if (playerId.equals(playerFromKey(entry.getKey()))
                        && (entry.getValue().revision > 0 || !entry.getValue().queue.isEmpty())) keys.add(entry.getKey());
            }
        } else {
            Set<String> dirty = dirty(world);
            for (String key : new ArrayList<>(dirty)) {
                if (playerId.equals(playerFromKey(key))) {
                    keys.add(key);
                    dirty.remove(key);
                }
            }
        }
        List<String> packets = new ArrayList<>();
        for (String key : keys) {
            Long tombstone = tombstones(world).get(key);
            if (tombstone != null) {
                packets.add(UnitQueueWire.statePacket(unitIdFromKey(key), tombstone, true, false, 1, List.of()));
                if (!initial) tombstones(world).remove(key);
                continue;
            }
            QueueState state = lookup(world, key);
            if (state != null) {
                packets.add(UnitQueueWire.statePacket(unitIdFromKey(key), state.revision, false,
                        state.activeStarted, state.nextStepId, List.copyOf(state.queue)));
            }
        }
        return List.copyOf(packets);
    }

    static synchronized void applyRemoteState(World world, String playerId, int unitId, long revision,
                                              boolean removed, boolean activeStarted, long nextStepId,
                                              List<QueuedUnitCommand> commands) {
        if (world == null || playerId == null || playerId.isBlank() || unitId < 0 || revision < 0) return;
        String key = Unit.key(playerId, unitId);
        if (removed) {
            states(world).remove(key);
            return;
        }
        QueueState current = lookup(world, key);
        if (current != null && revision < current.revision) return;
        QueueState state = new QueueState();
        state.revision = revision;
        state.activeStarted = activeStarted;
        state.nextStepId = Math.max(1, nextStepId);
        long maxStep = 0;
        if (commands != null) {
            for (QueuedUnitCommand command : commands) {
                if (command == null || state.queue.size() >= MAX_QUEUE || !validateStructural(command)) continue;
                state.queue.addLast(command);
                maxStep = Math.max(maxStep, command.stepId());
            }
        }
        state.nextStepId = Math.max(state.nextStepId, maxStep + 1);
        if (state.queue.isEmpty()) state.activeStarted = false;
        states(world).put(key, state);
    }

    private static Map<String, QueueState> states(World world) {
        return STATES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
    }

    private static QueueState state(World world, String key) {
        return states(world).computeIfAbsent(key, ignored -> new QueueState());
    }

    private static QueueState lookup(World world, String key) {
        Map<String,QueueState> states = STATES.get(world);
        return states == null ? null : states.get(key);
    }

    private static LinkedHashSet<String> dirty(World world) {
        return DIRTY.computeIfAbsent(world, ignored -> new LinkedHashSet<>());
    }

    private static Map<String, Long> tombstones(World world) {
        return TOMBSTONES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
    }

    private static void markDirty(World world, String key) {
        dirty(world).add(key);
    }

    private static boolean validPlayerId(String id) {
        return id != null && !id.isBlank() && id.length() <= 64 && !containsControl(id) && id.indexOf('|') < 0;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static boolean containsControl(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String playerFromKey(String key) {
        if (key == null) return "";
        int separator = key.lastIndexOf(':');
        return separator <= 0 ? "" : key.substring(0, separator);
    }

    private static int unitIdFromKey(String key) {
        if (key == null) return -1;
        int separator = key.lastIndexOf(':');
        if (separator < 0 || separator + 1 >= key.length()) return -1;
        try { return Integer.parseInt(key.substring(separator + 1)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private static final class QueueState {
        final Deque<QueuedUnitCommand> queue = new ArrayDeque<>();
        long revision;
        long nextStepId = 1;
        boolean activeStarted;
    }
}

final class UnitQueueWire {
    private static final int MAX_COMMAND_CHARS = 4096;
    private static final int MAX_STATE_CHARS = 128 * 1024;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private UnitQueueWire() { }

    static String mutationPacket(UnitQueueMutation mutation) {
        if (mutation == null) return "";
        String command = mutation.operation() == UnitQueueOperation.CLEAR || mutation.command() == null
                ? "" : encodeCommand(mutation.command());
        return "QUEUE|" + mutation.playerId() + '|' + mutation.unitId() + '|' + mutation.operation().name()
                + '|' + mutation.expectedRevision() + '|' + command;
    }

    static UnitQueueMutation parseMutation(String[] parts, String authoritativePlayerId) {
        if (parts == null || parts.length != 6 || !"QUEUE".equals(parts[0])) throw malformed("mutation fields");
        if (authoritativePlayerId == null || authoritativePlayerId.isBlank()
                || !authoritativePlayerId.equals(parts[1])) throw malformed("mutation owner");
        int unitId = integer(parts[2], 0, Integer.MAX_VALUE, "unit ID");
        UnitQueueOperation operation;
        try { operation = UnitQueueOperation.valueOf(parts[3]); }
        catch (RuntimeException ex) { throw malformed("operation"); }
        long revision = longNumber(parts[4], 0, Long.MAX_VALUE, "revision");
        QueuedUnitCommand command = operation == UnitQueueOperation.CLEAR ? null : decodeCommand(parts[5]);
        if (operation != UnitQueueOperation.CLEAR && command == null) throw malformed("command");
        return new UnitQueueMutation(authoritativePlayerId, unitId, operation, revision, command);
    }

    static String statePacket(int unitId, long revision, boolean removed,
                              boolean activeStarted, long nextStepId,
                              List<QueuedUnitCommand> commands) {
        StringBuilder payload = new StringBuilder();
        if (commands != null) {
            if (commands.size() > UnitCommandQueueSystem.MAX_QUEUE) throw malformed("queue length");
            for (QueuedUnitCommand command : commands) {
                if (!payload.isEmpty()) payload.append(';');
                payload.append(encodeCommand(command));
            }
        }
        return "QUEUE_STATE|" + unitId + '|' + revision + '|' + (removed ? '1' : '0') + '|'
                + (activeStarted ? '1' : '0') + '|' + Math.max(1, nextStepId) + '|' + payload;
    }

    static boolean readState(World world, String message, String playerId) {
        if (message == null || !message.startsWith("QUEUE_STATE|")) return false;
        if (message.length() > MAX_STATE_CHARS) throw malformed("state size");
        String[] parts = message.split("\\|", -1);
        if (parts.length != 7) throw malformed("state fields");
        int unitId = integer(parts[1], 0, Integer.MAX_VALUE, "unit ID");
        long revision = longNumber(parts[2], 0, Long.MAX_VALUE, "revision");
        boolean removed = flag(parts[3]);
        boolean activeStarted = flag(parts[4]);
        long nextStepId = longNumber(parts[5], 1, Long.MAX_VALUE, "next step");
        List<QueuedUnitCommand> commands = new ArrayList<>();
        if (!parts[6].isBlank()) {
            String[] encoded = parts[6].split(";", -1);
            if (encoded.length > UnitCommandQueueSystem.MAX_QUEUE) throw malformed("queue length");
            for (String value : encoded) commands.add(decodeCommand(value));
        }
        UnitCommandQueueSystem.applyRemoteState(world, playerId, unitId, revision, removed,
                activeStarted, nextStepId, List.copyOf(commands));
        return true;
    }

    private static String encodeCommand(QueuedUnitCommand command) {
        if (command == null) return "";
        String raw = command.stepId() + "\t" + command.kind().name() + "\t" + command.systemId() + "\t"
                + command.x1() + "\t" + command.y1() + "\t" + command.x2() + "\t" + command.y2() + "\t"
                + command.radius() + "\t" + command.targetKey() + "\t" + command.resourceId() + "\t"
                + command.gateId() + "\t" + command.destinationSystemId() + "\t" + command.tacticalType().name();
        String encoded = ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_COMMAND_CHARS) throw malformed("command size");
        return encoded;
    }

    private static QueuedUnitCommand decodeCommand(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_COMMAND_CHARS) throw malformed("command size");
        final String raw;
        try { raw = new String(DECODER.decode(encoded), StandardCharsets.UTF_8); }
        catch (RuntimeException ex) { throw malformed("command encoding"); }
        String[] values = raw.split("\\t", -1);
        if (values.length != 13) throw malformed("command fields");
        long stepId = longNumber(values[0], 0, Long.MAX_VALUE, "step ID");
        QueuedCommandKind kind;
        UnitOrderType tacticalType;
        try { kind = QueuedCommandKind.valueOf(values[1]); }
        catch (RuntimeException ex) { throw malformed("command kind"); }
        try { tacticalType = UnitOrderType.valueOf(values[12]); }
        catch (RuntimeException ex) { throw malformed("tactical type"); }
        String systemId = token(values[2], 128, "system ID");
        double x1 = number(values[3], "x1"), y1 = number(values[4], "y1");
        double x2 = number(values[5], "x2"), y2 = number(values[6], "y2");
        double radius = number(values[7], "radius");
        String target = token(values[8], 256, "target");
        int resourceId = integer(values[9], -1, Integer.MAX_VALUE, "resource ID");
        String gateId = token(values[10], 128, "gate ID");
        String destination = token(values[11], 128, "destination system");
        return new QueuedUnitCommand(stepId, kind, systemId, x1, y1, x2, y2, radius,
                target, resourceId, gateId, destination, tacticalType);
    }

    private static String token(String value, int max, String label) {
        String token = value == null ? "" : value;
        if (token.length() > max) throw malformed(label);
        for (int i = 0; i < token.length(); i++) if (Character.isISOControl(token.charAt(i))) throw malformed(label);
        return token;
    }

    private static int integer(String value, int min, int max, String label) {
        final int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (RuntimeException ex) { throw malformed(label); }
        if (parsed < min || parsed > max) throw malformed(label);
        return parsed;
    }

    private static long longNumber(String value, long min, long max, String label) {
        final long parsed;
        try { parsed = Long.parseLong(value); }
        catch (RuntimeException ex) { throw malformed(label); }
        if (parsed < min || parsed > max) throw malformed(label);
        return parsed;
    }

    private static double number(String value, String label) {
        final double parsed;
        try { parsed = Double.parseDouble(value); }
        catch (RuntimeException ex) { throw malformed(label); }
        if (!Double.isFinite(parsed)) throw malformed(label);
        return parsed;
    }

    private static boolean flag(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw malformed("flag");
    }

    private static SnapshotDecodeException malformed(String field) {
        return new SnapshotDecodeException("Malformed unit command queue " + field + ".");
    }
}

final class UnitCommandQueueRenderer {
    private UnitCommandQueueRenderer() { }

    static void draw(Graphics2D g2, World world, Unit unit) {
        if (g2 == null || world == null || unit == null || !unit.selected || !PlayerRegistry.isLocal(unit.playerId)) return;
        List<QueuedUnitCommand> commands = UnitCommandQueueSystem.commands(world, unit.key());
        if (commands.isEmpty()) return;
        Color owner = PlayerRegistry.color(unit.playerId);
        Graphics2D q = (Graphics2D)g2.create();
        q.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 205));
        q.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{9f, 7f}, 0));
        q.setFont(q.getFont().deriveFont(Font.BOLD, 11f));
        double fromX = unit.x, fromY = unit.y;
        int number = 1;
        for (QueuedUnitCommand command : commands) {
            if (!world.activeSystemId().equals(command.systemId())) break;
            if (command.kind() == QueuedCommandKind.TACTICAL && command.tacticalType() == UnitOrderType.PATROL) {
                q.draw(new Line2D.Double(fromX, fromY, command.x1(), command.y1()));
                marker(q, command.x1(), command.y1(), number++);
                q.draw(new Line2D.Double(command.x1(), command.y1(), command.x2(), command.y2()));
                marker(q, command.x2(), command.y2(), number++);
                fromX = command.x2(); fromY = command.y2();
                continue;
            }
            double[] point = point(world, command);
            if (point == null) continue;
            q.draw(new Line2D.Double(fromX, fromY, point[0], point[1]));
            marker(q, point[0], point[1], number++);
            fromX = point[0]; fromY = point[1];
            if (command.kind() == QueuedCommandKind.WORMHOLE) break;
        }
        q.dispose();
    }

    private static double[] point(World world, QueuedUnitCommand command) {
        return switch (command.kind()) {
            case MOVE -> new double[]{command.x1(), command.y1()};
            case ATTACK -> targetPoint(world, command.targetKey());
            case HARVEST -> {
                ResourceNode node = world.findResource(command.resourceId());
                yield node == null ? null : new double[]{node.x, node.y};
            }
            case TACTICAL -> switch (command.tacticalType()) {
                case ATTACK_MOVE -> new double[]{command.x2(), command.y2()};
                case GUARD, HOLD -> new double[]{command.x1(), command.y1()};
                case ESCORT -> targetPoint(world, command.targetKey());
                case PATROL, NONE -> null;
            };
            case WORMHOLE -> {
                WormholeGate gate = null;
                for (WormholeGate candidate : world.wormholes) if (command.gateId().equals(candidate.id)) { gate = candidate; break; }
                yield gate == null ? null : new double[]{gate.x, gate.y};
            }
        };
    }

    private static double[] targetPoint(World world, String key) {
        Unit unit = CombatTarget.unit(world, key);
        if (unit != null) return new double[]{unit.x, unit.y};
        Base base = CombatTarget.base(world, key);
        return base == null ? null : new double[]{base.x, base.y};
    }

    private static void marker(Graphics2D g2, double x, double y, int number) {
        int cx = (int)Math.round(x), cy = (int)Math.round(y), r = 11;
        g2.fillOval(cx - r, cy - r, r * 2, r * 2);
        g2.setColor(Color.BLACK);
        String text = Integer.toString(number);
        int tw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, cx - tw / 2, cy + 4);
        Color owner = PlayerRegistry.color(PlayerRegistry.localId());
        g2.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 205));
    }
}
