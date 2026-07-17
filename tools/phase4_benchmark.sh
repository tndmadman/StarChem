#!/usr/bin/env bash
set -euo pipefail
cat > /tmp/AiBrainLogBenchmark.java <<'JAVA'
package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

public final class AiBrainLogBenchmark {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("starchem-ai-log-bench-");
        try {
            AiBrainLog.resetForTests(directory);
            PlayerRegistry.reset("WAIT", "AI Logger Benchmark", 0x50BEFF);
            World world = new World("AI Logger Benchmark",
                    Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                    StarSystems.CORSAIR_SYSTEM_ID, false);
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            world.units.clear();
            world.bases.clear();
            world.resources.clear();

            for (int i = 0; i < 800; i++) {
                Unit unit = new Unit(Config.CORSAIRS_ID, 200_000 + i, "station_builder",
                        500 + (i % 40) * 80, 500 + (i / 40) * 80);
                unit.inventory.put(Material.IRON, 50.0 + i % 20);
                if ((i & 1) == 0) unit.issueMove(unit.x + 200, unit.y + 100);
                world.units.put(unit.key(), unit);
            }
            for (int i = 0; i < 80; i++) {
                Base base = new Base(Config.CORSAIRS_ID + ":BENCH-B" + i,
                        Config.CORSAIRS_ID, "outpost",
                        1000 + (i % 10) * 450, 1200 + (i / 10) * 450);
                base.inventory.put(Material.COPPER, 500.0 + i);
                world.bases.put(base.id, base);
            }

            world.systemTime += 1.1;
            AiBrainLog.observe(world);
            for (int warm = 0; warm < 20; warm++) {
                mutate(world, warm);
                world.systemTime += 1.1;
                AiBrainLog.observe(world);
            }

            long[] scans = new long[160];
            for (int sample = 0; sample < scans.length; sample++) {
                mutate(world, sample);
                world.systemTime += 1.1;
                long start = System.nanoTime();
                AiBrainLog.observe(world);
                scans[sample] = System.nanoTime() - start;
            }

            long[] events = new long[5000];
            for (int i = 0; i < events.length; i++) {
                long start = System.nanoTime();
                AiBrainLog.event(world, "BENCH", "event", "event-" + i);
                events[i] = System.nanoTime() - start;
            }

            if (!AiBrainLog.awaitIdleForTests(10_000)) {
                throw new IllegalStateException("async writer did not drain benchmark records");
            }
            AiBrainLog.setEnabled(false);

            double scanP99 = percentileMs(scans, 0.99);
            double eventP99 = percentileMs(events, 0.99);
            String metrics = String.format(
                    "full-scan count=%d p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n" +
                    "event-submit count=%d p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n",
                    scans.length, percentileMs(scans, 0.50), percentileMs(scans, 0.95),
                    scanP99, maxMs(scans), events.length, percentileMs(events, 0.50),
                    percentileMs(events, 0.95), eventP99, maxMs(events));
            System.out.print(metrics);
            Files.writeString(Path.of("logger-benchmark.txt"), metrics);
            if (scanP99 >= 8.0) {
                throw new IllegalStateException("simulation-thread logger p99 exceeded 8ms: " + scanP99);
            }
        } finally {
            AiBrainLog.closeForTests();
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void mutate(World world, int sample) {
        for (int i = sample % 10; i < 800; i += 10) {
            Unit unit = world.units.get(Unit.key(Config.CORSAIRS_ID, 200_000 + i));
            unit.x += 6.0;
            unit.inventory.merge(Material.IRON, 0.25, Double::sum);
        }
    }

    private static double percentileMs(long[] samples, double percentile) {
        long[] copy = samples.clone();
        Arrays.sort(copy);
        int index = Math.min(copy.length - 1,
                Math.max(0, (int)Math.ceil(copy.length * percentile) - 1));
        return copy[index] / 1_000_000.0;
    }

    private static double maxMs(long[] samples) {
        long max = 0;
        for (long sample : samples) max = Math.max(max, sample);
        return max / 1_000_000.0;
    }
}
JAVA
mkdir -p /tmp/ai-log-bench
javac -cp build/classes/java/main -d /tmp/ai-log-bench /tmp/AiBrainLogBenchmark.java
java -cp build/classes/java/main:/tmp/ai-log-bench com.tndmadman.rts.AiBrainLogBenchmark
