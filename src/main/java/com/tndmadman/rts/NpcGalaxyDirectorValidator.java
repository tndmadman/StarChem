package com.tndmadman.rts;

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
        add(world, 1, "station_builder", x + 100, y);
        add(world, 2, "prospector", x - 100, y);
        add(world, 3, "frigate", x, y + 100);
        add(world, 4, "frigate", x, y - 100);
        add(world, 5, "destroyer", x + 150, y + 100);
        add(world, 6, "destroyer", x - 150, y - 100);
        world.saveActiveSystem();

        new NpcGalaxyDirector().update(world, 1.0);
        String target = expeditionTarget(world);
        require(!target.isBlank(), "organized NPC faction did not establish a neighboring foothold");
        world.activateSystem(target);
        require(world.bases.values().stream().anyMatch(base -> Config.CORSAIRS_ID.equals(base.playerId)),
                "NPC expedition did not create a target-system outpost");
        long expeditionUnits = world.units.values().stream().filter(unit -> Config.CORSAIRS_ID.equals(unit.playerId)).count();
        require(expeditionUnits >= 5, "NPC expedition did not transfer combat ships and a worker");
        Base foothold = world.bases.values().stream().filter(base -> Config.CORSAIRS_ID.equals(base.playerId)).findFirst().orElseThrow();
        require(!foothold.inventory.isEmpty(), "NPC expedition foothold received no transferred supplies");
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        require(world.bases.containsKey(source.id), "NPC expedition incorrectly moved the source station");
        require(world.units.values().stream().noneMatch(unit -> Config.CORSAIRS_ID.equals(unit.playerId) && unit.type().baseBuilder),
                "NPC expedition did not consume its deployer");
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

    private static void add(World world, int id, String type, double x, double y) {
        Unit unit = new Unit(Config.CORSAIRS_ID, id, type, x, y);
        world.units.put(unit.key(), unit);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
