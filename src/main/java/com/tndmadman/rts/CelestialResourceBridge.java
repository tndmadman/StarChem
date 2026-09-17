package com.tndmadman.rts;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bridges released celestial deposits from persistent system state into the active tactical world's
 * live resource list. Unfractured planets and moons remain inactive and therefore cannot render,
 * select, mine, or replicate as visible resources.
 */
final class CelestialResourceBridge {
    private static final Map<CelestialSystem, WorldSystemState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CelestialResourceBridge() { }

    static void register(WorldSystemState state) {
        if (state == null || state.celestials == null) return;
        STATES.put(state.celestials, state);
    }

    static void sync(List<ResourceNode> liveResources, CelestialSystem celestials) {
        if (liveResources == null || celestials == null) return;
        WorldSystemState state = STATES.get(celestials);
        if (state == null || liveResources == state.resources) return;

        // Keep already-bridged nodes in the tactical list when a field seals. They become inactive
        // in place, so depletion never removes list entries or forces a spatial/list rebuild.
        for (ResourceNode node : liveResources) {
            if (!isCelestial(node) || CelestialExtractionSystem.released(state, node.celestialAnchorBodyId)) continue;
            node.active = false;
            node.respawnTimer = 0;
        }

        // Do not call celestials.update(0) from this render/simulation hot path. The normal celestial
        // simulation pass owns deposit seeding. Calling a nested zero-delta celestial update here
        // after the last rock depleted was doing a second full celestial gameplay pass on the exact
        // frame the field disappeared and caused the remaining depletion hitch.
        for (ResourceNode node : state.resources) {
            if (!isReleasedCelestial(state, node) || containsId(liveResources, node.id)) continue;
            liveResources.add(node);
        }
    }

    private static boolean containsId(List<ResourceNode> resources, int id) {
        for (ResourceNode node : resources) if (node != null && node.id == id) return true;
        return false;
    }

    private static boolean isReleasedCelestial(WorldSystemState state, ResourceNode node) {
        return isCelestial(node) && CelestialExtractionSystem.released(state, node.celestialAnchorBodyId);
    }

    private static boolean isCelestial(ResourceNode node) {
        return node != null
                && node.celestialAnchorBodyId != null
                && !node.celestialAnchorBodyId.isBlank();
    }
}
