package com.tndmadman.rts;

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
