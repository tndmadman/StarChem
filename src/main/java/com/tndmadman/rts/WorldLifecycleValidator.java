package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Deterministic registration/teardown assertions without forced garbage collection. */
final class WorldLifecycleValidator {
    private WorldLifecycleValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        StarSystemDefinition definition = StarSystems.defaultSystem();
        WorldSystemState isolated = new WorldSystemState("isolated", definition,
                new CelestialSystem(definition, new Random(1234)));
        require(isolated.registeredForTesting(), "new standalone system registered");
        isolated.dispose();
        require(!isolated.registeredForTesting(), "standalone system unregistered");
        isolated.dispose(); // idempotent

        for (int i = 0; i < 2; i++) {
            World world = new World("lifecycle", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            List<WorldSystemState> initial = world.policySystemStates();
            require(!initial.isEmpty(), "constructed galaxy must have systems");
            for (WorldSystemState state : initial) require(state.registeredForTesting(), "live state registered");

            // Dynamic player-home systems must be removed without affecting static live systems.
            world.ensurePlayerHome("P2", false);
            String deletedHomeId = world.playerHomeSystemId("P2");
            WorldSystemState removedHome = null;
            for (WorldSystemState candidate : world.policySystemStates()) {
                if (candidate.id.equals(deletedHomeId)) removedHome = candidate;
            }
            require(removedHome != null && removedHome.registeredForTesting(),
                    "new player home registered");
            require(world.removePlayerAndPruneEmptySystems("P2").contains(deletedHomeId),
                    "empty player home pruned");
            require(!removedHome.registeredForTesting(), "pruned system unregistered");
            for (WorldSystemState state : initial)
                require(state.registeredForTesting(), "removing home does not unregister static systems");

            String previous = world.activeSystemId();
            for (WorldSystemState state : initial) {
                world.activateSystem(state.id);
                require(state.registeredForTesting(), "system switch must not unregister live state");
            }
            world.activateSystem(previous);
            Map<String,Object> saved = world.captureServerSaveGalaxy();
            List<Object> rows = new ArrayList<>(ServerSaveStore.list(saved.get("systems")));
            require(!rows.isEmpty(), "save must include system rows");
            rows.add(rows.get(0)); // An invalid duplicated restored ID must fail closed.
            Map<String,Object> malformedSave = new LinkedHashMap<>(saved);
            malformedSave.put("systems", rows);
            boolean rejected = false;
            try {
                world.restoreServerSaveGalaxy(malformedSave);
            } catch (IllegalArgumentException expected) {
                rejected = expected.getMessage() != null && expected.getMessage().contains("Duplicate system ID");
            }
            require(rejected, "duplicate saved galaxy system IDs must be rejected");
            for (WorldSystemState old : initial) {
                require(old.registeredForTesting(),
                        "duplicate save IDs must be rejected before tearing down the live galaxy");
            }
            require(world.policySystemStates().size() == initial.size(),
                    "duplicate save IDs must preserve the original live systems");

            world.restoreServerSaveGalaxy(saved);
            for (WorldSystemState state : initial)
                require(!state.registeredForTesting(), "save restore must unregister replaced state");
            List<WorldSystemState> restored = world.policySystemStates();
            for (WorldSystemState state : restored)
                require(state.registeredForTesting(), "restored state must be registered");

            world.syncEnvironment(world.systemId(), world.systemSeed() + 1, 0);
            for (WorldSystemState state : restored)
                require(!state.registeredForTesting(), "match restart must unregister old state");
            List<WorldSystemState> restarted = world.policySystemStates();
            for (WorldSystemState state : restarted)
                require(state.registeredForTesting(), "restart replacement registered");

            // The server and graphical client both use World.dispose. Verify teardown
            // also removes non-celestial world-scoped state, not just galaxy systems.
            Map<String,Object> fakeSavedPlan = Map.of("plans", List.of(Map.of(
                    "id", "AP-test",
                    "systemId", world.activeSystemId(),
                    "playerId", "P1",
                    "root", Map.of(
                            "kind", "SHIP",
                            "itemId", Rules.STARTING_SHIP,
                            "targetBaseId", "P1:B-test",
                            "productionJobId", "J-test"))));
            ProductionPlanner.restore(world, fakeSavedPlan);
            require(ProductionPlanner.containsWorldForTesting(world),
                    "restored auto-production planner must be associated with its live world");
            SystemSimulationScheduler.step(world, 0.1);
            require(SystemSimulationScheduler.containsWorldForTesting(world),
                    "inactive simulation cadence must be associated with its live world");

            world.dispose();
            require(!ProductionPlanner.containsWorldForTesting(world),
                    "world disposal must remove strong-keyed auto-production planner state");
            require(!SystemSimulationScheduler.containsWorldForTesting(world),
                    "world disposal must remove deferred simulation time");
            for (WorldSystemState state : restarted)
                require(!state.registeredForTesting(), "world dispose unregisters all systems");
            world.dispose();
        }
        System.out.println("World lifecycle validation passed.");
    }

    private static void require(boolean okay, String description) {
        if (!okay) throw new IllegalStateException(description);
    }
}
