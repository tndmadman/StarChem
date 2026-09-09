package com.tndmadman.rts;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Headless checks for the client-local visual content architecture introduced by issue #390. */
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

        // Ships were previously classified from gameplay stats. Require authored coverage for every
        // current ship so a balance edit can no longer silently change its visual family.
        for (String shipId : Rules.SHIPS.keySet()) {
            require(catalog.containsShip(shipId), "Missing authored ship visual: " + shipId);
        }

        require(catalog.containsStation("outpost"), "Outpost must use the visual-content path.");
        require(catalog.containsSystem(StarSystems.DEFAULT_SYSTEM_ID),
                "Default star system must use the visual-content path.");
        StarSystemDefinition sol = StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID);
        for (CelestialBodyDefinition body : sol.bodies()) {
            require(catalog.containsCelestial(sol.id(), body.id()),
                    "Missing default-system celestial visual: " + sol.id() + "/" + body.id());
        }

        ShipVisualDefinition prospectorBeforeReload = VisualCatalog.ship("prospector");
        VisualCatalog.reload();
        require(prospectorBeforeReload.equals(VisualCatalog.ship("prospector")),
                "Deterministic ship visuals changed across catalog reload.");
        require(VisualCatalog.ship("__missing_ship__").equals(ShipVisualDefinition.FALLBACK),
                "Unknown ship visual must fail safely to fallback.");
        require(VisualCatalog.station("__missing_station__").equals(StationVisualDefinition.FALLBACK),
                "Unknown station visual must fail safely to fallback.");
        require(VisualCatalog.system("__missing_system__").equals(SystemVisualDefinition.FALLBACK),
                "Unknown system visual must fail safely to fallback.");
        require(VisualCatalog.celestial("__missing_system__", "__missing_body__")
                        .equals(CatalogCelestialVisualDefinition.FALLBACK),
                "Unknown celestial visual must fail safely to fallback.");

        validateSpriteCacheInvalidation();

        System.out.println("Visual content validation passed: "
                + catalog.shipCount() + " ships, "
                + catalog.stationCount() + " stations, "
                + catalog.systemCount() + " systems, "
                + catalog.celestialCount() + " celestials.");
    }

    private static void validateManifestRegistration() throws Exception {
        Map<String, Object> root = ServerSaveStore.object(MiniJson.parse(Files.readString(MANIFEST_PATH)));
        Map<String, Object> files = ServerSaveStore.object(root.get("files"));
        String configured = ServerSaveStore.string(files, "visuals", "").trim().replace('\\', '/');
        String expected = VisualCatalog.CONFIG_PATH.toString().replace('\\', '/');
        require(expected.equals(configured),
                "config/starchem.json must register the visual catalog at " + expected + ".");
    }

    private static void validateVisualConfigSeparation() throws Exception {
        Object root = MiniJson.parse(Files.readString(VisualCatalog.CONFIG_PATH));
        rejectGameplayKeys(root, "visuals");
    }

    private static void rejectGameplayKeys(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                require(!GAMEPLAY_ONLY_KEYS.contains(key),
                        "Gameplay field " + key + " must not appear in visual config at " + path + ".");
                rejectGameplayKeys(entry.getValue(), path + "." + key);
            }
        } else if (value instanceof Iterable<?> values) {
            int index = 0;
            for (Object item : values) rejectGameplayKeys(item, path + "[" + index++ + "]");
        }
    }

    private static void validateSpriteCacheInvalidation() {
        ShipSpriteCache.clear();
        Unit probe = new Unit("visual-validator", 390, "prospector", 0, 0);
        require(ShipSpriteCache.sprite(probe, Color.WHITE) != null,
                "Medium-LOD ship sprite cache failed to render a probe sprite.");
        require(ShipSpriteCache.sizeForValidation() > 0,
                "Ship sprite cache did not retain the rendered probe sprite.");
        require(ShipSpriteCache.sizeForValidation() <= ShipSpriteCache.maxEntriesForValidation(),
                "Ship sprite cache exceeded its configured maximum.");
        VisualCatalog.reload();
        require(ShipSpriteCache.sizeForValidation() == 0,
                "Visual catalog reload must invalidate generated ship sprites.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
