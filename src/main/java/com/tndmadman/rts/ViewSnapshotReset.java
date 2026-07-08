package com.tndmadman.rts;

import java.util.*;

final class ViewSnapshotReset {
    private ViewSnapshotReset() { }

    static void apply(World world, String systemId, long seed, double time) {
        if (world == null || systemId == null || systemId.isBlank() || systemId.contains("WAIT") || time < 0) return;
        long temporary = seed ^ 0x5DEECE66DL;
        if (temporary == seed) temporary++;
        String syncId = baseSystemId(systemId);
        world.syncEnvironment(syncId, temporary, 0);
        world.syncEnvironment(syncId, seed, time);
        if (!systemId.equals(syncId)) {
            String owner = ownerFromHome(systemId);
            if (!owner.isBlank() && !"WAIT".equals(owner)) world.ensurePlayerHome(owner);
            world.activateSystem(systemId);
        }
    }

    static void applyPreservingEntities(World world, String systemId, long seed, double time) {
        Map<String, Unit> units = new LinkedHashMap<>(world.units);
        Map<String, Base> bases = new LinkedHashMap<>(world.bases);
        List<ProjectileShot> shots = new ArrayList<>(world.shots);
        List<WorldItem> items = new ArrayList<>(world.items);
        apply(world, systemId, seed, time);
        world.units.clear();
        world.units.putAll(units);
        world.bases.clear();
        world.bases.putAll(bases);
        world.shots.clear();
        world.shots.addAll(shots);
        world.items.clear();
        world.items.addAll(items);
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
