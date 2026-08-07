package com.tndmadman.rts;

import java.util.Map;

final class OwnerFleetLocations {
    private OwnerFleetLocations() { }

    static Map<String, String> capture(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return Map.of();
        return world.ownerUnitLocations(playerId);
    }
}
