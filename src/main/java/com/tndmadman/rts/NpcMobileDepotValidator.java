package com.tndmadman.rts;

import java.util.Set;

public final class NpcMobileDepotValidator {
    private static final double EPSILON = 0.001;

    private NpcMobileDepotValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC mobile depot validation passed.");
    }

    static void validateOrThrow() {
        validateFreightersSpreadAcrossMiningClusters();
        validateHaulersBalanceDepotClaims();
        validateDemandAwareStationDelivery();
    }

    private static void validateFreightersSpreadAcrossMiningClusters() {
        Fixture fixture = fixture("Mobile Depot Distribution");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;

        ResourceNode west = node(world, 96_001, Material.IRON,
                world.width * 0.22, world.height * 0.28);
        ResourceNode east = node(world, 96_002, Material.COPPER,
                world.width * 0.78, world.height * 0.72);
        Unit westMinerA = unit(world, faction, 96_101, "prospector", west.x - 80, west.y);
        Unit westMinerB = unit(world, faction, 96_102, "prospector", west.x + 70, west.y + 40);
        Unit eastMinerA = unit(world, faction, 96_103, "prospector", east.x - 60, east.y);
        Unit eastMinerB = unit(world, faction, 96_104, "prospector", east.x + 75, east.y - 35);
        westMinerA.automationResourceId = west.id;
        westMinerB.automationResourceId = west.id;
        eastMinerA.automationResourceId = east.id;
        eastMinerB.automationResourceId = east.id;

        Unit first = unit(world, faction, 96_201, "freighter",
                world.width * 0.48, world.height * 0.5);
        Unit second = unit(world, faction, 96_202, "freighter",
                world.width * 0.52, world.height * 0.5);

        NpcMobileDepotSystem.update(world, faction);
        double firstX = first.targetX;
        double firstY = first.targetY;
        double secondX = second.targetX;
        double secondY = second.targetY;
        require(Calc.distance(firstX, firstY, secondX, secondY) >= 700.0,
                "multiple freighters were assigned overlapping depot anchors");
        require(nearestAnchorDistance(west, first, second) < 900.0,
                "no freighter covered the western mining cluster");
        require(nearestAnchorDistance(east, first, second) < 900.0,
                "no freighter covered the eastern mining cluster");

        NpcMobileDepotSystem.update(world, faction);
        require(Math.abs(first.targetX - firstX) < EPSILON
                        && Math.abs(first.targetY - firstY) < EPSILON
                        && Math.abs(second.targetX - secondX) < EPSILON
                        && Math.abs(second.targetY - secondY) < EPSILON,
                "stable mining clusters produced jittering freighter assignments");
    }

    private static void validateHaulersBalanceDepotClaims() {
        Fixture fixture = fixture("Mobile Depot Claim Balance");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        Unit firstDepot = unit(world, faction, 96_301, "freighter", 2000, 2200);
        Unit secondDepot = unit(world, faction, 96_302, "freighter", 3600, 2200);
        firstDepot.inventory.put(Material.IRON, 220.0);
        secondDepot.inventory.put(Material.COPPER, 220.0);
        Unit firstHauler = unit(world, faction, 96_311, "hauler", 2750, 2200);
        Unit secondHauler = unit(world, faction, 96_312, "hauler", 2850, 2200);
        HaulerSystem system = new HaulerSystem();

        system.update(world, firstHauler, 0.1);
        system.update(world, secondHauler, 0.1);
        require(firstHauler.logisticsRequestId.startsWith("MOBILE_DEPOT:"),
                "first hauler did not claim a loaded mobile depot");
        require(secondHauler.logisticsRequestId.startsWith("MOBILE_DEPOT:"),
                "second hauler did not claim a loaded mobile depot");
        require(!firstHauler.logisticsRequestId.equals(secondHauler.logisticsRequestId),
                "two haulers claimed the same small depot while another loaded depot waited");
    }

    private static void validateDemandAwareStationDelivery() {
        Fixture fixture = fixture("Mobile Depot Demand Delivery");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        Base warehouse = new Base(fixture.outpost.id, faction.id(), "outpost",
                2100, 2100);
        world.bases.put(warehouse.id, warehouse);
        Base laboratory = new Base(faction.id() + ":LAB", faction.id(), "laboratory",
                4700, 4300);
        world.bases.put(laboratory.id, laboratory);
        require(StationFuelRules.requirement(laboratory.typeId) != null,
                "laboratory fixture has no configured fuel requirement");

        Unit depot = unit(world, faction, 96_401, "freighter", 2500, 2200);
        Unit hauler = unit(world, faction, 96_402, "hauler", 2500, 2200);
        depot.inventory.put(Material.FUEL, 240.0);
        double before = total(world, faction.id(), Material.FUEL);
        HaulerSystem system = new HaulerSystem();

        int guard = 0;
        boolean routedToLab = false;
        while (guard++ < 160 && laboratory.inventory.getOrDefault(Material.FUEL, 0.0) < 239.0) {
            system.update(world, hauler, 1.0);
            if (laboratory.id.equals(hauler.logisticsTargetBaseId)) routedToLab = true;
            hauler.updatePosition(1.0, world.width, world.height);
        }

        require(routedToLab,
                "hauler chose the closer warehouse instead of the fuel-starved laboratory");
        require(laboratory.inventory.getOrDefault(Material.FUEL, 0.0) > 200.0,
                "hauler did not empty the freighter into the station that needed its cargo");
        require(warehouse.inventory.getOrDefault(Material.FUEL, 0.0) <= EPSILON,
                "mobile-depot cargo was dumped into the nearest unrelated station");
        require(depot.cargoUsed() <= EPSILON && hauler.cargoUsed() <= EPSILON,
                "mobile-depot delivery left cargo stranded in the freighter or hauler");
        require(Math.abs(total(world, faction.id(), Material.FUEL) - before) < EPSILON,
                "mobile-depot hauling duplicated or destroyed cargo");
    }

    private static double nearestAnchorDistance(ResourceNode node, Unit... depots) {
        double best = Double.MAX_VALUE;
        for (Unit depot : depots) {
            best = Math.min(best,
                    Calc.distance(node.x, node.y, depot.targetX, depot.targetY));
        }
        return best;
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        NpcFaction faction = corsairs();
        Base outpost = new Base(faction.id() + ":B1", faction.id(), "outpost",
                world.width * 0.5, world.height * 0.5);
        world.bases.put(outpost.id, outpost);
        return new Fixture(world, faction, outpost);
    }

    private static ResourceNode node(World world, int id, Material material,
                                     double x, double y) {
        ResourceNode node = new ResourceNode(id, material.label + " validator node",
                NodeKind.SILICATE_ROCK, material, x, y, 5000, 10, 42);
        world.resources.add(node);
        return node;
    }

    private static Unit unit(World world, NpcFaction faction, int id,
                             String type, double x, double y) {
        Unit unit = new Unit(faction.id(), id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static double total(World world, String playerId, Material material) {
        double total = 0;
        for (Base base : world.bases.values()) {
            if (playerId.equals(base.playerId)) {
                total += base.inventory.getOrDefault(material, 0.0);
            }
        }
        for (Unit unit : world.units.values()) {
            if (playerId.equals(unit.playerId)) {
                total += unit.inventory.getOrDefault(material, 0.0);
            }
        }
        return total;
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction, Base outpost) { }
}
