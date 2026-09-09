package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executable regression coverage for the issue #384 scenario foundation. */
public final class ScenarioFrameworkValidator {
    private ScenarioFrameworkValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("Scenario framework validation passed.");
    }

    static void validate() {
        validateCatalog();
        validateProgressionAndStartingState();
        validateCaptureRestore();
        validateFailureAndHiddenObjective();
        validateMalformedGraph();
    }

    private static void validateCatalog() {
        expectEquals("sample scenario count", 3, ScenarioRules.all().size());
        expectEquals("frontier lookup", "frontier_industry", ScenarioRules.require("frontier_industry").id());
        expectEquals("survival lookup", "corsair_endurance", ScenarioRules.require("corsair_endurance").id());
        expectEquals("conquest lookup", "conquest_path", ScenarioRules.require("conquest_path").id());
        for (ScenarioDefinition definition : ScenarioRules.all()) ScenarioRules.validate(definition);
    }

    private static void validateProgressionAndStartingState() {
        PlayerRegistry.reset("SOLO", "Scenario Tester", 0x50BEFF);
        World world = new World("Scenario Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        int shipsBefore = liveShips(world, "SOLO");
        ScenarioDirector.start(world, "frontier_industry");
        expectEquals("starting ships applied", shipsBefore + 2, liveShips(world, "SOLO"));

        ScenarioView initial = ScenarioDirector.view(world);
        expectEquals("scenario active", true, initial.active());
        expectEquals("first objective completed", ScenarioObjectiveStatus.COMPLETED,
                objective(initial, "establish_fleet").status());
        expectEquals("research stage active", ScenarioObjectiveStatus.ACTIVE,
                objective(initial, "industrial_breakthrough").status());

        world.completeResearch("SOLO", "advanced_industry");
        ScenarioDirector.evaluateAuthoritative(world);
        ScenarioView researched = ScenarioDirector.view(world);
        expectEquals("research objective completed", ScenarioObjectiveStatus.COMPLETED,
                objective(researched, "industrial_breakthrough").status());
        expectEquals("hauler stage active", ScenarioObjectiveStatus.ACTIVE,
                objective(researched, "hauler_wing").status());

        for (int i = 100; i < 103; i++) {
            world.units.put(Unit.key("SOLO", i), new Unit("SOLO", i, "hauler", 900 + i, 900));
        }
        world.saveActiveSystem();
        ScenarioDirector.evaluateAuthoritative(world);
        ScenarioView hauling = ScenarioDirector.view(world);
        expectEquals("hauler stage completed", ScenarioObjectiveStatus.COMPLETED,
                objective(hauling, "hauler_wing").status());
        expectEquals("regional stage active", ScenarioObjectiveStatus.ACTIVE,
                objective(hauling, "regional_presence").status());
    }

    private static void validateCaptureRestore() {
        PlayerRegistry.reset("SOLO", "Save Tester", 0x50BEFF);
        World source = new World("Save Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioDirector.start(source, "frontier_industry");
        source.completeResearch("SOLO", "advanced_industry");
        ScenarioDirector.evaluateAuthoritative(source);
        Map<String,Object> saved = ScenarioDirector.capture(source);
        expectEquals("saved scenario ID", "frontier_industry", saved.get("scenarioId"));

        PlayerRegistry.reset("SOLO", "Restore Tester", 0x50BEFF);
        World restored = new World("Restore Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioDirector.restore(restored, saved);
        ScenarioView view = ScenarioDirector.view(restored);
        expectEquals("restored scenario ID", "frontier_industry", view.id());
        expectEquals("restored completed research stage", ScenarioObjectiveStatus.COMPLETED,
                objective(view, "industrial_breakthrough").status());
        expectEquals("restored next stage", ScenarioObjectiveStatus.ACTIVE,
                objective(view, "hauler_wing").status());
    }

    private static void validateFailureAndHiddenObjective() {
        PlayerRegistry.reset("SOLO", "Failure Tester", 0x50BEFF);
        World world = new World("Failure Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioDirector.start(world, "corsair_endurance");
        ScenarioView initial = ScenarioDirector.view(world);
        if (hasObjective(initial, "fleet_destroyed")) {
            throw new IllegalStateException("Hidden failure objective leaked before it resolved.");
        }
        for (Unit unit : world.units.values()) if ("SOLO".equals(unit.playerId)) unit.hp = 0;
        for (Base base : world.bases.values()) if ("SOLO".equals(base.playerId)) base.hp = 0;
        world.saveActiveSystem();
        ScenarioDirector.evaluateAuthoritative(world);
        ScenarioView failed = ScenarioDirector.view(world);
        expectEquals("scenario failed", true, failed.failed());
        expectEquals("failure objective revealed", ScenarioObjectiveStatus.FAILED,
                objective(failed, "fleet_destroyed").status());
    }

    private static void validateMalformedGraph() {
        ScenarioObjective a = new ScenarioObjective("a", "A", "", ScenarioObjectiveKind.PRIMARY,
                List.of("b"), new ScenarioTrigger(ScenarioTriggerType.OWN_SHIPS, "", 1,
                ScenarioComparison.AT_LEAST), List.of(), false);
        ScenarioObjective b = new ScenarioObjective("b", "B", "", ScenarioObjectiveKind.PRIMARY,
                List.of("a"), new ScenarioTrigger(ScenarioTriggerType.OWN_STATIONS, "", 1,
                ScenarioComparison.AT_LEAST), List.of(), false);
        ScenarioDefinition invalid = new ScenarioDefinition("cycle_test", "Cycle Test", "",
                ScenarioMultiplayerMode.SOLO, ScenarioStartingState.empty(), List.of(), Map.of(), Map.of(), Map.of(),
                List.of(a, b), "done", "failed", "validator");
        try {
            ScenarioRules.validate(invalid);
            throw new IllegalStateException("Expected cyclic scenario graph to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static int liveShips(World world, String playerId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId) && unit.hp > 0) count++;
        return count;
    }

    private static ScenarioObjectiveView objective(ScenarioView view, String id) {
        for (ScenarioObjectiveView objective : view.objectives()) if (id.equals(objective.id())) return objective;
        throw new IllegalStateException("Missing scenario objective view: " + id);
    }

    private static boolean hasObjective(ScenarioView view, String id) {
        for (ScenarioObjectiveView objective : view.objectives()) if (id.equals(objective.id())) return true;
        return false;
    }

    private static void expectEquals(String name, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual);
        }
    }
}
