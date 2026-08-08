package com.tndmadman.rts;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class OwnerFleetLocationRegistry {
    private static final Map<World, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<World> SUSPENDED = Collections.newSetFromMap(new WeakHashMap<>());

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
        synchronized (SUSPENDED) { SUSPENDED.remove(world); }
        STATES.put(world, new State(true, ownerId, safe));
    }

    static State state(World world) {
        if (world == null) return State.EMPTY;
        synchronized (SUSPENDED) {
            if (SUSPENDED.contains(world)) return State.EMPTY;
        }
        State state = STATES.get(world);
        return state == null ? State.EMPTY : state;
    }

    static void suspendUntilFreshProjection(World world) {
        if (world == null) return;
        STATES.remove(world);
        synchronized (SUSPENDED) { SUSPENDED.add(world); }
    }

    static void clear(World world) {
        if (world == null) return;
        STATES.remove(world);
        synchronized (SUSPENDED) { SUSPENDED.remove(world); }
    }

    record State(boolean initialized, String ownerId, Map<String, String> locations) {
        static final State EMPTY = new State(false, "", Map.of());

        State {
            ownerId = ownerId == null ? "" : ownerId;
            locations = locations == null ? Map.of() : Map.copyOf(locations);
        }
    }
}
