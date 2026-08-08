package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** JSON-driven station interaction roles and per-station control state. */
final class StationControls {
    private static final Map<String, InteractionRule> RULES = loadRules();
    private static final Map<World, RuntimeState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private StationControls() { }

    static String role(String typeId) { return rule(typeId).role; }
    static boolean nonProduction(String typeId) { return rule(typeId).nonProduction; }
    static boolean handles(String typeId) { return Rules.findBase(typeId) != null; }

    static List<Material> radarCandidates(World world, Base radar) {
        if (world == null || radar == null || !IntelWarfareSystem.isRadar(radar.typeId)) return List.of();
        Set<Material> present = new LinkedHashSet<>();
        for (ResourceNode node : world.resources) {
            if (node != null && node.material != null) present.add(node.material);
        }
        List<Material> out = new ArrayList<>(present);
        out.sort(Comparator.comparing(StationControls::materialLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Material::name));
        return List.copyOf(out);
    }

    static List<Material> radarPriorities(World world, Base radar) {
        if (world == null || radar == null || !IntelWarfareSystem.isRadar(radar.typeId)) return List.of();
        List<Material> priorities = mutablePriorities(world, radar);
        Set<Material> present = new LinkedHashSet<>(radarCandidates(world, radar));
        priorities.removeIf(material -> material == null || !present.contains(material));
        return List.copyOf(priorities);
    }

    static int priorityRank(World world, Base radar, Material material) {
        List<Material> priorities = radarPriorities(world, radar);
        int index = priorities.indexOf(material);
        return index >= 0 ? index : 1_000_000;
    }

    static boolean putRadarPriorityFirst(World world, Base radar, Material material) {
        if (!validRadarMaterial(world, radar, material)) return false;
        List<Material> priorities = mutablePriorities(world, radar);
        priorities.remove(material);
        priorities.add(0, material);
        return true;
    }

    static boolean moveRadarPriority(World world, Base radar, Material material, int delta) {
        if (delta == 0 || !validRadarMaterial(world, radar, material)) return false;
        List<Material> priorities = mutablePriorities(world, radar);
        int from = priorities.indexOf(material);
        if (from < 0) return false;
        int to = Math.max(0, Math.min(priorities.size() - 1, from + delta));
        if (from == to) return true;
        priorities.remove(from);
        priorities.add(to, material);
        return true;
    }

    static boolean removeRadarPriority(World world, Base radar, Material material) {
        return world != null && radar != null && mutablePriorities(world, radar).remove(material);
    }

    static boolean clearRadarPriorities(World world, Base radar) {
        if (world == null || radar == null || !IntelWarfareSystem.isRadar(radar.typeId)) return false;
        mutablePriorities(world, radar).clear();
        return true;
    }

    static List<String> decoyProfiles(String typeId) { return rule(typeId).decoyProfiles; }

    static String decoySpoofType(World world, Base decoy) {
        if (world == null || decoy == null || !IntelWarfareSystem.isDecoy(decoy.typeId)) {
            return IntelWarfareSystem.CONTACT_STATION;
        }
        InteractionRule rule = rule(decoy.typeId);
        String selected = systemState(world).decoyProfiles.get(decoy.id);
        if (validDecoyProfile(rule, selected)) return selected;
        if (validDecoyProfile(rule, rule.defaultDecoyProfile)) return rule.defaultDecoyProfile;
        for (String profile : rule.decoyProfiles) if (validDecoyProfile(rule, profile)) return profile;
        return IntelWarfareSystem.CONTACT_STATION;
    }

    static boolean setDecoySpoofType(World world, Base decoy, String profile) {
        if (world == null || decoy == null || !IntelWarfareSystem.isDecoy(decoy.typeId)) return false;
        InteractionRule rule = rule(decoy.typeId);
        if (!validDecoyProfile(rule, profile)) return false;
        systemState(world).decoyProfiles.put(decoy.id, profile);
        return true;
    }

    static double jammerRange(String typeId) { return Math.max(0, rule(typeId).jamRange); }
    static double jammerStrength(String typeId) { return Math.max(0, rule(typeId).jamStrength); }
    static double signatureMultiplier(String typeId) { return Math.max(0, rule(typeId).signatureMultiplier); }

    private static boolean validRadarMaterial(World world, Base radar, Material material) {
        return world != null && radar != null && material != null && IntelWarfareSystem.isRadar(radar.typeId)
                && radarCandidates(world, radar).contains(material);
    }

    private static boolean validDecoyProfile(InteractionRule rule, String profile) {
        return profile != null && !profile.isBlank() && rule.decoyProfiles.contains(profile)
                && Rules.findBase(profile) != null && !IntelWarfareSystem.isDecoy(profile);
    }

    private static List<Material> mutablePriorities(World world, Base radar) {
        return systemState(world).radarPriorities.computeIfAbsent(radar.id, ignored -> new ArrayList<>());
    }

    private static SystemState systemState(World world) {
        RuntimeState state = STATES.computeIfAbsent(world, ignored -> new RuntimeState());
        String systemId = world.activeSystemId();
        if (systemId == null || systemId.isBlank()) systemId = "UNKNOWN";
        return state.systems.computeIfAbsent(systemId, ignored -> new SystemState());
    }

    private static InteractionRule rule(String typeId) {
        return typeId == null ? InteractionRule.EMPTY : RULES.getOrDefault(typeId, InteractionRule.EMPTY);
    }

    private static String materialLabel(Material material) {
        return material == null || material.label == null || material.label.isBlank() ? material.name() : material.label;
    }

    private static Map<String, InteractionRule> loadRules() {
        Path path = stationConfigPath();
        if (!Files.exists(path)) return Map.of();
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));
            Map<String,Object> stations = object(root.getOrDefault("stationTypes", root));
            Map<String,InteractionRule> out = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : stations.entrySet()) {
                Map<String,Object> row = object(entry.getValue());
                String role = string(row, "role", "production").trim().toLowerCase(Locale.ROOT);
                boolean nonProduction = bool(row, "nonProduction", false);
                String defaultProfile = string(row, "decoyProfile", "").trim();
                List<String> profiles = strings(row.get("decoyProfiles"));
                if (!defaultProfile.isBlank() && !profiles.contains(defaultProfile)) {
                    List<String> expanded = new ArrayList<>();
                    expanded.add(defaultProfile);
                    expanded.addAll(profiles);
                    profiles = List.copyOf(expanded);
                }
                out.put(entry.getKey(), new InteractionRule(role, nonProduction, profiles, defaultProfile,
                        number(row, "jamRange", 0), number(row, "jamStrength", 0),
                        number(row, "signatureMultiplier", 1)));
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load station-control fields from " + path + ": " + ex.getMessage());
        }
    }

    private static Path stationConfigPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return Path.of("config/stations.json");
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            return Path.of(string(files, "stations", "config/stations.json"));
        } catch (Exception ignored) {
            return Path.of("config/stations.json");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            String text = item == null ? "" : String.valueOf(item).trim();
            if (!text.isBlank()) out.add(text);
        }
        return List.copyOf(out);
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean flag) return flag;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record InteractionRule(String role, boolean nonProduction, List<String> decoyProfiles,
                                   String defaultDecoyProfile, double jamRange, double jamStrength,
                                   double signatureMultiplier) {
        private static final InteractionRule EMPTY = new InteractionRule("production", false, List.of(), "", 0, 0, 1);
    }

    private static final class RuntimeState {
        final Map<String,SystemState> systems = new LinkedHashMap<>();
    }

    private static final class SystemState {
        final Map<String,List<Material>> radarPriorities = new LinkedHashMap<>();
        final Map<String,String> decoyProfiles = new LinkedHashMap<>();
    }
}

/** Authoritative control command handler shared by solo and multiplayer paths. */
final class StationControlCommands {
    private StationControlCommands() { }

    static boolean apply(World world, String playerId, String baseId, String action, String value) {
        if (world == null || playerId == null || baseId == null || action == null) return false;
        Base base = world.bases.get(baseId);
        if (base == null || !playerId.equals(base.playerId)) return false;
        String command = action.trim().toUpperCase(Locale.ROOT);
        if (command.startsWith("LOG_ROUTE_")) {
            return LogisticsRouteSystem.applyCommand(world, playerId, baseId, command, value);
        }
        if (!StationControls.nonProduction(base.typeId)) return false;
        boolean changed = switch (command) {
            case "RADAR_PRIORITY_TOP" -> StationControls.putRadarPriorityFirst(world, base, material(value));
            case "RADAR_PRIORITY_UP" -> StationControls.moveRadarPriority(world, base, material(value), -1);
            case "RADAR_PRIORITY_DOWN" -> StationControls.moveRadarPriority(world, base, material(value), 1);
            case "RADAR_PRIORITY_REMOVE" -> StationControls.removeRadarPriority(world, base, material(value));
            case "RADAR_PRIORITY_CLEAR" -> StationControls.clearRadarPriorities(world, base);
            case "DECOY_PROFILE" -> StationControls.setDecoySpoofType(world, base, value);
            default -> false;
        };
        if (changed) world.status = status(base, command, value);
        return changed;
    }

    private static Material material(String value) {
        try { return value == null ? null : Material.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String status(Base base, String action, String value) {
        if (action.startsWith("RADAR_PRIORITY")) {
            if ("RADAR_PRIORITY_CLEAR".equals(action)) return base.type().name + " resource priorities cleared.";
            Material material = material(value);
            String label = material == null ? value : material.label;
            return base.type().name + " resource priority updated: " + label + ".";
        }
        if ("DECOY_PROFILE".equals(action)) {
            BaseType spoof = Rules.findBase(value);
            return base.type().name + " now spoofs " + (spoof == null ? value : spoof.name) + ".";
        }
        return base.type().name + " controls updated.";
    }
}
