package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;

final class AuthoritativeSystemScheduler {
    static final int MAX_INACTIVE_UPDATES_PER_TICK = 4;
    private static final double DISCOVERY_INTERVAL_SECONDS = 1.0;
    private static final double EPSILON = 0.000001;

    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Set<String> hotSystems = new LinkedHashSet<>();
    private final PriorityQueue<Due> due = new PriorityQueue<>(Comparator
            .comparingDouble(Due::at)
            .thenComparing(Due::systemId)
            .thenComparingLong(Due::generation));
    private double clock;
    private double nextDiscovery;
    private long nextGeneration = 1;
    private List<String> lastUpdatedSystems = List.of();
    private Stats stats = Stats.empty();

    void update(World world, double dt, Supplier<GalaxyMapSnapshot> discovery) {
        if (world == null || discovery == null || !Double.isFinite(dt) || dt <= 0) return;
        clock += dt;
        if (slots.isEmpty() || clock + EPSILON >= nextDiscovery) {
            refresh(discovery, world.activeSystemId());
        }

        String previousSystem = world.activeSystemId();
        List<String> updated = new ArrayList<>();
        try {
            updateHotSystems(world, dt, updated);
            updateDueInactiveSystems(world, dt, updated);
        } finally {
            if (previousSystem != null && !previousSystem.isBlank()) world.activateSystem(previousSystem);
        }

        lastUpdatedSystems = List.copyOf(updated);
        stats = snapshotStats(updated.size());
    }

    void refreshNow() {
        nextDiscovery = 0;
    }

    void wake(String systemId) {
        Slot slot = slots.get(systemId);
        if (slot == null) {
            nextDiscovery = 0;
            return;
        }
        if (slot.tier == SystemSimulationScheduler.SimulationTier.HOT) return;
        slot.nextDue = clock;
        schedule(slot);
    }

    String[] lastUpdatedSystems() {
        return lastUpdatedSystems.toArray(new String[0]);
    }

    Stats stats() {
        return stats;
    }

    String statusLine() {
        return "sim " + stats.updatedSystems() + "/" + stats.trackedSystems()
                + " backlog " + stats.backlog()
                + " tiers " + stats.hotSystems() + "/" + stats.warmSystems()
                + "/" + stats.coldSystems() + "/" + stats.dormantSystems();
    }

    private void updateHotSystems(World world, double dt, List<String> updated) {
        for (String systemId : new ArrayList<>(hotSystems)) {
            Slot slot = slots.get(systemId);
            if (slot == null) {
                hotSystems.remove(systemId);
                continue;
            }
            if (!activate(world, slot)) continue;

            run(world, slot, dt);
            if (slot.tier != SystemSimulationScheduler.SimulationTier.HOT) {
                hotSystems.remove(systemId);
                slot.nextDue = clock + SystemSimulationScheduler.intervalSeconds(slot.tier);
                schedule(slot);
            }
            updated.add(systemId);
        }
    }

    private void updateDueInactiveSystems(World world, double dt, List<String> updated) {
        int processed = 0;
        while (processed < MAX_INACTIVE_UPDATES_PER_TICK) {
            Due candidate = due.peek();
            if (candidate == null || candidate.at() > clock + EPSILON) break;
            due.poll();
            Slot slot = slots.get(candidate.systemId());
            if (slot == null || slot.generation != candidate.generation()
                    || slot.tier == SystemSimulationScheduler.SimulationTier.HOT) continue;
            if (!activate(world, slot)) continue;

            run(world, slot, dt);
            if (slot.tier == SystemSimulationScheduler.SimulationTier.HOT) {
                markHot(slot);
            } else {
                slot.nextDue = clock + SystemSimulationScheduler.intervalSeconds(slot.tier);
                schedule(slot);
            }
            updated.add(slot.systemId);
            processed++;
        }
    }

    private boolean activate(World world, Slot slot) {
        world.activateSystem(slot.systemId);
        if (slot.systemId.equals(world.activeSystemId())) return true;
        slots.remove(slot.systemId);
        hotSystems.remove(slot.systemId);
        return false;
    }

    private void run(World world, Slot slot, double dt) {
        double elapsed = Math.max(dt, clock - slot.lastRun);
        world.updateCurrentSystem(elapsed);
        slot.lastRun = clock;
        slot.tier = SystemSimulationScheduler.tier(world);
    }

    private void refresh(Supplier<GalaxyMapSnapshot> discovery, String activeSystemId) {
        nextDiscovery = clock + DISCOVERY_INTERVAL_SECONDS;
        GalaxyMapSnapshot snapshot = discovery.get();
        Set<String> discovered = new LinkedHashSet<>();
        if (snapshot != null && !snapshot.empty()) {
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()
                        || system.id().contains("WAIT")) continue;
                discovered.add(system.id());
                upsert(system.id(), signature(system));
            }
        }
        if (discovered.isEmpty() && activeSystemId != null && !activeSystemId.isBlank()
                && !activeSystemId.contains("WAIT")) {
            discovered.add(activeSystemId);
            upsert(activeSystemId, 0);
        }

        Iterator<Map.Entry<String, Slot>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Slot> entry = iterator.next();
            if (discovered.contains(entry.getKey())) continue;
            hotSystems.remove(entry.getKey());
            iterator.remove();
        }
    }

    private void upsert(String systemId, int signature) {
        Slot slot = slots.get(systemId);
        if (slot == null) {
            slot = new Slot(systemId, clock, signature);
            slots.put(systemId, slot);
            slot.nextDue = clock;
            schedule(slot);
        } else if (slot.signature != signature) {
            slot.signature = signature;
            if (slot.tier != SystemSimulationScheduler.SimulationTier.HOT) {
                slot.nextDue = clock;
                schedule(slot);
            }
        }
    }

    private int signature(GalaxyMapSystem system) {
        return Objects.hash(system.ships(), system.bases(), system.localShips(), system.localBases(),
                system.controllerId(), system.controlStatus(), Math.round(system.captureProgress() * 1000.0));
    }

    private void markHot(Slot slot) {
        slot.generation = nextGeneration++;
        hotSystems.add(slot.systemId);
    }

    private void schedule(Slot slot) {
        hotSystems.remove(slot.systemId);
        slot.generation = nextGeneration++;
        due.add(new Due(slot.systemId, slot.nextDue, slot.generation));
    }

    private Stats snapshotStats(int updatedSystems) {
        int backlog = 0;
        int hot = 0;
        int warm = 0;
        int cold = 0;
        int dormant = 0;
        for (Slot slot : slots.values()) {
            if (slot.tier != SystemSimulationScheduler.SimulationTier.HOT
                    && slot.nextDue <= clock + EPSILON) backlog++;
            switch (slot.tier) {
                case HOT -> hot++;
                case WARM -> warm++;
                case COLD -> cold++;
                case DORMANT -> dormant++;
            }
        }
        return new Stats(slots.size(), updatedSystems, backlog, hot, warm, cold, dormant);
    }

    record Stats(int trackedSystems, int updatedSystems, int backlog, int hotSystems,
                 int warmSystems, int coldSystems, int dormantSystems) {
        static Stats empty() {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    private static final class Slot {
        final String systemId;
        double lastRun;
        double nextDue;
        long generation;
        int signature;
        SystemSimulationScheduler.SimulationTier tier = SystemSimulationScheduler.SimulationTier.DORMANT;

        Slot(String systemId, double lastRun, int signature) {
            this.systemId = systemId;
            this.lastRun = lastRun;
            this.signature = signature;
        }
    }

    private record Due(String systemId, double at, long generation) { }
}
