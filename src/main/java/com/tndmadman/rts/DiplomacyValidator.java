package com.tndmadman.rts;

import java.util.Map;

final class DiplomacyValidator {
    private DiplomacyValidator() { }

    public static void main(String[] args) {
        World world = new World("DiplomacyValidator", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);

        require(DiplomacySystem.hostile(world, "P1", "P2"), "FFA players must remain hostile by default.");
        require(!DiplomacySystem.mayDamage(world, "P1", "P1"), "Friendly fire must be disabled by default.");

        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FIXED_TEAMS, false, true, true);
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("alpha", "Alpha", 0x50BEFF));
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("beta", "Beta", 0xFF7050));
        DiplomacySystem.assignTeam(world, "P1", "alpha");
        DiplomacySystem.assignTeam(world, "P2", "alpha");
        DiplomacySystem.assignTeam(world, "P3", "beta");

        require(DiplomacySystem.allied(world, "P1", "P2"), "Players on the same team must be allied.");
        require(!DiplomacySystem.mayTarget(world, "P1", "P2"), "Allied players must not be valid attack targets.");
        require(DiplomacySystem.hostile(world, "P1", "P3"), "Different fixed teams must be hostile.");
        require(DiplomacySystem.sharesVision(world, "P1", "P2"), "Configured allies must share vision.");
        require(DiplomacySystem.sharesVictory(world, "P1", "P2"), "Configured allies must share victory.");
        require(DiplomacySystem.victoryGroupId(world, "P1").equals(DiplomacySystem.victoryGroupId(world, "P2")),
                "Allied players must resolve to the same victory group.");

        DiplomacySystem.setRelationship(world, "P1", "P3", DiplomacySystem.Relationship.NEUTRAL);
        require(DiplomacySystem.neutral(world, "P1", "P3"), "Explicit neutral relationships must override team defaults.");
        require(!DiplomacySystem.mayDamage(world, "P1", "P3"), "Neutral targets must not take combat damage.");

        Map<String,Object> saved = DiplomacySystem.capture(world);
        World restored = new World("DiplomacyRestore", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        DiplomacySystem.restore(restored, saved);
        require(DiplomacySystem.allied(restored, "P1", "P2"), "Team assignments must survive restore.");
        require(DiplomacySystem.neutral(restored, "P1", "P3"), "Explicit relationships must survive restore.");
        require(DiplomacySystem.teams(restored).size() == 2, "Team definitions must survive restore.");

        DiplomacySystem.configure(restored, DiplomacySystem.MatchMode.COOP_VS_NPC, false, true, true);
        require(DiplomacySystem.allied(restored, "P1", "P9"), "Co-op humans must be allied.");
        require(DiplomacySystem.hostile(restored, "P1", Config.RAIDERS_ID), "Co-op humans must be hostile to NPC factions.");

        System.out.println("Diplomacy validation passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
