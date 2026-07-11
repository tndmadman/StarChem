package com.tndmadman.rts;

import java.util.Set;

public final class NpcSystemIsolationValidator {
    private static final String SOLO_HOME_ID = StarSystems.PLAYER_HOME_SYSTEM_ID + "_SOLO";
    private static final String SECOND_HOME_ID = StarSystems.PLAYER_HOME_SYSTEM_ID + "_P2";

    private NpcSystemIsolationValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC system isolation validation passed.");
    }

    static void validateOrThrow() {
        validateIndependentFactionTimers();
        validateFactionSystemScope();
        validateRuntimeCleanup();
    }

    private static void validateIndependentFactionTimers() {
        PlayerRegistry.reset("SOLO", "NPC Isolation Validator", 0x50BEFF);
        World world = new World("NPC Isolation Validator",
                Set.of(Config.FREE_MINERS_ID, Config.CORSAIRS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                true);

        require(SOLO_HOME_ID.equals(world.activeSystemId()), "validator did not start in the solo home");
        addCombatShip(world, "SOLO", 9001);

        world.spawnPlayerGroup("P2", 1);
        world.activateSystem(SECOND_HOME_ID);
        addCombatShip(world, "P2", 9002);
        world.activateSystem(SOLO_HOME_ID);

        world.update(17.5);
        require(factionAssetCount(world, SOLO_HOME_ID, Config.RAIDERS_ID) == 0,
                "solo-home Raider timer advanced more than once per world update");
        require(factionAssetCount(world, SECOND_HOME_ID, Config.RAIDERS_ID) == 0,
                "second-home Raider timer advanced more than once per world update");

        world.update(1.0);
        require(factionAssetCount(world, SOLO_HOME_ID, Config.RAIDERS_ID) > 0,
                "solo-home Raiders did not spawn after their own elapsed timer");
        require(factionAssetCount(world, SECOND_HOME_ID, Config.RAIDERS_ID) > 0,
                "second-home Raiders did not spawn after their own elapsed timer");
    }

    private static void validateFactionSystemScope() {
        PlayerRegistry.reset("SOLO", "NPC Scope Validator", 0x50BEFF);
        World world = new World("NPC Scope Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                true);

        world.update(70.0);
        require(factionAssetCount(world, SOLO_HOME_ID, Config.CORSAIRS_ID) == 0,
                "Corsairs spawned outside corsair_den");
        require(factionAssetCount(world, StarSystems.CORSAIR_SYSTEM_ID, Config.CORSAIRS_ID) > 0,
                "Corsairs did not spawn in corsair_den");
    }

    private static void validateRuntimeCleanup() {
        PlayerRegistry.reset("SOLO", "NPC Cleanup Validator", 0x50BEFF);
        World world = new World("NPC Cleanup Validator",
                Set.of(Config.FREE_MINERS_ID, Config.CORSAIRS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                true);

        world.updateCurrentSystem(1.0);
        world.spawnPlayerGroup("P2", 1);
        world.activateSystem(SECOND_HOME_ID);
        addCombatShip(world, "P2", 9003);
        world.updateCurrentSystem(1.0);
        require(world.npcRuntimeSystemCount() == 2,
                "validator did not create separate NPC runtime state for both player homes");

        world.activateSystem(SOLO_HOME_ID);
        Set<String> deleted = world.removePlayerAndPruneEmptySystems("P2");
        require(deleted.contains(SECOND_HOME_ID), "second player home was not pruned");
        require(world.npcRuntimeSystemCount() == 1,
                "NPC runtime state for a pruned system was retained");
    }

    private static void addCombatShip(World world, String playerId, int unitId) {
        Unit unit = new Unit(playerId, unitId, "frigate", world.width * 0.48, world.height * 0.52);
        world.units.put(unit.key(), unit);
        world.saveActiveSystem();
    }

    private static int factionAssetCount(World world, String systemId, String factionId) {
        String previous = world.activeSystemId();
        world.activateSystem(systemId);
        int count = 0;
        for (Unit unit : world.units.values()) {
            if (factionId.equals(unit.playerId) && unit.hp > 0) count++;
        }
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) count++;
        }
        if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
