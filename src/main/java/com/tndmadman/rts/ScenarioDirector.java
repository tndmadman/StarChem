package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-authoritative scenario progression runtime.
 *
 * The director deliberately owns progression instead of placing scenario-specific switches in World or UI code.
 * State can be captured/restored by the server persistence layer and projected to a filtered client view.
 */
final class ScenarioDirector {
    private static final long VIEW_REFRESH_NANOS = 200_000_000L;
    private static final Map<World,RuntimeState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ScenarioDirector() { }

    static boolean active(World world) {
        return world != null && STATES.containsKey(world);
    }

    static void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    static void start(World world, String scenarioId) {
        start(world, ScenarioRules.require(scenarioId), true);
    }

    static void start(World world, ScenarioDefinition definition, boolean applyStartingState) {
        if (world == null) throw new IllegalArgumentException("World is required to start a scenario.");
        ScenarioRules.validate(definition);
        RuntimeState runtime = RuntimeState.initial(definition, ScenarioMetrics.collect(world).maxSystemTime());
        STATES.put(world, runtime);
        if (applyStartingState) applyStartingState(world, definition.startingState());
        runtime.lastObservedSystemTime = ScenarioMetrics.collect(world).maxSystemTime();
        unlockReadyObjectives(runtime);
        evaluateAuthoritative(world);
    }

    static ScenarioDefinition definition(World world) {
        RuntimeState runtime = STATES.get(world);
        return runtime == null ? null : runtime.definition;
    }

    static void evaluateAuthoritative(World world) {
        RuntimeState runtime = STATES.get(world);
        if (world == null || runtime == null || runtime.terminal()) return;
        ScenarioMetrics metrics = ScenarioMetrics.collect(world);
        advanceClock(runtime, metrics.maxSystemTime());

        boolean changed;
        int passes = 0;
        do {
            changed = false;
            unlockReadyObjectives(runtime);
            for (ScenarioObjective objective : runtime.definition.objectives()) {
                if (runtime.status(objective.id()) != ScenarioObjectiveStatus.ACTIVE) continue;
                ScenarioProgress progress = evaluateTrigger(world, runtime, metrics, objective.trigger());
                runtime.progress.put(objective.id(), progress);
                if (!progress.complete()) continue;
                completeObjective(world, runtime, objective);
                changed = true;
                if (runtime.terminal()) break;
            }
            passes++;
        } while (changed && !runtime.terminal() && passes <= runtime.definition.objectives().size() + 1);

        if (!runtime.terminal() && allPrimaryObjectivesComplete(runtime)) {
            finish(runtime, true, runtime.definition.completionText());
        }
        runtime.lastEvaluationNanos = System.nanoTime();
    }

    static ScenarioView view(World world) {
        RuntimeState runtime = STATES.get(world);
        if (runtime == null) return ScenarioView.disabled();
        long now = System.nanoTime();
        if (!runtime.terminal() && (runtime.lastEvaluationNanos == 0
                || now - runtime.lastEvaluationNanos >= VIEW_REFRESH_NANOS)) {
            evaluateAuthoritative(world);
        }
        List<ScenarioObjectiveView> objectives = new ArrayList<>();
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            ScenarioObjectiveStatus status = runtime.status(objective.id());
            if (objective.hidden() && status != ScenarioObjectiveStatus.COMPLETED
                    && status != ScenarioObjectiveStatus.FAILED) continue;
            ScenarioProgress progress = runtime.progress.getOrDefault(objective.id(),
                    new ScenarioProgress(0, objective.trigger().target(), ""));
            objectives.add(new ScenarioObjectiveView(objective.id(), objective.title(), objective.description(),
                    objective.kind(), status, progress.current(), progress.target()));
        }
        return new ScenarioView(runtime.definition.id(), runtime.definition.name(), runtime.definition.description(),
                runtime.completed, runtime.failed, runtime.outcomeText, runtime.lastNotice,
                runtime.elapsedSeconds, objectives);
    }

    /**
     * Compatibility projection for the existing single-objective network/UI model.
     * This does not expose hidden locked/active objectives.
     */
    static ObjectiveState objectiveState(World world) {
        RuntimeState runtime = STATES.get(world);
        if (runtime == null) return ObjectiveState.disabled();
        ScenarioObjective selected = visibleFocusObjective(runtime);
        if (selected == null) {
            String id = "scenario." + runtime.definition.id();
            ObjectiveStatus status = runtime.completed || runtime.failed
                    ? ObjectiveStatus.COMPLETED : ObjectiveStatus.ACTIVE;
            return new ObjectiveState(id, status, runtime.completed ? 1 : 0, 1,
                    runtime.leaderId, runtime.completed ? runtime.leaderId : "", runtime.elapsedSeconds);
        }
        ScenarioProgress progress = runtime.progress.getOrDefault(selected.id(),
                new ScenarioProgress(0, selected.trigger().target(), ""));
        ObjectiveStatus status = runtime.terminal() ? ObjectiveStatus.COMPLETED : ObjectiveStatus.ACTIVE;
        return new ObjectiveState("scenario." + runtime.definition.id() + "." + selected.id(), status,
                progress.current(), Math.max(1, progress.target()), progress.leaderId(),
                runtime.completed ? progress.leaderId() : "", runtime.elapsedSeconds);
    }

    static Map<String,Object> capture(World world) {
        RuntimeState runtime = STATES.get(world);
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
        ScenarioRules.validate(definition);
        RuntimeState runtime = RuntimeState.initial(definition, ScenarioMetrics.collect(world).maxSystemTime());

        Map<String,Object> statuses = ServerSaveStore.object(row.get("objectiveStatuses"));
        for (Map.Entry<String,Object> entry : statuses.entrySet()) {
            if (definition.objective(entry.getKey()) == null) {
                throw new IllegalArgumentException("Saved scenario " + scenarioId
                        + " references unknown objective " + entry.getKey() + ".");
            }
            ScenarioObjectiveStatus status = ServerSaveStore.enumValue(ScenarioObjectiveStatus.class,
                    entry.getValue(), ScenarioObjectiveStatus.LOCKED);
            runtime.statuses.put(entry.getKey(), status);
        }

        Map<String,Object> progress = ServerSaveStore.object(row.get("objectiveProgress"));
        for (Map.Entry<String,Object> entry : progress.entrySet()) {
            ScenarioObjective objective = definition.objective(entry.getKey());
            if (objective == null) continue;
            Map<String,Object> value = ServerSaveStore.object(entry.getValue());
            runtime.progress.put(entry.getKey(), new ScenarioProgress(
                    Math.max(0, ServerSaveStore.intValue(value, "current", 0)),
                    Math.max(0, ServerSaveStore.intValue(value, "target", objective.trigger().target())),
                    ServerSaveStore.string(value, "leaderId", "")));
        }
        for (Object action : ServerSaveStore.list(row.get("firedActions"))) {
            String key = String.valueOf(action);
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
            unlockReadyObjectives(runtime);
            evaluateAuthoritative(world);
        }
    }

    private static void applyStartingState(World world, ScenarioStartingState startingState) {
        if (startingState == null) return;
        String previousSystem = world.activeSystemId();
        if (!startingState.startingSystem().isBlank()) {
            world.activateSystem(startingState.startingSystem());
        }
        for (Map.Entry<String,List<String>> entry : startingState.completedResearch().entrySet()) {
            for (String playerId : resolveOwners(entry.getKey())) {
                world.completedResearch.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>())
                        .addAll(entry.getValue());
            }
        }
        for (ScenarioStartingShip ship : startingState.ships()) {
            for (String playerId : resolveOwners(ship.owner())) {
                for (int i = 0; i < ship.count(); i++) {
                    int unitId = nextUnitId(world, playerId);
                    double x = ship.x() + i * ship.spacing();
                    world.units.put(Unit.key(playerId, unitId), new Unit(playerId, unitId, ship.typeId(), x, ship.y()));
                }
            }
        }
        for (ScenarioStartingStation station : startingState.stations()) {
            for (String playerId : resolveOwners(station.owner())) {
                String baseId = nextBaseId(world, playerId);
                Base base = new Base(baseId, playerId, station.typeId(), station.x(), station.y());
                for (Map.Entry<String,Double> inventory : station.inventory().entrySet()) {
                    Material material = Material.valueOf(inventory.getKey());
                    if (inventory.getValue() > 0) base.inventory.put(material, inventory.getValue());
                }
                world.bases.put(baseId, base);
            }
        }
        world.saveActiveSystem();
        if (!startingState.startingSystem().isBlank()) {
            // A scenario-selected starting system becomes the active view. Otherwise preserve caller state.
        } else if (previousSystem != null && !previousSystem.isBlank()) {
            world.activateSystem(previousSystem);
        }
    }

    private static List<String> resolveOwners(String owner) {
        List<String> humans = humanPlayers();
        if ("ALL_HUMANS".equals(owner)) return humans;
        if ("PLAYER".equals(owner)) {
            String local = PlayerRegistry.localId();
            if (local != null && !local.isBlank() && !"WAIT".equals(local) && !NpcRules.isNpcFaction(local)) {
                return List.of(local);
            }
            return humans.isEmpty() ? List.of() : List.of(humans.get(0));
        }
        return owner == null || owner.isBlank() ? List.of() : List.of(owner);
    }

    private static int nextUnitId(World world, String playerId) {
        int id = 1;
        while (world.units.containsKey(Unit.key(playerId, id))) id++;
        return id;
    }

    private static String nextBaseId(World world, String playerId) {
        int id = 1;
        while (world.bases.containsKey(playerId + ":B" + id)) id++;
        return playerId + ":B" + id;
    }

    private static void advanceClock(RuntimeState runtime, double observed) {
        if (!Double.isFinite(observed) || observed < 0) return;
        if (Double.isFinite(runtime.lastObservedSystemTime) && observed >= runtime.lastObservedSystemTime) {
            runtime.elapsedSeconds += observed - runtime.lastObservedSystemTime;
        }
        runtime.lastObservedSystemTime = observed;
    }

    private static ScenarioProgress evaluateTrigger(World world, RuntimeState runtime, ScenarioMetrics metrics,
                                                    ScenarioTrigger trigger) {
        List<List<String>> groups = participantGroups(world, runtime.definition.multiplayer(), metrics.players());
        int selected = trigger.comparison() == ScenarioComparison.AT_MOST ? Integer.MAX_VALUE : 0;
        String leader = "";
        boolean any = false;
        for (List<String> members : groups) {
            int current = groupProgress(world, runtime, metrics, members, trigger);
            boolean better = !any || trigger.comparison() == ScenarioComparison.AT_MOST
                    ? current < selected : current > selected;
            if (better) {
                selected = current;
                leader = groupId(world, runtime.definition.multiplayer(), members);
            }
            any = true;
        }
        if (!any) selected = 0;
        boolean complete = compare(selected, trigger.target(), trigger.comparison()) && any;
        runtime.leaderId = leader;
        return new ScenarioProgress(Math.max(0, selected), trigger.target(), leader, complete);
    }

    private static int groupProgress(World world, RuntimeState runtime, ScenarioMetrics metrics,
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
        for (String id : metricPlayers) if (humanPlayer(id)) humans.add(id);
        for (String id : humanPlayers()) if (!humans.contains(id)) humans.add(id);
        Collections.sort(humans);
        if (humans.isEmpty()) return List.of();
        if (mode == ScenarioMultiplayerMode.SOLO || mode == ScenarioMultiplayerMode.COOP) {
            return List.of(List.copyOf(humans));
        }
        if (mode == ScenarioMultiplayerMode.COMPETITIVE) {
            List<List<String>> groups = new ArrayList<>();
            for (String id : humans) groups.add(List.of(id));
            return groups;
        }
        Map<String,List<String>> teams = new LinkedHashMap<>();
        for (String id : humans) {
            String group = DiplomacySystem.victoryGroupId(world, id);
            if (group.isBlank()) group = id;
            teams.computeIfAbsent(group, ignored -> new ArrayList<>()).add(id);
        }
        return new ArrayList<>(teams.values());
    }

    private static String groupId(World world, ScenarioMultiplayerMode mode, List<String> members) {
        if (members == null || members.isEmpty()) return "";
        if (mode == ScenarioMultiplayerMode.SOLO || mode == ScenarioMultiplayerMode.COOP) return members.get(0);
        if (mode == ScenarioMultiplayerMode.COMPETITIVE) return members.get(0);
        String group = DiplomacySystem.victoryGroupId(world, members.get(0));
        return group.isBlank() ? members.get(0) : group;
    }

    private static void completeObjective(World world, RuntimeState runtime, ScenarioObjective objective) {
        runtime.statuses.put(objective.id(), objective.kind() == ScenarioObjectiveKind.FAILURE
                ? ScenarioObjectiveStatus.FAILED : ScenarioObjectiveStatus.COMPLETED);
        executeActions(runtime, objective);
        if (objective.kind() == ScenarioObjectiveKind.FAILURE && !runtime.terminal()) {
            finish(runtime, false, runtime.definition.failureText());
        }
    }

    private static void executeActions(RuntimeState runtime, ScenarioObjective objective) {
        for (int i = 0; i < objective.actions().size(); i++) {
            String key = objective.id() + "#" + i;
            if (!runtime.firedActions.add(key)) continue;
            ScenarioAction action = objective.actions().get(i);
            switch (action.type()) {
                case ACTIVATE_OBJECTIVE -> {
                    ScenarioObjectiveStatus status = runtime.status(action.target());
                    if (status == ScenarioObjectiveStatus.LOCKED) {
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

    private static void unlockReadyObjectives(RuntimeState runtime) {
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (runtime.status(objective.id()) != ScenarioObjectiveStatus.LOCKED) continue;
            boolean ready = true;
            for (String prerequisite : objective.prerequisites()) {
                if (runtime.status(prerequisite) != ScenarioObjectiveStatus.COMPLETED) {
                    ready = false;
                    break;
                }
            }
            if (ready && objective.prerequisites().isEmpty()
                    || ready && !objective.prerequisites().isEmpty()) {
                runtime.statuses.put(objective.id(), ScenarioObjectiveStatus.ACTIVE);
            }
        }
    }

    private static boolean allPrimaryObjectivesComplete(RuntimeState runtime) {
        boolean sawPrimary = false;
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (objective.kind() != ScenarioObjectiveKind.PRIMARY) continue;
            sawPrimary = true;
            if (runtime.status(objective.id()) != ScenarioObjectiveStatus.COMPLETED) return false;
        }
        return sawPrimary;
    }

    private static void finish(RuntimeState runtime, boolean victory, String text) {
        if (runtime.terminal()) return;
        runtime.completed = victory;
        runtime.failed = !victory;
        runtime.outcomeText = text == null || text.isBlank()
                ? (victory ? runtime.definition.completionText() : runtime.definition.failureText()) : text;
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (runtime.status(objective.id()) == ScenarioObjectiveStatus.LOCKED) {
                runtime.statuses.put(objective.id(), ScenarioObjectiveStatus.SKIPPED);
            }
        }
    }

    private static ScenarioObjective visibleFocusObjective(RuntimeState runtime) {
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (!objective.hidden() && objective.kind() == ScenarioObjectiveKind.PRIMARY
                    && runtime.status(objective.id()) == ScenarioObjectiveStatus.ACTIVE) return objective;
        }
        for (ScenarioObjective objective : runtime.definition.objectives()) {
            if (!objective.hidden() && runtime.status(objective.id()) == ScenarioObjectiveStatus.ACTIVE) return objective;
        }
        return null;
    }

    private static List<String> humanPlayers() {
        List<String> out = new ArrayList<>();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) if (humanPlayer(player.id())) out.add(player.id());
        Collections.sort(out);
        return List.copyOf(out);
    }

    private static boolean humanPlayer(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }

    private static final class RuntimeState {
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

        private RuntimeState(ScenarioDefinition definition, double observedTime) {
            this.definition = definition;
            this.lastObservedSystemTime = observedTime;
            for (ScenarioObjective objective : definition.objectives()) {
                statuses.put(objective.id(), ScenarioObjectiveStatus.LOCKED);
                progress.put(objective.id(), new ScenarioProgress(0, objective.trigger().target(), ""));
            }
        }

        static RuntimeState initial(ScenarioDefinition definition, double observedTime) {
            return new RuntimeState(definition, observedTime);
        }

        ScenarioObjectiveStatus status(String id) {
            return statuses.getOrDefault(id, ScenarioObjectiveStatus.LOCKED);
        }

        boolean terminal() { return completed || failed; }
    }
}

record ScenarioProgress(int current, int target, String leaderId, boolean complete) {
    ScenarioProgress(int current, int target, String leaderId) {
        this(current, target, leaderId, false);
    }

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

/** Galaxy-wide metrics used by scenario triggers. */
final class ScenarioMetrics {
    private final Set<String> players = new LinkedHashSet<>();
    private final Map<String,Integer> ships = new HashMap<>();
    private final Map<String,Integer> combatShips = new HashMap<>();
    private final Map<String,Integer> stations = new HashMap<>();
    private final Map<String,Integer> liveAssets = new HashMap<>();
    private final Map<String,Integer> fleetPower = new HashMap<>();
    private final Map<String,Integer> controlledSystems = new HashMap<>();
    private final Map<String,Map<String,Integer>> shipTypes = new HashMap<>();
    private final Map<String,Map<String,Integer>> stationTypes = new HashMap<>();
    private double maxSystemTime;

    private ScenarioMetrics() { }

    static ScenarioMetrics collect(World world) {
        ScenarioMetrics metrics = new ScenarioMetrics();
        if (world == null) return metrics;
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) metrics.players.add(player.id());
        metrics.players.addAll(world.completedResearch.keySet());
        GalaxyMapSnapshot galaxy = world.authoritativeGalaxyMapSnapshot();
        Set<String> visited = new LinkedHashSet<>();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) {
                if (system == null) continue;
                if (system.id() != null && !system.id().isBlank()) visited.add(system.id());
                if (human(system.controllerId())) {
                    metrics.controlledSystems.merge(system.controllerId(), 1, Integer::sum);
                    metrics.players.add(system.controllerId());
                }
            }
        }
        String previousSystem = world.activeSystemId();
        String previousStatus = world.status;
        if (visited.isEmpty() && previousSystem != null && !previousSystem.isBlank()) visited.add(previousSystem);
        try {
            for (String systemId : visited) {
                world.activateSystem(systemId);
                metrics.maxSystemTime = Math.max(metrics.maxSystemTime, world.systemTime());
                for (Unit unit : world.units.values()) {
                    if (unit.hp <= 0 || !human(unit.playerId)) continue;
                    metrics.players.add(unit.playerId);
                    metrics.ships.merge(unit.playerId, 1, Integer::sum);
                    metrics.liveAssets.merge(unit.playerId, 1, Integer::sum);
                    if (WeaponRules.armed(unit)) metrics.combatShips.merge(unit.playerId, 1, Integer::sum);
                    metrics.shipTypes.computeIfAbsent(unit.playerId, ignored -> new HashMap<>())
                            .merge(unit.shipTypeId, 1, Integer::sum);
                    metrics.fleetPower.merge(unit.playerId, strength(unit.hp, unit.shield), Integer::sum);
                }
                for (Base base : world.bases.values()) {
                    if (base.hp <= 0 || !human(base.playerId)) continue;
                    metrics.players.add(base.playerId);
                    metrics.stations.merge(base.playerId, 1, Integer::sum);
                    metrics.liveAssets.merge(base.playerId, 1, Integer::sum);
                    metrics.stationTypes.computeIfAbsent(base.playerId, ignored -> new HashMap<>())
                            .merge(base.typeId, 1, Integer::sum);
                    metrics.fleetPower.merge(base.playerId, strength(base.hp, base.shield), Integer::sum);
                }
            }
        } finally {
            if (previousSystem != null && !previousSystem.isBlank()) world.activateSystem(previousSystem);
            world.status = previousStatus;
        }
        return metrics;
    }

    Set<String> players() { return Set.copyOf(players); }
    int ships(String id) { return ships.getOrDefault(id, 0); }
    int combatShips(String id) { return combatShips.getOrDefault(id, 0); }
    int stations(String id) { return stations.getOrDefault(id, 0); }
    int liveAssets(String id) { return liveAssets.getOrDefault(id, 0); }
    int fleetPower(String id) { return fleetPower.getOrDefault(id, 0); }
    int controlledSystems(String id) { return controlledSystems.getOrDefault(id, 0); }
    Map<String,Integer> shipTypes(String id) { return shipTypes.getOrDefault(id, Map.of()); }
    Map<String,Integer> stationTypes(String id) { return stationTypes.getOrDefault(id, Map.of()); }
    double maxSystemTime() { return maxSystemTime; }

    private static int strength(double hp, double shield) {
        double total = Math.max(0, hp) + Math.max(0, shield);
        if (!Double.isFinite(total)) return 0;
        return (int)Math.min(Integer.MAX_VALUE, Math.round(total));
    }

    private static boolean human(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }
}
