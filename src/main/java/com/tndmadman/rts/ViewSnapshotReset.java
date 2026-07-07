package com.tndmadman.rts;

final class ViewSnapshotReset {
    private ViewSnapshotReset() { }

    static void apply(World world, String systemId, long seed, double time) {
        if (world == null || systemId == null || systemId.isBlank() || time < 0) return;
        long temporary = seed ^ 0x5DEECE66DL;
        if (temporary == seed) temporary++;
        String syncId = baseSystemId(systemId);
        world.syncEnvironment(syncId, temporary, 0);
        world.syncEnvironment(syncId, seed, time);
        if (!systemId.equals(syncId)) {
            String owner = ownerFromHome(systemId);
            if (!owner.isBlank()) world.ensurePlayerHome(owner);
            world.activateSystem(systemId);
        }
    }

    private static String baseSystemId(String systemId) {
        if (systemId.startsWith(StarSystems.PLAYER_HOME_SYSTEM_ID + "_")) return StarSystems.PLAYER_HOME_SYSTEM_ID;
        return systemId;
    }

    private static String ownerFromHome(String systemId) {
        String prefix = StarSystems.PLAYER_HOME_SYSTEM_ID + "_";
        return systemId.startsWith(prefix) ? systemId.substring(prefix.length()) : "";
    }
}
