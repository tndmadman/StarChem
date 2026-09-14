package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Bridges deterministic celestial deposits from persistent system state into the active tactical
 * world's live resource list.
 *
 * <p>WorldSystemState and World intentionally own separate resource collections. Celestial gameplay
 * seeds deposits while CelestialSystem is updating the persistent state, while mining/rendering use
 * the active World's resource list. Without this bridge a later saveActive pass can replace the
 * persistent list with the live list before those new deposits are ever visible, effectively
 * deleting them. The bridge copies only body-anchored nodes and keeps the same ResourceNode object,
 * so mining, depletion, respawn, save state and extraction objectives all remain authoritative.</p>
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

        // A zero-time update is safe and guarantees deposits exist even when this sync is invoked
        // before the normal environment tick (for example immediately after loading a system).
        boolean seeded = false;
        for (ResourceNode node : state.resources) {
            if (isCelestial(node)) {
                seeded = true;
                break;
            }
        }
        if (!seeded) celestials.update(0);

        Set<Integer> liveIds = new HashSet<>();
        for (ResourceNode node : liveResources) if (node != null) liveIds.add(node.id);

        // Snapshot the state list because a zero-time celestial update may have just populated it.
        for (ResourceNode node : new ArrayList<>(state.resources)) {
            if (!isCelestial(node) || !liveIds.add(node.id)) continue;
            liveResources.add(node);
        }
    }

    private static boolean isCelestial(ResourceNode node) {
        return node != null
                && node.celestialAnchorBodyId != null
                && !node.celestialAnchorBodyId.isBlank();
    }
}
