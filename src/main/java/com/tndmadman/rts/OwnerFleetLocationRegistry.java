package com.tndmadman.rts;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

final class OwnerFleetLocationRegistry {
    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

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
        STATES.put(world, new State(true, ownerId, safe));
    }

    static State state(World world) {
        State state = world == null ? null : STATES.get(world);
        return state == null ? State.EMPTY : state;
    }

    static void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    record State(boolean initialized, String ownerId, Map<String, String> locations) {
        static final State EMPTY = new State(false, "", Map.of());

        State {
            ownerId = ownerId == null ? "" : ownerId;
            locations = locations == null ? Map.of() : Map.copyOf(locations);
        }
    }
}
