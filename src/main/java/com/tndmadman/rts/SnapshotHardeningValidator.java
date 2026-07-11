package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class SnapshotHardeningValidator {
    private SnapshotHardeningValidator() { }

    public static void main(String[] args) {
        validateStrictWireDecoding();
        validateCountAndNumericLimits();
        validateAtomicApplicationAndRecovery();
        System.out.println("StarChem snapshot hardening validation passed.");
    }

    private static void validateStrictWireDecoding() {
        Snapshot valid = validSnapshot(1, 1000, 1000);
        String encoded = SnapshotWriter.write(valid);
        Snapshot decoded = SnapshotReader.read(encoded);
        require(decoded.units().size() == 1, "valid snapshot did not decode");

        String[] sections = encoded.split("\\|", -1);
        for (int length = 1; length < sections.length; length++) {
            String truncated = String.join("|", Arrays.copyOf(sections, length));
            expectReject(() -> SnapshotReader.read(truncated), "sections");
        }

        String[] badSequence = sections.clone();
        badSequence[1] = "not-a-number";
        expectReject(() -> SnapshotReader.read(String.join("|", badSequence)), "sequence");

        String[] badUnitSections = sections.clone();
        String[] unit = badUnitSections[3].split(",", -1);
        unit[3] = "NaN";
        badUnitSections[3] = String.join(",", unit);
        expectReject(() -> SnapshotReader.read(String.join("|", badUnitSections)), "finite");

        String[] shortResource = sections.clone();
        shortResource[4] = "1,Rock,SILICATE_ROCK";
        expectReject(() -> SnapshotReader.read(String.join("|", shortResource)), "columns");

        Snapshot badMaterial = new Snapshot(2, valid.players(), valid.units(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ItemState(1, "UNKNOWN_MATERIAL", 1, 0, 0, 0, 0, 0, 0)), "", -1);
        expectReject(() -> SnapshotReader.read(SnapshotWriter.write(badMaterial)), "material");

        Snapshot afterInvalid = SnapshotReader.read(encoded);
        require(afterInvalid.sequence() == 1, "valid snapshot was not accepted after invalid frames");
    }

    private static void validateCountAndNumericLimits() {
        UnitState unit = validUnit(1, 0, 0);
        List<UnitState> tooMany = new ArrayList<>();
        for (int i = 0; i <= SnapshotReader.MAX_UNITS; i++) tooMany.add(unit);
        Snapshot oversized = new Snapshot(1, List.of(), tooMany, List.of(), List.of(), List.of(), List.of(), List.of(), "", -1);
        expectReject(() -> SnapshotValidator.validate(oversized), "count");

        Snapshot infinite = new Snapshot(1, List.of(), List.of(validUnit(1, Double.POSITIVE_INFINITY, 0)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", -1);
        expectReject(() -> SnapshotValidator.validate(infinite), "finite");

        Snapshot extreme = new Snapshot(1, List.of(), List.of(validUnit(1, SnapshotReader.MAX_ABS_COORDINATE + 1, 0)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", -1);
        expectReject(() -> SnapshotValidator.validate(extreme), "range");
    }

    private static void validateAtomicApplicationAndRecovery() {
        PlayerRegistry.reset("SOLO", "Snapshot Validator", 0x50BEFF);
        World world = new World("Snapshot Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Unit existing = new Unit("SOLO", 1, Rules.STARTING_SHIP, 10, 10);
        world.units.put(existing.key(), existing);

        Snapshot invalid = new Snapshot(1,
                List.of(new PlayerInfo("SOLO", "Snapshot Validator", 0x50BEFF, true)),
                List.of(validUnit(1, 1000, 1000)),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new ItemState(1, "NOT_A_MATERIAL", 1, 0, 0, 0, 0, 0, 0)), "", -1);

        expectReject(() -> WorldNetAccess.apply(world, invalid), "material");
        require(world.units.size() == 1, "rejected snapshot changed unit count");
        require(world.units.get(existing.key()) == existing, "rejected snapshot replaced the existing unit");
        require(existing.x == 10 && existing.y == 10, "rejected snapshot partially changed unit position");
        require(world.items.isEmpty(), "rejected snapshot partially changed items");

        Snapshot valid = validSnapshot(2, 1000, 1000);
        WorldNetAccess.apply(world, valid);
        Unit applied = world.units.get(existing.key());
        require(applied != null && applied.x == 1000 && applied.y == 1000,
                "valid snapshot was not applied after a rejected snapshot");
    }

    private static Snapshot validSnapshot(long sequence, double x, double y) {
        return new Snapshot(sequence,
                List.of(new PlayerInfo("SOLO", "Snapshot Validator", 0x50BEFF, true)),
                List.of(validUnit(1, x, y)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", -1);
    }

    private static UnitState validUnit(int id, double x, double y) {
        ShipType ship = Rules.ship(Rules.STARTING_SHIP);
        return new UnitState("SOLO", id, Rules.STARTING_SHIP, x, y, x, y, 0,
                UnitTask.IDLE.name(), -1, "", "", ship.maxHp, ship.maxShield,
                "", 0, UnitOrderType.NONE.name(), 0, 0, 0, 0, 0, "", 0);
    }

    private static void expectReject(Runnable action, String expectedText) {
        try {
            action.run();
            throw new IllegalStateException("expected snapshot rejection containing " + expectedText);
        } catch (SnapshotDecodeException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            require(message.contains(expectedText.toLowerCase()),
                    "snapshot rejection did not mention " + expectedText + ": " + ex.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
