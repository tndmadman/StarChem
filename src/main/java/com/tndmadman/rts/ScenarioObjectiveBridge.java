package com.tndmadman.rts;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps scenario transport compatible with the existing single-objective snapshot field. */
final class ScenarioObjectiveBridge {
    private static final String PREFIX = "scenario.";
    private static final Map<World,ObjectiveState> NETWORK = Collections.synchronizedMap(new WeakHashMap<>());

    private ScenarioObjectiveBridge() { }

    static void evaluateAuthoritative(World world) {
        if (ScenarioDirector.active(world)) ScenarioDirector.evaluateAuthoritative(world);
        else ObjectiveSystem.evaluateAuthoritative(world, 0);
    }

    static ObjectiveState state(World world) {
        if (ScenarioDirector.active(world)) return ScenarioDirector.objectiveState(world);
        return ObjectiveSystem.state(world);
    }

    static void applyNetworkState(World world, ObjectiveState state) {
        if (world == null) return;
        ObjectiveState incoming = state == null ? ObjectiveState.disabled() : state;
        if (scenarioState(incoming)) {
            NETWORK.put(world, incoming);
            return;
        }
        NETWORK.remove(world);
        ObjectiveSystem.applyNetworkState(world, incoming);
    }

    static ObjectiveView view(World world) {
        if (world == null) return ObjectiveView.disabled();
        if (ScenarioDirector.active(world)) {
            ScenarioDirector.evaluateAuthoritative(world);
            return scenarioView(world, ScenarioDirector.objectiveState(world));
        }
        ObjectiveState network = NETWORK.get(world);
        if (scenarioState(network)) return scenarioView(world, network);
        return ObjectiveSystem.view(world);
    }

    static boolean scenarioActive(World world) {
        return ScenarioDirector.active(world) || scenarioState(NETWORK.get(world));
    }

    static boolean scenarioFailure(World world) {
        if (ScenarioDirector.active(world)) return ScenarioDirector.view(world).failed();
        ObjectiveState state = NETWORK.get(world);
        return terminalScenarioState(state) && state.current() == 0;
    }

    static String scenarioOutcome(World world) {
        if (ScenarioDirector.active(world)) return ScenarioDirector.view(world).outcomeText();
        ObjectiveState state = NETWORK.get(world);
        if (!scenarioState(state)) return "";
        ScenarioDefinition definition = scenarioDefinition(state.conditionId());
        if (definition == null) return "";
        return scenarioFailure(world) ? definition.failureText()
                : terminalScenarioState(state) ? definition.completionText() : "";
    }

    private static ObjectiveView scenarioView(World world, ObjectiveState state) {
        ScenarioDefinition definition = scenarioDefinition(state.conditionId());
        if (definition == null) {
            return new ObjectiveView(state.conditionId(), "Scenario objective", "",
                    state.status(), state.current(), state.target(),
                    participantName(state.completedById()), participantName(state.leaderId()));
        }
        String objectiveId = objectiveId(state.conditionId(), definition.id());
        ScenarioObjective objective = objectiveId.isBlank() ? null : definition.objective(objectiveId);
        if (objective == null) {
            String description = state.status() == ObjectiveStatus.COMPLETED
                    ? (state.current() == 0 ? definition.failureText() : definition.completionText())
                    : definition.description();
            return new ObjectiveView(state.conditionId(), definition.name(), description,
                    state.status(), state.current(), state.target(),
                    participantName(state.completedById()), participantName(state.leaderId()));
        }
        return new ObjectiveView(state.conditionId(), objective.title(), objective.description(),
                state.status(), state.current(), state.target(),
                participantName(state.completedById()), participantName(state.leaderId()));
    }

    private static ScenarioDefinition scenarioDefinition(String conditionId) {
        if (conditionId == null || !conditionId.startsWith(PREFIX)) return null;
        String rest = conditionId.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        String scenarioId = dot < 0 ? rest : rest.substring(0, dot);
        return ScenarioRules.definition(scenarioId);
    }

    private static String objectiveId(String conditionId, String scenarioId) {
        String prefix = PREFIX + scenarioId;
        if (conditionId == null || conditionId.equals(prefix)) return "";
        String objectivePrefix = prefix + ".";
        return conditionId.startsWith(objectivePrefix) ? conditionId.substring(objectivePrefix.length()) : "";
    }

    private static boolean scenarioState(ObjectiveState state) {
        return state != null && state.conditionId().startsWith(PREFIX);
    }

    private static boolean terminalScenarioState(ObjectiveState state) {
        return scenarioState(state) && state.status() == ObjectiveStatus.COMPLETED
                && scenarioDefinition(state.conditionId()) != null
                && objectiveId(state.conditionId(), scenarioDefinition(state.conditionId()).id()).isBlank();
    }

    private static String participantName(String participantId) {
        return participantId == null || participantId.isBlank() ? "" : PlayerRegistry.name(participantId);
    }
}
