#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected one exact match, found {text.count(old)}")
    return text.replace(old, new, 1)


def regex_once(text, pattern, replacement, label):
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, found {count}")
    return updated

# Explicit weapon fit metadata.
weapons_path = ROOT / "config/weapons.json"
weapons = json.loads(weapons_path.read_text(encoding="utf-8"))
metadata = {
    "point_defense_laser": {
        "compatibleHulls": ["destroyer", "cruiser", "battleship", "carrier", "dreadnought", "supercarrier", "titan", "monolith"],
        "requiresResearch": [],
        "installationCost": {"POINT_DEFENSE_LASER_ASSEMBLY": 1},
    },
    "light_railgun": {
        "compatibleHulls": ["frigate", "destroyer", "cruiser", "battle_cruiser", "battleship"],
        "requiresResearch": [],
        "installationCost": {"RAILGUN_ASSEMBLY": 1},
    },
    "heavy_cannon": {
        "compatibleHulls": ["cruiser", "battle_cruiser", "battleship", "dreadnought", "titan", "monolith"],
        "requiresResearch": [],
        "installationCost": {"HEAVY_CANNON_ASSEMBLY": 1},
    },
    "fighter_strike": {
        "compatibleHulls": ["carrier", "supercarrier"],
        "requiresResearch": [],
        "installationCost": {"FIGHTER_CONTROL_MODULE": 1},
    },
    "capital_lance": {
        "compatibleHulls": ["dreadnought", "titan", "monolith"],
        "requiresResearch": [],
        "installationCost": {"LANCE_FOCUSING_ARRAY": 1, "TARGETING_COMPUTER": 1},
    },
    "siege_lance": {
        "compatibleHulls": ["monolith"],
        "requiresResearch": [],
        "installationCost": {"LANCE_FOCUSING_ARRAY": 2, "TARGETING_COMPUTER": 2},
    },
    "light_missile": {
        "compatibleHulls": ["destroyer", "cruiser", "battle_cruiser", "battleship"],
        "requiresResearch": ["combat_doctrine"],
        "installationCost": {"MISSILE_GUIDANCE_PACKAGE": 1, "MISSILE_WARHEAD": 2},
    },
    "torpedo": {
        "compatibleHulls": ["cruiser", "battle_cruiser", "battleship"],
        "requiresResearch": [],
        "installationCost": {"TORPEDO_ASSEMBLY": 1, "MISSILE_GUIDANCE_PACKAGE": 1, "MISSILE_WARHEAD": 2},
    },
    "capital_torpedo": {
        "compatibleHulls": ["dreadnought"],
        "requiresResearch": ["battlefleet_engineering"],
        "installationCost": {"TORPEDO_ASSEMBLY": 2, "MISSILE_GUIDANCE_PACKAGE": 1, "MISSILE_WARHEAD": 3},
    },
}
for weapon_id, values in metadata.items():
    row = weapons["weaponTypes"][weapon_id]
    row.update(values)
weapons_path.write_text(json.dumps(weapons, indent=2) + "\n", encoding="utf-8")

# Explicit module compatibility/duplicate policy.
modules_path = ROOT / "config/modules.json"
modules = json.loads(modules_path.read_text(encoding="utf-8"))
mobile_hulls = [hull for hull, count in modules["hullUtilitySlots"].items() if int(count) > 0]
for row in modules["shipModules"].values():
    row["compatibleHulls"] = mobile_hulls
    row["allowDuplicates"] = False
modules_path.write_text(json.dumps(modules, indent=2) + "\n", encoding="utf-8")

# WeaponType stores authoritative fit rules.
write("src/main/java/com/tndmadman/rts/WeaponType.java", '''package com.tndmadman.rts;

import java.awt.Color;
import java.util.List;
import java.util.Set;

final class WeaponType {
    final String id, name;
    final double range, damage, cooldownSeconds, shotSpeed, tracking;
    final boolean beam, movingShot, screenWeapon, stoppable;
    final Color color;
    final Set<String> compatibleHulls;
    final Set<String> requiredResearch;
    final List<Cost> installationCost;

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color) {
        this(id, name, range, damage, cooldownSeconds, beam, color, false, false, false, 0, 0.5,
                Set.of(), Set.of(), List.of());
    }

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color,
               boolean movingShot, boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking) {
        this(id, name, range, damage, cooldownSeconds, beam, color, movingShot, screenWeapon, stoppable,
                shotSpeed, tracking, Set.of(), Set.of(), List.of());
    }

    WeaponType(String id, String name, double range, double damage, double cooldownSeconds, boolean beam, Color color,
               boolean movingShot, boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking,
               Set<String> compatibleHulls, Set<String> requiredResearch, List<Cost> installationCost) {
        this.id = id == null ? "" : id.trim();
        this.name = name == null || name.isBlank() ? this.id : name.trim();
        this.range = range;
        this.damage = damage;
        this.cooldownSeconds = cooldownSeconds;
        this.beam = beam;
        this.color = color;
        this.movingShot = movingShot;
        this.screenWeapon = screenWeapon;
        this.stoppable = stoppable;
        this.shotSpeed = shotSpeed;
        this.tracking = tracking;
        this.compatibleHulls = compatibleHulls == null ? Set.of() : Set.copyOf(compatibleHulls);
        this.requiredResearch = requiredResearch == null ? Set.of() : Set.copyOf(requiredResearch);
        this.installationCost = installationCost == null ? List.of() : List.copyOf(installationCost);
    }

    boolean compatibleWith(String hullId) {
        return hullId != null && compatibleHulls.contains(hullId);
    }
}
''')

weapon_rules = read("src/main/java/com/tndmadman/rts/WeaponRules.java")
old_parse = '''            WEAPONS.put(id, new WeaponType(
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
                    number(w, "tracking", 0.5)));
'''
new_parse = '''            if (!w.containsKey("compatibleHulls")) {
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
'''
weapon_rules = replace_once(weapon_rules, old_parse, new_parse, "WeaponRules.parseWeapons")

old_validate = '''            for (String weaponId : definition.weaponIds()) if (!WEAPONS.containsKey(weaponId)) {
                throw new RuleConfigurationException("Unknown weapon ID " + weaponId + " for loadout " + definition.id());
            }
            for (String topicId : definition.requiredResearch()) if (ResearchRules.topic(topicId) == null) {
'''
new_validate = '''            for (String weaponId : definition.weaponIds()) {
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
'''
weapon_rules = replace_once(weapon_rules, old_validate, new_validate, "WeaponRules authored compatibility")

insert_before_validate = '''    private static void validateAndIndex() {
        for (ShipLoadoutDefinition definition : SHIP_LOADOUTS.values()) {
'''
replacement_validate = '''    private static void validateAndIndex() {
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
'''
weapon_rules = replace_once(weapon_rules, insert_before_validate, replacement_validate, "WeaponRules metadata validation")

old_defaults = '''        WEAPONS.put("point_defense_laser", new WeaponType("point_defense_laser", "Point Defense Laser", 360, 6, 0.25, true, color("#66e8ff"), false, true, false, 0, 1.0));
        WEAPONS.put("light_railgun", new WeaponType("light_railgun", "Light Railgun", 620, 18, 0.85, false, color("#9fdcff")));
        WEAPONS.put("heavy_cannon", new WeaponType("heavy_cannon", "Heavy Cannon", 780, 55, 1.6, false, color("#ffd17a")));
        WEAPONS.put("fighter_strike", new WeaponType("fighter_strike", "Fighter Strike", 920, 80, 2.4, false, color("#c77dff")));
        WEAPONS.put("capital_lance", new WeaponType("capital_lance", "Capital Lance", 1150, 220, 4.0, true, color("#ff5f55")));
        WEAPONS.put("siege_lance", new WeaponType("siege_lance", "Siege Lance", 1400, 420, 5.5, true, color("#ff9f1c")));
        WEAPONS.put("light_missile", new WeaponType("light_missile", "Light Missile", 820, 36, 1.8, false, color("#d7f7ff"), true, false, true, 430, 0.75));
        WEAPONS.put("torpedo", new WeaponType("torpedo", "Torpedo", 980, 115, 3.2, false, color("#ffb86b"), true, false, true, 310, 0.45));
        WEAPONS.put("capital_torpedo", new WeaponType("capital_torpedo", "Capital Torpedo", 1300, 260, 4.8, false, color("#ff6b35"), true, false, true, 250, 0.30));
'''
new_defaults = '''        fallbackWeapon("point_defense_laser", "Point Defense Laser", 360, 6, 0.25, true, color("#66e8ff"), false, true, false, 0, 1.0,
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
'''
weapon_rules = replace_once(weapon_rules, old_defaults, new_defaults, "WeaponRules fallback metadata")

fallback_anchor = '''    private static void defaultDefinition(String hullId, List<String> weapons) {
'''
fallback_method = '''    private static void fallbackWeapon(String id, String name, double range, double damage,
                                       double cooldown, boolean beam, Color color, boolean movingShot,
                                       boolean screenWeapon, boolean stoppable, double shotSpeed, double tracking,
                                       Set<String> compatibleHulls, Set<String> requiredResearch,
                                       List<Cost> installationCost) {
        WEAPONS.put(id, new WeaponType(id, name, range, damage, cooldown, beam, color,
                movingShot, screenWeapon, stoppable, shotSpeed, tracking,
                compatibleHulls, requiredResearch, installationCost));
    }

    private static void defaultDefinition(String hullId, List<String> weapons) {
'''
weapon_rules = replace_once(weapon_rules, fallback_anchor, fallback_method, "WeaponRules fallback helper")
write("src/main/java/com/tndmadman/rts/WeaponRules.java", weapon_rules)

# Module rules become explicit and hull-authoritative.
module_rules = read("src/main/java/com/tndmadman/rts/ShipModuleRules.java")
module_rules = replace_once(module_rules,
'''        return MODULES.values().stream()
                .sorted(java.util.Comparator.comparing(ShipModuleDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
''',
'''        return MODULES.values().stream()
                .filter(module -> module.compatibleHulls().contains(hullId))
                .sorted(java.util.Comparator.comparing(ShipModuleDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
''', "ShipModuleRules.allowedModules")

old_module_validation = '''        Set<String> unique = new LinkedHashSet<>();
        for (String moduleId : clean) {
            if (!MODULES.containsKey(moduleId)) return Validation.reject("Unknown ship module: " + moduleId + ".");
            if (!unique.add(moduleId)) return Validation.reject("A ship cannot fit the same utility module twice.");
        }
'''
new_module_validation = '''        Set<String> unique = new LinkedHashSet<>();
        for (String moduleId : clean) {
            ShipModuleDefinition module = MODULES.get(moduleId);
            if (module == null) return Validation.reject("Unknown ship module: " + moduleId + ".");
            if (!module.compatibleHulls().contains(hullId)) {
                return Validation.reject("Ship module " + moduleId + " is not compatible with this hull.");
            }
            if (!module.allowDuplicates() && !unique.add(moduleId)) {
                return Validation.reject("A ship cannot fit the same utility module twice.");
            }
        }
'''
module_rules = replace_once(module_rules, old_module_validation, new_module_validation, "ShipModuleRules.validate")

old_module_ctor = '''                        new LinkedHashSet<>(strings(row.get("requiresResearch"))),
                        costs(row.get("installationCost")),
                        seed,
                        visualStyle,
                        color);
'''
new_module_ctor = '''                        declaredSet(row, "compatibleHulls", id, false),
                        declaredSet(row, "requiresResearch", id, true),
                        costsRequired(row, "installationCost", id),
                        requiredBoolean(row, "allowDuplicates", id),
                        seed,
                        visualStyle,
                        color);
'''
module_rules = replace_once(module_rules, old_module_ctor, new_module_ctor, "ShipModuleRules parsing")

old_definition_validation = '''    private static void validateDefinition(ShipModuleDefinition module) {
        if (module.kind() == ShipModuleKind.AFTERBURNER) {
'''
new_definition_validation = '''    private static void validateDefinition(ShipModuleDefinition module) {
        if (module.compatibleHulls().isEmpty()) {
            throw new RuleConfigurationException("Ship module " + module.id() + " has no compatible hulls.");
        }
        for (String hullId : module.compatibleHulls()) if (Rules.findShip(hullId) == null) {
            throw new RuleConfigurationException("Unknown compatible hull " + hullId + " for module " + module.id());
        }
        for (String topicId : module.requiredResearch()) if (ResearchRules.topic(topicId) == null) {
            throw new RuleConfigurationException("Unknown research ID " + topicId + " for module " + module.id());
        }
        if (module.installationCost().isEmpty()) {
            throw new RuleConfigurationException("Ship module " + module.id() + " must declare a positive installation cost.");
        }
        if (module.kind() == ShipModuleKind.AFTERBURNER) {
'''
module_rules = replace_once(module_rules, old_definition_validation, new_definition_validation, "ShipModuleRules metadata validation")

helper_anchor = '''    private static int strictInteger(Object value, String label) {
'''
helpers = '''    private static Set<String> declaredSet(Map<String,Object> row, String key, String moduleId,
                                           boolean allowEmpty) {
        if (!row.containsKey(key)) {
            throw new RuleConfigurationException("Missing " + key + " for ship module " + moduleId + ".");
        }
        Set<String> result = new LinkedHashSet<>(strings(row.get(key)));
        if (!allowEmpty && result.isEmpty()) {
            throw new RuleConfigurationException("Ship module " + moduleId + " must declare " + key + ".");
        }
        return Set.copyOf(result);
    }

    private static List<Cost> costsRequired(Map<String,Object> row, String key, String moduleId) {
        if (!row.containsKey(key)) {
            throw new RuleConfigurationException("Missing " + key + " for ship module " + moduleId + ".");
        }
        List<Cost> result = costs(row.get(key));
        if (result.isEmpty()) {
            throw new RuleConfigurationException("Ship module " + moduleId + " must declare a positive " + key + ".");
        }
        return result;
    }

    private static boolean requiredBoolean(Map<String,Object> row, String key, String moduleId) {
        Object value = row.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new RuleConfigurationException("Missing or invalid " + key + " for ship module " + moduleId + ".");
        }
        return flag;
    }

    private static int strictInteger(Object value, String label) {
'''
module_rules = replace_once(module_rules, helper_anchor, helpers, "ShipModuleRules metadata helpers")

module_rules = regex_once(module_rules,
    r'''record ShipModuleDefinition\(String id, String displayName, String description, ShipModuleKind kind,\n\s*double activationDistance, double range, double speedMultiplier,\n\s*double agilityMultiplier, double jumpDistance, double cooldownSeconds,\n\s*Set<String> requiredResearch, List<Cost> installationCost,\n\s*int seed, ShipModuleVisualStyle visualStyle, Color color\) \{.*?\n\}''',
'''record ShipModuleDefinition(String id, String displayName, String description, ShipModuleKind kind,
                            double activationDistance, double range, double speedMultiplier,
                            double agilityMultiplier, double jumpDistance, double cooldownSeconds,
                            Set<String> compatibleHulls, Set<String> requiredResearch,
                            List<Cost> installationCost, boolean allowDuplicates,
                            int seed, ShipModuleVisualStyle visualStyle, Color color) {
    ShipModuleDefinition {
        id = id == null ? "" : id.trim();
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        description = description == null ? "" : description.trim();
        activationDistance = Math.max(0, activationDistance);
        range = Math.max(0, range);
        speedMultiplier = Math.max(1, speedMultiplier);
        agilityMultiplier = Calc.clamp(agilityMultiplier, 0.05, 1.0);
        jumpDistance = Math.max(0, jumpDistance);
        cooldownSeconds = Math.max(0, cooldownSeconds);
        compatibleHulls = compatibleHulls == null ? Set.of() : Set.copyOf(compatibleHulls);
        requiredResearch = requiredResearch == null ? Set.of() : Set.copyOf(requiredResearch);
        installationCost = installationCost == null ? List.of() : List.copyOf(installationCost);
        if (seed == 0) throw new IllegalArgumentException("Module seed must be non-zero.");
        visualStyle = visualStyle == null ? ShipModuleVisualStyle.JUMP_CORE : visualStyle;
        color = color == null ? new Color(0x72D8FF) : color;
    }
}''', "ShipModuleDefinition record")
write("src/main/java/com/tndmadman/rts/ShipModuleRules.java", module_rules)

# Dynamic fits derive only from selected configured components.
fit_rules = read("src/main/java/com/tndmadman/rts/ShipFitSpec.java")
fit_rules = regex_once(fit_rules,
    r'''    static Set<String> allowedWeaponIds\(String hullId\) \{.*?\n    \}\n\n    static Set<String> requiredResearch''',
'''    static Set<String> allowedWeaponIds(String hullId) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (WeaponType weapon : WeaponRules.WEAPONS.values()) {
            if (weapon.compatibleWith(hullId)) out.add(weapon.id);
        }
        return Set.copyOf(out);
    }

    static Set<String> requiredResearch''', "PlayerFitRules.allowedWeaponIds")
fit_rules = regex_once(fit_rules,
    r'''    static Set<String> requiredResearch\(ShipFitSpec spec\) \{.*?\n    \}\n\n    static List<Cost> buildPremium''',
'''    static Set<String> requiredResearch(ShipFitSpec spec) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (spec == null) return Set.of();
        for (String weaponId : spec.weaponIds()) {
            WeaponType weapon = WeaponRules.WEAPONS.get(weaponId);
            if (weapon != null) out.addAll(weapon.requiredResearch);
        }
        out.addAll(ShipModuleRules.requiredResearch(spec.moduleIds()));
        return Set.copyOf(out);
    }

    static List<Cost> buildPremium''', "PlayerFitRules.requiredResearch")
fit_rules = regex_once(fit_rules,
    r'''    static List<Cost> installationCost\(ShipFitSpec spec\) \{.*?\n    \}\n\n    private static List<Cost> componentCost\(String weaponId\) \{.*?\n    \}\n\n    static double refitTimeSeconds''',
'''    static List<Cost> installationCost(ShipFitSpec spec) {
        EnumMap<Material,Double> total = new EnumMap<>(Material.class);
        if (spec != null) {
            for (String weaponId : spec.weaponIds()) {
                WeaponType weapon = WeaponRules.WEAPONS.get(weaponId);
                if (weapon == null) continue;
                for (Cost cost : weapon.installationCost) {
                    total.merge(cost.material(), cost.amount(), Double::sum);
                }
            }
            for (Cost cost : ShipModuleRules.installationCost(spec.moduleIds())) {
                total.merge(cost.material(), cost.amount(), Double::sum);
            }
        }
        List<Cost> out = new ArrayList<>();
        for (Map.Entry<Material,Double> entry : total.entrySet()) {
            if (entry.getValue() > 0) out.add(new Cost(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    static double refitTimeSeconds''', "PlayerFitRules configured costs")
write("src/main/java/com/tndmadman/rts/ShipFitSpec.java", fit_rules)

# Focused validation for issue #329 and configured half of #330.
write("src/main/java/com/tndmadman/rts/ConfiguredFitRuleValidator.java", '''package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class ConfiguredFitRuleValidator {
    private ConfiguredFitRuleValidator() { }

    public static void main(String[] args) {
        for (WeaponType weapon : WeaponRules.WEAPONS.values()) {
            require(!weapon.compatibleHulls.isEmpty(), weapon.id + " lacks compatible hulls");
            require(!weapon.installationCost.isEmpty(), weapon.id + " lacks configured installation cost");
            for (String hullId : weapon.compatibleHulls) {
                require(Rules.findShip(hullId) != null, weapon.id + " references unknown hull " + hullId);
            }
            for (String topicId : weapon.requiredResearch) {
                require(ResearchRules.topic(topicId) != null, weapon.id + " references unknown research " + topicId);
            }
        }
        for (ShipModuleDefinition module : ShipModuleRules.MODULES.values()) {
            require(!module.compatibleHulls().isEmpty(), module.id() + " lacks compatible hulls");
            require(!module.installationCost().isEmpty(), module.id() + " lacks installation cost");
        }

        ShipFitSpec mixed = new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_missile", "point_defense_laser"),
                List.of("micro_jump_drive"));
        PlayerFitRules.Validation mixedValidation = PlayerFitRules.validate(mixed);
        require(mixedValidation.valid(), "mixed configured fit rejected: " + mixedValidation.reason());
        require(PlayerFitRules.requiredResearch(mixed).equals(Set.of("combat_doctrine", "battlefleet_engineering")),
                "mixed component research aggregation is not exact");

        ShipFitSpec repeatedPresetWeapon = new ShipFitSpec("cruiser",
                List.of("light_missile", "light_missile", "torpedo"), List.of());
        require(PlayerFitRules.requiredResearch(repeatedPresetWeapon).equals(Set.of("combat_doctrine")),
                "weapon research still depends on authored preset membership");

        PlayerFitRules.Validation incompatible = PlayerFitRules.validate(
                new ShipFitSpec("frigate", List.of("capital_lance"), List.of()));
        require(!incompatible.valid() && incompatible.reason().contains("not compatible"),
                "incompatible hull/weapon pair was not rejected precisely");

        require(WeaponRules.findLoadout("frigate").requiredResearch().isEmpty(),
                "legacy frigate authored unlock changed");
        require(WeaponRules.findLoadout("destroyer").requiredResearch().contains("combat_doctrine"),
                "legacy destroyer authored unlock changed");
        require(WeaponRules.findLoadout("dreadnought").requiredResearch().contains("battlefleet_engineering"),
                "legacy dreadnought authored unlock changed");

        List<Cost> missileCost = WeaponRules.WEAPONS.get("light_missile").installationCost;
        require(missileCost.stream().anyMatch(cost -> cost.material() == Material.MISSILE_GUIDANCE_PACKAGE),
                "configured missile cost missing guidance package");
        require(missileCost.stream().anyMatch(cost -> cost.material() == Material.MISSILE_WARHEAD),
                "configured missile cost missing warheads");

        System.out.println("StarChem configured ship-fit compatibility, research, and cost validation passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
''')

print("Applied issue #289 phase 1 explicit fit-rule hardening.")
