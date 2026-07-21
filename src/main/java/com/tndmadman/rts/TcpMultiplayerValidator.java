package com.tndmadman.rts;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** End-to-end authoritative host/client validation over the real TCP transport. */
public final class TcpMultiplayerValidator {
    private TcpMultiplayerValidator() { }

    public static void main(String[] args) throws Exception {
        Path store = Files.createTempFile("starchem-tcp-session-", ".properties");
        Files.deleteIfExists(store);
        System.setProperty("starchem.sessionStore", store.toString());
        PeerNetwork server = null;
        PeerNetwork client = null;
        try {
            int port = freePort();
            Config hostConfig = Config.host("TCP Validation Host", port, false);
            World serverWorld = new World(hostConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            server = PeerNetwork.start(hostConfig, serverWorld);

            Config clientConfig = Config.join("TCP Validation Client", "127.0.0.1", port, false);
            PendingPlayerPassword.remember(clientConfig, "validator-password".toCharArray(), false);
            World clientWorld = new World(clientConfig.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            client = PeerNetwork.start(clientConfig, clientWorld);
            require(!client.clientReady(), "TCP client reported ready before authoritative initial state was applied");

            PeerNetwork finalServer = server;
            PeerNetwork finalClient = client;
            runUntil(finalServer, finalClient, clientWorld,
                    () -> finalClient.clientReady()
                            && !"SOLO".equals(finalClient.localPlayerId())
                            && serverWorld.hasLiveAssets(finalClient.localPlayerId())
                            && clientWorld.hasLiveAssets(finalClient.localPlayerId()),
                    12_000, "TCP client did not join and receive authoritative initial state");

            String playerId = client.localPlayerId();
            Unit serverUnit = firstUnit(serverWorld, playerId);
            Unit clientUnit = firstUnit(clientWorld, playerId);
            require(serverUnit != null && clientUnit != null, "joined player unit was missing");

            double targetX = serverUnit.x + 30;
            double targetY = serverUnit.y + 20;
            double wireTargetX = Double.parseDouble(Calc.round(targetX));
            double wireTargetY = Double.parseDouble(Calc.round(targetY));
            client.move(new MoveCommand(playerId, serverUnit.unitId, targetX, targetY));
            long commandDeadline = System.currentTimeMillis() + 3_000;
            boolean commandApplied = false;
            while (System.currentTimeMillis() < commandDeadline) {
                networkTick(server, client);
                Unit authoritative = unit(serverWorld, playerId, serverUnit.unitId);
                if (authoritative != null && Math.abs(authoritative.targetX - wireTargetX) < 0.001
                        && Math.abs(authoritative.targetY - wireTargetY) < 0.001) {
                    commandApplied = true;
                    break;
                }
            }
            require(commandApplied, "TCP move command did not reach the authoritative server");

            // Drive a long stream of authoritative state changes without running AI or automation.
            // This isolates TCP framing, ordering, coalescing, and client snapshot convergence.
            for (int i = 0; i < 400; i++) {
                double x = 2200 + i * 2.0;
                double y = 3100 + Math.sin(i / 12.0) * 180;
                setAuthoritativePosition(serverWorld, playerId, serverUnit.unitId, x, y);
                networkTick(server, client);
                ClientEnvironmentSync.advance(clientWorld, 0.016);
                ClientPrediction.update(clientWorld, 0.016);
            }

            settleUntilConverged(server, client, serverWorld, clientWorld, playerId, serverUnit.unitId,
                    5_000, "client did not converge to the final authoritative TCP snapshot");

            Unit authoritative = unit(serverWorld, playerId, serverUnit.unitId);
            Unit replicated = unit(clientWorld, playerId, serverUnit.unitId);
            require(authoritative != null && replicated != null, "replicated unit disappeared");
            require(distance(authoritative.x, authoritative.y, replicated.x, replicated.y) < 0.75,
                    "client drifted after sustained TCP snapshot traffic");

            System.out.println("StarChem TCP multiplayer integration validation passed.");
        } finally {
            if (client != null) client.shutdown();
            if (server != null) server.shutdown();
            System.clearProperty("starchem.sessionStore");
            Files.deleteIfExists(store);
        }
    }

    private static void runUntil(PeerNetwork server, PeerNetwork client, World clientWorld, Check condition,
                                 long timeoutMs, String failure) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.ok() && System.currentTimeMillis() < deadline) {
            tick(server, client, clientWorld);
            if (client.clientReady() && !clientWorld.hasLiveAssets(client.localPlayerId())) {
                throw new IllegalStateException("TCP client became ready before its authoritative assets were present");
            }
        }
        require(condition.ok(), failure);
    }

    private static void tick(PeerNetwork server, PeerNetwork client, World clientWorld) throws InterruptedException {
        networkTick(server, client);
        ClientEnvironmentSync.advance(clientWorld, 0.016);
        ClientPrediction.update(clientWorld, 0.016);
    }

    private static void settleUntilConverged(PeerNetwork server, PeerNetwork client, World serverWorld,
                                              World clientWorld, String playerId, int unitId,
                                              long timeoutMs, String failure) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        double lastDistance = Double.POSITIVE_INFINITY;
        while (System.currentTimeMillis() < deadline) {
            settleTick(server, client, clientWorld);
            Unit authoritative = unit(serverWorld, playerId, unitId);
            Unit replicated = unit(clientWorld, playerId, unitId);
            if (authoritative != null && replicated != null) {
                lastDistance = distance(authoritative.x, authoritative.y, replicated.x, replicated.y);
                if (lastDistance < 0.75) return;
            }
        }
        throw new IllegalStateException(failure + " (remaining position error " + lastDistance + ")");
    }

    private static void settleTick(PeerNetwork server, PeerNetwork client, World clientWorld) throws InterruptedException {
        networkTick(server, client);
        ClientEnvironmentSync.advance(clientWorld, 0.016);
        ClientPrediction.update(clientWorld, 0.016);
    }

    private static void networkTick(PeerNetwork server, PeerNetwork client) throws InterruptedException {
        server.tick();
        client.tick();
        Thread.sleep(8);
    }

    private static void setAuthoritativePosition(World world, String playerId, int unitId, double x, double y) {
        String old = world.activeSystemId();
        java.util.LinkedHashSet<String> systemIds = systemIds(world, playerId);
        try {
            for (String systemId : systemIds) {
                if (systemId == null || systemId.isBlank() || systemId.contains("WAIT")) continue;
                world.activateSystem(systemId);
                Unit unit = world.units.get(Unit.key(playerId, unitId));
                if (unit == null) continue;
                unit.x = Calc.clamp(x, 0, world.width);
                unit.y = Calc.clamp(y, 0, world.height);
                unit.targetX = unit.x;
                unit.targetY = unit.y;
                unit.task = UnitTask.IDLE;
                world.saveActiveSystem();
                return;
            }
            throw new IllegalStateException("authoritative unit was not found while generating TCP snapshot traffic");
        } finally {
            world.activateSystem(old);
        }
    }

    private static java.util.LinkedHashSet<String> systemIds(World world, String playerId) {
        java.util.LinkedHashSet<String> systemIds = new java.util.LinkedHashSet<>();
        systemIds.add(world.activeSystemId());
        systemIds.add(world.playerHomeSystemId(playerId));
        GalaxyMapSnapshot galaxy = world.galaxyMapSnapshot();
        if (galaxy != null && galaxy.systems() != null) {
            for (GalaxyMapSystem system : galaxy.systems()) {
                if (system != null && system.id() != null && !system.id().isBlank()) systemIds.add(system.id());
            }
        }
        return systemIds;
    }

    private static Unit firstUnit(World world, String playerId) {
        return findAcrossSystems(world, playerId, -1);
    }

    private static Unit unit(World world, String playerId, int unitId) {
        return findAcrossSystems(world, playerId, unitId);
    }

    private static Unit findAcrossSystems(World world, String playerId, int unitId) {
        String old = world.activeSystemId();
        java.util.LinkedHashSet<String> systemIds = systemIds(world, playerId);
        try {
            for (String systemId : systemIds) {
                if (systemId == null || systemId.isBlank() || systemId.contains("WAIT")) continue;
                world.activateSystem(systemId);
                if (unitId >= 0) {
                    Unit exact = world.units.get(Unit.key(playerId, unitId));
                    if (exact != null) return exact;
                } else {
                    for (Unit candidate : world.units.values()) {
                        if (playerId.equals(candidate.playerId)) return candidate;
                    }
                }
            }
            return null;
        } finally {
            world.activateSystem(old);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static double distance(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Check { boolean ok() throws Exception; }
}
