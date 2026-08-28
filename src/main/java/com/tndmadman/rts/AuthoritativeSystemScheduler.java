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
    private Set<String> viewedSystems = Set.of();
    private double clock;
    private double nextDiscovery;
    private long nextGeneration = 1;
    private List<String> lastUpdatedSystems = List.of();
    private Stats stats = Stats.empty();

    void update(World world, double dt, Supplier<GalaxyMapSnapshot> discovery) {
        if (world == null || discovery == null || !Double.isFinite(dt) || dt <= 0) return;
        clock += dt;
        viewedSystems = ViewedSystemRegistry.snapshot(world);
        String activeSystemId = world.activeSystemId();
        boolean activePlayerAssetsChanged = activePlayerAssetCountChanged(world, activeSystemId);
        if (slots.isEmpty() || activePlayerAssetsChanged || clock + EPSILON >= nextDiscovery) {
            refresh(discovery, activeSystemId);
        }
        if (activePlayerAssetsChanged) wake(activeSystemId);
        promoteViewedSystems();

        String previousSystem = world.activeSystemId();
        List<String> updated = new ArrayList<>();
        List<Slot> ranHot = new ArrayList<>();
        try {
            boolean playerAssetTopologyChanged = runHotSystems(world, dt, updated, ranHot);
            if (playerAssetTopologyChanged) refresh(discovery, previousSystem);
            finishHotSystems(ranHot);
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
        if (hotSystems.contains(systemId)) return;
        if (slot.queuedDue != null && slot.nextDue <= clock + EPSILON) return;
        slot.nextDue = clock;
        schedule(slot);
    }

    String[] lastUpdatedSystems() {
        return lastUpdatedSystems.toArray(new String[0]);
    }

    Stats stats() {
        return stats;
    }

    int pendingEntryCount() {
        return due.size();
    }

    String statusLine() {
        return "sim " + stats.updatedSystems() + "/" + stats.trackedSystems()
                + " backlog " + stats.backlog()
                + " tiers " + stats.hotSystems() + "/" + stats.warmSystems()
                + "/" + stats.coldSystems() + "/" + stats.dormantSystems();
    }

    private void promoteViewedSystems() {
        for (String systemId : viewedSystems) {
            Slot slot = slots.get(systemId);
            if (slot != null && !hotSystems.contains(systemId)) markHot(slot);
        }
    }

    private boolean runHotSystems(World world, double dt, List<String> updated, List<Slot> ranHot) {
        boolean playerAssetTopologyChanged = false;
        for (String systemId : new ArrayList<>(hotSystems)) {
            Slot slot = slots.get(systemId);
            if (slot == null) {
                hotSystems.remove(systemId);
                continue;
            }
            if (!activate(world, slot)) continue;

            int playerAssetsBefore = playerAssetCount(world);
            run(world, slot, dt);
            int playerAssetsAfter = slot.playerAssets;
            if (playerAssetsBefore != playerAssetsAfter) playerAssetTopologyChanged = true;
            ranHot.add(slot);
            updated.add(systemId);
        }
        return playerAssetTopologyChanged;
    }

    private void finishHotSystems(List<Slot> ranHot) {
        for (Slot slot : ranHot) {
            if (slots.get(slot.systemId) != slot) continue;
            if (slot.tier != SystemSimulationScheduler.SimulationTier.HOT
                    && !viewedSystems.contains(slot.systemId)) {
                hotSystems.remove(slot.systemId);
                slot.nextDue = clock + nextInterval(slot);
                schedule(slot);
            }
        }
    }

    private void updateDueInactiveSystems(World world, double dt, List<String> updated) {
        int processed = 0;
        while (processed < MAX_INACTIVE_UPDATES_PER_TICK) {
            Due candidate = due.peek();
            if (candidate == null || candidate.at() > clock + EPSILON) break;
            due.poll();
            Slot slot = slots.get(candidate.systemId());
            if (slot != null && candidate.equals(slot.queuedDue)) slot.queuedDue = null;
            if (slot == null || slot.generation != candidate.generation()
                    || hotSystems.contains(slot.systemId)) continue;
            if (!activate(world, slot)) continue;

            run(world, slot, dt);
            if (slot.tier == SystemSimulationScheduler.SimulationTier.HOT
                    || viewedSystems.contains(slot.systemId)) {
                markHot(slot);
            } else {
                slot.nextDue = clock + nextInterval(slot);
                schedule(slot);
            }
            updated.add(slot.systemId);
            processed++;
        }
    }

    private boolean activate(World world, Slot slot) {
        world.activateSystem(slot.systemId);
        if (slot.systemId.equals(world.activeSystemId())) return true;
        removeSlot(slot);
        return false;
    }

    private void run(World world, Slot slot, double dt) {
        double elapsed = Math.max(dt, clock - slot.lastRun);
        world.updateCurrentSystem(elapsed);
        slot.lastRun = clock;
        slot.tier = SystemSimulationScheduler.tier(world);
        slot.playerAssets = playerAssetCount(world);
        slot.eventDueIn = GalaxyEventDirector.nextDueInSeconds(world, slot.systemId);
    }

    private double nextInterval(Slot slot) {
        double normal = SystemSimulationScheduler.intervalSeconds(slot.tier);
        if (!Double.isFinite(slot.eventDueIn)) return normal;
        return Math.max(0.01, Math.min(normal, slot.eventDueIn));
    }

    private boolean activePlayerAssetCountChanged(World world, String systemId) {
        if (systemId == null || systemId.isBlank()) return false;
        Slot slot = slots.get(systemId);
        return slot != null && slot.playerAssets >= 0 && slot.playerAssets != playerAssetCount(world);
    }

    private int playerAssetCount(World world) {
        int count = 0;
        for (Unit unit : world.units.values()) {
            if (unit.hp > 0 && !NpcRules.isNpcFaction(unit.playerId)) count++;
        }
        for (Base base : world.bases.values()) {
            if (base.hp > 0 && !NpcRules.isNpcFaction(base.playerId)) count++;
        }
        return count;
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
            Slot slot = entry.getValue();
            unschedule(slot);
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
            if (!hotSystems.contains(systemId)) {
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
        unschedule(slot);
        slot.generation = nextGeneration++;
        hotSystems.add(slot.systemId);
    }

    private void schedule(Slot slot) {
        unschedule(slot);
        hotSystems.remove(slot.systemId);
        slot.generation = nextGeneration++;
        Due scheduled = new Due(slot.systemId, slot.nextDue, slot.generation);
        slot.queuedDue = scheduled;
        due.add(scheduled);
    }

    private void unschedule(Slot slot) {
        if (slot == null || slot.queuedDue == null) return;
        due.remove(slot.queuedDue);
        slot.queuedDue = null;
    }

    private void removeSlot(Slot slot) {
        if (slot == null) return;
        unschedule(slot);
        slots.remove(slot.systemId);
        hotSystems.remove(slot.systemId);
    }

    private Stats snapshotStats(int updatedSystems) {
        int backlog = 0;
        int hot = 0;
        int warm = 0;
        int cold = 0;
        int dormant = 0;
        for (Slot slot : slots.values()) {
            boolean effectiveHot = hotSystems.contains(slot.systemId);
            if (!effectiveHot && slot.nextDue <= clock + EPSILON) backlog++;
            if (effectiveHot) {
                hot++;
                continue;
            }
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
        Due queuedDue;
        int playerAssets = -1;
        double eventDueIn = Double.POSITIVE_INFINITY;
        SystemSimulationScheduler.SimulationTier tier = SystemSimulationScheduler.SimulationTier.DORMANT;

        Slot(String systemId, double lastRun, int signature) {
            this.systemId = systemId;
            this.lastRun = lastRun;
            this.signature = signature;
        }
    }

    private record Due(String systemId, double at, long generation) { }
}
