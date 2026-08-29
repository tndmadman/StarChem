package com.tndmadman.rts;

import java.util.Set;

public final class GalaxyEventPerformanceValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private GalaxyEventPerformanceValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem galaxy event performance validation passed.");
    }

    static void validateOrThrow() {
        World world = new World("Event Performance", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Event Performance", 0x50BEFF);
        world.configureGalaxyCopies(2);
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        require(map.systems().size() >= 12, "large-galaxy event performance fixture is unexpectedly small");
        GalaxyEventDirector.configurePolicy(world,
                configWithPolicy(true, 4.0, Set.of(GalaxyEventKind.values())), false);

        AuthoritativeSystemScheduler scheduler = new AuthoritativeSystemScheduler();
        long start = System.nanoTime();
        int maxUpdated = 0;
        for (int i = 0; i < 1_200; i++) {
            scheduler.update(world, 0.25, world::authoritativeGalaxyMapSnapshot);
            int updated = scheduler.stats().updatedSystems();
            maxUpdated = Math.max(maxUpdated, updated);
            require(updated <= AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK,
                    "event scheduling exceeded inactive-system update budget: " + updated);
            require(scheduler.pendingEntryCount() <= Math.max(1, scheduler.stats().trackedSystems()),
                    "event-aware scheduler accumulated unbounded due entries");
            int active = ServerSaveStore.list(GalaxyEventDirector.capture(world).get("events")).size();
            require(active <= GalaxyEventCatalog.load().maxActiveGalaxy(),
                    "event director exceeded galaxy-wide active limit during large-galaxy soak: " + active);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        require(maxUpdated <= AuthoritativeSystemScheduler.MAX_INACTIVE_UPDATES_PER_TICK,
                "large-galaxy scheduler exceeded bounded work");
        require(scheduler.stats().trackedSystems() == map.systems().size(),
                "large-galaxy scheduler lost systems during event soak");
        require(elapsedMs < 30_000,
                "large-galaxy event soak exceeded generous regression budget: " + elapsedMs + "ms");
    }

    private static Config configWithPolicy(boolean enabled, double frequency, Set<GalaxyEventKind> categories) {
        String joined = categories.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("none");
        return Config.parse(new String[]{"--solo", "--galaxy-copies", "2",
                enabled ? "--enable-events" : "--disable-events",
                "--event-frequency", Double.toString(frequency),
                "--event-categories", joined});
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
