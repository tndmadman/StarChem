package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

public final class NpcFactionLifecycleValidator {
    private NpcFactionLifecycleValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem galaxy-wide NPC faction lifecycle validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("WAIT", "NPC Faction Lifecycle Validator", 0x50BEFF);
        World world = new World("NPC Faction Lifecycle Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);

        world.activateSystem("red_dwarf");
        AiDevCommands.spawnCorsairs(world);
        world.saveActiveSystem();
        world.update(1.0);

        NpcFactionRuntime runtime = runtime(world, Config.CORSAIRS_ID);
        require(runtime != null, "Corsair galaxy lifecycle was not created");
        require(runtime.state() == NpcFactionRuntime.State.ACTIVE,
                "forced Corsair assets did not activate the galaxy lifecycle");
        require(runtime.spawnCount() == 0,
                "forced Corsair spawn was incorrectly counted as a natural lifecycle spawn");

        tickGalaxy(world, 200);
        require(runtime.spawnCount() == 0,
                "a remote system spawned a duplicate Corsair faction while forced assets survived");

        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.killCorsairs(world);
        world.saveActiveSystem();
        world.update(1.0);
        require(runtime.state() == NpcFactionRuntime.State.RESPAWNING,
                "total Corsair defeat did not begin the global respawn state");

        tickGalaxy(world, 88);
        require(runtime.spawnCount() == 0,
                "Corsairs respawned before the configured galaxy-wide delay elapsed");

        tickGalaxy(world, 2);
        require(runtime.spawnCount() == 1,
                "Corsair lifecycle did not produce exactly one home-system respawn");
        require(world.hasLiveAssets(Config.CORSAIRS_ID),
                "lifecycle respawn did not create living Corsair assets");

        tickGalaxy(world, 200);
        require(runtime.spawnCount() == 1,
                "multiple systems restarted their own Corsair respawn timers");
    }

    private static void tickGalaxy(World world, int seconds) {
        for (int i = 0; i < seconds; i++) world.update(1.0);
    }

    @SuppressWarnings("unchecked")
    private static NpcFactionRuntime runtime(World world, String factionId) {
        try {
            Field field = World.class.getDeclaredField("npcFactionRuntimes");
            field.setAccessible(true);
            Map<String, NpcFactionRuntime> runtimes =
                    (Map<String, NpcFactionRuntime>)field.get(world);
            require(runtimes.size() == 1,
                    "expected one organized-faction runtime, found " + runtimes.size());
            return runtimes.get(factionId);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to inspect NPC faction lifecycle", ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
