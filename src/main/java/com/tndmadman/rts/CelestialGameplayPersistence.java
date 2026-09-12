package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;

/** Save/restore support for mutable celestial gameplay state and physical body anchors. */
final class CelestialGameplayPersistence {
    private static final String RESOURCE_ANCHORS = "$resourceAnchors";
    private static final String BASE_ANCHORS = "$baseAnchors";

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

        Map<String,Object> resourceAnchors = new LinkedHashMap<>();
        for (ResourceNode node : state.resources) {
            if (node.celestialAnchorBodyId == null || node.celestialAnchorBodyId.isBlank()) continue;
            resourceAnchors.put(Integer.toString(node.id), node.celestialAnchorBodyId);
        }
        if (!resourceAnchors.isEmpty()) out.put(RESOURCE_ANCHORS, resourceAnchors);

        Map<String,Object> baseAnchors = new LinkedHashMap<>();
        for (Base base : state.bases.values()) {
            if (base.celestialAnchorBodyId == null || base.celestialAnchorBodyId.isBlank()) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("bodyId", base.celestialAnchorBodyId);
            row.put("radius", base.celestialOrbitRadius);
            row.put("angle", base.celestialOrbitAngle);
            row.put("speed", base.celestialOrbitSpeed);
            baseAnchors.put(base.id, row);
        }
        if (!baseAnchors.isEmpty()) out.put(BASE_ANCHORS, baseAnchors);
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

        Map<String,Object> resourceAnchors = ServerSaveStore.object(saved.get(RESOURCE_ANCHORS));
        for (ResourceNode node : state.resources) {
            String bodyId = ServerSaveStore.asString(resourceAnchors.get(Integer.toString(node.id)), "");
            if (!bodyId.isBlank() && state.celestials.bodyView(bodyId) != null) node.celestialAnchorBodyId = bodyId;
        }

        Map<String,Object> baseAnchors = ServerSaveStore.object(saved.get(BASE_ANCHORS));
        for (Base base : state.bases.values()) {
            Map<String,Object> row = ServerSaveStore.object(baseAnchors.get(base.id));
            String bodyId = ServerSaveStore.asString(row.get("bodyId"), "");
            if (bodyId.isBlank() || state.celestials.bodyView(bodyId) == null) continue;
            base.celestialAnchorBodyId = bodyId;
            base.celestialOrbitRadius = Math.max(0, ServerSaveStore.asDouble(row.get("radius"), base.celestialOrbitRadius));
            base.celestialOrbitAngle = ServerSaveStore.asDouble(row.get("angle"), base.celestialOrbitAngle);
            base.celestialOrbitSpeed = ServerSaveStore.asDouble(row.get("speed"), base.celestialOrbitSpeed);
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
