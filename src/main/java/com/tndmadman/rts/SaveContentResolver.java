package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;

final class SaveContentResolver {
    private static final Map<String, String> SHIP_ALIASES = Map.of();
    private static final Map<String, String> BASE_ALIASES = Map.of();
    private static final Map<String, String> CRAFTABLE_ALIASES = Map.of();
    private static final Map<String, String> RESEARCH_ALIASES = Map.of();
    private static final Map<String, String> WEAPON_ALIASES = Map.of();
    private static final Map<String, String> SYSTEM_TEMPLATE_ALIASES = Map.of();

    private SaveContentResolver() { }

    static String shipId(String savedId) {
        String id = alias(savedId, SHIP_ALIASES);
        return Rules.findShip(id) == null ? Rules.STARTING_SHIP : id;
    }

    static String baseId(String savedId) {
        String id = alias(savedId, BASE_ALIASES);
        return Rules.findBase(id) == null ? Rules.DEFAULT_BASE : id;
    }

    static String optionalBaseId(String savedId) {
        String id = alias(savedId, BASE_ALIASES);
        return id.isBlank() || Rules.findBase(id) == null ? "" : id;
    }

    static String weaponId(String savedId) {
        String id = alias(savedId, WEAPON_ALIASES);
        return id.isBlank() || !WeaponRules.WEAPONS.containsKey(id) ? "" : id;
    }

    static String systemTemplateId(String savedId) {
        String id = alias(savedId, SYSTEM_TEMPLATE_ALIASES);
        StarSystemDefinition definition = StarSystems.get(id);
        return definition == null ? StarSystems.DEFAULT_SYSTEM_ID : definition.id();
    }

    static Material material(String savedId) {
        if (savedId == null || savedId.isBlank()) return null;
        try {
            return Material.valueOf(savedId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static NodeKind nodeKind(String savedId) {
        if (savedId == null || savedId.isBlank()) return NodeKind.SILICATE_ROCK;
        try {
            return NodeKind.valueOf(savedId);
        } catch (IllegalArgumentException ex) {
            return NodeKind.SILICATE_ROCK;
        }
    }

    static String productionItemId(ProductionJobKind kind, String savedId) {
        String id = savedId == null ? "" : savedId;
        return switch (kind) {
            case SHIP -> validShip(alias(id, SHIP_ALIASES));
            case STATION_PACKAGE -> validBase(alias(id, BASE_ALIASES));
            case CRAFTABLE -> validCraftable(alias(id, CRAFTABLE_ALIASES));
            case RESEARCH -> validResearch(alias(id, RESEARCH_ALIASES));
        };
    }

    static Map<String,Object> migrationPolicy() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("unknownShips", "replace-with-starting-ship");
        out.put("unknownStations", "replace-with-default-station");
        out.put("unknownStationPackages", "clear-package");
        out.put("unknownProductionJobs", "drop-job");
        out.put("unknownProjectiles", "drop-projectile");
        out.put("unknownMaterials", "drop-stack");
        out.put("unknownSystemTemplates", "use-default-template");
        return out;
    }

    private static String validShip(String id) { return Rules.findShip(id) == null ? "" : id; }
    private static String validBase(String id) { return Rules.findBase(id) == null ? "" : id; }
    private static String validCraftable(String id) { return CraftingRules.item(id) == null ? "" : id; }
    private static String validResearch(String id) { return ResearchRules.topic(id) == null ? "" : id; }

    private static String alias(String id, Map<String, String> aliases) {
        String clean = id == null ? "" : id.trim();
        return aliases.getOrDefault(clean, clean);
    }
}
