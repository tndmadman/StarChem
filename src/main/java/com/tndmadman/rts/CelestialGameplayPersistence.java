package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;

/** Save/restore support for mutable celestial intel and objective progress. */
final class CelestialGameplayPersistence {
    private CelestialGameplayPersistence() { }

    static Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (world == null) return out;
        for (WorldSystemState state : world.policySystemStates()) {
            Map<String,Object> saved = captureState(state);
            if (!saved.isEmpty()) out.put(state.id, saved);
        }
        return out;
    }

    static void restore(World world, Object savedState) {
        if (world == null) return;
        Map<String,Object> root = ServerSaveStore.object(savedState);
        for (WorldSystemState state : world.policySystemStates()) {
            restoreState(state, root.get(state.id));
        }
    }

    static Map<String,Object> captureState(WorldSystemState state) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (state == null) return out;
        for (CelestialBodyState body : CelestialGameplaySystem.bodyStates(state)) {
            Map<String,Object> row = new LinkedHashMap<>();
            if (!body.intelByPlayer.isEmpty()) {
                Map<String,Object> intel = new LinkedHashMap<>();
                body.intelByPlayer.forEach((player, level) -> {
                    if (player != null && !player.isBlank() && level != null) intel.put(player, level.name());
                });
                if (!intel.isEmpty()) row.put("intel", intel);
            }
            putDoubleMap(row, "scanSeconds", body.scanSecondsByPlayer);
            putDoubleMap(row, "holdSeconds", body.holdSecondsByPlayer);
            putDoubleMap(row, "extracted", body.extractedByPlayer);
            if (!row.isEmpty()) out.put(body.profile.bodyId(), row);
        }
        return out;
    }

    static void restoreState(WorldSystemState state, Object savedState) {
        if (state == null) return;
        Map<String,Object> saved = ServerSaveStore.object(savedState);
        for (CelestialBodyState body : CelestialGameplaySystem.bodyStates(state)) {
            Map<String,Object> row = ServerSaveStore.object(saved.get(body.profile.bodyId()));
            if (row.isEmpty()) continue;

            body.intelByPlayer.clear();
            Map<String,Object> intel = ServerSaveStore.object(row.get("intel"));
            for (Map.Entry<String,Object> entry : intel.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                try {
                    body.intelByPlayer.put(entry.getKey(),
                            CelestialIntelLevel.valueOf(ServerSaveStore.asString(entry.getValue(), "VISIBLE")));
                } catch (IllegalArgumentException ignored) {
                    body.intelByPlayer.put(entry.getKey(), CelestialIntelLevel.VISIBLE);
                }
            }
            restoreDoubleMap(body.scanSecondsByPlayer, row.get("scanSeconds"));
            restoreDoubleMap(body.holdSecondsByPlayer, row.get("holdSeconds"));
            restoreDoubleMap(body.extractedByPlayer, row.get("extracted"));
        }
    }

    private static void putDoubleMap(Map<String,Object> out, String key, Map<String,Double> values) {
        if (values == null || values.isEmpty()) return;
        Map<String,Object> saved = new LinkedHashMap<>();
        for (Map.Entry<String,Double> entry : values.entrySet()) {
            String player = entry.getKey();
            Double value = entry.getValue();
            if (player == null || player.isBlank() || value == null || !Double.isFinite(value) || value <= 0) continue;
            saved.put(player, value);
        }
        if (!saved.isEmpty()) out.put(key, saved);
    }

    private static void restoreDoubleMap(Map<String,Double> target, Object savedState) {
        target.clear();
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(savedState).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            double value = ServerSaveStore.asDouble(entry.getValue(), 0);
            if (Double.isFinite(value) && value > 0) target.put(entry.getKey(), value);
        }
    }
}
