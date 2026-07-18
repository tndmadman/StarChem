package com.tndmadman.rts;

import java.util.Set;

public final class NpcRuntimeResetValidator {
    private NpcRuntimeResetValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC runtime reset validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("WAIT", "NPC Runtime Reset Validator", 0x50BEFF);
        World world = new World("NPC Runtime Reset Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        NpcFaction faction = corsairs();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();

        Base source = new Base(faction.id() + ":RESET_B1", faction.id(),
                "outpost", 4000, 4000);
        for (Material material : Material.values()) source.inventory.put(material, 5000.0);
        world.bases.put(source.id, source);
        Unit builder = new Unit(faction.id(), 88_001, "station_builder", 4200, 4000);
        Unit first = new Unit(faction.id(), 88_002, "frigate", 4100, 4050);
        Unit second = new Unit(faction.id(), 88_003, "destroyer", 4150, 4100);
        world.units.put(builder.key(), builder);
        world.units.put(first.key(), first);
        world.units.put(second.key(), second);
        world.saveActiveSystem();

        require(NpcStationConstructionSystem.start(world, faction, source, builder,
                        "shipyard", NpcBudgetCategory.STATION_RECOVERY),
                "fixture could not create a station construction runtime");
        NpcStrategicDirector.update(world, faction, 1.0);
        NpcSquadCombatSystem.update(world, faction, NpcStrategicState.FORTIFY, 1.0);
        NpcRecoverySystem.update(world, faction);
        require(NpcStationConstructionSystem.hasAnyActivePlan(world, faction),
                "station construction runtime was not created");
        require(!NpcSquadCombatSystem.snapshot(world, faction).squads().isEmpty(),
                "squad runtime was not created");
        require(NpcStrategicDirector.transitionCount(world, faction) > 0,
                "strategic runtime was not created");

        int originalCopies = GalaxyRuntimeOptions.copiesPerTemplate();
        int alternateCopies = originalCopies == 1 ? 2 : 1;
        try {
            world.configureGalaxyCopies(alternateCopies);
            require(!NpcStationConstructionSystem.hasAnyActivePlan(world, faction),
                    "same-seed galaxy rebuild retained a station plan");
            require(NpcSquadCombatSystem.snapshot(world, faction).squads().isEmpty(),
                    "same-seed galaxy rebuild retained squad assignments");
            require(NpcRecoverySystem.state(world, faction, world.activeSystemId())
                            == NpcRecoveryState.IDLE,
                    "same-seed galaxy rebuild retained recovery state");
            require(NpcStrategicDirector.transitionCount(world, faction) == 0,
                    "same-seed galaxy rebuild retained strategic transition history");
        } finally {
            world.configureGalaxyCopies(originalCopies);
        }
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
