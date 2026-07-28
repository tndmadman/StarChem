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
        validateGameplayArrivalWake();
        validateViewedDormantSystemContinuity();
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
        require(scheduler.pendingEntryCount() <= scheduler.stats().trackedSystems(),
                "deleted system left stale due-time entries behind");

        AuthoritativeSystemScheduler fallback = new AuthoritativeSystemScheduler();
        fallback.update(world, 1.0 / 60.0,
                () -> new GalaxyMapSnapshot(world.activeSystemId(), List.of(), List.of()));
        require(fallback.stats().trackedSystems() == 1,
                "empty topology discovery did not preserve the active system");
    }

    private static void validateGameplayArrivalWake() {
        World world = new World("Gameplay Wake", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        world.spawnPlayerGroup("P1", 1, true);
        String homeSystem = world.playerHomeSystemId("P1");
        world.activateSystem(homeSystem);
        require(!world.wormholes.isEmpty(), "player home did not contain a wormhole for scheduler wake validation");
        WormholeGate gate = world.wormholes.get(0);
        String destinationSystem = gate.toSystemId;
        Unit ship = firstPlayerUnit(world, "P1");
        require(ship != null, "player spawn did not create a ship for scheduler wake validation");

        world.activateSystem(destinationSystem);
        require(SystemSimulationScheduler.tier(world) == SystemSimulationScheduler.SimulationTier.DORMANT,
                "wormhole destination was not inactive before player arrival");
        world.activateSystem(homeSystem);

        AuthoritativeSystemScheduler scheduler = new AuthoritativeSystemScheduler();
        GalaxyMapSnapshot map = world.galaxyMapSnapshot();
        int classificationLimit = Math.max(16, map.systems().size() * 3);
        for (int i = 0; i < classificationLimit; i++) {
            scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
            if (scheduler.stats().trackedSystems() == map.systems().size()
                    && scheduler.stats().backlog() == 0) break;
        }
        require(scheduler.stats().trackedSystems() == map.systems().size(),
                "gameplay wake validation did not discover the complete galaxy");
        require(scheduler.stats().backlog() == 0,
                "gameplay wake validation did not drain initial scheduler work");

        world.activateSystem(homeSystem);
        ship = firstPlayerUnit(world, "P1");
        require(ship != null, "player ship disappeared before wormhole transfer");
        ship.x = gate.x;
        ship.y = gate.y;
        ship.targetX = gate.x;
        ship.targetY = gate.y;
        ship.task = UnitTask.IDLE;
        ship.wormholeCooldown = 0;
        world.saveActiveSystem();

        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(List.of(scheduler.lastUpdatedSystems()).contains(destinationSystem),
                "normal wormhole transfer did not wake the destination promptly");
        require(scheduler.stats().hotSystems() >= 2,
                "wormhole destination was not retained as hot after player arrival");

        world.activateSystem(destinationSystem);
        Unit transferred = firstPlayerUnit(world, "P1");
        require(transferred != null, "player ship was not present in the wormhole destination");
        double startX = transferred.x;
        transferred.issueMove(startX + 120, transferred.y);
        world.saveActiveSystem();

        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        world.activateSystem(destinationSystem);
        transferred = firstPlayerUnit(world, "P1");
        require(transferred != null && transferred.x > startX,
                "gameplay command did not execute on the newly hot destination");

        world.units.remove(transferred.key());
        world.saveActiveSystem();
        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(!List.of(scheduler.lastUpdatedSystems()).contains(destinationSystem),
                "empty destination did not demote after player assets left");

        for (int i = 0; i < 2_000; i++) scheduler.wake(destinationSystem);
        require(scheduler.pendingEntryCount() <= scheduler.stats().trackedSystems(),
                "repeated wakes created unbounded stale due-time entries");
        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(frequency(scheduler.lastUpdatedSystems(), destinationSystem) == 1,
                "repeated wakes caused duplicate simulation updates");
        require(scheduler.pendingEntryCount() <= scheduler.stats().trackedSystems(),
                "due-time queue remained unbounded after processing repeated wakes");

        String externalDestination = "";
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && !homeSystem.equals(system.id()) && !destinationSystem.equals(system.id())) {
                externalDestination = system.id();
                break;
            }
        }
        require(!externalDestination.isBlank(),
                "gameplay wake validation could not select an external mutation destination");
        world.activateSystem(homeSystem);
        world.movePlayerAssetsToSystem("P1", externalDestination);
        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(List.of(scheduler.lastUpdatedSystems()).contains(externalDestination),
                "cross-system mutation between scheduler ticks did not wake its destination");
    }

    private static Unit firstPlayerUnit(World world, String playerId) {
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId) && unit.hp > 0) return unit;
        return null;
    }

    private static int frequency(String[] values, String expected) {
        int count = 0;
        for (String value : values) if (expected.equals(value)) count++;
        return count;
    }

    private static void validateViewedDormantSystemContinuity() {
        World world = new World("Viewed Dormant", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyMapSnapshot map = world.galaxyMapSnapshot();
        require(map != null && map.systems().size() > 1, "viewed-system test galaxy is too small");

        String sourceSystem = map.activeSystemId();
        world.activateSystem(sourceSystem);
        String viewedSystem = "";
        WormholeGate discoveryGate = null;
        for (WormholeGate gate : world.wormholes) {
            if (gate == null || gate.toSystemId == null || gate.toSystemId.isBlank()) continue;
            world.activateSystem(gate.toSystemId);
            boolean dormant = SystemSimulationScheduler.tier(world) == SystemSimulationScheduler.SimulationTier.DORMANT;
            world.activateSystem(sourceSystem);
            if (dormant) {
                viewedSystem = gate.toSystemId;
                discoveryGate = gate;
                break;
            }
        }
        require(discoveryGate != null && !viewedSystem.isBlank(),
                "viewed-system test could not select a discoverable dormant system");

        world.activateSystem(viewedSystem);
        ResourceNode resource = world.resources.get(0);
        double beforeTime = world.systemTime();
        double beforeAngle = resource.orbitAngle;
        world.activateSystem(sourceSystem);

        Unit scout = new Unit("P1", 90_101, "scout", discoveryGate.x, discoveryGate.y);
        world.units.put(scout.key(), scout);
        world.saveActiveSystem();

        ClientViewCache views = new ClientViewCache();
        require(views.requestView(world, "P1", sourceSystem, 1),
                "client view cache rejected the scout-occupied source system");
        views.makeSnapshot(world, "P1", 1);

        world.activateSystem(sourceSystem);
        world.units.remove(scout.key());
        world.saveActiveSystem();
        require(views.requestView(world, "P1", viewedSystem, 2),
                "client view cache rejected a sensor-discovered remote system");

        world.activateSystem(viewedSystem);
        require(SystemSimulationScheduler.tier(world) == SystemSimulationScheduler.SimulationTier.DORMANT,
                "remote viewed system unexpectedly contained simulation-hot assets");
        world.activateSystem(sourceSystem);

        AuthoritativeSystemScheduler scheduler = new AuthoritativeSystemScheduler();
        for (int i = 0; i < 120; i++) {
            scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
            require(List.of(scheduler.lastUpdatedSystems()).contains(viewedSystem),
                    "actively viewed dormant system skipped an authoritative environment tick");
        }

        world.activateSystem(viewedSystem);
        require(world.systemTime() - beforeTime > 1.95,
                "actively viewed dormant system time did not advance continuously");
        require(Math.abs(resource.orbitAngle - beforeAngle) > 0.000001,
                "actively viewed dormant resource orbit remained frozen");
        require(SystemSimulationScheduler.tier(world) == SystemSimulationScheduler.SimulationTier.DORMANT,
                "view tracking incorrectly promoted dormant gameplay simulation");

        views.remove("P1");
        scheduler.update(world, 1.0 / 60.0, world::galaxyMapSnapshot);
        require(scheduler.stats().hotSystems() == 0,
                "system remained visually hot after the final viewer disconnected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
