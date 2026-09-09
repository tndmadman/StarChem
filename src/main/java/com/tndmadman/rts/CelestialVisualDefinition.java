package com.tndmadman.rts;

import java.awt.Color;

/** Purely cosmetic celestial rendering metadata, resolved locally by system/body ID. */
record CelestialVisualDefinition(String systemId, String bodyId, String style, Integer colorRgb,
                                 double glowScale, long seed) {
    static final CelestialVisualDefinition FALLBACK =
            new CelestialVisualDefinition("fallback", "fallback", "legacy", null, 2.2, 0xCE1E57L);

    CelestialVisualDefinition {
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
