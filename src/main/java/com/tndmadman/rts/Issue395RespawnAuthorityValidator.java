package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Regression coverage for issue #395 server-authoritative respawn handling. */
public final class Issue395RespawnAuthorityValidator {
    private static final String PLAYER_ID = "P7";

    private Issue395RespawnAuthorityValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem issue 395 respawn authority validation passed.");
    }

    static void validateOrThrow() {
        try {
            GalaxyRuntimeOptions.configureCopies(1);
            validateLiveAssetsRejected();
            validateRemoteAssetsRejected();
            validateOneShotHomeRespawn();
            validateConcurrentDuplicateRespawn();
        } finally {
            GalaxyRuntimeOptions.configureCopies(1);
        }
    }

    private static void validateLiveAssetsRejected() {
        World world = freshWorld("Issue 395 Live Assets");
        int unitsBefore = liveUnitCount(world, PLAYER_ID);
        int basesBefore = liveBaseCount(world, PLAYER_ID);

        RespawnAuthority.Result result = RespawnAuthority.tryRespawn(world, PLAYER_ID);
        require(result == RespawnAuthority.Result.LIVE_ASSETS,
                "respawn was not rejected while the player still had live assets");
        require(liveUnitCount(world, PLAYER_ID) == unitsBefore && liveBaseCount(world, PLAYER_ID) == basesBefore,
                "rejected respawn changed the player's live force");
    }

    private static void validateRemoteAssetsRejected() {
        World world = freshWorld("Issue 395 Remote Assets");
        String home = world.playerHomeSystemId(PLAYER_ID);
        String remote = firstSystemOtherThan(world, home);
        require(!remote.isBlank(), "validator could not find a remote system");

        world.movePlayerAssetsToSystem(PLAYER_ID, remote);
        world.activateSystem(home);
        require(noLiveLocalAssets(world, PLAYER_ID),
                "validator setup left player assets in the viewed home system");
        require(world.hasLiveAssets(PLAYER_ID),
                "validator setup did not retain live assets in another system");

        RespawnAuthority.Result result = RespawnAuthority.tryRespawn(world, PLAYER_ID);
        require(result == RespawnAuthority.Result.LIVE_ASSETS,
                "respawn was not rejected when assets existed only in another system");
        require(liveSystems(world, PLAYER_ID).contains(remote),
                "remote live assets were lost during a rejected respawn");
    }

    private static void validateOneShotHomeRespawn() {
        World world = freshWorld("Issue 395 One Shot");
        String home = world.playerHomeSystemId(PLAYER_ID);
        String viewed = firstSystemOtherThan(world, home);
        require(!viewed.isBlank(), "validator could not find a non-home viewed system");

        clearPlayerCombatAssets(world, PLAYER_ID);
        require(!world.hasLiveAssets(PLAYER_ID), "validator could not establish a defeated player state");
        world.activateSystem(viewed);

        RespawnAuthority.Result first = RespawnAuthority.tryRespawn(world, PLAYER_ID);
        require(first == RespawnAuthority.Result.SPAWNED,
                "legitimately defeated player did not receive one respawn");
        require(viewed.equals(world.activeSystemId()),
                "respawn authority unexpectedly used or changed the caller's active system");
        require(liveUnitCount(world, PLAYER_ID) == 1 && liveBaseCount(world, PLAYER_ID) == 1,
                "successful respawn did not create exactly one starter ship and one starter base");
        require(liveSystems(world, PLAYER_ID).equals(Set.of(home)),
                "respawn starter force was not placed exclusively in the authoritative home system");

        RespawnAuthority.Result second = RespawnAuthority.tryRespawn(world, PLAYER_ID);
        require(second == RespawnAuthority.Result.LIVE_ASSETS,
                "second respawn for the same defeat was not rejected");
        require(liveUnitCount(world, PLAYER_ID) == 1 && liveBaseCount(world, PLAYER_ID) == 1,
                "duplicate respawn created an additional starter force");
    }

    private static void validateConcurrentDuplicateRespawn() {
        World world = freshWorld("Issue 395 Concurrent");
        clearPlayerCombatAssets(world, PLAYER_ID);
        require(!world.hasLiveAssets(PLAYER_ID), "validator could not establish concurrent defeated state");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<RespawnAuthority.Result> first = new AtomicReference<>();
        AtomicReference<RespawnAuthority.Result> second = new AtomicReference<>();
        Thread a = new Thread(() -> runRespawn(world, ready, start, first), "issue395-respawn-a");
        Thread b = new Thread(() -> runRespawn(world, ready, start, second), "issue395-respawn-b");
        a.start();
        b.start();
        await(ready, "respawn worker readiness");
        start.countDown();
        join(a);
        join(b);

        List<RespawnAuthority.Result> results = List.of(first.get(), second.get());
        long spawned = results.stream().filter(RespawnAuthority.Result.SPAWNED::equals).count();
        require(spawned == 1, "concurrent duplicate respawn produced " + spawned + " successful spawns");
        require(results.stream().allMatch(result -> result == RespawnAuthority.Result.SPAWNED
                        || result == RespawnAuthority.Result.LIVE_ASSETS
                        || result == RespawnAuthority.Result.IN_PROGRESS),
                "concurrent duplicate respawn returned an unexpected result: " + results);
        require(liveUnitCount(world, PLAYER_ID) == 1 && liveBaseCount(world, PLAYER_ID) == 1,
                "concurrent duplicate packets created more than one starter group");
    }

    private static World freshWorld(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.register(PLAYER_ID, name, 0xFF5F55, false);
        WorldNetAccess.addPeerGroup(world, PLAYER_ID);
        require(world.hasLiveAssets(PLAYER_ID), "validator failed to create initial player assets");
        return world;
    }

    private static void clearPlayerCombatAssets(World world, String playerId) {
        String previous = world.activeSystemId();
        List<String> systemIds = new ArrayList<>();
        for (WorldSystemState state : world.policySystemStates()) systemIds.add(state.id);
        try {
            for (String systemId : systemIds) {
                world.activateSystem(systemId);
                world.units.values().removeIf(unit -> unit != null && playerId.equals(unit.playerId));
                world.bases.values().removeIf(base -> base != null && playerId.equals(base.playerId));
                world.shots.removeIf(shot -> shot != null && playerId.equals(shot.ownerId));
                world.saveActiveSystem();
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
    }

    private static int liveUnitCount(World world, String playerId) {
        int count = 0;
        for (WorldSystemState state : world.policySystemStates()) {
            for (Unit unit : state.units.values()) {
                if (unit != null && unit.hp > 0 && playerId.equals(unit.playerId)) count++;
            }
        }
        return count;
    }

    private static int liveBaseCount(World world, String playerId) {
        int count = 0;
        for (WorldSystemState state : world.policySystemStates()) {
            for (Base base : state.bases.values()) {
                if (base != null && base.hp > 0 && playerId.equals(base.playerId)) count++;
            }
        }
        return count;
    }

    private static Set<String> liveSystems(World world, String playerId) {
        Set<String> systems = new LinkedHashSet<>();
        for (WorldSystemState state : world.policySystemStates()) {
            boolean live = state.units.values().stream().anyMatch(unit -> unit != null && unit.hp > 0 && playerId.equals(unit.playerId))
                    || state.bases.values().stream().anyMatch(base -> base != null && base.hp > 0 && playerId.equals(base.playerId));
            if (live) systems.add(state.id);
        }
        return Set.copyOf(systems);
    }

    private static boolean noLiveLocalAssets(World world, String playerId) {
        for (Unit unit : world.units.values()) if (unit != null && unit.hp > 0 && playerId.equals(unit.playerId)) return false;
        for (Base base : world.bases.values()) if (base != null && base.hp > 0 && playerId.equals(base.playerId)) return false;
        return true;
    }

    private static String firstSystemOtherThan(World world, String excludedSystemId) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && !system.id().equals(excludedSystemId)) return system.id();
        }
        return "";
    }

    private static void runRespawn(World world, CountDownLatch ready, CountDownLatch start,
                                   AtomicReference<RespawnAuthority.Result> result) {
        ready.countDown();
        await(start, "respawn start gate");
        result.set(RespawnAuthority.tryRespawn(world, PLAYER_ID));
    }

    private static void await(CountDownLatch latch, String label) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + label, ex);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + thread.getName(), ex);
        }
        require(!thread.isAlive(), "respawn worker did not complete: " + thread.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
