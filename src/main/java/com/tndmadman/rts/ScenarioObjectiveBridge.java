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
        if (isScenarioState(incoming)) {
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
        if (isScenarioState(network)) return scenarioView(world, network);
        return ObjectiveSystem.view(world);
    }

    static boolean scenarioActive(World world) {
        return ScenarioDirector.active(world) || isScenarioState(NETWORK.get(world));
    }

    static boolean scenarioFailure(World world) {
        if (ScenarioDirector.active(world)) return ScenarioDirector.view(world).failed();
        ObjectiveState state = NETWORK.get(world);
        return terminalScenarioState(state) && state.current() == 0;
    }

    static String scenarioOutcome(World world) {
        if (ScenarioDirector.active(world)) return ScenarioDirector.view(world).outcomeText();
        ObjectiveState state = NETWORK.get(world);
        if (!isScenarioState(state)) return "";
        ScenarioDefinition definition = definitionFor(state.conditionId());
        if (definition == null) return "";
        return scenarioFailure(world) ? definition.failureText()
                : terminalScenarioState(state) ? definition.completionText() : "";
    }

    static boolean isScenarioState(ObjectiveState state) {
        return state != null && state.conditionId().startsWith(PREFIX);
    }

    static ScenarioDefinition definitionFor(String conditionId) {
        if (conditionId == null || !conditionId.startsWith(PREFIX)) return null;
        String rest = conditionId.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        String scenarioId = dot < 0 ? rest : rest.substring(0, dot);
        return ScenarioRules.definition(scenarioId);
    }

    static ScenarioObjective objectiveFor(String conditionId) {
        ScenarioDefinition definition = definitionFor(conditionId);
        if (definition == null) return null;
        String objectiveId = objectiveId(conditionId, definition.id());
        return objectiveId.isBlank() ? null : definition.objective(objectiveId);
    }

    static boolean terminalScenarioState(ObjectiveState state) {
        if (!isScenarioState(state) || state.status() != ObjectiveStatus.COMPLETED) return false;
        ScenarioDefinition definition = definitionFor(state.conditionId());
        return definition != null && objectiveId(state.conditionId(), definition.id()).isBlank();
    }

    private static ObjectiveView scenarioView(World world, ObjectiveState state) {
        ScenarioDefinition definition = definitionFor(state.conditionId());
        if (definition == null) {
            return new ObjectiveView(state.conditionId(), "Scenario objective", "",
                    state.status(), state.current(), state.target(),
                    participantName(state.completedById()), participantName(state.leaderId()));
        }
        ScenarioObjective objective = objectiveFor(state.conditionId());
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

    private static String objectiveId(String conditionId, String scenarioId) {
        String prefix = PREFIX + scenarioId;
        if (conditionId == null || conditionId.equals(prefix)) return "";
        String objectivePrefix = prefix + ".";
        return conditionId.startsWith(objectivePrefix) ? conditionId.substring(objectivePrefix.length()) : "";
    }

    private static String participantName(String participantId) {
        return participantId == null || participantId.isBlank() ? "" : PlayerRegistry.name(participantId);
    }
}
