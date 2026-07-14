package com.tndmadman.rts;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Routes world-simulation audio through the authoritative event stream and
 * keeps local playback scoped to the world and system currently rendered.
 */
final class SystemAudio {
    private static final Set<World> NON_RENDERED_WORLDS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile World listenerWorld;
    private static volatile String listenerSystemId = "";

    private SystemAudio() { }

    static void markNonRendered(World world) {
        if (world == null) return;
        NON_RENDERED_WORLDS.add(world);
        if (listenerWorld == world) {
            listenerWorld = null;
            listenerSystemId = "";
        }
    }

    static boolean nonRendered(World world) {
        return world != null && NON_RENDERED_WORLDS.contains(world);
    }

    static boolean rendered(World world) {
        return world != null && !nonRendered(world) && listenerWorld == world;
    }

    static void listenTo(World world) {
        if (world == null || nonRendered(world)) return;
        String systemId = world.activeSystemId();
        if (systemId == null || systemId.isBlank()) return;
        listenerWorld = world;
        listenerSystemId = systemId;
        AudioEventCenter.listenerChanged(world);
    }

    static boolean audible(World world) {
        return world != null && audible(world, world.activeSystemId());
    }

    static boolean audible(World world, String systemId) {
        return rendered(world) && audible(systemId);
    }

    static boolean audible(String systemId) {
        return systemId != null && !systemId.isBlank() && Objects.equals(listenerSystemId, systemId);
    }

    static void play(World world, SoundCue cue) {
        AudioEventCenter.play(world, cue);
    }

    static void play(World world, String systemId, SoundCue cue) {
        AudioEventCenter.play(world, systemId, cue);
    }

    static void playForPlayer(World world, String playerId, SoundCue cue) {
        AudioEventCenter.playForPlayer(world, playerId, cue);
    }

    static void playForPlayerInSystem(World world, String playerId, SoundCue cue) {
        AudioEventCenter.playForPlayerInSystem(world, playerId, cue);
    }

    static void play(String systemId, SoundCue cue) {
        if (audible(systemId)) ProceduralAudio.play(cue);
    }

    static void playWeaponFire(World world, WeaponType weapon, double distance) {
        AudioEventCenter.playWeaponFire(world, weapon, distance);
    }

    static void playWeaponImpact(World world, WeaponType weapon) {
        AudioEventCenter.playWeaponImpact(world, weapon);
    }

    static void playDestruction(World world, double scale) {
        AudioEventCenter.playDestruction(world, scale);
    }

    static void playResourceDepleted(World world, Material material) {
        AudioEventCenter.playResourceDepleted(world, material);
    }
}
