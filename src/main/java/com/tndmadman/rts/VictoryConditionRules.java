package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


enum VictoryConditionType {
    COMPLETE_RESEARCH,
    COMPLETE_RESEARCH_COUNT,
    OWN_SHIPS,
    OWN_COMBAT_SHIPS,
    OWN_STATIONS,
    OWN_SHIP_TYPE,
    OWN_STATION_TYPE,
    FLEET_POWER,
    CONTROL_SYSTEMS,
    SURVIVE_SECONDS
}

record VictoryConditionDefinition(String id, String displayName, String description,
                                  VictoryConditionType type, String value, int target) {
    VictoryConditionDefinition {
        id = cleanId(id);
        displayName = cleanText(displayName, id);
        description = cleanText(description, displayName);
        type = type == null ? VictoryConditionType.COMPLETE_RESEARCH : type;
        value = value == null ? "" : value.trim();
        if (target < 1) throw new IllegalArgumentException("Victory-condition target must be positive: " + id);
        if (requiresValue(type) && value.isBlank()) {
            throw new IllegalArgumentException("Victory condition " + id + " requires a value.");
        }
    }

    private static boolean requiresValue(VictoryConditionType type) {
        return type == VictoryConditionType.COMPLETE_RESEARCH
                || type == VictoryConditionType.OWN_SHIP_TYPE
                || type == VictoryConditionType.OWN_STATION_TYPE;
    }

    private static String cleanId(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (clean.isBlank() || !clean.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid victory-condition ID: " + value);
        }
        return clean;
    }

    private static String cleanText(String value, String fallback) {
        String clean = value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.isBlank() ? fallback : clean;
    }

    String formatProgress(int current) {
        int safe = Math.max(0, current);
        if (type != VictoryConditionType.SURVIVE_SECONDS) return safe + " / " + target;
        return duration(safe) + " / " + duration(target);
    }

    private static String duration(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }

    @Override public String toString() { return displayName; }
}

final class VictoryConditionRules {
    private static final Path DEFAULT_FILE = Path.of("config/victory-conditions.json");
    private static final List<VictoryConditionDefinition> DEFINITIONS = load();
    private static final Map<String,VictoryConditionDefinition> BY_ID = index(DEFINITIONS);

    private VictoryConditionRules() { }

    static List<VictoryConditionDefinition> all() { return DEFINITIONS; }

    static VictoryConditionDefinition definition(String id) {
        return BY_ID.get(id == null ? "" : id.trim().toLowerCase(Locale.ROOT));
    }

    static VictoryConditionDefinition require(String id) {
        VictoryConditionDefinition definition = definition(id);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown victory condition: " + id
                    + ". Expected one of: " + String.join(", ", BY_ID.keySet()) + ".");
        }
        return definition;
    }

    static String normalizeId(String id) {
        VictoryConditionDefinition definition = definition(id);
        return definition == null ? defaultId() : definition.id();
    }

    static String defaultId() { return DEFINITIONS.get(0).id(); }

    private static List<VictoryConditionDefinition> load() {
        Path path = configuredPath();
        if (!Files.isRegularFile(path)) return fallbackDefinitions();
        try {
            Object parsed = MiniJson.parse(Files.readString(path));
            if (!(parsed instanceof Map<?,?> root)) {
                throw new IllegalArgumentException(path + " must contain a JSON object.");
            }
            Object rawDefinitions = root.get("victoryConditions");
            if (!(rawDefinitions instanceof List<?> rows) || rows.isEmpty()) {
                throw new IllegalArgumentException(path + " must contain a non-empty victoryConditions array.");
            }
            List<VictoryConditionDefinition> out = new ArrayList<>();
            for (Object row : rows) out.add(parseDefinition(row));
            Map<String,VictoryConditionDefinition> indexed = index(out);
            if (indexed.size() != out.size()) throw new IllegalArgumentException("Victory-condition IDs must be unique.");
            return Collections.unmodifiableList(out);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Could not load victory conditions from " + path + ": " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not read victory conditions from " + path + ": " + ex.getMessage(), ex);
        }
    }

    private static Path configuredPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.isRegularFile(manifest)) return DEFAULT_FILE;
        try {
            Object parsed = MiniJson.parse(Files.readString(manifest));
            if (!(parsed instanceof Map<?,?> root)) return DEFAULT_FILE;
            Object filesValue = root.get("files");
            if (!(filesValue instanceof Map<?,?> files)) return DEFAULT_FILE;
            Object configured = files.get("victoryConditions");
            if (configured == null || String.valueOf(configured).isBlank()) return DEFAULT_FILE;
            Path candidate = Path.of(String.valueOf(configured)).normalize();
            if (candidate.isAbsolute() || candidate.startsWith("..")) {
                throw new IllegalArgumentException("Victory-condition path escapes the release directory.");
            }
            return candidate;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not read victory-condition path: " + ex.getMessage(), ex);
        }
    }

    private static VictoryConditionDefinition parseDefinition(Object value) {
        if (!(value instanceof Map<?,?> raw)) throw new IllegalArgumentException("Victory-condition entries must be objects.");
        Map<String,Object> row = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : raw.entrySet()) row.put(String.valueOf(entry.getKey()), entry.getValue());
        String id = text(row.get("id"));
        String displayName = text(row.get("displayName"));
        String description = text(row.get("description"));
        VictoryConditionType type;
        try { type = VictoryConditionType.valueOf(text(row.get("type")).toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Unknown victory-condition type for " + id + "."); }
        String parameter = text(row.get("value"));
        int target = positiveInt(row.get("target"), id);
        return new VictoryConditionDefinition(id, displayName, description, type, parameter, target);
    }

    private static Map<String,VictoryConditionDefinition> index(List<VictoryConditionDefinition> definitions) {
        Map<String,VictoryConditionDefinition> out = new LinkedHashMap<>();
        for (VictoryConditionDefinition definition : definitions) {
            if (out.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate victory-condition ID: " + definition.id());
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("At least one victory condition is required.");
        return Collections.unmodifiableMap(out);
    }

    private static int positiveInt(Object value, String id) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Victory-condition target must be numeric: " + id);
        double numeric = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(numeric) || numeric != integer || integer < 1 || integer > 1_000_000_000) {
            throw new IllegalArgumentException("Victory-condition target is outside the allowed range: " + id);
        }
        return integer;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static List<VictoryConditionDefinition> fallbackDefinitions() {
        return List.of(
                new VictoryConditionDefinition("industrial_breakthrough", "Industrial Breakthrough",
                        "Complete Advanced Industry research.", VictoryConditionType.COMPLETE_RESEARCH,
                        "advanced_industry", 1),
                new VictoryConditionDefinition("research_supremacy", "Research Supremacy",
                        "Complete all four research topics.", VictoryConditionType.COMPLETE_RESEARCH_COUNT, "", 4),
                new VictoryConditionDefinition("fleet_muster", "Fleet Muster",
                        "Command twelve active ships across the galaxy.", VictoryConditionType.OWN_SHIPS, "", 12),
                new VictoryConditionDefinition("battle_ready", "Battle Ready",
                        "Command six armed combat ships across the galaxy.", VictoryConditionType.OWN_COMBAT_SHIPS, "", 6),
                new VictoryConditionDefinition("station_network", "Station Network",
                        "Operate five active stations across the galaxy.", VictoryConditionType.OWN_STATIONS, "", 5),
                new VictoryConditionDefinition("carrier_group", "Carrier Group",
                        "Command two active carriers.", VictoryConditionType.OWN_SHIP_TYPE, "carrier", 2),
                new VictoryConditionDefinition("laboratory_network", "Laboratory Network",
                        "Operate three active laboratories.", VictoryConditionType.OWN_STATION_TYPE, "laboratory", 3),
                new VictoryConditionDefinition("fleet_power", "Fleet Power",
                        "Reach a combined active fleet and station strength of 30,000.", VictoryConditionType.FLEET_POWER, "", 30000),
                new VictoryConditionDefinition("system_dominance", "System Dominance",
                        "Control three galaxy systems.", VictoryConditionType.CONTROL_SYSTEMS, "", 3),
                new VictoryConditionDefinition("endurance", "Endurance",
                        "Keep at least one active asset alive for thirty minutes.", VictoryConditionType.SURVIVE_SECONDS, "", 1800));
    }
}
