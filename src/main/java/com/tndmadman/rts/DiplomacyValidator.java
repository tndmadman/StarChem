package com.tndmadman.rts;

import java.util.Map;

final class DiplomacyValidator {
    private DiplomacyValidator() { }

    public static void main(String[] args) {
        validatesRelationshipsAndPersistence();
        validatesModeTransitions();
        validatesLockedAlliances();
        validatesWorldCleanup();
        validatesBootstrapModes();
        validatesMatchSettingsRoundTrip();
        validatesCommandPolicies();
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
        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FIXED_TEAMS, true, true, true);
        require(DiplomacySystem.mayTarget(world, "P1", "P2"),
                "Friendly-fire rules must allow deliberate allied targeting when enabled.");
        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FIXED_TEAMS, false, true, true);
        DiplomacySystem.setRelationship(world, "P1", "P3", DiplomacySystem.Relationship.NEUTRAL);
        require(DiplomacySystem.neutral(world, "P1", "P3"), "Explicit neutral relationships must override defaults.");
        Map<String,Object> saved = DiplomacySystem.capture(world);
        World restored = new World("DiplomacyRestore", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        DiplomacySystem.restore(restored, saved);
        require(DiplomacySystem.allied(restored, "P1", "P2"), "Team assignments must survive restore.");
        require(DiplomacySystem.neutral(restored, "P1", "P3"), "Explicit relationships must survive restore.");
    }

    private static void validatesModeTransitions() {
        World world = new World("DiplomacyModeTransition", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FIXED_TEAMS, false, true, true);
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("alpha", "Alpha", 0x50BEFF));
        DiplomacySystem.assignTeam(world, "P1", "alpha");
        DiplomacySystem.assignTeam(world, "P2", "alpha");
        DiplomacySystem.setRelationship(world, "P1", "P3", DiplomacySystem.Relationship.NEUTRAL);
        require(DiplomacySystem.allied(world, "P1", "P2"), "Fixed-team setup must establish an alliance.");

        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FFA, true, true, true);
        require(DiplomacySystem.mode(world) == DiplomacySystem.MatchMode.FFA,
                "Mode transition must enter FFA.");
        require(DiplomacySystem.teams(world).isEmpty(), "FFA transition must clear team definitions.");
        require(DiplomacySystem.teamId(world, "P1").isBlank(), "FFA transition must clear owner assignments.");
        require(DiplomacySystem.hostile(world, "P1", "P2"), "Former teammates must become hostile in FFA.");
        require(DiplomacySystem.hostile(world, "P1", "P3"), "FFA transition must clear explicit neutrality.");
        require(!DiplomacySystem.friendlyFire(world), "FFA must normalize friendly fire off.");
        require(!DiplomacySystem.sharedVision(world), "FFA must normalize shared vision off.");
        require(!DiplomacySystem.sharedVictory(world), "FFA must normalize shared victory off.");
    }

    private static void validatesLockedAlliances() {
        World world = new World("LockedAllianceValidator", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.LOCKED_ALLIANCES, false, true, true);
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("alpha", "Alpha", 0x50BEFF));
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("beta", "Beta", 0xFF7050));
        DiplomacySystem.assignTeam(world, "P1", "alpha");
        DiplomacySystem.assignTeam(world, "P2", "beta");
        DiplomacySystem.assignTeam(world, "P1", "beta");
        DiplomacySystem.setRelationship(world, "P1", "P2", DiplomacySystem.Relationship.NEUTRAL);
        require("alpha".equals(DiplomacySystem.teamId(world, "P1")),
                "Locked alliances must reject team reassignment.");
        require(DiplomacySystem.hostile(world, "P1", "P2"),
                "Locked alliances must reject relationship overrides.");
    }

    private static void validatesWorldCleanup() {
        World world = new World("DiplomacyCleanup", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FIXED_TEAMS, false, true, true);
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("alpha", "Alpha", 0x50BEFF));
        DiplomacySystem.assignTeam(world, "P1", "alpha");
        WorldRuntimeCleanup.discard(world);
        require(DiplomacySystem.mode(world) == DiplomacySystem.MatchMode.FFA,
                "Discarded worlds must not retain diplomacy mode state.");
        require(DiplomacySystem.teams(world).isEmpty(),
                "Discarded worlds must not retain diplomacy teams.");
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
            require(IntelWarfareSystem.allied(world, "P1", "P2"),
                    "Shared-vision diplomacy must feed the intelligence system.");
            require(PlayerRegistry.name("P1").contains("Human Coalition"), "Team identity must be visible.");
        } finally {
            if (previous == null) System.clearProperty("starchem.diplomacyMode");
            else System.setProperty("starchem.diplomacyMode", previous);
        }
    }

    private static void validatesMatchSettingsRoundTrip() {
        SkirmishSettings configured = SkirmishSettings.standard().withDiplomacy(
                new DiplomacyMatchSettings(DiplomacySystem.MatchMode.FIXED_TEAMS, true, true, false));
        SkirmishSettings packet = SkirmishSettings.fromPacket(configured.packet());
        require(packet.diplomacy().mode() == DiplomacySystem.MatchMode.FIXED_TEAMS,
                "WORLDINFO must preserve diplomacy mode.");
        require(packet.diplomacy().friendlyFire(), "WORLDINFO must preserve friendly-fire policy.");
        require(packet.diplomacy().sharedVision(), "WORLDINFO must preserve shared-vision policy.");
        require(!packet.diplomacy().sharedVictory(), "WORLDINFO must preserve shared-victory policy.");

        SkirmishSettings saved = SkirmishSettings.fromSaved(configured.saveMap(), SkirmishSettings.standard());
        require(saved.diplomacy().equals(configured.diplomacy()),
                "Server-save skirmish settings must preserve diplomacy policy.");

        SkirmishSettings legacy = SkirmishSettings.fromPacket("WORLDINFO|standard|normal||");
        require(legacy.diplomacy().mode() == DiplomacySystem.MatchMode.FFA,
                "Legacy WORLDINFO packets must default to FFA.");

        World world = new World("SettingsApply", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "One", 0x50BEFF);
        PlayerRegistry.register("P2", "Two", 0x77DD88, false);
        SkirmishRuntime.bind(world, configured);
        String firstTeam = DiplomacySystem.teamId(world, "P1");
        String secondTeam = DiplomacySystem.teamId(world, "P2");
        require(DiplomacySystem.mode(world) == DiplomacySystem.MatchMode.FIXED_TEAMS,
                "Bound skirmish settings must configure world diplomacy.");
        require(!firstTeam.isBlank() && !secondTeam.isBlank(),
                "Bound settings must assign registered players to configured teams.");
        require(!firstTeam.equals(secondTeam) && DiplomacySystem.hostile(world, "P1", "P2"),
                "Fixed-team assignments must place consecutive players on opposing teams.");
    }

    private static void validatesCommandPolicies() {
        World world = new World("CommandPolicyValidator", java.util.Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        DiplomacySystem.configure(world, DiplomacySystem.MatchMode.FIXED_TEAMS, false, true, true);
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("alpha", "Alpha", 0x50BEFF));
        DiplomacySystem.defineTeam(world, new DiplomacySystem.TeamDefinition("beta", "Beta", 0xFF7050));
        DiplomacySystem.assignTeam(world, "P1", "alpha");
        DiplomacySystem.assignTeam(world, "P2", "alpha");
        DiplomacySystem.assignTeam(world, "P3", "beta");
        Unit actor = new Unit("P1", 1, Rules.STARTING_SHIP, 100, 100);
        Unit ally = new Unit("P2", 1, Rules.STARTING_SHIP, 140, 100);
        Unit enemy = new Unit("P3", 1, Rules.STARTING_SHIP, 180, 100);
        world.units.put(actor.key(), actor);
        world.units.put(ally.key(), ally);
        world.units.put(enemy.key(), enemy);

        actor.issueAttack(CombatTarget.unit(ally));
        require(actor.attackTarget.isBlank(), "Attack mutation must reject allied targets.");
        actor.issueAttack(CombatTarget.unit(enemy));
        require(!actor.attackTarget.isBlank(), "Attack mutation must accept hostile targets.");

        UnitOrderCommand escortAlly = new UnitOrderCommand("P1", 1, UnitOrderType.ESCORT,
                0, 0, 0, 0, 0, CombatTarget.unit(ally), 0);
        require(AUnitOrder.apply(world, escortAlly), "Server order validation must accept allied escort targets.");
        UnitOrderCommand escortEnemy = new UnitOrderCommand("P1", 1, UnitOrderType.ESCORT,
                0, 0, 0, 0, 0, CombatTarget.unit(enemy), 0);
        require(!AUnitOrder.apply(world, escortEnemy), "Server order validation must reject hostile escort targets.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
