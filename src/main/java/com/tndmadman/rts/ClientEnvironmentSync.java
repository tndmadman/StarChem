package com.tndmadman.rts;

final class ClientEnvironmentSync {
    private static final double HARD_DRIFT_SECONDS = 3.0;
    private static final double DRIFT_DEADBAND_SECONDS = 0.01;
    private static final double MAX_SLEW_SECONDS_PER_SNAPSHOT = 0.02;
    private static final double SLEW_GAIN = 0.20;

    private ClientEnvironmentSync() { }

    static void advance(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt <= 0) return;
        world.advanceClientEnvironment(dt);
    }

    static void synchronizeSnapshot(World world, String systemId, double hostTime, boolean forceCorrection) {
        if (world == null || systemId == null || systemId.isBlank() || !Double.isFinite(hostTime) || hostTime < 0) {
            CelestialPacketCache.clear();
            return;
        }
        if (!systemId.equals(world.activeSystemId())) {
            CelestialPacketCache.clear();
            return;
        }

        long firstEnvironmentSeed = CelestialPacketCache.seed(0L);
        long secondEnvironmentSeed = CelestialPacketCache.seed(1L);
        if (firstEnvironmentSeed == secondEnvironmentSeed) {
            FogOfWarPersistence.noteEnvironment(systemId, firstEnvironmentSeed);
        }

        double drift = hostTime - world.systemTime();
        if (forceCorrection || Math.abs(drift) > HARD_DRIFT_SECONDS) {
            world.syncClientEnvironment(systemId, hostTime);
            CelestialPacketCache.apply(world);
        } else if (Math.abs(drift) > DRIFT_DEADBAND_SECONDS) {
            double correction = Math.max(-MAX_SLEW_SECONDS_PER_SNAPSHOT,
                    Math.min(MAX_SLEW_SECONDS_PER_SNAPSHOT, drift * SLEW_GAIN));
            world.advanceClientEnvironment(correction);
        }
        CelestialPacketCache.clear();
    }

    static double hardDriftSeconds() { return HARD_DRIFT_SECONDS; }
    static double maxSlewSecondsPerSnapshot() { return MAX_SLEW_SECONDS_PER_SNAPSHOT; }
}
