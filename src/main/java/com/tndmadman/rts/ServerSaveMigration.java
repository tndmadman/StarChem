package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ServerSaveMigration {
    private ServerSaveMigration() { }

    static Result migrate(int sourceVersion, Map<String,Object> manifest, Map<String,Object> players,
                          Map<String,Object> galaxy, Map<String,Object> runtime) {
        int version = sourceVersion;
        List<String> notes = new ArrayList<>();
        if (version == 1) {
            ensurePlayers(players);
            ensureRuntime(runtime);
            version = 2;
            notes.add("v1->v2 normalized optional player/session/runtime sections");
        }
        if (version == 2) {
            version = 3;
            notes.add("v2->v3 assigns default authored loadouts to legacy ships and queued builds");
        }
        if (version == 3) {
            ensureRuntime(runtime);
            runtime.computeIfAbsent("shipFits", ignored -> new LinkedHashMap<>());
            version = 4;
            notes.add("v3->v4 adds dynamic and published player fit catalogs");
        }
        if (version != ServerSaveStore.SAVE_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported save format " + sourceVersion + ".");
        }
        SavedFitReferenceValidator.validate(sourceVersion, galaxy, runtime);
        manifest.put("saveFormatVersion", ServerSaveStore.SAVE_FORMAT_VERSION);
        manifest.put("loadedSaveFormatVersion", sourceVersion);
        manifest.put("contentCompatibilityPolicy", SaveContentResolver.migrationPolicy());
        if (!notes.isEmpty()) manifest.put("migrationNotes", List.copyOf(notes));
        return new Result(manifest, players, galaxy, runtime, notes);
    }

    private static void ensurePlayers(Map<String,Object> players) {
        players.computeIfAbsent("roster", ignored -> new ArrayList<>());
        players.computeIfAbsent("completedResearch", ignored -> new LinkedHashMap<>());
        players.computeIfAbsent("sessions", ignored -> new ArrayList<>());
    }

    private static void ensureRuntime(Map<String,Object> runtime) {
        runtime.computeIfAbsent("simulationScheduler", ignored -> new LinkedHashMap<>());
        runtime.computeIfAbsent("productionPlanner", ignored -> new LinkedHashMap<>());
        runtime.computeIfAbsent("npcFactions", ignored -> new LinkedHashMap<>());
    }

    record Result(Map<String,Object> manifest, Map<String,Object> players, Map<String,Object> galaxy,
                  Map<String,Object> runtime, List<String> notes) { }
}
