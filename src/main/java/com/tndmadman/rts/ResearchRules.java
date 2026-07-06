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

    static boolean shipUnlocked(World world, String playerId, String shipTypeId) {
        boolean gated = false;
        for (ResearchTopic topic : TOPICS.values()) {
            if (!topic.unlocks.ships.contains(shipTypeId)) continue;
            gated = true;
            if (world.hasResearch(playerId, topic.id)) return true;
        }
        return !gated;
    }

    static ResearchTopic firstTopicUnlockingShip(String shipTypeId) {
        for (ResearchTopic topic : TOPICS.values()) if (topic.unlocks.ships.contains(shipTypeId)) return topic;
        return null;
    }

    static String missingPrerequisite(World world, String playerId, ResearchTopic topic) {
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
        } catch (Exception ex) {
            System.err.println("Could not load research rules: " + ex.getMessage());
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
            Map<String,Object> unlocks = object(r.get("unlocks"));
            out.put(e.getKey(), new ResearchTopic(
                    e.getKey(),
                    string(r, "displayName", e.getKey()),
                    string(r, "description", ""),
                    stringList(r.getOrDefault("stationTypes", r.get("stations"))),
                    stringList(r.get("requires"),
                    number(r, "timeSeconds", 30),
                    costs(r.getOrDefault("requiredResources", r.get("cost"))),
                    new ResearchUnlocks(stringList(unlocks.get("ships")))));
        }
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
        for (Object v : array(value)) out.add(String.valueOf(v));
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
        return v == null ? fallback : String.valueOf(v);
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

    ResearchTopic(String id, String name, String description, List<String> stationTypes, List<String> requires,
                  double timeSeconds, List<Cost> requiredResources, ResearchUnlocks unlocks) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.stationTypes = List.copyOf(stationTypes);
        this.requires = List.copyOf(requires);
        this.timeSeconds = Math.max(1.0, timeSeconds);
        this.requiredResources = List.copyOf(requiredResources);
        this.unlocks = unlocks;
    }

    boolean canResearchAt(String stationTypeId) {
        return stationTypes.contains(stationTypeId);
    }

    String unlockLabel() {
        if (unlocks.ships.isEmpty()) return "Unlocks: none";
        StringBuilder b = new StringBuilder("Unlocks: ");
        for (int i = 0; i < unlocks.ships.size(); i++) {
            if (i > 0) b.append(", ");
            ShipType ship = Rules.ship(unlocks.ships.get(i));
            b.append(ship == null ? unlocks.ships.get(i) : ship.name);
        }
        return b.toString();
    }
}

final class ResearchUnlocks {
    final List<String> ships;

    ResearchUnlocks(List<String> ships) {
        this.ships = List.copyOf(ships);
    }
}
