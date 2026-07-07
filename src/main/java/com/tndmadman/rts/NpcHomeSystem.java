package com.tndmadman.rts;

final class NpcHomeSystem {
    private NpcHomeSystem() { }

    static void keepCorsairsHome(World world) {
        String faction = Config.CORSAIRS_ID;
        String destination = StarSystems.CORSAIR_SYSTEM_ID;
        world.movePlayerAssetsToSystem(faction, destination);
    }
}
