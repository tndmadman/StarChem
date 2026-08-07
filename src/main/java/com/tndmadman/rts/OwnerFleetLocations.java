package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class OwnerFleetLocations {
    private OwnerFleetLocations() { }

    static Map<String, String> capture(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank() || "WAIT".equals(playerId)) return Map.of();
        String previousSystemId = world.activeSystemId();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Set<String> systemIds = new LinkedHashSet<>();
            GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
            if (snapshot != null && snapshot.systems() != null) {
                for (GalaxyMapSystem system : snapshot.systems()) {
                    if (system != null && system.id() != null && !system.id().isBlank()) systemIds.add(system.id());
                }
            }
            if (previousSystemId != null && !previousSystemId.isBlank()) systemIds.add(previousSystemId);

            for (String systemId : systemIds) {
                world.activateSystem(systemId);
                if (!systemId.equals(world.activeSystemId())) continue;
                for (Unit unit : world.units.values()) {
                    if (unit == null || unit.hp <= 0 || !playerId.equals(unit.playerId)) continue;
                    out.put(unit.key(), systemId);
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
        }
        return Map.copyOf(out);
    }
}
