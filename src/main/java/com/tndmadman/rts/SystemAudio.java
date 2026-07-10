package com.tndmadman.rts;

import java.util.Objects;

/**
 * Routes world-simulation audio only when the simulated system matches the
 * system currently being rendered for the local player.
 */
final class SystemAudio {
    private static volatile String listenerSystemId = "";

    private SystemAudio() { }

    static void listenTo(World world) {
        if (world != null) listenTo(world.activeSystemId());
    }

    static void listenTo(String systemId) {
        if (systemId == null || systemId.isBlank()) return;
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
