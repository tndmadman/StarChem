package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GalaxyEventConfigValidator {
    private GalaxyEventConfigValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        System.out.println("StarChem galaxy event config validation passed.");
    }

    static void validateOrThrow() throws Exception {
        load(validConfig(singleEvent("good", "RICH_RESOURCE", "\"resourceMaterial\":\"RARE_EARTHS\"")));
        reject("duplicate ids", validConfig(singleEvent("dup", "RICH_RESOURCE", "\"resourceMaterial\":\"RARE_EARTHS\"")
                + "," + singleEvent("dup", "DERELICT_SALVAGE", "\"salvageMaterial\":\"SCRAP_METAL\"")));
        reject("invalid kind", validConfig(singleEvent("bad-kind", "NOPE", "")));
        reject("unknown role", validConfig(singleEvent("bad-role", "RICH_RESOURCE",
                "\"resourceMaterial\":\"RARE_EARTHS\",\"eligibleRoles\":[\"definitely-not-a-role\"]")));
        reject("bad resource", validConfig(singleEvent("bad-resource", "RICH_RESOURCE",
                "\"resourceMaterial\":\"NOT_A_MATERIAL\"")));
        reject("bad NPC faction", validConfig(singleEvent("bad-npc", "PIRATE_AMBUSH",
                "\"npcFactionId\":\"NOT_A_FACTION\"")));
        reject("bad discovery rule", validConfig(singleEvent("bad-discovery", "RICH_RESOURCE",
                "\"resourceMaterial\":\"RARE_EARTHS\",\"discoveryRule\":\"MAGIC\"")));
        reject("inverted duration", validConfig(singleEvent("bad-duration", "RICH_RESOURCE",
                "\"resourceMaterial\":\"RARE_EARTHS\",\"minDurationSeconds\":90,\"maxDurationSeconds\":30")));
        reject("bad active limit", validConfig(singleEvent("bad-limit", "RICH_RESOURCE",
                "\"resourceMaterial\":\"RARE_EARTHS\",\"maxActiveInstances\":0")));
        reject("bad reward material", validConfig(singleEvent("bad-reward", "RICH_RESOURCE",
                "\"resourceMaterial\":\"RARE_EARTHS\",\"rewardMaterial\":\"NOPE\",\"rewardAmount\":10")));
        reject("bad modifier", validConfig(singleEvent("bad-modifier", "ENVIRONMENTAL",
                "\"modifiers\":{\"sensorRange\":0}")));
        reject("bad director chance", configWithDirector("\"spawnChance\":1.5", singleEvent("good", "RICH_RESOURCE",
                "\"resourceMaterial\":\"RARE_EARTHS\"")));
        reject("bad director system limit", configWithDirector("\"maxActiveGalaxy\":2,\"maxActivePerSystem\":3",
                singleEvent("good", "RICH_RESOURCE", "\"resourceMaterial\":\"RARE_EARTHS\"")));
    }

    private static String validConfig(String events) {
        return configWithDirector("", events);
    }

    private static String configWithDirector(String override, String events) {
        String director = "\"enabled\":true,\"initialDelaySeconds\":30,\"evaluationSeconds\":45,"
                + "\"spawnChance\":0.35,\"maxActiveGalaxy\":4,\"maxActivePerSystem\":2,"
                + "\"wormholeClosingSeconds\":3";
        if (override != null && !override.isBlank()) director = override;
        return "{\"version\":2,\"director\":{" + director + "},\"events\":[" + events + "]}";
    }

    private static String singleEvent(String id, String kind, String extra) {
        String base = "\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"kind\":\"" + kind + "\","
                + "\"enabled\":true,\"weight\":1,\"safeForHome\":true,\"eligibleRoles\":[],"
                + "\"minimumAgeSeconds\":0,\"cooldownSeconds\":10,\"discoveryRule\":\"SENSOR\","
                + "\"minDurationSeconds\":30,\"maxDurationSeconds\":60,\"discoveryRadius\":500,"
                + "\"minDistanceFromPlayerAssets\":0,\"minDistanceFromWormholes\":0,\"placementAttempts\":4,"
                + "\"entityCount\":1,\"amount\":10,\"maxActiveInstances\":1,\"rewardAmount\":0,"
                + "\"rewardLifetimeSeconds\":30";
        if (extra != null && !extra.isBlank()) base += "," + extra;
        return "{" + base + "}";
    }

    private static void reject(String name, String json) throws Exception {
        boolean rejected = false;
        try {
            load(json);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "malformed event config was accepted: " + name);
    }

    private static GalaxyEventCatalog load(String json) throws IOException {
        Path path = Files.createTempFile("starchem-events-", ".json");
        try {
            Files.writeString(path, json);
            return GalaxyEventCatalog.loadForValidation(path);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
