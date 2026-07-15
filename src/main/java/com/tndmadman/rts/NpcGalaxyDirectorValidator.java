package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.Set;

public final class NpcGalaxyDirectorValidator {
    private NpcGalaxyDirectorValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC galaxy director validation passed.");
    }

    static void validateOrThrow() {
        World world = new World("NPC Director Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Base source = new Base(Config.CORSAIRS_ID + ":B90", Config.CORSAIRS_ID, Rules.DEFAULT_BASE, x, y);
        for (Material material : Material.values()) if (material.raw || material == Material.FUEL) source.inventory.put(material, 1000.0);
        world.bases.put(source.id, source);
        addBase(world, "laboratory", x + 520, y);
        addBase(world, "shipyard", x - 520, y);
        addBase(world, "manufacturing", x, y + 520);
        add(world, 1, "station_builder", x + 100, y);
        add(world, 2, "prospector", x - 100, y);
        add(world, 3, "prospector", x - 140, y + 60);
        add(world, 4, "prospector", x - 180, y + 120);
        add(world, 5, "frigate", x, y + 100);
        add(world, 6, "frigate", x, y - 100);
        add(world, 7, "destroyer", x + 150, y + 100);
        add(world, 8, "destroyer", x - 150, y - 100);
        add(world, 9, "frigate", x + 210, y - 140);
        add(world, 10, "hauler", x + 180, y + 180);
        add(world, 11, "deep_miner", x - 220, y + 180);
        for (String topicId : corsairs().researchTopicIds()) world.completeResearch(Config.CORSAIRS_ID, topicId);
        world.saveActiveSystem();

        NpcGalaxyDirector director = new NpcGalaxyDirector();
        for (int i = 0; i < 40; i++) {
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            director.update(world, 1.0);
        }
        String target = expeditionTarget(world);
        require(!target.isBlank(), "strategically ready NPC faction did not establish a neighboring foothold");
        world.activateSystem(target);
        Base foothold = world.bases.values().stream()
                .filter(base -> Config.CORSAIRS_ID.equals(base.playerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("NPC expedition did not create a target-system outpost"));
        Set<String> expeditionUnitKeys = factionUnitKeys(world);
        require(expeditionUnitKeys.size() >= 5,
                "NPC expedition did not transfer combat ships and a worker");
        require(!foothold.inventory.isEmpty(), "NPC expedition foothold received no transferred supplies");

        world.updateCurrentSystem(1.0);
        require(world.bases.containsKey(foothold.id),
                "active-system cleanup relocated the expedition foothold to Corsair Den");
        require(world.units.keySet().containsAll(expeditionUnitKeys),
                "active-system cleanup relocated expedition ships to Corsair Den");

        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        require(world.bases.containsKey(source.id), "NPC expedition incorrectly moved the source station");
        require(world.units.values().stream().noneMatch(unit -> Config.CORSAIRS_ID.equals(unit.playerId) && unit.type().baseBuilder),
                "NPC expedition did not consume its deployer");
        require(!world.bases.containsKey(foothold.id),
                "expedition foothold was merged into Corsair Den during cleanup");
        for (String unitKey : expeditionUnitKeys) {
            require(!world.units.containsKey(unitKey),
                    "expedition ship was merged into Corsair Den during cleanup: " + unitKey);
        }

        for (int i = 0; i < 30; i++) world.update(1.0);
        world.activateSystem(target);
        require(world.bases.containsKey(foothold.id),
                "background simulation relocated the expedition foothold to Corsair Den");
        require(world.units.keySet().containsAll(expeditionUnitKeys),
                "background simulation relocated expedition ships to Corsair Den");
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static void addBase(World world, String type, double x, double y) {
        String id = Config.CORSAIRS_ID + ":B" + (90 + world.bases.size());
        world.bases.put(id, new Base(id, Config.CORSAIRS_ID, type, x, y));
    }

    private static String expeditionTarget(World world) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapSystem system : map.systems()) {
            if (StarSystems.CORSAIR_SYSTEM_ID.equals(system.id()) || !system.staticSystem()) continue;
            world.activateSystem(system.id());
            if (world.bases.values().stream().anyMatch(base -> Config.CORSAIRS_ID.equals(base.playerId))) return system.id();
        }
        return "";
    }

    private static Set<String> factionUnitKeys(World world) {
        Set<String> keys = new LinkedHashSet<>();
        for (Unit unit : world.units.values()) {
            if (Config.CORSAIRS_ID.equals(unit.playerId) && unit.hp > 0) keys.add(unit.key());
        }
        return keys;
    }

    private static void add(World world, int id, String type, double x, double y) {
        Unit unit = new Unit(Config.CORSAIRS_ID, id, type, x, y);
        world.units.put(unit.key(), unit);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
