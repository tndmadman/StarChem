package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fair bounded round-robin inactive-system simulation with accumulated elapsed time.
 * The authoritative coordinator supplies an immutable roster whose identity only changes when
 * systems are added/removed/restored. System switching only changes the active-system filter.
 */
final class GalaxyInactiveSimulationScheduler {
    static final int MAX_SYSTEMS_PER_FRAME = 4;

    private final Map<String,Double> lastUpdatedAt = new LinkedHashMap<>();
    private double clock;
    private int cursor;
    private List<String> lastBatch = List.of();
    private List<String> cachedRoster;
    private String cachedActiveSystemId;
    private List<String> inactiveIds = List.of();
    private int rosterRebuilds;

    void reset() {
        lastUpdatedAt.clear();
        clock = 0;
        cursor = 0;
        lastBatch = List.of();
        cachedRoster = null;
        cachedActiveSystemId = null;
        inactiveIds = List.of();
        rosterRebuilds = 0;
    }

    int lastBatchSize() { return lastBatch.size(); }
    List<String> lastBatch() { return lastBatch; }
    int rosterRebuildsForTesting() { return rosterRebuilds; }

    /** Legacy/test entrypoint, not the per-frame authoritative scheduler path. */
    void update(World world, double dt, GalaxyMapSnapshot snapshot) {
        if (snapshot == null || snapshot.empty()) {
            lastBatch = List.of();
            return;
        }
        List<String> roster = new ArrayList<>(snapshot.systems().size());
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null) roster.add(system.id());
        }
        updateRoster(world, dt, roster);
    }

    void updateRoster(World world, double dt, List<String> roster) {
        if (world == null || roster == null || roster.isEmpty() || !Double.isFinite(dt) || dt <= 0) {
            lastBatch = List.of();
            return;
        }

        clock += dt;
        String activeSystemId = world.activeSystemId();
        if (activeSystemId != null && !activeSystemId.isBlank()) lastUpdatedAt.put(activeSystemId, clock);

        if (cachedRoster != roster || !Objects.equals(cachedActiveSystemId, activeSystemId)) {
            rebuildRoster(roster, activeSystemId);
        }
        if (inactiveIds.isEmpty()) {
            lastBatch = List.of();
            return;
        }

        int budget = Math.min(MAX_SYSTEMS_PER_FRAME, inactiveIds.size());
        String previousStatus = world.status;
        List<String> updated = new ArrayList<>(budget);
        try {
            for (int i = 0; i < budget; i++) {
                String systemId = inactiveIds.get(Math.floorMod(cursor++, inactiveIds.size()));
                double previous = lastUpdatedAt.getOrDefault(systemId, clock - dt);
                double elapsed = Math.max(0, clock - previous);
                lastUpdatedAt.put(systemId, clock);
                if (elapsed <= 0) continue;
                world.activateSystem(systemId);
                world.updateCurrentSystem(elapsed);
                updated.add(systemId);
            }
        } finally {
            if (activeSystemId != null && !activeSystemId.isBlank()) world.activateSystem(activeSystemId);
            world.status = previousStatus;
        }
        lastBatch = List.copyOf(updated);
    }

    private void rebuildRoster(List<String> roster, String activeSystemId) {
        cachedRoster = roster;
        cachedActiveSystemId = activeSystemId;
        // LinkedHashSet preserves original deterministic coordinator iteration order and
        // prevents accidental duplicate system IDs from consuming multiple budget slots.
        Set<String> liveIds = new LinkedHashSet<>();
        List<String> inactive = new ArrayList<>(roster.size());
        for (String id : roster) {
            if (id == null || id.isBlank() || !liveIds.add(id)) continue;
            if (!id.equals(activeSystemId)) inactive.add(id);
        }
        lastUpdatedAt.keySet().removeIf(id -> !liveIds.contains(id));
        inactiveIds = List.copyOf(inactive);
        cursor = inactiveIds.isEmpty() ? 0 : Math.floorMod(cursor, inactiveIds.size());
        rosterRebuilds++;
    }
}
