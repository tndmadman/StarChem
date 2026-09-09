package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

record GalaxyGenerationSettings(
        boolean procedural,
        int targetSystemCount,
        GalaxyTopologyStyle topologyStyle,
        double permanentConnectivityDensity,
        double frontierFrequency,
        double resourceRichness,
        double rareResourceFrequency,
        double hazardFrequency,
        double npcDensity,
        int startingSeparation,
        Map<String,Double> templateWeights
) {
    static final int MIN_SYSTEMS = 2;
    static final int MAX_SYSTEMS = 96;

    GalaxyGenerationSettings {
        if (targetSystemCount < MIN_SYSTEMS || targetSystemCount > MAX_SYSTEMS) {
            throw new IllegalArgumentException("targetSystemCount must be between " + MIN_SYSTEMS + " and " + MAX_SYSTEMS + ".");
        }
        topologyStyle = topologyStyle == null ? GalaxyTopologyStyle.MIXED : topologyStyle;
        permanentConnectivityDensity = unit(permanentConnectivityDensity, "permanentConnectivityDensity");
        frontierFrequency = unit(frontierFrequency, "frontierFrequency");
        resourceRichness = range(resourceRichness, 0.25, 4.0, "resourceRichness");
        rareResourceFrequency = unit(rareResourceFrequency, "rareResourceFrequency");
        hazardFrequency = unit(hazardFrequency, "hazardFrequency");
        npcDensity = unit(npcDensity, "npcDensity");
        startingSeparation = Math.max(1, Math.min(12, startingSeparation));
        templateWeights = sanitizeWeights(templateWeights);
    }

    static GalaxyGenerationSettings legacy(int copiesPerTemplate) {
        int copies = Math.max(1, Math.min(2, copiesPerTemplate));
        int systems = Math.max(MIN_SYSTEMS, Math.min(MAX_SYSTEMS, StarSystems.staticOptions().size() * copies));
        return new GalaxyGenerationSettings(false, systems, GalaxyTopologyStyle.RING,
                0.20, 0.0, 1.0, 0.15, 0.15, 0.5, 2, Map.of());
    }

    static GalaxyGenerationSettings load(int legacyCopies) {
        GalaxyGenerationSettings fallback = legacy(legacyCopies);
        Path path = configuredPath();
        if (!Files.exists(path)) return fallback;
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));
            Map<String,Object> generation = object(root.get("generation"));
            if (generation.isEmpty()) return fallback;

            boolean enabled = bool(generation.get("enabled"), false);
            GalaxySizePreset preset = GalaxySizePreset.parse(text(generation.get("size"), "medium"));
            int target = intValue(generation.get("targetSystemCount"), preset.systemCount());
            GalaxyTopologyStyle topology = GalaxyTopologyStyle.parse(text(generation.get("topology"), "mixed"));
            double connectivity = doubleValue(generation.get("permanentConnectivityDensity"), 0.35);
            double frontier = doubleValue(generation.get("frontierFrequency"), 0.20);
            double richness = doubleValue(generation.get("resourceRichness"), 1.0);
            double rare = doubleValue(generation.get("rareResourceFrequency"), 0.15);
            double hazard = doubleValue(generation.get("hazardFrequency"), 0.15);
            double npc = doubleValue(generation.get("npcDensity"), 0.5);
            int separation = intValue(generation.get("startingSeparation"), 3);
            Map<String,Double> weights = weights(object(generation.get("templateWeights")));

            if (!enabled) return fallback;
            return new GalaxyGenerationSettings(true, target, topology, connectivity, frontier,
                    richness, rare, hazard, npc, separation, weights);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not load galaxy generation settings " + path + ": " + ex.getMessage(), ex);
        }
    }

    double weightFor(StarSystemDefinition template) {
        if (template == null) return 0;
        double weight = templateWeights.getOrDefault(template.id(), 1.0);
        if (template.modifiers().miningYield() > 1.0 || template.hasTag("rich")) {
            weight *= resourceRichness;
        }
        if (template.hasTag("rare") || template.hasTag("rare-resource")) {
            weight *= 0.5 + rareResourceFrequency * 2.0;
        }
        if (template.modifiers().environmentalDamagePerSecond() > 0
                || template.hasTag("hazard") || template.hasTag("dangerous")) {
            weight *= 0.35 + hazardFrequency * 2.0;
        }
        return Math.max(0.0001, weight);
    }

    private static Map<String,Double> sanitizeWeights(Map<String,Double> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String,Double> out = new LinkedHashMap<>();
        for (Map.Entry<String,Double> entry : source.entrySet()) {
            String id = entry.getKey() == null ? "" : entry.getKey().trim();
            Double value = entry.getValue();
            if (id.isBlank() || value == null || !Double.isFinite(value) || value <= 0 || value > 100) {
                throw new IllegalArgumentException("templateWeights must contain positive finite weights no greater than 100.");
            }
            if (StarSystems.options().stream().noneMatch(definition -> definition.id().equals(id))) {
                throw new IllegalArgumentException("Unknown galaxy template weight: " + id);
            }
            out.put(id, value);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String,Double> weights(Map<String,Object> raw) {
        if (raw.isEmpty()) return Map.of();
        Map<String,Double> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("Galaxy template weight for " + entry.getKey() + " must be numeric.");
            }
            out.put(entry.getKey(), number.doubleValue());
        }
        return out;
    }

    private static Path configuredPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return Path.of("config/galaxy.json");
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            Object path = files.get("galaxy");
            if (path instanceof String text && !text.isBlank()) return Path.of(text.trim());
        } catch (Exception ignored) { }
        return Path.of("config/galaxy.json");
    }

    private static double unit(double value, String label) {
        return range(value, 0.0, 1.0, label);
    }

    private static double range(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException("Galaxy integer setting must be a whole number.");
        }
        return number.intValue();
    }

    private static double doubleValue(Object value, double fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Galaxy numeric setting must be finite.");
        }
        return number.doubleValue();
    }

    private static String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
    }
}

enum GalaxySizePreset {
    TINY("tiny", 8),
    SMALL("small", 14),
    MEDIUM("medium", 24),
    LARGE("large", 40),
    HUGE("huge", 64);

    private final String id;
    private final int systemCount;

    GalaxySizePreset(String id, int systemCount) {
        this.id = id;
        this.systemCount = systemCount;
    }

    String id() { return id; }
    int systemCount() { return systemCount; }

    static GalaxySizePreset parse(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (GalaxySizePreset preset : values()) if (preset.id.equals(clean)) return preset;
        throw new IllegalArgumentException("Unknown galaxy size preset: " + value);
    }
}

enum GalaxyTopologyStyle {
    RING("ring"),
    CLUSTERED("clustered"),
    HUBS("hubs"),
    FRONTIER("frontier"),
    DENSE("dense"),
    MIXED("mixed");

    private final String id;

    GalaxyTopologyStyle(String id) { this.id = id; }
    String id() { return id; }

    static GalaxyTopologyStyle parse(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if ("spokes".equals(clean) || "spokes_hubs".equals(clean) || "hub".equals(clean)) return HUBS;
        if ("sparse_frontier".equals(clean) || "sparse".equals(clean)) return FRONTIER;
        if ("dense_network".equals(clean)) return DENSE;
        if ("mixed_procedural".equals(clean) || "procedural".equals(clean)) return MIXED;
        for (GalaxyTopologyStyle style : values()) if (style.id.equals(clean)) return style;
        throw new IllegalArgumentException("Unknown galaxy topology style: " + value);
    }
}
