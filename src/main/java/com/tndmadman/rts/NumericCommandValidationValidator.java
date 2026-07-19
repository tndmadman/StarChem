package com.tndmadman.rts;

import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Set;

/** Focused regression validation for non-finite and out-of-range gameplay numbers. */
public final class NumericCommandValidationValidator {
    private NumericCommandValidationValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            validateAuthenticatedCommands();
            validateAuthoritativeRepair();
            validateSerializationBoundaries();
        } else {
            switch (args[0]) {
                case "authenticated" -> validateAuthenticatedCommands();
                case "repair" -> validateAuthoritativeRepair();
                case "serialization" -> validateSerializationBoundaries();
                default -> throw new IllegalArgumentException("Unknown validation stage: " + args[0]);
            }
        }
        System.out.println("StarChem numeric command validation passed" + (args.length == 0 ? "." : ": " + args[0]));
    }

    private static void validateAuthenticatedCommands() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PerfStats perfStats = new PerfStats();
        PeerTransport transport = PeerTransport.server(0, perfStats);
        transport.start();
        try (Socket socket = new Socket(loopback, transport.localPort())) {
            socket.setTcpNoDelay(true);
            waitConnection(transport, loopback, socket.getLocalPort());

            Config config = Config.host("Numeric Validation Host", transport.localPort(), false);
            World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            PeerServerSide server = new PeerServerSide(config, world, transport);
            ConnectionId connectionId = transport.connectionId(loopback, socket.getLocalPort());
            String verifier = PasswordAuth.verifier("Numeric Client", "numeric-validation-password");
            server.join(connectionId, loopback, socket.getLocalPort(), "Numeric Client", verifier, false, "");

            Unit unit = firstUnit(world, "P1");
            double originalTargetX = unit.targetX;
            double originalTargetY = unit.targetY;

            rejectMove(server, connectionId, unit, "NaN", "100", originalTargetX, originalTargetY);
            rejectMove(server, connectionId, unit, "Infinity", "100", originalTargetX, originalTargetY);
            rejectMove(server, connectionId, unit, "-Infinity", "100", originalTargetX, originalTargetY);
            rejectMove(server, connectionId, unit, "1e309", "100", originalTargetX, originalTargetY);
            rejectMove(server, connectionId, unit, Double.toString(Math.nextDown(0.0)), "100", originalTargetX, originalTargetY);
            rejectMove(server, connectionId, unit, Double.toString(Math.nextUp((double)world.width)), "100", originalTargetX, originalTargetY);

            sendMove(server, connectionId, unit, "-0.0", "0.0");
            require(Double.doubleToRawLongBits(unit.targetX) == Double.doubleToRawLongBits(-0.0),
                    "signed zero move coordinate was not accepted");
            require(unit.targetY == 0.0, "zero move coordinate was not accepted");

            sendMove(server, connectionId, unit,
                    Double.toString(Math.nextDown((double)world.width)),
                    Double.toString(Math.nextDown((double)world.height)));
            require(unit.targetX < world.width && unit.targetY < world.height,
                    "coordinates immediately inside the world boundary were rejected");

            sendOrder(server, connectionId, unit, "HOLD", "100", "100", "100", "100", "0", "", "0");
            require(unit.orderType == UnitOrderType.HOLD, "valid hold order was rejected");
            double orderX = unit.orderX1;
            double orderY = unit.orderY1;
            sendOrder(server, connectionId, unit, "HOLD", "NaN", "100", "100", "100", "0", "", "0");
            require(unit.orderType == UnitOrderType.HOLD && unit.orderX1 == orderX && unit.orderY1 == orderY,
                    "non-finite order changed authoritative state");
            sendOrder(server, connectionId, unit, "HOLD", "100", "100", "100", "100",
                    Double.toString(Math.nextUp(GameplayCommandNumbers.MAX_ORDER_RADIUS)), "", "0");
            require(unit.orderType == UnitOrderType.HOLD && unit.orderX1 == orderX && unit.orderY1 == orderY,
                    "out-of-range order radius changed authoritative state");

            Thread.sleep(300);
            require(transport.perfSnapshot().malformedPacketsPerSecond() > 0,
                    "rejected numeric commands were not counted as malformed packets");
        } finally {
            transport.shutdown();
        }
    }

    private static void validateAuthoritativeRepair() {
        PlayerRegistry.reset("SOLO", "Numeric Repair", 0x50BEFF);
        World world = new World("Numeric Repair");
        Unit unit = world.units.values().iterator().next();

        unit.issueMove(320, 240);
        double targetX = unit.targetX;
        double targetY = unit.targetY;
        unit.issueMove(Double.NaN, Double.POSITIVE_INFINITY);
        require(unit.targetX == targetX && unit.targetY == targetY,
                "model-level move guard accepted non-finite coordinates");

        unit.x = Double.NaN;
        unit.y = Double.NEGATIVE_INFINITY;
        unit.targetX = 400;
        unit.targetY = 300;
        unit.heading = Double.NaN;
        unit.orderRadius = Double.POSITIVE_INFINITY;
        unit.updatePosition(1.0 / 60.0, world.width, world.height);
        require(GameplayCommandNumbers.worldCoordinate(world, unit.x, unit.y),
                "corrupted unit position was not repaired");
        require(GameplayCommandNumbers.worldCoordinate(world, unit.targetX, unit.targetY),
                "corrupted unit target was not repaired");
        require(Double.isFinite(unit.heading), "corrupted unit heading was not repaired");
        require(unit.orderType == UnitOrderType.NONE && unit.orderRadius == 0,
                "corrupted unit order was not cleared");

        double beforeCargo = unit.inventory.getOrDefault(Material.IRON, 0.0);
        unit.addCargo(Material.IRON, Double.POSITIVE_INFINITY);
        require(unit.inventory.getOrDefault(Material.IRON, 0.0) == beforeCargo,
                "non-finite cargo amount entered authoritative inventory");
    }

    private static void validateSerializationBoundaries() {
        PlayerRegistry.reset("SOLO", "Numeric Serialization", 0x50BEFF);
        World world = new World("Numeric Serialization");
        Unit unit = world.units.values().iterator().next();

        String validSnapshot = SnapshotWriter.write(WorldNetAccess.snapshot(world, 1));
        require(!validSnapshot.contains("NaN") && !validSnapshot.contains("Infinity"),
                "valid snapshot contained a non-finite number");

        double originalTarget = unit.targetX;
        unit.targetX = Double.NaN;
        expectReject(() -> SnapshotWriter.write(WorldNetAccess.snapshot(world, 2)),
                "snapshot writer emitted corrupted authoritative state");
        unit.targetX = originalTarget;

        expectReject(() -> MiniJson.stringify(Map.of("value", Double.NaN)),
                "save JSON writer accepted NaN");
        expectReject(() -> MiniJson.stringify(Map.of("value", Double.POSITIVE_INFINITY)),
                "save JSON writer accepted infinity");
        expectReject(() -> MiniJson.parse("{\"value\":1e309}"),
                "save JSON parser accepted an overflowing exponent");
        require(MiniJson.stringify(Map.of("value", -0.0)).contains("-0.0"),
                "save JSON writer rejected signed zero");
    }

    private static void rejectMove(PeerServerSide server, ConnectionId connectionId, Unit unit,
                                   String x, String y, double expectedX, double expectedY) {
        sendMove(server, connectionId, unit, x, y);
        require(unit.targetX == expectedX && unit.targetY == expectedY,
                "rejected move changed authoritative target for " + x + "," + y);
    }

    private static void sendMove(PeerServerSide server, ConnectionId connectionId, Unit unit, String x, String y) {
        SideAOrders.handle(server,
                new String[]{"MOVE", unit.playerId, Integer.toString(unit.unitId), x, y}, connectionId);
    }

    private static void sendOrder(PeerServerSide server, ConnectionId connectionId, Unit unit, String type,
                                  String x1, String y1, String x2, String y2, String radius,
                                  String target, String phase) {
        SideAOrders.handle(server,
                new String[]{"ORDER", unit.playerId, Integer.toString(unit.unitId), type,
                        x1, y1, x2, y2, radius, target, phase}, connectionId);
    }

    private static Unit firstUnit(World world, String playerId) {
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) return unit;
        throw new IllegalStateException("Player unit was not created");
    }

    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!transport.hasConnection(address, port) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        require(transport.hasConnection(address, port), "TCP connection was not registered");
    }

    private static void expectReject(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
