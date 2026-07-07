package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;

final class DynamicSystemAllocator {
    private final Map<String, String> playerSystems = new LinkedHashMap<>();
    private int nextEmptySystem = 1;

    String systemForPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) playerId = PlayerRegistry.localId();
        return playerSystems.computeIfAbsent(playerId, ignored -> createEmptySystemId());
    }

    String systemForNpcFaction(String factionId) {
        if (Config.CORSAIRS_ID.equals(factionId)) return StarSystems.CORSAIR_SYSTEM_ID;
        return StarSystems.DEFAULT_SYSTEM_ID;
    }

    WormholeLink wormholeBetween(String fromSystemId, String toSystemId) {
        String a = fromSystemId == null || fromSystemId.isBlank() ? StarSystems.DEFAULT_SYSTEM_ID : fromSystemId;
        String b = toSystemId == null || toSystemId.isBlank() ? StarSystems.DEFAULT_SYSTEM_ID : toSystemId;
        return new WormholeLink(a + "__" + b, a, b);
    }

    private String createEmptySystemId() {
        return StarSystems.PLAYER_HOME_SYSTEM_ID + "_" + nextEmptySystem++;
    }
}
