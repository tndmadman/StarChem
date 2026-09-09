package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-authoritative, atomic player respawn transaction. */
final class RespawnAuthority {
    enum Result {
        SPAWNED,
        INVALID_PLAYER,
        LIVE_ASSETS,
        IN_PROGRESS,
        SPAWN_FAILED;

        boolean spawned() { return this == SPAWNED; }
    }

    private static final Map<World, Set<String>> IN_FLIGHT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RespawnAuthority() { }

    static Result tryRespawn(World world, String playerId) {
        if (world == null || !validPlayerId(playerId)) return Result.INVALID_PLAYER;

        synchronized (world) {
            if (!begin(world, playerId)) return Result.IN_PROGRESS;
            try {
                // Defeat is authoritative and galaxy-wide. A client view never defines eligibility.
                if (world.hasLiveAssets(playerId)) return Result.LIVE_ASSETS;

                clearDefeatedCombatState(world, playerId);
                WorldNetAccess.respawnPlayer(world, playerId);
                return world.hasLiveAssets(playerId) ? Result.SPAWNED : Result.SPAWN_FAILED;
            } finally {
                end(world, playerId);
            }
        }
    }

    private static void clearDefeatedCombatState(World world, String playerId) {
        String previousSystemId = world.activeSystemId();
        List<String> systemIds = new ArrayList<>();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id != null && !state.id.isBlank()) systemIds.add(state.id);
        }

        try {
            for (String systemId : systemIds) {
                world.activateSystem(systemId);
                UnitCommandQueueSystem.removePlayer(world, playerId);
                world.units.values().removeIf(unit -> unit != null && playerId.equals(unit.playerId));
                world.bases.values().removeIf(base -> base != null && playerId.equals(base.playerId));
                world.shots.removeIf(shot -> shot != null && playerId.equals(shot.ownerId));
                world.saveActiveSystem();
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
        }

        // Defeated fleets cannot retain automation that references removed ships or stations.
        LogisticsRouteSystem.removePlayer(world, playerId);
    }

    private static boolean begin(World world, String playerId) {
        synchronized (IN_FLIGHT) {
            return IN_FLIGHT.computeIfAbsent(world, ignored -> new java.util.LinkedHashSet<>()).add(playerId);
        }
    }

    private static void end(World world, String playerId) {
        synchronized (IN_FLIGHT) {
            Set<String> players = IN_FLIGHT.get(world);
            if (players == null) return;
            players.remove(playerId);
            if (players.isEmpty()) IN_FLIGHT.remove(world);
        }
    }

    private static boolean validPlayerId(String playerId) {
        return playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId)
                && !NpcRules.isNpcFaction(playerId);
    }
}
