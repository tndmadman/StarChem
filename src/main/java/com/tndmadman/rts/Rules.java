package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class Rules {
    static final Map<String, ShipType> SHIPS = new LinkedHashMap<>();
    static final Map<String, BaseType> BASES = new LinkedHashMap<>();
    static final List<ResourceBelt> RESOURCE_BELTS = new ArrayList<>();
    static ResourceRespawnRules RESOURCE_RESPAWN = new ResourceRespawnRules(18);
    static String STARTING_SHIP = "prospector";
    static String DEFAULT_BASE = "outpost";

    static {
        if (!loadExternal()) loadDefaults();
    }

    private Rules() { }

    private static boolean loadExternal() {
        if (Files.exists(Path.of("config/starchem.json"))) return loadManifest(Path.of("config/starchem.json"));
        if (Files.exists(Path.of("config/starchem-rules.json"))) return loadLegacy(Path.of("config/starchem-rules.json"));
        return false;
    }

    private static boolean loadManifest(Path path) {
        try {
            Map<String,Object> root = readObject(path);
            Map<String,Object> files = object(root.get("files"));
            Map<String,ShipType> ships = parseShipFiles(files.getOrDefault("ships", "config/ships.json"));
            Map<String,BaseType> bases = parseBases(readObject(configFile(files, "stations", "config/stations.json")));
            Map<String,Object> resources = readObject(configFile(files, "resources", "config/resources.json"));
            List<ResourceBelt> belts = parseResourceBelts(resources);
            ResourceRespawnRules respawn = parseRespawn(resources, RESOURCE_RESPAWN);
            apply(string(root, "startingShipType", STARTING_SHIP), string(root, "defaultStationType", DEFAULT_BASE), ships, bases, belts, respawn);
            return true;
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load split StarChem config: " + ex.getMessage());
        }
    }

    private static boolean loadLegacy(Path path) {
        try {
            Map<String,Object> root = readObject(path);
            Map<String,ShipType> ships = parseShips(root);
            Map<String,BaseType> bases = parseBases(root);
            List<ResourceBelt> belts = parseResourceBelts(root);
            ResourceRespawnRules respawn = parseRespawn(root, RESOURCE_RESPAWN);
            apply(string(root, "startingShipType", STARTING_SHIP), string(root, "defaultStationType", DEFAULT_BASE), ships, bases, belts, respawn);
            return true;
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load legacy StarChem rules config: " + ex.getMessage());
        }
    }

    private static void apply(String startShip, String defaultBase, Map<String,ShipType> ships, Map<String,BaseType> bases,
                              List<ResourceBelt> belts, ResourceRespawnRules respawn) {
        if (ships.isEmpty()) throw new RuleConfigurationException("No ship types loaded.");
        if (bases.isEmpty()) throw new RuleConfigurationException("No station types loaded.");
        if (!ships.containsKey(startShip)) {
            throw new RuleConfigurationException("Unknown starting ship type ID: " + startShip);
        }
        if (!bases.containsKey(defaultBase)) {
            throw new RuleConfigurationException("Unknown default station type ID: " + defaultBase);
        }
        SHIPS.clear();
        SHIPS.putAll(ships);
        BASES.clear();
        BASES.putAll(bases);
        RESOURCE_BELTS.clear();
        if (belts.isEmpty()) RESOURCE_BELTS.addAll(defaultResourceBelts());
        else RESOURCE_BELTS.addAll(belts);
        STARTING_SHIP = startShip;
        DEFAULT_BASE = defaultBase;
        RESOURCE_RESPAWN = respawn;
    }

    private static Path configFile(Map<String,Object> files, String key, String fallback) {
        return Path.of(string(files, key, fallback));
    }

    private static Map<String,Object> readObject(Path path) throws IOException {
        Object parsed = MiniJson.parse(Files.readString(path));
        return object(parsed);
    }

    private static Map<String,ShipType> parseShipFiles(Object fileValue) throws IOException {
        Map<String,ShipType> out = new LinkedHashMap<>();
        if (fileValue instanceof List<?> list) {
            for (Object item : list) out.putAll(parseShips(readObject(Path.of(String.valueOf(item)))));
        } else {
            out.putAll(parseShips(readObject(Path.of(String.valueOf(fileValue)))));
        }
        return out;
    }

    private static Map<String,ShipType> parseShips(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("shipTypes", doc));
        Map<String,ShipType> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> s = object(e.getValue());
            if (s.isEmpty()) continue;
            String id = e.getKey();
            double hp = number(s, "maxHp", 100);
            if (!s.containsKey("weaponHardpoints")) {
                throw new RuleConfigurationException("Missing weaponHardpoints for ship " + id + ".");
            }
            int weaponHardpoints = integer(s, "weaponHardpoints", -1);
            if (weaponHardpoints < 0 || weaponHardpoints > 64) {
                throw new RuleConfigurationException("weaponHardpoints for ship " + id + " must be between 0 and 64.");
            }
            out.put(id, new ShipType(
                    id,
                    string(s, "displayName", id),
                    shipSize(string(s, "size", "SMALL")),
                    integer(s, "seed", Math.abs(id.hashCode())),
                    hp,
                    number(s, "speed", 120),
                    number(s, "cargoCapacity", 0),
                    number(s, "harvestRange", 0),
                    number(s, "orbitRadius", 80),
                    number(s, "idleStationOrbitRadius", 110),
                    number(s, "scoutRange", 0),
                    integer(s, "scoutDispatchLimit", 0),
                    number(s, "maxShield", Math.max(0, hp * 0.35)),
                    number(s, "shieldRegen", Math.max(0, hp * 0.012)),
                    number(s, "shieldRegenDelay", 4.0),
                    integer(s, "tractorBeams", integer(s, "tractorBeamCount", 0)),
                    number(s, "tractorRange", 0),
                    bool(s, "baseBuilder", false),
                    nodeKinds(s.get("canHarvest")),
                    costs(s.get("buildCost")),
                    number(s, "buildTimeSeconds", 0),
                    weaponHardpoints));
        }
        return out;
    }

    private static Map<String,BaseType> parseBases(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("stationTypes", doc));
        Map<String,BaseType> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> s = object(e.getValue());
            if (s.isEmpty()) continue;
            String id = e.getKey();
            double hp = number(s, "maxHp", 1000);
            out.put(id, new BaseType(
                    id,
                    string(s, "displayName", id),
                    hp,
                    number(s, "unloadRange", 120),
                    number(s, "unloadRate", 100),
                    number(s, "buildRadius", 72),
                    number(s, "maxShield", Math.max(0, hp * 0.45)),
                    number(s, "shieldRegen", Math.max(0, hp * 0.01)),
                    number(s, "shieldRegenDelay", 5.0),
                    stringList(s.get("canBuildShips")),
                    stringList(s.get("canBuildStationPackages")),
                    costs(s.get("buildCost")),
                    number(s, "buildTimeSeconds", 0),
                    bool(s, "canRefitShips", false),
                    number(s, "refitRange", number(s, "unloadRange", 120))));
        }
        return out;
    }

    private static List<ResourceBelt> parseResourceBelts(Map<String,Object> doc) {
        List<ResourceBelt> out = new ArrayList<>();
        for (Object item : array(doc.get("resourceBelts"))) {
            Map<String,Object> b = object(item);
            if (b.isEmpty()) continue;
            out.add(new ResourceBelt(
                    string(b, "name", "Resource Belt"),
                    nodeKind(string(b, "kind", "SILICATE_ROCK")),
                    materials(b.get("materials")),
                    number(b, "orbit", 2500),
                    number(b, "width", 300),
                    number(b, "arc", 1.0),
                    integer(b, "count", 20),
                    number(b, "amount", 100),
                    number(b, "harvestRate", 8),
                    number(b, "radius", 3)));
        }
        return List.copyOf(out);
    }

    private static ResourceRespawnRules parseRespawn(Map<String,Object> doc, ResourceRespawnRules fallback) {
        Map<String,Object> r = object(doc.get("resourceRespawn"));
        if (r.isEmpty()) return fallback;
        return new ResourceRespawnRules(number(r, "respawnDelaySeconds", fallback.respawnDelaySeconds));
    }

    private static void loadDefaults() {
        STARTING_SHIP = "prospector";
        DEFAULT_BASE = "outpost";
        SHIPS.clear();
        BASES.clear();
        RESOURCE_BELTS.clear();
        RESOURCE_RESPAWN = new ResourceRespawnRules(18);

        ship(new ShipType("prospector", "Prospector", ShipSize.SMALL, 1501, 100, 185, 120, 105, 72, 96, 0, 0, false,
                EnumSet.of(NodeKind.SILICATE_ROCK, NodeKind.GAS_CLOUD), cost(Material.IRON,80, Material.COPPER,40)));
        ship(new ShipType("station_builder", "Deployer", ShipSize.LARGE, 2451, 240, 115, 0, 0, 90, 120, 0, 0, true,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,220, Material.COPPER,120, Material.SILICATES,100, Material.ICE,40)));
        ship(new ShipType("scout", "Scout", ShipSize.SMALL, 9907, 70, 275, 45, 60, 70, 115, 420, 5, false,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,60, Material.COPPER,90, Material.HYDROGEN,40)));
        ship(new ShipType("hauler", "Hauler", ShipSize.LARGE, 3319, 150, 138, 340, 70, 84, 120, 0, 0, false,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,150, Material.COPPER,60, Material.SILICATES,80)));
        ship(new ShipType("deep_miner", "Deep Miner", ShipSize.MEDIUM, 6173, 180, 125, 220, 125, 86, 120, 0, 0, false,
                EnumSet.of(NodeKind.SILICATE_ROCK), cost(Material.IRON,180, Material.COPPER,110, Material.SILICATES,140, Material.ICE,60)));
        ship(new ShipType("gas_harvester", "Gas Harvester", ShipSize.MEDIUM, 7281, 125, 150, 180, 130, 92, 120, 0, 0, false,
                EnumSet.of(NodeKind.GAS_CLOUD), cost(Material.IRON,120, Material.COPPER,150, Material.SILICATES,60, Material.ICE,80, Material.HYDROGEN,80)));
        ship(new ShipType("freighter", "Freighter", ShipSize.XL, 8431, 360, 92, 1440, 0, 110, 155, 0, 0, false,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,420, Material.COPPER,200, Material.SILICATES,300, Material.ICE,140)));
        ship(new ShipType("salvager", "Salvager", ShipSize.MEDIUM, 5227, 135, 135, 600, 0, 88, 120, 0, 0,
                115, 2.0, 3.5, 2, 360, false, EnumSet.noneOf(NodeKind.class),
                cost(Material.IRON,160, Material.COPPER,120, Material.SILICATES,90)));

        base(new BaseType("outpost", "Outpost", 1200, 118, 95, 72,
                List.of("prospector", "station_builder"), List.of("shipyard"), List.of()));
        base(new BaseType("shipyard", "Shipyard", 2400, 150, 160, 100,
                List.of("prospector", "station_builder", "scout", "hauler", "deep_miner", "gas_harvester", "freighter", "salvager"),
                List.of(), cost(Material.IRON,500, Material.COPPER,250, Material.SILICATES,350, Material.ICE,160)));

        RESOURCE_BELTS.addAll(defaultResourceBelts());
    }

    private static List<ResourceBelt> defaultResourceBelts() {
        return List.of(
                new ResourceBelt("Inner Iron Belt", NodeKind.SILICATE_ROCK, List.of(Material.IRON), 1900, 260, 1.0, 130, 22, 7.5, 2.8),
                new ResourceBelt("Copper Arc", NodeKind.SILICATE_ROCK, List.of(Material.COPPER), 2650, 300, 0.8, 110, 18, 6.5, 2.6),
                new ResourceBelt("Silicate Belt", NodeKind.SILICATE_ROCK, List.of(Material.SILICATES), 3500, 360, 1.2, 140, 24, 8.0, 3.0),
                new ResourceBelt("Ice Ring", NodeKind.SILICATE_ROCK, List.of(Material.ICE), 4650, 420, 0.9, 115, 20, 7.0, 2.8),
                new ResourceBelt("Hydrogen Drift", NodeKind.GAS_CLOUD, List.of(Material.HYDROGEN), 5450, 520, 1.1, 120, 26, 9.0, 4.8),
                new ResourceBelt("Outer Gas Band", NodeKind.GAS_CLOUD, List.of(Material.HELIUM, Material.METHANE, Material.AMMONIA, Material.HYDROGEN), 6650, 620, 1.4, 160, 22, 7.5, 4.5)
        );
    }

    private static void ship(ShipType type) { SHIPS.put(type.id, type); }
    private static void base(BaseType type) { BASES.put(type.id, type); }

    static ShipType findShip(String id) { return id == null ? null : SHIPS.get(id); }
    static BaseType findBase(String id) { return id == null ? null : BASES.get(id); }

    static ShipType ship(String id) {
        ShipType type = findShip(id);
        if (type == null) throw new UnknownRuleIdException("Unknown ship type ID: " + id);
        return type;
    }

    static BaseType base(String id) {
        BaseType type = findBase(id);
        if (type == null) throw new UnknownRuleIdException("Unknown station type ID: " + id);
        return type;
    }

    static List<Cost> cost(Object... pairs) {
        List<Cost> result = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) result.add(new Cost((Material) pairs[i], ((Number) pairs[i + 1]).doubleValue()));
        return List.copyOf(result);
    }

    static String formatCost(List<Cost> cost) {
        if (cost.isEmpty()) return "free";
        StringBuilder b = new StringBuilder();
        for (Cost c : cost) {
            if (!b.isEmpty()) b.append(", ");
            b.append(Calc.round(c.amount())).append(' ').append(c.material().label);
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return (Map<String,Object>) map;
        return Map.of();
    }

    private static List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static int integer(Map<String,Object> map, String key, int fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : fallback;
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

    private static EnumSet<NodeKind> nodeKinds(Object value) {
        EnumSet<NodeKind> out = EnumSet.noneOf(NodeKind.class);
        for (Object v : array(value)) out.add(nodeKind(String.valueOf(v)));
        return out;
    }

    private static List<Material> materials(Object value) {
        List<Material> out = new ArrayList<>();
        for (Object v : array(value)) out.add(material(String.valueOf(v)));
        return List.copyOf(out);
    }

    private static ShipSize shipSize(String value) {
        return StrictConfigEnums.parse(ShipSize.class, value, "ship size");
    }

    private static NodeKind nodeKind(String value) {
        return StrictConfigEnums.parse(NodeKind.class, value, "resource node kind");
    }

    private static Material material(String value) {
        return StrictConfigEnums.parse(Material.class, value, "material");
    }
}

final class UnknownRuleIdException extends IllegalArgumentException {
    UnknownRuleIdException(String message) { super(message); }
}

final class RuleConfigurationException extends IllegalArgumentException {
    RuleConfigurationException(String message) { super(message); }
}

final class ShipType {
    final String id, name;
    final ShipSize size;
    final int seed;
    final double maxHp, speed, cargoCapacity, harvestRange, orbitRadius, idleOrbitRadius, scoutRange;
    final double maxShield, shieldRegen, shieldRegenDelay, tractorRange, buildTimeSeconds;
    final int scoutDispatchLimit, tractorBeamCount, weaponHardpoints;
    final boolean baseBuilder;
    final EnumSet<NodeKind> harvestKinds;
    final List<Cost> buildCost;

    ShipType(String id, String name, ShipSize size, int seed, double maxHp, double speed, double cargoCapacity,
             double harvestRange, double orbitRadius, double idleOrbitRadius, double scoutRange, int scoutDispatchLimit,
             boolean baseBuilder, EnumSet<NodeKind> harvestKinds, List<Cost> buildCost) {
        this(id, name, size, seed, maxHp, speed, cargoCapacity, harvestRange, orbitRadius, idleOrbitRadius, scoutRange,
                scoutDispatchLimit, Math.max(0, maxHp * 0.35), Math.max(0, maxHp * 0.012), 4.0, 0, 0,
                baseBuilder, harvestKinds, buildCost, 0);
    }

    ShipType(String id, String name, ShipSize size, int seed, double maxHp, double speed, double cargoCapacity,
             double harvestRange, double orbitRadius, double idleOrbitRadius, double scoutRange, int scoutDispatchLimit,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             boolean baseBuilder, EnumSet<NodeKind> harvestKinds, List<Cost> buildCost) {
        this(id, name, size, seed, maxHp, speed, cargoCapacity, harvestRange, orbitRadius, idleOrbitRadius, scoutRange,
                scoutDispatchLimit, maxShield, shieldRegen, shieldRegenDelay, 0, 0, baseBuilder, harvestKinds, buildCost, 0);
    }

    ShipType(String id, String name, ShipSize size, int seed, double maxHp, double speed, double cargoCapacity,
             double harvestRange, double orbitRadius, double idleOrbitRadius, double scoutRange, int scoutDispatchLimit,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             int tractorBeamCount, double tractorRange,
             boolean baseBuilder, EnumSet<NodeKind> harvestKinds, List<Cost> buildCost) {
        this(id, name, size, seed, maxHp, speed, cargoCapacity, harvestRange, orbitRadius, idleOrbitRadius, scoutRange,
                scoutDispatchLimit, maxShield, shieldRegen, shieldRegenDelay, tractorBeamCount, tractorRange,
                baseBuilder, harvestKinds, buildCost, 0);
    }

    ShipType(String id, String name, ShipSize size, int seed, double maxHp, double speed, double cargoCapacity,
             double harvestRange, double orbitRadius, double idleOrbitRadius, double scoutRange, int scoutDispatchLimit,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             int tractorBeamCount, double tractorRange,
             boolean baseBuilder, EnumSet<NodeKind> harvestKinds, List<Cost> buildCost, double buildTimeSeconds) {
        this(id, name, size, seed, maxHp, speed, cargoCapacity, harvestRange, orbitRadius, idleOrbitRadius, scoutRange,
                scoutDispatchLimit, maxShield, shieldRegen, shieldRegenDelay, tractorBeamCount, tractorRange,
                baseBuilder, harvestKinds, buildCost, buildTimeSeconds, 0);
    }

    ShipType(String id, String name, ShipSize size, int seed, double maxHp, double speed, double cargoCapacity,
             double harvestRange, double orbitRadius, double idleOrbitRadius, double scoutRange, int scoutDispatchLimit,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             int tractorBeamCount, double tractorRange,
             boolean baseBuilder, EnumSet<NodeKind> harvestKinds, List<Cost> buildCost, double buildTimeSeconds,
             int weaponHardpoints) {
        this.id = id; this.name = name; this.size = size; this.seed = seed; this.maxHp = maxHp; this.speed = speed;
        this.cargoCapacity = cargoCapacity; this.harvestRange = harvestRange; this.orbitRadius = orbitRadius;
        this.idleOrbitRadius = idleOrbitRadius; this.scoutRange = scoutRange; this.scoutDispatchLimit = scoutDispatchLimit;
        this.maxShield = maxShield; this.shieldRegen = shieldRegen; this.shieldRegenDelay = shieldRegenDelay;
        this.tractorBeamCount = Math.max(0, tractorBeamCount); this.tractorRange = Math.max(0, tractorRange);
        this.baseBuilder = baseBuilder; this.harvestKinds = harvestKinds; this.buildCost = buildCost;
        this.buildTimeSeconds = Math.max(0, buildTimeSeconds);
        this.weaponHardpoints = Math.max(0, weaponHardpoints);
    }
}

final class BaseType {
    final String id, name;
    final double maxHp, unloadRange, unloadRate, buildRadius;
    final double maxShield, shieldRegen, shieldRegenDelay, buildTimeSeconds;
    final double refitRange;
    final boolean canRefitShips;
    final List<String> buildableShips, basePackages;
    final List<Cost> buildCost;

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
             List<String> buildableShips, List<String> basePackages, List<Cost> buildCost) {
        this(id, name, maxHp, unloadRange, unloadRate, buildRadius, Math.max(0, maxHp * 0.45), Math.max(0, maxHp * 0.01), 5.0,
                buildableShips, basePackages, buildCost, 0);
    }

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             List<String> buildableShips, List<String> basePackages, List<Cost> buildCost) {
        this(id, name, maxHp, unloadRange, unloadRate, buildRadius, maxShield, shieldRegen, shieldRegenDelay,
                buildableShips, basePackages, buildCost, 0);
    }

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             List<String> buildableShips, List<String> basePackages, List<Cost> buildCost, double buildTimeSeconds) {
        this(id, name, maxHp, unloadRange, unloadRate, buildRadius, maxShield, shieldRegen, shieldRegenDelay,
                buildableShips, basePackages, buildCost, buildTimeSeconds,
                fallbackCanRefit(id), fallbackRefitRange(id, unloadRange));
    }

    static boolean fallbackCanRefit(String id) {
        return "shipyard".equals(id) || "outpost".equals(id);
    }

    static double fallbackRefitRange(String id, double unloadRange) {
        if ("shipyard".equals(id)) return 520;
        if ("outpost".equals(id)) return 420;
        return unloadRange;
    }

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
             double maxShield, double shieldRegen, double shieldRegenDelay,
             List<String> buildableShips, List<String> basePackages, List<Cost> buildCost, double buildTimeSeconds,
             boolean canRefitShips, double refitRange) {
        this.id = id; this.name = name; this.maxHp = maxHp; this.unloadRange = unloadRange; this.unloadRate = unloadRate;
        this.buildRadius = buildRadius; this.maxShield = maxShield; this.shieldRegen = shieldRegen; this.shieldRegenDelay = shieldRegenDelay;
        this.buildableShips = buildableShips; this.basePackages = basePackages; this.buildCost = buildCost;
        this.buildTimeSeconds = Math.max(0, buildTimeSeconds);
        this.canRefitShips = canRefitShips;
        this.refitRange = Math.max(0, refitRange);
    }
}

final class ResourceBelt {
    final String name;
    final NodeKind kind;
    final List<Material> materials;
    final double orbit, width, arc, amount, harvestRate, radius;
    final int count;

    ResourceBelt(String name, NodeKind kind, List<Material> materials, double orbit, double width, double arc,
                 int count, double amount, double harvestRate, double radius) {
        this.name = name;
        this.kind = kind;
        this.materials = materials;
        this.orbit = orbit;
        this.width = width;
        this.arc = arc;
        this.count = count;
        this.amount = amount;
        this.harvestRate = harvestRate;
        this.radius = radius;
    }
}
