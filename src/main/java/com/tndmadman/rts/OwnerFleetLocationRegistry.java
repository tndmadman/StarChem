package com.tndmadman.rts;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

final class OwnerFleetLocationRegistry {
    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, GalaxyMapWire.OwnerProjection> BLOCKED_PROJECTIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OwnerFleetLocationRegistry() { }

    static void replace(World world, String ownerId, Map<String, String> locations) {
        if (world == null || ownerId == null || ownerId.isBlank() || "WAIT".equals(ownerId)) return;
        Map<String, String> safe = locations == null ? Map.of() : Map.copyOf(locations);
        String prefix = ownerId + ":";
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().startsWith(prefix)
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new SnapshotDecodeException("Owner fleet location packet contains a foreign or invalid unit.");
            }
        }
        BLOCKED_PROJECTIONS.remove(world);
        STATES.put(world, new State(true, ownerId, safe));
    }

    static State state(World world) {
        if (world != null) {
            GalaxyMapWire.OwnerProjection projection =
                    GalaxyMapWire.decodedOwnerProjection(world.galaxyMapSnapshot());
            GalaxyMapWire.OwnerProjection blocked = BLOCKED_PROJECTIONS.get(world);
            if (blocked != null && projection == blocked) return State.EMPTY;
            if (projection.present()) {
                if (blocked != null) BLOCKED_PROJECTIONS.remove(world);
                State prior = STATES.get(world);
                if (prior == null || !prior.initialized()
                        || !projection.ownerId().equals(prior.ownerId())
                        || !projection.locations().equals(prior.locations())) {
                    replace(world, projection.ownerId(), projection.locations());
                }
            }
        }
        State state = world == null ? null : STATES.get(world);
        return state == null ? State.EMPTY : state;
    }

    static void suspendUntilFreshProjection(World world) {
        if (world == null) return;
        GalaxyMapWire.OwnerProjection current =
                GalaxyMapWire.decodedOwnerProjection(world.galaxyMapSnapshot());
        BLOCKED_PROJECTIONS.put(world, current);
        STATES.remove(world);
    }

    static void clear(World world) {
        if (world == null) return;
        STATES.remove(world);
        BLOCKED_PROJECTIONS.remove(world);
    }

    record State(boolean initialized, String ownerId, Map<String, String> locations) {
        static final State EMPTY = new State(false, "", Map.of());

        State {
            ownerId = ownerId == null ? "" : ownerId;
            locations = locations == null ? Map.of() : Map.copyOf(locations);
        }
    }
}
