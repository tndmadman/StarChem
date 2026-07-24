package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class SystemSimulationSchedulerValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private SystemSimulationSchedulerValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem system simulation scheduler validation passed.");
    }

    static void validateOrThrow() {
        World dormant = new World("Dormant", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        for (int i = 0; i < 49; i++) {
            require(SystemSimulationScheduler.step(dormant, 0.1) == 0,
                    "dormant system ticked before its accumulated interval");
        }
        require(SystemSimulationScheduler.step(dormant, 0.1) >= 4.99,
                "dormant system did not release its accumulated interval");

        World cold = new World("Cold", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        cold.addWorldItem(Material.IRON, 1, 100, 100, 0, 0, 0, 0);
        for (int i = 0; i < 7; i++) {
            require(SystemSimulationScheduler.step(cold, 0.1) == 0,
                    "cold system ticked before its accumulated interval");
        }
        require(SystemSimulationScheduler.step(cold, 0.1) >= 0.75,
                "cold system did not release its accumulated interval");

        World orbit = new World("Orbit", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        ResourceNode orbiting = orbit.resources.get(0);
        double beforeAngle = orbiting.orbitAngle;
        orbit.updateCurrentSystem(0.05);
        require(Math.abs(orbiting.orbitAngle - beforeAngle) > 0.000001,
                "dormant-system resource orbit was frozen by simulation throttling");

        World warm = new World("Warm", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        warm.bases.put("NPC_CORSAIRS:B1", new Base("NPC_CORSAIRS:B1", Config.CORSAIRS_ID,
                Rules.DEFAULT_BASE, warm.width * 0.5, warm.height * 0.5));
        require(SystemSimulationScheduler.step(warm, 0.05) == 0, "warm system ticked too frequently");
        require(SystemSimulationScheduler.step(warm, 0.08) >= 0.12,
                "warm system did not release accumulated time");

        World warmFast = new World("Warm Fast Forward", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        warmFast.bases.put("NPC_CORSAIRS:B1", new Base("NPC_CORSAIRS:B1", Config.CORSAIRS_ID,
                Rules.DEFAULT_BASE, warmFast.width * 0.5, warmFast.height * 0.5));
        require(Math.abs(SystemSimulationScheduler.step(warmFast, 1.0) - 1.0) < 0.0001,
                "warm-system fast-forward tick discarded elapsed simulation time");

        World hot = new World("Hot", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        hot.units.put("P1:1", new Unit("P1", 1, "prospector", 1000, 1000));
        require(Math.abs(SystemSimulationScheduler.step(hot, 0.016) - 0.016) < 0.0001,
                "player-occupied hot system was throttled");

        validateAuthoritativeScheduling();
    }

    private static void validateAuthoritativeScheduling() {
        World world = new World("Due Scheduler", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyMapSnapshot initial = world.galaxyMapSnapshot();
        require(initial != null && initial.systems().size() > 1, "scheduler test galaxy is too small");

        AuthoritativeSystemScheduler scheduler = new AuthoritativeSystemScheduler();
        int classified = 0;
        int classificationTicks = (initial.systems().size()
                + AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK - 1)
                / AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK + 1;
        for (int i = 0; i < classificationTicks; i++) {
            scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
            int updated = scheduler.stats().updatedSystems();
            require(updated <= AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK,
                    "initial inactive-system classification exceeded the per-tick budget");
            classified += updated;
        }
        require(classified == initial.systems().size(),
                "initial scheduler passes did not classify every known system exactly once");

        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(scheduler.stats().updatedSystems() == 0,
                "inactive systems continued updating before their deadlines");

        int maxUpdated = 0;
        int soakUpdates = 0;
        for (int i = 0; i < 360; i++) {
            scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
            int updated = scheduler.stats().updatedSystems();
            maxUpdated = Math.max(maxUpdated, updated);
            soakUpdates += updated;
        }
        require(maxUpdated <= AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK,
                "inactive-system due work exceeded the per-tick budget during soak");
        require(soakUpdates <= initial.systems().size() + AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK,
                "dormant systems ran too frequently during the scheduler soak");

        for (int i = 0; i < initial.systems().size() && scheduler.stats().backlog() > 0; i++) {
            scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        }
        require(scheduler.stats().backlog() == 0, "inactive scheduler backlog did not drain");

        String promoted = initial.systems().get(0).id();
        world.activateSystem(promoted);
        world.units.put("P1:1", new Unit("P1", 1, "prospector", 1000, 1000));
        world.saveActiveSystem();
        scheduler.wake(promoted);
        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(List.of(scheduler.lastUpdatedSystems()).contains(promoted),
                "promoted system did not run promptly");
        require(scheduler.stats().hotSystems() >= 1, "promoted system was not classified hot");

        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(List.of(scheduler.lastUpdatedSystems()).contains(promoted),
                "hot system did not continue running every authoritative tick");

        GalaxyMapSnapshot reduced = new GalaxyMapSnapshot(initial.activeSystemId(),
                initial.systems().subList(0, initial.systems().size() - 1), initial.links());
        scheduler.refreshNow();
        scheduler.update(world, 1.0 / 60.0, () -> reduced);
        require(scheduler.stats().trackedSystems() == reduced.systems().size(),
                "deleted system remained in authoritative scheduler state");

        AuthoritativeSystemScheduler fallback = new AuthoritativeSystemScheduler();
        fallback.update(world, 1.0 / 60.0,
                () -> new GalaxyMapSnapshot(world.activeSystemId(), List.of(), List.of()));
        require(fallback.stats().trackedSystems() == 1,
                "empty topology discovery did not preserve the active system");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
