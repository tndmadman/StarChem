package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** End-to-end validation for trusted server developer mutations. */
public final class ServerDevCommandValidator {
    private ServerDevCommandValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem server developer command validation passed.");
    }

    static void validate() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            TcpIntegrationHarness.TestClient client = harness.addClient("DevCommandTarget");
            harness.awaitJoined(client);
            String playerId = client.playerId();
            TcpIntegrationHarness.require(TcpIntegrationHarness.realPlayerId(playerId), "developer validator did not join a real player");

            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            ServerConsole console = ServerConsole.detached(64, new PrintStream(errors, true, StandardCharsets.UTF_8));
            harness.headlessServer.attachConsole(console);

            BaseRef base = firstBase(harness.serverWorld, playerId);
            TcpIntegrationHarness.require(base != null, "joined player did not receive a base");
            int shipsBefore = countShips(harness.serverWorld, playerId);
            String previousSystem = harness.serverWorld.activeSystemId();
            harness.serverWorld.activateSystem(base.systemId);
            ShipType queuedShip = Rules.ship(Rules.STARTING_SHIP);
            ProductionJob queued = ProductionSystem.enqueueWaiting(harness.serverWorld, base.base,
                    ProductionJobKind.SHIP, queuedShip.id, queuedShip.name, 10);
            harness.serverWorld.saveActiveSystem();
            harness.serverWorld.activateSystem(previousSystem);
            TcpIntegrationHarness.require(queued != null, "could not create a waiting production job for validation");

            submit(console, "dev mode on");
            submit(console, "help dev role");
            submit(console, "dev help access");
            submit(console, "dev role list");
            submit(console, "dev role show " + playerId);
            submit(console, "dev role set " + playerId + " developer-freebuild");
            submit(console, "dev resource set " + playerId + " " + base.base.id + " FUEL 500");
            submit(console, "dev resource fill " + playerId + " " + base.base.id + " 1000");
            submit(console, "dev production fund " + base.base.id + " " + queued.id);
            submit(console, "dev production finish " + base.base.id + " " + queued.id);
            submit(console, "dev research grant " + playerId + " advanced_industry");
            submit(console, "dev research grant " + playerId + " combat_doctrine");
            submit(console, "dev spawn ship " + playerId + " frigate 2 " + base.systemId);
            submit(console, "dev player heal-all " + playerId);
            submit(console, "tell " + playerId + " Developer command validation");
            harness.runTicks(80);
            requireConnected(harness, client, playerId, "initial developer command batch");

            base = firstBase(harness.serverWorld, playerId);
            TcpIntegrationHarness.require(base != null, "player base disappeared during developer commands");
            TcpIntegrationHarness.require(Math.abs(base.base.inventory.getOrDefault(Material.FUEL, 0.0) - 1000.0) < 0.001,
                    "resource set command did not update authoritative inventory");
            TcpIntegrationHarness.require(!base.base.productionQueue.contains(queued),
                    "production fund/finish commands did not complete the queued job");
            TcpIntegrationHarness.require(harness.serverWorld.hasResearch(playerId, "advanced_industry"),
                    "research grant did not complete prerequisite");
            TcpIntegrationHarness.require(harness.serverWorld.hasResearch(playerId, "combat_doctrine"),
                    "research grant did not complete requested topic");
            TcpIntegrationHarness.require(harness.serverNetwork.runtimeDevAccessGranted(playerId),
                    "developer role did not grant runtime access");
            TcpIntegrationHarness.require(harness.serverNetwork.runtimeFreeBuildEnabled(playerId),
                    "developer-freebuild role did not enable free-build");
            TcpIntegrationHarness.require(countShips(harness.serverWorld, playerId) >= shipsBefore + 2,
                    "ship spawn command did not add the requested ships");

            submit(console, "dev access revoke " + playerId);
            harness.runTicks(20);
            requireConnected(harness, client, playerId, "legacy developer access revoke");
            TcpIntegrationHarness.require(!harness.serverNetwork.runtimeDevAccessGranted(playerId)
                            && !harness.serverNetwork.runtimeFreeBuildEnabled(playerId),
                    "legacy access revoke did not clear access and free-build");

            submit(console, "dev access grant " + playerId);
            harness.runTicks(20);
            requireConnected(harness, client, playerId, "legacy developer access grant");
            TcpIntegrationHarness.require(harness.serverNetwork.runtimeDevAccessGranted(playerId)
                            && !harness.serverNetwork.runtimeFreeBuildEnabled(playerId),
                    "legacy access grant did not preserve separate free-build state");

            submit(console, "dev role set " + playerId + " developer-freebuild");
            harness.runTicks(20);
            requireConnected(harness, client, playerId, "developer-freebuild regrant");
            client.network().devSetFreeCrafting(playerId, false);
            harness.runTicks(20);
            TcpIntegrationHarness.require(!harness.serverWorld.devFreeBuildFor(playerId),
                    "runtime developer packet did not disable free-build");
            client.network().devSetFreeCrafting(playerId, true);
            harness.runTicks(20);

            submit(console, "dev research revoke " + playerId + " advanced_industry cascade");
            submit(console, "dev role set " + playerId + " none");
            harness.runTicks(20);
            TcpIntegrationHarness.require(!harness.serverNetwork.runtimeDevAccessGranted(playerId)
                            && !harness.serverNetwork.runtimeFreeBuildEnabled(playerId),
                    "none role did not revoke access and free-build");
            submit(console, "dev mode off");
            harness.runTicks(40);
            TcpIntegrationHarness.require(!harness.serverWorld.hasResearch(playerId, "advanced_industry")
                            && !harness.serverWorld.hasResearch(playerId, "combat_doctrine"),
                    "cascade research revoke did not remove dependent topics");
            TcpIntegrationHarness.require(!harness.serverNetwork.runtimeDevEnabled(),
                    "runtime developer mode did not disable");

            List<String> roleHelp = ServerDevRoleCommands.help(List.of("role"));
            List<String> accessHelp = ServerDevRoleCommands.help(List.of("access"));
            TcpIntegrationHarness.require(roleHelp.stream().anyMatch(line -> line.contains("dev role set <player> none"))
                            && accessHelp.stream().anyMatch(line -> line.contains("Prefer 'dev role set'")),
                    "developer help did not explain role and access commands");
            String errorText = errors.toString(StandardCharsets.UTF_8);
            TcpIntegrationHarness.require(errorText.isBlank(), "developer command validator reported console errors: " + errorText);
            console.close();
        }
    }

    private static void requireConnected(TcpIntegrationHarness harness, TcpIntegrationHarness.TestClient client,
                                         String playerId, String phase) {
        boolean serverConnected = harness.serverNetwork.serverSessionConnected(playerId);
        boolean clientConnected = client.network().clientConnected();
        if (serverConnected && clientConnected) return;
        PerfSnapshot serverPerf = harness.serverNetwork.perfSnapshot();
        PerfSnapshot clientPerf = client.network().perfSnapshot();
        throw new IllegalStateException("developer session disconnected during " + phase
                + " | serverConnected=" + serverConnected
                + " clientConnected=" + clientConnected
                + " serverSlowClose=" + serverPerf.slowConnectionClosesPerSecond()
                + " serverInboundOverflow=" + serverPerf.inboundOverflowsPerSecond()
                + " serverMalformed=" + serverPerf.malformedPacketsPerSecond()
                + " clientMalformed=" + clientPerf.malformedPacketsPerSecond()
                + " clientSnapshotReject=" + clientPerf.snapshotDecodeFailuresPerSecond()
                + " serverQueued=" + serverPerf.queuedFrames()
                + " clientQueued=" + clientPerf.queuedFrames());
    }

    private static void submit(ServerConsole console, String command) {
        TcpIntegrationHarness.require(console.submit(command), "could not queue developer command: " + command);
    }

    private static BaseRef firstBase(World world, String playerId) {
        String previous = world.activeSystemId();
        try {
            GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
            if (snapshot != null) for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                world.activateSystem(system.id());
                for (Base base : world.bases.values()) if (playerId.equals(base.playerId)) return new BaseRef(system.id(), base);
            }
            return null;
        } finally { if (previous != null && !previous.isBlank()) world.activateSystem(previous); }
    }

    private static int countShips(World world, String playerId) {
        int count = 0;
        String previous = world.activeSystemId();
        try {
            GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
            if (snapshot != null) for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                world.activateSystem(system.id());
                for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) count++;
            }
            return count;
        } finally { if (previous != null && !previous.isBlank()) world.activateSystem(previous); }
    }

    private record BaseRef(String systemId, Base base) { }
}
