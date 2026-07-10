package com.tndmadman.rts;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Routes world-simulation audio only when the simulated system matches the
 * system currently being rendered for the local player.
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

    static void listenTo(World world) {
        if (world == null || NON_RENDERED_WORLDS.contains(world)) return;
        String systemId = world.activeSystemId();
        if (systemId == null || systemId.isBlank()) return;
        listenerWorld = world;
        listenerSystemId = systemId;
    }

    static boolean audible(World world) {
        return world != null && audible(world.activeSystemId());
    }

    static boolean audible(World world, String systemId) {
        return world != null && audible(systemId);
    }

    static boolean audible(String systemId) {
        return systemId != null && !systemId.isBlank() && Objects.equals(listenerSystemId, systemId);
    }

    static void play(World world, SoundCue cue) {
        if (audible(world)) ProceduralAudio.play(cue);
    }

    static void play(World world, String systemId, SoundCue cue) {
        if (audible(world, systemId)) ProceduralAudio.play(cue);
    }

    static void play(String systemId, SoundCue cue) {
        if (audible(systemId)) ProceduralAudio.play(cue);
    }

    static void playWeaponFire(World world, WeaponType weapon, double distance) {
        if (audible(world)) ProceduralAudio.playWeaponFire(weapon, distance);
    }

    static void playWeaponImpact(World world, WeaponType weapon) {
        if (audible(world)) ProceduralAudio.playWeaponImpact(weapon);
    }

    static void playDestruction(World world, double scale) {
        if (audible(world)) ProceduralAudio.playDestruction(scale);
    }

    static void playResourceDepleted(World world, Material material) {
        if (audible(world)) ProceduralAudio.playResourceDepleted(material);
    }
}
