package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class NpcCrossSystemOperationsValidator {
    private NpcCrossSystemOperationsValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem cross-system NPC operations validation passed.");
    }

    static void validateOrThrow() {
        validateGalaxyWideCaps();
        validateCommittedCapacityAccounting();
        validateConstructionCleanup();
        validateFactionScopedReset();
        validateRemoteRepairEvacuation();
    }

    private static void validateGalaxyWideCaps() {
        Fixture fixture = fixture("Galaxy Capacity Caps");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        clearFactionAssets(world, faction.id());

        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        addBase(world, faction, "outpost", 1);
        addBase(world, faction, "shipyard", 2);
        addCategoryUnits(world, faction, 10_000,
                Math.max(1, faction.targetFleetSize() / 2),
                Math.max(1, faction.maxWorkers() / 2),
                Math.max(1, faction.maxSupportUnits() / 2),
                Math.max(1, faction.maxIndustryUnits() / 2));
        stockFactionBases(world, faction);
        world.saveActiveSystem();

        world.activateSystem("red_dwarf");
        addBase(world, faction, "outpost", 3);
        addBase(world, faction, "manufacturing", 4);
        addCategoryUnits(world, faction, 20_000,
                faction.targetFleetSize() - Math.max(1, faction.targetFleetSize() / 2),
                faction.maxWorkers() - Math.max(1, faction.maxWorkers() / 2),
                faction.maxSupportUnits() - Math.max(1, faction.maxSupportUnits() / 2),
                faction.maxIndustryUnits() - Math.max(1, faction.maxIndustryUnits() / 2));
        stockFactionBases(world, faction);
        world.saveActiveSystem();

        world.completeResearch(faction.id(), "advanced_industry");
        world.completeResearch(faction.id(), "combat_doctrine");
        world.completeResearch(faction.id(), "battlefleet_engineering");
        NpcStrategicDirector.onSpawned(world, faction);

        NpcFactionCapacitySnapshot before = NpcFactionCapacitySystem.snapshot(world, faction);
        require(before.stationCommitments() == faction.maxStations(),
                "fixture did not reach the global station cap");
        require(before.combat() == faction.targetFleetSize(),
                "fixture did not reach the global combat cap");
        require(before.workers() == faction.maxWorkers(),
                "fixture did not reach the global worker cap");
        require(before.support() == faction.maxSupportUnits(),
                "fixture did not reach the global support cap");
        require(before.industry() == faction.maxIndustryUnits(),
                "fixture did not reach the global industry cap");

        NpcSystem homeController = organizedController();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        homeController.update(world, 100.0);
        world.saveActiveSystem();
        NpcSystem remoteController = organizedController();
        world.activateSystem("red_dwarf");
        remoteController.update(world, 100.0);
        world.saveActiveSystem();

        NpcFactionCapacitySnapshot after = NpcFactionCapacitySystem.snapshot(world, faction);
        require(after.stationCommitments() == faction.maxStations(),
                "local systems exceeded the galaxy-wide station cap");
        require(after.combat() == faction.targetFleetSize(),
                "local systems exceeded the galaxy-wide combat cap");
        require(after.workers() == faction.maxWorkers(),
                "local systems exceeded the galaxy-wide worker cap");
        require(after.support() == faction.maxSupportUnits(),
                "local systems exceeded the galaxy-wide support cap");
        require(after.industry() == faction.maxIndustryUnits(),
                "local systems exceeded the galaxy-wide industry cap");
        require(!NpcStationConstructionSystem.hasAnyActivePlan(world, faction),
                "station construction started despite the global station cap");
    }

    private static void validateCommittedCapacityAccounting() {
        Fixture fixture = fixture("Committed Capacity Accounting");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        clearFactionAssets(world, faction.id());
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Base source = addBase(world, faction, "outpost", 1);
        stock(source);
        Unit builder = addUnit(world, faction, 30_001, "station_builder",
                source.x + 80, source.y);
        ProductionJob queuedCombat = new ProductionJob(
                "CAPACITY_COMBAT", ProductionJobKind.SHIP,
                faction.fleetUnitTypes().get(0), 10, 10, true, "");
        source.productionQueue.add(queuedCombat);
        require(NpcStationConstructionSystem.start(world, faction, source, builder,
                        "shipyard", NpcBudgetCategory.STATION_RECOVERY),
                "capacity fixture could not start a construction plan");

        NpcFactionCapacitySnapshot snapshot = NpcFactionCapacitySystem.snapshot(world, faction);
        require(snapshot.combat() == 1,
                "queued combat production was not counted toward global capacity");
        require(snapshot.stationCommitments() == 2,
                "active construction was not counted toward global station capacity");
    }

    private static void validateConstructionCleanup() {
        Fixture missingBuilder = fixture("Missing Builder Cleanup");
        World world = missingBuilder.world;
        NpcFaction faction = missingBuilder.faction;
        clearFactionAssets(world, faction.id());
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Base source = addBase(world, faction, "outpost", 1);
        stock(source);
        Unit builder = addUnit(world, faction, 40_001, "station_builder",
                source.x + 80, source.y);
        require(NpcStationConstructionSystem.start(world, faction, source, builder,
                        "shipyard", NpcBudgetCategory.STATION_RECOVERY),
                "missing-builder fixture could not start construction");
        world.units.remove(builder.key());
        world.bases.remove(source.id);
        world.saveActiveSystem();
        world.activateSystem("red_dwarf");
        addBase(world, faction, "outpost", 2);
        world.saveActiveSystem();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        new NpcGalaxyDirector().update(world, 1.0);
        require(!NpcStationConstructionSystem.hasAnyActivePlan(world, faction),
                "plan survived after its final local deployer disappeared");

        Fixture blocked = fixture("No Construction Site Timeout");
        world = blocked.world;
        faction = blocked.faction;
        clearFactionAssets(world, faction.id());
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        source = addBase(world, faction, "outpost", 1);
        stock(source);
        builder = addUnit(world, faction, 40_101, "station_builder",
                source.x + 80, source.y);
        require(NpcStationConstructionSystem.start(world, faction, source, builder,
                        "shipyard", NpcBudgetCategory.STATION_RECOVERY),
                "no-site fixture could not start construction");
        obstructConstructionRings(world, source);
        for (int i = 0; i < 4; i++) {
            NpcStationConstructionSystem.update(world, faction, 1.0);
        }
        require(!NpcStationConstructionSystem.hasAnyActivePlan(world, faction),
                "permanently blocked construction plan did not reach a terminal outcome");
    }

    private static void validateFactionScopedReset() {
        Fixture fixture = fixture("Faction Scoped Reset");
        World world = fixture.world;
        NpcFaction first = fixture.faction;
        NpcFaction second = cloneFaction(first, "NPC_SECOND_ORGANIZED", "Second Organized Faction");
        clearFactionAssets(world, first.id());
        clearFactionAssets(world, second.id());
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        Base firstBase = addBase(world, first, "outpost", 1);
        Base secondBase = addBase(world, second, "outpost", 2);
        stock(firstBase);
        stock(secondBase);
        Unit firstCombat = addUnit(world, first, 50_001, "frigate",
                firstBase.x + 100, firstBase.y);
        Unit secondCombat = addUnit(world, second, 50_101, "frigate",
                secondBase.x + 100, secondBase.y);
        Unit secondBuilder = addUnit(world, second, 50_102, "station_builder",
                secondBase.x + 80, secondBase.y + 60);
        NpcSquadCombatSystem.update(world, first, NpcStrategicState.FORTIFY, 1.0);
        NpcSquadCombatSystem.update(world, second, NpcStrategicState.FORTIFY, 1.0);
        require(!NpcSquadCombatSystem.snapshot(world, first).squads().isEmpty(),
                "first faction squad runtime was not created");
        require(!NpcSquadCombatSystem.snapshot(world, second).squads().isEmpty(),
                "second faction squad runtime was not created");
        require(NpcStationConstructionSystem.start(world, second, secondBase,
                        secondBuilder, "shipyard", NpcBudgetCategory.STATION_RECOVERY),
                "second faction construction runtime was not created");

        world.resetOrganizedNpcFactionState(first, NpcFactionResetReason.DEV_RESET);
        require(NpcSquadCombatSystem.snapshot(world, first).squads().isEmpty(),
                "reset faction retained its squad runtime");
        require(!NpcSquadCombatSystem.snapshot(world, second).squads().isEmpty(),
                "resetting one faction cleared another faction's squad runtime");
        require(NpcStationConstructionSystem.snapshot(world, second).active(),
                "resetting one faction cleared another faction's construction plan");
        require(world.units.containsKey(firstCombat.key())
                        && world.units.containsKey(secondCombat.key()),
                "runtime reset incorrectly deleted living faction assets");
    }

    private static void validateRemoteRepairEvacuation() {
        Fixture fixture = fixture("Remote Repair Evacuation");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        clearFactionAssets(world, faction.id());
        String home = StarSystems.CORSAIR_SYSTEM_ID;
        String sourceSystem = adjacentNonHomeSystem(world, home);

        world.activateSystem(home);
        Base homeBase = addBase(world, faction, "outpost", 1);
        homeBase.inventory.put(Material.IRON, 1000.0);
        homeBase.inventory.put(Material.COPPER, 1000.0);
        world.saveActiveSystem();

        world.activateSystem(sourceSystem);
        world.resources.clear();
        Base depleted = addBase(world, faction, "outpost", 2);
        depleted.inventory.clear();
        Unit damaged = addUnit(world, faction, 60_001, "frigate",
                depleted.x + 100, depleted.y);
        damaged.hp = damaged.type().maxHp * 0.25;
        Unit escort = addUnit(world, faction, 60_002, "destroyer",
                depleted.x + 160, depleted.y);
        world.saveActiveSystem();

        for (int i = 0; i < 25; i++) {
            world.activateSystem(sourceSystem);
            NpcRepairEvacuationSystem.update(world, faction, 1.0);
        }
        world.activateSystem(sourceSystem);
        WormholeGate gate = gateAt(world, damaged.targetX, damaged.targetY);
        require(gate != null,
                "depleted remote station did not produce a wormhole evacuation order");
        require(home.equals(gate.toSystemId),
                "repair evacuation did not select the resource-rich home system");
        require(damaged.task == UnitTask.MOVE,
                "damaged ship was not moving along the evacuation route");
        require(escort.task == UnitTask.MOVE,
                "healthy repair escort did not join the evacuation");

        damaged.x = gate.x;
        damaged.y = gate.y;
        escort.x = gate.x;
        escort.y = gate.y;
        require(world.transferTouchingShips(faction.id()),
                "repair evacuation ships did not transfer through the wormhole");
        world.activateSystem(home);
        NpcRepairEvacuationSystem.update(world, faction, 1.0);
        require(world.units.containsKey(damaged.key()),
                "damaged ship did not arrive in the friendly repair system");
        require(!NpcRepairEvacuationSystem.ownsUnit(world, damaged),
                "completed repair evacuation retained ownership of the ship");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        return new Fixture(world, corsairs());
    }

    private static NpcSystem organizedController() {
        return new NpcSystem(Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID));
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static NpcFaction cloneFaction(NpcFaction source, String id, String name) {
        return new NpcFaction(id, name, source.rgb(), true, NpcBehavior.FACTION,
                source.firstSpawnSeconds(), source.respawnSeconds(), source.orderSeconds(),
                source.baseType(), source.startingUnits(), source.workerUnitTypes(),
                source.fleetUnitTypes(), source.supportUnitTypes(), source.stationPackageTypes(),
                source.industryUnitTypes(), source.researchTopicIds(), source.craftableItemIds(),
                source.maxWorkers(), source.targetFleetSize(), source.raidFleetSize(),
                source.harassFleetSize(), source.maxSupportUnits(), source.maxStations(),
                source.maxIndustryUnits(), source.buildSeconds(), source.stationBuildSeconds(),
                source.defendRange(), source.raidCooldownSeconds(), source.retreatHpPercent(),
                source.stationSpacing(), source.fuelReserve(), source.spawnDistance(),
                source.spawnPadding(), source.unitSpacing(), EnumSet.copyOf(source.targetMaterials()),
                source.harvestNodeKinds().isEmpty() ? EnumSet.noneOf(NodeKind.class)
                        : EnumSet.copyOf(source.harvestNodeKinds()),
                source.attackBases(), source.attackUnits(), source.attackNpcFactions(),
                false, source.harassWorkers(), source.preferWorkerTargets(),
                false, 0, name + " spawned.");
    }

    private static void clearFactionAssets(World world, String factionId) {
        String previous = world.activeSystemId();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapSystem system : map.systems()) {
            world.activateSystem(system.id());
            world.units.values().removeIf(unit -> factionId.equals(unit.playerId));
            world.bases.values().removeIf(base -> factionId.equals(base.playerId));
            world.saveActiveSystem();
        }
        if (previous != null && !previous.isBlank()) world.activateSystem(previous);
    }

    private static Base addBase(World world, NpcFaction faction, String type, int ordinal) {
        Base base = new Base(faction.id() + ":B" + ordinal, faction.id(), type,
                world.width * 0.35 + ordinal * 260,
                world.height * 0.42 + ordinal * 170);
        world.bases.put(base.id, base);
        return base;
    }

    private static Unit addUnit(World world, NpcFaction faction, int id,
                                String type, double x, double y) {
        Unit unit = new Unit(faction.id(), id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static void addCategoryUnits(World world, NpcFaction faction, int firstId,
                                         int combat, int workers, int support, int industry) {
        int id = firstId;
        for (int i = 0; i < combat; i++) {
            String type = faction.fleetUnitTypes().get(i % faction.fleetUnitTypes().size());
            addUnit(world, faction, id++, type,
                    world.width * 0.45 + i * 35, world.height * 0.45);
        }
        for (int i = 0; i < workers; i++) {
            addUnit(world, faction, id++, faction.workerUnitTypes().get(0),
                    world.width * 0.46 + i * 35, world.height * 0.48);
        }
        for (int i = 0; i < support; i++) {
            String type = faction.supportUnitTypes().get(i % faction.supportUnitTypes().size());
            addUnit(world, faction, id++, type,
                    world.width * 0.47 + i * 35, world.height * 0.51);
        }
        for (int i = 0; i < industry; i++) {
            String type = faction.industryUnitTypes().get(i % faction.industryUnitTypes().size());
            addUnit(world, faction, id++, type,
                    world.width * 0.48 + i * 35, world.height * 0.54);
        }
    }

    private static void stockFactionBases(World world, NpcFaction faction) {
        for (Base base : world.bases.values()) {
            if (faction.id().equals(base.playerId)) stock(base);
        }
    }

    private static void stock(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static void obstructConstructionRings(World world, Base source) {
        int id = 1;
        for (double radius = 250; radius <= 1250; radius += 250) {
            for (int i = 0; i < 24; i++) {
                double angle = i * Math.PI * 2.0 / 24.0;
                Base obstruction = new Base("BLOCK:" + id++, "BLOCKER", "outpost",
                        source.x + Math.cos(angle) * radius,
                        source.y + Math.sin(angle) * radius);
                world.bases.put(obstruction.id, obstruction);
            }
        }
    }

    private static String adjacentNonHomeSystem(World world, String home) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapLink link : map.links()) {
            String candidate = link.fromSystemId().equals(home) ? link.toSystemId()
                    : link.toSystemId().equals(home) ? link.fromSystemId() : "";
            if (candidate.isBlank()) continue;
            for (GalaxyMapSystem system : map.systems()) {
                if (candidate.equals(system.id()) && !system.home()) return candidate;
            }
        }
        throw new IllegalStateException("Corsair home has no non-home adjacent system");
    }

    private static WormholeGate gateAt(World world, double x, double y) {
        for (WormholeGate gate : world.wormholes) {
            if (Calc.distance(gate.x, gate.y, x, y) < 1.0) return gate;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction) { }
}
