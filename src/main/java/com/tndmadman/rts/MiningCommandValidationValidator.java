package com.tndmadman.rts;

/** Focused regression validation for authoritative mining target handling. */
public final class MiningCommandValidationValidator {
    private static final int MINER_ID = 9001;
    private static final int NON_MINER_ID = 9002;
    private static final int MISSING_RESOURCE_ID = Integer.MAX_VALUE;

    private MiningCommandValidationValidator() { }

    public static void main(String[] args) {
        World world = new World("Mining Validation");
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Mining Validation", 0x50BEFF);

        Fixture fixture = fixture(world);
        Unit miner = new Unit("SOLO", MINER_ID, fixture.ship.id, fixture.node.x, fixture.node.y);
        world.units.clear();
        world.units.put(miner.key(), miner);

        validateAuthoritativeOrders(world, miner, fixture.node);
        validateInvalidStateRecovery(world, miner, fixture.node);

        ShipType nonMiningShip = nonMiningShip();
        if (nonMiningShip != null) validateIncompatibleShip(world, nonMiningShip, fixture.node);

        System.out.println("StarChem mining command validation passed.");
    }

    private static void validateAuthoritativeOrders(World world, Unit miner, ResourceNode node) {
        reset(miner);
        AUnitWork.apply(world, new HarvestCommand(miner.playerId, miner.unitId, node.id));
        require(miner.task == UnitTask.AUTO_HARVEST, "valid mining order was rejected");
        require(miner.automationResourceId == node.id, "valid mining target ID was not retained");
        require(miner.miningAnchorSet && miner.miningAnchorX == node.x && miner.miningAnchorY == node.y,
                "valid mining order did not set the authoritative anchor");

        validateHiddenResourceOrder(world, miner, node);

        reset(miner);
        AUnitWork.apply(world, new HarvestCommand(miner.playerId, miner.unitId, MISSING_RESOURCE_ID));
        requireRejectedOrder(miner, "missing resource order changed authoritative state");

        reset(miner);
        boolean active = node.active;
        node.active = false;
        AUnitWork.apply(world, new HarvestCommand(miner.playerId, miner.unitId, node.id));
        requireRejectedOrder(miner, "inactive resource order changed authoritative state");
        node.active = active;

        reset(miner);
        double amount = node.amount;
        node.amount = 0.05;
        AUnitWork.apply(world, new HarvestCommand(miner.playerId, miner.unitId, node.id));
        requireRejectedOrder(miner, "depleted resource order changed authoritative state");
        node.amount = amount;
    }

    private static void validateHiddenResourceOrder(World world, Unit miner, ResourceNode node) {
        double minerX = miner.x;
        double minerY = miner.y;
        double nodeX = node.x;
        double nodeY = node.y;
        try {
            reset(miner);
            miner.x = 100;
            miner.y = 100;
            miner.targetX = miner.x;
            miner.targetY = miner.y;
            node.x = Math.max(2_000, world.width - 100);
            node.y = Math.max(2_000, world.height - 100);
            AUnitWork.apply(world, new HarvestCommand(miner.playerId, miner.unitId, node.id));
            requireRejectedOrder(miner, "hidden resource ID bypassed authoritative sensor validation");
        } finally {
            miner.x = minerX;
            miner.y = minerY;
            miner.targetX = minerX;
            miner.targetY = minerY;
            node.x = nodeX;
            node.y = nodeY;
            reset(miner);
        }
    }

    private static void validateInvalidStateRecovery(World world, Unit miner, ResourceNode node) {
        WorkSystem work = new WorkSystem();

        world.resources.clear();
        reset(miner);
        miner.startAutoHarvest(MISSING_RESOURCE_ID);
        work.update(world, miner, 0.1);
        requireClearedTarget(miner, "missing legacy mining target was not cleared");

        reset(miner);
        miner.addCargo(node.material, miner.type().cargoCapacity);
        miner.startAutoHarvest(MISSING_RESOURCE_ID);
        work.update(world, miner, 0.1);
        requireClearedTarget(miner, "missing full-cargo target was retained");

        world.resources.add(node);
        reset(miner);
        boolean active = node.active;
        node.active = false;
        miner.startAutoHarvest(node.id);
        work.update(world, miner, 0.1);
        requireClearedTarget(miner, "inactive legacy mining target was not cleared");
        node.active = active;

        reset(miner);
        node.active = true;
        node.amount = Math.min(0.01, node.maxAmount);
        miner.x = node.x;
        miner.y = node.y;
        miner.targetX = node.x;
        miner.targetY = node.y;
        miner.startAutoHarvest(node.id);
        work.update(world, miner, 1.0);
        requireClearedTarget(miner, "depleted target ID was retained after mining");
    }

    private static void validateIncompatibleShip(World world, ShipType nonMiningShip, ResourceNode node) {
        node.active = true;
        node.amount = Math.max(1.0, node.amount);
        Unit unit = new Unit("SOLO", NON_MINER_ID, nonMiningShip.id, node.x, node.y);
        world.units.put(unit.key(), unit);

        AUnitWork.apply(world, new HarvestCommand(unit.playerId, unit.unitId, node.id));
        requireRejectedOrder(unit, "non-mining ship accepted an authoritative mining order");

        unit.startAutoHarvest(node.id);
        new WorkSystem().update(world, unit, 0.1);
        requireClearedTarget(unit, "incompatible legacy mining assignment was not cleared");
    }

    private static Fixture fixture(World world) {
        for (ShipType ship : Rules.SHIPS.values()) {
            if (ship.harvestKinds.isEmpty()) continue;
            for (ResourceNode node : world.resources) {
                if (node.active && node.amount > 0.05 && ship.harvestKinds.contains(node.kind)) {
                    return new Fixture(ship, node);
                }
            }
        }
        throw new IllegalStateException("No compatible mining ship and resource fixture was available.");
    }

    private static ShipType nonMiningShip() {
        for (ShipType ship : Rules.SHIPS.values()) if (ship.harvestKinds.isEmpty()) return ship;
        return null;
    }

    private static void reset(Unit unit) {
        unit.inventory.clear();
        unit.task = UnitTask.IDLE;
        unit.automationResourceId = -1;
        unit.miningAnchorSet = false;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
    }

    private static void requireRejectedOrder(Unit unit, String message) {
        require(unit.task == UnitTask.IDLE
                && unit.automationResourceId == -1
                && !unit.miningAnchorSet, message);
    }

    private static void requireClearedTarget(Unit unit, String message) {
        require(unit.automationResourceId == -1 && unit.task != UnitTask.AUTO_HARVEST, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(ShipType ship, ResourceNode node) { }
}
