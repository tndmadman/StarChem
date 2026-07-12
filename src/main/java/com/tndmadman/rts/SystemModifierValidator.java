package com.tndmadman.rts;

import java.util.Set;

public final class SystemModifierValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private SystemModifierValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem system modifier validation passed.");
    }

    static void validateOrThrow() {
        validateMiningYield();
        validateResourceRespawn();
        validateShieldAndEnvironment();
        validateMovementSensorAndWeaponScales();
    }

    private static void validateMiningYield() {
        World world = world("binary_forge");
        Unit miner = new Unit("P1", 1, "prospector", 1000, 1000);
        ResourceNode node = new ResourceNode(999, "Test Iron", NodeKind.SILICATE_ROCK, Material.IRON,
                1000, 1000, 100, 10, 3);
        world.units.put(miner.key(), miner);
        world.resources.add(node);
        miner.startAutoHarvest(node.id);
        new WorkSystem().update(world, miner, 1.0);
        double expected = 10 * StarSystems.get("binary_forge").modifiers().miningYield();
        require(Math.abs(miner.inventory.getOrDefault(Material.IRON, 0.0) - expected) < 0.01,
                "mining-yield system modifier was not applied");
    }

    private static void validateResourceRespawn() {
        World world = world("ice_belt");
        ResourceNode node = new ResourceNode(1000, "Respawn Test", NodeKind.SILICATE_ROCK, Material.ICE,
                1200, 1200, 10, 1, 2);
        node.active = false;
        node.respawnTimer = 1.0;
        node.updateRespawn(0.5, world);
        double expected = 1.0 - 0.5 * StarSystems.get("ice_belt").modifiers().resourceRespawn();
        require(Math.abs(node.respawnTimer - expected) < 0.01, "resource-respawn modifier was not applied");
    }

    private static void validateShieldAndEnvironment() {
        World world = world("pulsar_reach");
        Unit ship = new Unit("P1", 1, "frigate", 1000, 1000);
        ship.shield = 0;
        ship.shieldDelayTimer = 0;
        world.units.put(ship.key(), ship);
        ShieldSystem.update(world, 1.0);
        double expectedRegen = ship.type().shieldRegen * StarSystems.get("pulsar_reach").modifiers().shieldRegen();
        require(Math.abs(ship.shield - expectedRegen) < 0.01, "shield-regeneration modifier was not applied");
        double before = ship.shield;
        SystemModifierRules.applyEnvironment(world, 1.0);
        double expectedDamage = StarSystems.get("pulsar_reach").modifiers().environmentalDamagePerSecond();
        require(Math.abs(ship.shield - (before - expectedDamage)) < 0.01,
                "environmental damage modifier was not applied through shields");
    }

    private static void validateMovementSensorAndWeaponScales() {
        World volcanic = world("volcanic_crucible");
        Unit mover = new Unit("P1", 1, "frigate", 1000, 1000);
        mover.moveTo(5000, 1000);
        double start = mover.x;
        mover.updatePosition(SystemModifierRules.movementSpeed(volcanic), volcanic.width, volcanic.height);
        require(Math.abs((mover.x - start) - mover.type().speed * 0.94) < 0.1,
                "movement-speed modifier was not applied");
        World nebula = world("nebula_expanse");
        require(Math.abs(SystemModifierRules.sensorRange(nebula) - 0.72) < 0.001,
                "sensor-range modifier was not loaded");
        World warzone = world("warzone");
        require(Math.abs(SystemModifierRules.weaponRange(warzone) - 1.08) < 0.001,
                "weapon-range modifier was not loaded");
    }

    private static World world(String systemId) {
        World world = new World("Modifier Validator", NO_NPCS, systemId, false);
        world.activateSystem(systemId);
        return world;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
