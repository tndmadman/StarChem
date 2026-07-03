package com.tndmadman.rts;

import java.util.*;

final class Rules {
    static final Map<String, ShipType> SHIPS = new LinkedHashMap<>();
    static final Map<String, BaseType> BASES = new LinkedHashMap<>();
    static final String STARTING_SHIP = "prospector";
    static final String DEFAULT_BASE = "outpost";

    static {
        ship(new ShipType("prospector", "Prospector", ShipSize.SMALL, 1501, 100, 185, 120, 105, 72, 96, 0, 0, false,
                EnumSet.of(NodeKind.SILICATE_ROCK, NodeKind.GAS_CLOUD), cost(Material.IRON,80, Material.COPPER,40)));
        ship(new ShipType("builder", "Deployer", ShipSize.LARGE, 2451, 240, 115, 0, 0, 90, 120, 0, 0, true,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,220, Material.COPPER,120, Material.SILICATES,100, Material.ICE,40)));
        ship(new ShipType("scout", "Scout", ShipSize.SMALL, 9907, 70, 275, 45, 60, 70, 115, 420, 5, false,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,60, Material.COPPER,90, Material.HYDROGEN,40)));
        ship(new ShipType("hauler", "Hauler", ShipSize.LARGE, 3319, 150, 138, 340, 70, 84, 120, 0, 0, false,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,150, Material.COPPER,60, Material.SILICATES,80)));
        ship(new ShipType("deep_miner", "Deep Miner", ShipSize.MEDIUM, 6173, 180, 125, 220, 125, 86, 120, 0, 0, false,
                EnumSet.of(NodeKind.SILICATE_ROCK), cost(Material.IRON,180, Material.COPPER,110, Material.SILICATES,140, Material.ICE,60)));
        ship(new ShipType("gas_harvester", "Gas Harvester", ShipSize.MEDIUM, 7281, 125, 150, 180, 130, 92, 120, 0, 0, false,
                EnumSet.of(NodeKind.GAS_CLOUD), cost(Material.IRON,120, Material.COPPER,150, Material.SILICATES,60, Material.ICE,80, Material.HYDROGEN,80)));
        ship(new ShipType("freighter", "Freighter", ShipSize.XL, 8431, 360, 92, 1440, 0, 110, 155, 0, 0, false,
                EnumSet.noneOf(NodeKind.class), cost(Material.IRON,420, Material.COPPER,200, Material.SILICATES,300, Material.ICE,140)));

        base(new BaseType("outpost", "Outpost", 1200, 118, 95, 72,
                List.of("prospector", "builder"), List.of("shipyard"), List.of()));
        base(new BaseType("shipyard", "Shipyard", 2400, 150, 160, 100,
                List.of("prospector", "builder", "scout", "hauler", "deep_miner", "gas_harvester", "freighter"),
                List.of(), cost(Material.IRON,500, Material.COPPER,250, Material.SILICATES,350, Material.ICE,160)));
    }

    private static void ship(ShipType type) { SHIPS.put(type.id, type); }
    private static void base(BaseType type) { BASES.put(type.id, type); }
    static ShipType ship(String id) { return SHIPS.getOrDefault(id, SHIPS.get(STARTING_SHIP)); }
    static BaseType base(String id) { return BASES.getOrDefault(id, BASES.get(DEFAULT_BASE)); }

    static List<Cost> cost(Object... pairs) {
        List<Cost> result = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) result.add(new Cost((Material) pairs[i], ((Number) pairs[i + 1]).doubleValue()));
        return List.copyOf(result);
    }

    static String formatCost(List<Cost> cost) {
        if (cost.isEmpty()) return "free";
        StringBuilder b = new StringBuilder();
        for (Cost c : cost) {
            if (!b.isEmpty()) b.append(", ");
            b.append(Calc.round(c.amount())).append(' ').append(c.material().label);
        }
        return b.toString();
    }
}

final class ShipType {
    final String id, name;
    final ShipSize size;
    final int seed;
    final double maxHp, speed, cargoCapacity, harvestRange, orbitRadius, idleOrbitRadius, scoutRange;
    final int scoutDispatchLimit;
    final boolean baseBuilder;
    final EnumSet<NodeKind> harvestKinds;
    final List<Cost> buildCost;

    ShipType(String id, String name, ShipSize size, int seed, double maxHp, double speed, double cargoCapacity,
             double harvestRange, double orbitRadius, double idleOrbitRadius, double scoutRange, int scoutDispatchLimit,
             boolean baseBuilder, EnumSet<NodeKind> harvestKinds, List<Cost> buildCost) {
        this.id = id; this.name = name; this.size = size; this.seed = seed; this.maxHp = maxHp; this.speed = speed;
        this.cargoCapacity = cargoCapacity; this.harvestRange = harvestRange; this.orbitRadius = orbitRadius;
        this.idleOrbitRadius = idleOrbitRadius; this.scoutRange = scoutRange; this.scoutDispatchLimit = scoutDispatchLimit;
        this.baseBuilder = baseBuilder; this.harvestKinds = harvestKinds; this.buildCost = buildCost;
    }
}

final class BaseType {
    final String id, name;
    final double maxHp, unloadRange, unloadRate, buildRadius;
    final List<String> buildableShips, basePackages;
    final List<Cost> buildCost;

    BaseType(String id, String name, double maxHp, double unloadRange, double unloadRate, double buildRadius,
             List<String> buildableShips, List<String> basePackages, List<Cost> buildCost) {
        this.id = id; this.name = name; this.maxHp = maxHp; this.unloadRange = unloadRange; this.unloadRate = unloadRate;
        this.buildRadius = buildRadius; this.buildableShips = buildableShips; this.basePackages = basePackages; this.buildCost = buildCost;
    }
}
