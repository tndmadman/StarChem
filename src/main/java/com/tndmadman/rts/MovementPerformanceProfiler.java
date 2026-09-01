package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Diagnostic micro-profiler for the client-side movement/render paths behind issue #372. */
public final class MovementPerformanceProfiler {
    private static final int SHIPS = 20;
    private static final double DT = 1.0 / 60.0;

    private MovementPerformanceProfiler() { }

    public static void main(String[] args) {
        World world = new World("Movement profiler", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Profiler", 0x50BEFF);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();

        List<Unit> ships = new ArrayList<>();
        for (int i = 0; i < SHIPS; i++) {
            Unit unit = new Unit("P1", i + 1, "frigate", 420 + (i % 5) * 72, 360 + (i / 5) * 72);
            world.units.put(unit.key(), unit);
            ships.add(unit);
        }

        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = image.createGraphics();
        graphics.setClip(0, 0, image.getWidth(), image.getHeight());
        GameCamera camera = new GameCamera();
        camera.update(world, image.getWidth(), image.getHeight(), DT);
        MinimapHud minimap = new MinimapHud();

        // Warm up JIT and graphics paths before reporting anything.
        for (int i = 0; i < 600; i++) ClientPrediction.update(world, DT);
        for (int i = 0; i < 40; i++) {
            world.draw(graphics);
            FogOfWarView.drawWorld(graphics, world);
            minimap.draw(graphics, world, camera, image.getWidth(), image.getHeight());
        }

        setStationary(ships);
        double predictionStationary = timeMillis(3000, () -> ClientPrediction.update(world, DT));

        setStationary(ships);
        final int[] orbitStep = {0};
        double predictionOrbiting = timeMillis(3000, () -> {
            retargetOrbit(ships, ++orbitStep[0]);
            ClientPrediction.update(world, DT);
        });

        setMoveOrders(ships);
        double predictionMove = timeMillis(3000, () -> ClientPrediction.update(world, DT));

        setStationary(ships);
        double drawStationary = timeMillis(300, () -> world.draw(graphics));

        setStationary(ships);
        final int[] drawStep = {0};
        double drawOrbiting = timeMillis(300, () -> {
            retargetOrbit(ships, ++drawStep[0]);
            ClientPrediction.update(world, DT);
            world.draw(graphics);
        });

        setMoveOrders(ships);
        double drawMove = timeMillis(300, () -> world.draw(graphics));

        setStationary(ships);
        FogOfWarView.clearCachedStateForTest(world);
        FogOfWarView.forceRefreshForTest(world);
        double fogStationary = timeMillis(300, () -> FogOfWarView.drawWorld(graphics, world));

        setStationary(ships);
        FogOfWarView.clearCachedStateForTest(world);
        FogOfWarView.forceRefreshForTest(world);
        final int[] fogStep = {0};
        double fogOrbiting = timeMillis(300, () -> {
            retargetOrbit(ships, ++fogStep[0]);
            ClientPrediction.update(world, DT);
            world.systemTime += DT;
            FogOfWarView.forceRefreshForTest(world);
            FogOfWarView.drawWorld(graphics, world);
        });

        setStationary(ships);
        FogOfWarView.clearCachedStateForTest(world);
        double minimapStationary = timeMillis(300, () -> minimap.draw(
                graphics, world, camera, image.getWidth(), image.getHeight()));

        setStationary(ships);
        FogOfWarView.clearCachedStateForTest(world);
        final int[] minimapStep = {0};
        double minimapOrbiting = timeMillis(300, () -> {
            retargetOrbit(ships, ++minimapStep[0]);
            ClientPrediction.update(world, DT);
            world.systemTime += DT;
            FogOfWarView.forceRefreshForTest(world);
            minimap.draw(graphics, world, camera, image.getWidth(), image.getHeight());
        });

        setStationary(ships);
        FogOfWarView.clearCachedStateForTest(world);
        double fullFrameStationary = timeMillis(180, () -> {
            world.draw(graphics);
            FogOfWarView.drawWorld(graphics, world);
            minimap.draw(graphics, world, camera, image.getWidth(), image.getHeight());
        });

        setMoveOrders(ships);
        FogOfWarView.clearCachedStateForTest(world);
        final int[] fullMoveStep = {0};
        double fullFrameMove = timeMillis(180, () -> {
            ClientPrediction.update(world, DT);
            world.systemTime += DT;
            FogOfWarView.forceRefreshForTest(world);
            world.draw(graphics);
            FogOfWarView.drawWorld(graphics, world);
            minimap.draw(graphics, world, camera, image.getWidth(), image.getHeight());
            fullMoveStep[0]++;
        });

        graphics.dispose();

        System.out.printf("movement-profiler ships=%d%n", SHIPS);
        report("client prediction stationary", predictionStationary, 3000);
        report("client prediction orbiting", predictionOrbiting, 3000);
        report("client prediction MOVE", predictionMove, 3000);
        report("world draw stationary", drawStationary, 300);
        report("world draw orbiting", drawOrbiting, 300);
        report("world draw MOVE", drawMove, 300);
        report("fog draw stationary", fogStationary, 300);
        report("fog draw orbiting", fogOrbiting, 300);
        report("minimap stationary", minimapStationary, 300);
        report("minimap orbiting", minimapOrbiting, 300);
        report("frame subset stationary", fullFrameStationary, 180);
        report("frame subset MOVE", fullFrameMove, 180);
    }

    private static void setStationary(List<Unit> ships) {
        for (Unit unit : ships) {
            unit.task = UnitTask.IDLE;
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.afterburnerActive = false;
        }
    }

    private static void setMoveOrders(List<Unit> ships) {
        for (int i = 0; i < ships.size(); i++) {
            Unit unit = ships.get(i);
            unit.task = UnitTask.MOVE;
            unit.targetX = Math.min(3000, unit.x + 1200 + i * 7);
            unit.targetY = Math.min(2200, unit.y + 800 + i * 5);
        }
    }

    private static void retargetOrbit(List<Unit> ships, int step) {
        double t = step * DT * 0.35;
        for (int i = 0; i < ships.size(); i++) {
            Unit unit = ships.get(i);
            unit.task = UnitTask.IDLE;
            double angle = t * ((i & 1) == 0 ? 1 : -1) + i * 0.63;
            unit.targetX = 600 + Math.cos(angle) * 180;
            unit.targetY = 500 + Math.sin(angle) * 180;
        }
    }

    private static double timeMillis(int iterations, Runnable action) {
        long started = System.nanoTime();
        for (int i = 0; i < iterations; i++) action.run();
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static void report(String label, double totalMillis, int iterations) {
        System.out.printf("%-30s total=%9.3f ms avg=%8.5f ms/op%n",
                label, totalMillis, totalMillis / Math.max(1, iterations));
    }
}
