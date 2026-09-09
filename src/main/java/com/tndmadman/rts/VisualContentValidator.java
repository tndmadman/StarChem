package com.tndmadman.rts;

/** Headless checks for the client-local visual content architecture introduced by issue #390. */
public final class VisualContentValidator {
    private VisualContentValidator() { }

    public static void main(String[] args) {
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

        VisualCatalog.reload();
        require(VisualCatalog.ship("__missing_ship__").equals(ShipVisualDefinition.FALLBACK),
                "Unknown ship visual must fail safely to fallback.");
        require(VisualCatalog.station("__missing_station__").equals(StationVisualDefinition.FALLBACK),
                "Unknown station visual must fail safely to fallback.");
        require(VisualCatalog.system("__missing_system__").equals(SystemVisualDefinition.FALLBACK),
                "Unknown system visual must fail safely to fallback.");
        require(VisualCatalog.celestial("__missing_system__", "__missing_body__")
                        .equals(CelestialVisualDefinition.FALLBACK),
                "Unknown celestial visual must fail safely to fallback.");

        require(ShipSpriteCache.maxEntriesForValidation() > 0,
                "Ship sprite cache must remain bounded.");
        require(ShipSpriteCache.sizeForValidation() <= ShipSpriteCache.maxEntriesForValidation(),
                "Ship sprite cache exceeded its configured maximum.");

        System.out.println("Visual content validation passed: "
                + catalog.shipCount() + " ships, "
                + catalog.stationCount() + " stations, "
                + catalog.systemCount() + " systems, "
                + catalog.celestialCount() + " celestials.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
