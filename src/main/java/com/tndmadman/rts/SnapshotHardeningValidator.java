package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class SnapshotHardeningValidator {
    private SnapshotHardeningValidator() { }

    public static void main(String[] args) {
        validateStrictWireDecoding();
        validatePackedSystemRoundTrip();
        validateCountAndNumericLimits();
        validateAtomicApplicationAndRecovery();
        validateViewSwitchRecovery();
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

    private static void validatePackedSystemRoundTrip() {
        String systemId = StarSystems.PLAYER_HOME_SYSTEM_ID + "_P2";
        String state = "123456789~100,200,0";
        Snapshot original = new Snapshot(3,
                List.of(new PlayerInfo("P2", "Remote Player", 0x7DFF7A, false)),
                List.of(validUnit("P2", 2, 1200, 1300)),
                List.of(validResource(1, 1500, 1500, 500)), List.of(), List.of(), List.of(), List.of(),
                systemId + "~" + state, 42);

        require(systemId.equals(original.systemId()), "snapshot constructor retained a packed system ID");
        require(state.equals(original.celestialState()), "snapshot constructor lost embedded celestial state");

        String encoded = SnapshotWriter.write(original);
        Snapshot decoded = SnapshotReader.read(encoded);
        require(systemId.equals(decoded.systemId()), "snapshot decoder exposed a packed system ID");
        require(state.equals(decoded.celestialState()), "snapshot decoder lost celestial state");
        require(encoded.equals(SnapshotWriter.write(decoded)), "snapshot environment state did not round-trip");
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

    private static void validateViewSwitchRecovery() {
        PlayerRegistry.reset("WAIT", "View Validator", 0x50BEFF);
        World world = new World("View Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PeerTransport transport = null;
        try {
            Config config = Config.join("View Validator", "127.0.0.1", 55000);
            transport = PeerTransport.client(config.serverAddress, new PerfStats());
            PeerClientSide client = new PeerClientSide(config, world, transport);
            client.readWelcome(new String[]{
                    "WELCOME", "P1", "View Validator", Integer.toString(0x50BEFF),
                    world.systemId(), Long.toString(world.systemSeed()), "0", "DEV", "0",
                    "SESSION", "snapshot-validator-session-token-0000000000000000"
            });

            String homeSystem = world.playerHomeSystemId("P1");
            String remoteSystem = StarSystems.PLAYER_HOME_SYSTEM_ID + "_P2";

            client.jump("P1", remoteSystem, 0, 0);
            client.readFullView(SyncFrame.write(viewSnapshot(10, remoteSystem, "P2", 2, 2200, 2300, 500)));
            require(remoteSystem.equals(world.activeSystemId()), "remote view snapshot was rejected as a different system");
            Unit remote = world.units.get(Unit.key("P2", 2));
            require(remote != null && remote.x == 2200 && remote.y == 2300,
                    "remote player assets were not visible after switching systems");

            client.jump("P1", homeSystem, 0, 0);
            client.readFullView(SyncFrame.write(viewSnapshot(11, homeSystem, "P1", 1, 1100, 1200, 450)));
            require(homeSystem.equals(world.activeSystemId()), "home view snapshot was rejected after returning");
            Unit home = world.units.get(Unit.key("P1", 1));
            require(home != null && home.x == 1100 && home.y == 1200,
                    "home player assets were not restored after returning");

            client.readSnapshot(SnapshotWriter.write(viewSnapshot(12, homeSystem, "P1", 1, 1400, 1500, 300)));
            Unit updated = world.units.get(Unit.key("P1", 1));
            ResourceNode resource = world.findResource(1);
            require(updated != null && updated.x == 1400 && updated.y == 1500,
                    "ships stopped accepting snapshots after returning home");
            require(resource != null && Math.abs(resource.amount - 300) < 0.001,
                    "resources stopped accepting snapshots after returning home");
        } catch (Exception ex) {
            throw new IllegalStateException("view switch regression validation failed", ex);
        } finally {
            if (transport != null) transport.shutdown();
        }
    }

    private static Snapshot validSnapshot(long sequence, double x, double y) {
        return new Snapshot(sequence,
                List.of(new PlayerInfo("SOLO", "Snapshot Validator", 0x50BEFF, true)),
                List.of(validUnit(1, x, y)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", -1);
    }

    private static Snapshot viewSnapshot(long sequence, String systemId, String playerId, int unitId,
                                          double x, double y, double resourceAmount) {
        return new Snapshot(sequence,
                List.of(new PlayerInfo(playerId, playerId, 0x7DFF7A, "P1".equals(playerId))),
                List.of(validUnit(playerId, unitId, x, y)),
                List.of(validResource(1, x + 300, y + 300, resourceAmount)),
                List.of(), List.of(), List.of(), List.of(), systemId, 100 + sequence,
                (9000 + sequence) + "~100,200,0");
    }

    private static ResourceState validResource(int id, double x, double y, double amount) {
        return new ResourceState(id, "Validator Rock", NodeKind.SILICATE_ROCK.name(), Material.IRON.name(),
                x, y, 1000, 10, 45, amount, true, 0, x, y, 0, 0, 0, false);
    }

    private static UnitState validUnit(int id, double x, double y) {
        return validUnit("SOLO", id, x, y);
    }

    private static UnitState validUnit(String playerId, int id, double x, double y) {
        ShipType ship = Rules.ship(Rules.STARTING_SHIP);
        return new UnitState(playerId, id, Rules.STARTING_SHIP, x, y, x, y, 0,
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
