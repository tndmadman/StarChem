package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-authoritative, atomic player respawn lifecycle and transaction. */
final class RespawnAuthority {
    enum Lifecycle {
        ACTIVE,
        DEFEATED,
        RESPAWNING
    }

    enum Result {
        SPAWNED,
        INVALID_PLAYER,
        LIVE_ASSETS,
        NOT_ELIGIBLE,
        IN_PROGRESS,
        SPAWN_FAILED;

        boolean spawned() { return this == SPAWNED; }
    }

    private static final Map<World, Map<String, Lifecycle>> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RespawnAuthority() { }

    /**
     * Observe normal authenticated players from the authoritative simulation thread.
     * A RESPawn packet never creates eligibility; it may only consume DEFEATED state
     * established here from the server's galaxy-wide asset state.
     */
    static void observeRegisteredPlayers(World world) {
        if (world == null) return;
        synchronized (world) {
            Map<String, Lifecycle> states = stateMap(world);
            for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
                if (player == null || !validPlayerId(player.id())) continue;
                String playerId = player.id();
                if (ObserverSessions.isObserver(world, playerId)) {
                    states.remove(playerId);
                    continue;
                }

                Lifecycle current = states.get(playerId);
                if (current == Lifecycle.RESPAWNING) continue;
                if (world.hasLiveAssets(playerId)) {
                    states.put(playerId, Lifecycle.ACTIVE);
                } else if (current == Lifecycle.ACTIVE || current == null) {
                    states.put(playerId, Lifecycle.DEFEATED);
                }
            }
        }
    }

    static Result tryRespawn(World world, String playerId) {
        if (world == null || !validPlayerId(playerId)) return Result.INVALID_PLAYER;

        synchronized (world) {
            Map<String, Lifecycle> states = stateMap(world);
            Lifecycle lifecycle = states.get(playerId);
            if (lifecycle == Lifecycle.RESPAWNING) return Result.IN_PROGRESS;

            // Global live assets always win over any stale lifecycle state.
            if (world.hasLiveAssets(playerId)) {
                states.put(playerId, Lifecycle.ACTIVE);
                return Result.LIVE_ASSETS;
            }
            if (lifecycle != Lifecycle.DEFEATED) return Result.NOT_ELIGIBLE;

            states.put(playerId, Lifecycle.RESPAWNING);
            try {
                clearDefeatedCombatState(world, playerId);
                WorldNetAccess.respawnPlayer(world, playerId);
                if (!world.hasLiveAssets(playerId)) {
                    states.put(playerId, Lifecycle.DEFEATED);
                    return Result.SPAWN_FAILED;
                }
                states.put(playerId, Lifecycle.ACTIVE);
                return Result.SPAWNED;
            } catch (RuntimeException ex) {
                // A failed transaction remains eligible for a clean retry rather than
                // getting stuck in RESPAWNING forever.
                states.put(playerId, Lifecycle.DEFEATED);
                return Result.SPAWN_FAILED;
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

    private static Map<String, Lifecycle> stateMap(World world) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
        }
    }

    private static boolean validPlayerId(String playerId) {
        return playerId != null && !playerId.isBlank()
                && !"WAIT".equals(playerId) && !"SOLO".equals(playerId)
                && !NpcRules.isNpcFaction(playerId);
    }
}
