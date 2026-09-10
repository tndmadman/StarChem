package com.tndmadman.rts;

import java.awt.Color;

/** Purely cosmetic star-system rendering metadata, resolved locally by system ID. */
record SystemVisualDefinition(String id, int orbitColorRgb, int orbitAlpha, long seed) {
    static final SystemVisualDefinition FALLBACK =
            new SystemVisualDefinition("fallback", 0x789BBE, 42, 0x57A253L);

    SystemVisualDefinition {
        id = id == null || id.isBlank() ? "fallback" : id.trim();
        orbitColorRgb &= 0xFFFFFF;
        orbitAlpha = Math.max(0, Math.min(255, orbitAlpha));
    }

    Color orbitColor(int alphaAdjustment) {
        Color c = new Color(orbitColorRgb);
        int alpha = Math.max(0, Math.min(255, orbitAlpha + alphaAdjustment));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
