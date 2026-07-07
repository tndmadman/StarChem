package com.tndmadman.rts;

final class ClientSync {
    private ClientSync() { }

    static void regular(World world, Snapshot snapshot) {
        WorldNetAccess.apply(world, snapshot);
    }

    static boolean initial(World world, Snapshot snapshot, String playerId) {
        WorldNetAccess.applyView(world, snapshot);
        return !WorldNetAccess.hasPlayerAssets(snapshot, playerId);
    }
}
