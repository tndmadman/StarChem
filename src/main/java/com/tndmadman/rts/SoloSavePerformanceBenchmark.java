package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Report-only benchmark of immutable capture, verified disk promotion, and restart load.
 * Includes the project's maximum supported 64-system procedural galaxy.
 */
public final class SoloSavePerformanceBenchmark {
    private SoloSavePerformanceBenchmark() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        GalaxyRuntimeOptions.State previous = GalaxyRuntimeOptions.snapshot();
        Path root = Files.createTempDirectory("starchem-solo-perf-");
        try {
            for (int systems : new int[]{20, GalaxyGenerationSettings.MAX_SYSTEMS}) {
                GalaxyGenerationSettings generation = new GalaxyGenerationSettings(
                        true, systems, GalaxyTopologyStyle.RING,
                        0.20, 0.10, 1.0, 0.15, 0.15, 0.5, 2, Map.of());
                long proceduralSeed = 47813L + systems;
                GalaxyRuntimeOptions.configureGeneration(generation, proceduralSeed);
                Config config = Config.solo("Save Performance");
                World world = new World("Save Performance", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
                World restored = null;
                try {
                    PlayerRegistry.activate(world);
                    PlayerRegistry.reset("SOLO", "Save Performance", 0x50BEFF);
                    if (world.simulationSystemIdsForTesting().size() != systems) {
                        throw new IllegalStateException("Generated system count differs from requested size.");
                    }
                    SoloSaveService service = new SoloSaveService(root.resolve("size-" + systems));
                    SoloSaveService.Slot slot = service.create("Benchmark " + systems);
                    SoloSaveService.activate(world, slot.id());
                    long started = System.nanoTime();
                    SoloSaveService.CapturedSave captured = service.capture(slot.id(), world, config, "manual");
                    long afterCapture = System.nanoTime();
                    service.write(captured);
                    long afterDisk = System.nanoTime();
                    restored = service.load(slot.id(), config);
                    long afterLoad = System.nanoTime();
                    if (restored.simulationSystemIdsForTesting().size() != systems) {
                        throw new IllegalStateException("Restored galaxy lost systems.");
                    }
                    if (restored.systemSeed() != world.systemSeed()) {
                        throw new IllegalStateException("Restored seed differs.");
                    }
                    long archiveBytes = Files.size(root.resolve("size-" + systems)
                            .resolve(slot.id()).resolve(SoloSaveService.ARCHIVE));
                    Runtime runtime = Runtime.getRuntime();
                    long usedHeap = runtime.totalMemory() - runtime.freeMemory();
                    System.out.printf("SOLO-SAVE-PERF systems=%d captureMs=%.2f writeMs=%.2f "
                                    + "restoreMs=%.2f archiveMB=%.2f usedHeapMB=%.2f%n",
                            systems, (afterCapture - started) / 1_000_000.0,
                            (afterDisk - afterCapture) / 1_000_000.0,
                            (afterLoad - afterDisk) / 1_000_000.0,
                            archiveBytes / 1_048_576.0, usedHeap / 1_048_576.0);
                } finally {
                    if (restored != null) restored.dispose();
                    world.dispose();
                }
            }
        } finally {
            GalaxyRuntimeOptions.restore(previous);
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("Solo save/load supported-galaxy benchmark passed.");
    }
}
