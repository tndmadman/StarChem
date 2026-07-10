package com.tndmadman.rts;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Routes world-simulation audio only when the simulated system matches the
 * system currently being observed by the local player.
 */
final class SystemAudio {
    private static final Map<World, String> LISTENER_SYSTEM_BY_WORLD =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SystemAudio() { }

    static void listenTo(World world) {
        if (world != null) listenTo(world, world.activeSystemId());
    }

    static void listenTo(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank()) return;
        LISTENER_SYSTEM_BY_WORLD.put(world, systemId);
    }

    static boolean audible(World world) {
        return world != null && audible(world, world.activeSystemId());
    }

    static boolean audible(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank()) return false;
        String listenerSystem;
        synchronized (LISTENER_SYSTEM_BY_WORLD) {
            listenerSystem = LISTENER_SYSTEM_BY_WORLD.get(world);
            if (listenerSystem == null || listenerSystem.isBlank()) {
                listenerSystem = world.activeSystemId();
                if (listenerSystem != null && !listenerSystem.isBlank()) {
                    LISTENER_SYSTEM_BY_WORLD.put(world, listenerSystem);
                }
            }
        }
        return Objects.equals(listenerSystem, systemId);
    }

    static void play(World world, SoundCue cue) {
        if (audible(world)) ProceduralAudio.play(cue);
    }

    static void play(World world, String systemId, SoundCue cue) {
        if (audible(world, systemId)) ProceduralAudio.play(cue);
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
