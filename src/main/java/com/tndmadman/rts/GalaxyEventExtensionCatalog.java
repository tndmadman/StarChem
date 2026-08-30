package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Version-3, JSON-first extension catalog for dynamic galaxy events.
 *
 * Gameplay values belong in config/events.json. Java defines only generic
 * mechanics and strict schema/enum validation; storm strengths, objective
 * radii/timers, spawned content, rewards, chains and pocket-system settings
 * are deliberately not hard-coded here.
 */
final class GalaxyEventExtensionCatalog {
    static final Path CONFIG_PATH = Path.of("config/events.json");
    static final int SCHEMA_VERSION = 3;

    private final AdvancedEventDirectorDefinition director;
    private final List<AdvancedEventDefinition> definitions;
    private final Map<String, AdvancedEventDefinition> byId;

    private GalaxyEventExtensionCatalog(AdvancedEventDirectorDefinition director,
                                        List<AdvancedEventDefinition> definitions) {
        this.director = director;
        this.definitions = List.copyOf(definitions);
        Map<String, AdvancedEventDefinition> index = new LinkedHashMap<>();
        for (AdvancedEventDefinition definition : definitions) {
            validateDefinition(definition);
            if (index.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate advanced galaxy event id: " + definition.id());
            }
        }
        for (AdvancedEventDefinition definition : definitions) validateReferences(definition, index);
        this.byId = Map.copyOf(index);
    }

    static GalaxyEventExtensionCatalog load() { return load(CONFIG_PATH); }

    static GalaxyEventExtensionCatalog loadForValidation(Path path) {
        if (path == null) throw new IllegalStateException("Galaxy event config path is required.");
        return load(path);
    }

    private static GalaxyEventExtensionCatalog load(Path path) {
        try {
            Map<String,Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(path)));
            int version = ServerSaveStore.intValue(root, "version", -1);
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("config/events.json schema version must be " + SCHEMA_VERSION
                        + " (found " + version + ").");
            }
            Map<String,Object> extensions = ServerSaveStore.object(root.get("extensions"));
            AdvancedEventDirectorDefinition director = parseDirector(ServerSaveStore.object(extensions.get("director")));
            List<AdvancedEventDefinition> definitions = new ArrayList<>();
            for (Object value : ServerSaveStore.list(extensions.get("definitions"))) {
                AdvancedEventDefinition definition = parseDefinition(ServerSaveStore.object(value));
                if (definition != null) definitions.add(definition);
            }
            if (director.enabled() && definitions.isEmpty()) {
                throw new IllegalStateException("Enabled config/events.json extensions require at least one definition.");
            }
            return new GalaxyEventExtensionCatalog(director, definitions);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Could not load advanced galaxy event config: " + ex.getMessage(), ex);
        }
    }

    private static AdvancedEventDirectorDefinition parseDirector(Map<String,Object> row) {
        boolean enabled = ServerSaveStore.boolValue(row, "enabled", true);
        double initialDelay = number(row, "initialDelaySeconds", 45, 0, 86_400);
        double evaluation = number(row, "evaluationSeconds", 30, 1, 86_400);
        double chance = number(row, "spawnChance", 0.25, 0, 1);
        int maxGalaxy = integer(row, "maxActiveGalaxy", 6, 1, 1024);
        int maxSystem = integer(row, "maxActivePerSystem", 2, 1, maxGalaxy);
        int historyLimit = integer(row, "historyLimit", 200, 1, 10_000);
        return new AdvancedEventDirectorDefinition(enabled, initialDelay, evaluation, chance,
                maxGalaxy, maxSystem, historyLimit);
    }

    private static AdvancedEventDefinition parseDefinition(Map<String,Object> row) {
        String id = text(row, "id", "");
        if (id.isBlank()) return null;
        String name = text(row, "name", id);
        GalaxyEventKind wireKind = enumValue(GalaxyEventKind.class, text(row, "wireKind", "ENVIRONMENTAL"),
                "wireKind for " + id);
        boolean enabled = ServerSaveStore.boolValue(row, "enabled", true);
        double weight = number(row, "weight", 1, 0, 1_000_000);
        int maxActive = integer(row, "maxActiveInstances", 2, 1, 1024);
        boolean safeForHome = ServerSaveStore.boolValue(row, "safeForHome", true);
        Set<String> roles = lowercaseSet(row.get("eligibleRoles"));
        double minimumAge = number(row, "minimumAgeSeconds", 0, 0, 86_400_000);
        double cooldown = number(row, "cooldownSeconds", 180, 0, 86_400_000);
        double minDuration = number(row, "minDurationSeconds", 120, 1, 86_400_000);
        double maxDuration = number(row, "maxDurationSeconds", 240, minDuration, 86_400_000);
        AdvancedEventDiscovery discovery = parseDiscovery(ServerSaveStore.object(row.get("discovery")), id);
        AdvancedEventPlacement placement = parsePlacement(ServerSaveStore.object(row.get("placement")));
        SystemModifiers modifiers = parseModifiers(ServerSaveStore.object(row.get("modifiers")), id);
        AdvancedEventScope scope = parseScope(ServerSaveStore.object(row.get("scope")), id);
        AdvancedEventSpawn spawn = parseSpawn(ServerSaveStore.object(row.get("spawn")), id);
        List<AdvancedEventStage> stages = new ArrayList<>();
        for (Object value : ServerSaveStore.list(row.get("stages"))) {
            stages.add(parseStage(ServerSaveStore.object(value), id));
        }
        List<AdvancedEventReward> rewards = parseRewards(row.get("rewards"), id);
        AdvancedEventChain chain = parseChain(ServerSaveStore.object(row.get("chain")));
        return new AdvancedEventDefinition(id, name, wireKind, enabled, weight, maxActive, safeForHome,
                roles, minimumAge, cooldown, minDuration, maxDuration, discovery, placement, modifiers,
                scope, spawn, List.copyOf(stages), rewards, chain);
    }

    private static AdvancedEventDiscovery parseDiscovery(Map<String,Object> row, String eventId) {
        AdvancedDiscoveryMode mode = enumValue(AdvancedDiscoveryMode.class,
                text(row, "mode", "SENSOR"), "discovery mode for " + eventId);
        double radius = number(row, "radius", 900, 25, 100_000);
        double scanSeconds = number(row, "scanSeconds", 8, 0.1, 86_400);
        return new AdvancedEventDiscovery(mode, radius, scanSeconds);
    }

    private static AdvancedEventPlacement parsePlacement(Map<String,Object> row) {
        return new AdvancedEventPlacement(
                number(row, "minDistanceFromPlayerAssets", 500, 0, 100_000),
                number(row, "minDistanceFromWormholes", 350, 0, 100_000),
                integer(row, "attempts", 24, 1, 4096));
    }

    private static SystemModifiers parseModifiers(Map<String,Object> row, String eventId) {
        double mining = positive(row, "miningYield", 1, eventId);
        double respawn = positive(row, "resourceRespawn", 1, eventId);
        double sensor = positive(row, "sensorRange", 1, eventId);
        double shield = positive(row, "shieldRegen", 1, eventId);
        double movement = positive(row, "movementSpeed", 1, eventId);
        double weapon = positive(row, "weaponRange", 1, eventId);
        double damage = number(row, "environmentalDamagePerSecond", 0, 0, 1_000_000);
        return new SystemModifiers(mining, respawn, sensor, shield, movement, weapon, damage);
    }

    private static AdvancedEventScope parseScope(Map<String,Object> row, String eventId) {
        AdvancedScopeMode mode = enumValue(AdvancedScopeMode.class, text(row, "mode", "LOCAL"),
                "scope mode for " + eventId);
        int count = integer(row, "systemCount", 1, 1, 96);
        double moveEvery = number(row, "moveEverySeconds", 60, 1, 86_400_000);
        return new AdvancedEventScope(mode, count, moveEvery);
    }

    private static AdvancedEventSpawn parseSpawn(Map<String,Object> row, String eventId) {
        List<AdvancedResourceSpawn> resources = new ArrayList<>();
        for (Object value : ServerSaveStore.list(row.get("resources"))) {
            Map<String,Object> item = ServerSaveStore.object(value);
            resources.add(new AdvancedResourceSpawn(
                    material(text(item, "material", "RARE_EARTHS"), eventId, "spawn resource"),
                    integer(item, "count", 1, 1, 256),
                    number(item, "amount", 250, 0.05, 1_000_000_000),
                    number(item, "spreadRadius", 120, 0, 100_000),
                    number(item, "harvestRate", 12, 0.01, 1_000_000)));
        }
        List<AdvancedItemSpawn> items = new ArrayList<>();
        for (Object value : ServerSaveStore.list(row.get("items"))) {
            Map<String,Object> item = ServerSaveStore.object(value);
            items.add(new AdvancedItemSpawn(
                    material(text(item, "material", "SCRAP_METAL"), eventId, "spawn item"),
                    integer(item, "count", 1, 1, 256),
                    number(item, "amount", 25, 0.05, 1_000_000_000),
                    number(item, "spreadRadius", 120, 0, 100_000)));
        }
        List<AdvancedUnitSpawn> units = new ArrayList<>();
        for (Object value : ServerSaveStore.list(row.get("units"))) {
            Map<String,Object> item = ServerSaveStore.object(value);
            String ownerId = text(item, "ownerId", "EVENT_HOSTILE");
            String ownerName = text(item, "ownerName", ownerId);
            int color = rgb(text(item, "ownerColor", "#FF6666"), eventId);
            String shipTypeId = text(item, "shipTypeId", "frigate");
            AdvancedUnitRole role = enumValue(AdvancedUnitRole.class, text(item, "role", "HOSTILE"),
                    "unit role for " + eventId);
            units.add(new AdvancedUnitSpawn(ownerId, ownerName, color, shipTypeId,
                    integer(item, "count", 1, 1, 256),
                    number(item, "spreadRadius", 150, 0, 100_000), role));
        }
        Map<String,Object> pocket = ServerSaveStore.object(row.get("pocketSystem"));
        AdvancedPocketSystem pocketSystem = pocket.isEmpty() ? AdvancedPocketSystem.NONE : new AdvancedPocketSystem(
                true,
                text(pocket, "templateId", StarSystems.DEFAULT_SYSTEM_ID),
                text(pocket, "idSuffix", "pocket"),
                number(pocket, "gateOffsetX", 0, -100_000, 100_000),
                number(pocket, "gateOffsetY", 0, -100_000, 100_000),
                number(pocket, "exitX", 0.5, 0, 1),
                number(pocket, "exitY", 0.5, 0, 1));
        return new AdvancedEventSpawn(List.copyOf(resources), List.copyOf(items), List.copyOf(units), pocketSystem);
    }

    private static AdvancedEventStage parseStage(Map<String,Object> row, String eventId) {
        String id = text(row, "id", "stage");
        String name = text(row, "name", id);
        double timeout = number(row, "timeoutSeconds", 0, 0, 86_400_000);
        AdvancedEventObjective objective = parseObjective(ServerSaveStore.object(row.get("objective")), eventId, id);
        AdvancedEventSpawn spawn = parseSpawn(ServerSaveStore.object(row.get("spawn")), eventId + "/" + id);
        return new AdvancedEventStage(id, name, timeout, objective, spawn, parseRewards(row.get("rewards"), eventId));
    }

    private static AdvancedEventObjective parseObjective(Map<String,Object> row, String eventId, String stageId) {
        AdvancedObjectiveType type = enumValue(AdvancedObjectiveType.class, text(row, "type", "SURVIVE"),
                "objective type for " + eventId + "/" + stageId);
        double radius = number(row, "radius", 450, 1, 100_000);
        double seconds = number(row, "seconds", 30, 0, 86_400_000);
        Material material = optionalMaterial(text(row, "material", ""), eventId, "objective material");
        double amount = number(row, "amount", 0, 0, 1_000_000_000);
        int targetCount = integer(row, "targetCount", 0, 0, 100_000);
        Material repairMaterial = optionalMaterial(text(row, "repairMaterial", ""), eventId, "repair material");
        double repairAmount = number(row, "repairAmount", 0, 0, 1_000_000_000);
        String researchId = text(row, "researchId", "");
        boolean contested = ServerSaveStore.boolValue(row, "contested", false);
        boolean npcCompetition = ServerSaveStore.boolValue(row, "npcCompetition", false);
        double npcCompetitionSeconds = number(row, "npcCompetitionSeconds", 0, 0, 86_400_000);
        AdvancedDestination destination = enumValue(AdvancedDestination.class,
                text(row, "destination", "EVENT_POINT"), "destination for " + eventId + "/" + stageId);
        List<AdvancedChoice> choices = new ArrayList<>();
        for (Object value : ServerSaveStore.list(row.get("choices"))) {
            Map<String,Object> choice = ServerSaveStore.object(value);
            choices.add(new AdvancedChoice(
                    text(choice, "id", "choice"), text(choice, "label", "Choice"),
                    number(choice, "offsetX", 0, -100_000, 100_000),
                    number(choice, "offsetY", 0, -100_000, 100_000),
                    number(choice, "radius", radius, 1, 100_000),
                    text(choice, "nextStageId", ""),
                    parseRewards(choice.get("rewards"), eventId)));
        }
        return new AdvancedEventObjective(type, radius, seconds, material, amount, targetCount,
                repairMaterial, repairAmount, researchId, contested, npcCompetition,
                npcCompetitionSeconds, destination, List.copyOf(choices));
    }

    private static List<AdvancedEventReward> parseRewards(Object value, String eventId) {
        List<AdvancedEventReward> rewards = new ArrayList<>();
        for (Object raw : ServerSaveStore.list(value)) {
            Map<String,Object> row = ServerSaveStore.object(raw);
            AdvancedRewardType type = enumValue(AdvancedRewardType.class, text(row, "type", "MATERIAL"),
                    "reward type for " + eventId);
            Material material = optionalMaterial(text(row, "material", ""), eventId, "reward material");
            double amount = number(row, "amount", 0, 0, 1_000_000_000);
            String researchId = text(row, "researchId", "");
            String factionId = text(row, "factionId", "");
            DiplomacySystem.Relationship relationship = row.containsKey("relationship")
                    ? enumValue(DiplomacySystem.Relationship.class, text(row, "relationship", "NEUTRAL"),
                            "reward relationship for " + eventId)
                    : DiplomacySystem.Relationship.NEUTRAL;
            String message = text(row, "message", "");
            rewards.add(new AdvancedEventReward(type, material, amount, researchId,
                    factionId, relationship, message));
        }
        return List.copyOf(rewards);
    }

    private static AdvancedEventChain parseChain(Map<String,Object> row) {
        if (row.isEmpty()) return AdvancedEventChain.NONE;
        return new AdvancedEventChain(text(row, "onComplete", ""), text(row, "onFail", ""),
                number(row, "chance", 1, 0, 1), number(row, "delaySeconds", 0, 0, 86_400_000));
    }

    private static void validateDefinition(AdvancedEventDefinition definition) {
        if (definition.id().isBlank()) throw new IllegalStateException("Advanced event id is required.");
        Set<String> validRoles = new LinkedHashSet<>();
        for (StarSystemDefinition system : StarSystems.options()) {
            if (system != null && system.role() != null) validRoles.add(system.role().trim().toLowerCase(Locale.ROOT));
        }
        for (String role : definition.eligibleRoles()) {
            if (!validRoles.contains(role)) {
                throw new IllegalStateException("Advanced event " + definition.id() + " references unknown system role " + role + ".");
            }
        }
        Set<String> stageIds = new LinkedHashSet<>();
        for (AdvancedEventStage stage : definition.stages()) {
            if (stage.id().isBlank() || !stageIds.add(stage.id())) {
                throw new IllegalStateException("Advanced event " + definition.id() + " has blank or duplicate stage ids.");
            }
            validateObjective(definition.id(), stage.objective());
        }
        if (definition.spawn().pocketSystem().enabled()) validatePocket(definition.id(), definition.spawn().pocketSystem());
        for (AdvancedEventStage stage : definition.stages()) {
            if (stage.spawn().pocketSystem().enabled()) validatePocket(definition.id(), stage.spawn().pocketSystem());
        }
    }

    private static void validateObjective(String eventId, AdvancedEventObjective objective) {
        if (objective.type() == AdvancedObjectiveType.DELIVER && (objective.material() == null || objective.amount() <= 0)) {
            throw new IllegalStateException("Advanced event " + eventId + " DELIVER requires material and amount.");
        }
        if (objective.type() == AdvancedObjectiveType.REPAIR
                && (objective.repairMaterial() == null || objective.repairAmount() <= 0)) {
            throw new IllegalStateException("Advanced event " + eventId + " REPAIR requires repairMaterial and repairAmount.");
        }
        if (objective.type() == AdvancedObjectiveType.CHOICE && objective.choices().isEmpty()) {
            throw new IllegalStateException("Advanced event " + eventId + " CHOICE requires choices.");
        }
    }

    private static void validatePocket(String eventId, AdvancedPocketSystem pocket) {
        boolean found = false;
        for (StarSystemDefinition option : StarSystems.options()) {
            if (option != null && option.id().equals(pocket.templateId())) { found = true; break; }
        }
        if (!found) throw new IllegalStateException("Advanced event " + eventId
                + " references unknown pocket-system template " + pocket.templateId() + ".");
    }

    private static void validateReferences(AdvancedEventDefinition definition,
                                           Map<String, AdvancedEventDefinition> index) {
        AdvancedEventChain chain = definition.chain();
        if (!chain.onComplete().isBlank() && !index.containsKey(chain.onComplete())) {
            throw new IllegalStateException("Advanced event " + definition.id()
                    + " chains to unknown event " + chain.onComplete() + ".");
        }
        if (!chain.onFail().isBlank() && !index.containsKey(chain.onFail())) {
            throw new IllegalStateException("Advanced event " + definition.id()
                    + " chains to unknown event " + chain.onFail() + ".");
        }
        for (AdvancedEventReward reward : allRewards(definition)) {
            if (reward.type() == AdvancedRewardType.MATERIAL && (reward.material() == null || reward.amount() <= 0)) {
                throw new IllegalStateException("Advanced event " + definition.id() + " has invalid material reward.");
            }
            if (reward.type() == AdvancedRewardType.RESEARCH && reward.researchId().isBlank()) {
                throw new IllegalStateException("Advanced event " + definition.id() + " has blank research reward.");
            }
            if (reward.type() == AdvancedRewardType.RELATIONSHIP && !knownNpcFaction(reward.factionId())) {
                throw new IllegalStateException("Advanced event " + definition.id()
                        + " has unknown relationship faction " + reward.factionId() + ".");
            }
        }
    }

    private static List<AdvancedEventReward> allRewards(AdvancedEventDefinition definition) {
        List<AdvancedEventReward> out = new ArrayList<>(definition.rewards());
        for (AdvancedEventStage stage : definition.stages()) {
            out.addAll(stage.rewards());
            for (AdvancedChoice choice : stage.objective().choices()) out.addAll(choice.rewards());
        }
        return out;
    }

    private static boolean knownNpcFaction(String id) {
        if (id == null || id.isBlank()) return false;
        for (NpcFaction faction : NpcRules.factions()) if (faction != null && id.equals(faction.id())) return true;
        return false;
    }

    private static Material material(String value, String eventId, String field) {
        Material parsed = optionalMaterial(value, eventId, field);
        if (parsed == null) throw new IllegalStateException("Advanced event " + eventId + " requires " + field + ".");
        return parsed;
    }

    private static Material optionalMaterial(String value, String eventId, String field) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) return null;
        try { return Material.valueOf(clean.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Advanced event " + eventId + " has invalid " + field + ": " + value + ".");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try { return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalStateException("Invalid " + label + ": " + value + "."); }
    }

    private static String text(Map<String,Object> row, String key, String fallback) {
        return ServerSaveStore.string(row, key, fallback).trim();
    }

    private static double positive(Map<String,Object> row, String key, double fallback, String eventId) {
        double value = ServerSaveStore.doubleValue(row, key, fallback);
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalStateException("Advanced event " + eventId + " has invalid modifier " + key + ".");
        }
        return value;
    }

    private static double number(Map<String,Object> row, String key, double fallback, double min, double max) {
        double value = ServerSaveStore.doubleValue(row, key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalStateException("Advanced event numeric field " + key + " is out of range.");
        }
        return value;
    }

    private static int integer(Map<String,Object> row, String key, int fallback, int min, int max) {
        int value = ServerSaveStore.intValue(row, key, fallback);
        if (value < min || value > max) {
            throw new IllegalStateException("Advanced event integer field " + key + " is out of range.");
        }
        return value;
    }

    private static int rgb(String value, String eventId) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("#")) text = text.substring(1);
        try {
            int parsed = Integer.parseInt(text, 16);
            if (text.length() != 6) throw new NumberFormatException();
            return parsed & 0xFFFFFF;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Advanced event " + eventId + " has invalid ownerColor " + value + ".");
        }
    }

    private static Set<String> lowercaseSet(Object value) {
        Set<String> out = new LinkedHashSet<>();
        for (Object item : ServerSaveStore.list(value)) {
            String text = ServerSaveStore.asString(item, "").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank()) out.add(text);
        }
        return Set.copyOf(out);
    }

    AdvancedEventDirectorDefinition director() { return director; }
    List<AdvancedEventDefinition> definitions() { return definitions; }
    AdvancedEventDefinition byId(String id) { return byId.get(id); }
}

record AdvancedEventDirectorDefinition(boolean enabled, double initialDelaySeconds,
                                       double evaluationSeconds, double spawnChance,
                                       int maxActiveGalaxy, int maxActivePerSystem,
                                       int historyLimit) { }

enum AdvancedDiscoveryMode { SENSOR, PROXIMITY, SCAN, SYSTEM_ENTRY }
enum AdvancedScopeMode { LOCAL, GALAXY_MOVING }
enum AdvancedObjectiveType { SCAN, HOLD_AREA, DELIVER, ESCORT, REPAIR, DESTROY, SALVAGE, MINE, SURVIVE, REACH_LOCATION, CHOICE }
enum AdvancedRewardType { MATERIAL, RESEARCH, NOTICE, RELATIONSHIP }
enum AdvancedUnitRole { HOSTILE, CIVILIAN, COMPETITOR, BOSS }
enum AdvancedDestination { EVENT_POINT, NEAREST_BASE, WORMHOLE }

record AdvancedEventDiscovery(AdvancedDiscoveryMode mode, double radius, double scanSeconds) { }
record AdvancedEventPlacement(double minDistanceFromPlayerAssets, double minDistanceFromWormholes,
                              int attempts) { }
record AdvancedEventScope(AdvancedScopeMode mode, int systemCount, double moveEverySeconds) { }
record AdvancedResourceSpawn(Material material, int count, double amount, double spreadRadius,
                             double harvestRate) { }
record AdvancedItemSpawn(Material material, int count, double amount, double spreadRadius) { }
record AdvancedUnitSpawn(String ownerId, String ownerName, int ownerColorRgb, String shipTypeId,
                         int count, double spreadRadius, AdvancedUnitRole role) { }
record AdvancedPocketSystem(boolean enabled, String templateId, String idSuffix,
                            double gateOffsetX, double gateOffsetY, double exitX, double exitY) {
    static final AdvancedPocketSystem NONE = new AdvancedPocketSystem(false, "", "", 0, 0, 0.5, 0.5);
}
record AdvancedEventSpawn(List<AdvancedResourceSpawn> resources, List<AdvancedItemSpawn> items,
                          List<AdvancedUnitSpawn> units, AdvancedPocketSystem pocketSystem) {
    AdvancedEventSpawn {
        resources = resources == null ? List.of() : List.copyOf(resources);
        items = items == null ? List.of() : List.copyOf(items);
        units = units == null ? List.of() : List.copyOf(units);
        pocketSystem = pocketSystem == null ? AdvancedPocketSystem.NONE : pocketSystem;
    }
}
record AdvancedChoice(String id, String label, double offsetX, double offsetY, double radius,
                      String nextStageId, List<AdvancedEventReward> rewards) {
    AdvancedChoice { rewards = rewards == null ? List.of() : List.copyOf(rewards); }
}
record AdvancedEventObjective(AdvancedObjectiveType type, double radius, double seconds,
                              Material material, double amount, int targetCount,
                              Material repairMaterial, double repairAmount, String researchId,
                              boolean contested, boolean npcCompetition, double npcCompetitionSeconds,
                              AdvancedDestination destination, List<AdvancedChoice> choices) {
    AdvancedEventObjective { choices = choices == null ? List.of() : List.copyOf(choices); }
}
record AdvancedEventStage(String id, String name, double timeoutSeconds,
                          AdvancedEventObjective objective, AdvancedEventSpawn spawn,
                          List<AdvancedEventReward> rewards) {
    AdvancedEventStage { rewards = rewards == null ? List.of() : List.copyOf(rewards); }
}
record AdvancedEventReward(AdvancedRewardType type, Material material, double amount,
                           String researchId, String factionId,
                           DiplomacySystem.Relationship relationship, String message) { }
record AdvancedEventChain(String onComplete, String onFail, double chance, double delaySeconds) {
    static final AdvancedEventChain NONE = new AdvancedEventChain("", "", 0, 0);
}
record AdvancedEventDefinition(String id, String name, GalaxyEventKind wireKind, boolean enabled,
                               double weight, int maxActiveInstances, boolean safeForHome,
                               Set<String> eligibleRoles, double minimumAgeSeconds,
                               double cooldownSeconds, double minDurationSeconds,
                               double maxDurationSeconds, AdvancedEventDiscovery discovery,
                               AdvancedEventPlacement placement, SystemModifiers modifiers,
                               AdvancedEventScope scope, AdvancedEventSpawn spawn,
                               List<AdvancedEventStage> stages, List<AdvancedEventReward> rewards,
                               AdvancedEventChain chain) {
    AdvancedEventDefinition {
        eligibleRoles = eligibleRoles == null ? Set.of() : Set.copyOf(eligibleRoles);
        stages = stages == null ? List.of() : List.copyOf(stages);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        spawn = spawn == null ? new AdvancedEventSpawn(List.of(), List.of(), List.of(), AdvancedPocketSystem.NONE) : spawn;
        chain = chain == null ? AdvancedEventChain.NONE : chain;
        scope = scope == null ? new AdvancedEventScope(AdvancedScopeMode.LOCAL, 1, 60) : scope;
        modifiers = modifiers == null ? SystemModifiers.STANDARD : modifiers;
    }
}
