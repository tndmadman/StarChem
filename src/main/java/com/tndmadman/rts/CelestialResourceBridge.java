package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        // Do not remove already-bridged celestial nodes when a field seals. They are inactive and
        // invisible, and retaining their stable objects avoids a live-resource list resize plus a
        // spatial-index rebuild on the exact frame a field is exhausted. A later fracture simply
        // reactivates these same objects.
        for (ResourceNode node : liveResources) {
            if (!isCelestial(node) || CelestialExtractionSystem.released(state, node.celestialAnchorBodyId)) continue;
            node.active = false;
            node.respawnTimer = 0;
        }

        boolean releasedDepositPresent = false;
        for (ResourceNode node : state.resources) {
            if (isReleasedCelestial(state, node)) {
                releasedDepositPresent = true;
                break;
            }
        }
        if (!releasedDepositPresent) celestials.update(0);

        Set<Integer> liveIds = new HashSet<>();
        for (ResourceNode node : liveResources) if (node != null) liveIds.add(node.id);

        for (ResourceNode node : new ArrayList<>(state.resources)) {
            if (!isReleasedCelestial(state, node) || !liveIds.add(node.id)) continue;
            liveResources.add(node);
        }
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
