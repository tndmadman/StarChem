package com.tndmadman.rts;

import java.awt.Color;

@SuppressWarnings("unused")
enum Material {
    IRON,
    COPPER,
    NICKEL,
    COBALT,
    ALUMINUM,
    TITANIUM,
    TUNGSTEN,
    GOLD,
    PLATINUM,
    URANIUM,
    SILICATES,
    ICE,
    CARBON,
    SULFUR,
    PHOSPHATES,
    RARE_EARTHS,
    HYDROGEN,
    HELIUM,
    METHANE,
    AMMONIA,
    NITROGEN,
    NEON,
    ARGON,
    XENON,
    FUEL,
    SCRAP_METAL,
    HULL_PLATING,
    CIRCUIT_FRAGMENTS,
    STEEL_PLATE,
    NICKEL_STEEL,
    ALUMINUM_ALLOY,
    TITANIUM_ALLOY,
    COBALT_SUPERALLOY,
    TUNGSTEN_CARBIDE,
    CERAMIC_COMPOSITE,
    RADIATION_SHIELDING,
    POLYMER_RESIN,
    INDUSTRIAL_LUBRICANT,
    WATER_COOLANT,
    CRYOGENIC_COOLANT,
    METHANE_PROPELLANT,
    HYPERGOLIC_PROPELLANT,
    EXPLOSIVE_COMPOUND,
    SHIELD_PLASMA_MIX,
    COPPER_WIRING,
    GOLD_CONTACT_MESH,
    PRINTED_CIRCUIT_BOARD,
    POWER_REGULATOR,
    CAPACITOR_BANK,
    SENSOR_ARRAY,
    NAVIGATION_COMPUTER,
    TARGETING_COMPUTER,
    STRUCTURAL_FRAME,
    PRESSURE_HULL_SECTION,
    CARGO_POD,
    MINING_HEAD,
    GAS_COMPRESSOR,
    TRACTOR_BEAM_EMITTER,
    FABRICATION_TOOLSET,
    LOGISTICS_CONTROL_MODULE,
    FUEL_CELL_STACK,
    REACTOR_CONTROL_UNIT,
    FISSION_REACTOR_CORE,
    FUSION_REACTOR,
    ION_THRUSTER,
    FUSION_DRIVE,
    SHIELD_EMITTER,
    ARMOR_MATRIX,
    POINT_DEFENSE_LASER_ASSEMBLY,
    RAILGUN_ASSEMBLY,
    HEAVY_CANNON_ASSEMBLY,
    MISSILE_GUIDANCE_PACKAGE,
    MISSILE_WARHEAD,
    TORPEDO_ASSEMBLY,
    FIGHTER_CONTROL_MODULE,
    LANCE_FOCUSING_ARRAY,
    SHIELD_GENERATOR,
    CAPITAL_REACTOR_CORE,
    CAPITAL_DRIVE_CORE,
    COMMAND_CORE,
    FIGHTER_BAY_MODULE,
    MANUFACTURING_LINE,
    RESEARCH_MATRIX,
    MEGASTRUCTURE_TRUSS;

    final String label;
    final Color color;
    final MaterialFamily family;
    final ResourceTier tier;
    final boolean raw;

    Material() {
        MaterialDefinition definition = MaterialRules.definition(name());
        this.label = definition.displayName();
        this.color = definition.color();
        this.family = definition.family();
        this.tier = definition.tier();
        this.raw = definition.raw();
    }

    boolean harvestable() { return raw; }
}

enum MaterialFamily { METAL, MINERAL, VOLATILE, GAS, REFINED, SALVAGE, ALLOY, COMPOSITE, CHEMICAL, ELECTRONIC, INDUSTRIAL, POWER, WEAPON, CAPITAL }
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

