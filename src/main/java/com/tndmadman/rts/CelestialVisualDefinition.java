package com.tndmadman.rts;

import java.awt.Color;

record CelestialVisualDefinition(
        String id,
        CelestialVisualClass visualClass,
        Color primary,
        Color secondary,
        Color accent,
        Color atmosphere,
        double atmosphereStrength,
        double cloudCoverage,
        double ringInnerRadius,
        double ringOuterRadius,
        double ringFlattening,
        double ringAngle,
        Color ringColor,
        boolean emissive
) {
    CelestialVisualDefinition {
        id = id == null || id.isBlank() ? "fallback" : id.trim();
        visualClass = visualClass == null ? CelestialVisualClass.ROCKY : visualClass;
        primary = primary == null ? Color.GRAY : primary;
        secondary = secondary == null ? primary.darker() : secondary;
        accent = accent == null ? primary.brighter() : accent;
        atmosphere = atmosphere == null ? accent : atmosphere;
        atmosphereStrength = clamp(atmosphereStrength, 0, 1);
        cloudCoverage = clamp(cloudCoverage, 0, 1);
        ringInnerRadius = Math.max(0, ringInnerRadius);
        ringOuterRadius = Math.max(0, ringOuterRadius);
        if (ringOuterRadius > 0 && ringInnerRadius <= 0) ringInnerRadius = 1.25;
        if (ringInnerRadius > 0 && ringOuterRadius < ringInnerRadius) ringOuterRadius = ringInnerRadius;
        ringFlattening = clamp(ringFlattening <= 0 ? 0.36 : ringFlattening, 0.12, 1);
        if (!Double.isFinite(ringAngle)) ringAngle = 0;
        ringColor = ringColor == null ? secondary : ringColor;
    }

    boolean hasAtmosphere() { return atmosphereStrength > 0.01; }
    boolean hasClouds() { return cloudCoverage > 0.01; }
    boolean hasRings() { return ringOuterRadius > ringInnerRadius && ringOuterRadius > 1.0; }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
