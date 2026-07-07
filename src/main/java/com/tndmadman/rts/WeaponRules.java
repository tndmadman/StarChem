package com.tndmadman.rts;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class WeaponRules {
    static final Map<String, WeaponType> WEAPONS = new LinkedHashMap<>();
    static final Map<String, List<String>> LOADOUTS = new LinkedHashMap<>();

    static {
        if (!loadExternal()) loadDefaults();
    }

    private WeaponRules() { }

    static List<WeaponType> loadout(ShipType ship) {
        List<String> ids = LOADOUTS.getOrDefault(ship.id, List.of());
        List<WeaponType> out = new ArrayList<>();
        for (String id : ids) {
            WeaponType weapon = WEAPONS.get(id);
            if (weapon != null) out.add(weapon);
        }
        return List.copyOf(out);
    }

    static boolean armed(ShipType ship) {
        for (WeaponType weapon : loadout(ship)) if (!weapon.screenWeapon) return true;
        return false;
    }

    static double maxRange(ShipType ship) {
        double max = 0;
        for (WeaponType weapon : loadout(ship)) {
            if (weapon.screenWeapon) continue;
            max = Math.max(max, weapon.range);
        }
        return max;
    }

    static WeaponVolley volley(ShipType ship, double distance) { return volley(ship, distance, false); }
    static WeaponVolley directVolley(ShipType ship, double distance) { return volley(ship, distance, true); }

    static List<WeaponType> movingWeapons(ShipType ship, double distance) {
        List<WeaponType> out = new ArrayList<>();
        for (WeaponType weapon : loadout(ship)) if (!weapon.screenWeapon && weapon.movingShot && distance <= weapon.range) out.add(weapon);
        return List.copyOf(out);
    }

    static List<WeaponType> screenWeapons(ShipType ship) {
        List<WeaponType> out = new ArrayList<>();
        for (WeaponType weapon : loadout(ship)) if (weapon.screenWeapon) out.add(weapon);
        return List.copyOf(out);
    }

    private static WeaponVolley volley(ShipType ship, double distance, boolean directOnly) {
        double damage = 0;
        double cooldown = 0;
        WeaponType visual = null;
        for (WeaponType weapon : loadout(ship)) {
            if (weapon.screenWeapon) continue;
            if (distance > weapon.range) continue;
            if (directOnly && weapon.movingShot) continue;
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
            WEAPONS.clear();
            LOADOUTS.clear();
            parseWeapons(readObject(Path.of(string(files, "weapons", "config/weapons.json"))));
            Object loadouts = files.getOrDefault("loadouts", List.of(
                    "config/loadouts/industry.json",
                    "config/loadouts/combat-line.json",
                    "config/loadouts/capitals.json",
                    "config/loadouts/megastructures.json"));
            if (loadouts instanceof List<?> list) for (Object path : list) parseLoadouts(readObject(Path.of(String.valueOf(path))));
            else parseLoadouts(readObject(Path.of(String.valueOf(loadouts))));
            return !WEAPONS.isEmpty();
        } catch (Exception ex) {
            System.err.println("Could not load weapon config: " + ex.getMessage());
            return false;
        }
    }

    private static void parseWeapons(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("weaponTypes", doc));
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> w = object(e.getValue());
            if (w.isEmpty()) continue;
            String id = e.getKey();
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
                    number(w, "tracking", 0.5)));
        }
    }

    private static void parseLoadouts(Map<String,Object> doc) {
        Map<String,Object> source = object(doc.getOrDefault("shipLoadouts", doc));
        for (Map.Entry<String,Object> e : source.entrySet()) LOADOUTS.put(e.getKey(), stringList(e.getValue()));
    }

    private static void loadDefaults() {
        WEAPONS.clear();
        LOADOUTS.clear();
        WEAPONS.put("point_defense_laser", new WeaponType("point_defense_laser", "Point Defense Laser", 360, 6, 0.25, true, color("#66e8ff"), false, true, false, 0, 1.0));
        WEAPONS.put("light_railgun", new WeaponType("light_railgun", "Light Railgun", 620, 18, 0.85, false, color("#9fdcff")));
        WEAPONS.put("heavy_cannon", new WeaponType("heavy_cannon", "Heavy Cannon", 780, 55, 1.6, false, color("#ffd17a")));
        WEAPONS.put("fighter_strike", new WeaponType("fighter_strike", "Fighter Strike", 920, 80, 2.4, false, color("#c77dff")));
        WEAPONS.put("capital_lance", new WeaponType("capital_lance", "Capital Lance", 1150, 220, 4.0, true, color("#ff5f55")));
        WEAPONS.put("siege_lance", new WeaponType("siege_lance", "Siege Lance", 1400, 420, 5.5, true, color("#ff9f1c")));
        WEAPONS.put("light_missile", new WeaponType("light_missile", "Light Missile", 820, 36, 1.8, false, color("#d7f7ff"), true, false, true, 430, 0.75));
        WEAPONS.put("torpedo", new WeaponType("torpedo", "Torpedo", 980, 115, 3.2, false, color("#ffb86b"), true, false, true, 310, 0.45));
        WEAPONS.put("capital_torpedo", new WeaponType("capital_torpedo", "Capital Torpedo", 1300, 260, 4.8, false, color("#ff6b35"), true, false, true, 250, 0.30));
        LOADOUTS.put("frigate", List.of("light_railgun"));
        LOADOUTS.put("destroyer", List.of("light_railgun", "light_missile", "point_defense_laser"));
        LOADOUTS.put("cruiser", List.of("light_railgun", "heavy_cannon", "light_missile", "torpedo"));
        LOADOUTS.put("battle_cruiser", List.of("heavy_cannon", "heavy_cannon", "torpedo"));
        LOADOUTS.put("battleship", List.of("heavy_cannon", "heavy_cannon", "torpedo", "torpedo", "point_defense_laser", "point_defense_laser"));
        LOADOUTS.put("carrier", List.of("fighter_strike", "point_defense_laser", "point_defense_laser"));
        LOADOUTS.put("dreadnought", List.of("capital_lance", "capital_torpedo", "heavy_cannon"));
        LOADOUTS.put("supercarrier", List.of("fighter_strike", "fighter_strike", "point_defense_laser", "point_defense_laser"));
        LOADOUTS.put("titan", List.of("capital_lance", "heavy_cannon", "heavy_cannon", "point_defense_laser", "point_defense_laser"));
        LOADOUTS.put("monolith", List.of("siege_lance", "capital_lance", "capital_lance", "point_defense_laser", "point_defense_laser", "point_defense_laser"));
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
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) for (Object v : list) out.add(String.valueOf(v));
        return List.copyOf(out);
    }

    private static Color color(String value) {
        try { return Color.decode(value); }
        catch (Exception ignored) { return new Color(0x9fdcff); }
    }
}

record WeaponVolley(double damage, double cooldownSeconds, WeaponType visualWeapon) { }
