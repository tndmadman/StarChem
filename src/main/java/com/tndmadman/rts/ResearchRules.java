package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class ResearchRules {
    private static final Map<String, ResearchTopic> TOPICS = load();

    private ResearchRules() { }

    static ResearchTopic topic(String id) {
        return TOPICS.get(id);
    }

    static List<ResearchTopic> all() {
        return List.copyOf(TOPICS.values());
    }

    static List<ResearchTopic> forStation(String stationTypeId) {
        List<ResearchTopic> out = new ArrayList<>();
        for (ResearchTopic topic : TOPICS.values()) if (topic.canResearchAt(stationTypeId)) out.add(topic);
        return List.copyOf(out);
    }

    static List<ResearchTopic> topicsUnlocking(ResearchUnlockKind kind, String targetId) {
        if (kind == null || targetId == null || targetId.isBlank()) return List.of();
        List<ResearchTopic> out = new ArrayList<>();
        for (ResearchTopic topic : TOPICS.values()) {
            if (topic.unlocks.contains(kind, targetId)) out.add(topic);
        }
        return List.copyOf(out);
    }

    static boolean shipUnlocked(World world, String playerId, String shipTypeId) {
        return ResearchPolicy.unlocked(world, playerId, ResearchUnlockKind.SHIP, shipTypeId);
    }

    static ResearchTopic firstTopicUnlockingShip(String shipTypeId) {
        List<ResearchTopic> topics = topicsUnlocking(ResearchUnlockKind.SHIP, shipTypeId);
        return topics.isEmpty() ? null : topics.get(0);
    }

    static String missingPrerequisite(World world, String playerId, ResearchTopic topic) {
        if (world == null || playerId == null || playerId.isBlank() || topic == null) return "unknown prerequisite";
        for (String required : topic.requires) {
            if (!world.hasResearch(playerId, required)) {
                ResearchTopic missing = topic(required);
                return missing == null ? required : missing.name;
            }
        }
        return "";
    }

    private static Map<String, ResearchTopic> load() {
        Map<String, ResearchTopic> out = new LinkedHashMap<>();
        try {
            Object researchFile = null;
            if (Files.exists(Path.of("config/starchem.json"))) {
                Map<String,Object> root = readObject(Path.of("config/starchem.json"));
                researchFile = object(root.get("files")).get("research");
            }
            if (researchFile != null) parseResearchFiles(researchFile, out);
            else if (Files.exists(Path.of("config/research.json"))) parseTopics(readObject(Path.of("config/research.json")), out);
            validate(out);
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load research rules: " + ex.getMessage());
        }
        if (out.isEmpty()) out.putAll(defaultTopics());
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, ResearchTopic> defaultTopics() {
        Map<String, ResearchTopic> out = new LinkedHashMap<>();
        out.put("advanced_industry", new ResearchTopic(
                "advanced_industry",
                "Advanced Industry",
                "Unlocks specialist mining and heavy hauling ships.",
                List.of("laboratory"),
                List.of(),
                35,
                List.of(new Cost(Material.FUEL, 25), new Cost(Material.COPPER, 120), new Cost(Material.SILICATES, 100)),
                new ResearchUnlocks(List.of("deep_miner", "gas_harvester", "freighter", "salvager"))));
        out.put("combat_doctrine", new ResearchTopic(
                "combat_doctrine",
                "Combat Doctrine",
                "Unlocks the first dedicated combat hulls.",
                List.of("laboratory"),
                List.of("advanced_industry"),
                50,
                List.of(new Cost(Material.FUEL, 35), new Cost(Material.IRON, 180), new Cost(Material.COPPER, 120), new Cost(Material.CIRCUIT_FRAGMENTS, 20)),
                new ResearchUnlocks(List.of("frigate", "destroyer", "cruiser"))));
        return out;
    }

    private static void parseResearchFiles(Object fileValue, Map<String, ResearchTopic> out) throws IOException {
        if (fileValue instanceof List<?> list) {
            for (Object item : list) parseTopics(readObject(Path.of(String.valueOf(item))), out);
        } else {
            parseTopics(readObject(Path.of(String.valueOf(fileValue))), out);
        }
    }

    private static void parseTopics(Map<String,Object> doc, Map<String, ResearchTopic> out) {
        Map<String,Object> source = object(doc.getOrDefault("researchTopics", doc));
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> r = object(e.getValue());
            if (r.isEmpty()) continue;
            if (out.containsKey(e.getKey())) throw new RuleConfigurationException("Duplicate research topic ID: " + e.getKey());
            Map<String,Object> unlocks = object(r.get("unlocks"));
            out.put(e.getKey(), new ResearchTopic(
                    e.getKey(),
                    string(r, "displayName", e.getKey()),
                    string(r, "description", ""),
                    stringList(r.getOrDefault("stationTypes", r.get("stations"))),
                    stringList(r.get("requires")),
                    number(r, "timeSeconds", 30),
                    costs(r.getOrDefault("requiredResources", r.get("cost"))),
                    ResearchUnlocks.parse(unlocks),
                    string(r, "branch", ""),
                    string(r, "doctrineGroup", "")));
        }
    }

    private static void validate(Map<String, ResearchTopic> topics) {
        if (topics.isEmpty()) return;
        for (ResearchTopic topic : topics.values()) {
            if (topic.id.isBlank()) throw new RuleConfigurationException("Research topic ID cannot be blank.");
            if (topic.stationTypes.isEmpty()) throw new RuleConfigurationException("Research topic " + topic.id + " has no research stationTypes.");
            for (String stationId : topic.stationTypes) {
                if (!Rules.BASES.containsKey(stationId)) {
                    throw new RuleConfigurationException("Research topic " + topic.id + " references unknown station type: " + stationId);
                }
            }
            for (String required : topic.requires) {
                if (!topics.containsKey(required)) {
                    throw new RuleConfigurationException("Research topic " + topic.id + " requires unknown topic: " + required);
                }
                if (required.equals(topic.id)) {
                    throw new RuleConfigurationException("Research topic " + topic.id + " cannot require itself.");
                }
            }
            validateTargets(topic);
        }

        Map<String,Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : topics.keySet()) detectCycle(id, topics, state, path);
    }

    private static void validateTargets(ResearchTopic topic) {
        for (String shipId : topic.unlocks.values(ResearchUnlockKind.SHIP)) {
            if (!Rules.SHIPS.containsKey(shipId)) {
                throw new RuleConfigurationException("Research topic " + topic.id + " unlocks unknown ship: " + shipId);
            }
        }
        for (ResearchUnlockKind kind : List.of(ResearchUnlockKind.STATION, ResearchUnlockKind.STATION_PACKAGE)) {
            for (String stationId : topic.unlocks.values(kind)) {
                if (!Rules.BASES.containsKey(stationId)) {
                    throw new RuleConfigurationException("Research topic " + topic.id + " unlocks unknown station: " + stationId);
                }
            }
        }
    }

    private static void detectCycle(String id, Map<String, ResearchTopic> topics, Map<String,Integer> state, Deque<String> path) {
        int current = state.getOrDefault(id, 0);
        if (current == 2) return;
        if (current == 1) {
            List<String> cycle = new ArrayList<>(path);
            Collections.reverse(cycle);
            cycle.add(id);
            throw new RuleConfigurationException("Research dependency cycle: " + String.join(" -> ", cycle));
        }
        state.put(id, 1);
        path.push(id);
        for (String required : topics.get(id).requires) detectCycle(required, topics, state, path);
        path.pop();
        state.put(id, 2);
    }

    private static Map<String,Object> readObject(Path path) throws IOException {
        Object parsed = MiniJson.parse(Files.readString(path));
        return object(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return (Map<String,Object>) map;
        return Map.of();
    }

    private static List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object v : array(value)) {
            String id = String.valueOf(v).trim();
            if (!id.isBlank()) out.add(id);
        }
        return List.copyOf(out);
    }

    private static List<Cost> costs(Object value) {
        Map<String,Object> map = object(value);
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<String,Object> e : map.entrySet()) {
            Object amount = e.getValue();
            if (amount instanceof Number n) out.add(new Cost(material(e.getKey()), n.doubleValue()));
        }
        return List.copyOf(out);
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v).trim();
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Material material(String value) {
        try { return Material.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw new IllegalArgumentException("Unknown material: " + value); }
    }
}

final class ResearchTopic {
    final String id, name, description;
    final List<String> stationTypes, requires;
    final double timeSeconds;
    final List<Cost> requiredResources;
    final ResearchUnlocks unlocks;
    final String branch;
    final String doctrineGroup;

    ResearchTopic(String id, String name, String description, List<String> stationTypes, List<String> requires,
                  double timeSeconds, List<Cost> requiredResources, ResearchUnlocks unlocks) {
        this(id, name, description, stationTypes, requires, timeSeconds, requiredResources, unlocks, "", "");
    }

    ResearchTopic(String id, String name, String description, List<String> stationTypes, List<String> requires,
                  double timeSeconds, List<Cost> requiredResources, ResearchUnlocks unlocks,
                  String branch, String doctrineGroup) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.stationTypes = List.copyOf(stationTypes);
        this.requires = List.copyOf(requires);
        this.timeSeconds = Math.max(1.0, timeSeconds);
        this.requiredResources = List.copyOf(requiredResources);
        this.unlocks = unlocks == null ? ResearchUnlocks.empty() : unlocks;
        this.branch = branch == null ? "" : branch.trim();
        this.doctrineGroup = doctrineGroup == null ? "" : doctrineGroup.trim();
    }

    boolean canResearchAt(String stationTypeId) {
        return stationTypes.contains(stationTypeId);
    }

    String unlockLabel() {
        if (unlocks.isEmpty()) return "Unlocks: none";
        List<String> labels = new ArrayList<>();
        for (ResearchUnlockKind kind : ResearchUnlockKind.values()) {
            for (String id : unlocks.values(kind)) {
                String label = id;
                if (kind == ResearchUnlockKind.SHIP) {
                    ShipType ship = Rules.ship(id);
                    label = ship == null ? id : ship.name;
                }
                labels.add(kind.label + ": " + label);
            }
        }
        return "Unlocks: " + String.join(", ", labels);
    }
}

enum ResearchUnlockKind {
    SHIP("ships", "Ship"),
    STATION("stations", "Station"),
    STATION_PACKAGE("stationPackages", "Station package"),
    MODULE("modules", "Module"),
    WEAPON("weapons", "Weapon"),
    CRAFTABLE("craftables", "Craftable"),
    PRODUCTION_POLICY("productionPolicies", "Production policy"),
    COMBAT_POLICY("combatPolicies", "Combat policy"),
    FLEET_CAPABILITY("fleetCapabilities", "Fleet capability"),
    STRATEGIC_INFRASTRUCTURE("strategicInfrastructure", "Strategic infrastructure"),
    RADAR_CAPABILITY("radarCapabilities", "Radar capability"),
    FORMATION_CAPABILITY("formationCapabilities", "Formation capability"),
    CAPABILITY("capabilities", "Capability"),
    MODIFIER("modifiers", "Modifier");

    final String configKey;
    final String label;

    ResearchUnlockKind(String configKey, String label) {
        this.configKey = configKey;
        this.label = label;
    }

    static ResearchUnlockKind fromConfigKey(String key) {
        if (key == null) return null;
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        for (ResearchUnlockKind kind : values()) {
            String candidate = kind.configKey.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (candidate.equals(normalized)) return kind;
        }
        if (normalized.equals("stationpackages")) return STATION_PACKAGE;
        return null;
    }
}

final class ResearchUnlocks {
    final List<String> ships;
    private final EnumMap<ResearchUnlockKind, List<String>> values;

    ResearchUnlocks(List<String> ships) {
        this(Map.of(ResearchUnlockKind.SHIP, ships == null ? List.of() : ships));
    }

    ResearchUnlocks(Map<ResearchUnlockKind, List<String>> values) {
        this.values = new EnumMap<>(ResearchUnlockKind.class);
        if (values != null) {
            for (Map.Entry<ResearchUnlockKind, List<String>> entry : values.entrySet()) {
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                if (entry.getValue() != null) {
                    for (String id : entry.getValue()) {
                        if (id != null && !id.isBlank()) unique.add(id.trim());
                    }
                }
                if (!unique.isEmpty()) this.values.put(entry.getKey(), List.copyOf(unique));
            }
        }
        this.ships = this.values.getOrDefault(ResearchUnlockKind.SHIP, List.of());
    }

    static ResearchUnlocks empty() {
        return new ResearchUnlocks(Map.of());
    }

    static ResearchUnlocks parse(Map<String,Object> config) {
        if (config == null || config.isEmpty()) return empty();
        EnumMap<ResearchUnlockKind, List<String>> parsed = new EnumMap<>(ResearchUnlockKind.class);
        for (Map.Entry<String,Object> entry : config.entrySet()) {
            ResearchUnlockKind kind = ResearchUnlockKind.fromConfigKey(entry.getKey());
            if (kind == null) throw new RuleConfigurationException("Unknown research unlock category: " + entry.getKey());
            if (!(entry.getValue() instanceof List<?> list)) {
                throw new RuleConfigurationException("Research unlock category " + entry.getKey() + " must be an array.");
            }
            List<String> ids = new ArrayList<>();
            for (Object value : list) {
                String id = String.valueOf(value).trim();
                if (!id.isBlank()) ids.add(id);
            }
            parsed.put(kind, List.copyOf(ids));
        }
        return new ResearchUnlocks(parsed);
    }

    List<String> values(ResearchUnlockKind kind) {
        return kind == null ? List.of() : values.getOrDefault(kind, List.of());
    }

    boolean contains(ResearchUnlockKind kind, String id) {
        return kind != null && id != null && values(kind).contains(id);
    }

    boolean isEmpty() {
        return values.isEmpty();
    }
}
