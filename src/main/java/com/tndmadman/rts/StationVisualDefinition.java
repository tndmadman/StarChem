package com.tndmadman.rts;

import java.awt.Color;

/** Purely cosmetic station rendering metadata, resolved locally by station type ID. */
record StationVisualDefinition(String id, String style, int hullSides, int hullColorRgb,
                               int coreColorRgb, double coreScale) {
    static final StationVisualDefinition FALLBACK =
            new StationVisualDefinition("fallback", "legacy", 6, 0x141D2A, 0x7DCDFF, 1.0);

    StationVisualDefinition {
        id = id == null || id.isBlank() ? "fallback" : id.trim();
        style = style == null || style.isBlank() ? "legacy" : style.trim().toLowerCase();
        hullSides = Math.max(3, Math.min(12, hullSides));
        coreScale = Double.isFinite(coreScale) ? Math.max(0.5, Math.min(2.0, coreScale)) : 1.0;
        hullColorRgb &= 0xFFFFFF;
        coreColorRgb &= 0xFFFFFF;
    }

    Color hullColor() { return new Color(hullColorRgb); }
    Color coreColor(int alpha) {
        Color c = new Color(coreColorRgb);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
