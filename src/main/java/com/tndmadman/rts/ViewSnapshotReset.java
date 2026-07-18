package com.tndmadman.rts;

import java.util.*;

final class ViewSnapshotReset {
    private ViewSnapshotReset() { }

    static void apply(World world, String systemId, long seed, double time) {
        if (world == null || systemId == null || systemId.isBlank() || systemId.contains("WAIT") || time < 0) return;
        if (seed != world.systemSeed()) world.useSystemSeed(seed);
        String owner = ownerFromHome(systemId);
        if (!owner.isBlank() && !"WAIT".equals(owner)) world.ensurePlayerHome(owner);
        world.activateSystem(systemId);
        if (!systemId.equals(world.activeSystemId())) {
            world.syncEnvironment(systemId, seed, time);
            world.activateSystem(systemId);
        }
        if (systemId.equals(world.activeSystemId())) world.syncClientEnvironment(systemId, time);
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

    private static String ownerFromHome(String systemId) {
        String prefix = StarSystems.PLAYER_HOME_SYSTEM_ID + "_";
        return systemId.startsWith(prefix) ? systemId.substring(prefix.length()) : "";
    }
}
