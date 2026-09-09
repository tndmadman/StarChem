package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Deterministic, owner-scoped supply derived from control, usable topology and existing stations. */
final class StrategicSupplyService {
    private static final long REFRESH_NANOS = 1_000_000_000L;
    private static final Map<World, Map<String, CacheEntry>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, Integer> RECOMPUTES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StrategicSupplyService() { }

    static StrategicSupplyState state(World world, String ownerId, String systemId) {
        if (world == null || clean(ownerId).isBlank() || clean(systemId).isBlank()) {
            return StrategicSupplyState.ISOLATED;
        }
        return states(world, ownerId).getOrDefault(systemId, StrategicSupplyState.ISOLATED);
    }

    static Map<String, StrategicSupplyState> states(World world, String ownerId) {
        String owner = clean(ownerId);
        if (world == null || owner.isBlank() || "WAIT".equals(owner)) return Map.of();
        long now = System.nanoTime();
        synchronized (CACHE) {
            Map<String, CacheEntry> byOwner = CACHE.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
            CacheEntry cached = byOwner.get(owner);
            if (cached != null && now - cached.capturedAtNanos < REFRESH_NANOS) return cached.states;
            Map<String, StrategicSupplyState> calculated = calculate(world, owner);
            byOwner.put(owner, new CacheEntry(now, calculated));
            RECOMPUTES.put(world, RECOMPUTES.getOrDefault(world, 0) + 1);
            return calculated;
        }
    }

    static int isolatedCount(World world, String ownerId) {
        int count = 0;
        for (StrategicSupplyState state : states(world, ownerId).values()) {
            if (state == StrategicSupplyState.ISOLATED) count++;
        }
        return count;
    }

    static void invalidate(World world) {
        if (world != null) CACHE.remove(world);
    }

    static void clear(World world) {
        if (world == null) return;
        CACHE.remove(world);
        RECOMPUTES.remove(world);
    }

    static int recomputeCountForTest(World world) {
        return world == null ? 0 : RECOMPUTES.getOrDefault(world, 0);
    }

    private static Map<String, StrategicSupplyState> calculate(World world, String ownerId) {
        List<WorldSystemState> systems = world.policySystemStates();
        Map<String, WorldSystemState> controlled = new LinkedHashMap<>();
        Set<String> roots = new LinkedHashSet<>();
        for (WorldSystemState system : systems) {
            if (system == null || !ownerId.equals(system.control.controllerId())) continue;
            if (system.control.status() != SystemControlStatus.CONTROLLED
                    && system.control.status() != SystemControlStatus.PROTECTED) continue;
            controlled.put(system.id, system);
            // Protected homes are the canonical strategic supply roots. A command outpost helps an
            // isolated pocket survive, but it must not magically bypass a blockade and become a
            // fully supplied root of its own.
            if (system.control.status() == SystemControlStatus.PROTECTED) roots.add(system.id);
        }
        if (controlled.isEmpty()) return Map.of();

        // Organized NPC factions and test worlds can legitimately begin without a protected home.
        // Give those factions one deterministic bootstrap root; prefer a command node when one is
        // available so infrastructure still matters without allowing arbitrary new roots later.
        if (roots.isEmpty()) {
            String bootstrap = "";
            for (Map.Entry<String, WorldSystemState> entry : controlled.entrySet()) {
                if (hasCommandNode(entry.getValue(), ownerId)) {
                    bootstrap = entry.getKey();
                    break;
                }
            }
            roots.add(bootstrap.isBlank() ? controlled.keySet().iterator().next() : bootstrap);
        }

        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String id : controlled.keySet()) adjacency.put(id, new LinkedHashSet<>());
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        for (GalaxyMapLink link : GalaxyTopology.effectiveLinks(world, ownerId, map.links())) {
            if (link == null || !adjacency.containsKey(link.fromSystemId()) || !adjacency.containsKey(link.toSystemId())) continue;
            adjacency.get(link.fromSystemId()).add(link.toSystemId());
            adjacency.get(link.toSystemId()).add(link.fromSystemId());
        }

        Set<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!reachable.add(id)) continue;
            for (String next : adjacency.getOrDefault(id, Set.of())) {
                if (!reachable.contains(next)) queue.addLast(next);
            }
        }

        Map<String, StrategicSupplyState> result = new LinkedHashMap<>();
        for (Map.Entry<String, WorldSystemState> entry : controlled.entrySet()) {
            String id = entry.getKey();
            if (reachable.contains(id)) result.put(id, StrategicSupplyState.SUPPLIED);
            else if (StrategicInfrastructureRules.hasLogisticsNode(entry.getValue().bases.values(), ownerId)) {
                result.put(id, StrategicSupplyState.STRAINED);
            } else result.put(id, StrategicSupplyState.ISOLATED);
        }
        return Map.copyOf(result);
    }

    private static boolean hasCommandNode(WorldSystemState state, String ownerId) {
        for (Base base : state.bases.values()) {
            if (base != null && base.hp > 0 && ownerId.equals(base.playerId)
                    && StrategicInfrastructureRules.isCommandType(base.typeId)) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record CacheEntry(long capturedAtNanos, Map<String, StrategicSupplyState> states) { }
}
