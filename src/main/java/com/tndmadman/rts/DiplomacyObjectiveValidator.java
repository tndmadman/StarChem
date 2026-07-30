package com.tndmadman.rts;

import java.util.Set;

final class DiplomacyObjectiveValidator {
    private DiplomacyObjectiveValidator() { }

    public static void main(String[] args) {
        World world = new World("TeamObjectiveValidator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "One", 0x50BEFF);
        PlayerRegistry.register("P2", "Two", 0x77DD88, false);
        SkirmishSettings settings = new SkirmishSettings(
                SkirmishPreset.STANDARD,
                NpcDifficulty.NORMAL,
                Set.of(),
                "fleet_muster",
                DiplomacyMatchSettings.teams());
        SkirmishRuntime.bind(world, settings);
        DiplomacySystem.assignTeam(world, "P2", DiplomacySystem.teamId(world, "P1"));

        for (int i = 1; i <= 6; i++) {
            Unit first = new Unit("P1", i, Rules.STARTING_SHIP, 100 + i * 10, 100);
            Unit second = new Unit("P2", i, Rules.STARTING_SHIP, 100 + i * 10, 180);
            world.units.put(first.key(), first);
            world.units.put(second.key(), second);
        }

        ObjectiveSystem.evaluateAuthoritative(world, 0);
        ObjectiveView result = ObjectiveSystem.view(world);
        require(result.status() == ObjectiveStatus.COMPLETED,
                "Allied fleet totals must complete a shared objective.");
        require(result.current() == 12,
                "Shared objective progress must aggregate both teammates.");
        require("Team Alpha".equals(result.completedBy()),
                "Shared objective completion must name the victorious team.");
        System.out.println("Diplomacy objective validation passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
