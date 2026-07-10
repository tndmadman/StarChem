package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class CraftingRules {
    private static final Map<String, CraftableItem> ITEMS = load();

    private CraftingRules() { }

    static CraftableItem item(String id) {
        return ITEMS.get(id);
    }

    static List<CraftableItem> forStation(String stationTypeId) {
        List<CraftableItem> out = new ArrayList<>();
        for (CraftableItem item : ITEMS.values()) if (item.canCraftAt(stationTypeId)) out.add(item);
        return List.copyOf(out);
    }

    private static Map<String, CraftableItem> load() {
        Map<String, CraftableItem> out = new LinkedHashMap<>();
        try {
            Object files = null;
            if (Files.exists(Path.of("config/starchem.json"))) {
                Map<String,Object> root = readObject(Path.of("config/starchem.json"));
                files = object(root.get("files")).get("craftables");
            }
            if (files != null) parseCraftableFiles(files, out);
            else if (Files.exists(Path.of("config/craftables/fuel.json"))) parseCraftables(readObject(Path.of("config/craftables/fuel.json")), out);
        } catch (Exception ex) {
            System.err.println("Could not load craftables: " + ex.getMessage());
        }
        if (out.isEmpty()) {
            out.put("fuel", new CraftableItem(
                    "fuel",
                    "Fuel",
                    "Refined reactor fuel for laboratory operation.",
                    "fuel_cell",
                    "#FFB347",
                    List.of("manufacturing"),
                    List.of(new Cost(Material.HYDROGEN, 30), new Cost(Material.HELIUM, 10), new Cost(Material.METHANE, 12)),
                    Material.FUEL,
                    50,
                    12));
        }
        return Collections.unmodifiableMap(out);
    }

    private static void parseCraftableFiles(Object fileValue, Map<String, CraftableItem> out) throws IOException {
        if (fileValue instanceof List<?> list) {
            for (Object item : list) parseCraftables(readObject(Path.of(String.valueOf(item))), out);
        } else {
            parseCraftables(readObject(Path.of(String.valueOf(fileValue))), out);
        }
    }

    private static void parseCraftables(Map<String,Object> doc, Map<String, CraftableItem> out) {
        Map<String,Object> source = object(doc.getOrDefault("craftableItems", doc));
        for (Map.Entry<String,Object> e : source.entrySet()) {
            Map<String,Object> c = object(e.getValue());
            if (c.isEmpty()) continue;
            Map<String,Object> output = object(c.get("output"));
            Material outputMaterial = material(string(output, "material", string(c, "outputMaterial", "FUEL")));
            double outputAmount = number(output, "amount", number(c, "outputAmount", 1));
            out.put(e.getKey(), new CraftableItem(
                    e.getKey(),
                    string(c, "displayName", e.getKey()),
                    string(c, "description", ""),
                    string(c, "style", "industrial"),
                    string(c, "color", "#FFB347"),
                    stringList(c.getOrDefault("stationTypes", c.get("stations"))),
                    costs(c.getOrDefault("requiredResources", c.get("input"))),
                    outputMaterial,
                    outputAmount,
                    number(c, "timeSeconds", 10)));
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

final class CraftableItem {
    final String id, name, description, style, color;
    final List<String> stationTypes;
    final List<Cost> requiredResources;
    final Material outputMaterial;
    final double outputAmount, timeSeconds;

    CraftableItem(String id, String name, String description, String style, String color, List<String> stationTypes,
                  List<Cost> requiredResources, Material outputMaterial, double outputAmount) {
        this(id, name, description, style, color, stationTypes, requiredResources, outputMaterial, outputAmount, 10);
    }

    CraftableItem(String id, String name, String description, String style, String color, List<String> stationTypes,
                  List<Cost> requiredResources, Material outputMaterial, double outputAmount, double timeSeconds) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.style = style;
        this.color = color;
        this.stationTypes = List.copyOf(stationTypes);
        this.requiredResources = List.copyOf(requiredResources);
        this.outputMaterial = outputMaterial;
        this.outputAmount = outputAmount;
        this.timeSeconds = Math.max(0, timeSeconds);
    }

    boolean canCraftAt(String stationTypeId) {
        return stationTypes.contains(stationTypeId);
    }

    String outputLabel() {
        return Calc.round(outputAmount) + " " + outputMaterial.label;
    }
}
