package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps large procedural galaxies from simulating every inactive system every frame.
 * Inactive systems are advanced round-robin with accumulated elapsed time so simulation
 * remains bounded while each system still receives authoritative updates over time.
 */
final class GalaxyInactiveSimulationScheduler {
    static final int MAX_SYSTEMS_PER_FRAME = 4;

    private final Map<String,Double> lastUpdatedAt = new LinkedHashMap<>();
    private double clock;
    private int cursor;
    private List<String> lastBatch = List.of();

    void reset() {
        lastUpdatedAt.clear();
        clock = 0;
        cursor = 0;
        lastBatch = List.of();
    }

    int lastBatchSize() { return lastBatch.size(); }
    List<String> lastBatch() { return lastBatch; }

    void update(World world, double dt, GalaxyMapSnapshot snapshot) {
        if (world == null || snapshot == null || snapshot.empty() || !Double.isFinite(dt) || dt <= 0) {
            lastBatch = List.of();
            return;
        }

        clock += dt;
        String activeSystemId = world.activeSystemId();
        if (activeSystemId != null && !activeSystemId.isBlank()) lastUpdatedAt.put(activeSystemId, clock);

        List<String> inactive = new ArrayList<>();
        Set<String> liveIds = new LinkedHashSet<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system == null || system.id() == null || system.id().isBlank()) continue;
            liveIds.add(system.id());
            if (!system.id().equals(activeSystemId)) inactive.add(system.id());
        }
        lastUpdatedAt.keySet().removeIf(id -> !liveIds.contains(id));
        if (inactive.isEmpty()) {
            lastBatch = List.of();
            return;
        }

        int budget = Math.min(MAX_SYSTEMS_PER_FRAME, inactive.size());
        String previousStatus = world.status;
        List<String> updated = new ArrayList<>(budget);
        try {
            for (int i = 0; i < budget; i++) {
                String systemId = inactive.get(Math.floorMod(cursor++, inactive.size()));
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
}
