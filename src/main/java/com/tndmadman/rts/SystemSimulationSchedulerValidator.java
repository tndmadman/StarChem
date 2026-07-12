package com.tndmadman.rts;

import java.util.Set;

public final class SystemSimulationSchedulerValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private SystemSimulationSchedulerValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem system simulation scheduler validation passed.");
    }

    static void validateOrThrow() {
        World cold = new World("Cold", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        for (int i = 0; i < 7; i++) require(SystemSimulationScheduler.step(cold, 0.1) == 0,
                "cold system ticked before its accumulated interval");
        require(SystemSimulationScheduler.step(cold, 0.1) >= 0.75,
                "cold system did not release its accumulated interval");

        World orbit = new World("Orbit", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        ResourceNode orbiting = orbit.resources.get(0);
        double beforeAngle = orbiting.orbitAngle;
        orbit.updateCurrentSystem(0.05);
        require(Math.abs(orbiting.orbitAngle - beforeAngle) > 0.000001,
                "cold-system resource orbit was frozen by simulation throttling");

        World warm = new World("Warm", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        warm.bases.put("NPC_CORSAIRS:B1", new Base("NPC_CORSAIRS:B1", Config.CORSAIRS_ID,
                Rules.DEFAULT_BASE, warm.width * 0.5, warm.height * 0.5));
        require(SystemSimulationScheduler.step(warm, 0.05) == 0, "warm system ticked too frequently");
        require(SystemSimulationScheduler.step(warm, 0.08) >= 0.12, "warm system did not release accumulated time");

        World hot = new World("Hot", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        hot.units.put("P1:1", new Unit("P1", 1, "prospector", 1000, 1000));
        require(Math.abs(SystemSimulationScheduler.step(hot, 0.016) - 0.016) < 0.0001,
                "player-occupied hot system was throttled");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
