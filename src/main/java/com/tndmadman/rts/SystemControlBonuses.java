package com.tndmadman.rts;

final class SystemControlBonuses {
    private static final double MINING_BONUS = 1.12;
    private static final double SHIELD_REGEN_BONUS = 1.08;

    private SystemControlBonuses() { }

    static double miningYield(World world, String ownerId) {
        return controls(world, ownerId) ? MINING_BONUS : 1.0;
    }

    static double shieldRegen(World world, String ownerId) {
        return controls(world, ownerId) ? SHIELD_REGEN_BONUS : 1.0;
    }

    private static boolean controls(World world, String ownerId) {
        if (world == null || ownerId == null || ownerId.isBlank()) return false;
        return ownerId.equals(world.activeSystemControllerId());
    }
}
