package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

public final class ClientEnvironmentSyncValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);
    private static final String TARGET_SYSTEM = "nebula_expanse_2";

    private ClientEnvironmentSyncValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem client environment synchronization validation passed.");
    }

    static void validateOrThrow() {
        try {
            GalaxyRuntimeOptions.configureCopies(2);
            validateSecondCopyViewAndOrbitPrediction();
            validatePartialResourceSyncPreservesOrbitPrediction();
            validateFogPersistenceAcrossSavedServerRestart();
        } finally {
            GalaxyRuntimeOptions.configureCopies(1);
        }
    }

    private static void validateSecondCopyViewAndOrbitPrediction() {
        PlayerRegistry.reset("WAIT", "Environment Server", 0x50BEFF);
        World server = new World("Environment Server", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        server.activateSystem(TARGET_SYSTEM);
        require(TARGET_SYSTEM.equals(server.activeSystemId()), "server could not activate second-copy system");

        ResourceSyncMode.fullForNextSnapshot();
        Snapshot full = WorldNetAccess.snapshot(server, 1);
        require(!full.resources().isEmpty(), "full-view snapshot omitted resources");

        World client = new World("Environment Client", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        client.useSystemSeed(server.systemSeed());
        client.ensurePlayerHome("P1");
        String preservedHome = client.playerHomeSystemId("P1");
        client.activateSystem(preservedHome);

        PlayerRegistry.activate(client);
        WorldNetAccess.applyFullView(client, full);
        require(TARGET_SYSTEM.equals(client.activeSystemId()), "full-view snapshot did not activate exact system instance");
        require(hasSystem(client.authoritativeGalaxyMapSnapshot(), preservedHome), "full-view reset destroyed an existing player home");

        ResourceNode node = firstOrbiting(client);
        double beforeAngle = node.orbitAngle;
        double beforeTime = client.systemTime();
        ClientEnvironmentSync.advance(client, 0.25);
        require(client.systemTime() > beforeTime, "client visual system time did not advance");
        require(Math.abs(node.orbitAngle - beforeAngle) > 0.000001, "client resource orbit remained frozen between snapshots");

        PlayerRegistry.activate(server);
        server.updateCurrentSystem(0.25);
        Snapshot regular = WorldNetAccess.snapshot(server, 2);
        require(regular.resources().isEmpty(), "regular correction test unexpectedly included every resource");

        PlayerRegistry.activate(client);
        WorldNetAccess.applyView(client, regular);
        double afterSnapshot = node.orbitAngle;
        ClientEnvironmentSync.advance(client, 0.1);
        require(Math.abs(node.orbitAngle - afterSnapshot) > 0.000001, "resource orbit froze after sparse snapshot apply");
        require(TARGET_SYSTEM.equals(client.activeSystemId()), "sparse snapshot changed the viewed system instance");
        require(hasSystem(client.authoritativeGalaxyMapSnapshot(), preservedHome), "sparse snapshot destroyed an existing player home");

        PlayerRegistry.activate(server);
        ResourceSyncMode.fullForNextSnapshot();
        Snapshot correction = WorldNetAccess.snapshot(server, 3);
        require(correction.resources().size() == server.resources.size(), "corrective snapshot did not contain all resources");
        PlayerRegistry.activate(client);
        WorldNetAccess.applyView(client, correction);
        require(client.resources.size() == server.resources.size(), "client did not retain complete corrected resource set");
    }

    private static void validatePartialResourceSyncPreservesOrbitPrediction() {
        World client = new World("Partial Resource Client", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        ResourceNode node = firstOrbiting(client);
        node.updateOrbit(0.25);

        double beforeX = node.x;
        double beforeY = node.y;
        double beforeAngle = node.orbitAngle;
        double updatedAmount = Math.max(0.5, node.amount - 0.5);
        ResourceState state = new ResourceState(node.id, node.name, node.kind.name(), node.material.name(),
                node.x + 1.0, node.y, node.maxAmount, node.harvestRate, node.radius,
                updatedAmount, true, 0,
                node.orbitCenterX, node.orbitCenterY, node.orbitRadius,
                node.orbitAngle + 0.4, node.orbitSpeed, node.orbiting);

        NetResourceSync.apply(client, List.of(state));

        require(Math.abs(node.amount - updatedAmount) < 0.000001,
                "partial resource sync did not update authoritative amount");
        require(Math.abs(node.x - beforeX) < 0.000001 && Math.abs(node.y - beforeY) < 0.000001,
                "sub-threshold resource sync snapped the predicted orbit position");
        require(Math.abs(node.orbitAngle - beforeAngle) < 0.000001,
                "sub-threshold resource sync rewound the predicted orbit phase");
    }

    private static void validateFogPersistenceAcrossSavedServerRestart() {
        String previousStore = System.getProperty("starchem.sessionStore");
        try {
            Path directory = Files.createTempDirectory("starchem-fow-restart-");
            System.setProperty("starchem.sessionStore", directory.resolve("sessions.properties").toString());
            FogOfWarPersistence.clearEnvironmentSeedsForTest();

            World firstClient = new World("Fog Client One", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
            firstClient.activateSystem(TARGET_SYSTEM);
            long firstLocalSeed = firstClient.systemSeed();
            long savedServerEnvironmentSeed = 0x31A8B7C6D5E4F203L;
            stageEnvironmentSeed(firstClient, savedServerEnvironmentSeed);
            require(FogOfWarPersistence.environmentSeedForTest(TARGET_SYSTEM, firstLocalSeed)
                            == savedServerEnvironmentSeed,
                    "server-provided environment identity was not retained for fog persistence");

            int columns = Math.max(1, (int)Math.ceil(firstClient.width / (double)FogOfWarView.CELL_SIZE));
            int rows = Math.max(1, (int)Math.ceil(firstClient.height / (double)FogOfWarView.CELL_SIZE));
            FogOfWarPersistence.clearForTest("P1", TARGET_SYSTEM, firstLocalSeed, columns, rows);
            BitSet explored = new BitSet(columns * rows);
            explored.set(3);
            explored.set(Math.min(columns * rows - 1, 17));
            FogOfWarPersistence.saveLater("P1", TARGET_SYSTEM, firstLocalSeed, columns, rows, explored,
                    List.of(new FogOfWarView.KnownWormhole("restart-gate", "restart-target", 1200, 900)));
            FogOfWarPersistence.flushForTest();

            FogOfWarPersistence.clearEnvironmentSeedsForTest();
            World restartedClient = new World("Fog Client Two", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
            restartedClient.activateSystem(TARGET_SYSTEM);
            long restartedLocalSeed = restartedClient.systemSeed();
            require(restartedLocalSeed != firstLocalSeed,
                    "restart fixture unexpectedly reused the client-local world seed");
            stageEnvironmentSeed(restartedClient, savedServerEnvironmentSeed);
            FogOfWarPersistence.Stored restored = FogOfWarPersistence.load(
                    "P1", TARGET_SYSTEM, restartedLocalSeed, columns, rows);
            require(restored.explored().equals(explored),
                    "explored fog was not restored with the saved server environment identity");
            require(restored.wormholes().stream().anyMatch(gate -> "restart-gate".equals(gate.id())
                            && "restart-target".equals(gate.toSystemId())),
                    "known wormhole memory was not restored after server restart");

            FogOfWarPersistence.clearEnvironmentSeedsForTest();
            World newWorldClient = new World("Fog Client New World", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
            newWorldClient.activateSystem(TARGET_SYSTEM);
            stageEnvironmentSeed(newWorldClient, savedServerEnvironmentSeed + 1);
            FogOfWarPersistence.Stored isolated = FogOfWarPersistence.load(
                    "P1", TARGET_SYSTEM, newWorldClient.systemSeed(), columns, rows);
            require(isolated.explored().isEmpty() && isolated.wormholes().isEmpty(),
                    "fog memory leaked into a different authoritative saved world");
        } catch (Exception ex) {
            throw new IllegalStateException("fog restart persistence validation failed", ex);
        } finally {
            FogOfWarPersistence.clearEnvironmentSeedsForTest();
            if (previousStore == null) System.clearProperty("starchem.sessionStore");
            else System.setProperty("starchem.sessionStore", previousStore);
        }
    }

    private static void stageEnvironmentSeed(World client, long environmentSeed) {
        CelestialPacketCache.receive(client.activeSystemId(), environmentSeed + "~");
        ClientEnvironmentSync.synchronizeSnapshot(client, client.activeSystemId(), client.systemTime(), false);
    }

    private static ResourceNode firstOrbiting(World world) {
        for (ResourceNode node : world.resources) if (node.orbiting && node.active) return node;
        throw new IllegalStateException("test system has no active orbiting resource");
    }

    private static boolean hasSystem(GalaxyMapSnapshot map, String systemId) {
        for (GalaxyMapSystem system : map.systems()) if (systemId.equals(system.id())) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
