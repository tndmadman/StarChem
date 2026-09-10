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

    /** Regression seam for topology tests. Uses the exact graph derivation used by production. */
    static Map<String, StrategicSupplyState> deriveStatesForTest(Set<String> controlledIds,
                                                                  Set<String> roots,
                                                                  Set<String> supportedIds,
                                                                  List<GalaxyMapLink> links) {
        return deriveStates(controlledIds, roots, supportedIds, links);
    }

    private static Map<String, StrategicSupplyState> calculate(World world, String ownerId) {
        List<WorldSystemState> systems = world.policySystemStates();
        Map<String, WorldSystemState> controlled = new LinkedHashMap<>();
        Set<String> roots = new LinkedHashSet<>();
        Set<String> locallySupported = new LinkedHashSet<>();
        for (WorldSystemState system : systems) {
            if (system == null || !ownerId.equals(system.control.controllerId())) continue;
            if (system.control.status() != SystemControlStatus.CONTROLLED
                    && system.control.status() != SystemControlStatus.PROTECTED) continue;
            controlled.put(system.id, system);
            // Protected homes are the canonical strategic supply roots. A command outpost helps an
            // isolated pocket survive, but it must not magically bypass a blockade and become a
            // fully supplied root of its own.
            if (system.control.status() == SystemControlStatus.PROTECTED) roots.add(system.id);
            if (StrategicInfrastructureRules.hasLogisticsNode(system.bases.values(), ownerId)) {
                locallySupported.add(system.id);
            }
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

        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        return deriveStates(controlled.keySet(), roots, locallySupported,
                GalaxyTopology.effectiveLinks(world, ownerId, map.links()));
    }

    private static Map<String, StrategicSupplyState> deriveStates(Set<String> controlledIds,
                                                                   Set<String> roots,
                                                                   Set<String> supportedIds,
                                                                   List<GalaxyMapLink> links) {
        Set<String> controlled = new LinkedHashSet<>();
        if (controlledIds != null) {
            for (String id : controlledIds) {
                String cleanId = clean(id);
                if (!cleanId.isBlank()) controlled.add(cleanId);
            }
        }
        if (controlled.isEmpty()) return Map.of();

        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (String id : controlled) adjacency.put(id, new LinkedHashSet<>());
        if (links != null) {
            for (GalaxyMapLink link : links) {
                if (link == null) continue;
                String from = clean(link.fromSystemId());
                String to = clean(link.toSystemId());
                if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) continue;
                adjacency.get(from).add(to);
                adjacency.get(to).add(from);
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        if (roots != null) {
            for (String root : roots) {
                String cleanRoot = clean(root);
                if (controlled.contains(cleanRoot)) queue.addLast(cleanRoot);
            }
        }
        Set<String> reachable = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!reachable.add(id)) continue;
            for (String next : adjacency.getOrDefault(id, Set.of())) {
                if (!reachable.contains(next)) queue.addLast(next);
            }
        }

        Set<String> supported = new LinkedHashSet<>();
        if (supportedIds != null) {
            for (String id : supportedIds) {
                String cleanId = clean(id);
                if (controlled.contains(cleanId)) supported.add(cleanId);
            }
        }

        Map<String, StrategicSupplyState> result = new LinkedHashMap<>();
        for (String id : controlled) {
            if (reachable.contains(id)) result.put(id, StrategicSupplyState.SUPPLIED);
            else if (supported.contains(id)) result.put(id, StrategicSupplyState.STRAINED);
            else result.put(id, StrategicSupplyState.ISOLATED);
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
