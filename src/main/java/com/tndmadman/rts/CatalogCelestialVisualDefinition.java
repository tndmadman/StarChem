package com.tndmadman.rts;

import java.awt.Color;

/** Lightweight legacy/config metadata retained by the generic #390 visual catalog. */
record CatalogCelestialVisualDefinition(String systemId, String bodyId, String style, Integer colorRgb,
                                        double glowScale, long seed) {
    static final CatalogCelestialVisualDefinition FALLBACK =
            new CatalogCelestialVisualDefinition("fallback", "fallback", "legacy", null, 2.2, 0xCE1E57L);

    CatalogCelestialVisualDefinition {
        systemId = systemId == null ? "" : systemId.trim();
        bodyId = bodyId == null ? "" : bodyId.trim();
        style = style == null || style.isBlank() ? "legacy" : style.trim().toLowerCase();
        if (colorRgb != null) colorRgb &= 0xFFFFFF;
        glowScale = Double.isFinite(glowScale) ? Math.max(1.0, Math.min(5.0, glowScale)) : 2.2;
    }

    Color colorOr(Color fallback) {
        return colorRgb == null ? fallback : new Color(colorRgb);
    }
}
