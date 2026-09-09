package com.tndmadman.rts;

import java.awt.Color;

/** Immutable, presentation-only station art-direction metadata. */
record StationVisualDefinition(
        String id,
        StationVisualPreset preset,
        double scale,
        int ringCount,
        int armCount,
        int moduleCount,
        int lightCount,
        int batteryCount,
        Color accent
) {
    StationVisualDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Station visual id is blank.");
        if (preset == null) throw new IllegalArgumentException("Station visual preset is missing: " + id);
        if (!Double.isFinite(scale) || scale < 0.65 || scale > 1.8)
            throw new IllegalArgumentException("Station visual scale out of range: " + id);
        bounded(id, "ringCount", ringCount, 0, 4);
        bounded(id, "armCount", armCount, 0, 8);
        bounded(id, "moduleCount", moduleCount, 0, 16);
        bounded(id, "lightCount", lightCount, 0, 24);
        bounded(id, "batteryCount", batteryCount, 0, 12);
        accent = accent == null ? new Color(110, 205, 255) : accent;
    }

    private static void bounded(String id, String field, int value, int min, int max) {
        if (value < min || value > max)
            throw new IllegalArgumentException(field + " out of range for station visual " + id + ".");
    }
}

enum StationVisualPreset {
    OUTPOST,
    SHIPYARD,
    LABORATORY,
    FACTORY
}
