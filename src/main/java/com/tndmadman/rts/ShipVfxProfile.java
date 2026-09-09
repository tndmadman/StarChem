package com.tndmadman.rts;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Authored cosmetic engine and weapon mount layout for the procedural hull families. */
final class ShipVfxProfile {
    private static final Map<String, ShipVfxProfile> CACHE = new HashMap<>();

    final double engineX;
    final double muzzleX;
    final double thrusterY;
    final double driveScale;
    final int exhaustRgb;
    private final double[] engineY;
    private final double[] muzzleY;

    private ShipVfxProfile(double engineX, double[] engineY, double muzzleX, double[] muzzleY,
                           double thrusterY, double driveScale, int exhaustRgb) {
        this.engineX = engineX;
        this.engineY = engineY;
        this.muzzleX = muzzleX;
        this.muzzleY = muzzleY;
        this.thrusterY = thrusterY;
        this.driveScale = driveScale;
        this.exhaustRgb = exhaustRgb;
    }

    static ShipVfxProfile forType(ShipType type) {
        if (type == null) return fallback(1.0);
        String key = type.id == null ? "" : type.id;
        ShipVfxProfile cached = CACHE.get(key);
        if (cached != null) return cached;
        ShipVfxProfile created = create(type);
        CACHE.put(key, created);
        return created;
    }

    int engineCount() { return engineY.length; }
    double engineY(int index) { return engineY[Math.floorMod(index, engineY.length)]; }
    int muzzleCount() { return muzzleY.length; }
    double muzzleY(int index) { return muzzleY[Math.floorMod(index, muzzleY.length)]; }

    private static ShipVfxProfile create(ShipType type) {
        double s = Math.max(0.55, type.size.scale);
        String id = type.id == null ? "" : type.id.toLowerCase(Locale.ROOT);
        if (id.contains("monolith")) {
            return profile(s, 64, new double[]{-20, -7, 7, 20}, -54,
                    new double[]{-24, -12, 0, 12, 24}, 29, 1.45, 0x73D8FF);
        }
        if (type.baseBuilder || id.contains("builder") || id.contains("deployer")) {
            return profile(s, 30, new double[]{-8, 8}, -30,
                    new double[]{-13, 13}, 23, 1.00, 0x89DFFF);
        }
        if (id.contains("scout") || type.scoutRange > 0) {
            return profile(s, 25, new double[]{0}, -28,
                    new double[]{0}, 15, 0.88, 0x9FEAFF);
        }
        if (id.contains("gas")) {
            return profile(s, 26, new double[]{-8, 8}, -24,
                    new double[]{-9, 9}, 20, 0.95, 0x75F4DB);
        }
        if (type.harvestRange > 0 && type.harvestKinds.contains(NodeKind.SILICATE_ROCK)) {
            return profile(s, 24, new double[]{-7, 7}, -29,
                    new double[]{-11, 11}, 20, 0.96, 0x7EDBFF);
        }
        if (id.contains("hauler") || id.contains("freighter") || type.cargoCapacity >= 300) {
            return profile(s, 31, new double[]{-10, 0, 10}, -32,
                    new double[]{-13, 13}, 23, 1.08, 0x8ED8FF);
        }
        if (id.contains("carrier")) {
            return profile(s, 40, new double[]{-19, -7, 7, 19}, -42,
                    new double[]{-22, -11, 0, 11, 22}, 30, 1.26, 0x72CBFF);
        }
        if (id.contains("dread") || id.contains("titan")) {
            return profile(s, 45, new double[]{-17, 0, 17}, -48,
                    new double[]{-20, -10, 0, 10, 20}, 29, 1.34, 0x68C4FF);
        }
        if (s >= 2.6) {
            return profile(s, 38, new double[]{-15, 0, 15}, -42,
                    new double[]{-18, -9, 0, 9, 18}, 27, 1.22, 0x78D1FF);
        }
        if (s >= 1.5) {
            return profile(s, 29, new double[]{-8, 8}, -34,
                    new double[]{-12, 0, 12}, 22, 1.08, 0x82D8FF);
        }
        return profile(s, 29, new double[]{0}, -30,
                new double[]{-7, 7}, 17, 0.94, 0x91E2FF);
    }

    private static ShipVfxProfile fallback(double s) {
        return profile(s, 29, new double[]{0}, -30, new double[]{0}, 18, 1.0, 0x8ADFFF);
    }

    private static ShipVfxProfile profile(double s, double engineX, double[] engineY,
                                          double muzzleX, double[] muzzleY, double thrusterY,
                                          double driveScale, int exhaustRgb) {
        double[] scaledEngines = new double[engineY.length];
        double[] scaledMuzzles = new double[muzzleY.length];
        for (int i = 0; i < engineY.length; i++) scaledEngines[i] = engineY[i] * s;
        for (int i = 0; i < muzzleY.length; i++) scaledMuzzles[i] = muzzleY[i] * s;
        return new ShipVfxProfile(engineX * s, scaledEngines, muzzleX * s, scaledMuzzles,
                thrusterY * s, driveScale, exhaustRgb);
    }
}
