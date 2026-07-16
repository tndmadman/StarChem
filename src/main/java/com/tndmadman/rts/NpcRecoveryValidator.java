package com.tndmadman.rts;

import java.util.Set;

public final class NpcRecoveryValidator {
    private static final double EPSILON = 0.001;

    private NpcRecoveryValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC recovery validation passed.");
    }

    static void validateOrThrow() {
        validatePhysicalPaidRepairAndEscortOwnership();
        validateEmergencyRebuildFromCargo();
        validateLoadedPackageRecoveryRules();
        validatePhysicalWormholeEvacuation();
        validateImpossibleGroupsAreScuttled();
        validateReachableMiningRecovery();
        validateBlockedRepairEconomy();
        validateRemoteSurvivorAndControlledRespawn();
        validateFreshSpawnResetsTacticalTimers();
    }

    private static void validatePhysicalPaidRepairAndEscortOwnership() {
        Fixture fixture = fixture("NPC Physical Paid Repair", StarSystems.CORSAIR_SYSTEM_ID);
        Base base = addBase(fixture, "outpost", 4000, 4000);
        for (Material material : Material.values()) base.inventory.put(material, 3000.0);

        Unit damaged = addUnit(fixture, 70_001, "frigate", 5000, 4000);
        damaged.hp = damaged.type().maxHp * 0.30;
        Unit repairEscort = addUnit(fixture, 70_002, "destroyer", 5070, 4050);
        Unit unrelatedEscort = addUnit(fixture, 70_003, "destroyer", 3900, 3850);
        Unit unrelatedProtected = addUnit(fixture, 70_004, "cruiser", 3950, 3850);
        String unrelatedTarget = CombatTarget.unit(unrelatedProtected);
        require(AUnitOrder.apply(fixture.world, new UnitOrderCommand(
                        unrelatedEscort.playerId,
                        unrelatedEscort.unitId,
                        UnitOrderType.ESCORT,
                        unrelatedProtected.x,
                        unrelatedProtected.y,
                        unrelatedProtected.x,
                        unrelatedProtected.y,
                        UnitOrderSystem.defaultRadius(UnitOrderType.ESCORT),
                        unrelatedTarget,
                        0)),
                "fixture could not create an unrelated escort order");
        fixture.world.saveActiveSystem();

        double startDistance = Calc.distance(damaged.x, damaged.y, base.x, base.y);
        stepCurrent(fixture.world, 1);
        require(Calc.distance(damaged.x, damaged.y, base.x, base.y) < startDistance,
                "damaged ship did not physically move toward its repair station");
        require(repairEscort.orderType == UnitOrderType.ESCORT
                        && CombatTarget.unit(damaged).equals(repairEscort.orderTarget),
                "recovery did not assign a healthy repair escort");
        require(unrelatedEscort.orderType == UnitOrderType.ESCORT
                        && unrelatedTarget.equals(unrelatedEscort.orderTarget),
                "recovery cleared an unrelated escort order");

        double ironBefore = base.inventory.getOrDefault(Material.IRON, 0.0);
        double copperBefore = base.inventory.getOrDefault(Material.COPPER, 0.0);
        int elapsed = 0;
        while (damaged.hp + EPSILON < damaged.type().maxHp && elapsed++ < 80) {
            stepCurrent(fixture.world, 1);
        }
        require(Math.abs(damaged.hp - damaged.type().maxHp) < EPSILON,
                "damaged ship did not reach the station and complete paid repair");
        require(base.inventory.getOrDefault(Material.IRON, 0.0) < ironBefore,
                "physical repair consumed no iron");
        require(base.inventory.getOrDefault(Material.COPPER, 0.0) < copperBefore,
                "physical repair consumed no copper");

        stepCurrent(fixture.world, 2);
        require(repairEscort.orderType != UnitOrderType.ESCORT
                        || !CombatTarget.unit(damaged).equals(repairEscort.orderTarget),
                "recovery-created escort remained attached after full repair");
        require(unrelatedEscort.orderType == UnitOrderType.ESCORT
                        && unrelatedTarget.equals(unrelatedEscort.orderTarget),
                "unrelated escort was cleared when repair completed");
    }

    private static void validateEmergencyRebuildFromCargo() {
        Fixture fixture = fixture("NPC Emergency Cargo Rebuild", "red_dwarf");
        fixture.world.wormholes.clear();
        Unit builder = addUnit(fixture, 71_001, "station_builder", 4000, 4000);
        Unit freighter = addUnit(fixture, 71_002, "freighter", 4060, 4000);
        freighter.inventory.put(Material.IRON, 220.0);
        freighter.inventory.put(Material.COPPER, 120.0);
        freighter.inventory.put(Material.SILICATES, 140.0);
        freighter.inventory.put(Material.ICE, 60.0);
        freighter.inventory.put(Material.FUEL, 30.0);
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 1);
        Base emergency = firstFactionBase(fixture.world, fixture.faction.id());
        require(emergency != null && "outpost".equals(emergency.typeId),
                "funded stationless group did not establish an emergency outpost");
        require(!fixture.world.units.containsKey(builder.key()),
                "emergency rebuild did not consume its deployer");
        require(fixture.world.units.containsKey(freighter.key()),
                "emergency rebuild consumed the surviving freighter");
        require(emergency.inventory.getOrDefault(Material.FUEL, 0.0) >= 29.9,
                "emergency station did not receive surplus expedition cargo");
    }

    private static void validateLoadedPackageRecoveryRules() {
        Fixture viable = fixture("NPC Loaded Shipyard Recovery", "red_dwarf");
        viable.world.wormholes.clear();
        Unit shipyardBuilder = addUnit(viable, 72_001, "station_builder", 4000, 4000);
        shipyardBuilder.basePackageType = "shipyard";
        viable.world.saveActiveSystem();
        stepCurrent(viable.world, 1);
        Base recovered = firstFactionBase(viable.world, viable.faction.id());
        require(recovered != null && "shipyard".equals(recovered.typeId),
                "recovery-capable loaded shipyard was not deployed");

        Fixture deadEnd = fixture("NPC Loaded Laboratory Dead End", "red_dwarf");
        deadEnd.world.wormholes.clear();
        Unit labBuilder = addUnit(deadEnd, 72_101, "station_builder", 4000, 4000);
        labBuilder.basePackageType = "laboratory";
        deadEnd.world.saveActiveSystem();
        stepCurrent(deadEnd.world, 1);
        require(firstFactionBase(deadEnd.world, deadEnd.faction.id()) == null,
                "recovery deployed a laboratory that could not rebuild the faction");
        stepCurrent(deadEnd.world, 50);
        require(!deadEnd.world.units.containsKey(labBuilder.key()),
                "dead-end loaded deployer survived indefinitely without a recovery route");
    }

    private static void validatePhysicalWormholeEvacuation() {
        Fixture fixture = fixture("NPC Physical Evacuation", "red_dwarf");
        require(!fixture.world.wormholes.isEmpty(),
                "evacuation fixture has no wormhole");
        Unit combat = addUnit(fixture, 73_001, "frigate", 4000, 4000);
        Unit freighter = addUnit(fixture, 73_002, "freighter", 4050, 4050);
        freighter.inventory.put(Material.IRON, freighter.type().cargoCapacity);
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 1);
        WormholeGate gate = gateAtTarget(fixture.world, combat.targetX, combat.targetY);
        require(gate != null,
                "recovery did not issue a real wormhole evacuation target");
        require(Math.abs(freighter.targetX - gate.x) < EPSILON
                        && Math.abs(freighter.targetY - gate.y) < EPSILON,
                "full freighter automation overrode the evacuation order");
        String destination = gate.toSystemId;

        int elapsed = 0;
        while (fixture.world.units.containsKey(combat.key()) && elapsed++ < 120) {
            stepCurrent(fixture.world, 1);
        }
        require(!fixture.world.units.containsKey(combat.key())
                        && !fixture.world.units.containsKey(freighter.key()),
                "stationless fleet did not physically reach and transfer through its wormhole");
        fixture.world.activateSystem(destination);
        require(fixture.world.units.containsKey(combat.key())
                        && fixture.world.units.containsKey(freighter.key()),
                "evacuated ships did not persist in the destination system");
    }

    private static void validateImpossibleGroupsAreScuttled() {
        Fixture workerGroup = fixture("NPC Impossible Worker Recovery", "red_dwarf");
        workerGroup.world.wormholes.clear();
        Unit builder = addUnit(workerGroup, 74_001, "station_builder", 4000, 4000);
        Unit worker = addUnit(workerGroup, 74_002, "prospector", 4080, 4000);
        workerGroup.world.saveActiveSystem();
        stepCurrent(workerGroup.world, 1);
        require(NpcRecoverySystem.state(workerGroup.world, workerGroup.faction,
                        workerGroup.world.activeSystemId()) == NpcRecoveryState.STRANDED,
                "builder plus insufficient worker cargo was treated as reachable recovery");
        stepCurrent(workerGroup.world, 50);
        require(!workerGroup.world.units.containsKey(builder.key())
                        && !workerGroup.world.units.containsKey(worker.key()),
                "impossible builder-and-worker group became immortal");

        Fixture freighterGroup = fixture("NPC Impossible Freighter Recovery", "red_dwarf");
        freighterGroup.world.wormholes.clear();
        Unit secondBuilder = addUnit(freighterGroup, 74_101, "station_builder", 4000, 4000);
        Unit emptyFreighter = addUnit(freighterGroup, 74_102, "freighter", 4080, 4000);
        freighterGroup.world.saveActiveSystem();
        stepCurrent(freighterGroup.world, 50);
        require(!freighterGroup.world.units.containsKey(secondBuilder.key())
                        && !freighterGroup.world.units.containsKey(emptyFreighter.key()),
                "empty builder-and-freighter group survived without mining or materials");
    }

    private static void validateReachableMiningRecovery() {
        Fixture fixture = fixture("NPC Reachable Mining Recovery", "red_dwarf");
        fixture.world.wormholes.clear();
        fixture.world.resources.clear();
        addUnit(fixture, 75_001, "station_builder", 4000, 4000);
        for (int i = 0; i < 5; i++) {
            addUnit(fixture, 75_010 + i, "prospector", 3980 + i * 35, 4040);
        }
        addNode(fixture.world, 90_001, Material.IRON, 4200, 4000, 260);
        addNode(fixture.world, 90_002, Material.COPPER, 4200, 4100, 180);
        addNode(fixture.world, 90_003, Material.SILICATES, 4100, 4200, 180);
        addNode(fixture.world, 90_004, Material.ICE, 4000, 4200, 120);
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 1);
        require(NpcRecoverySystem.state(fixture.world, fixture.faction,
                        fixture.world.activeSystemId()) == NpcRecoveryState.STRANDED_RECOVERY,
                "fully reachable mining recovery was not recognized");
        boolean mining = fixture.world.units.values().stream()
                .anyMatch(unit -> fixture.faction.id().equals(unit.playerId)
                        && unit.task == UnitTask.AUTO_HARVEST);
        require(mining, "recovery workers received no real mining assignments");

        int elapsed = 0;
        while (firstFactionBase(fixture.world, fixture.faction.id()) == null
                && elapsed++ < 120) {
            stepCurrent(fixture.world, 1);
        }
        require(firstFactionBase(fixture.world, fixture.faction.id()) != null,
                "reachable workers failed to mine and build an emergency outpost");
    }

    private static void validateBlockedRepairEconomy() {
        Fixture fixture = fixture("NPC Blocked Repair Economy", StarSystems.CORSAIR_SYSTEM_ID);
        fixture.world.resources.clear();
        addNode(fixture.world, 91_001, Material.IRON, 4140, 4000, 200);
        addNode(fixture.world, 91_002, Material.COPPER, 4140, 4100, 200);
        Base base = addBase(fixture, "outpost", 4000, 4000);
        Unit damaged = addUnit(fixture, 76_001, "frigate", 4020, 4000);
        damaged.hp = damaged.type().maxHp * 0.20;
        Unit worker = addUnit(fixture, 76_002, "prospector", 4050, 4050);
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 3);
        require(damaged.hp < damaged.type().maxHp,
                "unfunded repair granted free hull integrity");
        require(worker.task == UnitTask.AUTO_HARVEST,
                "blocked repair did not activate emergency worker mining");
        ResourceNode target = fixture.world.findResource(worker.automationResourceId);
        require(target != null
                        && (target.material == Material.IRON
                        || target.material == Material.COPPER),
                "blocked repair worker mined an unrelated material");
    }

    private static void validateRemoteSurvivorAndControlledRespawn() {
        Fixture fixture = fixture("NPC Remote Survivor Lifecycle", StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(fixture.world);
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        stepCurrent(fixture.world, 2);

        fixture.world.activateSystem("red_dwarf");
        Base remote = addBase(fixture, "shipyard", 4000, 4000);
        fixture.world.saveActiveSystem();
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        fixture.world.units.values().removeIf(unit -> fixture.faction.id().equals(unit.playerId));
        fixture.world.bases.values().removeIf(base -> fixture.faction.id().equals(base.playerId));
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 100);
        require(firstFactionBase(fixture.world, fixture.faction.id()) == null,
                "home respawn occurred while a remote Corsair station survived");
        require(fixture.world.hasLiveAssets(fixture.faction.id()),
                "remote station was not included in galaxy-wide defeat detection");

        fixture.world.activateSystem("red_dwarf");
        require(fixture.world.bases.containsKey(remote.id),
                "remote survivor vanished during home-system simulation");
        AiDevCommands.killCorsairs(fixture.world);
        require(!fixture.world.hasLiveAssets(fixture.faction.id()),
                "total-defeat setup left a Corsair asset alive");

        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        stepCurrent(fixture.world, 91);
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "true galaxy-wide defeat did not produce exactly one controlled respawn");
    }

    private static void validateFreshSpawnResetsTacticalTimers() {
        Fixture fixture = fixture("NPC Fresh Spawn Timer Reset", StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(fixture.world);
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        stepCurrent(fixture.world, 14);
        AiDevCommands.killCorsairs(fixture.world);
        require(AiDevCommands.spawnCorsairs(fixture.world),
                "forced replacement spawn failed after total defeat");
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        Base home = firstFactionBase(fixture.world, fixture.faction.id());
        require(home != null, "replacement spawn has no home station");
        for (Material material : Material.values()) home.inventory.put(material, 5000.0);
        HangarStore.add(home.inventory, Material.FUEL, fixture.faction.fuelReserve() + 100.0);
        addUnit(fixture, 77_001, "station_builder", home.x + 150, home.y);
        addUnit(fixture, 77_002, "prospector", home.x + 190, home.y + 40);
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 4);
        require(!NpcStationConstructionSystem.hasActivePlan(fixture.world, fixture.faction),
                "fresh spawn inherited the previous life's near-expired station timer");
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "fresh spawn constructed another station before its full cooldown");
    }

    private static Fixture fixture(String name, String systemId) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        world.activateSystem(systemId);
        world.units.clear();
        world.bases.clear();
        NpcRecoverySystem.clear(world);
        NpcExpeditionSystem.clear(world);
        NpcStationConstructionSystem.clear(world);
        NpcSquadCombatSystem.clear(world);
        world.saveActiveSystem();
        return new Fixture(world, corsairs());
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Base addBase(Fixture fixture, String type, double x, double y) {
        String id = fixture.faction.id() + ":B" + (80_000 + fixture.world.bases.size());
        Base base = new Base(id, fixture.faction.id(), type, x, y);
        fixture.world.bases.put(id, base);
        return base;
    }

    private static Unit addUnit(Fixture fixture, int id, String type, double x, double y) {
        Unit unit = new Unit(fixture.faction.id(), id, type, x, y);
        fixture.world.units.put(unit.key(), unit);
        return unit;
    }

    private static void addNode(World world, int id, Material material,
                                double x, double y, double amount) {
        world.resources.add(new ResourceNode(id,
                "Recovery " + material.label,
                NodeKind.SILICATE_ROCK,
                material,
                x,
                y,
                amount,
                55.0,
                20.0));
    }

    private static Base firstFactionBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        return null;
    }

    private static int factionBaseCount(World world, String factionId) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) count++;
        }
        return count;
    }

    private static WormholeGate gateAtTarget(World world, double x, double y) {
        for (WormholeGate gate : world.wormholes) {
            if (Math.abs(gate.x - x) < EPSILON
                    && Math.abs(gate.y - y) < EPSILON) return gate;
        }
        return null;
    }

    private static void stepCurrent(World world, int seconds) {
        for (int i = 0; i < seconds; i++) world.updateCurrentSystem(1.0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction) { }
}
