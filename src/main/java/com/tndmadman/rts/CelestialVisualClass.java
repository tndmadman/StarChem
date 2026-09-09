package com.tndmadman.rts;

import java.util.Locale;

enum CelestialVisualClass {
    STAR,
    ROCKY,
    TERRESTRIAL,
    DESERT,
    ICE,
    LAVA,
    GAS_GIANT,
    ICE_GIANT,
    TOXIC,
    DEAD,
    INDUSTRIAL;

    static CelestialVisualClass parse(String value, CelestialVisualClass fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
