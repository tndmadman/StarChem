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


enum ObjectiveStatus {
    DISABLED,
    ACTIVE,
    COMPLETED
}

record ObjectiveState(String conditionId, ObjectiveStatus status, int current, int target,
                      String leaderId, String completedById, double elapsedSeconds) {
    ObjectiveState {
        conditionId = conditionId == null ? "" : conditionId;
        status = status == null ? ObjectiveStatus.DISABLED : status;
        current = Math.max(0, current);
        target = Math.max(0, target);
        leaderId = leaderId == null ? "" : leaderId;
        completedById = completedById == null ? "" : completedById;
        elapsedSeconds = Double.isFinite(elapsedSeconds) ? Math.max(0, elapsedSeconds) : 0;
    }

    static ObjectiveState disabled() {
        return new ObjectiveState("", ObjectiveStatus.DISABLED, 0, 0, "", "", 0);
    }

    boolean completed() { return status == ObjectiveStatus.COMPLETED; }
}

record ObjectiveView(String id, String title, String description, ObjectiveStatus status,
                     int current, int target, String completedBy, String leader) {
    ObjectiveView {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        status = status == null ? ObjectiveStatus.DISABLED : status;
        current = Math.max(0, current);
        target = Math.max(0, target);
        completedBy = completedBy == null ? "" : completedBy;
        leader = leader == null ? "" : leader;
    }

    static ObjectiveView disabled() {
        return new ObjectiveView("", "", "", ObjectiveStatus.DISABLED, 0, 0, "", "");
    }

    boolean enabled() { return status != ObjectiveStatus.DISABLED; }
    boolean completed() { return status == ObjectiveStatus.COMPLETED; }

    String progressLabel() {
        if (completed()) return "COMPLETE";
        VictoryConditionDefinition definition = VictoryConditionRules.definition(id);
        return definition == null ? current + " / " + target : definition.formatProgress(current);
    }
}

final class ObjectiveSystem {
    private static final long REFRESH_NANOS = 200_000_000L;
    private static final Map<World,ObjectiveState> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World,Boolean> NETWORK_STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World,Long> LAST_REFRESH = Collections.synchronizedMap(new WeakHashMap<>());

    private ObjectiveSystem() { }

    static void reconfigure(World world, SkirmishSettings settings) {
        if (world == null) return;
        SkirmishSettings normalized = settings == null ? SkirmishSettings.standard() : settings;
        ObjectiveState next = initialState(normalized);
        ObjectiveState previous = STATES.get(world);
        if (previous != null && previous.conditionId().equals(next.conditionId())
                && previous.status() != ObjectiveStatus.DISABLED) return;
        STATES.put(world, next);
        NETWORK_STATES.remove(world);
        LAST_REFRESH.remove(world);
    }

    static ObjectiveState state(World world) {
        if (world == null) return ObjectiveState.disabled();
        ObjectiveState state = STATES.get(world);
        if (state != null) return state;
        ObjectiveState initial = initialState(SkirmishRuntime.settings(world));
        STATES.put(world, initial);
        return initial;
    }

    static void refreshAuthoritative(World world) {
        if (world == null) return;
        long now = System.nanoTime();
        Long previous = LAST_REFRESH.get(world);
        if (previous != null && now - previous < REFRESH_NANOS) return;
        LAST_REFRESH.put(world, now);
        evaluateAuthoritative(world, 0);
    }

    static void evaluateAuthoritative(World world, double dt) {
        if (world == null) return;
        SkirmishSettings settings = SkirmishRuntime.settings(world);
        if (settings.preset() == SkirmishPreset.SANDBOX) {
            STATES.put(world, ObjectiveState.disabled());
            return;
        }
        VictoryConditionDefinition definition = settings.victoryCondition();
        ObjectiveState previous = state(world);
        if (!previous.conditionId().equals(definition.id())) previous = initialState(settings);
        if (previous.completed()) {
            STATES.put(world, previous);
            return;
        }

        Metrics metrics = collectMetrics(world);
        double elapsed = Math.max(previous.elapsedSeconds(), metrics.maxSystemTime());
        if (Double.isFinite(dt) && dt > 0) elapsed += Math.min(dt, 1.0);
        Progress best = bestProgress(world, definition, metrics, elapsed);
        boolean completed = best.current() >= definition.target() && !best.participantId().isBlank();
        STATES.put(world, new ObjectiveState(
                definition.id(),
                completed ? ObjectiveStatus.COMPLETED : ObjectiveStatus.ACTIVE,
                best.current(),
                definition.target(),
                best.participantId(),
                completed ? best.participantId() : "",
                elapsed));
    }

    static ObjectiveView view(World world) {
        if (world == null || SkirmishRuntime.settings(world).preset() == SkirmishPreset.SANDBOX) {
            return ObjectiveView.disabled();
        }
        if (!Boolean.TRUE.equals(NETWORK_STATES.get(world))) refreshAuthoritative(world);
        VictoryConditionDefinition definition = SkirmishRuntime.settings(world).victoryCondition();
        ObjectiveState state = state(world);
        if (!definition.id().equals(state.conditionId())) {
            state = initialState(SkirmishRuntime.settings(world));
            STATES.put(world, state);
        }
        return new ObjectiveView(
                definition.id(),
                definition.displayName(),
                definition.description(),
                state.status(),
                state.current(),
                state.target(),
                participantName(world, state.completedById()),
                participantName(world, state.leaderId()));
    }

    static Map<String,Object> capture(World world) {
        ObjectiveState state = state(world);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("conditionId", state.conditionId());
        out.put("status", state.status().name());
        out.put("current", state.current());
        out.put("target", state.target());
        out.put("leaderId", state.leaderId());
        out.put("completedById", state.completedById());
        out.put("elapsedSeconds", state.elapsedSeconds());
        return out;
    }

    static void restore(World world, Object saved) {
        if (world == null) return;
        NETWORK_STATES.remove(world);
        LAST_REFRESH.remove(world);
        Map<String,Object> row = ServerSaveStore.object(saved);
        if (row.isEmpty()) {
            STATES.put(world, initialState(SkirmishRuntime.settings(world)));
            return;
        }
        String conditionId = ServerSaveStore.string(row, "conditionId",
                SkirmishRuntime.settings(world).victoryConditionId());
        VictoryConditionDefinition definition = VictoryConditionRules.definition(conditionId);
        if (definition == null || !conditionId.equals(SkirmishRuntime.settings(world).victoryConditionId())) {
            STATES.put(world, initialState(SkirmishRuntime.settings(world)));
            return;
        }
        ObjectiveStatus status = ServerSaveStore.enumValue(ObjectiveStatus.class,
                row.get("status"), ObjectiveStatus.ACTIVE);
        int current = Math.max(0, ServerSaveStore.intValue(row, "current", 0));
        int target = Math.max(1, ServerSaveStore.intValue(row, "target", definition.target()));
        String leaderId = ServerSaveStore.string(row, "leaderId", "");
        String completedById = ServerSaveStore.string(row, "completedById", "");
        double elapsed = Math.max(0, ServerSaveStore.doubleValue(row, "elapsedSeconds", 0));
        STATES.put(world, new ObjectiveState(conditionId, status, current, target,
                leaderId, completedById, elapsed));
    }

    static void applyNetworkState(World world, ObjectiveState state) {
        if (world == null) return;
        ObjectiveState incoming = state == null ? ObjectiveState.disabled() : state;
        SkirmishSettings settings = SkirmishRuntime.settings(world);
        if (settings.preset() == SkirmishPreset.SANDBOX) {
            STATES.put(world, ObjectiveState.disabled());
            NETWORK_STATES.put(world, true);
            return;
        }
        if (!settings.victoryConditionId().equals(incoming.conditionId())) return;
        STATES.put(world, incoming);
        NETWORK_STATES.put(world, true);
        LAST_REFRESH.remove(world);
    }

    private static ObjectiveState initialState(SkirmishSettings settings) {
        if (settings == null || settings.preset() == SkirmishPreset.SANDBOX) {
            return ObjectiveState.disabled();
        }
        VictoryConditionDefinition definition = settings.victoryCondition();
        return new ObjectiveState(definition.id(), ObjectiveStatus.ACTIVE, 0,
                definition.target(), "", "", 0);
    }

    private static Progress bestProgress(World world, VictoryConditionDefinition definition,
                                         Metrics metrics, double elapsed) {
        List<String> players = new ArrayList<>(metrics.players());
        Collections.sort(players);
        Map<String,List<String>> groups = new LinkedHashMap<>();
        for (String playerId : players) {
            if (!humanPlayer(playerId)) continue;
            String groupId = DiplomacySystem.victoryGroupId(world, playerId);
            if (groupId.isBlank()) groupId = playerId;
            groups.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(playerId);
        }
        Progress best = new Progress("", 0);
        for (Map.Entry<String,List<String>> entry : groups.entrySet()) {
            int current = groupProgress(world, definition, metrics, entry.getValue(), elapsed);
            if (current > best.current()) best = new Progress(entry.getKey(), current);
        }
        return best;
    }

    private static int groupProgress(World world, VictoryConditionDefinition definition,
                                     Metrics metrics, List<String> members, double elapsed) {
        if (members == null || members.isEmpty()) return 0;
        return switch (definition.type()) {
            case COMPLETE_RESEARCH -> members.stream().anyMatch(playerId -> world.hasResearch(playerId, definition.value())) ? 1 : 0;
            case COMPLETE_RESEARCH_COUNT -> {
                Set<String> completed = new LinkedHashSet<>();
                for (String playerId : members) completed.addAll(world.completedResearch.getOrDefault(playerId, Set.of()));
                yield completed.size();
            }
            case OWN_SHIPS -> sum(members, metrics::ships);
            case OWN_COMBAT_SHIPS -> sum(members, metrics::combatShips);
            case OWN_STATIONS -> sum(members, metrics::stations);
            case OWN_SHIP_TYPE -> sum(members,
                    playerId -> metrics.shipTypes(playerId).getOrDefault(definition.value(), 0));
            case OWN_STATION_TYPE -> sum(members,
                    playerId -> metrics.stationTypes(playerId).getOrDefault(definition.value(), 0));
            case FLEET_POWER -> sum(members, metrics::fleetPower);
            case CONTROL_SYSTEMS -> sum(members, metrics::controlledSystems);
            case SURVIVE_SECONDS -> sum(members, metrics::liveAssets) > 0
                    ? (int)Math.min(Integer.MAX_VALUE, Math.floor(elapsed)) : 0;
        };
    }

    private static int sum(List<String> members, java.util.function.ToIntFunction<String> value) {
        long total = 0;
        for (String member : members) total += Math.max(0, value.applyAsInt(member));
        return (int)Math.min(Integer.MAX_VALUE, total);
    }

    private static Metrics collectMetrics(World world) {
        MutableMetrics metrics = new MutableMetrics();
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) metrics.addPlayer(player.id());
        for (String playerId : world.completedResearch.keySet()) metrics.addPlayer(playerId);

        GalaxyMapSnapshot galaxy = world.authoritativeGalaxyMapSnapshot();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) {
                if (system == null || !humanPlayer(system.controllerId())) continue;
                metrics.controlledSystems.merge(system.controllerId(), 1, Integer::sum);
                metrics.addPlayer(system.controllerId());
            }
        }

        String previousSystem = world.activeSystemId();
        String previousStatus = world.status;
        Set<String> visited = new LinkedHashSet<>();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) {
                if (system != null && system.id() != null && !system.id().isBlank()) visited.add(system.id());
            }
        }
        if (visited.isEmpty() && previousSystem != null && !previousSystem.isBlank()) visited.add(previousSystem);
        try {
            for (String systemId : visited) {
                world.activateSystem(systemId);
                metrics.maxSystemTime = Math.max(metrics.maxSystemTime, world.systemTime());
                for (Unit unit : world.units.values()) {
                    if (unit.hp <= 0 || !humanPlayer(unit.playerId)) continue;
                    metrics.addPlayer(unit.playerId);
                    metrics.ships.merge(unit.playerId, 1, Integer::sum);
                    metrics.liveAssets.merge(unit.playerId, 1, Integer::sum);
                    if (WeaponRules.armed(unit)) {
                        metrics.combatShips.merge(unit.playerId, 1, Integer::sum);
                    }
                    metrics.shipTypes.computeIfAbsent(unit.playerId, ignored -> new HashMap<>())
                            .merge(unit.shipTypeId, 1, Integer::sum);
                    metrics.fleetPower.merge(unit.playerId,
                            safeStrength(unit.hp, unit.shield), Integer::sum);
                }
                for (Base base : world.bases.values()) {
                    if (base.hp <= 0 || !humanPlayer(base.playerId)) continue;
                    metrics.addPlayer(base.playerId);
                    metrics.stations.merge(base.playerId, 1, Integer::sum);
                    metrics.liveAssets.merge(base.playerId, 1, Integer::sum);
                    metrics.stationTypes.computeIfAbsent(base.playerId, ignored -> new HashMap<>())
                            .merge(base.typeId, 1, Integer::sum);
                    metrics.fleetPower.merge(base.playerId,
                            safeStrength(base.hp, base.shield), Integer::sum);
                }
            }
        } finally {
            if (previousSystem != null && !previousSystem.isBlank()) world.activateSystem(previousSystem);
            world.status = previousStatus;
        }
        return metrics.freeze();
    }

    private static int safeStrength(double hp, double shield) {
        double sum = Math.max(0, hp) + Math.max(0, shield);
        if (!Double.isFinite(sum)) return 0;
        return (int)Math.min(Integer.MAX_VALUE, Math.round(sum));
    }

    private static String participantName(World world, String participantId) {
        if (participantId == null || participantId.isBlank()) return "";
        if (participantId.startsWith("TEAM:")) {
            String teamId = participantId.substring(5);
            for (DiplomacySystem.TeamDefinition team : DiplomacySystem.teams(world)) {
                if (team.id().equals(teamId)) return team.displayName();
            }
        }
        return PlayerRegistry.name(participantId);
    }

    private static boolean humanPlayer(String playerId) {
        return playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId)
                && !NpcRules.isNpcFaction(playerId);
    }

    private record Progress(String participantId, int current) { }

    private record Metrics(Set<String> players, Map<String,Integer> ships,
                           Map<String,Integer> combatShips, Map<String,Integer> stations,
                           Map<String,Integer> liveAssets, Map<String,Integer> fleetPower,
                           Map<String,Integer> controlledSystems,
                           Map<String,Map<String,Integer>> shipTypes,
                           Map<String,Map<String,Integer>> stationTypes,
                           double maxSystemTime) {
        int ships(String playerId) { return ships.getOrDefault(playerId, 0); }
        int combatShips(String playerId) { return combatShips.getOrDefault(playerId, 0); }
        int stations(String playerId) { return stations.getOrDefault(playerId, 0); }
        int liveAssets(String playerId) { return liveAssets.getOrDefault(playerId, 0); }
        int fleetPower(String playerId) { return fleetPower.getOrDefault(playerId, 0); }
        int controlledSystems(String playerId) { return controlledSystems.getOrDefault(playerId, 0); }
        Map<String,Integer> shipTypes(String playerId) { return shipTypes.getOrDefault(playerId, Map.of()); }
        Map<String,Integer> stationTypes(String playerId) { return stationTypes.getOrDefault(playerId, Map.of()); }
    }

    private static final class MutableMetrics {
        private final Set<String> players = new LinkedHashSet<>();
        private final Map<String,Integer> ships = new LinkedHashMap<>();
        private final Map<String,Integer> combatShips = new LinkedHashMap<>();
        private final Map<String,Integer> stations = new LinkedHashMap<>();
        private final Map<String,Integer> liveAssets = new LinkedHashMap<>();
        private final Map<String,Integer> fleetPower = new LinkedHashMap<>();
        private final Map<String,Integer> controlledSystems = new LinkedHashMap<>();
        private final Map<String,Map<String,Integer>> shipTypes = new LinkedHashMap<>();
        private final Map<String,Map<String,Integer>> stationTypes = new LinkedHashMap<>();
        private double maxSystemTime;

        void addPlayer(String playerId) { if (humanPlayer(playerId)) players.add(playerId); }

        Metrics freeze() {
            return new Metrics(Set.copyOf(players), Map.copyOf(ships), Map.copyOf(combatShips),
                    Map.copyOf(stations), Map.copyOf(liveAssets), Map.copyOf(fleetPower),
                    Map.copyOf(controlledSystems), nestedCopy(shipTypes), nestedCopy(stationTypes),
                    maxSystemTime);
        }

        private static Map<String,Map<String,Integer>> nestedCopy(Map<String,Map<String,Integer>> source) {
            Map<String,Map<String,Integer>> out = new LinkedHashMap<>();
            for (Map.Entry<String,Map<String,Integer>> entry : source.entrySet()) {
                out.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            return Map.copyOf(out);
        }
    }
}
