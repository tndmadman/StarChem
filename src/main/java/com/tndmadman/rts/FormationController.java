package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime formation controller. It reconstructs group membership from the
 * authoritative per-unit queue heads, so destroyed/detached members disappear
 * naturally and survivors are re-slotted without replacing queue semantics.
 */
final class FormationController {
    // Keep formation readiness aligned with UnitCommandQueueSystem's move
    // completion radius. A wider readiness radius lets one member leave the
    // queue while another is still approaching its slot.
    private static final double ARRIVAL_DISTANCE = 7.0;

    record RuntimeTarget(double x, double y, double pace, boolean groupReady) { }

    private enum Mode { MOVE, WORMHOLE_SOURCE, WORMHOLE_DESTINATION }

    private record GroupKey(String token, FleetFormation formation, Mode mode,
                            long anchorXBits, long anchorYBits,
                            long forwardXBits, long forwardYBits, String gateId) { }

    private record GroupSeed(GroupKey key, double anchorX, double anchorY,
                             double forwardX, double forwardY, String gateId,
                             List<String> unitKeys) { }

    /**
     * Once every member has reached its final slot, keep those exact slots
     * latched while individual queue heads drain. Without this latch, the first
     * member to complete disappears from formation membership, causing an N-1
     * re-plan that makes the remaining ships shuffle across the destination.
     */
    private record SettledGroup(List<String> memberKeys,
                                Map<String, FleetFormationPlanner.Target> targets,
                                double pace) { }

    private record Cache(String systemId, long timeBits,
                         Map<String, RuntimeTarget> movementTargets,
                         Map<String, FleetFormationPlanner.Target> escortTargets) { }

    private static final Map<World, Cache> CACHES = new WeakHashMap<>();
    private static final Map<World, Map<GroupKey, SettledGroup>> SETTLED_GROUPS = new WeakHashMap<>();

    private FormationController() { }

    static void invalidate(World world) {
        if (world != null) CACHES.remove(world);
    }

    static void clear(World world) {
        invalidate(world);
        if (world != null) SETTLED_GROUPS.remove(world);
    }

    static double arrivalDistance() {
        return ARRIVAL_DISTANCE;
    }

    static RuntimeTarget target(World world, Unit unit, QueuedUnitCommand command) {
        if (world == null || unit == null || command == null) return null;
        FormationIntent intent = FormationIntent.parse(command);
        if (intent == null) return fallback(world, command);
        RuntimeTarget target = cache(world).movementTargets().get(unit.key());
        return target == null ? fallback(world, command) : target;
    }

    static FleetFormationPlanner.Target escortTarget(World world, Unit unit, Unit escorted) {
        if (world == null || unit == null || escorted == null) return null;
        return cache(world).escortTargets().get(unit.key());
    }

    static double cohesionCap(double basePace, double ownRemaining, double anchorRemaining) {
        if (!Double.isFinite(basePace) || basePace <= 0) return 0;
        double error = ownRemaining - anchorRemaining;
        double tolerance = FleetFormationPlanner.cohesionTolerance();
        if (error > tolerance) return Math.min(1200.0, basePace * 1.22);
        if (error < -tolerance) return Math.max(1.0, basePace * 0.82);
        return Math.min(1200.0, basePace);
    }

    /**
     * Return the settled targets for the still-active members without changing
     * their geometry. Kept package-private so the formation validator can
     * regression-test the arrival handoff directly.
     */
    static Map<String, FleetFormationPlanner.Target> retainedSettledTargets(
            Map<String, FleetFormationPlanner.Target> settledTargets, List<String> activeKeys) {
        if (settledTargets == null || settledTargets.isEmpty() || activeKeys == null || activeKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, FleetFormationPlanner.Target> retained = new LinkedHashMap<>();
        for (String key : activeKeys) {
            FleetFormationPlanner.Target target = settledTargets.get(key);
            if (target == null) return Map.of();
            retained.put(key, target);
        }
        return Map.copyOf(retained);
    }

    private static Cache cache(World world) {
        String systemId = world.activeSystemId();
        long timeBits = Double.doubleToLongBits(world.systemTime());
        Cache cached = CACHES.get(world);
        if (cached != null && cached.timeBits() == timeBits && cached.systemId().equals(systemId)) return cached;
        Cache built = build(world, systemId, timeBits);
        CACHES.put(world, built);
        return built;
    }

    private static Cache build(World world, String systemId, long timeBits) {
        Map<GroupKey, GroupSeed> groups = new LinkedHashMap<>();
        Map<String, List<String>> escortGroups = new LinkedHashMap<>();

        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0) continue;
            QueuedUnitCommand command = UnitCommandQueueSystem.activeCommand(world, unit.key());
            FormationIntent intent = FormationIntent.parse(command);
            if (intent != null) addFormationGroup(world, systemId, unit, command, intent, groups);

            if (unit.orderType == UnitOrderType.ESCORT && unit.orderTarget != null && !unit.orderTarget.isBlank()) {
                String group = unit.playerId + "\n" + unit.orderTarget;
                escortGroups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(unit.key());
            }
        }

        Map<GroupKey, SettledGroup> settledGroups = SETTLED_GROUPS.computeIfAbsent(world,
                ignored -> new LinkedHashMap<>());
        // If a settled formation has fully drained from the queue, its latch is
        // no longer relevant. This also prevents a future command that reuses a
        // token from inheriting stale geometry.
        settledGroups.keySet().removeIf(key -> !groups.containsKey(key));

        Map<String, RuntimeTarget> movementTargets = new LinkedHashMap<>();
        for (GroupSeed seed : groups.values()) {
            if (seed.unitKeys().isEmpty()) continue;
            if (seed.key().mode() == Mode.WORMHOLE_SOURCE) {
                // Source-side wormhole movement is not a terminal formation
                // arrival. The command must remain intact for destination
                // regrouping, so never latch source-gate geometry.
                settledGroups.remove(seed.key());
                WormholeGate gate = gate(world, seed.gateId());
                if (gate == null) continue;
                FleetFormationPlanner.Plan pacePlan = FleetFormationPlanner.plan(world, seed.unitKeys(),
                        seed.key().formation(), gate.x, gate.y, seed.forwardX(), seed.forwardY());
                for (String key : seed.unitKeys()) {
                    movementTargets.put(key, new RuntimeTarget(gate.x, gate.y, pacePlan.pace(), true));
                }
                continue;
            }

            SettledGroup settled = settledGroups.get(seed.key());
            if (settled != null && settledStillCompleting(world, seed, settled)) {
                Map<String, FleetFormationPlanner.Target> retained =
                        retainedSettledTargets(settled.targets(), seed.unitKeys());
                if (retained.size() == seed.unitKeys().size()) {
                    for (String key : seed.unitKeys()) {
                        FleetFormationPlanner.Target target = retained.get(key);
                        movementTargets.put(key,
                                new RuntimeTarget(target.x(), target.y(), settled.pace(), true));
                    }
                    continue;
                }
            }
            if (settled != null) settledGroups.remove(seed.key());

            FleetFormationPlanner.Plan plan = FleetFormationPlanner.plan(world, seed.unitKeys(),
                    seed.key().formation(), seed.anchorX(), seed.anchorY(), seed.forwardX(), seed.forwardY());
            FleetFormationPlanner.Target anchorTarget = plan.target(plan.anchorKey());
            Unit anchor = world.units.get(plan.anchorKey());
            double anchorRemaining = anchor == null || anchorTarget == null ? 0
                    : Calc.distance(anchor.x, anchor.y, anchorTarget.x(), anchorTarget.y());

            List<String> expected = activeExpectedMembers(world, seed.key().token());
            boolean everyExpectedMemberPresent = expected.size() == seed.unitKeys().size()
                    && seed.unitKeys().containsAll(expected);
            boolean everySlotReached = everyExpectedMemberPresent;
            if (everySlotReached) {
                for (String key : seed.unitKeys()) {
                    Unit member = world.units.get(key);
                    FleetFormationPlanner.Target target = plan.target(key);
                    if (member == null || target == null
                            || Calc.distance(member.x, member.y, target.x(), target.y()) > ARRIVAL_DISTANCE) {
                        everySlotReached = false;
                        break;
                    }
                }
            }
            boolean groupReady = everyExpectedMemberPresent && everySlotReached;

            if (groupReady) {
                settledGroups.put(seed.key(), new SettledGroup(
                        List.copyOf(seed.unitKeys()), Map.copyOf(plan.targets()), plan.pace()));
            }

            for (String key : seed.unitKeys()) {
                Unit member = world.units.get(key);
                FleetFormationPlanner.Target target = plan.target(key);
                if (member == null || target == null) continue;
                double ownRemaining = Calc.distance(member.x, member.y, target.x(), target.y());
                double pace = cohesionCap(plan.pace(), ownRemaining, anchorRemaining);
                movementTargets.put(key, new RuntimeTarget(target.x(), target.y(), pace, groupReady));
            }
        }

        if (settledGroups.isEmpty()) SETTLED_GROUPS.remove(world);

        Map<String, FleetFormationPlanner.Target> escortTargets = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : escortGroups.entrySet()) {
            int split = entry.getKey().indexOf('\n');
            if (split < 0 || split + 1 >= entry.getKey().length()) continue;
            String owner = entry.getKey().substring(0, split);
            String protectedKey = entry.getKey().substring(split + 1);
            Unit escorted = CombatTarget.unit(world, protectedKey);
            if (escorted == null || escorted.hp <= 0 || !DiplomacySystem.allied(world, owner, escorted.playerId)) continue;
            FleetFormationPlanner.Plan plan = FleetFormationPlanner.escortPlan(world, entry.getValue(), escorted);
            escortTargets.putAll(plan.targets());
        }

        return new Cache(systemId, timeBits, Map.copyOf(movementTargets), Map.copyOf(escortTargets));
    }

    private static boolean settledStillCompleting(World world, GroupSeed seed, SettledGroup settled) {
        if (world == null || seed == null || settled == null || seed.unitKeys().isEmpty()) return false;
        if (seed.unitKeys().size() > settled.memberKeys().size()
                || !settled.memberKeys().containsAll(seed.unitKeys())) return false;
        if (retainedSettledTargets(settled.targets(), seed.unitKeys()).size() != seed.unitKeys().size()) return false;

        // A missing member is considered a normal arrival completion only while
        // it is still alive and sitting at the slot that made the group ready.
        // Destruction or an actual detach/move-away therefore releases the latch
        // and lets survivors compact/re-form as before.
        for (String key : settled.memberKeys()) {
            if (seed.unitKeys().contains(key)) continue;
            Unit member = world.units.get(key);
            FleetFormationPlanner.Target target = settled.targets().get(key);
            if (member == null || member.hp <= 0 || target == null
                    || Calc.distance(member.x, member.y, target.x(), target.y()) > ARRIVAL_DISTANCE) {
                return false;
            }
        }

        // If nobody has drained yet, only keep a pre-existing latch while every
        // member is still inside its settled slot. A later command that happens
        // to reuse the same token/geometry cannot inherit the latch from afar.
        if (seed.unitKeys().size() == settled.memberKeys().size()) {
            for (String key : seed.unitKeys()) {
                Unit member = world.units.get(key);
                FleetFormationPlanner.Target target = settled.targets().get(key);
                if (member == null || target == null
                        || Calc.distance(member.x, member.y, target.x(), target.y()) > ARRIVAL_DISTANCE) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> activeExpectedMembers(World world, String token) {
        List<String> expected = UnitCommandQueueSystem.formationMemberKeys(world, token);
        if (expected.isEmpty()) return expected;
        List<String> active = new ArrayList<>(expected.size());
        for (String key : expected) {
            int split = key.lastIndexOf(':');
            if (split <= 0) continue;
            String playerId = key.substring(0, split);
            String systemId = world.ownerUnitLocations(playerId).get(key);
            if (systemId != null && !systemId.isBlank()) active.add(key);
        }
        return active;
    }

    private static void addFormationGroup(World world, String systemId, Unit unit,
                                          QueuedUnitCommand command, FormationIntent intent,
                                          Map<GroupKey, GroupSeed> groups) {
        if (command == null || !FormationIntent.structurallyValid(command)) return;
        final Mode mode;
        final double anchorX;
        final double anchorY;
        final String gateId;

        if (command.kind() == QueuedCommandKind.MOVE && command.systemId().equals(systemId)) {
            mode = Mode.MOVE;
            anchorX = command.x2();
            anchorY = command.y2();
            gateId = "";
        } else if (command.kind() == QueuedCommandKind.WORMHOLE && command.systemId().equals(systemId)) {
            WormholeGate gate = gate(world, command.gateId());
            if (gate == null) return;
            mode = Mode.WORMHOLE_SOURCE;
            anchorX = gate.x;
            anchorY = gate.y;
            gateId = gate.id;
        } else if (command.kind() == QueuedCommandKind.WORMHOLE
                && command.destinationSystemId().equals(systemId)) {
            mode = Mode.WORMHOLE_DESTINATION;
            anchorX = command.x2();
            anchorY = command.y2();
            gateId = command.gateId();
        } else {
            return;
        }

        GroupKey key = new GroupKey(intent.token(), intent.formation(), mode,
                Double.doubleToLongBits(anchorX), Double.doubleToLongBits(anchorY),
                Double.doubleToLongBits(intent.forwardX()), Double.doubleToLongBits(intent.forwardY()), gateId);
        GroupSeed seed = groups.get(key);
        if (seed == null) {
            seed = new GroupSeed(key, anchorX, anchorY, intent.forwardX(), intent.forwardY(), gateId, new ArrayList<>());
            groups.put(key, seed);
        }
        seed.unitKeys().add(unit.key());
    }

    private static RuntimeTarget fallback(World world, QueuedUnitCommand command) {
        if (command.kind() == QueuedCommandKind.WORMHOLE && command.systemId().equals(world.activeSystemId())) {
            WormholeGate gate = gate(world, command.gateId());
            if (gate != null) return new RuntimeTarget(gate.x, gate.y, command.radius(), true);
        }
        return new RuntimeTarget(command.x1(), command.y1(), command.radius(), true);
    }

    private static WormholeGate gate(World world, String gateId) {
        if (world == null || gateId == null || gateId.isBlank()) return null;
        for (WormholeGate gate : world.wormholes) if (gateId.equals(gate.id)) return gate;
        return null;
    }
}
