package com.tndmadman.rts;

import java.util.Set;

public final class RadarTowerValidator {
    private RadarTowerValidator() { }

    public static void main(String[] args) {
        World world = new World("Radar tower validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);

        clear(world);
        BaseType tierOne = requireType(RadarTowerRules.TIER_ONE);
        BaseType tierTwo = requireType(RadarTowerRules.TIER_TWO);
        BaseType tierThree = requireType(RadarTowerRules.TIER_THREE);

        require(RadarTowerRules.all().size() == 3, "Radar station recognition was not loaded from JSON.");
        require(RadarTowerRules.isRadarTower(tierOne.id)
                        && RadarTowerRules.isRadarTower(tierTwo.id)
                        && RadarTowerRules.isRadarTower(tierThree.id),
                "A JSON radar role was not recognized.");
        require(RadarTowerRules.tierNumber(tierOne.id) == 1, "Radar Picket JSON tier was not loaded.");
        require(RadarTowerRules.tierNumber(tierTwo.id) == 2, "Radar Array JSON tier was not loaded.");
        require(RadarTowerRules.tierNumber(tierThree.id) == 3, "Radar Nexus JSON tier was not loaded.");
        require(RadarTowerRules.sensorRange(tierOne.id) == 1_500
                        && RadarTowerRules.sensorRange(tierTwo.id) == 2_800
                        && RadarTowerRules.sensorRange(tierThree.id) == 4_800,
                "Radar sensor ranges were not loaded from JSON.");
        require(RadarTowerRules.resourceDispatchLimit(tierOne.id) < RadarTowerRules.resourceDispatchLimit(tierTwo.id)
                        && RadarTowerRules.resourceDispatchLimit(tierTwo.id) < RadarTowerRules.resourceDispatchLimit(tierThree.id),
                "Radar resource-dispatch limits do not increase by JSON tier.");
        require(RadarTowerRules.requiredResearchName(tierOne.id).isBlank(),
                "Tier-one radar unexpectedly loaded a research requirement.");
        require("Advanced Industry".equals(RadarTowerRules.requiredResearchName(tierTwo.id)),
                "Tier-two radar research was not loaded from JSON.");
        require("Battlefleet Engineering".equals(RadarTowerRules.requiredResearchName(tierThree.id)),
                "Tier-three radar research was not loaded from JSON.");
        require(totalCost(tierOne) < totalCost(tierTwo) && totalCost(tierTwo) < totalCost(tierThree),
                "Radar tower package costs do not increase by tier.");
        require(Rules.findShip("scout") == null, "Scout hull remains registered after removal.");
        require("prospector".equals(SaveContentResolver.shipId("scout")),
                "Legacy Scout ships do not migrate to Prospectors.");
        require("prospector".equals(SaveContentResolver.productionItemId(ProductionJobKind.SHIP, "scout")),
                "Queued legacy Scout builds do not migrate to Prospectors.");

        Base outpost = new Base("P1:B1", "P1", Rules.DEFAULT_BASE, 1_000, 1_000);
        world.bases.put(outpost.id, outpost);
        require(outpost.type().basePackages.contains(tierOne.id),
                "Starting Outpost cannot manufacture the tier-one radar package.");
        require(outpost.type().basePackages.contains(tierTwo.id) && outpost.type().basePackages.contains(tierThree.id),
                "Starting Outpost does not expose the advanced radar package progression.");
        BaseType shipyard = requireType("shipyard");
        require(!shipyard.buildableShips.contains("scout"), "Shipyard still exposes the removed Scout hull.");

        require(RadarTowerRules.unlocked(world, "P1", tierOne.id),
                "Tier-one radar tower unexpectedly requires research.");
        require(!RadarTowerRules.unlocked(world, "P1", tierTwo.id),
                "Tier-two radar tower was unlocked before Advanced Industry.");
        require(!RadarTowerRules.unlocked(world, "P1", tierThree.id),
                "Tier-three radar tower was unlocked before Battlefleet Engineering.");

        BuildSystem build = new BuildSystem();
        require(!build.loadBasePackage(world, outpost.id, tierTwo.id),
                "Server accepted a tier-two radar package without its JSON research requirement.");
        require(world.status.contains("Advanced Industry"),
                "Tier-two package rejection did not identify Advanced Industry.");

        world.completeResearch("P1", "advanced_industry");
        require(RadarTowerRules.unlocked(world, "P1", tierTwo.id),
                "Advanced Industry did not unlock the tier-two radar tower.");
        require(!RadarTowerRules.unlocked(world, "P1", tierThree.id),
                "Advanced Industry incorrectly unlocked the tier-three radar tower.");
        world.completeResearch("P1", "battlefleet_engineering");
        require(RadarTowerRules.unlocked(world, "P1", tierThree.id),
                "Battlefleet Engineering did not unlock the tier-three radar tower.");

        Unit deployer = new Unit("P1", 77, "station_builder", 2_000, 2_000);
        deployer.basePackageType = tierOne.id;
        world.units.put(deployer.key(), deployer);
        int basesBeforePlacement = world.bases.size();
        require(build.placePackage(world, deployer), "Loaded Deployer could not place a radar tower.");
        require(!world.units.containsKey(deployer.key()), "Radar placement did not consume its Deployer.");
        require(world.bases.size() == basesBeforePlacement + 1, "Radar placement did not create one station.");

        validateVisibility(world, tierOne.id);
        validateWorkerDispatch(world, tierOne.id);
        System.out.println("Radar tower validator passed.");
    }

    private static void validateVisibility(World world, String radarTypeId) {
        clear(world);
        Base radar = new Base("P1:B9", "P1", radarTypeId, 4_000, 4_000);
        world.bases.put(radar.id, radar);
        Unit inside = new Unit("P2", 1, "frigate", 5_450, 4_000);
        Unit outside = new Unit("P2", 2, "frigate", 5_550, 4_000);
        world.units.put(inside.key(), inside);
        world.units.put(outside.key(), outside);
        require(VisibilityRules.unitVisible(world, "P1", inside),
                "Tier-one radar did not reveal a target inside its JSON range.");
        require(!VisibilityRules.unitVisible(world, "P1", outside),
                "Tier-one radar revealed a target beyond its JSON range.");
    }

    private static void validateWorkerDispatch(World world, String radarTypeId) {
        clear(world);
        Base radar = new Base("P1:B10", "P1", radarTypeId, 4_000, 4_000);
        world.bases.put(radar.id, radar);
        ResourceNode node = new ResourceNode(1, "Radar iron", NodeKind.SILICATE_ROCK,
                Material.IRON, 5_000, 4_000, 500, 5, 3);
        world.resources.add(node);
        for (int i = 1; i <= 4; i++) {
            Unit miner = new Unit("P1", i, "prospector", 1_000, 1_000 + i * 20);
            world.units.put(miner.key(), miner);
        }
        world.updateCurrentSystem(0.1);
        int assigned = 0;
        for (Unit unit : world.units.values()) {
            if (unit.task == UnitTask.AUTO_HARVEST && unit.automationResourceId == node.id) assigned++;
        }
        require(assigned == RadarTowerRules.resourceDispatchLimit(radarTypeId),
                "Radar did not dispatch the JSON-configured number of workers.");
    }

    private static void clear(World world) {
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
    }

    private static BaseType requireType(String id) {
        BaseType type = Rules.findBase(id);
        if (type == null) throw new IllegalStateException("Missing station type: " + id);
        return type;
    }

    private static double totalCost(BaseType type) {
        double total = 0;
        for (Cost cost : type.buildCost) total += Math.max(0, cost.amount());
        return total;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
