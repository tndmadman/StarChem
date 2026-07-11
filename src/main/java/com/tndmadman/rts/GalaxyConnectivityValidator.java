package com.tndmadman.rts;

import java.util.Set;

public final class GalaxyConnectivityValidator {
    private static final String SOLO_HOME_ID = StarSystems.PLAYER_HOME_SYSTEM_ID + "_SOLO";
    private static final String HOST_HOME_ID = StarSystems.PLAYER_HOME_SYSTEM_ID + "_P1";

    private GalaxyConnectivityValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem galaxy connectivity validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("SOLO", "Galaxy Validator", 0x50BEFF);
        for (StarSystemDefinition definition : StarSystems.options()) validateSoloTemplate(definition);
        validateShipRoundTrip();
        validateNonSoloBootstrap();
        validateLocalHostBootstrap();
    }

    private static void validateSoloTemplate(StarSystemDefinition definition) {
        World world = new World("Galaxy Validator", Set.of(), definition.id(), true);
        require(SOLO_HOME_ID.equals(world.activeSystemId()),
                definition.id() + ": solo did not start in a generated player-home system");
        require(SOLO_HOME_ID.equals(world.playerHomeSystemId(world.localPlayerId)),
                definition.id() + ": solo home registration does not match the active system");
        require(definition.name().equals(world.systemName()),
                definition.id() + ": selected system definition name was not preserved");
        require(definition.width() == world.width && definition.height() == world.height,
                definition.id() + ": selected system dimensions were not preserved");
        require(world.units.size() == 1 && world.bases.size() == 1,
                definition.id() + ": starting assets were not spawned in the generated home");
        require(gateTo(world, StarSystems.CORSAIR_SYSTEM_ID) != null,
                definition.id() + ": solo home has no wormhole to the Corsair system");

        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        require(snapshot.systems().size() == 2,
                definition.id() + ": solo graph should contain only the generated home and Corsair system");
        require(snapshot.links().size() == 1,
                definition.id() + ": solo graph should contain one bidirectional home/Corsair link");
        if (!StarSystems.CORSAIR_SYSTEM_ID.equals(definition.id())) {
            require(snapshot.systems().stream().noneMatch(system -> definition.id().equals(system.id())),
                    definition.id() + ": unused template system remained as an orphan map node");
        }

        require(world.viewSystemThroughWormhole(StarSystems.CORSAIR_SYSTEM_ID),
                definition.id() + ": could not view the Corsair system through the generated wormhole");
        require(gateTo(world, SOLO_HOME_ID) != null,
                definition.id() + ": Corsair system has no return wormhole to the solo home");
        require(world.viewSystemThroughWormhole(SOLO_HOME_ID),
                definition.id() + ": could not return to the solo home through the wormhole");
    }

    private static void validateShipRoundTrip() {
        World world = new World("Galaxy Travel Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        Unit ship = world.units.values().iterator().next();
        WormholeGate outbound = gateTo(world, StarSystems.CORSAIR_SYSTEM_ID);
        require(outbound != null, "round-trip test has no outbound wormhole");
        ship.x = outbound.x;
        ship.y = outbound.y;
        ship.wormholeCooldown = 0;
        require(world.transferTouchingShips(world.localPlayerId), "ship did not travel from the solo home to Corsair");

        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        ship = world.units.get(ship.key());
        require(ship != null, "ship was not present in the Corsair system after travel");
        WormholeGate inbound = gateTo(world, SOLO_HOME_ID);
        require(inbound != null, "round-trip test has no return wormhole");
        ship.x = inbound.x;
        ship.y = inbound.y;
        ship.wormholeCooldown = 0;
        require(world.transferTouchingShips(world.localPlayerId), "ship did not return from Corsair to the solo home");

        world.activateSystem(SOLO_HOME_ID);
        require(world.units.containsKey(ship.key()), "returned ship was not present in the solo home");
    }

    private static void validateNonSoloBootstrap() {
        World world = new World("Network Bootstrap Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        require(StarSystems.DEFAULT_SYSTEM_ID.equals(world.activeSystemId()),
                "non-solo bootstrap unexpectedly generated a local player home");
        require(world.wormholes.isEmpty(), "non-solo bootstrap unexpectedly linked the default system");
        require(snapshot.systems().size() == 2 && snapshot.links().isEmpty(),
                "non-solo bootstrap graph changed while fixing solo startup");
    }

    private static void validateLocalHostBootstrap() {
        StarSystemDefinition selected = StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID);
        World server = new World("Local Host Validator", Set.of(), selected.id(), false);
        PlayerRegistry.activate(server);
        PlayerRegistry.reset("SOLO", "Local Host Validator", 0x50BEFF);
        PlayerRegistry.register("P1", "Local Host Validator", 0xFF5F55, false);
        WorldNetAccess.addPeerGroup(server, "P1");
        server.activateSystem(HOST_HOME_ID);

        GalaxyMapSnapshot serverMap = server.galaxyMapSnapshot();
        GalaxyMapSystem serverHome = system(serverMap, HOST_HOME_ID);
        require(serverHome != null, "local host did not create the P1 home system");
        require(selected.name().equals(serverHome.name()), "local host P1 home did not preserve the selected system definition");
        require(serverMap.systems().stream().noneMatch(system -> SOLO_HOME_ID.equals(system.id())),
                "local host created an obsolete SOLO home system");
        require(serverMap.systems().size() == 2 && serverMap.links().size() == 1,
                "local host graph should contain only P1 home and Corsair");
        require(server.units.size() == 1 && server.bases.size() == 1,
                "local host server did not create exactly one starting fleet");
        require(server.units.values().stream().allMatch(unit -> "P1".equals(unit.playerId)),
                "local host server created a ship for the wrong owner");
        require(server.bases.values().stream().allMatch(base -> "P1".equals(base.playerId)),
                "local host server created a base for the wrong owner");

        Snapshot snapshot = WorldNetAccess.snapshot(server, 1);
        require(snapshot.players().stream().noneMatch(player -> "SOLO".equals(player.id())),
                "network snapshot leaked the SOLO placeholder player");
        require(WorldNetAccess.hasPlayerAssets(snapshot, "P1"),
                "network snapshot did not contain the local host fleet");

        World client = new World("Local Host Validator", Set.of(), selected.id(), false);
        PlayerRegistry.activate(client);
        PlayerRegistry.reset("WAIT", "Local Host Validator", 0x50BEFF);
        PlayerRegistry.register("P1", "Local Host Validator", 0xFF5F55, true);
        client.ensurePlayerHome("P1", WorldNetAccess.usesPrimaryHome("P1"));
        client.activateSystem(client.playerHomeSystemId("P1"));
        WorldNetAccess.apply(client, snapshot);

        GalaxyMapSnapshot clientMap = client.galaxyMapSnapshot();
        require(clientMap.systems().stream().noneMatch(system -> SOLO_HOME_ID.equals(system.id())),
                "local host client recreated an obsolete SOLO home from the snapshot");
        require(HOST_HOME_ID.equals(client.activeSystemId()),
                "local host client was switched away from its assigned P1 home");
        require(client.units.size() == 1 && client.bases.size() == 1,
                "local host client received duplicate starting assets");
        require(client.units.values().stream().allMatch(unit -> "P1".equals(unit.playerId)),
                "local host client ship owner changed during snapshot apply");
        require(client.bases.values().stream().allMatch(base -> "P1".equals(base.playerId)),
                "local host client base owner changed during snapshot apply");

        require(client.viewGalaxySystem(StarSystems.CORSAIR_SYSTEM_ID),
                "local host client could not view Corsair after joining");
        require(client.units.isEmpty() && client.bases.isEmpty(),
                "local host assets were duplicated into Corsair");
        require(client.viewGalaxySystem(HOST_HOME_ID),
                "local host client could not return to its P1 home");
        require(client.units.size() == 1 && client.bases.size() == 1,
                "local host assets changed after switching systems");
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String systemId) {
        if (snapshot == null || snapshot.systems() == null) return null;
        for (GalaxyMapSystem system : snapshot.systems()) if (systemId.equals(system.id())) return system;
        return null;
    }

    private static WormholeGate gateTo(World world, String targetSystemId) {
        for (WormholeGate gate : world.wormholes) {
            if (targetSystemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
