package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ClientViewCache {
    private final Map<String, String> viewByPlayer = new LinkedHashMap<>();
    private final Map<String, Long> revisionByPlayer = new LinkedHashMap<>();

    void setHome(World world, String playerId) {
        if (!realPlayerId(playerId)) return;
        viewByPlayer.put(playerId, world.playerHomeSystemId(playerId));
    }

    void remove(String playerId) {
        viewByPlayer.remove(playerId);
        revisionByPlayer.remove(playerId);
    }

    void removeSystems(Set<String> systemIds) {
        if (systemIds == null || systemIds.isEmpty()) return;
        viewByPlayer.values().removeIf(systemIds::contains);
    }

    String[] systems(World world) {
        Set<String> out = new LinkedHashSet<>(viewByPlayer.values());
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (realPlayerId(player.id())) out.add(world.playerHomeSystemId(player.id()));
        }
        out.add(world.activeSystemId());
        out.removeIf(systemId -> systemId == null || systemId.isBlank() || systemId.contains("WAIT"));
        return out.toArray(new String[0]);
    }

    String view(World world, String playerId) {
        if (!realPlayerId(playerId)) return world.activeSystemId();
        String existing = viewByPlayer.get(playerId);
        if (existing != null && !existing.contains("WAIT")) return existing;
        String home = world.playerHomeSystemId(playerId);
        viewByPlayer.put(playerId, home);
        return home;
    }

    void setViewRevision(String playerId, long revision) {
        if (!realPlayerId(playerId) || revision < 0) return;
        revisionByPlayer.put(playerId, revision);
    }

    long viewRevision(String playerId) {
        return playerId == null ? 0 : revisionByPlayer.getOrDefault(playerId, 0L);
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
            if (realPlayerId(playerId) && !world.activeSystemId().contains("WAIT")) viewByPlayer.put(playerId, world.activeSystemId());
        } finally {
            world.activateSystem(old);
        }
    }

    private boolean realPlayerId(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }
}
