package com.tndmadman.rts;

/** Generic cosmetic ship metadata loaded from config/visuals.json for issue #390. */
record CatalogShipVisualDefinition(String id, ShipVisualFamily family, long seed, int detailCount) {
    static final CatalogShipVisualDefinition FALLBACK =
            new CatalogShipVisualDefinition("fallback", ShipVisualFamily.COMBAT, 0x51A7C0DEL, 2);

    CatalogShipVisualDefinition {
        id = id == null || id.isBlank() ? "fallback" : id.trim();
        family = family == null ? ShipVisualFamily.COMBAT : family;
        detailCount = Math.max(1, Math.min(6, detailCount));
    }

    String cacheKey() {
        return id + ':' + family.name() + ':' + seed + ':' + detailCount;
    }
}

enum ShipVisualFamily {
    SCOUT,
    MINER,
    GAS,
    CARGO,
    BUILDER,
    COMBAT,
    CARRIER,
    SIEGE,
    MONOLITH
}
