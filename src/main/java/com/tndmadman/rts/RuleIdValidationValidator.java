package com.tndmadman.rts;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class RuleIdValidationValidator {
    private static final String UNKNOWN_SHIP = "missing_ship_type";
    private static final String UNKNOWN_STATION = "missing_station_type";

    private RuleIdValidationValidator() { }

    public static void main(String[] args) {
        validateStrictLookups();
        validateAuthoritativeCommands();
        validateSnapshotDecoding();
        validateAtomicSnapshotApplication();
        validateProductionQueueDecoding();
        validateSaveContentMigration();
        System.out.println("StarChem rule ID validation passed.");
    }

    private static void validateStrictLookups() {
        require(Rules.findShip(UNKNOWN_SHIP) == null, "unknown ship lookup did not return null");
        require(Rules.findBase(UNKNOWN_STATION) == null, "unknown station lookup did not return null");
        expectUnknownRule(() -> Rules.ship(UNKNOWN_SHIP), UNKNOWN_SHIP);
        expectUnknownRule(() -> Rules.base(UNKNOWN_STATION), UNKNOWN_STATION);
    }

    private static void validateAuthoritativeCommands() {
        PlayerRegistry.reset("SOLO", "Rule ID Validator", 0x50BEFF);
        World world = new World("Rule ID Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Base base = new Base("SOLO:B1", "SOLO", Rules.DEFAULT_BASE, 100, 100);
        world.bases.put(base.id, base);

        int queueSize = base.productionQueue.size();
        require(!world.buildShip(base.id, UNKNOWN_SHIP), "unknown ship build command was accepted");
        require(base.productionQueue.size() == queueSize, "unknown ship build mutated the production queue");
        require(world.status.contains(UNKNOWN_SHIP), "unknown ship build status omitted the invalid ID");

        require(!world.loadBasePackage(base.id, UNKNOWN_STATION), "unknown station package command was accepted");
        require(base.productionQueue.size() == queueSize, "unknown station package mutated the production queue");
        require(world.status.contains(UNKNOWN_STATION), "unknown station package status omitted the invalid ID");

        Unit carrier = new Unit("SOLO", 1, Rules.STARTING_SHIP, 140, 100);
        carrier.basePackageType = UNKNOWN_STATION;
        world.units.put(carrier.key(), carrier);
        int baseCount = world.bases.size();
        require(!world.placePackage(carrier), "unknown station package was placed");
        require(world.units.containsKey(carrier.key()), "invalid package placement consumed the carrier");
        require(world.bases.size() == baseCount, "invalid package placement created a station");
        require(world.status.contains(UNKNOWN_STATION), "invalid package placement status omitted the invalid ID");
    }

    private static void validateSnapshotDecoding() {
        expectSnapshotReject(
                () -> SnapshotReader.read("SNAP|1||SOLO,1," + UNKNOWN_SHIP + ",0,0,0,0,0,IDLE,-1,-,-"),
                UNKNOWN_SHIP);
        expectSnapshotReject(
                () -> BaseStateParser.parse("SOLO:B1,SOLO," + UNKNOWN_STATION + ",0,0"),
                UNKNOWN_STATION);
    }

    private static void validateAtomicSnapshotApplication() {
        PlayerRegistry.reset("SOLO", "Rule ID Validator", 0x50BEFF);
        World world = new World("Rule ID Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Unit existing = new Unit("SOLO", 1, Rules.STARTING_SHIP, 10, 10);
        world.units.put(existing.key(), existing);
        int unitCount = world.units.size();
        int baseCount = world.bases.size();

        UnitState badPackage = new UnitState(
                "SOLO", 1, Rules.STARTING_SHIP, 10, 10, 10, 10, 0,
                UnitTask.IDLE.name(), -1, UNKNOWN_STATION, "", existing.hp, existing.shield,
                "", 0, UnitOrderType.NONE.name(), 0, 0, 0, 0, 0, "", 0);
        Snapshot snapshot = new Snapshot(
                1, List.of(), List.of(badPackage), List.of(), List.of(), List.of(), List.of(), List.of(), "", -1);

        expectSnapshotReject(() -> WorldNetAccess.apply(world, snapshot), UNKNOWN_STATION);
        require(world.units.size() == unitCount, "rejected snapshot changed unit state");
        require(world.bases.size() == baseCount, "rejected snapshot changed base state");
        require(world.units.get(existing.key()) == existing, "rejected snapshot replaced an existing unit");
    }

    private static void validateProductionQueueDecoding() {
        String encoded = "P1^SHIP^" + UNKNOWN_SHIP + "^1^1^0^-^-";
        expectSnapshotReject(() -> StrictProductionQueueCodec.decode(encoded, "validator", "SOLO:B1"), UNKNOWN_SHIP);
    }

    private static void validateSaveContentMigration() {
        PlayerRegistry.reset("SOLO", "Rule ID Validator", 0x50BEFF);
        World source = new World("Rule ID Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Map<String,Object> galaxy = source.captureServerSaveGalaxy();
        Map<String,Object> firstSystem = ServerSaveStore.object(ServerSaveStore.list(galaxy.get("systems")).get(0));
        firstSystem.put("templateId", "removed_system_template");

        Map<String,Object> unit = new LinkedHashMap<>();
        unit.put("playerId", "SOLO");
        unit.put("unitId", 99);
        unit.put("shipTypeId", UNKNOWN_SHIP);
        unit.put("basePackageType", UNKNOWN_STATION);
        unit.put("x", 10);
        unit.put("y", 20);
        firstSystem.put("units", List.of(unit));

        Map<String,Object> invalidJob = new LinkedHashMap<>();
        invalidJob.put("id", "bad-job");
        invalidJob.put("kind", ProductionJobKind.SHIP.name());
        invalidJob.put("itemId", UNKNOWN_SHIP);
        invalidJob.put("duration", 1);
        invalidJob.put("remaining", 1);
        Map<String,Object> base = new LinkedHashMap<>();
        base.put("id", "SOLO:B99");
        base.put("playerId", "SOLO");
        base.put("typeId", UNKNOWN_STATION);
        base.put("x", 30);
        base.put("y", 40);
        base.put("productionQueue", List.of(invalidJob));
        firstSystem.put("bases", List.of(base));

        Map<String,Object> shot = new LinkedHashMap<>();
        shot.put("id", 12);
        shot.put("ownerId", "SOLO");
        shot.put("weaponId", "removed_weapon");
        shot.put("targetKey", "unit:SOLO:99");
        firstSystem.put("projectiles", List.of(shot));

        Map<String,Object> item = new LinkedHashMap<>();
        item.put("id", 7);
        item.put("material", "REMOVED_MATERIAL");
        item.put("amount", 10);
        firstSystem.put("worldItems", List.of(item));

        World restored = new World("Rule ID Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        restored.restoreServerSaveGalaxy(galaxy);
        require(restored.units.values().stream().anyMatch(u -> Rules.STARTING_SHIP.equals(u.shipTypeId)),
                "save restore did not replace removed ship IDs");
        require(restored.units.values().stream().allMatch(u -> u.basePackageType.isBlank()),
                "save restore did not clear removed station packages");
        require(restored.bases.values().stream().anyMatch(b -> Rules.DEFAULT_BASE.equals(b.typeId)),
                "save restore did not replace removed station IDs");
        require(restored.bases.values().stream().allMatch(b -> b.productionQueue.isEmpty()),
                "save restore did not drop invalid production jobs");
        require(restored.shots.isEmpty(), "save restore did not drop invalid projectile weapons");
        require(restored.items.isEmpty(), "save restore did not drop invalid world item materials");
    }

    private static void expectUnknownRule(Runnable action, String id) {
        try {
            action.run();
            throw new IllegalStateException("expected unknown rule ID rejection for " + id);
        } catch (UnknownRuleIdException ex) {
            require(ex.getMessage() != null && ex.getMessage().contains(id),
                    "unknown rule ID error omitted " + id);
        }
    }

    private static void expectSnapshotReject(Runnable action, String id) {
        try {
            action.run();
            throw new IllegalStateException("expected snapshot rejection for " + id);
        } catch (SnapshotDecodeException ex) {
            require(ex.getMessage() != null && ex.getMessage().contains(id),
                    "snapshot rejection omitted " + id);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
