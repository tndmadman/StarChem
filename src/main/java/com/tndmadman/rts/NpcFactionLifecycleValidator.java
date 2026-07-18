package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

public final class NpcFactionLifecycleValidator {
    private static final String REMOTE_SYSTEM_ID = "red_dwarf";

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

        world.activateSystem(REMOTE_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(world);
        require(REMOTE_SYSTEM_ID.equals(world.activeSystemId()),
                "forced spawn did not restore the developer's previous system view");
        require(localFactionAssetCount(world, Config.CORSAIRS_ID) == 0,
                "forced spawn created Corsair assets in the viewed remote system");

        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        require(localFactionBaseCount(world, Config.CORSAIRS_ID) == 1,
                "forced spawn did not create exactly one Corsair home station");
        require(localFactionUnitCount(world, Config.CORSAIRS_ID) > 0,
                "forced spawn did not create the configured Corsair starting units");
        giveResourcesAndResearch(world);
        assertConstructionCooldown(world, "forced");

        NpcFactionRuntime runtime = runtime(world, Config.CORSAIRS_ID);
        require(runtime != null, "Corsair galaxy lifecycle was not created");
        require(runtime.state() == NpcFactionRuntime.State.ACTIVE,
                "forced Corsair assets did not activate the galaxy lifecycle");
        require(runtime.spawnCount() == 0,
                "forced Corsair spawn was incorrectly counted as a natural lifecycle spawn");

        int assetsBeforeDuplicate = galaxyFactionAssetCount(world, Config.CORSAIRS_ID);
        world.activateSystem("carbon_basin");
        AiDevCommands.spawnCorsairs(world);
        require("carbon_basin".equals(world.activeSystemId()),
                "duplicate forced spawn changed the active system");
        require(galaxyFactionAssetCount(world, Config.CORSAIRS_ID) == assetsBeforeDuplicate,
                "duplicate forced spawn created additional Corsair assets");

        tickGalaxy(world, 200);
        require(runtime.spawnCount() == 0,
                "a remote system spawned a duplicate Corsair faction while forced assets survived");

        AiDevCommands.killCorsairs(world);
        require(!world.hasLiveAssets(Config.CORSAIRS_ID),
                "developer kill left living Corsair assets in another system");
        addRespawnRequirement(world);
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

        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        giveResourcesAndResearch(world);
        assertConstructionCooldown(world, "natural");

        tickGalaxy(world, 200);
        require(runtime.spawnCount() == 1,
                "multiple systems restarted their own Corsair respawn timers");
    }

    private static void assertConstructionCooldown(World world, String spawnKind) {
        int basesBefore = localFactionBaseCount(world, Config.CORSAIRS_ID);
        int buildersBefore = localBaseBuilderCount(world, Config.CORSAIRS_ID);
        world.updateCurrentSystem(1.0);
        require(localFactionBaseCount(world, Config.CORSAIRS_ID) == basesBefore,
                spawnKind + " spawn bypassed the configured station-build cooldown");
        require(localBaseBuilderCount(world, Config.CORSAIRS_ID) == buildersBefore,
                spawnKind + " spawn immediately constructed a station deployer");
    }

    private static void giveResourcesAndResearch(World world) {
        Base base = world.bases.values().stream()
                .filter(candidate -> Config.CORSAIRS_ID.equals(candidate.playerId) && candidate.hp > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Corsair home station is missing"));
        for (Material material : Material.values()) HangarStore.add(base.inventory, material, 1000.0);
        world.completeResearch(Config.CORSAIRS_ID, "advanced_industry");
        world.saveActiveSystem();
    }

    private static void addRespawnRequirement(World world) {
        String previous = world.activeSystemId();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Unit playerCombat = new Unit("RESPAWN_TEST_PLAYER", 99_001, "frigate",
                world.width * 0.78, world.height * 0.78);
        world.units.put(playerCombat.key(), playerCombat);
        world.saveActiveSystem();
        if (previous != null && !previous.isBlank()) world.activateSystem(previous);
    }

    private static void tickGalaxy(World world, int seconds) {
        for (int i = 0; i < seconds; i++) world.update(1.0);
    }

    private static int galaxyFactionAssetCount(World world, String factionId) {
        String previous = world.activeSystemId();
        int count = 0;
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system == null || system.id() == null || system.id().isBlank()) continue;
            world.activateSystem(system.id());
            count += localFactionAssetCount(world, factionId);
        }
        if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        return count;
    }

    private static int localFactionAssetCount(World world, String factionId) {
        return localFactionBaseCount(world, factionId) + localFactionUnitCount(world, factionId);
    }

    private static int localFactionBaseCount(World world, String factionId) {
        int count = 0;
        for (Base base : world.bases.values()) if (factionId.equals(base.playerId) && base.hp > 0) count++;
        return count;
    }

    private static int localFactionUnitCount(World world, String factionId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (factionId.equals(unit.playerId) && unit.hp > 0) count++;
        return count;
    }

    private static int localBaseBuilderCount(World world, String factionId) {
        int count = 0;
        for (Unit unit : world.units.values()) {
            if (factionId.equals(unit.playerId) && unit.hp > 0 && unit.type().baseBuilder) count++;
        }
        return count;
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
