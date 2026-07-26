package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ClientViewCache {
    private final Map<String, String> viewByPlayer = new LinkedHashMap<>();
    private final Map<String, Long> revisionByPlayer = new LinkedHashMap<>();
    private World registeredWorld;

    void setHome(World world, String playerId) {
        if (!realPlayerId(playerId)) return;
        world.ensurePlayerHome(playerId, WorldNetAccess.usesPrimaryHome(playerId));
        viewByPlayer.put(playerId, world.playerHomeSystemId(playerId));
        publish(world);
    }

    void remove(String playerId) {
        viewByPlayer.remove(playerId);
        revisionByPlayer.remove(playerId);
        publish(null);
    }

    void removeSystems(Set<String> systemIds) {
        if (systemIds == null || systemIds.isEmpty()) return;
        viewByPlayer.values().removeIf(systemIds::contains);
        publish(null);
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
        if (existing != null && !existing.contains("WAIT")) {
            publish(world);
            return existing;
        }
        String home = world.playerHomeSystemId(playerId);
        viewByPlayer.put(playerId, home);
        publish(world);
        return home;
    }

    boolean requestView(World world, String playerId, String systemId, long revision) {
        if (!realPlayerId(playerId) || !knownSystem(world, systemId) || revision < 0) return false;
        viewByPlayer.put(playerId, systemId);
        revisionByPlayer.put(playerId, revision);
        publish(world);
        return true;
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
            if (realPlayerId(playerId) && !world.activeSystemId().contains("WAIT")) {
                viewByPlayer.put(playerId, world.activeSystemId());
            }
            publish(world);
        } finally {
            world.activateSystem(old);
        }
    }

    private void publish(World world) {
        if (world != null) registeredWorld = world;
        if (registeredWorld != null) ViewedSystemRegistry.replace(registeredWorld, viewByPlayer.values());
    }

    private boolean knownSystem(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank() || systemId.contains("WAIT")) return false;
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        if (snapshot == null || snapshot.empty()) return systemId.equals(world.activeSystemId());
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && systemId.equals(system.id())) return true;
        }
        return false;
    }

    private boolean realPlayerId(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }
}
