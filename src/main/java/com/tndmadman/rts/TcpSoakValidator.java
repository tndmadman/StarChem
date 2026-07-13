package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.*;

/** Deterministic multiplayer soak with concurrent clients, backpressure, and reconnect cycles. */
public final class TcpSoakValidator {
    private TcpSoakValidator() { }

    public static void main(String[] args) throws Exception {
        int seconds = integerProperty("starchem.soakSeconds", 8, 3, 86_400);
        long seed = longProperty("starchem.soakSeed", 0x5A17C0DEL);
        Random random = new Random(seed);
        System.out.println("StarChem TCP soak starting: seconds=" + seconds + " seed=" + seed);

        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host();
             TcpFaultProxy proxy = new TcpFaultProxy(InetAddress.getLoopbackAddress(), harness.serverConfig.port)) {
            List<TcpIntegrationHarness.TestClient> clients = new ArrayList<>();
            TcpIntegrationHarness.TestClient faulted = harness.addProxiedClient("Soak Faulted", proxy);
            clients.add(faulted);
            for (int i = 1; i <= 3; i++) clients.add(harness.addClient("Soak Healthy " + i));
            for (TcpIntegrationHarness.TestClient client : clients) harness.awaitJoined(client);

            Map<String, Integer> unitIds = new LinkedHashMap<>();
            for (TcpIntegrationHarness.TestClient client : clients) {
                Unit unit = harness.firstUnit(harness.serverWorld, client.playerId());
                TcpIntegrationHarness.require(unit != null, "soak client had no authoritative unit: " + client.playerId());
                unitIds.put(client.playerId(), unit.unitId);
            }

            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            int iteration = 0;
            boolean paused = false;
            long maxQueuedBytes = 0;
            while (System.nanoTime() < deadline) {
                if (iteration % 20 == 0) {
                    TcpIntegrationHarness.TestClient client = clients.get(random.nextInt(clients.size()));
                    if (client.network().clientConnected()) {
                        Unit unit = harness.unit(harness.serverWorld, client.playerId(), unitIds.get(client.playerId()));
                        if (unit != null) {
                            client.network().move(new MoveCommand(client.playerId(), unit.unitId,
                                    unit.x + random.nextDouble(-80, 80), unit.y + random.nextDouble(-80, 80)));
                        }
                    }
                }

                if (iteration > 0 && iteration % 180 == 0) {
                    proxy.resumeServerToClient();
                    paused = false;
                    proxy.dropActiveConnection();
                } else if (iteration % 120 == 30 && !paused) {
                    proxy.pauseServerToClient();
                    paused = true;
                } else if (iteration % 120 == 70 && paused) {
                    proxy.resumeServerToClient();
                    paused = false;
                }

                harness.tick();
                ConnectionId faultedId = harness.serverNetwork.connectionIdForPlayer(faulted.playerId());
                if (faultedId.valid()) {
                    ConnectionDiagnostics diagnostics = harness.serverNetwork.connectionDiagnostics(faultedId);
                    maxQueuedBytes = Math.max(maxQueuedBytes, diagnostics.queuedBytes());
                    TcpIntegrationHarness.require(diagnostics.queuedFrames() <= 4,
                            "soak detected unbounded replaceable queue: " + diagnostics);
                }

                if (iteration % 100 == 0) {
                    Set<String> ids = new HashSet<>();
                    for (TcpIntegrationHarness.TestClient client : clients) {
                        TcpIntegrationHarness.require(ids.add(client.playerId()), "soak detected duplicate player identity");
                    }
                    for (int i = 1; i < clients.size(); i++) {
                        TcpIntegrationHarness.require(clients.get(i).network().clientConnected(),
                                "healthy client disconnected during soak");
                    }
                }
                iteration++;
            }

            proxy.resumeServerToClient();
            harness.await(() -> faulted.network().clientConnected()
                            && harness.serverNetwork.serverSessionConnected(faulted.playerId()),
                    15_000, "fault-injected client did not recover before soak completion");
            harness.await(() -> harness.serverNetwork.serverPeerCount() == clients.size(), 8_000,
                    "server peer count did not recover after soak faults");
            for (TcpIntegrationHarness.TestClient client : clients) {
                TcpIntegrationHarness.require(client.network().clientConnected(),
                        "client was not connected at soak completion: " + client.config().playerName);
            }
            System.out.println("StarChem TCP soak passed: iterations=" + iteration + " maxQueuedBytes=" + maxQueuedBytes
                    + " seed=" + seed);
        }
    }

    private static int integerProperty(String name, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(System.getProperty(name, Integer.toString(fallback))))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static long longProperty(String name, long fallback) {
        try { return Long.parseLong(System.getProperty(name, Long.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
