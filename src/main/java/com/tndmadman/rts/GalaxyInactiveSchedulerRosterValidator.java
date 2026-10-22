package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scheduler roster invalidation and long-lived round-robin cache assertions. */
final class GalaxyInactiveSchedulerRosterValidator {
    private GalaxyInactiveSchedulerRosterValidator() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        World world = new World("roster", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyInactiveSimulationScheduler scheduler = new GalaxyInactiveSimulationScheduler();
        try {
            List<String> initial = world.simulationSystemIdsForTesting();
            require(!initial.isEmpty(), "initial roster populated");
            require(initial == world.simulationSystemIdsForTesting(), "roster reused without mutations");
            for (int i = 0; i < 6; i++)
                scheduler.updateRoster(world, 0.016, initial);
            require(scheduler.rosterRebuildsForTesting() == 1, "unchanged roster rebuilds once");
            require(scheduler.lastBatchSize() <= GalaxyInactiveSimulationScheduler.MAX_SYSTEMS_PER_FRAME,
                    "per-frame budget retained");

            String originalActive = world.activeSystemId();
            if (initial.size() > 1) {
                world.activateSystem(initial.get(1));
                scheduler.updateRoster(world, 0.016, initial);
                require(scheduler.rosterRebuildsForTesting() == 2, "active system change refreshes filtering");
                world.activateSystem(originalActive);
            }

            Map<String,Object> save = world.captureServerSaveGalaxy();
            world.restoreServerSaveGalaxy(save);
            List<String> restored = world.simulationSystemIdsForTesting();
            require(restored != initial, "save restore invalidates old roster identity");
            scheduler.updateRoster(world, 0.016, restored);
            require(scheduler.rosterRebuildsForTesting() >= 2, "restored roster recognized");

            world.syncEnvironment(world.systemId(), world.systemSeed() + 1, 0);
            require(world.simulationSystemIdsForTesting() != restored, "new match invalidates roster");
        } finally {
            world.dispose();
        }
        System.out.println("Galaxy inactive scheduler roster validation passed.");
    }

    private static void require(boolean okay, String message) {
        if (!okay) throw new IllegalStateException(message);
    }
}
