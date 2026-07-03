package com.tndmadman.rts;

final class CommandAuth {
    private CommandAuth() { }

    static boolean base(World world, String playerId, String baseId) {
        Base base = world.bases.get(baseId);
        return base != null && base.playerId.equals(playerId);
    }

    static boolean unit(World world, String playerId, String unitKey) {
        Unit unit = world.units.get(unitKey);
        return unit != null && unit.playerId.equals(playerId);
    }

    static boolean pack(World world, String playerId, String mode, String id) {
        if ("LOAD".equals(mode)) return base(world, playerId, id);
        return unit(world, playerId, id);
    }
}
