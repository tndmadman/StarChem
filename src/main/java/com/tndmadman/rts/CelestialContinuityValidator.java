package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Ensures periodic full-resource repairs do not hard-snap planets or moons. */
public final class CelestialContinuityValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private CelestialContinuityValidator() { }

    public static void main(String[] args) throws Exception {
        validateDirectCorrection();
        validateTcpCorrection();
        System.out.println("StarChem celestial continuity validation passed.");
    }

    private static void validateDirectCorrection() {
        PlayerRegistry.reset("WAIT", "Celestial Server", 0x50BEFF);
        World server = new World("Celestial Server", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        ResourceSyncMode.fullForNextSnapshot();
        Snapshot initial = WorldNetAccess.snapshot(server, 1);

        World smoothClient = new World("Smooth Client", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        World hardClient = new World("Hard Client", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(smoothClient);
        WorldNetAccess.applyFullView(smoothClient, initial);
        PlayerRegistry.activate(hardClient);
        WorldNetAccess.applyFullView(hardClient, initial);

        PlayerRegistry.activate(server);
        server.updateCurrentSystem(1.0);
        PlayerRegistry.activate(smoothClient);
        ClientEnvironmentSync.advance(smoothClient, 1.25);
        PlayerRegistry.activate(hardClient);
        ClientEnvironmentSync.advance(hardClient, 1.25);

        PlayerRegistry.activate(server);
        ResourceSyncMode.fullForNextSnapshot();
        Snapshot correction = WorldNetAccess.snapshot(server, 2);
        String correctionFrame = SyncFrame.writeResourceCorrection(correction);
        require(SyncFrame.isResourceCorrection(correctionFrame), "resource repair was not encoded as a resource correction");
        require(!SyncFrame.isView(correctionFrame), "resource repair was incorrectly encoded as a view synchronization");

        List<Point> smoothBefore = positions(smoothClient);
        List<Point> hardBefore = positions(hardClient);
        double smoothTimeBefore = smoothClient.systemTime();

        PlayerRegistry.activate(smoothClient);
        WorldNetAccess.applyResourceCorrection(smoothClient, SyncFrame.read(correctionFrame), true);
        PlayerRegistry.activate(hardClient);
        WorldNetAccess.applyFullView(hardClient, correction);

        double smoothStep = maxDisplacement(smoothBefore, positions(smoothClient));
        double hardStep = maxDisplacement(hardBefore, positions(hardClient));
        double smoothTimeAdjustment = Math.abs(smoothClient.systemTime() - smoothTimeBefore);

        require(smoothTimeAdjustment <= ClientEnvironmentSync.maxSlewSecondsPerSnapshot() + 0.000001,
                "resource correction hard-reset client environment time");
        require(hardStep > 0.001, "test setup did not produce a measurable hard celestial correction");
        require(smoothStep < hardStep * 0.35,
                "resource correction moved celestial bodies like a hard view reset: smooth=" + smoothStep + " hard=" + hardStep);
        require(smoothClient.resources.size() == server.resources.size(),
                "resource correction did not replace the complete resource set");
    }

    private static void validateTcpCorrection() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient client = harness.addClient("TCP Celestial Continuity");
            harness.awaitJoined(client);
            String systemId = client.world().activeSystemId();
            long previousSequence = client.network().clientSnapshotSequence();
            ClientEnvironmentSync.advance(client.world(), 0.25);
            double beforeTime = client.world().systemTime();
            List<Point> before = positions(client.world());

            harness.serverNetwork.forceServerResourceCorrectionForTest();
            harness.await(() -> client.network().clientSnapshotSequence() > previousSequence,
                    5_000, "forced TCP resource correction did not reach the client");

            double timeDelta = client.world().systemTime() - beforeTime;
            double displacement = maxDisplacement(before, positions(client.world()));
            require(systemId.equals(client.world().activeSystemId()),
                    "TCP resource correction changed the active system");
            require(timeDelta > -0.08,
                    "TCP resource correction rewound celestial time like a hard view reset: " + timeDelta);
            require(displacement < 250,
                    "TCP resource correction caused an excessive celestial jump: " + displacement);
        }
    }

    private static List<Point> positions(World world) {
        String state = CelestialSnapshotSync.write(world);
        List<Point> points = new ArrayList<>();
        if (state == null || state.isBlank()) return points;
        for (String row : state.split(";")) {
            String[] fields = row.split(",", -1);
            if (fields.length < 2) continue;
            points.add(new Point(Double.parseDouble(fields[0]), Double.parseDouble(fields[1])));
        }
        return points;
    }

    private static double maxDisplacement(List<Point> before, List<Point> after) {
        int count = Math.min(before.size(), after.size());
        double max = 0;
        for (int i = 0; i < count; i++) {
            Point a = before.get(i);
            Point b = after.get(i);
            max = Math.max(max, Math.hypot(a.x - b.x, a.y - b.y));
        }
        return max;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Point(double x, double y) { }
}
