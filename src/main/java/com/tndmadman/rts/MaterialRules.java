package com.tndmadman.rts;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MaterialRules {
    private static final Map<String, MaterialDefinition> DEFINITIONS = load();

    private MaterialRules() { }

    static MaterialDefinition definition(String id) {
        MaterialDefinition definition = DEFINITIONS.get(id);
        if (definition == null) throw new IllegalStateException("Material metadata is missing from JSON for " + id + ".");
        return definition;
    }

    static Map<String, MaterialDefinition> definitions() {
        return DEFINITIONS;
    }

    private static Map<String, MaterialDefinition> load() {
        try {
            Object materialFiles = null;
            Path manifest = Path.of("config/starchem.json");
            if (Files.exists(manifest)) {
                Map<String,Object> root = readObject(manifest);
                materialFiles = object(root.get("files")).get("materials");
            }
            if (materialFiles == null) materialFiles = "config/materials.json";

            Map<String, MaterialDefinition> out = new LinkedHashMap<>();
            for (String rawPath : filePaths(materialFiles)) {
                Path path = Path.of(rawPath);
                Map<String,Object> document = readObject(path);
                Map<String,Object> source = object(document.getOrDefault("materials", document));
                for (Map.Entry<String,Object> entry : source.entrySet()) {
                    String id = entry.getKey().trim().toUpperCase(Locale.ROOT);
                    Map<String,Object> data = object(entry.getValue());
                    if (data.isEmpty()) continue;
                    if (out.containsKey(id)) throw new IllegalArgumentException("Duplicate material metadata: " + id);
                    out.put(id, new MaterialDefinition(
                            id,
                            string(data, "displayName", id),
                            color(string(data, "color", "#FFFFFF")),
                            family(string(data, "family", "REFINED")),
                            tier(string(data, "tier", "COMMON")),
                            bool(data, "raw", false)));
                }
            }
            if (out.isEmpty()) throw new IllegalArgumentException("No material metadata was loaded.");
            return Collections.unmodifiableMap(out);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError("Could not load material metadata from JSON: " + ex.getMessage());
        }
    }

    private static List<String> filePaths(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String path = String.valueOf(item).trim();
                if (!path.isEmpty()) out.add(path);
            }
        } else if (value != null) {
            String path = String.valueOf(value).trim();
            if (!path.isEmpty()) out.add(path);
        }
        return List.copyOf(out);
    }

    private static Map<String,Object> readObject(Path path) throws IOException {
        if (!Files.exists(path)) throw new IOException("Missing material config: " + path);
        Object parsed = MiniJson.parse(Files.readString(path));
        return object(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return (Map<String,Object>) map;
        return Map.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static Color color(String value) {
        try { return Color.decode(value.trim()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid material color: " + value); }
    }

    private static MaterialFamily family(String value) {
        try { return MaterialFamily.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid material family: " + value); }
    }

    private static ResourceTier tier(String value) {
        try { return ResourceTier.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid material tier: " + value); }
    }
}

record MaterialDefinition(String id, String displayName, Color color, MaterialFamily family,
                          ResourceTier tier, boolean raw) {
    MaterialDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Material ID is required.");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Material display name is required for " + id + ".");
        if (color == null || family == null || tier == null) throw new IllegalArgumentException("Material metadata is incomplete for " + id + ".");
    }
}
