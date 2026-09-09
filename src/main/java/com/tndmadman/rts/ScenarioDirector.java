package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-authoritative objective graph runtime for authored scenarios. */
final class ScenarioDirector {
    private static final long VIEW_REFRESH_NANOS = 200_000_000L;
    private static final Map<World,Runtime> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ScenarioDirector() { }

    static boolean active(World world) { return world != null && STATES.containsKey(world); }
    static void clear(World world) { if (world != null) STATES.remove(world); }

    static void start(World world, String scenarioId) {
        start(world, ScenarioRules.require(scenarioId), true);
    }

    static void start(World world, ScenarioDefinition definition, boolean applyStartingState) {
        if (world == null) throw new IllegalArgumentException("World is required to start a scenario.");
        ScenarioRules.validate(definition);
        Runtime runtime = new Runtime(definition, ScenarioMetrics.collect(world).maxSystemTime());
        STATES.put(world, runtime);
        if (applyStartingState) ScenarioInitializer.apply(world, definition.startingState());
        runtime.lastObservedSystemTime = ScenarioMetrics.collect(world).maxSystemTime();
        unlockReady(runtime);
        evaluateAuthoritative(world);
    }

    static ScenarioDefinition definition(World world) {
        Runtime runtime = STATES.get(world);
        return runtime == null ? null : runtime.definition;
    }

    static void evaluateAuthoritative(World world) {
        Runtime runtime = STATES.get(world);
        if (world == null || runtime == null || runtime.terminal()) return;
        ScenarioMetrics metrics = ScenarioMetrics.collect(world);
        advanceClock(runtime, metrics.maxSystemTime());
        int pass = 0;
        boolean changed;
        do {
            changed = false;
            unlockReady(runtime);
            for (ScenarioObjective objective : runtime.definition.objectives()) {
                if (runtime.status(objective.id()) != ScenarioObjectiveStatus.ACTIVE) continue;
                ScenarioProgress progress = evaluateTrigger(world, runtime, metrics, objective.trigger());
                runtime.progress.put(objective.id(), progress);
                if (!progress.complete()) continue;
                completeObjective(runtime, objective);
                changed = true;
                if (runtime.terminal()) break;
            }
            pass++;
        } while (changed && !runtime.terminal() && pass <= runtime.definition.objectives().size());

        if (!runtime.terminal() && allPrimaryComplete(runtime)) {
            finish(runtime, true, runtime.definition.completionText());
        }
        runtime.lastEvaluationNanos = System.nanoTime();
    }

    static ScenarioView view(World world) {
        Runtime runtime = STATES.get(world);
        if (runtime == null) return ScenarioView.disabled();
        long now = System.nanoTime();
        if (!runtime.terminal() && (runtime.lastEvaluationNanos == 0
                || now - runtime.lastEvaluationNanos >= VIEW_REFRESH_NANOS)) evaluateAuthoritative(world);
        List<ScenarioObjectiveView> views = new ArrayList<>();
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            ScenarioObjectiveStatus status = runtime.status(objective.id());
            if (objective.hidden() && status != ScenarioObjectiveStatus.COMPLETED
                    && status != ScenarioObjectiveStatus.FAILED) continue;
            ScenarioProgress progress = runtime.progress.get(objective.id());
            if (progress == null) progress = new ScenarioProgress(0, objective.trigger().target(), "", false);
            views.add(new ScenarioObjectiveView(objective.id(), objective.title(), objective.description(),
                    objective.kind(), status, progress.current(), progress.target()));
        }
        return new ScenarioView(runtime.definition.id(), runtime.definition.name(), runtime.definition.description(),
                runtime.completed, runtime.failed, runtime.outcomeText, runtime.lastNotice,
                runtime.elapsedSeconds, views);
    }

    /** Projection that lets existing objective transport/UI consume one visible scenario stage later. */
    static ObjectiveState objectiveState(World world) {
        Runtime runtime = STATES.get(world);
        if (runtime == null) return ObjectiveState.disabled();
        ScenarioObjective focus = visibleFocus(runtime);
        if (focus == null) {
            return new ObjectiveState("scenario." + runtime.definition.id(),
                    runtime.terminal() ? ObjectiveStatus.COMPLETED : ObjectiveStatus.ACTIVE,
                    runtime.completed ? 1 : 0, 1, runtime.leaderId,
                    runtime.completed ? runtime.leaderId : "", runtime.elapsedSeconds);
        }
        ScenarioProgress progress = runtime.progress.getOrDefault(focus.id(),
                new ScenarioProgress(0, focus.trigger().target(), "", false));
        return new ObjectiveState("scenario." + runtime.definition.id() + "." + focus.id(),
                runtime.terminal() ? ObjectiveStatus.COMPLETED : ObjectiveStatus.ACTIVE,
                progress.current(), Math.max(1, progress.target()), progress.leaderId(),
                runtime.completed ? progress.leaderId() : "", runtime.elapsedSeconds);
    }

    static Map<String,Object> capture(World world) {
        Runtime runtime = STATES.get(world);
        if (runtime == null) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("scenarioId", runtime.definition.id());
        Map<String,Object> statuses = new LinkedHashMap<>();
        for (Map.Entry<String,ScenarioObjectiveStatus> entry : runtime.statuses.entrySet()) {
            statuses.put(entry.getKey(), entry.getValue().name());
        }
        out.put("objectiveStatuses", statuses);
        Map<String,Object> progress = new LinkedHashMap<>();
        for (Map.Entry<String,ScenarioProgress> entry : runtime.progress.entrySet()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("current", entry.getValue().current());
            row.put("target", entry.getValue().target());
            row.put("leaderId", entry.getValue().leaderId());
            progress.put(entry.getKey(), row);
        }
        out.put("objectiveProgress", progress);
        out.put("firedActions", new ArrayList<>(runtime.firedActions));
        out.put("elapsedSeconds", runtime.elapsedSeconds);
        out.put("completed", runtime.completed);
        out.put("failed", runtime.failed);
        out.put("outcomeText", runtime.outcomeText);
        out.put("lastNotice", runtime.lastNotice);
        out.put("leaderId", runtime.leaderId);
        return out;
    }

    static void restore(World world, Object saved) {
        if (world == null) throw new IllegalArgumentException("World is required to restore a scenario.");
        Map<String,Object> row = ServerSaveStore.object(saved);
        if (row.isEmpty()) {
            STATES.remove(world);
            return;
        }
        String scenarioId = ServerSaveStore.string(row, "scenarioId", "");
        ScenarioDefinition definition = ScenarioRules.require(scenarioId);
        Runtime runtime = new Runtime(definition, ScenarioMetrics.collect(world).maxSystemTime());

        for (Map.Entry<String,Object> entry : ServerSaveStore.object(row.get("objectiveStatuses")).entrySet()) {
            if (definition.objective(entry.getKey()) == null) {
                throw new IllegalArgumentException("Saved scenario " + scenarioId
                        + " references unknown objective " + entry.getKey() + ".");
            }
            runtime.statuses.put(entry.getKey(), ServerSaveStore.enumValue(ScenarioObjectiveStatus.class,
                    entry.getValue(), ScenarioObjectiveStatus.LOCKED));
        }
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(row.get("objectiveProgress")).entrySet()) {
            ScenarioObjective objective = definition.objective(entry.getKey());
            if (objective == null) continue;
            Map<String,Object> value = ServerSaveStore.object(entry.getValue());
            runtime.progress.put(entry.getKey(), new ScenarioProgress(
                    Math.max(0, ServerSaveStore.intValue(value, "current", 0)),
                    Math.max(0, ServerSaveStore.intValue(value, "target", objective.trigger().target())),
                    ServerSaveStore.string(value, "leaderId", ""), false));
        }
        for (Object value : ServerSaveStore.list(row.get("firedActions"))) {
            String key = String.valueOf(value);
            if (!key.isBlank()) runtime.firedActions.add(key);
        }
        runtime.elapsedSeconds = Math.max(0, ServerSaveStore.doubleValue(row, "elapsedSeconds", 0));
        runtime.completed = ServerSaveStore.boolValue(row, "completed", false);
        runtime.failed = ServerSaveStore.boolValue(row, "failed", false);
        if (runtime.completed && runtime.failed) {
            throw new IllegalArgumentException("Saved scenario cannot be both completed and failed: " + scenarioId);
        }
        runtime.outcomeText = ServerSaveStore.string(row, "outcomeText", "");
        runtime.lastNotice = ServerSaveStore.string(row, "lastNotice", "");
        runtime.leaderId = ServerSaveStore.string(row, "leaderId", "");
        runtime.lastObservedSystemTime = ScenarioMetrics.collect(world).maxSystemTime();
        STATES.put(world, runtime);
        if (!runtime.terminal()) {
            unlockReady(runtime);
            evaluateAuthoritative(world);
        }
    }

    private static ScenarioProgress evaluateTrigger(World world, Runtime runtime, ScenarioMetrics metrics,
                                                    ScenarioTrigger trigger) {
        List<List<String>> groups = participantGroups(world, runtime.definition.multiplayer(), metrics.players());
        boolean any = false;
        int selected = 0;
        String leader = "";
        for (List<String> members : groups) {
            int current = groupProgress(world, runtime, metrics, members, trigger);
            if (!any || better(current, selected, trigger)) {
                selected = current;
                leader = groupId(world, runtime.definition.multiplayer(), members);
            }
            any = true;
        }
        boolean complete = any && compare(selected, trigger.target(), trigger.comparison());
        runtime.leaderId = leader;
        return new ScenarioProgress(Math.max(0, selected), trigger.target(), leader, complete);
    }

    private static boolean better(int candidate, int selected, ScenarioTrigger trigger) {
        boolean candidateDone = compare(candidate, trigger.target(), trigger.comparison());
        boolean selectedDone = compare(selected, trigger.target(), trigger.comparison());
        if (candidateDone != selectedDone) return candidateDone;
        return trigger.comparison() == ScenarioComparison.AT_MOST ? candidate < selected : candidate > selected;
    }

    private static int groupProgress(World world, Runtime runtime, ScenarioMetrics metrics,
                                     List<String> members, ScenarioTrigger trigger) {
        return switch (trigger.type()) {
            case COMPLETE_RESEARCH -> members.stream().anyMatch(id -> world.hasResearch(id, trigger.value())) ? 1 : 0;
            case COMPLETE_RESEARCH_COUNT -> {
                Set<String> completed = new LinkedHashSet<>();
                for (String id : members) completed.addAll(world.completedResearch.getOrDefault(id, Set.of()));
                yield completed.size();
            }
            case OWN_SHIPS -> sum(members, metrics::ships);
            case OWN_COMBAT_SHIPS -> sum(members, metrics::combatShips);
            case OWN_STATIONS -> sum(members, metrics::stations);
            case OWN_SHIP_TYPE -> sum(members, id -> metrics.shipTypes(id).getOrDefault(trigger.value(), 0));
            case OWN_STATION_TYPE -> sum(members, id -> metrics.stationTypes(id).getOrDefault(trigger.value(), 0));
            case LIVE_ASSETS -> sum(members, metrics::liveAssets);
            case FLEET_POWER -> sum(members, metrics::fleetPower);
            case CONTROL_SYSTEMS -> sum(members, metrics::controlledSystems);
            case SURVIVE_SECONDS -> sum(members, metrics::liveAssets) > 0
                    ? (int)Math.min(Integer.MAX_VALUE, Math.floor(runtime.elapsedSeconds)) : 0;
        };
    }

    private static boolean compare(int current, int target, ScenarioComparison comparison) {
        return switch (comparison) {
            case AT_LEAST -> current >= target;
            case AT_MOST -> current <= target;
            case EQUALS -> current == target;
        };
    }

    private static int sum(List<String> members, java.util.function.ToIntFunction<String> fn) {
        long total = 0;
        for (String member : members) total += Math.max(0, fn.applyAsInt(member));
        return (int)Math.min(Integer.MAX_VALUE, total);
    }

    private static List<List<String>> participantGroups(World world, ScenarioMultiplayerMode mode, Set<String> metricPlayers) {
        List<String> humans = new ArrayList<>();
        for (String id : metricPlayers) if (human(id)) humans.add(id);
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (human(player.id()) && !humans.contains(player.id())) humans.add(player.id());
        }
        Collections.sort(humans);
        if (humans.isEmpty()) return List.of();
        if (mode == ScenarioMultiplayerMode.SOLO || mode == ScenarioMultiplayerMode.COOP) return List.of(List.copyOf(humans));
        if (mode == ScenarioMultiplayerMode.COMPETITIVE) {
            List<List<String>> out = new ArrayList<>();
            for (String id : humans) out.add(List.of(id));
            return out;
        }
        Map<String,List<String>> teams = new LinkedHashMap<>();
        for (String id : humans) {
            String group = DiplomacySystem.victoryGroupId(world, id);
            if (group.isBlank()) group = id;
            teams.computeIfAbsent(group, ignored -> new ArrayList<>()).add(id);
        }
        return List.copyOf(teams.values());
    }

    private static String groupId(World world, ScenarioMultiplayerMode mode, List<String> members) {
        if (members == null || members.isEmpty()) return "";
        if (mode != ScenarioMultiplayerMode.TEAMS) return members.get(0);
        String group = DiplomacySystem.victoryGroupId(world, members.get(0));
        return group.isBlank() ? members.get(0) : group;
    }

    private static boolean human(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }

    private static void advanceClock(Runtime runtime, double observed) {
        if (!Double.isFinite(observed) || observed < 0) return;
        if (Double.isFinite(runtime.lastObservedSystemTime) && observed >= runtime.lastObservedSystemTime) {
            runtime.elapsedSeconds += observed - runtime.lastObservedSystemTime;
        }
        runtime.lastObservedSystemTime = observed;
    }

    private static void unlockReady(Runtime runtime) {
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (runtime.status(objective.id()) != ScenarioObjectiveStatus.LOCKED) continue;
            boolean ready = true;
            for (String prerequisite : objective.prerequisites()) {
                if (runtime.status(prerequisite) != ScenarioObjectiveStatus.COMPLETED) {
                    ready = false;
                    break;
                }
            }
            if (ready) runtime.statuses.put(objective.id(), ScenarioObjectiveStatus.ACTIVE);
        }
    }

    private static void completeObjective(Runtime runtime, ScenarioObjective objective) {
        runtime.statuses.put(objective.id(), objective.kind() == ScenarioObjectiveKind.FAILURE
                ? ScenarioObjectiveStatus.FAILED : ScenarioObjectiveStatus.COMPLETED);
        executeActions(runtime, objective);
        if (objective.kind() == ScenarioObjectiveKind.FAILURE && !runtime.terminal()) {
            finish(runtime, false, runtime.definition.failureText());
        }
    }

    private static void executeActions(Runtime runtime, ScenarioObjective objective) {
        for (int i = 0; i < objective.actions().size(); i++) {
            String key = objective.id() + "#" + i;
            if (!runtime.firedActions.add(key)) continue;
            ScenarioAction action = objective.actions().get(i);
            switch (action.type()) {
                case ACTIVATE_OBJECTIVE -> {
                    if (runtime.status(action.target()) == ScenarioObjectiveStatus.LOCKED) {
                        runtime.statuses.put(action.target(), ScenarioObjectiveStatus.ACTIVE);
                    }
                }
                case NOTICE -> runtime.lastNotice = action.text();
                case END_SCENARIO -> finish(runtime, "victory".equals(action.outcome()),
                        "victory".equals(action.outcome()) ? runtime.definition.completionText()
                                : runtime.definition.failureText());
            }
        }
    }

    private static boolean allPrimaryComplete(Runtime runtime) {
        boolean found = false;
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (objective.kind() != ScenarioObjectiveKind.PRIMARY) continue;
            found = true;
            if (runtime.status(objective.id()) != ScenarioObjectiveStatus.COMPLETED) return false;
        }
        return found;
    }

    private static void finish(Runtime runtime, boolean victory, String text) {
        if (runtime.terminal()) return;
        runtime.completed = victory;
        runtime.failed = !victory;
        runtime.outcomeText = text == null || text.isBlank()
                ? (victory ? runtime.definition.completionText() : runtime.definition.failureText()) : text;
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            ScenarioObjectiveStatus status = runtime.status(objective.id());
            if (status == ScenarioObjectiveStatus.LOCKED || status == ScenarioObjectiveStatus.ACTIVE) {
                runtime.statuses.put(objective.id(), ScenarioObjectiveStatus.SKIPPED);
            }
        }
    }

    private static ScenarioObjective visibleFocus(Runtime runtime) {
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (!objective.hidden() && objective.kind() == ScenarioObjectiveKind.PRIMARY
                    && runtime.status(objective.id()) == ScenarioObjectiveStatus.ACTIVE) return objective;
        }
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (!objective.hidden() && runtime.status(objective.id()) == ScenarioObjectiveStatus.ACTIVE) return objective;
        }
        return null;
    }

    private static final class Runtime {
        final ScenarioDefinition definition;
        final Map<String,ScenarioObjectiveStatus> statuses = new LinkedHashMap<>();
        final Map<String,ScenarioProgress> progress = new LinkedHashMap<>();
        final Set<String> firedActions = new LinkedHashSet<>();
        double elapsedSeconds;
        double lastObservedSystemTime;
        long lastEvaluationNanos;
        boolean completed;
        boolean failed;
        String outcomeText = "";
        String lastNotice = "";
        String leaderId = "";

        Runtime(ScenarioDefinition definition, double observedTime) {
            this.definition = definition;
            this.lastObservedSystemTime = observedTime;
            for (ScenarioObjective objective : definition.objectives()) {
                statuses.put(objective.id(), ScenarioObjectiveStatus.LOCKED);
                progress.put(objective.id(), new ScenarioProgress(0, objective.trigger().target(), "", false));
            }
        }

        ScenarioObjectiveStatus status(String id) {
            return statuses.getOrDefault(id, ScenarioObjectiveStatus.LOCKED);
        }

        boolean terminal() { return completed || failed; }
    }
}

record ScenarioProgress(int current, int target, String leaderId, boolean complete) {
    ScenarioProgress {
        current = Math.max(0, current);
        target = Math.max(0, target);
        leaderId = leaderId == null ? "" : leaderId;
    }
}

record ScenarioObjectiveView(String id, String title, String description, ScenarioObjectiveKind kind,
                             ScenarioObjectiveStatus status, int current, int target) { }

record ScenarioView(String id, String name, String description, boolean completed, boolean failed,
                    String outcomeText, String notice, double elapsedSeconds,
                    List<ScenarioObjectiveView> objectives) {
    ScenarioView {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        outcomeText = outcomeText == null ? "" : outcomeText;
        notice = notice == null ? "" : notice;
        elapsedSeconds = Double.isFinite(elapsedSeconds) ? Math.max(0, elapsedSeconds) : 0;
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    static ScenarioView disabled() { return new ScenarioView("", "", "", false, false, "", "", 0, List.of()); }
    boolean active() { return !id.isBlank(); }
}
