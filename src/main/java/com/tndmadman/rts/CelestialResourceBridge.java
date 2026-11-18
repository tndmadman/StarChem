package com.tndmadman.rts;

import java.util.Arrays;
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

    static void unregister(WorldSystemState state) {
        if (state == null) return;
        synchronized (STATES) {
            if (STATES.get(state.celestials) == state) STATES.remove(state.celestials);
        }
    }

    static boolean registeredForTesting(WorldSystemState state) {
        return state != null && STATES.get(state.celestials) == state;
    }

    static void sync(List<ResourceNode> liveResources, CelestialSystem celestials) {
        if (liveResources == null || celestials == null) return;
        WorldSystemState state = STATES.get(celestials);
        if (state == null || liveResources == state.resources) return;

        // Rebuild membership from the live list every pass. IDs can enter through restoration,
        // depletion, normal spawning or system activation, so a persistent membership cache would
        // go stale. The per-system primitive scratch reuses storage without boxing/allocation.
        IdScratch seen = state.resourceBridgeIds;
        seen.reset(liveResources.size() + state.resources.size());
        for (ResourceNode node : liveResources) {
            if (node == null) continue;
            seen.add(node.id);
            // Depleted bodies retain the same object/ID; only mineability is disabled.
            if (isCelestial(node) && !CelestialExtractionSystem.released(state, node.celestialAnchorBodyId)) {
                node.active = false;
                node.respawnTimer = 0;
            }
        }

        // Normal celestial simulation seeds persistent deposits. Do not recurse into update(0).
        for (ResourceNode node : state.resources) {
            if (!isReleasedCelestial(state, node) || !seen.add(node.id)) continue;
            liveResources.add(node);
        }
    }

    /**
     * Epoch-stamped open-addressed int set, sized for the current pass and reused by its owning
     * WorldSystemState. It is NOT an incremental resource index: every sync scans actual live IDs.
     * No object reference survives in this scratch, and no state can leak across worlds.
     */
    static final class IdScratch {
        private int[] ids = new int[0];
        private int[] generations = new int[0];
        private int generation;

        void reset(int entries) {
            long needed = Math.max(8L, (long) Math.max(0, entries) * 2L);
            if (needed > (1L << 29)) throw new IllegalArgumentException("Resource index too large");
            int capacity = 8;
            while (capacity < needed) capacity <<= 1;
            if (capacity > ids.length) {
                ids = new int[capacity];
                generations = new int[capacity];
                generation = 1;
            } else if (++generation <= 0) {
                Arrays.fill(generations, 0);
                generation = 1;
            }
        }

        boolean add(int id) {
            int mask = ids.length - 1;
            int slot = (id * 0x9E3779B9) & mask;
            while (generations[slot] == generation) {
                if (ids[slot] == id) return false;
                slot = (slot + 1) & mask;
            }
            ids[slot] = id;
            generations[slot] = generation;
            return true;
        }

        int capacityForTesting() { return ids.length; }
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
