package com.tndmadman.rts;

import java.util.Map;

final class DiplomacyValidator {
    private DiplomacyValidator() { }

    public static void main(String[] args) {
        validatesRelationshipsAndPersistence();
        validatesBootstrapModes();
        System.out.println("Diplomacy validation passed.");
    }

    private static void validatesRelationshipsAndPersistence() {
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
        DiplomacySystem.setRelationship(world, "P1", "P3", DiplomacySystem.Relationship.NEUTRAL);
        require(DiplomacySystem.neutral(world, "P1", "P3"), "Explicit neutral relationships must override defaults.");
        Map<String,Object> saved = DiplomacySystem.capture(world);
        World restored = new World("DiplomacyRestore", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        DiplomacySystem.restore(restored, saved);
        require(DiplomacySystem.allied(restored, "P1", "P2"), "Team assignments must survive restore.");
        require(DiplomacySystem.neutral(restored, "P1", "P3"), "Explicit relationships must survive restore.");
    }

    private static void validatesBootstrapModes() {
        String previous = System.getProperty("starchem.diplomacyMode");
        try {
            System.setProperty("starchem.diplomacyMode", "coop");
            World world = new World("Bootstrap", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("P1", "One", 0x50BEFF);
            PlayerRegistry.register("P2", "Two", 0x77DD88, false);
            PlayerRegistry.register(Config.RAIDERS_ID, "Raiders", 0xFF4444, false);
            require(DiplomacySystem.mode(world) == DiplomacySystem.MatchMode.COOP_VS_NPC,
                    "Bootstrap must select co-op mode.");
            require(DiplomacySystem.allied(world, "P1", "P2"), "Co-op humans must share a team.");
            require(DiplomacySystem.hostile(world, "P1", Config.RAIDERS_ID), "Co-op humans must oppose NPCs.");
            require(PlayerRegistry.name("P1").contains("Human Coalition"), "Team identity must be visible.");
        } finally {
            if (previous == null) System.clearProperty("starchem.diplomacyMode");
            else System.setProperty("starchem.diplomacyMode", previous);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
