package com.tndmadman.rts;

import java.awt.Color;

@SuppressWarnings("unused")
enum Material {
    IRON("Iron", new Color(180,150,120)),
    COPPER("Copper", new Color(221,122,60)),
    SILICATES("Silicates", new Color(165,170,155)),
    ICE("Water Ice", new Color(145,220,255)),
    HYDROGEN("Hydrogen", new Color(110,210,255)),
    HELIUM("Helium", new Color(210,175,255)),
    METHANE("Methane", new Color(100,255,190)),
    AMMONIA("Ammonia", new Color(235,245,150)),
    FUEL("Fuel", new Color(255,185,70)),
    SCRAP_METAL("Scrap Metal", new Color(160,165,170)),
    HULL_PLATING("Hull Plating", new Color(120,145,170)),
    CIRCUIT_FRAGMENTS("Circuit Fragments", new Color(90,245,185));

    final String label;
    final Color color;
    Material(String label, Color color) { this.label = label; this.color = color; }
}

enum NodeKind { SILICATE_ROCK, GAS_CLOUD }
enum UnitTask { IDLE, MOVE, AUTO_HARVEST, RETURN_TO_STATION, ATTACK }
enum UnitOrderType { NONE, PATROL, GUARD, ESCORT, HOLD, ATTACK_MOVE }

enum ShipSize {
    SMALL(0.85),
    MEDIUM(1.05),
    LARGE(1.35),
    XL(1.75),
    FRIGATE(0.95),
    DESTROYER(1.15),
    CRUISER(1.45),
    BATTLE_CRUISER(1.75),
    BATTLESHIP(2.10),
    CARRIER(2.55),
    DREADNOUGHT(2.80),
    SUPERCARRIER(3.25),
    TITAN(3.85),
    MONOLITH(4.75);

    final double scale;
    ShipSize(double scale) { this.scale = scale; }
}

record Cost(Material material, double amount) { }
record MoveCommand(String playerId, int unitId, double x, double y) { }
record HarvestCommand(String playerId, int unitId, int resourceId) { }
record AttackCommand(String playerId, int unitId, String targetKey) { }
record UnitOrderCommand(String playerId, int unitId, UnitOrderType type,
                        double x1, double y1, double x2, double y2,
                        double radius, String targetKey, int phase) {
    UnitOrderCommand {
        if (type == null) type = UnitOrderType.NONE;
        targetKey = targetKey == null ? "" : targetKey;
    }
}
