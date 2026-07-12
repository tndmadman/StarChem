package com.tndmadman.rts;

final class ClientEnvironmentSync {
    private static final double HARD_DRIFT_SECONDS = 0.5;

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

        double drift = hostTime - world.systemTime();
        boolean correct = forceCorrection || Math.abs(drift) > HARD_DRIFT_SECONDS;
        if (correct) {
            world.syncClientEnvironment(systemId, hostTime);
            CelestialPacketCache.apply(world);
        }
        CelestialPacketCache.clear();
    }

    static double hardDriftSeconds() {
        return HARD_DRIFT_SECONDS;
    }
}
