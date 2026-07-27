package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class SkirmishPresetValidator {
    private SkirmishPresetValidator() { }

    static void validateOrThrow() {
        World previous = PlayerRegistry.activeWorld();
        try {
            validatePresetResolution();
            validateWorldIsolation();
            validateSerialization();
            validateSavePersistence();
        } finally {
            PlayerRegistry.activate(previous);
        }
    }

    private static void validatePresetResolution() {
        NpcFaction base = baseFaction(Config.RAIDERS_ID);
        SkirmishSettings standard = SkirmishSettings.create(SkirmishPreset.STANDARD, NpcDifficulty.NORMAL);
        SkirmishSettings hostile = SkirmishSettings.create(SkirmishPreset.HOSTILE, NpcDifficulty.HARD);
        NpcFaction standardRaiders = standard.resolve(List.of(base)).get(0);
        NpcFaction hostileRaiders = hostile.resolve(List.of(base)).get(0);
        require(standardRaiders.firstSpawnSeconds() == base.firstSpawnSeconds(),
                "standard preset changed current Raider spawn timing");
        require(standardRaiders.buildSeconds() == base.buildSeconds(),
                "standard preset changed current Raider build timing");
        require(standardRaiders.startingUnits().equals(base.startingUnits()),
                "standard preset changed current Raider starting fleet");
        require(hostileRaiders.firstSpawnSeconds() < standardRaiders.firstSpawnSeconds(),
                "hostile preset did not accelerate Raider spawning");
        require(hostileRaiders.respawnSeconds() < standardRaiders.respawnSeconds(),
                "hostile preset did not accelerate Raider respawning");
        require(hostileRaiders.startingUnits().size() > standardRaiders.startingUnits().size(),
                "hostile preset did not increase the Raider starting fleet");

        SkirmishSettings peaceful = SkirmishSettings.create(SkirmishPreset.PEACEFUL, NpcDifficulty.RELAXED);
        NpcFaction peacefulRaiders = peaceful.resolve(List.of(base)).get(0);
        require(!peacefulRaiders.enabled(), "peaceful preset left Raiders enabled");
    }

    private static void validateWorldIsolation() {
        PlayerRegistry.reset("SOLO", "Skirmish Standard", 0x50BEFF);
        World standardWorld = new World("Skirmish Standard", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        SkirmishRuntime.bind(standardWorld,
                SkirmishSettings.create(SkirmishPreset.STANDARD, NpcDifficulty.NORMAL));
        PlayerRegistry.activate(standardWorld);
        double standardSpawn = activeFaction(Config.RAIDERS_ID).firstSpawnSeconds();

        World hostileWorld = new World("Skirmish Hostile", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        SkirmishRuntime.bind(hostileWorld,
                SkirmishSettings.create(SkirmishPreset.HOSTILE, NpcDifficulty.BRUTAL));
        PlayerRegistry.activate(hostileWorld);
        double hostileSpawn = activeFaction(Config.RAIDERS_ID).firstSpawnSeconds();
        require(hostileSpawn < standardSpawn, "hostile world did not receive hostile NPC rules");

        PlayerRegistry.activate(standardWorld);
        require(activeFaction(Config.RAIDERS_ID).firstSpawnSeconds() == standardSpawn,
                "activating another world contaminated standard NPC rules");
    }

    private static void validateSerialization() {
        SkirmishSettings source = new SkirmishSettings(SkirmishPreset.HOSTILE, NpcDifficulty.HARD,
                Set.of(Config.FREE_MINERS_ID));
        require(source.equals(SkirmishSettings.fromPacket(source.packet())),
                "world-info packet did not preserve skirmish settings");
        require(source.equals(SkirmishSettings.fromSaved(source.saveMap(), SkirmishSettings.standard())),
                "save metadata did not preserve skirmish settings");
        require(SkirmishSettings.fromSaved(null, source).equals(source),
                "legacy save fallback did not preserve configured new-world settings");
    }

    private static void validateSavePersistence() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("starchem-skirmish-save-");
            Config hostile = Config.parse(new String[]{"--server", "50000", "--save-dir", dir.toString(),
                    "--save-name", "skirmish", "--skirmish-preset", "hostile", "--npc-difficulty", "hard"});
            World world = new World(hostile.playerName, hostile.disabledNpcFactionIds, hostile.systemId, false);
            SkirmishRuntime.bind(world, hostile.skirmishSettings);
            new ServerSaveStore(dir, "skirmish", 2).save(world, hostile, "skirmish-validator", List.of());

            Config changedLaunch = Config.parse(new String[]{"--server", "50000", "--save-dir", dir.toString(),
                    "--save-name", "skirmish", "--skirmish-preset", "peaceful", "--npc-difficulty", "relaxed"});
            World loaded = new ServerSaveStore(dir, "skirmish", 2).load(changedLaunch).orElseThrow();
            SkirmishSettings restored = SkirmishRuntime.settings(loaded);
            require(restored.preset() == SkirmishPreset.HOSTILE && restored.difficulty() == NpcDifficulty.HARD,
                    "loaded save used launch arguments instead of saved skirmish settings");
        } catch (Exception ex) {
            throw new IllegalStateException("skirmish save persistence validation failed", ex);
        } finally {
            deleteTree(dir);
        }
    }

    private static NpcFaction baseFaction(String id) {
        return NpcRules.baseFactions().stream().filter(faction -> id.equals(faction.id())).findFirst().orElseThrow();
    }

    private static NpcFaction activeFaction(String id) {
        return NpcRules.factions().stream().filter(faction -> id.equals(faction.id())).findFirst().orElseThrow();
    }

    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
