package com.tndmadman.rts;

import java.util.Set;

/** Regression coverage for Shipyard-built station packages loaded into Deployers. */
public final class ShipyardStationPackageValidator {
    private ShipyardStationPackageValidator() { }

    public static void main(String[] args) {
        PlayerRegistry.reset("SOLO", "Shipyard Package Validator", 0x50BEFF);
        World world = new World("Shipyard Package Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        String playerId = "SHIPYARD_PACKAGE_TEST";

        Base shipyard = new Base(playerId + ":B1", playerId, "shipyard", 100, 100);
        world.bases.put(shipyard.id, shipyard);
        for (Material material : Material.values()) shipyard.inventory.put(material, 100_000.0);

        Unit deployer = new Unit(playerId, 1, "station_builder", 112, 100);
        world.units.put(deployer.key(), deployer);

        require(shipyard.type().basePackages.contains("shipyard"),
                "Shipyard rules do not expose station packages");
        require(world.loadBasePackage(shipyard.id, "shipyard"),
                "Shipyard rejected a station package request");
        require(shipyard.productionQueue.size() == 1,
                "Shipyard station package did not enter the production queue");
        require(deployer.key().equals(shipyard.productionQueue.get(0).reservedUnitKey),
                "Shipyard package did not reserve the available Deployer");

        ProductionSystem.update(world, 1_000.0);
        require(shipyard.productionQueue.isEmpty(),
                "Shipyard station package did not finish production");
        require("shipyard".equals(deployer.basePackageType),
                "completed Shipyard package was not loaded into the Deployer");

        System.out.println("StarChem Shipyard station package validation passed.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Shipyard station package validation failed: " + message);
    }
}
