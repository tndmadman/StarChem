package com.tndmadman.rts;

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

            world.dispose();
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
