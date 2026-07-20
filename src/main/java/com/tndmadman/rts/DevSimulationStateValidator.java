package com.tndmadman.rts;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

public final class DevSimulationStateValidator {
    private DevSimulationStateValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        System.out.println("StarChem developer simulation state validation passed.");
    }

    static void validateOrThrow() throws Exception {
        NotificationRegistryValidator.validateOrThrow();
        validateWorldIsolationAndCentralReset();
        validateSoloShutdownPath();
        validateHostAndJoinShutdownPaths();
        validateGraphicalHostShutdownPath();
        validateDedicatedServerShutdownPath();
    }

    private static void validateWorldIsolationAndCentralReset() {
        PlayerRegistry.reset("WAIT", "Dev State Validator", 0x50BEFF);
        World first = new World("Dev State First", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        World second = new World("Dev State Second", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);

        mutateEverySetting(first);
        requireDefaults(second.aiDevSettings, "parallel world inherited developer settings");
        require(first.aiDevSettings.difficultyPreset() == NpcDifficultyPreset.FULL_WAR,
                "first world did not retain its own difficulty preset");

        first.aiDevSettings.resetToDefaults();
        requireDefaults(first.aiDevSettings, "central reset did not restore defaults");

        mutateEverySetting(first);
        first.aiDevSettings.resetOneShotRequests();
        require(first.aiDevSettings.pauseAi && first.aiDevSettings.fastAi,
                "one-shot reset incorrectly cleared persistent simulation controls");
        requireNoOneShotRequests(first.aiDevSettings, "one-shot reset left a pending request");

        first.aiDevSettings.resetToDefaults();
        World replacement = new World("Dev State Replacement", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        requireWorldDefaults(replacement, "replacement world did not start with defaults");
    }

    private static void validateSoloShutdownPath() throws IOException {
        Config config = Config.solo("Dev Solo", true, true, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "");
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false);
        PeerNetwork network = PeerNetwork.start(config, world);
        require(network == null, "solo lifecycle unexpectedly created a network");
        mutateEverySetting(world);
        WorldRuntimeCleanup.discard(world);
        requireWorldDefaults(world, "discarded solo world did not reset developer state");
    }

    private static void validateHostAndJoinShutdownPaths() throws Exception {
        int port = freePort();
        Config hostConfig = Config.host("Dev Host", port, true, false, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "");
        World hostWorld = new World(hostConfig.playerName, hostConfig.disabledNpcFactionIds, hostConfig.systemId, false);
        PeerNetwork hostNetwork = null;
        PeerNetwork joinNetwork = null;
        try {
            hostNetwork = PeerNetwork.start(hostConfig, hostWorld);
            require(hostNetwork != null, "host lifecycle did not create a network");

            Config joinConfig = Config.join("Dev Join", "127.0.0.1", port, true, true,
                    Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "");
            World joinWorld = new World(joinConfig.playerName, joinConfig.disabledNpcFactionIds, joinConfig.systemId, false);
            joinNetwork = PeerNetwork.start(joinConfig, joinWorld);
            require(joinNetwork != null, "join lifecycle did not create a network");

            mutateEverySetting(hostWorld);
            hostNetwork.setRuntimeDevEnabled(false);
            requireWorldDefaults(hostWorld, "runtime dev mode off did not restore host defaults");

            mutateEverySetting(hostWorld);
            mutateEverySetting(joinWorld);
            joinNetwork.shutdown();
            joinNetwork = null;
            hostNetwork.shutdown();
            hostNetwork = null;
            requireWorldDefaults(joinWorld, "JOIN shutdown did not reset developer state");
            requireWorldDefaults(hostWorld, "HOST shutdown did not reset developer state");
        } finally {
            if (joinNetwork != null) joinNetwork.shutdown();
            if (hostNetwork != null) hostNetwork.shutdown();
        }
    }

    private static void validateGraphicalHostShutdownPath() throws Exception {
        Config config = Config.host("Dev Graphical Host", freePort(), true, true,
                Set.of(), StarSystems.DEFAULT_SYSTEM_ID, "");
        LocalHostSession session = null;
        try {
            session = LocalHostSession.start(config);
            World clientWorld = session.clientWorld;
            World serverWorld = session.devAuthorityNetwork().devSettingsWorld(clientWorld);
            mutateEverySetting(clientWorld);
            mutateEverySetting(serverWorld);
            session.stop();
            session = null;
            requireWorldDefaults(clientWorld, "graphical host client shutdown did not reset developer state");
            requireWorldDefaults(serverWorld, "graphical host server shutdown did not reset developer state");
        } finally {
            if (session != null) session.stop();
        }
    }

    private static void validateDedicatedServerShutdownPath() throws Exception {
        Path saveDir = Files.createTempDirectory("starchem-dev-state-");
        HeadlessGameServer server = null;
        try {
            Config config = Config.dedicatedServer("Dev Dedicated", freePort(), true, true, Set.of(),
                    StarSystems.DEFAULT_SYSTEM_ID, "", 1, saveDir, "dev-state", 0, 1, true);
            server = HeadlessGameServer.start(config);
            mutateEverySetting(server.world);
            World stoppedWorld = server.world;
            server.stop();
            server = null;
            requireWorldDefaults(stoppedWorld, "dedicated-server shutdown did not reset developer state");
        } finally {
            if (server != null) server.stop();
            deleteTree(saveDir);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void mutateEverySetting(World world) {
        AiDevSettings settings = world.aiDevSettings;
        settings.pauseAi = true;
        settings.stepAi = true;
        settings.fastAi = true;
        settings.freezePlayerUnits = true;
        settings.freezeNpcCombat = true;
        settings.disableAttacks = true;
        settings.disableEconomy = true;
        settings.hotReloadRequested = true;
        settings.resetRequested = true;
        settings.forceSpawnRequested = true;
        settings.forceRaidRequested = true;
        settings.forceStationRequested = true;
        settings.forceResearchRequested = true;
        settings.forceCraftRequested = true;
        settings.setDifficultyPreset(NpcDifficultyPreset.FULL_WAR);
        DevTimerSettings.configure(world, true);
    }

    private static void requireWorldDefaults(World world, String message) {
        requireDefaults(world.aiDevSettings, message);
        require(!DevTimerSettings.disabled(world), message + ": production timer override");
    }

    private static void requireDefaults(AiDevSettings settings, String message) {
        require(!settings.pauseAi && !settings.stepAi && !settings.fastAi,
                message + ": AI timing controls");
        require(!settings.freezePlayerUnits && !settings.freezeNpcCombat,
                message + ": combat freezes");
        require(!settings.disableAttacks && !settings.disableEconomy,
                message + ": gameplay rules");
        require(settings.difficultyPreset() == NpcDifficultyPreset.NORMAL,
                message + ": difficulty preset");
        requireNoOneShotRequests(settings, message + ": one-shot requests");
    }

    private static void requireNoOneShotRequests(AiDevSettings settings, String message) {
        require(!settings.stepAi
                        && !settings.hotReloadRequested
                        && !settings.resetRequested
                        && !settings.forceSpawnRequested
                        && !settings.forceRaidRequested
                        && !settings.forceStationRequested
                        && !settings.forceResearchRequested
                        && !settings.forceCraftRequested,
                message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
