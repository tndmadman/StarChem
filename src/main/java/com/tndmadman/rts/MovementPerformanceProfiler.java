package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Repeatable movement/fog profiler for issue #372.
 *
 * With --gate it also enforces deliberately conservative automated ratios. The stricter
 * 10-15% moving-vs-stationary release target remains a real-machine acceptance check because
 * shared CI timing is too noisy for a 1.15x threshold.
 */
public final class MovementPerformanceProfiler {
    private static final int[] SHIP_COUNTS = {1, 10, 20, 50, 100, 250};
    private static final double DT = 1.0 / 60.0;
    private static final int WARMUP_ITERATIONS = 80;
    private static final int MEASURE_ITERATIONS = 120;
    private static final double AUTOMATED_MOVING_RATIO_LIMIT = 6.0;
    private static final double AUTOMATED_SCALING_LIMIT = 12.0;

    private MovementPerformanceProfiler() { }

    public static void main(String[] args) {
        boolean gate = false;
        for (String arg : args) if ("--gate".equalsIgnoreCase(arg)) gate = true;

        List<Result> results = new ArrayList<>();
        System.out.println("movement-profiler mode=" + (gate ? "gate" : "report"));
        System.out.println("ships  stationary-ms  moving-ms  ratio  tile-rebuilds/move");
        for (int ships : SHIP_COUNTS) {
            Result result = measure(ships);
            results.add(result);
            System.out.printf("%5d  %13.5f  %9.5f  %5.2fx  %8.3f%n",
                    ships, result.stationaryMillis, result.movingMillis, result.ratio(),
                    result.tileRebuildsPerMove);
        }

        if (gate) enforceGates(results);
    }

    private static Result measure(int shipCount) {
        Scenario scenario = scenario(shipCount);
        Graphics2D graphics = scenario.graphics;

        setStationary(scenario.ships);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            FogOfWarView.forceRefreshForTest(scenario.world);
            FogOfWarView.drawWorld(graphics, scenario.world);
        }

        setStationary(scenario.ships);
        double stationary = timeMillis(MEASURE_ITERATIONS, () -> {
            scenario.world.systemTime += DT;
            FogOfWarView.forceRefreshForTest(scenario.world);
            FogOfWarView.drawWorld(graphics, scenario.world);
        }) / MEASURE_ITERATIONS;

        setStationary(scenario.ships);
        final int[] step = {0};
        long tileBefore = FogOfWarView.visualFogRebuildCountForTest(scenario.world);
        double moving = timeMillis(MEASURE_ITERATIONS, () -> {
            retargetOrbit(scenario.ships, ++step[0]);
            ClientPrediction.update(scenario.world, DT);
            scenario.world.systemTime += DT;
            FogOfWarView.forceRefreshForTest(scenario.world);
            FogOfWarView.drawWorld(graphics, scenario.world);
        }) / MEASURE_ITERATIONS;
        long tileAfter = FogOfWarView.visualFogRebuildCountForTest(scenario.world);

        graphics.dispose();
        return new Result(shipCount, stationary, moving,
                Math.max(0, tileAfter - tileBefore) / (double)MEASURE_ITERATIONS);
    }

    private static Scenario scenario(int shipCount) {
        World world = new World("Movement profiler", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Profiler", 0x50BEFF);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();

        List<Unit> ships = new ArrayList<>(shipCount);
        for (int i = 0; i < shipCount; i++) {
            int column = i % 20;
            int row = i / 20;
            Unit unit = new Unit("P1", i + 1, "frigate",
                    260 + column * 42, 220 + row * 42);
            world.units.put(unit.key(), unit);
            ships.add(unit);
        }

        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = image.createGraphics();
        graphics.setClip(0, 0, image.getWidth(), image.getHeight());
        FogOfWarView.clearCachedStateForTest(world);
        FogOfWarView.forceRefreshForTest(world);
        FogOfWarView.drawWorld(graphics, world);
        FogOfWarView.clearDirtyFogTilesForTest(world);
        return new Scenario(world, ships, graphics);
    }

    private static void enforceGates(List<Result> results) {
        Result twenty = find(results, 20);
        Result fifty = find(results, 50);
        Result hundred = find(results, 100);
        Result twoFifty = find(results, 250);

        require(twenty.ratio() <= AUTOMATED_MOVING_RATIO_LIMIT,
                "20-ship moving/stationary CPU ratio regressed to " + decimal(twenty.ratio())
                        + "x (limit " + AUTOMATED_MOVING_RATIO_LIMIT + "x).");

        double hundredVsTwenty = hundred.movingMillis / Math.max(0.001, twenty.movingMillis);
        require(hundredVsTwenty <= AUTOMATED_SCALING_LIMIT,
                "100-ship moving fog cost scales too sharply vs 20 ships: "
                        + decimal(hundredVsTwenty) + "x.");

        double twoFiftyVsFifty = twoFifty.movingMillis / Math.max(0.001, fifty.movingMillis);
        require(twoFiftyVsFifty <= AUTOMATED_SCALING_LIMIT,
                "250-ship moving fog cost scales too sharply vs 50 ships: "
                        + decimal(twoFiftyVsFifty) + "x.");

        require(twenty.tileRebuildsPerMove <= 8.0,
                "20 moving ships rebuilt too many visual fog tiles per update: "
                        + decimal(twenty.tileRebuildsPerMove));

        System.out.println("Movement performance gate passed.");
    }

    private static Result find(List<Result> results, int ships) {
        for (Result result : results) if (result.ships == ships) return result;
        throw new IllegalStateException("Missing profiler result for " + ships + " ships.");
    }

    private static void setStationary(List<Unit> ships) {
        for (Unit unit : ships) {
            unit.task = UnitTask.IDLE;
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.afterburnerActive = false;
        }
    }

    private static void retargetOrbit(List<Unit> ships, int step) {
        double t = step * DT * 0.35;
        for (int i = 0; i < ships.size(); i++) {
            Unit unit = ships.get(i);
            unit.task = UnitTask.IDLE;
            double angle = t * ((i & 1) == 0 ? 1 : -1) + i * 0.63;
            unit.targetX = 760 + Math.cos(angle) * (180 + (i % 5) * 8);
            unit.targetY = 500 + Math.sin(angle) * (180 + (i % 7) * 6);
        }
    }

    private static double timeMillis(int iterations, Runnable action) {
        long started = System.nanoTime();
        for (int i = 0; i < iterations; i++) action.run();
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Scenario(World world, List<Unit> ships, Graphics2D graphics) { }

    private record Result(int ships, double stationaryMillis, double movingMillis,
                          double tileRebuildsPerMove) {
        double ratio() {
            return movingMillis / Math.max(0.05, stationaryMillis);
        }
    }
}
