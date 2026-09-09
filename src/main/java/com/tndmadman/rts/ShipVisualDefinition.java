package com.tndmadman.rts;

/**
 * Renderer-only ship presentation metadata. Gameplay balance and simulation values do not
 * belong here; the definition is resolved locally from the stable ship type id.
 */
record ShipVisualDefinition(String id, String assetPath, double assetScale,
                            double accentAlpha, boolean neutralProceduralFallback) {

    ShipVisualDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("visual id is required");
        if (!Double.isFinite(assetScale) || assetScale <= 0) assetScale = 1.0;
        if (!Double.isFinite(accentAlpha)) accentAlpha = 0.82;
        accentAlpha = Math.max(0.0, Math.min(1.0, accentAlpha));
    }

    static ShipVisualDefinition conventional(String shipTypeId) {
        String id = VisualCatalog.safeId(shipTypeId);
        return new ShipVisualDefinition(id, "/art/ships/" + id + ".png", 1.0, 0.82, true);
    }
}
