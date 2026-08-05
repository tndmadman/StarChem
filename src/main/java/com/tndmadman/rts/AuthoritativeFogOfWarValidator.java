package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Validates server authority, durable restart recovery, player isolation, and client bootstrap. */
public final class AuthoritativeFogOfWarValidator {
    private AuthoritativeFogOfWarValidator() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("starchem-authoritative-fog-");
        ServerFogOfWarStore store = ServerFogOfWarStore.forTest(directory, "fog-authority");
        World server = fixture("Authoritative fog server");
        String systemId = server.activeSystemId();
        long seed = server.systemSeed();
        ServerFogOfWarState.configureForTest(server, store);

        Base radar = new Base("P1:FOG-RADAR", "P1", RadarTowerRules.TIER_ONE, 1_000, 1_000);
        server.bases.put(radar.id, radar);
        server.wormholes.add(new WormholeGate("server-known-gate", systemId, "server-known-target",
                1_120, 1_080, 220, 260));
        ServerFogOfWarState.observeSystem(server, "P1", systemId);
        int explored = ServerFogOfWarState.exploredCellCountForTest(server, "P1", systemId);
        require(explored > 0, "Authoritative sensors did not produce explored cells.");
        require(ServerFogOfWarState.exploredCellCountForTest(server, "P2", systemId) == 0,
                "Fog state leaked between players.");
        String packet = ServerFogOfWarState.packet(server, "P1", systemId);
        require(packet.startsWith("FOG_STATE|"), "Initial authoritative fog packet was not produced.");
        ServerFogOfWarState.flushForTest(server);

        server.bases.remove(radar.id);
        Base movedRadar = new Base("P1:FOG-RADAR-MOVED", "P1", RadarTowerRules.TIER_ONE, 2_400, 2_100);
        server.bases.put(movedRadar.id, movedRadar);
        ServerFogOfWarState.observeSystem(server, "P1", systemId);
        ServerFogOfWarState.flushForTest(server);
        int expanded = ServerFogOfWarState.exploredCellCountForTest(server, "P1", systemId);
        require(expanded > explored, "Authoritative exploration did not grow monotonically.");

        World restarted = fixture("Authoritative fog restart");
        restarted.useSystemSeed(seed);
        ServerFogOfWarState.configureForTest(restarted, store);
        require(ServerFogOfWarState.exploredCellCountForTest(restarted, "P1", systemId) == expanded,
                "Dedicated-server restart did not restore explored cells.");

        validateClientBootstrap(directory, restarted, packet, systemId);
        validatePreviousFileRecovery(directory, seed, systemId, explored);
        System.out.println("Authoritative fog-of-war validator passed.");
    }

    private static void validateClientBootstrap(Path directory, World client, String packet, String systemId) {
        String previousStore = System.getProperty("starchem.sessionStore");
        System.setProperty("starchem.sessionStore", directory.resolve("client-sessions.properties").toString());
        PlayerRegistry.activate(client);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        int columns = Math.max(1, (int)Math.ceil(client.width / (double)FogOfWarView.CELL_SIZE));
        int rows = Math.max(1, (int)Math.ceil(client.height / (double)FogOfWarView.CELL_SIZE));
        try {
            FogOfWarPersistence.clearForTest("P1", systemId, client.systemSeed(), columns, rows);
            FogOfWarView.clearCachedStateForTest(client);
            ServerFogOfWarState.applyClient(client, "P1", packet.substring("FOG_STATE|".length()));
            client.bases.clear();
            client.units.clear();
            client.wormholes.clear();
            FogOfWarView.forceRefreshForTest(client);
            require(FogOfWarView.exploredCellCount(client) > 0,
                    "Client did not restore server-owned explored cells from bootstrap.");
            require(FogOfWarView.knownWormholes(client).stream()
                            .anyMatch(gate -> "server-known-gate".equals(gate.id())),
                    "Client did not restore server-owned known wormholes from bootstrap.");
        } finally {
            FogOfWarView.clearCachedStateForTest(client);
            if (previousStore == null) System.clearProperty("starchem.sessionStore");
            else System.setProperty("starchem.sessionStore", previousStore);
        }
    }

    private static void validatePreviousFileRecovery(Path directory, long seed, String systemId, int minimum) throws Exception {
        Path current = directory.resolve("fog-authority-fog.properties");
        require(Files.isRegularFile(current), "Server fog sidecar was not written.");
        Files.writeString(current, "corrupt fog file");
        World recovered = fixture("Authoritative fog recovery");
        recovered.useSystemSeed(seed);
        ServerFogOfWarState.configureForTest(recovered, ServerFogOfWarStore.forTest(directory, "fog-authority"));
        require(ServerFogOfWarState.exploredCellCountForTest(recovered, "P1", systemId) >= minimum,
                "Previous server fog sidecar was not used after current-file corruption.");
    }

    private static World fixture(String name) {
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        PlayerRegistry.register("P2", "Opponent", 0xFF5F55, false);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.shots.clear();
        world.items.clear();
        world.wormholes.clear();
        return world;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
