package com.tndmadman.rts;

import java.util.Set;

public final class ProductionQueueValidator {
    private ProductionQueueValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem production queue validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("SOLO", "Queue Validator", 0x50BEFF);
        World world = new World("Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "QUEUE_TEST";

        Base yard = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        fill(yard);
        double ironBefore = yard.inventory.get(Material.IRON);
        require(world.buildShip(yard.id, "prospector"), "ship should enqueue");
        require(yard.productionQueue.size() == 1, "ship queue missing");
        require(world.units.isEmpty(), "timed ship completed immediately");
        require(yard.inventory.get(Material.IRON) < ironBefore, "ship resources were not reserved");
        ProductionJob cancelled = yard.productionQueue.get(0);
        ProductionSystem.update(world, cancelled.duration / 2.0);
        require(cancelled.remaining > 0 && cancelled.remaining < cancelled.duration, "ship progress did not advance");
        require(ProductionSystem.cancel(world, playerId, yard.id, cancelled.id), "active cancellation failed");
        require(close(yard.inventory.get(Material.IRON), ironBefore), "active cancellation did not refund resources");

        require(world.buildShip(yard.id, "prospector"), "first queue entry failed");
        require(world.buildShip(yard.id, "prospector"), "second queue entry failed");
        require(world.buildShip(yard.id, "prospector"), "third queue entry failed");
        String thirdId = yard.productionQueue.get(2).id;
        require(ProductionSystem.move(world, playerId, yard.id, thirdId, -1), "waiting job reorder failed");
        require(yard.productionQueue.get(1).id.equals(thirdId), "waiting job did not move to the requested position");
        ProductionSystem.update(world, 1000);
        require(yard.productionQueue.isEmpty(), "ship queue did not drain");
        require(countUnits(world, playerId, "prospector") == 3, "queued ships were not produced");

        Base outpost = base(world, playerId + ":B2", playerId, "outpost", 300, 300);
        fill(outpost);
        Unit deployer = new Unit(playerId, 99, "station_builder", 310, 300);
        world.units.put(deployer.key(), deployer);
        require(world.loadBasePackage(outpost.id, "shipyard"), "station package should enqueue");
        require(outpost.productionQueue.size() == 1, "station package queue missing");
        require(outpost.productionQueue.get(0).reservedUnitKey.equals(deployer.key()), "Deployer was not reserved");
        ProductionSystem.update(world, 1000);
        require("shipyard".equals(deployer.basePackageType), "completed package was not loaded into its Deployer");

        Base lab = base(world, playerId + ":B3", playerId, "laboratory", 500, 500);
        fill(lab);
        require(world.research(lab.id, "advanced_industry"), "first research should enqueue");
        ProductionJob research = lab.productionQueue.get(0);
        double researchRemaining = research.remaining;
        lab.inventory.remove(Material.FUEL);
        ProductionSystem.update(world, 10);
        require(close(research.remaining, researchRemaining), "research advanced without station fuel");
        HangarStore.add(lab.inventory, Material.FUEL, 100);
        ProductionSystem.update(world, 10);
        require(research.remaining < researchRemaining, "research did not resume after refueling");
        require(world.research(lab.id, "combat_doctrine"), "research prerequisite chain should enqueue");
        require(lab.productionQueue.size() == 2, "research chain queue missing");
        require(!ProductionSystem.cancel(world, playerId, lab.id, lab.productionQueue.get(0).id),
                "prerequisite research cancellation should be rejected while dependents remain");
        require(lab.productionQueue.size() == 2, "rejected prerequisite cancellation changed the queue");

        BaseState state = NetBaseSync.toState(lab);
        Base restored = NetBaseSync.fromState(state);
        require(restored.productionQueue.size() == 2, "network state lost queued jobs");
        require(restored.productionQueue.get(0).itemId.equals("advanced_industry"), "network state changed queue order");

        ProductionSystem.update(world, 1000);
        require(world.hasResearch(playerId, "advanced_industry"), "first research did not complete");
        require(world.hasResearch(playerId, "combat_doctrine"), "chained research did not complete");

        World instantWorld = new World("Instant Queue Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String instantPlayerId = "INSTANT_TEST";
        Base instantYard = base(instantWorld, instantPlayerId + ":B1", instantPlayerId, "shipyard", 100, 100);
        fill(instantYard);
        DevTimerSettings.configure(instantWorld, true);
        require(instantWorld.buildShip(instantYard.id, "prospector"), "instant ship should enqueue");
        require(instantYard.productionQueue.size() == 1, "instant ship queue missing before tick");
        ResearchSystem.update(instantWorld, 0.016);
        require(instantYard.productionQueue.isEmpty(), "disabled timers did not drain production");
        require(countUnits(instantWorld, instantPlayerId, "prospector") == 1, "disabled timers did not produce ship");
    }

    private static Base base(World world, String id, String playerId, String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void fill(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static int countUnits(World world, String playerId, String typeId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId) && unit.shipTypeId.equals(typeId)) count++;
        return count;
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 0.01; }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Production queue validation failed: " + message);
    }
}
