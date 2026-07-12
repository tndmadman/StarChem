package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
        try {
            validateStaticPlan(1);
            validateStaticPlan(2);
            validateWanderingTopology();
            validateRuntimeWanderingTopology();
            validateSoloBootstrap();
            validateShipRoundTrip();
            validateLocalHostBootstrap();
            validateStaticSystemsSurviveCleanup();
        } finally {
            GalaxyRuntimeOptions.configureCopies(1);
        }
    }

    private static void validateStaticPlan(int copies) {
        GalaxyRuntimeOptions.configureCopies(copies);
        PlayerRegistry.reset("WAIT", "Galaxy Validator", 0x50BEFF);
        World world = new World("Galaxy Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        int templates = StarSystems.staticOptions().size();
        require(map.systems().size() == templates * copies,
                "static galaxy did not create exactly " + copies + " copy/copies of every template");
        require(map.systems().stream().allMatch(GalaxyMapSystem::staticSystem),
                "static bootstrap contained a dynamic system");
        for (StarSystemDefinition template : StarSystems.staticOptions()) {
            long count = map.systems().stream().filter(system -> template.id().equals(system.templateId())).count();
            require(count == copies, template.id() + " appeared " + count + " times instead of " + copies);
        }
        require(connected(map), "static galaxy graph is not connected");
        require(map.links().size() >= map.systems().size(), "static galaxy graph is too sparse for redundant travel");
        require(!world.wormholes.isEmpty(), "authoritative static entry system has no wormholes");
    }

    private static void validateWanderingTopology() {
        GalaxyPlan base = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, 8675309L, new GalaxyTopologyRules(0));
        GalaxyPlan added = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, 8675309L, new GalaxyTopologyRules(4));
        GalaxyPlan repeated = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, 8675309L, new GalaxyTopologyRules(4));
        require(added.links().size() == base.links().size() + 4,
                "configured wandering wormhole pair count was not added exactly");
        require(linkKeys(added).equals(linkKeys(repeated)),
                "wandering wormholes are not deterministic for the same galaxy seed");
        require(linkKeys(added).size() == added.links().size(),
                "wandering wormholes created a duplicate link");
    }

    private static void validateRuntimeWanderingTopology() {
        GalaxyRuntimeOptions.configureCopies(1);
        PlayerRegistry.reset("WAIT", "Wandering Runtime Validator", 0x50BEFF);
        World world = new World("Wandering Runtime Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        GalaxyPlan fixed = GalaxyPlanner.standard(StarSystems.DEFAULT_SYSTEM_ID, 1, world.systemSeed(), new GalaxyTopologyRules(0));
        int expected = fixed.links().size() + GalaxyTopologyRules.load().wanderingWormholePairs();
        require(map.links().size() == expected,
                "runtime galaxy created " + map.links().size() + " links instead of configured " + expected);
        for (GalaxyMapLink link : map.links()) {
            world.activateSystem(link.fromSystemId());
            require(gateTo(world, link.toSystemId()) != null,
                    "runtime galaxy is missing forward gate " + link.fromSystemId() + " -> " + link.toSystemId());
            world.activateSystem(link.toSystemId());
            require(gateTo(world, link.fromSystemId()) != null,
                    "runtime galaxy is missing return gate " + link.toSystemId() + " -> " + link.fromSystemId());
        }
    }

    private static Set<String> linkKeys(GalaxyPlan plan) {
        Set<String> keys = new HashSet<>();
        for (GalaxyLinkSpec link : plan.links()) {
            String a = link.fromSystemId().compareTo(link.toSystemId()) <= 0 ? link.fromSystemId() : link.toSystemId();
            String b = link.fromSystemId().compareTo(link.toSystemId()) <= 0 ? link.toSystemId() : link.fromSystemId();
            keys.add(a + "->" + b);
        }
        return keys;
    }

    private static void validateSoloBootstrap() {
        GalaxyRuntimeOptions.configureCopies(1);
        PlayerRegistry.reset("SOLO", "Galaxy Validator", 0x50BEFF);
        StarSystemDefinition selected = StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID);
        World world = new World("Galaxy Validator", Set.of(), selected.id(), true);
        require(SOLO_HOME_ID.equals(world.activeSystemId()), "solo did not start in its protected home");
        require(world.units.size() == 1 && world.bases.size() == 1, "solo starting assets were not created exactly once");
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        require(map.systems().size() == StarSystems.staticOptions().size() + 1,
                "solo galaxy should contain every static template plus one home");
        GalaxyMapSystem home = system(map, SOLO_HOME_ID);
        require(home != null && home.home() && home.controlStatus() == SystemControlStatus.PROTECTED,
                "solo home is not represented as protected");
        require(degree(map, SOLO_HOME_ID) >= 2, "solo home lacks redundant links into the static galaxy");
    }

    private static void validateShipRoundTrip() {
        World world = new World("Galaxy Travel Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        Unit ship = world.units.values().iterator().next();
        WormholeGate outbound = world.wormholes.get(0);
        String target = outbound.toSystemId;
        ship.x = outbound.x;
        ship.y = outbound.y;
        ship.wormholeCooldown = 0;
        require(world.transferTouchingShips(world.localPlayerId), "ship did not leave the protected home");
        world.activateSystem(target);
        ship = world.units.get(ship.key());
        require(ship != null, "ship was not present in destination system");
        WormholeGate inbound = gateTo(world, SOLO_HOME_ID);
        require(inbound != null, "destination has no return link to protected home");
        ship.x = inbound.x;
        ship.y = inbound.y;
        ship.wormholeCooldown = 0;
        require(world.transferTouchingShips(world.localPlayerId), "ship did not return through the wormhole");
        world.activateSystem(SOLO_HOME_ID);
        require(world.units.containsKey(ship.key()), "returned ship was missing from protected home");
    }

    private static void validateLocalHostBootstrap() {
        GalaxyRuntimeOptions.configureCopies(1);
        StarSystemDefinition selected = StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID);
        World server = new World("Local Host Validator", Set.of(), selected.id(), false);
        PlayerRegistry.activate(server);
        PlayerRegistry.reset("SOLO", "Local Host Validator", 0x50BEFF);
        PlayerRegistry.register("P1", "Local Host Validator", 0xFF5F55, false);
        WorldNetAccess.addPeerGroup(server, "P1");
        server.activateSystem(HOST_HOME_ID);

        GalaxyMapSnapshot serverMap = server.authoritativeGalaxyMapSnapshot();
        require(system(serverMap, HOST_HOME_ID) != null, "local host did not create the P1 home");
        require(serverMap.systems().size() == StarSystems.staticOptions().size() + 1,
                "local host lost a static system while creating P1 home");
        require(serverMap.systems().stream().noneMatch(system -> SOLO_HOME_ID.equals(system.id())),
                "local host created an obsolete SOLO home");

        Snapshot snapshot = WorldNetAccess.snapshot(server, 1);
        require(snapshot.players().stream().noneMatch(player -> "SOLO".equals(player.id())),
                "network snapshot leaked the SOLO placeholder");
        require(WorldNetAccess.hasPlayerAssets(snapshot, "P1"), "network snapshot did not contain the P1 fleet");

        World client = new World("Local Host Validator", Set.of(), selected.id(), false);
        PlayerRegistry.activate(client);
        PlayerRegistry.reset("WAIT", "Local Host Validator", 0x50BEFF);
        PlayerRegistry.register("P1", "Local Host Validator", 0xFF5F55, true);
        client.ensurePlayerHome("P1", WorldNetAccess.usesPrimaryHome("P1"));
        client.activateSystem(client.playerHomeSystemId("P1"));
        WorldNetAccess.apply(client, snapshot);
        require(HOST_HOME_ID.equals(client.activeSystemId()), "local host client left its assigned home during snapshot apply");
        require(client.units.size() == 1 && client.bases.size() == 1, "local host client duplicated starting assets");
    }

    private static void validateStaticSystemsSurviveCleanup() {
        GalaxyRuntimeOptions.configureCopies(2);
        World world = new World("Cleanup Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        int before = (int)world.authoritativeGalaxyMapSnapshot().systems().stream().filter(GalaxyMapSystem::staticSystem).count();
        world.spawnPlayerGroup("P9", 9);
        Set<String> deleted = world.removePlayerAndPruneEmptySystems("P9");
        require(deleted.stream().allMatch(GalaxySystemIdentity::playerHome), "cleanup deleted a permanent system");
        int after = (int)world.authoritativeGalaxyMapSnapshot().systems().stream().filter(GalaxyMapSystem::staticSystem).count();
        require(before == after, "static system count changed during player cleanup");
    }

    private static boolean connected(GalaxyMapSnapshot map) {
        if (map.systems().isEmpty()) return true;
        Map<String, Set<String>> graph = new HashMap<>();
        for (GalaxyMapSystem system : map.systems()) graph.put(system.id(), new HashSet<>());
        for (GalaxyMapLink link : map.links()) {
            graph.get(link.fromSystemId()).add(link.toSystemId());
            graph.get(link.toSystemId()).add(link.fromSystemId());
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(map.systems().get(0).id());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!visited.add(id)) continue;
            queue.addAll(graph.getOrDefault(id, Set.of()));
        }
        return visited.size() == map.systems().size();
    }

    private static int degree(GalaxyMapSnapshot map, String id) {
        int degree = 0;
        for (GalaxyMapLink link : map.links()) if (id.equals(link.fromSystemId()) || id.equals(link.toSystemId())) degree++;
        return degree;
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        for (GalaxyMapSystem system : snapshot.systems()) if (id.equals(system.id())) return system;
        return null;
    }

    private static WormholeGate gateTo(World world, String target) {
        for (WormholeGate gate : world.wormholes) if (target.equals(gate.toSystemId)) return gate;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
