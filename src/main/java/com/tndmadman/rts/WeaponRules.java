package com.tndmadman.rts;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class WeaponRules {
    static final Map<String, WeaponType> WEAPONS = new LinkedHashMap<>();
    /** Legacy hull-to-default-weapon view retained for compatibility and diagnostics. */
    static final Map<String, List<String>> LOADOUTS = new LinkedHashMap<>();
    static final Map<String, ShipLoadoutDefinition> SHIP_LOADOUTS = new LinkedHashMap<>();
    private static final Map<String, List<ShipLoadoutDefinition>> BY_HULL = new LinkedHashMap<>();
    private static final Map<String, String> DEFAULT_BY_HULL = new LinkedHashMap<>();

    static {
        if (!loadExternal()) loadDefaults();
    }

    private WeaponRules() { }

    static ShipLoadoutDefinition findLoadout(String id) {
        return id == null ? null : SHIP_LOADOUTS.get(id);
    }

    static ShipLoadoutDefinition defaultLoadout(String hullId) {
        if (hullId == null) return null;
        String id = DEFAULT_BY_HULL.get(hullId);
        ShipLoadoutDefinition found = id == null ? null : SHIP_LOADOUTS.get(id);
        if (found != null) return found;
        List<ShipLoadoutDefinition> variants = BY_HULL.getOrDefault(hullId, List.of());
        return variants.isEmpty() ? null : variants.get(0);
    }

    static String defaultLoadoutId(String hullId) {
        ShipLoadoutDefinition definition = defaultLoadout(hullId);
        return definition == null ? (hullId == null ? "" : hullId) : definition.id();
    }

    static List<ShipLoadoutDefinition> loadoutsForHull(String hullId) {
        return BY_HULL.getOrDefault(hullId, List.of());
    }

    static ShipLoadoutDefinition resolveForHull(String hullId, String requestedId) {
        ShipLoadoutDefinition requested = findLoadout(requestedId);
        if (requested != null && requested.hullId().equals(hullId)) return requested;
        return defaultLoadout(hullId);
    }

    static boolean unlocked(World world, String playerId, ShipLoadoutDefinition loadout) {
        if (loadout == null) return false;
        if (world == null || world.devFreeBuildFor(playerId)) return true;
        for (String topic : loadout.requiredResearch()) if (!world.hasResearch(playerId, topic)) return false;
        return true;
    }

    static String missingResearchLabel(World world, String playerId, ShipLoadoutDefinition loadout) {
        if (loadout == null) return "unknown loadout";
        List<String> labels = new ArrayList<>();
        for (String id : loadout.requiredResearch()) {
            if (world != null && world.hasResearch(playerId, id)) continue;
            ResearchTopic topic = ResearchRules.topic(id);
            labels.add(topic == null ? id : topic.name);
        }
        return String.join(", ", labels);
    }

    static List<Cost> buildCost(ShipType ship, ShipLoadoutDefinition loadout) {
        if (ship == null) return List.of();
        return mergeCosts(ship.buildCost, loadout == null ? List.of() : loadout.buildCost());
    }

    static List<Cost> refitCost(ShipLoadoutDefinition loadout) {
        return loadout == null ? List.of() : loadout.refitCost();
    }

    static List<WeaponType> loadout(ShipType ship) {
        return ship == null ? List.of() : loadout(defaultLoadout(ship.id));
    }

    static List<WeaponType> loadout(Unit unit) {
        if (unit == null) return List.of();
        return loadout(resolveForHull(unit.shipTypeId, unit.loadoutId));
    }

    static List<WeaponType> loadout(ShipLoadoutDefinition loadout) {
        if (loadout == null) return List.of();
        List<WeaponType> out = new ArrayList<>();
        for (String id : loadout.weaponIds()) {
            WeaponType weapon = WEAPONS.get(id);
            if (weapon != null) out.add(weapon);
        }
        return List.copyOf(out);
    }

    static boolean armed(Unit unit) { return armed(loadout(unit)); }
    static boolean armed(ShipType ship) { return armed(loadout(ship)); }
    static boolean armed(ShipLoadoutDefinition loadout) { return armed(loadout(loadout)); }

    private static boolean armed(List<WeaponType> weapons) {
        for (WeaponType weapon : weapons) if (!weapon.screenWeapon) return true;
        return false;
    }

    static double maxRange(Unit unit) { return maxRange(loadout(unit)); }
    static double maxRange(ShipType ship) { return maxRange(loadout(ship)); }
    static double maxRange(ShipLoadoutDefinition loadout) { return maxRange(loadout(loadout)); }

    private static double maxRange(List<WeaponType> weapons) {
        double max = 0;
        for (WeaponType weapon : weapons) {
            if (weapon.screenWeapon) continue;
            max = Math.max(max, weapon.range);
        }
        return max;
    }

    static double maxCooldown(Unit unit) {
        double max = 0;
        for (WeaponType weapon : loadout(unit)) max = Math.max(max, weapon.cooldownSeconds);
        return max;
    }

    static WeaponVolley volley(Unit unit, double distance) { return volley(loadout(unit), distance, false); }
    static WeaponVolley volley(ShipType ship, double distance) { return volley(loadout(ship), distance, false); }
    static WeaponVolley directVolley(Unit unit, double distance) { return volley(loadout(unit), distance, true); }
    static WeaponVolley directVolley(ShipType ship, double distance) { return volley(loadout(ship), distance, true); }

    static List<WeaponType> movingWeapons(Unit unit, double distance) { return movingWeapons(loadout(unit), distance); }
    static List<WeaponType> movingWeapons(ShipType ship, double distance) { return movingWeapons(loadout(ship), distance); }

    private static List<WeaponType> movingWeapons(List<WeaponType> weapons, double distance) {
        List<WeaponType> out = new ArrayList<>();
        for (WeaponType weapon : weapons) if (!weapon.screenWeapon && weapon.movingShot && distance <= weapon.range) out.add(weapon);
        return List.copyOf(out);
    }

    static List<WeaponType> screenWeapons(Unit unit) { return screenWeapons(loadout(unit)); }
    static List<WeaponType> screenWeapons(ShipType ship) { return screenWeapons(loadout(ship)); }

    private static List<WeaponType> screenWeapons(List<WeaponType> weapons) {
        List<WeaponType> out = new ArrayList<>();
        for (WeaponType weapon : weapons) if (weapon.screenWeapon) out.add(weapon);
        return List.copyOf(out);
    }

    private static WeaponVolley volley(List<WeaponType> weapons, double distance, boolean directOnly) {
        double damage = 0;
        double cooldown = 0;
        WeaponType visual = null;
        for (WeaponType weapon : weapons) {
            if (weapon.screenWeapon || distance > weapon.range || directOnly && weapon.movingShot) continue;
            damage += weapon.damage;
            cooldown = Math.max(cooldown, weapon.cooldownSeconds);
            if (visual == null || weapon.damage > visual.damage) visual = weapon;
        }
        return new WeaponVolley(damage, Math.max(0.2, cooldown), visual);
    }

    private static boolean loadExternal() {
        try {
            if (!Files.exists(Path.of("config/starchem.json"))) return false;
            Map<String,Object> root = readObject(Path.of("config/starchem.json"));
            Map<String,Object> files = object(root.get("files"));
            clear();
            parseWeapons(readObject(Path.of(string(files, "weapons", "config/weapons.json"))));
            Object loadouts = files.getOrDefault("loadouts", List.of(
                    "config/loadouts/industry.json",
                    "config/loadouts/combat-line.json",
                    "config/loadouts/capitals.json",
                    "config/loadouts/megastructures.json"));
            if (loadouts instanceof List<?> list) for (Object path : list) parseLoadouts(readObject(Path.of(String.valueOf(path))));
            else parseLoadouts(readObject(Path.of(String.valueOf(loadouts))));
            validateAndIndex();
            return !WEAPONS.isEmpty();
        } catch (RuleConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            System.err.println("Could not load weapon config: " + ex.getMessage());
            return false;
        }
    }

    private static void clear() {
        WEAPONS.clear();
        LOADOUTS.clear();
        SHIP_LOADOUTS.clear();
        BY_HULL.clear();
        DEFAULT_BY_HULL.clear();
        ShipModuleRules.clearLoadouts();
    }

    private static void parseWeapons(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("weaponTypes", doc));
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> w = object(e.getValue());
            if (w.isEmpty()) continue;
            String id = e.getKey();
            if (!w.containsKey("compatibleHulls")) {
                throw new RuleConfigurationException("Missing compatibleHulls for configurable weapon " + id + ".");
            }
            if (!w.containsKey("requiresResearch")) {
                throw new RuleConfigurationException("Missing requiresResearch for configurable weapon " + id + ".");
            }
            if (!w.containsKey("installationCost")) {
                throw new RuleConfigurationException("Missing installationCost for configurable weapon " + id + ".");
            }
            Set<String> compatibleHulls = new LinkedHashSet<>(stringList(w.get("compatibleHulls")));
            Set<String> requiredResearch = new LinkedHashSet<>(stringList(w.get("requiresResearch")));
            List<Cost> installationCost = costs(w.get("installationCost"));
            if (compatibleHulls.isEmpty()) {
                throw new RuleConfigurationException("Configurable weapon " + id + " must declare at least one compatible hull.");
            }
            if (installationCost.isEmpty()) {
                throw new RuleConfigurationException("Configurable weapon " + id + " must declare a positive installation cost.");
            }
            WEAPONS.put(id, new WeaponType(
                    id,
                    string(w, "displayName", id),
                    number(w, "range", 400),
                    number(w, "damage", 10),
                    number(w, "cooldownSeconds", 1),
                    bool(w, "beam", false),
                    color(string(w, "color", "#9fdcff")),
                    bool(w, "movingShot", false),
                    bool(w, "screenWeapon", false),
                    bool(w, "stoppable", false),
                    number(w, "shotSpeed", 0),
                    number(w, "tracking", 0.5),
                    compatibleHulls,
                    requiredResearch,
                    installationCost));
        }
    }

    private static void parseLoadouts(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("shipLoadouts", doc));
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            String id = entry.getKey();
            Object raw = entry.getValue();
            if (raw instanceof List<?>) {
                register(new ShipLoadoutDefinition(id, title(id), id, stringList(raw), Set.of(),
                        List.of(), List.of(), 12, true));
                ShipModuleRules.registerLoadout(id, List.of());
                continue;
            }
            Map<String,Object> row = object(raw);
            if (row.isEmpty()) continue;
            String hullId = string(row, "hullId", id);
            register(new ShipLoadoutDefinition(
                    id,
                    string(row, "displayName", title(id)),
                    hullId,
                    stringList(row.get("weapons")),
                    new LinkedHashSet<>(stringList(row.get("requiresResearch"))),
                    costs(row.get("buildCost")),
                    costs(row.get("refitCost")),
                    number(row, "refitTimeSeconds", 12),
                    bool(row, "default", id.equals(hullId))));
            ShipModuleRules.registerLoadout(id, stringList(row.get("modules")));
        }
    }

    private static void register(ShipLoadoutDefinition definition) {
        if (definition.id().isBlank()) throw new RuleConfigurationException("Ship loadout ID is blank.");
        if (SHIP_LOADOUTS.putIfAbsent(definition.id(), definition) != null) {
            throw new RuleConfigurationException("Duplicate ship loadout ID: " + definition.id());
        }
    }

    private static void validateAndIndex() {
        for (WeaponType weapon : WEAPONS.values()) {
            if (weapon.compatibleHulls.isEmpty()) {
                throw new RuleConfigurationException("Configurable weapon " + weapon.id + " has no compatible hulls.");
            }
            if (weapon.installationCost.isEmpty()) {
                throw new RuleConfigurationException("Configurable weapon " + weapon.id + " has no installation cost.");
            }
            for (String hullId : weapon.compatibleHulls) if (Rules.findShip(hullId) == null) {
                throw new RuleConfigurationException("Unknown compatible hull " + hullId + " for weapon " + weapon.id);
            }
            for (String topicId : weapon.requiredResearch) if (ResearchRules.topic(topicId) == null) {
                throw new RuleConfigurationException("Unknown research ID " + topicId + " for weapon " + weapon.id);
            }
        }
        for (ShipLoadoutDefinition definition : SHIP_LOADOUTS.values()) {
            if (Rules.findShip(definition.hullId()) == null) {
                throw new RuleConfigurationException("Unknown hull ID " + definition.hullId() + " for loadout " + definition.id());
            }
            for (String weaponId : definition.weaponIds()) {
                WeaponType weapon = WEAPONS.get(weaponId);
                if (weapon == null) {
                    throw new RuleConfigurationException("Unknown weapon ID " + weaponId + " for loadout " + definition.id());
                }
                if (!weapon.compatibleWith(definition.hullId())) {
                    throw new RuleConfigurationException("Weapon " + weaponId + " is not compatible with hull "
                            + definition.hullId() + " in loadout " + definition.id());
                }
            }
            for (String topicId : definition.requiredResearch()) if (ResearchRules.topic(topicId) == null) {
                throw new RuleConfigurationException("Unknown research ID " + topicId + " for loadout " + definition.id());
            }
            BY_HULL.computeIfAbsent(definition.hullId(), ignored -> new ArrayList<>()).add(definition);
            if (definition.defaultForHull()) {
                String previous = DEFAULT_BY_HULL.putIfAbsent(definition.hullId(), definition.id());
                if (previous != null) throw new RuleConfigurationException("Multiple default loadouts for hull " + definition.hullId());
            }
        }
        for (String hullId : Rules.SHIPS.keySet()) {
            List<ShipLoadoutDefinition> variants = BY_HULL.get(hullId);
            if (variants == null || variants.isEmpty()) {
                ShipLoadoutDefinition empty = new ShipLoadoutDefinition(hullId, title(hullId), hullId,
                        List.of(), Set.of(), List.of(), List.of(), 12, true);
                SHIP_LOADOUTS.put(hullId, empty);
                ShipModuleRules.registerLoadout(hullId, List.of());
                BY_HULL.put(hullId, new ArrayList<>(List.of(empty)));
                DEFAULT_BY_HULL.put(hullId, hullId);
                variants = BY_HULL.get(hullId);
            }
            if (!DEFAULT_BY_HULL.containsKey(hullId)) {
                throw new RuleConfigurationException("No default loadout configured for hull " + hullId);
            }
            variants.sort(Comparator.comparing((ShipLoadoutDefinition value) -> !value.defaultForHull())
                    .thenComparing(ShipLoadoutDefinition::displayName));
            BY_HULL.put(hullId, List.copyOf(variants));
            LOADOUTS.put(hullId, defaultLoadout(hullId).weaponIds());
        }
    }

    private static List<Cost> mergeCosts(List<Cost> first, List<Cost> second) {
        EnumMap<Material, Double> totals = new EnumMap<>(Material.class);
        if (first != null) for (Cost cost : first) totals.merge(cost.material(), cost.amount(), Double::sum);
        if (second != null) for (Cost cost : second) totals.merge(cost.material(), cost.amount(), Double::sum);
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material, Double> entry : totals.entrySet()) if (entry.getValue() > 0) {
            out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    private static void loadDefaults() {
        clear();
        fallbackWeapon("point_defense_laser", "Point Defense Laser", 360, 6, 0.25, true, color("#66e8ff"), false, true, false, 0, 1.0,
                Set.of("destroyer", "cruiser", "battleship", "carrier", "dreadnought", "supercarrier", "titan", "monolith"), Set.of(), List.of(new Cost(Material.POINT_DEFENSE_LASER_ASSEMBLY, 1)));
        fallbackWeapon("light_railgun", "Light Railgun", 620, 18, 0.85, false, color("#9fdcff"), false, false, false, 0, 0.5,
                Set.of("frigate", "destroyer", "cruiser", "battle_cruiser", "battleship"), Set.of(), List.of(new Cost(Material.RAILGUN_ASSEMBLY, 1)));
        fallbackWeapon("heavy_cannon", "Heavy Cannon", 780, 55, 1.6, false, color("#ffd17a"), false, false, false, 0, 0.5,
                Set.of("cruiser", "battle_cruiser", "battleship", "dreadnought", "titan", "monolith"), Set.of(), List.of(new Cost(Material.HEAVY_CANNON_ASSEMBLY, 1)));
        fallbackWeapon("fighter_strike", "Fighter Strike", 920, 80, 2.4, false, color("#c77dff"), false, false, false, 0, 0.5,
                Set.of("carrier", "supercarrier"), Set.of(), List.of(new Cost(Material.FIGHTER_CONTROL_MODULE, 1)));
        fallbackWeapon("capital_lance", "Capital Lance", 1150, 220, 4.0, true, color("#ff5f55"), false, false, false, 0, 0.5,
                Set.of("dreadnought", "titan", "monolith"), Set.of(), List.of(new Cost(Material.LANCE_FOCUSING_ARRAY, 1), new Cost(Material.TARGETING_COMPUTER, 1)));
        fallbackWeapon("siege_lance", "Siege Lance", 1400, 420, 5.5, true, color("#ff9f1c"), false, false, false, 0, 0.5,
                Set.of("monolith"), Set.of(), List.of(new Cost(Material.LANCE_FOCUSING_ARRAY, 2), new Cost(Material.TARGETING_COMPUTER, 2)));
        fallbackWeapon("light_missile", "Light Missile", 820, 36, 1.8, false, color("#d7f7ff"), true, false, true, 430, 0.75,
                Set.of("destroyer", "cruiser", "battle_cruiser", "battleship"), Set.of("combat_doctrine"), List.of(new Cost(Material.MISSILE_GUIDANCE_PACKAGE, 1), new Cost(Material.MISSILE_WARHEAD, 2)));
        fallbackWeapon("torpedo", "Torpedo", 980, 115, 3.2, false, color("#ffb86b"), true, false, true, 310, 0.45,
                Set.of("cruiser", "battle_cruiser", "battleship"), Set.of(), List.of(new Cost(Material.TORPEDO_ASSEMBLY, 1), new Cost(Material.MISSILE_GUIDANCE_PACKAGE, 1), new Cost(Material.MISSILE_WARHEAD, 2)));
        fallbackWeapon("capital_torpedo", "Capital Torpedo", 1300, 260, 4.8, false, color("#ff6b35"), true, false, true, 250, 0.30,
                Set.of("dreadnought"), Set.of("battlefleet_engineering"), List.of(new Cost(Material.TORPEDO_ASSEMBLY, 2), new Cost(Material.MISSILE_GUIDANCE_PACKAGE, 1), new Cost(Material.MISSILE_WARHEAD, 3)));
        defaultDefinition("frigate", List.of("light_railgun"));
        defaultDefinition("destroyer", List.of("light_railgun", "light_missile", "point_defense_laser"));
        defaultDefinition("cruiser", List.of("light_railgun", "heavy_cannon", "light_missile", "torpedo"));
        defaultDefinition("battle_cruiser", List.of("heavy_cannon", "heavy_cannon", "torpedo"));
        defaultDefinition("battleship", List.of("heavy_cannon", "heavy_cannon", "torpedo", "torpedo", "point_defense_laser", "point_defense_laser"));
        defaultDefinition("carrier", List.of("fighter_strike", "point_defense_laser", "point_defense_laser"));
        defaultDefinition("dreadnought", List.of("capital_lance", "capital_torpedo", "heavy_cannon"));
        defaultDefinition("supercarrier", List.of("fighter_strike", "fighter_strike", "point_defense_laser", "point_defense_laser"));
        defaultDefinition("titan", List.of("capital_lance", "heavy_cannon", "heavy_cannon", "point_defense_laser", "point_defense_laser"));
        defaultDefinition("monolith", List.of("siege_lance", "capital_lance", "capital_lance", "point_defense_laser", "point_defense_laser", "point_defense_laser"));
        validateAndIndex();
    }

    private static void fallbackWeapon(String id, String name, double range, double damage,
                                       double cooldown, boolean beam, Color color, boolean movingShot,
                                       boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking,
                                       Set<String> compatibleHulls, Set<String> requiredResearch,
                                       List<Cost> installationCost) {
        WEAPONS.put(id, new WeaponType(id, name, range, damage, cooldown, beam, color,
                movingShot, screenWeapon, stoppable, shotSpeed, tracking,
                compatibleHulls, requiredResearch, installationCost));
    }

    private static void defaultDefinition(String hullId, List<String> weapons) {
        register(new ShipLoadoutDefinition(hullId, title(hullId), hullId, weapons, Set.of(),
                List.of(), List.of(), 12, true));
        ShipModuleRules.registerLoadout(hullId, List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return (Map<String,Object>) map;
        return Map.of();
    }

    private static Map<String,Object> readObject(Path path) throws IOException {
        return object(MiniJson.parse(Files.readString(path)));
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key);
        double parsed = value instanceof Number number ? number.doubleValue() : fallback;
        if (!Double.isFinite(parsed) || parsed < 0) throw new RuleConfigurationException("Invalid " + key + " value.");
        return parsed;
    }

    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) {
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) out.add(text);
        }
        return List.copyOf(out);
    }

    private static List<Cost> costs(Object value) {
        Map<String,Object> source = object(value);
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            Material material;
            try { material = Material.valueOf(entry.getKey()); }
            catch (RuntimeException ex) { throw new RuleConfigurationException("Unknown loadout material: " + entry.getKey()); }
            double amount = entry.getValue() instanceof Number number ? number.doubleValue() : -1;
            if (!Double.isFinite(amount) || amount < 0) throw new RuleConfigurationException("Invalid loadout cost for " + material.name());
            if (amount > 0) out.add(new Cost(material, amount));
        }
        return List.copyOf(out);
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "Loadout";
        String[] words = value.split("[_-]");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static Color color(String value) {
        try { return Color.decode(value); }
        catch (Exception ignored) { return new Color(0x9fdcff); }
    }
}

record ShipLoadoutDefinition(String id, String displayName, String hullId, List<String> weaponIds,
                             Set<String> requiredResearch, List<Cost> buildCost, List<Cost> refitCost,
                             double refitTimeSeconds, boolean defaultForHull) {
    ShipLoadoutDefinition {
        id = id == null ? "" : id.trim();
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        hullId = hullId == null ? "" : hullId.trim();
        weaponIds = weaponIds == null ? List.of() : List.copyOf(weaponIds);
        requiredResearch = requiredResearch == null ? Set.of() : Set.copyOf(requiredResearch);
        buildCost = buildCost == null ? List.of() : List.copyOf(buildCost);
        refitCost = refitCost == null ? List.of() : List.copyOf(refitCost);
        refitTimeSeconds = Math.max(0, refitTimeSeconds);
    }
}

record WeaponVolley(double damage, double cooldownSeconds, WeaponType visualWeapon) { }
