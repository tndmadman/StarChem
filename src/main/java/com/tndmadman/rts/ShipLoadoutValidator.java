package com.tndmadman.rts;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShipLoadoutValidator {
    private ShipLoadoutValidator() { }

    public static void main(String[] args) {
        validateDefinitions();
        validateConstructionAndCombatResolution();
        validateLogisticsPreservesSelection();
        validateRefittingAndCancellation();
        validateDestroyedTargetRecovery();
        validateSnapshotAndSaveRoundTrip();
        validateActiveRefitPersistence();
        validateNpcLoadoutAwareness();
        validateDeveloperSpawnResolution();
        validateBudgetFingerprint();
        validateStrictRejection();
        System.out.println("StarChem ship loadout and refit validation passed.");
    }

    private static void validateDefinitions() {
        require(WeaponRules.loadoutsForHull("destroyer").size() >= 3,
                "destroyer variants were not loaded");
        require(WeaponRules.loadoutsForHull("cruiser").size() >= 3,
                "cruiser variants were not loaded");
        require(WeaponRules.loadoutsForHull("dreadnought").size() >= 3,
                "dreadnought variants were not loaded");
        require("destroyer".equals(WeaponRules.defaultLoadoutId("destroyer")),
                "legacy destroyer default ID changed");
    }

    private static void validateConstructionAndCombatResolution() {
        Fixture fixture = fixture("LOADOUT_BUILD");
        ShipLoadoutDefinition rail = requiredLoadout("destroyer_rail_escort");
        require(fixture.world.buildShip(fixture.yard.id, rail.id()),
                "selected destroyer loadout did not enqueue");
        require(fixture.yard.productionQueue.size() == 1, "selected loadout queue entry missing");
        ProductionJob queued = fixture.yard.productionQueue.get(0);
        require(rail.id().equals(queued.loadoutId), "selected loadout was not stored on the build job");
        ProductionSystem.update(fixture.world, 1000);
        Unit ship = findShip(fixture.world, fixture.playerId, "destroyer");
        require(ship != null, "selected destroyer was not produced");
        require(rail.id().equals(ship.loadoutId), "produced ship lost its selected loadout");
        require(Math.abs(WeaponRules.maxRange(ship) - 620) < 0.001,
                "combat range still resolved from the hull default");
        require(WeaponRules.loadout(ship).size() == 3,
                "combat weapons did not resolve from the unit loadout");
    }

    private static void validateLogisticsPreservesSelection() {
        Fixture fixture = fixture("LOADOUT_LOGISTICS");
        Base source = new Base(fixture.playerId + ":B2", fixture.playerId, "shipyard", 1400, 1000);
        for (Material material : Material.values()) source.inventory.put(material, 100_000.0);
        fixture.world.bases.put(source.id, source);
        fixture.yard.inventory.clear();

        ShipLoadoutDefinition rail = requiredLoadout("destroyer_rail_escort");
        require(fixture.world.buildShip(fixture.yard.id, rail.id()),
                "logistics-backed selected loadout was rejected");
        require(fixture.yard.productionQueue.size() == 1,
                "logistics-backed selected loadout did not create a queue job");
        require(rail.id().equals(fixture.yard.productionQueue.get(0).loadoutId),
                "logistics-backed queue lost the selected loadout");
    }

    private static void validateRefittingAndCancellation() {
        Fixture fixture = fixture("LOADOUT_REFIT");
        Unit ship = spawn(fixture, "destroyer", "destroyer");
        ShipLoadoutDefinition missile = requiredLoadout("destroyer_missile_screen");
        require(ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, missile, false),
                "valid refit request was rejected");
        require(ProductionSystem.refitLocked(fixture.world, ship.key()), "refit did not lock the subject ship");
        double dockedX = ship.x;
        double dockedY = ship.y;
        double oldX = ship.targetX;
        ship.issueMove(ship.x + 500, ship.y + 500);
        require(Math.abs(ship.targetX - oldX) < 0.001, "locked refit ship accepted a move command");
        fixture.world.update(0.25);
        require(close(ship.x, dockedX) && close(ship.y, dockedY),
                "normal simulation movement displaced a locked refit ship");
        ProductionSystem.update(fixture.world, 1000);
        require(missile.id().equals(ship.loadoutId), "completed refit did not install the target loadout");
        require(!ProductionSystem.refitLocked(fixture.world, ship.key()), "completed refit left the ship locked");
        require(Math.abs(WeaponRules.maxRange(ship) - 820) < 0.001,
                "refitted combat range did not update");

        ShipLoadoutDefinition rail = requiredLoadout("destroyer_rail_escort");
        ship.weaponCooldown = 0;
        double before = fixture.yard.inventory.getOrDefault(Material.RAILGUN_ASSEMBLY, 0.0);
        require(ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "second refit did not enqueue");
        ProductionJob job = fixture.yard.productionQueue.get(0);
        require(fixture.yard.inventory.getOrDefault(Material.RAILGUN_ASSEMBLY, 0.0) < before,
                "refit did not reserve configured resources");
        require(ProductionSystem.cancel(fixture.world, fixture.playerId, fixture.yard.id, job.id),
                "refit cancellation failed");
        require(close(fixture.yard.inventory.getOrDefault(Material.RAILGUN_ASSEMBLY, 0.0), before),
                "refit cancellation did not refund resources");
        require(!ProductionSystem.refitLocked(fixture.world, ship.key()), "cancelled refit left the ship locked");

        ship.task = UnitTask.ATTACK;
        require(!ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "combat-active ship refit was accepted");
        ship.task = UnitTask.IDLE;
        ship.x = fixture.yard.x + fixture.yard.type().refitRange + 5;
        ship.targetX = ship.x;
        require(!ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "remote refit request was accepted");
    }

    private static void validateDestroyedTargetRecovery() {
        Fixture fixture = fixture("LOADOUT_DESTROYED");
        Unit ship = spawn(fixture, "destroyer", "destroyer");
        ShipLoadoutDefinition rail = requiredLoadout("destroyer_rail_escort");
        ship.weaponCooldown = 0;
        double before = fixture.yard.inventory.getOrDefault(Material.RAILGUN_ASSEMBLY, 0.0);
        require(ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "destroyed-target refit did not enqueue");
        ship.hp = 0;
        ProductionSystem.update(fixture.world, 0.1);
        require(fixture.yard.productionQueue.isEmpty(), "destroyed target left a refit job queued");
        require(close(fixture.yard.inventory.getOrDefault(Material.RAILGUN_ASSEMBLY, 0.0), before),
                "destroyed target did not refund reserved refit resources");
    }

    private static void validateSnapshotAndSaveRoundTrip() {
        Fixture fixture = fixture("LOADOUT_PERSIST");
        Unit ship = spawn(fixture, "cruiser", "cruiser_artillery");

        Snapshot wire = SnapshotReader.read(SnapshotWriter.write(WorldNetAccess.snapshot(fixture.world, 42)));
        UnitState state = wire.units().stream().filter(row -> row.unitId() == ship.unitId).findFirst().orElseThrow();
        require("cruiser_artillery".equals(state.loadoutId()), "snapshot round-trip lost loadout ID");
        World replica = new World("Loadout Replica", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        WorldNetAccess.applyFullView(replica, wire);
        Unit replicated = replica.units.get(ship.key());
        require(replicated != null && "cruiser_artillery".equals(replicated.loadoutId),
                "snapshot application lost loadout ID");

        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        World restored = new World("Loadout Restored", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        restored.restoreServerSaveGalaxy(galaxy);
        Unit saved = restored.units.get(ship.key());
        require(saved != null && "cruiser_artillery".equals(saved.loadoutId),
                "server save restore lost loadout ID");

        Map<String,Object> legacyGalaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> firstSystem = ServerSaveStore.object(ServerSaveStore.list(legacyGalaxy.get("systems")).get(0));
        for (Object item : ServerSaveStore.list(firstSystem.get("units"))) {
            ServerSaveStore.object(item).remove("loadoutId");
        }
        World legacy = new World("Loadout Legacy", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        legacy.restoreServerSaveGalaxy(legacyGalaxy);
        Unit migrated = legacy.units.get(ship.key());
        require(migrated != null && WeaponRules.defaultLoadoutId(migrated.shipTypeId).equals(migrated.loadoutId),
                "legacy save did not receive the hull default loadout");
    }

    private static void validateActiveRefitPersistence() {
        Fixture fixture = fixture("LOADOUT_ACTIVE_REFIT");
        Unit ship = spawn(fixture, "destroyer", "destroyer");
        ShipLoadoutDefinition rail = requiredLoadout("destroyer_rail_escort");
        require(ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, rail, false),
                "active refit persistence fixture did not enqueue");

        Base networkBase = NetBaseSync.fromState(NetBaseSync.toState(fixture.yard));
        require(networkBase.productionQueue.size() == 1,
                "network base state lost the active refit job");
        ProductionJob networkJob = networkBase.productionQueue.get(0);
        require(networkJob.kind == ProductionJobKind.REFIT
                        && rail.id().equals(networkJob.loadoutId)
                        && ship.key().equals(networkJob.subjectUnitKey),
                "network base state changed active refit identity");

        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        World restored = new World("Active Refit Restored", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        Base restoredYard = restored.bases.get(fixture.yard.id);
        Unit restoredShip = restored.units.get(ship.key());
        require(restoredYard != null && restoredShip != null,
                "active refit save restore lost the station or ship");
        require(restoredYard.productionQueue.size() == 1
                        && restoredYard.productionQueue.get(0).kind == ProductionJobKind.REFIT,
                "active refit save restore lost the service job");
        require(ProductionSystem.refitLocked(restored, restoredShip.key()),
                "active refit save restore did not lock the subject ship");
        ProductionSystem.update(restored, 1000);
        require(rail.id().equals(restoredShip.loadoutId),
                "restored active refit did not complete with the selected loadout");
    }

    private static void validateNpcLoadoutAwareness() {
        Unit balanced = new Unit("LOADOUT_NPC", 1, "destroyer", 500, 500);
        Unit rail = new Unit("LOADOUT_NPC", 2, "destroyer", 500, 500);
        rail.loadoutId = "destroyer_rail_escort";

        double balancedThreat = NpcSquadCombatSystem.targetThreatScoreForTesting(balanced);
        double railThreat = NpcSquadCombatSystem.targetThreatScoreForTesting(rail);
        require(railThreat > balancedThreat + 0.001,
                "NPC threat scoring ignored the selected unit loadout");
    }

    private static void validateDeveloperSpawnResolution() {
        ShipLoadoutDefinition defaultFit = ServerDevCommands.resolveSpawnLoadout("destroyer");
        ShipLoadoutDefinition selectedFit = ServerDevCommands.resolveSpawnLoadout("destroyer_rail_escort");
        require(defaultFit != null && "destroyer".equals(defaultFit.id()),
                "developer hull spawn did not resolve the default loadout");
        require(selectedFit != null && "destroyer_rail_escort".equals(selectedFit.id()),
                "developer loadout spawn did not preserve the selected variant");
        require(ServerDevCommands.resolveSpawnLoadout("missing_loadout") == null,
                "developer spawning accepted an unknown loadout ID");
    }

    private static void validateBudgetFingerprint() {
        NpcFaction faction = null;
        for (NpcFaction candidate : NpcRules.factions()) {
            if (candidate.behavior() == NpcBehavior.FACTION) {
                faction = candidate;
                break;
            }
        }
        require(faction != null, "no organized NPC faction is configured");

        World world = new World("Loadout Budget Fingerprint", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Unit unit = new Unit(faction.id(), 1, "destroyer", 500, 500);
        world.units.put(unit.key(), unit);
        Base yard = new Base(faction.id() + ":B1", faction.id(), "shipyard", 450, 450);
        ProductionJob job = new ProductionJob("P1", ProductionJobKind.SHIP, "destroyer", 10, 10, true, "");
        job.loadoutId = "destroyer";
        yard.productionQueue.add(job);
        world.bases.put(yard.id, yard);

        long baseline = budgetFingerprint(world, faction);
        unit.loadoutId = "destroyer_rail_escort";
        long changedUnit = budgetFingerprint(world, faction);
        require(baseline != changedUnit,
                "NPC resource-budget fingerprint ignored a live unit loadout change");

        unit.loadoutId = "destroyer";
        job.loadoutId = "destroyer_rail_escort";
        long changedJob = budgetFingerprint(world, faction);
        require(baseline != changedJob,
                "NPC resource-budget fingerprint ignored a queued loadout change");
    }

    private static long budgetFingerprint(World world, NpcFaction faction) {
        try {
            Method method = NpcResourceBudget.class.getDeclaredMethod(
                    "localFingerprint", World.class, NpcFaction.class);
            method.setAccessible(true);
            return (long) method.invoke(null, world, faction);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("could not inspect NPC resource-budget fingerprint", ex);
        }
    }

    private static void validateStrictRejection() {
        Fixture fixture = fixture("LOADOUT_REJECT");
        Unit ship = spawn(fixture, "destroyer", "destroyer");
        ShipLoadoutDefinition cruiser = requiredLoadout("cruiser_artillery");
        require(!ProductionSystem.enqueueRefit(fixture.world, fixture.yard, ship, cruiser, false),
                "mismatched hull loadout was accepted");
        int queueBefore = fixture.yard.productionQueue.size();
        require(!ProductionCommands.apply(fixture.world, fixture.playerId, "REFIT", fixture.yard.id,
                        ship.key(), "missing_loadout"),
                "authoritative command accepted an unknown loadout ID");
        require(fixture.yard.productionQueue.size() == queueBefore,
                "rejected loadout command mutated the production queue");
        require(!fixture.world.buildShip(fixture.yard.id, "missing_loadout"),
                "authoritative build accepted an unknown loadout ID");

        UnitState invalid = new UnitState(ship.playerId, ship.unitId, ship.shipTypeId, ship.x, ship.y,
                ship.targetX, ship.targetY, ship.heading, ship.task.name(), ship.automationResourceId,
                ship.basePackageType, CargoCodec.write(ship.inventory), ship.hp, ship.shield,
                ship.attackTarget, ship.weaponFlashTimer, ship.orderType.name(), 0, 0, 0, 0, 0, "", 0,
                "missing_loadout");
        Snapshot snapshot = new Snapshot(1, List.of(), List.of(invalid), List.of(), List.of(), List.of(),
                List.of(), List.of(), fixture.world.activeSystemId(), fixture.world.systemTime());
        expectReject(() -> SnapshotValidator.validate(snapshot), "loadout");

        String badQueue = "P1^SHIP^destroyer^1^1^1^-^-^cruiser_artillery^-";
        expectReject(() -> StrictProductionQueueCodec.decode(badQueue, "validator", fixture.yard.id),
                "loadout");
        String badSubject = "P1^REFIT^destroyer^1^1^1^-^-^destroyer_rail_escort^not-a-unit";
        expectReject(() -> StrictProductionQueueCodec.decode(badSubject, "validator", fixture.yard.id),
                "subject");
    }

    private static Fixture fixture(String playerId) {
        PlayerRegistry.reset("SOLO", "Loadout Validator", 0x50BEFF);
        World world = new World("Loadout Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.completeResearch(playerId, "advanced_industry");
        world.completeResearch(playerId, "combat_doctrine");
        world.completeResearch(playerId, "battlefleet_engineering");
        Base yard = new Base(playerId + ":B1", playerId, "shipyard", 1000, 1000);
        for (Material material : Material.values()) yard.inventory.put(material, 100_000.0);
        world.bases.put(yard.id, yard);
        return new Fixture(world, yard, playerId);
    }

    private static Unit spawn(Fixture fixture, String hullId, String loadoutId) {
        int id = fixture.world.units.size() + 1;
        Unit unit = new Unit(fixture.playerId, id, hullId, fixture.yard.x + 20, fixture.yard.y + 20);
        unit.loadoutId = loadoutId;
        fixture.world.units.put(unit.key(), unit);
        return unit;
    }

    private static Unit findShip(World world, String playerId, String hullId) {
        for (Unit unit : world.units.values()) {
            if (playerId.equals(unit.playerId) && hullId.equals(unit.shipTypeId)) return unit;
        }
        return null;
    }

    private static ShipLoadoutDefinition requiredLoadout(String id) {
        ShipLoadoutDefinition loadout = WeaponRules.findLoadout(id);
        if (loadout == null) throw new IllegalStateException("missing test loadout " + id);
        return loadout;
    }

    private static void expectReject(Runnable action, String text) {
        try {
            action.run();
            throw new IllegalStateException("expected rejection containing " + text);
        } catch (SnapshotDecodeException ex) {
            require(ex.getMessage() != null && ex.getMessage().toLowerCase().contains(text.toLowerCase()),
                    "rejection did not mention " + text + ": " + ex.getMessage());
        }
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 0.001; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Ship loadout validation failed: " + message);
    }

    private record Fixture(World world, Base yard, String playerId) { }
}
