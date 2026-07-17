package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class NpcRules {
    private static final List<NpcFaction> FACTIONS;

    static {
        List<NpcFaction> loaded = loadExternal();
        FACTIONS = loaded.isEmpty() ? defaults() : loaded;
    }

    private NpcRules() { }

    static List<NpcFaction> factions() { return FACTIONS; }

    static boolean isNpcFaction(String playerId) {
        for (NpcFaction faction : FACTIONS) if (faction.id().equals(playerId)) return true;
        return false;
    }

    private static List<NpcFaction> loadExternal() {
        try {
            Path path = npcConfigPath();
            if (path == null || !Files.exists(path)) return List.of();
            Map<String,Object> root = readObject(path);
            List<NpcFaction> out = new ArrayList<>();
            for (Object item : array(root.get("factions"))) {
                Map<String,Object> f = object(item);
                if (f.isEmpty()) continue;
                NpcFaction faction = parseFaction(f);
                if (!faction.id().isBlank()) out.add(faction);
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            System.err.println("Could not load NPC config: " + ex.getMessage());
            return List.of();
        }
    }

    private static Path npcConfigPath() throws IOException {
        Path manifest = Path.of("config/starchem.json");
        if (Files.exists(manifest)) {
            Map<String,Object> root = readObject(manifest);
            Map<String,Object> files = object(root.get("files"));
            String configured = string(files, "npcs", "");
            if (!configured.isBlank()) return Path.of(configured);
        }
        Path fallback = Path.of("config/npcs.json");
        return Files.exists(fallback) ? fallback : null;
    }

    private static NpcFaction parseFaction(Map<String,Object> f) {
        String id = string(f, "id", "").trim();
        String name = string(f, "name", id).trim();
        NpcBehavior behavior = behavior(string(f, "behavior", "RAIDER"));
        return new NpcFaction(
                id,
                name.isBlank() ? id : name,
                color(f.get("color"), 0xFF5F55),
                bool(f, "enabled", true),
                behavior,
                number(f, "firstSpawnSeconds", 18.0),
                number(f, "respawnSeconds", 45.0),
                number(f, "orderSeconds", 2.0),
                string(f, "baseType", defaultBaseType(behavior)),
                stringList(f.get("startingUnits")),
                stringList(f.get("workerUnitTypes")),
                stringList(f.get("fleetUnitTypes")),
                stringList(f.get("supportUnitTypes")),
                stringList(f.get("stationPackageTypes")),
                stringList(f.get("industryUnitTypes")),
                stringList(f.get("researchTopicIds")),
                stringList(f.get("craftableItemIds")),
                integer(f, "maxWorkers", 0),
                integer(f, "targetFleetSize", 0),
                integer(f, "raidFleetSize", 0),
                integer(f, "harassFleetSize", 0),
                integer(f, "maxSupportUnits", 0),
                integer(f, "maxStations", 1),
                integer(f, "maxIndustryUnits", 0),
                number(f, "buildSeconds", 10.0),
                number(f, "stationBuildSeconds", 16.0),
                number(f, "defendRange", 1250.0),
                number(f, "raidCooldownSeconds", 18.0),
                number(f, "retreatHpPercent", 0.0),
                number(f, "stationSpacing", 520.0),
                number(f, "fuelReserve", 0.0),
                number(f, "spawnDistance", 2200.0),
                number(f, "spawnPadding", 700.0),
                number(f, "unitSpacing", 150.0),
                materialSet(f.get("targetMaterials")),
                nodeKindSet(f.get("harvestNodeKinds")),
                bool(f, "attackBases", true),
                bool(f, "attackUnits", true),
                bool(f, "attackNpcFactions", false),
                bool(f, "replaceWorkers", true),
                bool(f, "harassWorkers", behavior == NpcBehavior.FACTION),
                bool(f, "preferWorkerTargets", behavior == NpcBehavior.FACTION),
                bool(f, "requirePlayerCombatShips", behavior == NpcBehavior.RAIDER || behavior == NpcBehavior.FACTION),
                integer(f, "minPlayerCombatShips", behavior == NpcBehavior.RAIDER || behavior == NpcBehavior.FACTION ? 1 : 0),
                string(f, "spawnMessage", defaultSpawnMessage(behavior)));
    }

    private static String defaultBaseType(NpcBehavior behavior) {
        return behavior == NpcBehavior.FACTION ? "outpost" : Rules.DEFAULT_BASE;
    }

    private static String defaultSpawnMessage(NpcBehavior behavior) {
        return switch (behavior) {
            case MINER -> "Independent miners have entered the sector.";
            case FACTION -> "An organized NPC faction has established a foothold.";
            case RAIDER -> "Raider ships have entered the sector.";
        };
    }

    private static List<NpcFaction> defaults() {
        return List.of(
                new NpcFaction("NPC_RAIDERS", "Raiders", 0xFF5F55, true, NpcBehavior.RAIDER,
                        18.0, 45.0, 2.0, Rules.DEFAULT_BASE,
                        List.of("frigate", "frigate", "destroyer"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0, 1, 0, 10.0, 16.0, 1250.0, 18.0, 0.0, 520.0, 0.0,
                        2200.0, 700.0, 150.0, EnumSet.noneOf(Material.class), EnumSet.noneOf(NodeKind.class),
                        true, true, false, true, false, false, true, 1, "Raider ships have entered the sector."),
                new NpcFaction("NPC_MINERS", "Free Miners", 0xFFE066, true, NpcBehavior.MINER,
                        35.0, 60.0, 3.0, Rules.DEFAULT_BASE,
                        List.of("prospector", "prospector"), List.of("prospector"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 3, 0, 0, 0, 0, 1, 0, 10.0, 16.0, 1250.0, 18.0, 0.0, 520.0, 0.0,
                        2800.0, 700.0, 145.0, EnumSet.of(Material.IRON, Material.COPPER, Material.SILICATES, Material.ICE), EnumSet.of(NodeKind.SILICATE_ROCK),
                        false, false, false, true, false, false, false, 0, "Independent miners have entered the sector."),
                new NpcFaction("NPC_CORSAIRS", "Corsair Syndicate", 0xC77DFF, true, NpcBehavior.FACTION,
                        65.0, 90.0, 3.0, "outpost",
                        List.of("prospector", "prospector", "frigate"), List.of("prospector"), List.of("frigate", "destroyer"), List.of("hauler", "freighter", "salvager"), List.of("shipyard", "laboratory", "manufacturing"), List.of("deep_miner", "gas_harvester"), List.of("advanced_industry", "combat_doctrine", "battlefleet_engineering"), List.of("fuel"), 3, 5, 4, 2, 4, 6, 2, 12.0, 16.0, 1400.0, 22.0, 0.35, 560.0, 90.0,
                        3400.0, 700.0, 150.0, EnumSet.of(Material.IRON, Material.COPPER, Material.SILICATES, Material.ICE, Material.HYDROGEN, Material.HELIUM, Material.METHANE), EnumSet.noneOf(NodeKind.class),
                        true, true, false, false, true, true, true, 1, "Corsair Syndicate has established a foothold."));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (value instanceof Map<?,?> map) return (Map<String,Object>) map;
        return Map.of();
    }

    private static Map<String,Object> readObject(Path path) throws IOException {
        return object(MiniJson.parse(Files.readString(path)));
    }

    private static List<?> array(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static int integer(Map<String,Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : array(value)) {
            String s = String.valueOf(item).trim();
            if (!s.isBlank()) out.add(s);
        }
        return List.copyOf(out);
    }

    private static EnumSet<Material> materialSet(Object value) {
        EnumSet<Material> out = EnumSet.noneOf(Material.class);
        for (String item : stringList(value)) {
            try { out.add(Material.valueOf(item.trim().toUpperCase(Locale.ROOT))); }
            catch (Exception ignored) { }
        }
        return out;
    }

    private static EnumSet<NodeKind> nodeKindSet(Object value) {
        EnumSet<NodeKind> out = EnumSet.noneOf(NodeKind.class);
        for (String item : stringList(value)) {
            try { out.add(NodeKind.valueOf(item.trim().toUpperCase(Locale.ROOT))); }
            catch (Exception ignored) { }
        }
        return out;
    }

    private static NpcBehavior behavior(String value) {
        try { return NpcBehavior.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return NpcBehavior.RAIDER; }
    }

    private static int color(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        try {
            if (text.startsWith("#")) return Integer.parseInt(text.substring(1), 16);
            if (text.startsWith("0x") || text.startsWith("0X")) return Integer.parseInt(text.substring(2), 16);
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

enum NpcBehavior { RAIDER, MINER, FACTION }

record NpcFaction(
        String id,
        String name,
        int rgb,
        boolean enabled,
        NpcBehavior behavior,
        double firstSpawnSeconds,
        double respawnSeconds,
        double orderSeconds,
        String baseType,
        List<String> startingUnits,
        List<String> workerUnitTypes,
        List<String> fleetUnitTypes,
        List<String> supportUnitTypes,
        List<String> stationPackageTypes,
        List<String> industryUnitTypes,
        List<String> researchTopicIds,
        List<String> craftableItemIds,
        int maxWorkers,
        int targetFleetSize,
        int raidFleetSize,
        int harassFleetSize,
        int maxSupportUnits,
        int maxStations,
        int maxIndustryUnits,
        double buildSeconds,
        double stationBuildSeconds,
        double defendRange,
        double raidCooldownSeconds,
        double retreatHpPercent,
        double stationSpacing,
        double fuelReserve,
        double spawnDistance,
        double spawnPadding,
        double unitSpacing,
        Set<Material> targetMaterials,
        Set<NodeKind> harvestNodeKinds,
        boolean attackBases,
        boolean attackUnits,
        boolean attackNpcFactions,
        boolean replaceWorkers,
        boolean harassWorkers,
        boolean preferWorkerTargets,
        boolean requirePlayerCombatShips,
        int minPlayerCombatShips,
        String spawnMessage
) {
    boolean allowsMaterial(Material material) { return targetMaterials.isEmpty() || targetMaterials.contains(material); }
    boolean allowsKind(NodeKind kind) { return harvestNodeKinds.isEmpty() || harvestNodeKinds.contains(kind); }
    Set<String> workerTypeSet() { return workerUnitTypes.stream().collect(Collectors.toUnmodifiableSet()); }
    Set<String> supportTypeSet() { return supportUnitTypes.stream().collect(Collectors.toUnmodifiableSet()); }

    /** Home keeps an outpost plus at most two specialized infrastructure stations. */
    int homeInfrastructureTarget() {
        if (maxStations <= 0) return 0;
        if (behavior != NpcBehavior.FACTION) return 1;
        int specialized = Math.min(2, stationPackageTypes.size());
        return Math.min(maxStations, 1 + specialized);
    }

    /** Remaining global station slots become one-station frontier systems. */
    int maxControlledSystems() {
        if (behavior != NpcBehavior.FACTION || maxStations <= 0) return 1;
        return Math.max(1, 1 + Math.max(0, maxStations - homeInfrastructureTarget()));
    }
}
