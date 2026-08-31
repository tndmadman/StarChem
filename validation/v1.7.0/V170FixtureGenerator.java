package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates upgrade state using the exact published v1.7.0 implementation. */
public final class V170FixtureGenerator {
    private static final String COMMIT = "71bf62d1eb6a35e747ad9b494fded32b6e5e57fb";
    private static final int SAVE_FORMAT = 2;
    private static final String SAVE = "upgrade-v170";
    private static final int LEGACY_PORT = 50077;
    private static final Player ALPHA = new Player("P1", "V170 Alpha", "alpha-v170-password",
            "alpha-v170-current-" + "A".repeat(32), "alpha-v170-previous-" + "B".repeat(30), 0x5DADE2);
    private static final Player BETA = new Player("P2", "V170 Beta", "beta-v170-password",
            "beta-v170-current-" + "C".repeat(33), "beta-v170-previous-" + "D".repeat(31), 0xF5B041);

    private V170FixtureGenerator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected fixture output directory.");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path serverDir = root.resolve("server");
        Path clientDir = root.resolve("client");
        Files.createDirectories(serverDir);
        Files.createDirectories(clientDir);
        System.setProperty("starchem.sessionStore", clientDir.resolve("sessions.properties").toString());

        require(ServerSaveStore.SAVE_FORMAT_VERSION == SAVE_FORMAT,
                "published v1.7.0 source does not use expected save format " + SAVE_FORMAT);
        require(MultiplayerCompatibility.PROTOCOL_VERSION == 8,
                "published v1.7.0 source does not use expected protocol 8");

        Config config = Config.dedicatedServer("V170 Upgrade Fixture", LEGACY_PORT, false, false, Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, "", 1, serverDir, SAVE, 0, 6, true);
        World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
        addPlayer(world, ALPHA, 0);
        addPlayer(world, BETA, 1);
        world.completeResearch(ALPHA.id, "advanced_industry");
        world.completeResearch(BETA.id, "advanced_industry");

        world.activateSystem(world.playerHomeSystemId(ALPHA.id));
        Base alphaBase = firstBase(world, ALPHA.id);
        alphaBase.inventory.put(Material.IRON, 432.0);
        alphaBase.inventory.put(Material.COPPER, 210.0);
        alphaBase.productionQueue.add(new ProductionJob("P9001", ProductionJobKind.SHIP,
                Rules.STARTING_SHIP, 25, 17, false, ""));
        firstUnit(world, ALPHA.id).inventory.put(Material.IRON, 19.0);
        world.saveActiveSystem();

        String remote = world.galaxyMapSnapshot().systems().stream()
                .filter(system -> system != null && system.staticSystem())
                .map(GalaxyMapSystem::id).findFirst().orElse(world.activeSystemId());
        world.activateSystem(world.playerHomeSystemId(BETA.id));
        Base betaBase = firstBase(world, BETA.id);
        betaBase.inventory.put(Material.SILICATES, 321.0);
        betaBase.inventory.put(Material.COPPER, 144.0);
        firstUnit(world, BETA.id).inventory.put(Material.COPPER, 13.0);
        world.saveActiveSystem();
        world.movePlayerAssetsToSystem(BETA.id, remote);
        world.updateCurrentSystem(0.25);
        world.saveActiveSystem();

        List<PersistentPlayerSession> sessions = List.of(session(ALPHA), session(BETA));
        ServerSaveStore saves = new ServerSaveStore(serverDir, SAVE, 6);
        saves.save(world, config, "fixture-first", sessions);
        alphaBase.inventory.put(Material.IRON, 7.0);
        world.saveActiveSystem();
        saves.save(world, config, "fixture-current", sessions);
        Files.copy(serverDir.resolve(SAVE + "-current.starchem-save"),
                serverDir.resolve(SAVE + "-20260726-182049.starchem-save"), StandardCopyOption.REPLACE_EXISTING);

        TlsIdentity.serverSocketFactory(config);
        String fingerprint = TlsIdentity.serverFingerprint(config);

        ServerAdminStore admin = new ServerAdminStore(serverDir, SAVE);
        admin.save(new ServerAccessPolicy(false, "", 24, "v1.7 fixture MOTD"));
        new ServerModerationStore(serverDir, SAVE).save(ServerModerationState.open());
        ServerPlayerObservationStore observations = new ServerPlayerObservationStore(serverDir, SAVE);
        observations.record(ALPHA.id, ALPHA.name, java.net.InetAddress.getLoopbackAddress(), "fixture-device-v170-alpha");
        ServerEventJournal journal = new ServerEventJournal(serverDir, SAVE);
        journal.add("FIXTURE", ALPHA.id, "synthetic v1.7 activity");

        saveClient(ALPHA, fingerprint);
        saveClient(BETA, fingerprint);

        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("sourceVersion", "1.7.0");
        manifest.put("sourceCommit", COMMIT);
        manifest.put("sourceSaveFormat", SAVE_FORMAT);
        manifest.put("sourceProtocol", 8);
        manifest.put("sourceRulesVersion", 14);
        manifest.put("saveName", SAVE);
        manifest.put("serverName", config.playerName);
        manifest.put("legacyPort", LEGACY_PORT);
        manifest.put("tlsFingerprint", fingerprint);
        manifest.put("players", List.of(playerRow(ALPHA), playerRow(BETA)));
        manifest.put("systemCount", world.galaxyMapSnapshot().systems().size());
        manifest.put("npcRuntimeCount", ServerSaveStore.list(world.captureServerSaveRuntime().get("npcFactions")).size());
        manifest.put("files", fileRows(root));
        Files.writeString(root.resolve("fixture-manifest.json"), MiniJson.stringify(manifest) + "\n", StandardCharsets.UTF_8);
        System.out.println("Generated StarChem v1.7.0 upgrade fixture at " + root);
    }

    private static void addPlayer(World world, Player player, int slot) {
        PlayerRegistry.register(player.id, player.name, player.rgb, false);
        world.spawnPlayerGroup(player.id, slot);
    }

    private static PersistentPlayerSession session(Player player) {
        byte[] salt = PasswordAuth.newSalt();
        byte[] verifier = PasswordAuth.decodeVerifier(PasswordAuth.verifier(player.name, player.password));
        return new PersistentPlayerSession(player.id, player.name, player.rgb, salt,
                PasswordAuth.serverDigest(verifier, salt), PasswordAuth.tokenDigest(player.currentToken),
                PasswordAuth.tokenDigest(player.previousToken), System.currentTimeMillis() - 60_000);
    }

    private static void saveClient(Player player, String fingerprint) {
        Config client = Config.join(player.name, "127.0.0.1", LEGACY_PORT, false);
        SessionTokenStore.saveServerFingerprint(client, fingerprint);
        SessionTokenStore.saveAuthDigest(client, PasswordAuth.verifier(player.name, player.password));
        SessionTokenStore.save(client, player.id, player.currentToken);
    }

    private static Map<String,Object> playerRow(Player player) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", player.id);
        row.put("name", player.name);
        row.put("password", player.password);
        row.put("currentToken", player.currentToken);
        row.put("previousToken", player.previousToken);
        return row;
    }

    private static List<Object> fileRows(Path root) throws Exception {
        List<Object> rows = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("path", root.relativize(path).toString().replace('\\', '/'));
                row.put("bytes", Files.size(path));
                row.put("sha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                rows.add(row);
            }
        }
        return rows;
    }

    private static Base firstBase(World world, String playerId) {
        return world.bases.values().stream().filter(base -> playerId.equals(base.playerId)).findFirst().orElseThrow();
    }

    private static Unit firstUnit(World world, String playerId) {
        return world.units.values().stream().filter(unit -> playerId.equals(unit.playerId)).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Player(String id, String name, String password, String currentToken, String previousToken, int rgb) { }
}
