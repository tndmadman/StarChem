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
        validatePaidStationRepair();
        validateEmergencyRebuild();
        validateWormholeEvacuation();
        validateStrandedGraceAndScuttle();
        validateViableStrandedRecovery();
        validateRemoteSurvivorBlocksRespawn();
    }

    private static void validatePaidStationRepair() {
        Fixture fixture = fixture("NPC Paid Repair", StarSystems.CORSAIR_SYSTEM_ID);
        Base base = addBase(fixture.world, fixture.faction, "outpost",
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        for (Material material : Material.values()) base.inventory.put(material, 2000.0);
        Unit ship = addUnit(fixture.world, fixture.faction, 70_001, "frigate",
                base.x + 500, base.y);
        ship.hp = ship.type().maxHp * 0.30;
        double damagedHp = ship.hp;

        runRecovery(fixture.world);
        require(ship.task == UnitTask.MOVE,
                "damaged Corsair ship did not retreat toward a repair station");
        require(Math.abs(ship.targetX - base.x) < EPSILON && Math.abs(ship.targetY - base.y) < EPSILON,
                "damaged ship retreat target was not the nearest friendly station");
        require(NpcRecoverySystem.state(fixture.world, fixture.faction, fixture.world.activeSystemId())
                        == NpcRecoveryState.REPAIRING,
                "repairing group did not enter the REPAIRING recovery state");

        ship.x = base.x + 20;
        ship.y = base.y;
        ship.targetX = ship.x;
        ship.targetY = ship.y;
        double ironBefore = base.inventory.getOrDefault(Material.IRON, 0.0);
        double copperBefore = base.inventory.getOrDefault(Material.COPPER, 0.0);
        advanceRecovery(fixture.world, 1.0);
        require(ship.hp > damagedHp,
                "ship inside station service range received no hull repair");
        require(base.inventory.getOrDefault(Material.IRON, 0.0) < ironBefore,
                "hull repair did not consume iron");
        require(base.inventory.getOrDefault(Material.COPPER, 0.0) < copperBefore,
                "hull repair did not consume copper");

        for (int i = 0; i < 20 && ship.hp + EPSILON < ship.type().maxHp; i++) {
            advanceRecovery(fixture.world, 1.0);
        }
        require(Math.abs(ship.hp - ship.type().maxHp) < EPSILON,
                "paid repair did not restore the ship to full hull integrity");
    }

    private static void validateEmergencyRebuild() {
        Fixture fixture = fixture("NPC Emergency Rebuild", "red_dwarf");
        fixture.world.wormholes.clear();
        Unit builder = addUnit(fixture.world, fixture.faction, 71_001, "station_builder",
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        Unit depot = addUnit(fixture.world, fixture.faction, 71_002, "freighter",
                builder.x + 60, builder.y);
        depot.inventory.put(Material.IRON, 220.0);
        depot.inventory.put(Material.COPPER, 120.0);
        depot.inventory.put(Material.SILICATES, 140.0);
        depot.inventory.put(Material.ICE, 60.0);
        depot.inventory.put(Material.FUEL, 30.0);

        runRecovery(fixture.world);
        Base emergency = firstFactionBase(fixture.world, fixture.faction.id());
        require(emergency != null,
                "stationless Corsair group did not establish an emergency outpost");
        require(!fixture.world.units.containsKey(builder.key()),
                "emergency rebuild did not consume its deployer");
        require(fixture.world.units.containsKey(depot.key()),
                "emergency rebuild incorrectly consumed the mobile depot");
        require(emergency.inventory.getOrDefault(Material.FUEL, 0.0) >= 29.9,
                "emergency outpost did not receive surviving expedition supplies");
        require(NpcRecoverySystem.state(fixture.world, fixture.faction, fixture.world.activeSystemId())
                        == NpcRecoveryState.REBUILDING,
                "emergency foothold did not report the REBUILDING state");
    }

    private static void validateWormholeEvacuation() {
        Fixture fixture = fixture("NPC Wormhole Evacuation", "red_dwarf");
        require(!fixture.world.wormholes.isEmpty(),
                "evacuation fixture system has no generated wormhole");
        Unit first = addUnit(fixture.world, fixture.faction, 72_001, "frigate",
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        Unit second = addUnit(fixture.world, fixture.faction, 72_002, "prospector",
                first.x + 80, first.y);

        runRecovery(fixture.world);
        require(fixture.world.units.containsKey(first.key()) && fixture.world.units.containsKey(second.key()),
                "stationless expedition ships were deleted instead of evacuated");
        require(first.task == UnitTask.MOVE && second.task == UnitTask.MOVE,
                "stationless expedition did not receive evacuation movement orders");
        WormholeGate gate = gateAtTarget(fixture.world, first.targetX, first.targetY);
        require(gate != null,
                "evacuation order did not target a real wormhole");
        require(Math.abs(second.targetX - gate.x) < EPSILON && Math.abs(second.targetY - gate.y) < EPSILON,
                "evacuation ships were split across different routes");
        require(NpcRecoverySystem.state(fixture.world, fixture.faction, fixture.world.activeSystemId())
                        == NpcRecoveryState.EVACUATING,
                "stationless expedition did not enter EVACUATING state");

        first.x = gate.x;
        first.y = gate.y;
        second.x = gate.x;
        second.y = gate.y;
        String sourceSystem = fixture.world.activeSystemId();
        require(fixture.world.transferTouchingShips(fixture.faction.id()),
                "ships touching the evacuation wormhole did not transfer");
        require(fixture.world.units.values().stream().noneMatch(unit -> fixture.faction.id().equals(unit.playerId)),
                "evacuated ships remained in the source system");
        fixture.world.activateSystem(gate.toSystemId);
        require(fixture.world.units.containsKey(first.key()) && fixture.world.units.containsKey(second.key()),
                "evacuated ships did not persist in the destination system");
        fixture.world.activateSystem(sourceSystem);
    }

    private static void validateStrandedGraceAndScuttle() {
        Fixture fixture = fixture("NPC Stranded Scuttle", "red_dwarf");
        fixture.world.wormholes.clear();
        Unit stranded = addUnit(fixture.world, fixture.faction, 73_001, "frigate",
                fixture.world.width * 0.5, fixture.world.height * 0.5);

        runRecovery(fixture.world);
        require(fixture.world.units.containsKey(stranded.key()),
                "stranded ship was deleted without a recovery grace period");
        require(NpcRecoverySystem.state(fixture.world, fixture.faction, fixture.world.activeSystemId())
                        == NpcRecoveryState.STRANDED,
                "unrecoverable group did not enter STRANDED state");

        for (int i = 0; i < 8; i++) advanceRecovery(fixture.world, 5.0);
        require(fixture.world.units.containsKey(stranded.key()),
                "stranded ship was scuttled before the 45-second grace period");
        advanceRecovery(fixture.world, 5.0);
        require(!fixture.world.units.containsKey(stranded.key()),
                "truly unrecoverable ship was not scuttled after the grace period");
        require(NpcRecoverySystem.state(fixture.world, fixture.faction, fixture.world.activeSystemId())
                        == NpcRecoveryState.SCUTTLED,
                "unrecoverable group did not report the SCUTTLED state");
    }

    private static void validateViableStrandedRecovery() {
        Fixture fixture = fixture("NPC Viable Stranded Recovery", "red_dwarf");
        fixture.world.wormholes.clear();
        Unit builder = addUnit(fixture.world, fixture.faction, 74_001, "station_builder",
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        Unit worker = addUnit(fixture.world, fixture.faction, 74_002, "prospector",
                builder.x + 80, builder.y);

        runRecovery(fixture.world);
        for (int i = 0; i < 14; i++) advanceRecovery(fixture.world, 5.0);
        require(fixture.world.units.containsKey(builder.key()) && fixture.world.units.containsKey(worker.key()),
                "viable builder-and-worker recovery group was scuttled");
        require(NpcRecoverySystem.state(fixture.world, fixture.faction, fixture.world.activeSystemId())
                        == NpcRecoveryState.STRANDED_RECOVERY,
                "viable stranded group did not remain in recovery state");
    }

    private static void validateRemoteSurvivorBlocksRespawn() {
        Fixture fixture = fixture("NPC Remote Survivor Lifecycle", StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(fixture.world);
        fixture.world.activateSystem("red_dwarf");
        fixture.world.wormholes.clear();
        addUnit(fixture.world, fixture.faction, 75_001, "station_builder",
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        addUnit(fixture.world, fixture.faction, 75_002, "prospector",
                fixture.world.width * 0.5 + 80, fixture.world.height * 0.5);
        fixture.world.saveActiveSystem();

        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        fixture.world.units.values().removeIf(unit -> fixture.faction.id().equals(unit.playerId));
        fixture.world.bases.values().removeIf(base -> fixture.faction.id().equals(base.playerId));
        fixture.world.saveActiveSystem();

        for (int i = 0; i < 100; i++) fixture.world.update(1.0);
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        require(firstFactionBase(fixture.world, fixture.faction.id()) == null,
                "Corsairs respawned while remote recovery assets were still alive");
        require(fixture.world.hasLiveAssets(fixture.faction.id()),
                "remote survivor group did not remain part of galaxy-wide defeat detection");

        AiDevCommands.killCorsairs(fixture.world);
        require(!fixture.world.hasLiveAssets(fixture.faction.id()),
                "total-defeat setup left a Corsair asset alive");
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        for (int i = 0; i < 91; i++) fixture.world.update(1.0);
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        require(firstFactionBase(fixture.world, fixture.faction.id()) != null,
                "Corsairs did not begin one controlled respawn after true galaxy-wide defeat");
    }

    private static Fixture fixture(String name, String systemId) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        world.activateSystem(systemId);
        return new Fixture(world, corsairs());
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Base addBase(World world, NpcFaction faction, String type, double x, double y) {
        String id = faction.id() + ":B" + (80_000 + world.bases.size());
        Base base = new Base(id, faction.id(), type, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static Unit addUnit(World world, NpcFaction faction, int id, String type, double x, double y) {
        Unit unit = new Unit(faction.id(), id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static Base firstFactionBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        return null;
    }

    private static WormholeGate gateAtTarget(World world, double x, double y) {
        for (WormholeGate gate : world.wormholes) {
            if (Math.abs(gate.x - x) < EPSILON && Math.abs(gate.y - y) < EPSILON) return gate;
        }
        return null;
    }

    private static void runRecovery(World world) {
        NpcCollapseSystem.removeShipsWithoutStations(world);
    }

    private static void advanceRecovery(World world, double seconds) {
        world.systemTime += seconds;
        runRecovery(world);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction) { }
}
