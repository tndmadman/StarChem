package com.tndmadman.rts;

import java.util.Set;

public final class RadarTowerValidator {
    private RadarTowerValidator() { }

    public static void main(String[] args) {
        World world = new World("Radar tower validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);

        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();

        BaseType tierOne = requireType(RadarTowerRules.TIER_ONE);
        BaseType tierTwo = requireType(RadarTowerRules.TIER_TWO);
        BaseType tierThree = requireType(RadarTowerRules.TIER_THREE);

        require(RadarTowerRules.tierNumber(tierOne.id) == 1, "Radar Picket was not registered as tier one.");
        require(RadarTowerRules.tierNumber(tierTwo.id) == 2, "Radar Array was not registered as tier two.");
        require(RadarTowerRules.tierNumber(tierThree.id) == 3, "Radar Nexus was not registered as tier three.");
        require(RadarTowerRules.sensorRange(tierOne.id) < RadarTowerRules.sensorRange(tierTwo.id)
                        && RadarTowerRules.sensorRange(tierTwo.id) < RadarTowerRules.sensorRange(tierThree.id),
                "Radar tower sensor ranges do not increase by tier.");
        require(totalCost(tierOne) < totalCost(tierTwo) && totalCost(tierTwo) < totalCost(tierThree),
                "Radar tower package costs do not increase by tier.");

        Base outpost = new Base("P1:B1", "P1", Rules.DEFAULT_BASE, 1_000, 1_000);
        world.bases.put(outpost.id, outpost);
        require(outpost.type().basePackages.contains(RadarTowerRules.TIER_ONE),
                "Starting Outpost cannot manufacture the tier-one radar package.");
        require(outpost.type().basePackages.contains(RadarTowerRules.TIER_TWO)
                        && outpost.type().basePackages.contains(RadarTowerRules.TIER_THREE),
                "Starting Outpost does not expose the advanced radar package progression.");

        require(RadarTowerRules.unlocked(world, "P1", RadarTowerRules.TIER_ONE),
                "Tier-one radar tower unexpectedly requires research.");
        require(!RadarTowerRules.unlocked(world, "P1", RadarTowerRules.TIER_TWO),
                "Tier-two radar tower was unlocked before Advanced Industry.");
        require(!RadarTowerRules.unlocked(world, "P1", RadarTowerRules.TIER_THREE),
                "Tier-three radar tower was unlocked before Battlefleet Engineering.");

        BuildSystem build = new BuildSystem();
        require(!build.loadBasePackage(world, outpost.id, RadarTowerRules.TIER_TWO),
                "Server accepted a tier-two radar package without its research.");
        require(world.status.contains("Advanced Industry"),
                "Tier-two package rejection did not identify Advanced Industry.");

        world.completeResearch("P1", "advanced_industry");
        require(RadarTowerRules.unlocked(world, "P1", RadarTowerRules.TIER_TWO),
                "Advanced Industry did not unlock the tier-two radar tower.");
        require(!RadarTowerRules.unlocked(world, "P1", RadarTowerRules.TIER_THREE),
                "Advanced Industry incorrectly unlocked the tier-three radar tower.");

        world.completeResearch("P1", "battlefleet_engineering");
        require(RadarTowerRules.unlocked(world, "P1", RadarTowerRules.TIER_THREE),
                "Battlefleet Engineering did not unlock the tier-three radar tower.");

        Unit deployer = new Unit("P1", 77, "station_builder", 2_000, 2_000);
        deployer.basePackageType = RadarTowerRules.TIER_ONE;
        world.units.put(deployer.key(), deployer);
        int basesBeforePlacement = world.bases.size();
        require(build.placePackage(world, deployer), "Loaded Deployer could not place a radar tower.");
        require(!world.units.containsKey(deployer.key()), "Radar placement did not consume its Deployer.");
        require(world.bases.size() == basesBeforePlacement + 1, "Radar placement did not create one station.");
        Base placed = null;
        for (Base base : world.bases.values()) {
            if (RadarTowerRules.TIER_ONE.equals(base.typeId)) placed = base;
        }
        require(placed != null, "Placed radar tower was not present in the world.");

        world.bases.clear();
        world.units.clear();
        Base radar = new Base("P1:B9", "P1", RadarTowerRules.TIER_ONE, 4_000, 4_000);
        world.bases.put(radar.id, radar);
        Unit inside = new Unit("P2", 1, "frigate", 5_450, 4_000);
        Unit outside = new Unit("P2", 2, "frigate", 5_550, 4_000);
        world.units.put(inside.key(), inside);
        world.units.put(outside.key(), outside);
        require(VisibilityRules.unitVisible(world, "P1", inside),
                "Tier-one radar did not reveal a target inside its configured range.");
        require(!VisibilityRules.unitVisible(world, "P1", outside),
                "Tier-one radar revealed a target beyond its configured range.");

        System.out.println("Radar tower validator passed.");
    }

    private static BaseType requireType(String id) {
        BaseType type = Rules.findBase(id);
        if (type == null) throw new IllegalStateException("Missing radar tower station type: " + id);
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
