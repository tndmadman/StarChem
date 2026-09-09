package com.tndmadman.rts;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Headless checks for the renderer-owned visual metadata architecture from issue #390. */
public final class VisualContentValidator {
    private static final Path MANIFEST_PATH = Path.of("config/starchem.json");
    private static final Set<String> GAMEPLAY_ONLY_KEYS = Set.of(
            "maxHp", "maxShield", "shieldRegen", "shieldRegenDelay", "speed",
            "cargoCapacity", "harvestRange", "buildTimeSeconds", "buildCost",
            "canHarvest", "weaponHardpoints", "damage", "cooldown", "range",
            "miningYield", "resourceRespawn");

    private VisualContentValidator() { }

    public static void main(String[] args) throws Exception {
        validateManifestRegistration();
        validateVisualConfigSeparation();
        VisualCatalog catalog = VisualCatalog.loadStrictForValidation(VisualCatalog.CONFIG_PATH);
        require(catalog.shipCount() > 0, "Visual catalog has no ship definitions.");
        require(catalog.stationCount() > 0, "Visual catalog has no station definitions.");
        require(catalog.systemCount() > 0, "Visual catalog has no system definitions.");
        require(catalog.celestialCount() > 0, "Visual catalog has no celestial definitions.");

        for (String shipId : Rules.SHIPS.keySet()) require(catalog.containsShip(shipId), "Missing ship metadata: " + shipId);
        require(catalog.containsStation("outpost"), "Missing outpost visual metadata.");
        require(catalog.containsSystem(StarSystems.DEFAULT_SYSTEM_ID), "Missing default-system visual metadata.");

        CatalogShipVisualDefinition before = VisualCatalog.ship("prospector");
        ShipSpriteCache.resetForTest();
        Unit probe = new Unit("visual-validator", 390, "prospector", 0, 0);
        require(ShipSpriteCache.sprite(probe, Color.WHITE) != null, "Medium-LOD cache failed to render a probe.");
        require(ShipSpriteCache.snapshot().entries() > 0, "Probe sprite was not cached.");
        VisualCatalog.reload();
        require(ShipSpriteCache.snapshot().entries() == 0, "Visual catalog reload must invalidate ship sprites.");
        require(before.equals(VisualCatalog.ship("prospector")), "Visual metadata changed across deterministic reload.");
        require(VisualCatalog.ship("__missing__").equals(CatalogShipVisualDefinition.FALLBACK), "Missing ship metadata must fail safely.");
        require(VisualCatalog.station("__missing__").equals(StationVisualDefinition.FALLBACK), "Missing station metadata must fail safely.");
        require(VisualCatalog.system("__missing__").equals(SystemVisualDefinition.FALLBACK), "Missing system metadata must fail safely.");
        require(VisualCatalog.celestial("__missing__", "__missing__").equals(CatalogCelestialVisualDefinition.FALLBACK),
                "Missing celestial metadata must fail safely.");

        System.out.println("Visual content validation passed: " + catalog.shipCount() + " ships, "
                + catalog.stationCount() + " stations, " + catalog.systemCount() + " systems, "
                + catalog.celestialCount() + " celestials.");
    }

    private static void validateManifestRegistration() throws Exception {
        Map<String, Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(MANIFEST_PATH)));
        Map<String, Object> files = ServerSaveStore.object(root.get("files"));
        String configured = ServerSaveStore.string(files, "visuals", "").trim().replace('\\', '/');
        String expected = VisualCatalog.CONFIG_PATH.toString().replace('\\', '/');
        require(expected.equals(configured), "config/starchem.json must register visual metadata at " + expected + ".");
    }

    private static void validateVisualConfigSeparation() throws Exception {
        rejectGameplayKeys(MiniJson.parse(Files.readString(VisualCatalog.CONFIG_PATH)), "visuals");
    }

    private static void rejectGameplayKeys(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                require(!GAMEPLAY_ONLY_KEYS.contains(key), "Gameplay field " + key + " must not appear at " + path + ".");
                rejectGameplayKeys(entry.getValue(), path + "." + key);
            }
        } else if (value instanceof Iterable<?> values) {
            int index = 0;
            for (Object item : values) rejectGameplayKeys(item, path + "[" + index++ + "]");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
