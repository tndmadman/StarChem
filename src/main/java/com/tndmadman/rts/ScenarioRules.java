package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Declarative scenario catalog for issue #384. */
final class ScenarioRules {
    private static final Path DEFAULT_INDEX = Path.of("config/scenarios/index.json");
    private static final MiniJson.Limits JSON_LIMITS = new MiniJson.Limits(
            2 * 1024 * 1024, 64, 250_000, 256 * 1024, 50_000, 128, true);
    private static final List<ScenarioDefinition> DEFINITIONS = loadAll(DEFAULT_INDEX);
    private static final Map<String,ScenarioDefinition> BY_ID = index(DEFINITIONS);

    private ScenarioRules() { }

    static List<ScenarioDefinition> all() { return DEFINITIONS; }

    static ScenarioDefinition definition(String id) {
        return BY_ID.get(cleanIdLookup(id));
    }

    static ScenarioDefinition require(String id) {
        ScenarioDefinition definition = definition(id);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown scenario: " + id
                    + ". Expected one of: " + String.join(", ", BY_ID.keySet()) + ".");
        }
        return definition;
    }

    static ScenarioDefinition read(Path path) {
        if (path == null) throw new IllegalArgumentException("Scenario path is required.");
        try {
            Object parsed = MiniJson.parse(Files.readString(path), JSON_LIMITS);
            if (!(parsed instanceof Map<?,?> raw)) {
                throw new IllegalArgumentException(path + " must contain a JSON object.");
            }
            ScenarioDefinition definition = parseDefinition(copyMap(raw), path.toString());
            validate(definition);
            return definition;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read scenario " + path + ": " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid scenario " + path + ": " + ex.getMessage(), ex);
        }
    }

    static void validate(ScenarioDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("Scenario definition is required.");
        Map<String,ScenarioObjective> byId = new LinkedHashMap<>();
        int primaryCount = 0;
        for (ScenarioObjective objective : definition.objectives()) {
            if (byId.putIfAbsent(objective.id(), objective) != null) {
                throw new IllegalArgumentException("Scenario " + definition.id()
                        + " has duplicate objective ID: " + objective.id());
            }
            if (objective.kind() == ScenarioObjectiveKind.PRIMARY) primaryCount++;
            validateTrigger(definition, objective);
        }
        if (primaryCount == 0) {
            throw new IllegalArgumentException("Scenario " + definition.id() + " requires at least one primary objective.");
        }
        for (ScenarioObjective objective : definition.objectives()) {
            for (String prerequisite : objective.prerequisites()) {
                if (!byId.containsKey(prerequisite)) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " references unknown prerequisite " + prerequisite + ".");
                }
                if (objective.id().equals(prerequisite)) {
                    throw new IllegalArgumentException("Scenario objective " + objective.id() + " cannot require itself.");
                }
            }
            for (ScenarioAction action : objective.actions()) {
                validateAction(definition, objective, action, byId.keySet());
            }
        }
        validateAcyclic(definition, byId);
        validateStartingState(definition);
    }

    private static void validateTrigger(ScenarioDefinition definition, ScenarioObjective objective) {
        ScenarioTrigger trigger = objective.trigger();
        switch (trigger.type()) {
            case COMPLETE_RESEARCH -> {
                if (ResearchRules.topic(trigger.value()) == null) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " references unknown research topic " + trigger.value() + ".");
                }
            }
            case OWN_SHIP_TYPE -> {
                if (Rules.findShip(trigger.value()) == null) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " references unknown ship type " + trigger.value() + ".");
                }
            }
            case OWN_STATION_TYPE -> {
                if (Rules.findBase(trigger.value()) == null) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " references unknown station type " + trigger.value() + ".");
                }
            }
            default -> { }
        }
    }

    private static void validateAction(ScenarioDefinition definition, ScenarioObjective objective,
                                       ScenarioAction action, Set<String> objectiveIds) {
        switch (action.type()) {
            case ACTIVATE_OBJECTIVE -> {
                if (!objectiveIds.contains(action.target())) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " activates unknown objective " + action.target() + ".");
                }
            }
            case NOTICE -> {
                if (action.text().isBlank()) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " has a blank notice action.");
                }
            }
            case END_SCENARIO -> {
                if (!"victory".equals(action.outcome()) && !"failure".equals(action.outcome())) {
                    throw new IllegalArgumentException("Scenario " + definition.id() + " objective " + objective.id()
                            + " has invalid end-scenario outcome " + action.outcome() + ".");
                }
            }
        }
    }

    private static void validateAcyclic(ScenarioDefinition definition, Map<String,ScenarioObjective> byId) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : byId.keySet()) visit(definition.id(), id, byId, visiting, visited);
    }

    private static void visit(String scenarioId, String id, Map<String,ScenarioObjective> byId,
                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Scenario " + scenarioId + " objective graph contains a cycle at " + id + ".");
        }
        for (String prerequisite : byId.get(id).prerequisites()) {
            visit(scenarioId, prerequisite, byId, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static void validateStartingState(ScenarioDefinition definition) {
        ScenarioStartingState start = definition.startingState();
        for (ScenarioStartingShip ship : start.ships()) {
            if (Rules.findShip(ship.typeId()) == null) {
                throw new IllegalArgumentException("Scenario " + definition.id()
                        + " starting state references unknown ship type " + ship.typeId() + ".");
            }
        }
        for (ScenarioStartingStation station : start.stations()) {
            if (Rules.findBase(station.typeId()) == null) {
                throw new IllegalArgumentException("Scenario " + definition.id()
                        + " starting state references unknown station type " + station.typeId() + ".");
            }
            for (String material : station.inventory().keySet()) requireMaterial(definition.id(), material);
        }
        for (Map.Entry<String,List<String>> entry : start.completedResearch().entrySet()) {
            for (String topic : entry.getValue()) {
                if (ResearchRules.topic(topic) == null) {
                    throw new IllegalArgumentException("Scenario " + definition.id()
                            + " starting state references unknown research topic " + topic + ".");
                }
            }
        }
    }

    private static void requireMaterial(String scenarioId, String material) {
        try { Material.valueOf(material.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("Scenario " + scenarioId
                    + " starting inventory references unknown material " + material + ".");
        }
    }

    private static List<ScenarioDefinition> loadAll(Path indexPath) {
        if (!Files.isRegularFile(indexPath)) return List.of();
        try {
            Object parsed = MiniJson.parse(Files.readString(indexPath), JSON_LIMITS);
            if (!(parsed instanceof Map<?,?> root)) {
                throw new IllegalArgumentException(indexPath + " must contain a JSON object.");
            }
            Object rawFiles = root.get("scenarioFiles");
            if (!(rawFiles instanceof List<?> files) || files.isEmpty()) {
                throw new IllegalArgumentException(indexPath + " must contain a non-empty scenarioFiles array.");
            }
            Path directory = indexPath.toAbsolutePath().normalize().getParent();
            List<ScenarioDefinition> out = new ArrayList<>();
            for (Object item : files) {
                String raw = text(item);
                if (raw.isBlank()) throw new IllegalArgumentException("Scenario filename cannot be blank.");
                Path candidate = directory.resolve(raw).normalize();
                if (!candidate.startsWith(directory)) {
                    throw new IllegalArgumentException("Scenario path escapes config/scenarios: " + raw);
                }
                out.add(read(candidate));
            }
            return Collections.unmodifiableList(new ArrayList<>(index(out).values()));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load scenario index " + indexPath + ": " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not load scenario index " + indexPath + ": " + ex.getMessage(), ex);
        }
    }

    private static Map<String,ScenarioDefinition> index(List<ScenarioDefinition> definitions) {
        Map<String,ScenarioDefinition> out = new LinkedHashMap<>();
        for (ScenarioDefinition definition : definitions) {
            if (out.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate scenario ID: " + definition.id());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static ScenarioDefinition parseDefinition(Map<String,Object> row, String source) {
        String id = requiredId(row.get("id"), "scenario ID");
        String name = cleanText(row.get("name"), id);
        String description = cleanText(row.get("description"), name);
        ScenarioMultiplayerMode multiplayer = enumValue(ScenarioMultiplayerMode.class,
                row.get("multiplayer"), ScenarioMultiplayerMode.SOLO, "multiplayer mode", id);
        ScenarioStartingState startingState = parseStartingState(object(row.get("startingState")), id);
        List<ScenarioObjective> objectives = new ArrayList<>();
        for (Object item : array(row.get("objectives"))) objectives.add(parseObjective(item, id));
        if (objectives.isEmpty()) throw new IllegalArgumentException("Scenario " + id + " has no objectives.");
        return new ScenarioDefinition(
                id, name, description, multiplayer, startingState,
                stringList(row.get("enabledNpcFactions")),
                objectCopy(row.get("galaxy")), objectCopy(row.get("diplomacy")), objectCopy(row.get("events")),
                objectives,
                cleanText(row.get("completionText"), "Scenario complete."),
                cleanText(row.get("failureText"), "Scenario failed."), source);
    }

    private static ScenarioStartingState parseStartingState(Map<String,Object> row, String scenarioId) {
        String startingSystem = text(row.get("startingSystem"));
        List<ScenarioStartingShip> ships = new ArrayList<>();
        for (Object item : array(row.get("ships"))) {
            Map<String,Object> ship = requireObject(item, "starting ship", scenarioId);
            ships.add(new ScenarioStartingShip(
                    owner(ship.get("owner")), requiredId(ship.get("type"), "starting ship type"),
                    integer(ship.get("count"), 1, 1, 1000, "starting ship count", scenarioId),
                    number(ship.get("x"), 0, "starting ship x", scenarioId),
                    number(ship.get("y"), 0, "starting ship y", scenarioId),
                    number(ship.get("spacing"), 70, "starting ship spacing", scenarioId)));
        }
        List<ScenarioStartingStation> stations = new ArrayList<>();
        for (Object item : array(row.get("stations"))) {
            Map<String,Object> station = requireObject(item, "starting station", scenarioId);
            Map<String,Double> inventory = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : object(station.get("inventory")).entrySet()) {
                inventory.put(entry.getKey().toUpperCase(Locale.ROOT),
                        nonNegative(entry.getValue(), "station inventory " + entry.getKey(), scenarioId));
            }
            stations.add(new ScenarioStartingStation(
                    owner(station.get("owner")), requiredId(station.get("type"), "starting station type"),
                    number(station.get("x"), 0, "starting station x", scenarioId),
                    number(station.get("y"), 0, "starting station y", scenarioId), inventory));
        }
        Map<String,List<String>> completedResearch = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : object(row.get("completedResearch")).entrySet()) {
            completedResearch.put(owner(entry.getKey()), stringList(entry.getValue()));
        }
        return new ScenarioStartingState(startingSystem, ships, stations, completedResearch);
    }

    private static ScenarioObjective parseObjective(Object value, String scenarioId) {
        Map<String,Object> row = requireObject(value, "objective", scenarioId);
        String id = requiredId(row.get("id"), "objective ID");
        ScenarioObjectiveKind kind = enumValue(ScenarioObjectiveKind.class, row.get("kind"),
                ScenarioObjectiveKind.PRIMARY, "objective kind", id);
        Map<String,Object> triggerRow = requireObject(row.get("trigger"), "objective trigger", id);
        ScenarioTriggerType type = enumValue(ScenarioTriggerType.class, triggerRow.get("type"), null,
                "trigger type", id);
        ScenarioComparison comparison = enumValue(ScenarioComparison.class, triggerRow.get("comparison"),
                ScenarioComparison.AT_LEAST, "trigger comparison", id);
        int target = integer(triggerRow.get("target"), 1, 0, 1_000_000_000,
                "trigger target", id);
        ScenarioTrigger trigger = new ScenarioTrigger(type, text(triggerRow.get("value")), target, comparison);
        List<ScenarioAction> actions = new ArrayList<>();
        for (Object actionValue : array(row.get("actions"))) {
            Map<String,Object> action = requireObject(actionValue, "objective action", id);
            ScenarioActionType actionType = enumValue(ScenarioActionType.class, action.get("type"), null,
                    "action type", id);
            actions.add(new ScenarioAction(actionType, cleanIdLookup(text(action.get("target"))),
                    cleanText(action.get("text"), ""), text(action.get("outcome")).toLowerCase(Locale.ROOT)));
        }
        return new ScenarioObjective(id,
                cleanText(row.get("title"), id), cleanText(row.get("description"), ""), kind,
                stringIds(row.get("prerequisites")), trigger, actions, bool(row.get("hidden"), false));
    }

    private static String owner(Object value) {
        String clean = text(value).toUpperCase(Locale.ROOT);
        return clean.isBlank() ? "PLAYER" : clean;
    }

    private static Map<String,Object> requireObject(Object value, String label, String owner) {
        Map<String,Object> out = object(value);
        if (out.isEmpty()) throw new IllegalArgumentException("Scenario " + owner + " " + label + " must be an object.");
        return out;
    }

    private static Map<String,Object> copyMap(Map<?,?> raw) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static Map<String,Object> objectCopy(Object value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(object(value)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return copyMap(map);
        return Map.of();
    }

    private static List<?> array(Object value) { return value instanceof List<?> list ? list : List.of(); }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : array(value)) {
            String clean = text(item);
            if (!clean.isBlank()) out.add(clean);
        }
        return List.copyOf(out);
    }

    private static List<String> stringIds(Object value) {
        List<String> out = new ArrayList<>();
        for (String item : stringList(value)) out.add(requiredId(item, "objective prerequisite"));
        return List.copyOf(out);
    }

    private static String requiredId(Object value, String label) {
        String clean = cleanIdLookup(text(value));
        if (clean.isBlank() || !clean.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
        return clean;
    }

    private static String cleanIdLookup(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String cleanText(Object value, String fallback) {
        String clean = text(value).replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.isBlank() ? (fallback == null ? "" : fallback) : clean;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static int integer(Object value, int fallback, int min, int max, String label, String owner) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < min || number.intValue() > max) {
            throw new IllegalArgumentException("Scenario " + owner + " " + label + " must be an integer from " + min + " to " + max + ".");
        }
        return number.intValue();
    }

    private static double number(Object value, double fallback, String label, String owner) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Scenario " + owner + " " + label + " must be finite and numeric.");
        }
        return number.doubleValue();
    }

    private static double nonNegative(Object value, String label, String owner) {
        double out = number(value, 0, label, owner);
        if (out < 0) throw new IllegalArgumentException("Scenario " + owner + " " + label + " cannot be negative.");
        return out;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback,
                                                    String label, String owner) {
        String raw = text(value);
        if (raw.isBlank() && fallback != null) return fallback;
        try { return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("Scenario " + owner + " has unknown " + label + ": " + raw + ".");
        }
    }
}

enum ScenarioMultiplayerMode { SOLO, COOP, TEAMS, COMPETITIVE }
enum ScenarioObjectiveKind { PRIMARY, OPTIONAL, FAILURE }
enum ScenarioObjectiveStatus { LOCKED, ACTIVE, COMPLETED, FAILED, SKIPPED }
enum ScenarioComparison { AT_LEAST, AT_MOST, EQUALS }
enum ScenarioTriggerType {
    COMPLETE_RESEARCH,
    COMPLETE_RESEARCH_COUNT,
    OWN_SHIPS,
    OWN_COMBAT_SHIPS,
    OWN_STATIONS,
    OWN_SHIP_TYPE,
    OWN_STATION_TYPE,
    LIVE_ASSETS,
    FLEET_POWER,
    CONTROL_SYSTEMS,
    SURVIVE_SECONDS
}
enum ScenarioActionType { ACTIVATE_OBJECTIVE, NOTICE, END_SCENARIO }

record ScenarioTrigger(ScenarioTriggerType type, String value, int target, ScenarioComparison comparison) {
    ScenarioTrigger {
        if (type == null) throw new IllegalArgumentException("Scenario trigger type is required.");
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (target < 0) throw new IllegalArgumentException("Scenario trigger target cannot be negative.");
        comparison = comparison == null ? ScenarioComparison.AT_LEAST : comparison;
        if ((type == ScenarioTriggerType.COMPLETE_RESEARCH
                || type == ScenarioTriggerType.OWN_SHIP_TYPE
                || type == ScenarioTriggerType.OWN_STATION_TYPE) && value.isBlank()) {
            throw new IllegalArgumentException("Scenario trigger " + type + " requires a value.");
        }
    }
}

record ScenarioAction(ScenarioActionType type, String target, String text, String outcome) {
    ScenarioAction {
        if (type == null) throw new IllegalArgumentException("Scenario action type is required.");
        target = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        text = text == null ? "" : text.trim();
        outcome = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
    }
}

record ScenarioObjective(String id, String title, String description, ScenarioObjectiveKind kind,
                         List<String> prerequisites, ScenarioTrigger trigger, List<ScenarioAction> actions,
                         boolean hidden) {
    ScenarioObjective {
        id = id == null ? "" : id;
        title = title == null ? id : title;
        description = description == null ? "" : description;
        kind = kind == null ? ScenarioObjectiveKind.PRIMARY : kind;
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        if (trigger == null) throw new IllegalArgumentException("Scenario objective trigger is required: " + id);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}

record ScenarioStartingShip(String owner, String typeId, int count, double x, double y, double spacing) {
    ScenarioStartingShip {
        owner = owner == null || owner.isBlank() ? "PLAYER" : owner;
        typeId = typeId == null ? "" : typeId;
        count = Math.max(1, count);
        spacing = Double.isFinite(spacing) ? Math.max(0, spacing) : 0;
    }
}

record ScenarioStartingStation(String owner, String typeId, double x, double y, Map<String,Double> inventory) {
    ScenarioStartingStation {
        owner = owner == null || owner.isBlank() ? "PLAYER" : owner;
        typeId = typeId == null ? "" : typeId;
        inventory = inventory == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inventory));
    }
}

record ScenarioStartingState(String startingSystem, List<ScenarioStartingShip> ships,
                             List<ScenarioStartingStation> stations, Map<String,List<String>> completedResearch) {
    ScenarioStartingState {
        startingSystem = startingSystem == null ? "" : startingSystem.trim();
        ships = ships == null ? List.of() : List.copyOf(ships);
        stations = stations == null ? List.of() : List.copyOf(stations);
        Map<String,List<String>> research = new LinkedHashMap<>();
        if (completedResearch != null) {
            for (Map.Entry<String,List<String>> entry : completedResearch.entrySet()) {
                research.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        completedResearch = Collections.unmodifiableMap(research);
    }

    static ScenarioStartingState empty() { return new ScenarioStartingState("", List.of(), List.of(), Map.of()); }
}

record ScenarioDefinition(String id, String name, String description, ScenarioMultiplayerMode multiplayer,
                          ScenarioStartingState startingState, List<String> enabledNpcFactions,
                          Map<String,Object> galaxySettings, Map<String,Object> diplomacySettings,
                          Map<String,Object> eventSettings, List<ScenarioObjective> objectives,
                          String completionText, String failureText, String source) {
    ScenarioDefinition {
        id = id == null ? "" : id;
        name = name == null ? id : name;
        description = description == null ? "" : description;
        multiplayer = multiplayer == null ? ScenarioMultiplayerMode.SOLO : multiplayer;
        startingState = startingState == null ? ScenarioStartingState.empty() : startingState;
        enabledNpcFactions = enabledNpcFactions == null ? List.of() : List.copyOf(enabledNpcFactions);
        galaxySettings = galaxySettings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(galaxySettings));
        diplomacySettings = diplomacySettings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(diplomacySettings));
        eventSettings = eventSettings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(eventSettings));
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        completionText = completionText == null ? "Scenario complete." : completionText;
        failureText = failureText == null ? "Scenario failed." : failureText;
        source = source == null ? "" : source;
    }

    ScenarioObjective objective(String objectiveId) {
        if (objectiveId == null) return null;
        for (ScenarioObjective objective : objectives) if (objective.id().equals(objectiveId)) return objective;
        return null;
    }
}
