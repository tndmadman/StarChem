package com.tndmadman.rts;

/** Purely cosmetic ship rendering metadata, resolved locally by ship type ID. */
record ShipVisualDefinition(String id, ShipVisualFamily family, long seed, int detailCount) {
    static final ShipVisualDefinition FALLBACK =
            new ShipVisualDefinition("fallback", ShipVisualFamily.COMBAT, 0x51A7C0DEL, 2);

    ShipVisualDefinition {
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
