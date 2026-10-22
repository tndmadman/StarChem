package com.tndmadman.rts;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measured scheduler input-preparation comparison on supported procedural galaxy sizes.
 *
 * Baseline measures the former full galaxy-map snapshot input, including entity/resource
 * scans. Candidate measures coordinator immutable roster retrieval, not full simulation time.
 * This is intentionally report-only: hosted runner timing is noisy and correctness is gated.
 */
final class GalaxySchedulerPreparationBenchmark {
    private static volatile int consumed;

    private GalaxySchedulerPreparationBenchmark() { }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        try {
            for (int count : new int[] {20, GalaxyGenerationSettings.MAX_SYSTEMS}) {
                GalaxyRuntimeOptions.configureGeneration(new GalaxyGenerationSettings(
                        true, count, GalaxyTopologyStyle.RING, 0.20, 0.10,
                        1.0, 0.15, 0.15, 0.5, 2, Map.of()), 51973L + count);
                World world = new World("galaxy-benchmark", Set.of(),
                        StarSystems.DEFAULT_SYSTEM_ID, false);
                try {
                    PlayerRegistry.activate(world);
                    require(world.simulationSystemIdsForTesting().size() == count,
                            "requested number of live procedural systems");
                    for (int i = 0; i < 4; i++) {
                        consumed += world.authoritativeGalaxyMapSnapshot().systems().size();
                        consumed += world.simulationSystemIdsForTesting().size();
                    }

                    long[] oldNanos = new long[15];
                    long[] newNanos = new long[15];
                    for (int i = 0; i < oldNanos.length; i++) {
                        // Alternate order to reduce constant runner drift between paths.
                        if ((i & 1) == 0) {
                            oldNanos[i] = fullSnapshotNs(world);
                            newNanos[i] = rosterNs(world);
                        } else {
                            newNanos[i] = rosterNs(world);
                            oldNanos[i] = fullSnapshotNs(world);
                        }
                    }
                    Arrays.sort(oldNanos);
                    Arrays.sort(newNanos);
                    GalaxyInactiveSimulationScheduler scheduler = new GalaxyInactiveSimulationScheduler();
                    scheduler.updateRoster(world, 0.016, world.simulationSystemIdsForTesting());
                    require(scheduler.lastBatchSize() <= GalaxyInactiveSimulationScheduler.MAX_SYSTEMS_PER_FRAME,
                            "four-system simulation budget preserved");
                    require(scheduler.rosterRebuildsForTesting() == 1, "roster built once");
                    System.out.printf("GALAXY-SCHED-PREP systems=%d fullMapSnapshotMedianNs=%d "
                                    + "cachedRosterMedianNs=%d schedulerBatch=%d maxSupported=%d%n",
                            count, oldNanos[oldNanos.length / 2], newNanos[newNanos.length / 2],
                            scheduler.lastBatchSize(), GalaxyGenerationSettings.MAX_SYSTEMS);
                } finally {
                    world.dispose();
                }
            }
        } finally {
            GalaxyRuntimeOptions.clearGenerationOverride();
        }
        System.out.println("Galaxy scheduler preparation benchmark passed.");
    }

    private static long fullSnapshotNs(World world) {
        long started = System.nanoTime();
        int size = world.authoritativeGalaxyMapSnapshot().systems().size();
        long elapsed = System.nanoTime() - started;
        consumed += size;
        return elapsed;
    }

    private static long rosterNs(World world) {
        long started = System.nanoTime();
        int size = world.simulationSystemIdsForTesting().size();
        long elapsed = System.nanoTime() - started;
        consumed += size;
        return elapsed;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
