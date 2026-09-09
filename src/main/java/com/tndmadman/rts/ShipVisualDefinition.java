package com.tndmadman.rts;

import java.awt.geom.Path2D;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Immutable cosmetic-only ship artwork metadata. */
final class ShipVisualDefinition {
    enum Feature {
        ANONYMOUS_CONTACT,
        SCOUT_SENSOR,
        MINING_GEAR,
        GAS_GEAR,
        CARGO_MODULES,
        CONSTRUCTION_GEAR,
        SALVAGE_GEAR,
        HANGAR,
        SIEGE_WEAPON,
        CAPITAL,
        MEGASTRUCTURE
    }

    enum MountKind {
        ENGINE,
        HARDPOINT,
        HANGAR,
        CARGO_POD,
        MINING_HEAD,
        GAS_TANK,
        CONSTRUCTION_ARM,
        SALVAGE_BOOM,
        SENSOR_ARRAY,
        LANCE,
        BRIDGE
    }

    /**
     * Mount coordinates are authored in local hull pixels before ShipSize scaling.
     * width/height describe the primitive size; arm/boom mounts use them as endpoint deltas.
     */
    record Mount(MountKind kind, double x, double y, double width, double height) {
        Mount {
            if (kind == null || !finite(x, y, width, height)) {
                throw new IllegalArgumentException("Invalid visual mount.");
            }
        }
    }

    private final String id;
    private final double[][] outline;
    private final List<Mount> mounts;
    private final EnumSet<Feature> features;
    private final int complexityRank;

    ShipVisualDefinition(String id, double[][] outline, List<Mount> mounts,
                         Set<Feature> features, int complexityRank) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Visual id is blank.");
        if (outline == null || outline.length < 3) throw new IllegalArgumentException("Hull needs at least 3 points: " + id);
        this.id = id;
        this.outline = copyOutline(outline, id);
        this.mounts = mounts == null ? List.of() : List.copyOf(mounts);
        this.features = features == null || features.isEmpty()
                ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(features);
        this.complexityRank = Math.max(0, complexityRank);
    }

    String id() { return id; }
    List<Mount> mounts() { return mounts; }
    Set<Feature> features() { return Collections.unmodifiableSet(features); }
    boolean hasFeature(Feature feature) { return features.contains(feature); }
    int complexityRank() { return complexityRank; }

    int mountCount(MountKind kind) {
        int count = 0;
        for (Mount mount : mounts) if (mount.kind() == kind) count++;
        return count;
    }

    Path2D createHull(double scale) {
        double s = Double.isFinite(scale) && scale > 0 ? scale : 1.0;
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO, outline.length);
        path.moveTo(outline[0][0] * s, outline[0][1] * s);
        for (int i = 1; i < outline.length; i++) path.lineTo(outline[i][0] * s, outline[i][1] * s);
        path.closePath();
        return path;
    }

    double[][] normalizedOutline() {
        return copyOutline(outline, id);
    }

    private static double[][] copyOutline(double[][] source, String id) {
        double[][] copy = new double[source.length][2];
        for (int i = 0; i < source.length; i++) {
            if (source[i] == null || source[i].length < 2 || !finite(source[i][0], source[i][1])) {
                throw new IllegalArgumentException("Invalid hull point " + i + " for " + id);
            }
            copy[i][0] = source[i][0];
            copy[i][1] = source[i][1];
        }
        return copy;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
