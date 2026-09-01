package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Regression coverage for movement-driven fog/intel performance fixes. */
public final class FogPerformanceValidator {
    private FogPerformanceValidator() { }

    public static void main(String[] args) {
        World world = new World("Fog performance validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();

        List<Unit> friendly = new ArrayList<>();
        List<Unit> enemies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Unit sensor = new Unit("P1", i + 1, "frigate", 320 + (i % 5) * 90, 260 + (i / 5) * 90);
            world.units.put(sensor.key(), sensor);
            friendly.add(sensor);

            Unit enemy = new Unit("P2", i + 1, "frigate", 520 + (i % 5) * 105, 410 + (i / 5) * 105);
            if ((i & 1) == 0) enemy.task = UnitTask.MOVE;
            world.units.put(enemy.key(), enemy);
            enemies.add(enemy);
        }
        Base enemyBase = new Base("P2:B1", "P2", Rules.DEFAULT_BASE, 740, 520);
        world.bases.put(enemyBase.id, enemyBase);

        validateFrameEquivalence(world, enemies, enemyBase);
        validateIncrementalSensorCoverage(world, friendly);
        validatePartialFogComposition(world, friendly.get(0));
        validateFleetVisualFogAggregation(world, friendly);

        System.out.println("Fog performance validator passed.");
    }

    private static void validateFrameEquivalence(World world, List<Unit> enemies, Base enemyBase) {
        VisibilityRules.Frame frame = VisibilityRules.frame(world, "P1");
        require(frame.sensors().size() == 20, "Expected one stable fog sensor per friendly ship.");
        for (int i = 0; i < frame.sensors().size(); i++) {
            VisibilityRules.Sensor sensor = frame.sensors().get(i);
            require(sensor.sourceKey() != null && sensor.sourceKey().startsWith("U:"),
                    "Fog sensor lost its stable source identity.");
        }
        for (Unit enemy : enemies) {
            IntelWarfareSystem.DetectionStage expected = IntelWarfareSystem.unitStage(world, "P1", enemy);
            IntelWarfareSystem.DetectionStage actual = frame.unitStage(enemy);
            require(expected == actual,
                    "Cached visibility frame changed unit detection semantics for " + enemy.key()
                            + ": expected " + expected + " but got " + actual);
        }
        IntelWarfareSystem.DetectionStage expectedBase = IntelWarfareSystem.baseStage(world, "P1", enemyBase);
        IntelWarfareSystem.DetectionStage actualBase = frame.baseStage(enemyBase);
        require(expectedBase == actualBase,
                "Cached visibility frame changed base detection semantics: expected " + expectedBase
                        + " but got " + actualBase);
    }

    private static void validateIncrementalSensorCoverage(World world, List<Unit> friendly) {
        FogOfWarView.clearCachedStateForTest(world);
        FogOfWarView.forceRefreshForTest(world);
        long initial = FogOfWarView.sensorCoverageRebuildCountForTest(world);
        require(initial == friendly.size(),
                "Initial fog refresh did not build exactly one coverage entry per sensor: " + initial);

        FogOfWarView.forceRefreshForTest(world);
        long stationary = FogOfWarView.sensorCoverageRebuildCountForTest(world);
        require(stationary == initial,
                "Stationary sensors rebuilt fog coverage even though no sensor geometry changed.");

        Unit one = friendly.get(0);
        one.x += 18;
        world.systemTime += 0.1;
        FogOfWarView.forceRefreshForTest(world);
        long oneMoved = FogOfWarView.sensorCoverageRebuildCountForTest(world);
        require(oneMoved == stationary + 1,
                "Moving one ship rebuilt more than that ship's fog coverage: expected +1, got +"
                        + (oneMoved - stationary));

        for (Unit unit : friendly) {
            unit.x += 9;
            unit.y += 6;
        }
        world.systemTime += 0.1;
        FogOfWarView.forceRefreshForTest(world);
        long allMoved = FogOfWarView.sensorCoverageRebuildCountForTest(world);
        require(allMoved == oneMoved + friendly.size(),
                "Moving the fleet did not rebuild coverage exactly once per changed sensor.");
    }

    private static void validatePartialFogComposition(World world, Unit movingSensor) {
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        g.setClip(0, 0, image.getWidth(), image.getHeight());
        FogOfWarView.drawWorld(g, world);
        long fullBefore = FogOfWarView.fullFogCompositionCountForTest(world);
        long partialBefore = FogOfWarView.partialFogCompositionCountForTest(world);
        require(fullBefore > 0, "Initial fog draw did not build a complete buffer.");

        movingSensor.x += 14;
        movingSensor.y += 7;
        world.systemTime += 0.1;
        FogOfWarView.forceRefreshForTest(world);
        FogOfWarView.drawWorld(g, world);
        long fullAfter = FogOfWarView.fullFogCompositionCountForTest(world);
        long partialAfter = FogOfWarView.partialFogCompositionCountForTest(world);
        g.dispose();

        require(fullAfter == fullBefore,
                "A moving sensor forced a full fog-buffer composition with an unchanged viewport.");
        require(partialAfter > partialBefore,
                "A moving sensor did not use the dirty-region fog-buffer update path.");
    }

    private static void validateFleetVisualFogAggregation(World world, List<Unit> friendly) {
        FogOfWarView.clearCachedStateForTest(world);
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        g.setClip(0, 0, image.getWidth(), image.getHeight());

        FogOfWarView.drawWorld(g, world);
        long initialVisualRebuilds = FogOfWarView.visualFogRebuildCountForTest(world);
        require(initialVisualRebuilds == 1,
                "Initial fog draw did not build exactly one shared visual fog mask.");

        for (int i = 0; i < friendly.size(); i++) {
            Unit unit = friendly.get(i);
            unit.x += 11 + (i % 3);
            unit.y += 7 + (i % 2);
        }
        world.systemTime += 0.1;
        FogOfWarView.forceRefreshForTest(world);
        long beforeDraw = FogOfWarView.visualFogRebuildCountForTest(world);
        require(beforeDraw == initialVisualRebuilds,
                "Sensor-state refresh rebuilt the visual fog raster before a render was requested.");

        FogOfWarView.drawWorld(g, world);
        long afterDraw = FogOfWarView.visualFogRebuildCountForTest(world);
        g.dispose();

        require(afterDraw == initialVisualRebuilds + 1,
                "Moving the whole fleet rebuilt the shared visual fog raster more than once: expected +1, got +"
                        + (afterDraw - initialVisualRebuilds));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
