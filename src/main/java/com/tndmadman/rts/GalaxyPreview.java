package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record GalaxyPreview(
        long seed,
        GalaxyGenerationSettings settings,
        GalaxyMapSnapshot snapshot,
        List<String> startRegionSystemIds,
        Map<String,Integer> templateCounts
) {
    static GalaxyPreview generate(String primaryTemplateId, GalaxyGenerationSettings settings,
                                  long seed, int requestedStarts) {
        GalaxyPlan plan = GalaxyPlanner.procedural(primaryTemplateId, settings, seed);
        GalaxyMapSnapshot snapshot = snapshot(plan);
        List<String> starts = startRegions(plan, Math.max(1, requestedStarts), settings.startingSeparation());
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (GalaxyInstanceSpec system : plan.systems()) counts.merge(system.templateId(), 1, Integer::sum);
        return new GalaxyPreview(seed, settings, snapshot, starts, Map.copyOf(counts));
    }

    private static GalaxyMapSnapshot snapshot(GalaxyPlan plan) {
        List<GalaxyMapSystem> systems = new ArrayList<>();
        for (GalaxyInstanceSpec spec : plan.systems()) {
            StarSystemDefinition definition = StarSystems.get(spec.templateId());
            systems.add(new GalaxyMapSystem(
                    spec.id(), definition.name(), spec.templateId(), spec.lifetime(),
                    0, 0, 0, 0, 0,
                    spec.id().equals(plan.entrySystemId()), false, spec.lifetime() != SystemLifetime.STATIC,
                    "", "Neutral", SystemControlStatus.NEUTRAL, 0, 0x8A96A3));
        }
        List<GalaxyMapLink> links = new ArrayList<>();
        for (GalaxyLinkSpec link : plan.links()) {
            if (link.kind() == GalaxyLinkKind.PERMANENT) {
                links.add(new GalaxyMapLink(link.fromSystemId(), link.toSystemId()));
            }
        }
        return new GalaxyMapSnapshot(plan.entrySystemId(), List.copyOf(systems), List.copyOf(links));
    }

    static List<String> startRegions(GalaxyPlan plan, int requestedStarts, int minimumSeparation) {
        if (plan == null || plan.systems().isEmpty() || requestedStarts <= 0) return List.of();
        Map<String,Set<String>> graph = graph(plan);
        List<String> candidates = plan.systems().stream().map(GalaxyInstanceSpec::id).toList();
        List<String> selected = new ArrayList<>();
        selected.add(plan.entrySystemId());

        while (selected.size() < Math.min(requestedStarts, candidates.size())) {
            String best = null;
            int bestDistance = -1;
            for (String candidate : candidates) {
                if (selected.contains(candidate)) continue;
                int nearest = Integer.MAX_VALUE;
                for (String existing : selected) nearest = Math.min(nearest, distance(graph, candidate, existing));
                if (nearest > bestDistance || nearest == bestDistance && (best == null || candidate.compareTo(best) < 0)) {
                    best = candidate;
                    bestDistance = nearest;
                }
            }
            if (best == null) break;
            if (bestDistance < minimumSeparation) {
                throw new IllegalArgumentException("Galaxy cannot place " + requestedStarts
                        + " start regions at graph separation " + minimumSeparation + ".");
            }
            selected.add(best);
        }
        return List.copyOf(selected);
    }

    private static Map<String,Set<String>> graph(GalaxyPlan plan) {
        Map<String,Set<String>> graph = new HashMap<>();
        for (GalaxyInstanceSpec system : plan.systems()) graph.put(system.id(), new LinkedHashSet<>());
        for (GalaxyLinkSpec link : plan.links()) {
            if (link.kind() != GalaxyLinkKind.PERMANENT) continue;
            graph.computeIfAbsent(link.fromSystemId(), ignored -> new LinkedHashSet<>()).add(link.toSystemId());
            graph.computeIfAbsent(link.toSystemId(), ignored -> new LinkedHashSet<>()).add(link.fromSystemId());
        }
        return graph;
    }

    private static int distance(Map<String,Set<String>> graph, String from, String to) {
        if (from.equals(to)) return 0;
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<NodeDistance> queue = new ArrayDeque<>();
        queue.add(new NodeDistance(from, 0));
        while (!queue.isEmpty()) {
            NodeDistance current = queue.removeFirst();
            if (!visited.add(current.id())) continue;
            for (String next : graph.getOrDefault(current.id(), Set.of())) {
                if (next.equals(to)) return current.distance() + 1;
                if (!visited.contains(next)) queue.addLast(new NodeDistance(next, current.distance() + 1));
            }
        }
        return Integer.MAX_VALUE / 4;
    }

    private record NodeDistance(String id, int distance) { }
}
