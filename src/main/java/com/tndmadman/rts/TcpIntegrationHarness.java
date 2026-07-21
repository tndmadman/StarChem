package com.tndmadman.rts;

import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Shared in-process harness that exercises production PeerNetwork instances over real TCP sockets. */
final class TcpIntegrationHarness implements AutoCloseable {
    final Config serverConfig;
    final World serverWorld;
    final PeerNetwork serverNetwork;
    final HeadlessGameServer headlessServer;
    final List<TestClient> clients = new ArrayList<>();
    private final Path sessionStore;

    private TcpIntegrationHarness(boolean dedicated) throws Exception {
        sessionStore = Files.createTempFile("starchem-tcp-harness-", ".properties");
        Files.deleteIfExists(sessionStore);
        System.setProperty("starchem.sessionStore", sessionStore.toString());
        int port = freePort();
        if (dedicated) {
            serverConfig = Config.dedicatedServer("TCP Dedicated Validator", port, false,
                    Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
            headlessServer = HeadlessGameServer.start(serverConfig);
            serverWorld = headlessServer.world;
            serverNetwork = headlessServer.network;
        } else {
            serverConfig = Config.host("TCP Harness Host", port, false);
            serverWorld = new World(serverConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            serverNetwork = PeerNetwork.start(serverConfig, serverWorld);
            headlessServer = null;
        }
    }

    static TcpIntegrationHarness host() throws Exception { return new TcpIntegrationHarness(false); }
    static TcpIntegrationHarness dedicated() throws Exception { return new TcpIntegrationHarness(true); }

    TestClient addClient(String name) throws Exception { return addClient(name, null); }

    TestClient addProxiedClient(String name, TcpFaultProxy proxy) throws Exception {
        return addClient(name, Objects.requireNonNull(proxy, "proxy"));
    }

    private TestClient addClient(String name, TcpFaultProxy proxy) throws Exception {
        int port = proxy == null ? serverConfig.port : proxy.listenPort();
        Config config = Config.join(name, "127.0.0.1", port, false);
        PendingPlayerPassword.remember(config, "validator-password".toCharArray(), false);
        World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PeerNetwork network = PeerNetwork.start(config, world);
        TestClient client = new TestClient(config, world, network, proxy);
        clients.add(client);
        return client;
    }

    void tick() throws InterruptedException {
        if (headlessServer != null) headlessServer.tick(0.016);
        else {
            serverNetwork.updateServerWorlds(0.016);
            serverNetwork.tick();
        }
        for (TestClient client : List.copyOf(clients)) {
            client.network.tick();
            ClientEnvironmentSync.advance(client.world, 0.016);
            ClientPrediction.update(client.world, 0.016);
        }
        Thread.sleep(4);
    }

    void runTicks(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) tick();
    }

    void await(Check condition, long timeoutMs, String failure) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.ok() && System.currentTimeMillis() < deadline) tick();
        require(condition.ok(), failure);
    }

    void awaitJoined(TestClient client) throws Exception {
        await(() -> client.network.clientConnected()
                        && realPlayerId(client.playerId())
                        && serverWorld.hasLiveAssets(client.playerId())
                        && client.world.hasLiveAssets(client.playerId()),
                15_000, client.config.playerName + " did not join through the full TCP path");
    }

    void awaitConverged(TestClient client, int unitId, double tolerance, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Unit authoritative;
        Unit replicated;
        double separation;
        do {
            authoritative = unit(serverWorld, client.playerId(), unitId);
            replicated = unit(client.world, client.playerId(), unitId);
            separation = authoritative == null || replicated == null
                    ? Double.POSITIVE_INFINITY
                    : distance(authoritative.x, authoritative.y, replicated.x, replicated.y);
            if (separation <= tolerance) return;
            tick();
        } while (System.currentTimeMillis() < deadline);

        authoritative = unit(serverWorld, client.playerId(), unitId);
        replicated = unit(client.world, client.playerId(), unitId);
        separation = authoritative == null || replicated == null
                ? Double.POSITIVE_INFINITY
                : distance(authoritative.x, authoritative.y, replicated.x, replicated.y);
        throw new IllegalStateException(client.config.playerName
                + " did not converge to authoritative state"
                + " | serverSystem=" + serverWorld.activeSystemId()
                + " | clientSystem=" + client.world.activeSystemId()
                + " | distance=" + separation
                + " | authoritative=" + describe(authoritative)
                + " | replicated=" + describe(replicated));
    }

    Unit firstUnit(World world, String playerId) { return findAcrossSystems(world, playerId, -1); }
    Unit unit(World world, String playerId, int unitId) { return findAcrossSystems(world, playerId, unitId); }

    void setAuthoritativePosition(String playerId, int unitId, double x, double y) {
        String old = serverWorld.activeSystemId();
        try {
            for (String systemId : systemIds(serverWorld, playerId)) {
                if (!validSystem(systemId)) continue;
                serverWorld.activateSystem(systemId);
                Unit unit = serverWorld.units.get(Unit.key(playerId, unitId));
                if (unit == null) continue;
                unit.x = Calc.clamp(x, 0, serverWorld.width);
                unit.y = Calc.clamp(y, 0, serverWorld.height);
                unit.targetX = unit.x;
                unit.targetY = unit.y;
                unit.task = UnitTask.IDLE;
                serverWorld.saveActiveSystem();
                return;
            }
            throw new IllegalStateException("authoritative unit was not found for " + playerId + ':' + unitId);
        } finally {
            serverWorld.activateSystem(old);
        }
    }

    String reachableFromSystem(String systemId) {
        String old = serverWorld.activeSystemId();
        try {
            serverWorld.activateSystem(systemId);
            return serverWorld.wormholes.isEmpty() ? "" : serverWorld.wormholes.get(0).toSystemId;
        } finally {
            serverWorld.activateSystem(old);
        }
    }

    @Override public void close() {
        for (TestClient client : List.copyOf(clients)) {
            try { client.network.shutdown(); } catch (Exception ignored) { }
            if (client.proxy != null) try { client.proxy.close(); } catch (Exception ignored) { }
        }
        clients.clear();
        try {
            if (headlessServer != null) headlessServer.stop();
            else serverNetwork.shutdown();
        } catch (Exception ignored) { }
        System.clearProperty("starchem.sessionStore");
        try { Files.deleteIfExists(sessionStore); } catch (Exception ignored) { }
    }

    private Unit findAcrossSystems(World world, String playerId, int unitId) {
        String old = world.activeSystemId();
        try {
            for (String systemId : systemIds(world, playerId)) {
                if (!validSystem(systemId)) continue;
                world.activateSystem(systemId);
                if (unitId >= 0) {
                    Unit exact = world.units.get(Unit.key(playerId, unitId));
                    if (exact != null) return exact;
                } else {
                    for (Unit candidate : world.units.values()) if (playerId.equals(candidate.playerId)) return candidate;
                }
            }
            return null;
        } finally {
            world.activateSystem(old);
        }
    }

    private LinkedHashSet<String> systemIds(World world, String playerId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(world.activeSystemId());
        ids.add(world.playerHomeSystemId(playerId));
        GalaxyMapSnapshot galaxy = world.galaxyMapSnapshot();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) if (system != null) ids.add(system.id());
        }
        return ids;
    }

    private static String describe(Unit unit) {
        if (unit == null) return "missing";
        return unit.key()
                + " pos=(" + unit.x + ',' + unit.y + ')'
                + " target=(" + unit.targetX + ',' + unit.targetY + ')'
                + " task=" + unit.task
                + " order=" + unit.orderType;
    }

    private static boolean validSystem(String value) {
        return value != null && !value.isBlank() && !value.contains("WAIT");
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    static boolean realPlayerId(String id) {
        return id != null && !id.isBlank() && !"SOLO".equals(id) && !"WAIT".equals(id);
    }

    static double distance(double ax, double ay, double bx, double by) { return Math.hypot(ax - bx, ay - by); }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    record TestClient(Config config, World world, PeerNetwork network, TcpFaultProxy proxy) {
        String playerId() { return network.localPlayerId(); }
    }

    @FunctionalInterface interface Check { boolean ok() throws Exception; }
}
