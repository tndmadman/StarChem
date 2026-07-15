package com.tndmadman.rts;

/**
 * Legacy cleanup hook retained for call-site compatibility.
 *
 * Station expansion and lost-station replacement are both owned by
 * {@link NpcSystem}'s station-build timer. Running a second builder from
 * cleanup caused every simulation tick to be treated as another replacement
 * attempt, including during a normal one-of-four-stations startup.
 */
final class NpcStationReplacementSystem {
    private NpcStationReplacementSystem() { }

    static void replaceMissingStations(World world) {
        // Intentionally passive. NpcSystem.buildOrDeployStation performs the
        // authoritative, cooldown-controlled expansion and replacement work.
    }
}
