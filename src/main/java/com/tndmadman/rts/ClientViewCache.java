package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ClientViewCache {
    private final Map<String, String> viewByPlayer = new LinkedHashMap<>();

    void setHome(World world, String playerId) {
        if (playerId == null || playerId.isBlank()) return;
        viewByPlayer.put(playerId, world.playerHomeSystemId(playerId));
    }

    void remove(String playerId) {
        viewByPlayer.remove(playerId);
    }

    String[] systems(World world) {
        Set<String> out = new LinkedHashSet<>(viewByPlayer.values());
        out.add(world.activeSystemId());
        return out.toArray(new String[0]);
    }

    String view(World world, String playerId) {
        if (playerId == null || playerId.isBlank()) return world.activeSystemId();
        String existing = viewByPlayer.get(playerId);
        if (existing != null) return existing;
        String home = world.playerHomeSystemId(playerId);
        viewByPlayer.put(playerId, home);
        return home;
    }

    Snapshot makeSnapshot(World world, String playerId, long sequence) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(view(world, playerId));
            return WorldNetAccess.snapshot(world, sequence);
        } finally {
            world.activateSystem(old);
        }
    }

    void applyChange(World world, String playerId, Runnable change) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(view(world, playerId));
            change.run();
            world.saveActiveSystem();
            if (playerId != null && !playerId.isBlank()) viewByPlayer.put(playerId, world.activeSystemId());
        } finally {
            world.activateSystem(old);
        }
    }
}
