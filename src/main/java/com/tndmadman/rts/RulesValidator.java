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

public final class RulesValidator {
    private static final Path DEFAULT_MANIFEST = Path.of("config/starchem.json");

    private RulesValidator() { }

    public static void main(String[] args) {
        Path manifest = args.length > 0 ? Path.of(args[0]) : DEFAULT_MANIFEST;
        validateOrThrow(manifest);
        System.out.println("StarChem rules/config validation passed: " + manifest);
    }

    static void validateOrThrow(Path manifest) {
        List<String> errors = validate(manifest);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("StarChem rules/config validation failed:\n - " + String.join("\n - ", errors));
        }
    }

    static List<String> validate(Path manifest) {
        return new Validator(manifest).run();
    }

    private static final class Validator {
        private final Path manifest;
        private final List<String> errors = new ArrayList<>();
        private final Map<Path, Map<String,Object>> parsedObjects = new LinkedHashMap<>();
        private final Set<Path> failedPaths = new LinkedHashSet<>();
        private final Set<String> materialNames = enumNames(Material.values());
        private final Set<String> nodeKindNames = enumNames(NodeKind.values());
        private final Set<String> materialFamilyNames = enumNames(MaterialFamily.values());
        private final Set<String> resourceTierNames = enumNames(ResourceTier.values());

        private Validator(Path manifest) {
            this.manifest = manifest;
        }

        List<String> run() {
            Map<String,Object> root = readObject(manifest, "manifest");
            if (root.isEmpty()) return List.copyOf(errors);

            Map<String,Object> files = object(root.get("files"));
            if (files.isEmpty()) {
                errors.add("manifest files section is missing or empty.");
                return List.copyOf(errors);
            }

            validateManifestFileEntries(files);

            Map<String,Def> ships = definitions(files, "ships", "shipTypes", "ship");
            Map<String,Def> stations = definitions(files, "stations", "stationTypes", "station");
            Map<String,Def> research = definitions(files, "research", "researchTopics", "research topic");
            Map<String,Def> craftables = definitions(files, "craftables", "craftableItems", "craftable item");

            Set<String> shipIds = ships.keySet();
            Set<String> stationIds = stations.keySet();
            Set<String> researchIds = research.keySet();
            Set<String> craftableIds = craftables.keySet();

            requireId(string(root, "startingShipType", ""), shipIds, "manifest.startingShipType", "ship");
            requireId(string(root, "defaultStationType", ""), stationIds, "manifest.defaultStationType", "station");

            validateShips(ships, stationIds);
            validateStations(stations, shipIds);
            validateResearch(research, stationIds, shipIds, researchIds);
            validateCraftables(craftables, stationIds);
            validateMaterials(files);
            validateResources(files);
            validateNpcFactions(files, shipIds, stationIds, researchIds, craftableIds);
            validateSystems(files);

            return List.copyOf(errors);
        }

        private void validateManifestFileEntries(Map<String,Object> files) {
            for (Map.Entry<String,Object> entry : files.entrySet()) {
                for (String rawPath : filePaths(entry.getValue(), "manifest.files." + entry.getKey())) {
                    readObject(Path.of(rawPath), "manifest.files." + entry.getKey());
                }
            }
        }

        private Map<String,Def> definitions(Map<String,Object> files, String fileKey, String objectKey, String label) {
            Map<String,Def> out = new LinkedHashMap<>();
            Object fileValue = files.get(fileKey);
            if (fileValue == null) {
                errors.add("manifest.files." + fileKey + " is missing.");
                return out;
            }

            for (String rawPath : filePaths(fileValue, "manifest.files." + fileKey)) {
                Map<String,Object> doc = readObject(Path.of(rawPath), label + " config " + rawPath);
                if (doc.isEmpty()) continue;
                Map<String,Object> source = object(doc.getOrDefault(objectKey, doc));
                if (source.isEmpty()) {
                    errors.add(label + " config " + rawPath + " has no " + objectKey + " definitions.");
                    continue;
                }
                for (Map.Entry<String,Object> entry : source.entrySet()) {
                    String id = clean(entry.getKey());
                    String context = label + " '" + id + "' in " + rawPath;
                    if (id.isBlank()) {
                        errors.add(label + " config " + rawPath + " has a blank id.");
                        continue;
                    }
                    Map<String,Object> data = object(entry.getValue());
                    if (data.isEmpty()) {
                        errors.add(context + " must be a JSON object.");
                        continue;
                    }
                    if (out.containsKey(id)) {
                        errors.add(context + " duplicates " + out.get(id).source + ".");
                        continue;
                    }
                    out.put(id, new Def(id, data, context));
                }
            }
            return out;
        }

        private void validateShips(Map<String,Def> ships, Set<String> stationIds) {
            if (ships.isEmpty()) errors.add("No ship definitions were loaded.");
            for (Def ship : ships.values()) {
                validateCostMap(ship.data.get("buildCost"), ship.source + ".buildCost");
                validateNodeKindList(ship.data.get("canHarvest"), ship.source + ".canHarvest");
                validateIdList(ship.data.get("canCarryStationPackages"), stationIds, ship.source + ".canCarryStationPackages", "station");
                validateNonNegative(ship.data, "maxHp", ship.source);
                validateNonNegative(ship.data, "maxShield", ship.source);
                validateNonNegative(ship.data, "shieldRegen", ship.source);
                validateNonNegative(ship.data, "shieldRegenDelay", ship.source);
                validateNonNegative(ship.data, "speed", ship.source);
                validateNonNegative(ship.data, "cargoCapacity", ship.source);
                validateNonNegative(ship.data, "harvestRange", ship.source);
                validateNonNegative(ship.data, "orbitRadius", ship.source);
                validateNonNegative(ship.data, "idleStationOrbitRadius", ship.source);
                validateNonNegative(ship.data, "scoutRange", ship.source);
                validateNonNegative(ship.data, "scoutDispatchLimit", ship.source);
                validateNonNegative(ship.data, "tractorBeams", ship.source);
                validateNonNegative(ship.data, "tractorRange", ship.source);
                validateNonNegative(ship.data, "buildTimeSeconds", ship.source);
            }
        }

        private void validateStations(Map<String,Def> stations, Set<String> shipIds) {
            if (stations.isEmpty()) errors.add("No station definitions were loaded.");
            Set<String> stationIds = stations.keySet();
            for (Def station : stations.values()) {
                validateCostMap(station.data.get("buildCost"), station.source + ".buildCost");
                validateIdList(station.data.get("canBuildShips"), shipIds, station.source + ".canBuildShips", "ship");
                validateIdList(station.data.get("canBuildStationPackages"), stationIds, station.source + ".canBuildStationPackages", "station");
                requireId(string(station.data, "mustBeCarriedByShipType", ""), shipIds, station.source + ".mustBeCarriedByShipType", "ship");
                validateFuel(station.data.get("fuel"), station.source + ".fuel");
                validateNonNegative(station.data, "maxHp", station.source);
                validateNonNegative(station.data, "maxShield", station.source);
                validateNonNegative(station.data, "shieldRegen", station.source);
                validateNonNegative(station.data, "shieldRegenDelay", station.source);
                validateNonNegative(station.data, "unloadRange", station.source);
                validateNonNegative(station.data, "unloadRate", station.source);
                validateNonNegative(station.data, "buildRadius", station.source);
                validateNonNegative(station.data, "packedVolume", station.source);
            }
        }

        private void validateResearch(Map<String,Def> research, Set<String> stationIds, Set<String> shipIds, Set<String> researchIds) {
            for (Def topic : research.values()) {
                validateIdList(topic.data.getOrDefault("stationTypes", topic.data.get("stations")), stationIds, topic.source + ".stationTypes", "station");
                validateIdList(topic.data.get("requires"), researchIds, topic.source + ".requires", "research topic");
                validateCostMap(topic.data.getOrDefault("requiredResources", topic.data.get("cost")), topic.source + ".requiredResources");
                validateNonNegative(topic.data, "timeSeconds", topic.source);
                Map<String,Object> unlocks = object(topic.data.get("unlocks"));
                validateIdList(unlocks.get("ships"), shipIds, topic.source + ".unlocks.ships", "ship");
            }
        }

        private void validateCraftables(Map<String,Def> craftables, Set<String> stationIds) {
            for (Def item : craftables.values()) {
                validateIdList(item.data.getOrDefault("stationTypes", item.data.get("stations")), stationIds, item.source + ".stationTypes", "station");
                validateCostMap(item.data.getOrDefault("requiredResources", item.data.get("input")), item.source + ".requiredResources");
                Map<String,Object> output = object(item.data.get("output"));
                if (!output.isEmpty()) {
                    validateMaterialName(string(output, "material", ""), item.source + ".output.material");
                    validateNonNegative(output, "amount", item.source + ".output");
                } else {
                    validateMaterialName(string(item.data, "outputMaterial", ""), item.source + ".outputMaterial");
                    validateNonNegative(item.data, "outputAmount", item.source);
                }
            }
        }

        private void validateMaterials(Map<String,Object> files) {
            Object materialFile = files.get("materials");
            if (materialFile == null) {
                errors.add("manifest.files.materials is missing.");
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String rawPath : filePaths(materialFile, "manifest.files.materials")) {
                Map<String,Object> doc = readObject(Path.of(rawPath), "materials config " + rawPath);
                Map<String,Object> materials = object(doc.get("materials"));
                for (Map.Entry<String,Object> entry : materials.entrySet()) {
                    String id = clean(entry.getKey()).toUpperCase(Locale.ROOT);
                    String context = "material '" + id + "' in " + rawPath;
                    if (!materialNames.contains(id)) errors.add(context + " does not exist in Material enum.");
                    if (!seen.add(id)) errors.add(context + " is duplicated.");
                    Map<String,Object> data = object(entry.getValue());
                    if (string(data, "displayName", "").isBlank()) errors.add(context + ".displayName is blank.");
                    String family = string(data, "family", "").toUpperCase(Locale.ROOT);
                    if (!materialFamilyNames.contains(family)) errors.add(context + ".family is invalid: " + family);
                    String tier = string(data, "tier", "").toUpperCase(Locale.ROOT);
                    if (!resourceTierNames.contains(tier)) errors.add(context + ".tier is invalid: " + tier);
                    if (!(data.get("raw") instanceof Boolean)) errors.add(context + ".raw must be boolean.");
                }
            }
            for (String material : materialNames) if (!seen.contains(material)) errors.add("materials config is missing " + material + ".");
        }

        private void validateResources(Map<String,Object> files) {
            Object resourcesFile = files.get("resources");
            if (resourcesFile == null) return;
            for (String rawPath : filePaths(resourcesFile, "manifest.files.resources")) {
                Map<String,Object> resources = readObject(Path.of(rawPath), "resources config " + rawPath);
                validateResourceBelts(resources.get("resourceBelts"), "resources " + rawPath + ".resourceBelts");
                Map<String,Object> respawn = object(resources.get("resourceRespawn"));
                validateNonNegative(respawn, "respawnDelaySeconds", "resources " + rawPath + ".resourceRespawn");
            }
        }

        private void validateNpcFactions(Map<String,Object> files, Set<String> shipIds, Set<String> stationIds,
                                         Set<String> researchIds, Set<String> craftableIds) {
            Object npcFile = files.get("npcs");
            if (npcFile == null) return;
            for (String rawPath : filePaths(npcFile, "manifest.files.npcs")) {
                Map<String,Object> doc = readObject(Path.of(rawPath), "NPC config " + rawPath);
                int index = 0;
                for (Object item : array(doc.get("factions"))) {
                    Map<String,Object> faction = object(item);
                    String id = string(faction, "id", "#" + index);
                    String context = "NPC faction '" + id + "' in " + rawPath;
                    requireId(string(faction, "baseType", ""), stationIds, context + ".baseType", "station");
                    validateIdList(faction.get("startingUnits"), shipIds, context + ".startingUnits", "ship");
                    validateIdList(faction.get("workerUnitTypes"), shipIds, context + ".workerUnitTypes", "ship");
                    validateIdList(faction.get("fleetUnitTypes"), shipIds, context + ".fleetUnitTypes", "ship");
                    validateIdList(faction.get("supportUnitTypes"), shipIds, context + ".supportUnitTypes", "ship");
                    validateIdList(faction.get("industryUnitTypes"), shipIds, context + ".industryUnitTypes", "ship");
                    validateIdList(faction.get("stationPackageTypes"), stationIds, context + ".stationPackageTypes", "station");
                    validateIdList(faction.get("researchTopicIds"), researchIds, context + ".researchTopicIds", "research topic");
                    validateIdList(faction.get("craftableItemIds"), craftableIds, context + ".craftableItemIds", "craftable item");
                    validateMaterialList(faction.get("targetMaterials"), context + ".targetMaterials");
                    validateNodeKindList(faction.get("harvestNodeKinds"), context + ".harvestNodeKinds");
                    validateNonNegative(faction, "firstSpawnSeconds", context);
                    validateNonNegative(faction, "respawnSeconds", context);
                    validateNonNegative(faction, "orderSeconds", context);
                    validateNonNegative(faction, "maxWorkers", context);
                    validateNonNegative(faction, "targetFleetSize", context);
                    validateNonNegative(faction, "raidFleetSize", context);
                    validateNonNegative(faction, "harassFleetSize", context);
                    validateNonNegative(faction, "maxSupportUnits", context);
                    validateNonNegative(faction, "maxStations", context);
                    validateNonNegative(faction, "maxIndustryUnits", context);
                    validateNonNegative(faction, "buildSeconds", context);
                    validateNonNegative(faction, "stationBuildSeconds", context);
                    validateNonNegative(faction, "defendRange", context);
                    validateNonNegative(faction, "raidCooldownSeconds", context);
                    validateNonNegative(faction, "retreatHpPercent", context);
                    validateNonNegative(faction, "stationSpacing", context);
                    validateNonNegative(faction, "fuelReserve", context);
                    validateNonNegative(faction, "spawnDistance", context);
                    validateNonNegative(faction, "spawnPadding", context);
                    validateNonNegative(faction, "unitSpacing", context);
                    validateNonNegative(faction, "minPlayerCombatShips", context);
                    index++;
                }
            }
        }

        private void validateSystems(Map<String,Object> files) {
            Object systemFiles = files.get("systems");
            if (systemFiles == null) return;
            Set<String> systemIds = new LinkedHashSet<>();
            for (String rawPath : filePaths(systemFiles, "manifest.files.systems")) {
                Map<String,Object> system = readObject(Path.of(rawPath), "system config " + rawPath);
                String id = string(system, "id", stripJson(Path.of(rawPath).getFileName().toString()));
                if (id.isBlank()) errors.add("system config " + rawPath + " has a blank id.");
                else if (!systemIds.add(id)) errors.add("system config " + rawPath + " duplicates system id '" + id + "'.");
                validateNonNegative(system, "width", "system '" + id + "'");
                validateNonNegative(system, "height", "system '" + id + "'");
                validateBodies(system.get("bodies"), "system '" + id + "'.bodies");
                validateResourceBelts(system.get("resourceBelts"), "system '" + id + "'.resourceBelts");
                validateMaterialList(system.get("spawnMaterials"), "system '" + id + "'.spawnMaterials");
                validateStringList(system.get("tags"), "system '" + id + "'.tags");
                Map<String,Object> modifiers = object(system.get("modifiers"));
                for (String key : List.of("miningYield", "resourceRespawn", "sensorRange", "shieldRegen", "movementSpeed", "weaponRange", "environmentalDamagePerSecond")) {
                    validateNonNegative(modifiers, key, "system '" + id + "'.modifiers");
                }
            }
        }

        private void validateBodies(Object value, String context) {
            Set<String> ids = new LinkedHashSet<>();
            List<Map<String,Object>> bodies = new ArrayList<>();
            int index = 0;
            for (Object item : array(value)) {
                Map<String,Object> body = object(item);
                String id = string(body, "id", "body" + index);
                if (id.isBlank()) errors.add(context + "[" + index + "] has a blank id.");
                else if (!ids.add(id)) errors.add(context + "[" + index + "] duplicates body id '" + id + "'.");
                bodies.add(body);
                index++;
            }
            index = 0;
            for (Map<String,Object> body : bodies) {
                String id = string(body, "id", "body" + index);
                String parent = string(body, "parent", "");
                if (!parent.isBlank() && !ids.contains(parent)) errors.add(context + " body '" + id + "' references unknown parent body '" + parent + "'.");
                validateNonNegative(body, "orbit", context + " body '" + id + "'");
                validateNonNegative(body, "radius", context + " body '" + id + "'");
                index++;
            }
        }

        private void validateResourceBelts(Object value, String context) {
            int index = 0;
            for (Object item : array(value)) {
                Map<String,Object> belt = object(item);
                String label = context + "[" + index + "]";
                validateNodeKindName(string(belt, "kind", ""), label + ".kind");
                List<?> materials = array(belt.get("materials"));
                List<?> composition = array(belt.get("composition"));
                if (materials.isEmpty() && composition.isEmpty()) errors.add(label + " must define materials or composition.");
                validateMaterialList(materials, label + ".materials");
                validateComposition(composition, label + ".composition");
                validateNonNegative(belt, "orbit", label);
                validateNonNegative(belt, "width", label);
                validateNonNegative(belt, "arc", label);
                validateNonNegative(belt, "count", label);
                validateNonNegative(belt, "amount", label);
                validateNonNegative(belt, "harvestRate", label);
                validateNonNegative(belt, "radius", label);
                index++;
            }
        }

        private void validateComposition(Object value, String context) {
            int index = 0;
            for (Object item : array(value)) {
                Map<String,Object> entry = object(item);
                String label = context + "[" + index + "]";
                validateMaterialName(string(entry, "material", ""), label + ".material");
                Object weight = entry.get("weight");
                if (!(weight instanceof Number number) || number.doubleValue() <= 0 || !Double.isFinite(number.doubleValue())) {
                    errors.add(label + ".weight must be a finite positive number.");
                }
                index++;
            }
        }

        private void validateStringList(Object value, String context) {
            int index = 0;
            for (Object item : array(value)) {
                if (String.valueOf(item).trim().isBlank()) errors.add(context + "[" + index + "] is blank.");
                index++;
            }
        }

        private void validateFuel(Object value, String context) {
            Map<String,Object> fuel = object(value);
            if (fuel.isEmpty()) return;
            validateMaterialName(string(fuel, "material", ""), context + ".material");
            validateNonNegative(fuel, "perSecond", context);
        }

        private void validateCostMap(Object value, String context) {
            if (value == null) return;
            Map<String,Object> map = object(value);
            if (map.isEmpty() && !(value instanceof Map<?,?>)) {
                errors.add(context + " must be a JSON object.");
                return;
            }
            for (Map.Entry<String,Object> entry : map.entrySet()) {
                validateMaterialName(entry.getKey(), context + "." + entry.getKey());
                if (!(entry.getValue() instanceof Number amount)) {
                    errors.add(context + "." + entry.getKey() + " amount must be a number.");
                } else if (amount.doubleValue() < 0) {
                    errors.add(context + "." + entry.getKey() + " amount must be non-negative.");
                }
            }
        }

        private void validateIdList(Object value, Set<String> knownIds, String context, String label) {
            int index = 0;
            for (String id : stringList(value)) {
                if (id.isBlank()) errors.add(context + "[" + index + "] is blank.");
                else if (!knownIds.contains(id)) errors.add(context + "[" + index + "] references unknown " + label + " '" + id + "'.");
                index++;
            }
        }

        private void validateMaterialList(Object value, String context) {
            int index = 0;
            for (String material : stringList(value)) {
                validateMaterialName(material, context + "[" + index + "]");
                index++;
            }
        }

        private void validateNodeKindList(Object value, String context) {
            int index = 0;
            for (String kind : stringList(value)) {
                validateNodeKindName(kind, context + "[" + index + "]");
                index++;
            }
        }

        private void validateMaterialName(String value, String context) {
            if (value == null || value.isBlank()) return;
            if (!materialNames.contains(value.trim().toUpperCase(Locale.ROOT))) errors.add(context + " references unknown material '" + value + "'.");
        }

        private void validateNodeKindName(String value, String context) {
            if (value == null || value.isBlank()) return;
            if (!nodeKindNames.contains(value.trim().toUpperCase(Locale.ROOT))) errors.add(context + " references unknown node kind '" + value + "'.");
        }

        private void requireId(String value, Set<String> knownIds, String context, String label) {
            if (value == null || value.isBlank()) return;
            if (!knownIds.contains(value)) errors.add(context + " references unknown " + label + " '" + value + "'.");
        }

        private void validateNonNegative(Map<String,Object> map, String key, String context) {
            Object value = map.get(key);
            if (value == null) return;
            if (!(value instanceof Number number)) {
                errors.add(context + "." + key + " must be a number.");
            } else if (number.doubleValue() < 0) {
                errors.add(context + "." + key + " must be non-negative.");
            }
        }

        private Map<String,Object> readObject(Path path, String context) {
            Path normalized = path.normalize();
            if (parsedObjects.containsKey(normalized)) return parsedObjects.get(normalized);
            if (failedPaths.contains(normalized)) return Map.of();

            if (!Files.exists(normalized)) {
                errors.add(context + " file not found: " + normalized);
                failedPaths.add(normalized);
                return Map.of();
            }

            try {
                Object parsed = MiniJson.parse(Files.readString(normalized));
                Map<String,Object> object = object(parsed);
                if (!(parsed instanceof Map<?,?>)) {
                    errors.add(context + " root must be a JSON object: " + normalized);
                    failedPaths.add(normalized);
                    return Map.of();
                }
                parsedObjects.put(normalized, object);
                return object;
            } catch (IOException | IllegalArgumentException ex) {
                errors.add(context + " could not be parsed: " + normalized + " (" + ex.getMessage() + ")");
                failedPaths.add(normalized);
                return Map.of();
            }
        }

        private List<String> filePaths(Object value, String context) {
            List<String> out = new ArrayList<>();
            if (value == null) return out;
            if (value instanceof List<?> list) {
                int index = 0;
                for (Object item : list) {
                    addFilePath(out, item, context + "[" + index + "]");
                    index++;
                }
                return List.copyOf(out);
            }
            addFilePath(out, value, context);
            return List.copyOf(out);
        }

        private void addFilePath(List<String> out, Object value, String context) {
            if (!(value instanceof String text)) {
                errors.add(context + " must be a file path string.");
                return;
            }
            String cleaned = clean(text);
            if (cleaned.isBlank()) errors.add(context + " must not be blank.");
            else out.add(cleaned);
        }
    }

    private record Def(String id, Map<String,Object> data, String source) { }

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
        for (Object item : array(value)) out.add(String.valueOf(item).trim());
        return List.copyOf(out);
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripJson(String filename) {
        return filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> out = new LinkedHashSet<>();
        for (Enum<?> value : values) out.add(value.name());
        return Set.copyOf(out);
    }
}
