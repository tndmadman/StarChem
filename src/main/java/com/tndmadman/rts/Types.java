package com.tndmadman.rts;

import java.awt.Color;

@SuppressWarnings("unused")
enum Material {
    IRON("Iron", new Color(180,150,120), MaterialFamily.METAL, ResourceTier.COMMON, true),
    COPPER("Copper", new Color(221,122,60), MaterialFamily.METAL, ResourceTier.COMMON, true),
    NICKEL("Nickel", new Color(166,177,169), MaterialFamily.METAL, ResourceTier.COMMON, true),
    COBALT("Cobalt", new Color(75,112,196), MaterialFamily.METAL, ResourceTier.UNCOMMON, true),
    ALUMINUM("Aluminum", new Color(205,215,220), MaterialFamily.METAL, ResourceTier.COMMON, true),
    TITANIUM("Titanium", new Color(132,145,157), MaterialFamily.METAL, ResourceTier.UNCOMMON, true),
    TUNGSTEN("Tungsten", new Color(92,98,105), MaterialFamily.METAL, ResourceTier.RARE, true),
    GOLD("Gold", new Color(238,194,70), MaterialFamily.METAL, ResourceTier.RARE, true),
    PLATINUM("Platinum", new Color(214,220,225), MaterialFamily.METAL, ResourceTier.RARE, true),
    URANIUM("Uranium", new Color(126,220,91), MaterialFamily.METAL, ResourceTier.EXOTIC, true),

    SILICATES("Silicates", new Color(165,170,155), MaterialFamily.MINERAL, ResourceTier.COMMON, true),
    ICE("Water Ice", new Color(145,220,255), MaterialFamily.VOLATILE, ResourceTier.COMMON, true),
    CARBON("Carbon", new Color(70,74,80), MaterialFamily.MINERAL, ResourceTier.COMMON, true),
    SULFUR("Sulfur", new Color(229,205,63), MaterialFamily.MINERAL, ResourceTier.UNCOMMON, true),
    PHOSPHATES("Phosphates", new Color(190,157,112), MaterialFamily.MINERAL, ResourceTier.UNCOMMON, true),
    RARE_EARTHS("Rare Earths", new Color(210,112,177), MaterialFamily.MINERAL, ResourceTier.RARE, true),

    HYDROGEN("Hydrogen", new Color(110,210,255), MaterialFamily.GAS, ResourceTier.COMMON, true),
    HELIUM("Helium", new Color(210,175,255), MaterialFamily.GAS, ResourceTier.COMMON, true),
    METHANE("Methane", new Color(100,255,190), MaterialFamily.GAS, ResourceTier.COMMON, true),
    AMMONIA("Ammonia", new Color(235,245,150), MaterialFamily.GAS, ResourceTier.UNCOMMON, true),
    NITROGEN("Nitrogen", new Color(112,170,235), MaterialFamily.GAS, ResourceTier.UNCOMMON, true),
    NEON("Neon", new Color(255,109,96), MaterialFamily.GAS, ResourceTier.RARE, true),
    ARGON("Argon", new Color(153,126,230), MaterialFamily.GAS, ResourceTier.RARE, true),
    XENON("Xenon", new Color(93,231,229), MaterialFamily.GAS, ResourceTier.EXOTIC, true),

    FUEL("Fuel", new Color(255,185,70), MaterialFamily.REFINED, ResourceTier.UNCOMMON, false),
    SCRAP_METAL("Scrap Metal", new Color(160,165,170), MaterialFamily.SALVAGE, ResourceTier.COMMON, false),
    HULL_PLATING("Hull Plating", new Color(120,145,170), MaterialFamily.SALVAGE, ResourceTier.UNCOMMON, false),
    CIRCUIT_FRAGMENTS("Circuit Fragments", new Color(90,245,185), MaterialFamily.SALVAGE, ResourceTier.UNCOMMON, false);

    final String label;
    final Color color;
    final MaterialFamily family;
    final ResourceTier tier;
    final boolean raw;

    Material(String label, Color color, MaterialFamily family, ResourceTier tier, boolean raw) {
        this.label = label;
        this.color = color;
        this.family = family;
        this.tier = tier;
        this.raw = raw;
    }

    boolean harvestable() { return raw; }
}

enum MaterialFamily { METAL, MINERAL, VOLATILE, GAS, REFINED, SALVAGE }
enum ResourceTier { COMMON, UNCOMMON, RARE, EXOTIC }
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
