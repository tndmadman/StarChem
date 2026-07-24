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
    private static final double DISCOVERY_INTERVAL_SECONDS = 1.0;
    private static final double EPSILON = 0.000001;

    private final Map<String, Slot> slots = new LinkedHashMap<>();
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
        if (slots.isEmpty() || clock + EPSILON >= nextDiscovery) refresh(discovery);

        String previousSystem = world.activeSystemId();
        List<String> updated = new ArrayList<>();
        int guard = Math.max(16, slots.size() * 4);
        try {
  while (guard-- > 0) {
      Due candidate = due.peek();
      if (candidate == null || candidate.at() > clock + EPSILON) break;
      due.poll();
      Slot slot = slots.get(candidate.systemId());
      if (slot == null || slot.generation != candidate.generation()) continue;

      double elapsed = Math.max(dt, clock - slot.lastRun);
      world.activateSystem(slot.systemId);
      if (!slot.systemId.equals(world.activeSystemId())) {
          slots.remove(slot.systemId);
          continue;
      }
      world.updateCurrentSystem(elapsed);
      slot.lastRun = clock;
      slot.tier = SystemSimulationScheduler.tier(world);
      slot.nextDue = clock + Math.max(dt, SystemSimulationScheduler.intervalSeconds(slot.tier));
      schedule(slot);
      updated.add(slot.systemId);
  }
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
        return "sim " + stats.updatedSystems + "/" + stats.trackedSystems
      + " backlog " + stats.backlog
      + " tiers " + stats.hotSystems + "/" + stats.warmSystems
      + "/" + stats.coldSystems + "/" + stats.dormantSystems;
    }

    private void refresh(Supplier<GalaxyMapSnapshot> discovery) {
        nextDiscovery = clock + DISCOVERY_INTERVAL_SECONDS;
        GalaxyMapSnapshot snapshot = discovery.get();
        Set<String> discovered = new LinkedHashSet<>();
        if (snapshot != null && !snapshot.empty()) {
  for (GalaxyMapSystem system : snapshot.systems()) {
      if (system == null || system.id() == null || system.id().isBlank() || system.id().contains("WAIT")) continue;
      discovered.add(system.id());
      int signature = signature(system);
      Slot slot = slots.get(system.id());
      if (slot == null) {
          slot = new Slot(system.id(), clock, signature);
          slots.put(system.id(), slot);
          slot.nextDue = clock;
          schedule(slot);
      } else if (slot.signature != signature) {
          slot.signature = signature;
          slot.nextDue = clock;
          schedule(slot);
      }
  }
        }

        Iterator<Map.Entry<String, Slot>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
  if (!discovered.contains(iterator.next().getKey())) iterator.remove();
        }
    }

    private int signature(GalaxyMapSystem system) {
        return Objects.hash(system.ships(), system.bases(), system.localShips(), system.localBases(),
      system.controllerId(), system.controlStatus(), Math.round(system.captureProgress() * 1000.0));
    }

    private void schedule(Slot slot) {
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
  if (slot.nextDue <= clock + EPSILON) backlog++;
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
