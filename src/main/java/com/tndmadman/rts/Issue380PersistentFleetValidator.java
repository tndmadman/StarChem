package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Focused deterministic coverage for issue #380 persistent strategic fleets. */
public final class Issue380PersistentFleetValidator {
    private Issue380PersistentFleetValidator() { }

    public static void main(String[] args) {
        validateFleetLifecycleAndOwnership();
        validateSplitMergeAndUniqueMembership();
        validateRuntimePersistence();
        validateDestroyedMemberCleanup();
        validateStrategicWormholeTranslation();
        validateCombatPolicyTranslation();
        System.out.println("Issue 380 persistent fleet validation passed.");
    }

    private static void validateFleetLifecycleAndOwnership() {
        World world = world("Fleet lifecycle");
        Unit first = soloUnit(world);
        Unit second = addSoloUnit(world, first.x + 55, first.y + 35);

        FleetCreateResult created = FleetManager.create(world, "SOLO", "First Fleet",
                List.of(first.key(), second.key()));
        require(created.result() == FleetMutationResult.APPLIED && created.fleetId() > 0,
                "owned ships should create a persistent fleet");
        FleetView initial = requireView(world, "SOLO", created.fleetId());
        require(initial.memberKeys().equals(Set.of(first.key(), second.key())),
                "fleet should retain the exact owned membership");
        require(initial.shipsBySystem().getOrDefault(world.activeSystemId(), 0) == 2,
                "fleet location should be derived from authoritative ship locations");

        require(FleetManager.rename(world, "OTHER", created.fleetId(), initial.revision(), "Hijacked")
                        == FleetMutationResult.FORBIDDEN,
                "another owner must not rename the fleet");
        require(FleetManager.disband(world, "OTHER", created.fleetId(), initial.revision())
                        == FleetMutationResult.FORBIDDEN,
                "another owner must not disband the fleet");
        require(FleetManager.rename(world, "SOLO", created.fleetId(), initial.revision() + 9, "Stale")
                        == FleetMutationResult.CONFLICT,
                "stale fleet revisions must be rejected");
        require(FleetManager.rename(world, "SOLO", created.fleetId(), initial.revision(), "Vanguard")
                        == FleetMutationResult.APPLIED,
                "current fleet revision should allow rename");
        require("Vanguard".equals(requireView(world, "SOLO", created.fleetId()).name()),
                "renamed fleet should expose the authoritative name");
    }

    private static void validateSplitMergeAndUniqueMembership() {
        World world = world("Fleet split merge");
        Unit first = soloUnit(world);
        Unit second = addSoloUnit(world, first.x + 60, first.y);
        Unit third = addSoloUnit(world, first.x + 120, first.y);

        FleetCreateResult created = FleetManager.create(world, "SOLO", "Line Fleet",
                List.of(first.key(), second.key(), third.key()));
        require(created.result() == FleetMutationResult.APPLIED, "base fleet should create");
        require(FleetManager.create(world, "SOLO", "Duplicate", List.of(second.key())).result()
                        == FleetMutationResult.CONFLICT,
                "one ship must not belong to two persistent fleets");

        FleetView source = requireView(world, "SOLO", created.fleetId());
        FleetCreateResult split = FleetManager.split(world, "SOLO", created.fleetId(),
                source.revision(), "Detached Wing", List.of(third.key()));
        require(split.result() == FleetMutationResult.APPLIED && split.fleetId() != created.fleetId(),
                "split should create a new stable fleet identity");
        require(FleetManager.fleetIdForUnit(world, third.key()) == split.fleetId(),
                "split member should point at the new fleet");
        require(requireView(world, "SOLO", created.fleetId()).memberKeys().size() == 2,
                "source fleet should lose the split member");

        FleetView destinationBeforeMerge = requireView(world, "SOLO", created.fleetId());
        FleetView sourceBeforeMerge = requireView(world, "SOLO", split.fleetId());
        require(FleetManager.merge(world, "SOLO", created.fleetId(), split.fleetId(),
                destinationBeforeMerge.revision(), sourceBeforeMerge.revision()) == FleetMutationResult.APPLIED,
                "owned fleets should merge with matching revisions");
        require(FleetManager.view(world, "SOLO", split.fleetId()).isEmpty(),
                "merged source fleet should cease to exist");
        require(requireView(world, "SOLO", created.fleetId()).memberKeys().size() == 3,
                "merge should preserve all members");
        require(FleetManager.fleetIdForUnit(world, third.key()) == created.fleetId(),
                "reverse membership index should move with a merge");
    }

    private static void validateRuntimePersistence() {
        World world = world("Fleet persistence");
        Unit first = soloUnit(world);
        Unit second = addSoloUnit(world, first.x + 50, first.y + 25);
        FleetCreateResult created = FleetManager.create(world, "SOLO", "Persistent Fleet",
                List.of(first.key(), second.key()));
        require(created.result() == FleetMutationResult.APPLIED, "persistence fleet should create");
        FleetView before = requireView(world, "SOLO", created.fleetId());
        require(FleetManager.setFormation(world, "SOLO", created.fleetId(), before.revision(), FleetFormation.WEDGE)
                        == FleetMutationResult.APPLIED,
                "formation should persist as fleet state");
        FleetView afterFormation = requireView(world, "SOLO", created.fleetId());
        require(FleetManager.setHomeAndRally(world, "SOLO", created.fleetId(), afterFormation.revision(),
                "B1", world.activeSystemId(), first.x + 90, first.y + 70) == FleetMutationResult.APPLIED,
                "home/rally state should be accepted");

        Map<String,Object> galaxy = world.captureServerSaveGalaxy();
        Map<String,Object> runtime = world.captureServerSaveRuntime();

        World restored = new World("Fleet persistence restored", Set.of(), world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        restored.restoreServerSaveRuntime(runtime);
        FleetView loaded = requireView(restored, "SOLO", created.fleetId());
        require("Persistent Fleet".equals(loaded.name()), "fleet name should survive runtime restore");
        require(loaded.memberKeys().equals(Set.of(first.key(), second.key())),
                "fleet membership should survive runtime restore");
        require(loaded.formation() == FleetFormation.WEDGE,
                "fleet formation should survive runtime restore");
        require(world.activeSystemId().equals(loaded.rallySystemId()),
                "fleet rally system should survive runtime restore");
        require(FleetManager.fleetIdForUnit(restored, second.key()) == created.fleetId(),
                "reverse membership index should rebuild during restore");
    }

    private static void validateDestroyedMemberCleanup() {
        World world = world("Fleet destruction cleanup");
        Unit first = soloUnit(world);
        Unit second = addSoloUnit(world, first.x + 45, first.y + 45);
        FleetCreateResult created = FleetManager.create(world, "SOLO", "Survivors",
                List.of(first.key(), second.key()));
        require(created.result() == FleetMutationResult.APPLIED, "cleanup fleet should create");

        second.hp = 0;
        world.updateCurrentSystem(0.05);
        FleetView surviving = requireView(world, "SOLO", created.fleetId());
        require(!surviving.memberKeys().contains(second.key()) && surviving.memberKeys().contains(first.key()),
                "destroyed ships should be removed from persistent membership");
        require(FleetManager.fleetIdForUnit(world, second.key()) == 0,
                "destroyed ship must be removed from reverse membership index");
    }

    private static void validateStrategicWormholeTranslation() {
        World world = world("Fleet strategic travel");
        Unit first = soloUnit(world);
        Unit second = addSoloUnit(world, first.x + 40, first.y + 35);
        require(!world.wormholes.isEmpty(), "fleet travel fixture requires a wormhole");
        WormholeGate gate = world.wormholes.get(0);
        FleetCreateResult created = FleetManager.create(world, "SOLO", "Transit Fleet",
                List.of(first.key(), second.key()));
        require(created.result() == FleetMutationResult.APPLIED, "travel fleet should create");

        FleetCommandResult result = FleetCommandService.moveToSystem(world, "SOLO", created.fleetId(), gate.toSystemId);
        require(result.applied() && result.orderId() > 0,
                "fleet cross-system movement should compile into authoritative ship queues");
        for (String key : List.of(first.key(), second.key())) {
            List<QueuedUnitCommand> queue = UnitCommandQueueSystem.commands(world, key);
            require(!queue.isEmpty() && queue.get(0).kind() == QueuedCommandKind.WORMHOLE,
                    "each fleet member should receive an existing wormhole queue command");
            require(gate.toSystemId.equals(queue.get(0).destinationSystemId()),
                    "compiled wormhole command should preserve destination");
        }
        FleetView fleet = requireView(world, "SOLO", created.fleetId());
        require(fleet.order().type() == FleetOrderType.MOVE_TO_SYSTEM
                        && fleet.order().state() == FleetOrderState.EXECUTING,
                "fleet should expose the strategic order above unit queues");
        require(FleetCommandService.moveToSystem(world, "OTHER", created.fleetId(), gate.toSystemId).applied() == false,
                "foreign owners must not issue strategic fleet movement");
    }

    private static void validateCombatPolicyTranslation() {
        World world = world("Fleet combat policy");
        Unit first = soloUnit(world);
        Unit second = addSoloUnit(world, first.x + 30, first.y + 30);
        FleetCreateResult created = FleetManager.create(world, "SOLO", "Policy Fleet",
                List.of(first.key(), second.key()));
        require(created.result() == FleetMutationResult.APPLIED, "policy fleet should create");
        FleetCommandResult result = FleetCommandService.applyCombatPolicy(world, "SOLO", created.fleetId(),
                CombatStance.DEFENSIVE, TargetPriorityPolicy.NEAREST_THREAT);
        require(result.applied(), "fleet combat policy should compile through queue policy mutations");
        for (String key : List.of(first.key(), second.key())) {
            require(UnitCommandQueueSystem.combatStance(world, key) == CombatStance.DEFENSIVE,
                    "member queue policy should reflect fleet combat stance");
        }
        require(requireView(world, "SOLO", created.fleetId()).combatStance() == CombatStance.DEFENSIVE,
                "fleet entity should retain its strategic combat policy");
        require(!FleetCommandService.applyCombatPolicy(world, "OTHER", created.fleetId(),
                CombatStance.PASSIVE, TargetPriorityPolicy.NEAREST_THREAT).applied(),
                "foreign owners must not modify fleet combat policy");
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

    private static Unit addSoloUnit(World world, double x, double y) {
        Unit unit = world.spawnShip(Rules.STARTING_SHIP,
                Calc.clamp(x, 30, world.width - 30), Calc.clamp(y, 30, world.height - 30));
        world.saveActiveSystem();
        return unit;
    }

    private static FleetView requireView(World world, String ownerId, long fleetId) {
        Optional<FleetView> view = FleetManager.view(world, ownerId, fleetId);
        if (view.isEmpty()) throw new IllegalStateException("Fleet " + fleetId + " is missing.");
        return view.get();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
