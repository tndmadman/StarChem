package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executable regression coverage for the issue #384 scenario framework. */
public final class ScenarioFrameworkValidator {
    private ScenarioFrameworkValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("Scenario framework validation passed.");
    }

    static void validate() {
        validateCatalog();
        validateLaunchLifecycle();
        validateProgressionAndStartingState();
        validateSchedulerCaptureRestore();
        validateFailureAndHiddenObjective();
        validateCompletion();
        validateMultiplayerSyncAndReconnect();
        validateMalformedConfig();
        validateSkirmishUnaffected();
        ScenarioLaunch.configure(new String[0]);
    }

    private static void validateCatalog() {
        expectEquals("sample scenario count", 3, ScenarioRules.all().size());
        expectEquals("frontier lookup", "frontier_industry", ScenarioRules.require("frontier_industry").id());
        expectEquals("survival lookup", "corsair_endurance", ScenarioRules.require("corsair_endurance").id());
        expectEquals("conquest lookup", "conquest_path", ScenarioRules.require("conquest_path").id());
        for (ScenarioDefinition definition : ScenarioRules.all()) ScenarioRules.validate(definition);
        expectEquals("corsair alias", Config.CORSAIRS_ID,
                ScenarioLaunch.canonicalFactionId("corsair", "validator"));
    }

    private static void validateLaunchLifecycle() {
        String[] stripped = ScenarioLaunch.configure(new String[]{"--solo", "--scenario", "conquest_path"});
        Config config = Config.parse(stripped);
        GalaxyRuntimeOptions.configure(config);
        expectEquals("scenario galaxy copies", 2, GalaxyRuntimeOptions.copiesPerTemplate());
        expectEquals("scenario selected", "conquest_path", ScenarioLaunch.selectedId());

        PlayerRegistry.reset("SOLO", "Launch Tester", 0x50BEFF);
        World world = new World("Launch Tester", config.disabledNpcFactionIds, config.systemId, true);
        SkirmishRuntime.bind(world, config.skirmishSettings);
        expectEquals("scenario starts through normal world lifecycle", true, ScenarioDirector.active(world));
        expectEquals("scenario startup selection consumed", false, ScenarioLaunch.selected());
        expectEquals("scenario empty NPC allowlist disables raiders", true,
                SkirmishRuntime.settings(world).disabledNpcFactionIds().contains(Config.RAIDERS_ID));
        expectEquals("scenario empty NPC allowlist disables miners", true,
                SkirmishRuntime.settings(world).disabledNpcFactionIds().contains(Config.FREE_MINERS_ID));
        expectEquals("scenario empty NPC allowlist disables corsairs", true,
                SkirmishRuntime.settings(world).disabledNpcFactionIds().contains(Config.CORSAIRS_ID));

        expectRejected("dedicated scenario requires new world",
                () -> ScenarioLaunch.configure(new String[]{"--server", "--scenario", "frontier_industry"}));
        expectRejected("client cannot select server scenario",
                () -> ScenarioLaunch.configure(new String[]{"--join", "127.0.0.1", "50000",
                        "--scenario", "frontier_industry"}));
        ScenarioLaunch.configure(new String[0]);
        GalaxyRuntimeOptions.configureCopies(1);
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

    private static void validateSchedulerCaptureRestore() {
        PlayerRegistry.reset("SOLO", "Save Tester", 0x50BEFF);
        World source = new World("Save Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioDirector.start(source, "frontier_industry");
        source.completeResearch("SOLO", "advanced_industry");
        Map<String,Object> saved = SystemSimulationScheduler.capture(source);
        if (!saved.containsKey("$scenario")) {
            throw new IllegalStateException("Simulation scheduler did not persist scenario runtime state.");
        }

        PlayerRegistry.reset("SOLO", "Restore Tester", 0x50BEFF);
        World restored = new World("Restore Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SystemSimulationScheduler.restore(restored, saved);
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
        ObjectiveState initialWire = ScenarioObjectiveBridge.state(world);
        if (initialWire.conditionId().contains("fleet_destroyed")) {
            throw new IllegalStateException("Hidden failure objective leaked onto the objective wire state.");
        }
        for (Unit unit : world.units.values()) if ("SOLO".equals(unit.playerId)) unit.hp = 0;
        for (Base base : world.bases.values()) if ("SOLO".equals(base.playerId)) base.hp = 0;
        world.saveActiveSystem();
        ScenarioDirector.evaluateAuthoritative(world);
        ScenarioView failed = ScenarioDirector.view(world);
        expectEquals("scenario failed", true, failed.failed());
        expectEquals("failure objective revealed locally after resolution", ScenarioObjectiveStatus.FAILED,
                objective(failed, "fleet_destroyed").status());
        ObjectiveState terminal = ScenarioObjectiveBridge.state(world);
        expectEquals("failure wire terminal status", ObjectiveStatus.COMPLETED, terminal.status());
        expectEquals("failure wire value", 0, terminal.current());
        SnapshotValidator.validate(WorldNetAccess.snapshot(world, 2));
    }

    private static void validateCompletion() {
        PlayerRegistry.reset("SOLO", "Completion Tester", 0x50BEFF);
        World world = new World("Completion Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioObjective objective = new ScenarioObjective("ready", "Ready", "Own one ship.",
                ScenarioObjectiveKind.PRIMARY, List.of(),
                new ScenarioTrigger(ScenarioTriggerType.OWN_SHIPS, "", 1, ScenarioComparison.AT_LEAST),
                List.of(), false);
        ScenarioDefinition definition = new ScenarioDefinition("validator_completion", "Completion Test", "",
                ScenarioMultiplayerMode.SOLO, ScenarioStartingState.empty(), List.of(),
                Map.of(), Map.of(), Map.of(), List.of(objective), "Complete.", "Failed.", "validator");
        ScenarioDirector.start(world, definition, false);
        ScenarioView completed = ScenarioDirector.view(world);
        expectEquals("scenario completion", true, completed.completed());
        ObjectiveState terminal = ScenarioObjectiveBridge.state(world);
        expectEquals("completion wire terminal status", ObjectiveStatus.COMPLETED, terminal.status());
        expectEquals("completion wire value", 1, terminal.current());
    }

    private static void validateMultiplayerSyncAndReconnect() {
        PlayerRegistry.reset("SOLO", "Server Tester", 0x50BEFF);
        World server = new World("Server Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioDirector.start(server, "corsair_endurance");
        Snapshot snapshot = WorldNetAccess.snapshot(server, 7);
        SnapshotValidator.validate(snapshot);
        if (!snapshot.objective().conditionId().startsWith("scenario.corsair_endurance.")) {
            throw new IllegalStateException("Scenario objective was not projected onto multiplayer snapshot state.");
        }
        if (snapshot.objective().conditionId().contains("fleet_destroyed")) {
            throw new IllegalStateException("Hidden failure objective leaked into multiplayer snapshot state.");
        }

        PlayerRegistry.reset("SOLO", "Client Tester", 0x50BEFF);
        World client = new World("Client Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioObjectiveBridge.applyNetworkState(client, snapshot.objective());
        ObjectiveView clientView = ScenarioObjectiveBridge.view(client);
        expectEquals("client scenario title", "Survive the Contact", clientView.title());
        expectEquals("client scenario progress", snapshot.objective().current(), clientView.current());

        World reconnect = new World("Reconnect Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        ScenarioObjectiveBridge.applyNetworkState(reconnect, snapshot.objective());
        ObjectiveView reconnectView = ScenarioObjectiveBridge.view(reconnect);
        expectEquals("reconnect scenario title", clientView.title(), reconnectView.title());
        expectEquals("reconnect scenario progress", clientView.current(), reconnectView.current());
    }

    private static void validateMalformedConfig() {
        ScenarioObjective a = new ScenarioObjective("a", "A", "", ScenarioObjectiveKind.PRIMARY,
                List.of("b"), new ScenarioTrigger(ScenarioTriggerType.OWN_SHIPS, "", 1,
                ScenarioComparison.AT_LEAST), List.of(), false);
        ScenarioObjective b = new ScenarioObjective("b", "B", "", ScenarioObjectiveKind.PRIMARY,
                List.of("a"), new ScenarioTrigger(ScenarioTriggerType.OWN_STATIONS, "", 1,
                ScenarioComparison.AT_LEAST), List.of(), false);
        ScenarioDefinition invalidCycle = new ScenarioDefinition("cycle_test", "Cycle Test", "",
                ScenarioMultiplayerMode.SOLO, ScenarioStartingState.empty(), List.of(), Map.of(), Map.of(), Map.of(),
                List.of(a, b), "done", "failed", "validator");
        expectRejected("cyclic objective graph", () -> ScenarioRules.validate(invalidCycle));

        ScenarioObjective unknownShip = new ScenarioObjective("ship", "Ship", "",
                ScenarioObjectiveKind.PRIMARY, List.of(),
                new ScenarioTrigger(ScenarioTriggerType.OWN_SHIP_TYPE, "missing_validator_hull", 1,
                        ScenarioComparison.AT_LEAST), List.of(), false);
        ScenarioDefinition invalidReference = new ScenarioDefinition("missing_ref", "Missing Ref", "",
                ScenarioMultiplayerMode.SOLO, ScenarioStartingState.empty(), List.of(), Map.of(), Map.of(), Map.of(),
                List.of(unknownShip), "done", "failed", "validator");
        expectRejected("unknown scenario ship reference", () -> ScenarioRules.validate(invalidReference));
    }

    private static void validateSkirmishUnaffected() {
        ScenarioLaunch.configure(new String[0]);
        GalaxyRuntimeOptions.configureCopies(1);
        PlayerRegistry.reset("SOLO", "Skirmish Tester", 0x50BEFF);
        World world = new World("Skirmish Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(world, SkirmishSettings.standard());
        expectEquals("standard skirmish does not start scenario", false, ScenarioDirector.active(world));
        ObjectiveState state = ScenarioObjectiveBridge.state(world);
        if (state.conditionId().startsWith("scenario.")) {
            throw new IllegalStateException("Standard skirmish objective was replaced by scenario state.");
        }
        SnapshotValidator.validate(WorldNetAccess.snapshot(world, 9));
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

    private static void expectRejected(String name, Runnable action) {
        try {
            action.run();
            throw new IllegalStateException("Expected rejection: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectEquals(String name, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual);
        }
    }
}
