package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

record GalaxyInstanceSpec(
        String id,
        String templateId,
        SystemLifetime lifetime,
        String initialControllerId
) { }

enum GalaxyLinkKind { PERMANENT, WANDERING }

record GalaxyLinkSpec(String fromSystemId, String toSystemId, GalaxyLinkKind kind) {
    GalaxyLinkSpec(String fromSystemId, String toSystemId) {
        this(fromSystemId, toSystemId, GalaxyLinkKind.PERMANENT);
    }

    GalaxyLinkSpec {
        kind = kind == null ? GalaxyLinkKind.PERMANENT : kind;
    }
}

record GalaxyPlan(
        int copiesPerTemplate,
        String entrySystemId,
        List<GalaxyInstanceSpec> systems,
        List<GalaxyLinkSpec> links
) { }

final class GalaxyPlanner {
    private static final long COMPOSITION_SALT = 0x434F4D504F53454CL;
    private static final long TOPOLOGY_SALT = 0x544F504F4C4F4759L;
    private static final int MAX_PERMANENT_LINKS = 192;

    private GalaxyPlanner() { }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies) {
        return standard(primaryTemplateId, requestedCopies, 0L, GalaxyTopologyRules.load());
    }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies, long galaxySeed) {
        return standard(primaryTemplateId, requestedCopies, galaxySeed, GalaxyTopologyRules.load());
    }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies, long galaxySeed,
                               GalaxyTopologyRules topology) {
        GalaxyGenerationSettings generation = GalaxyRuntimeOptions.generationSettings();
        if (generation.procedural()) {
            long effectiveSeed = GalaxyRuntimeOptions.generationSeed(galaxySeed);
            return procedural(primaryTemplateId, generation, effectiveSeed, topology);
        }
        return legacyStandard(primaryTemplateId, requestedCopies, galaxySeed, topology);
    }

    static GalaxyPlan procedural(String primaryTemplateId, GalaxyGenerationSettings generation, long galaxySeed) {
        return procedural(primaryTemplateId, generation, galaxySeed, GalaxyTopologyRules.load());
    }

    static GalaxyPlan procedural(String primaryTemplateId, GalaxyGenerationSettings generation, long galaxySeed,
                                 GalaxyTopologyRules topology) {
        if (generation == null) throw new IllegalArgumentException("Galaxy generation settings are required.");
        int targetCount = Math.max(GalaxyGenerationSettings.MIN_SYSTEMS,
                Math.min(GalaxyGenerationSettings.MAX_SYSTEMS, generation.targetSystemCount()));
        List<StarSystemDefinition> selected = selectTemplates(primaryTemplateId, generation, targetCount, galaxySeed);
        List<GalaxyInstanceSpec> systems = instantiate(selected);
        List<GalaxyLinkSpec> fixedLinks = proceduralLinks(systems, generation, galaxySeed);
        int wanderingPairs = topology == null ? GalaxyTopologyRules.DEFAULT_WANDERING_PAIRS : topology.wanderingWormholePairs();
        List<GalaxyLinkSpec> links = WanderingWormholePlanner.add(systems, fixedLinks, wanderingPairs, galaxySeed);
        String entry = systems.isEmpty() ? StarSystems.DEFAULT_SYSTEM_ID : systems.get(0).id();
        return new GalaxyPlan(1, entry, List.copyOf(systems), List.copyOf(links));
    }

    private static GalaxyPlan legacyStandard(String primaryTemplateId, int requestedCopies, long galaxySeed,
                                             GalaxyTopologyRules topology) {
        int copies = Math.max(1, Math.min(2, requestedCopies));
        List<StarSystemDefinition> templates = orderedTemplates(primaryTemplateId);
        List<GalaxyInstanceSpec> systems = new ArrayList<>();
        for (int copy = 1; copy <= copies; copy++) {
            for (StarSystemDefinition template : templates) {
                String id = copy == 1 ? template.id() : template.id() + "_" + copy;
                String initialController = StarSystems.CORSAIR_SYSTEM_ID.equals(template.id()) ? Config.CORSAIRS_ID : "";
                systems.add(new GalaxyInstanceSpec(id, template.id(), SystemLifetime.STATIC, initialController));
            }
        }

        List<GalaxyLinkSpec> fixedLinks = connectedLinks(systems);
        int wanderingPairs = topology == null ? GalaxyTopologyRules.DEFAULT_WANDERING_PAIRS : topology.wanderingWormholePairs();
        List<GalaxyLinkSpec> links = WanderingWormholePlanner.add(systems, fixedLinks, wanderingPairs, galaxySeed);
        String entry = systems.isEmpty() ? StarSystems.DEFAULT_SYSTEM_ID : systems.get(0).id();
        return new GalaxyPlan(copies, entry, List.copyOf(systems), links);
    }

    private static List<StarSystemDefinition> selectTemplates(String primaryTemplateId,
                                                              GalaxyGenerationSettings settings,
                                                              int targetCount, long seed) {
        List<StarSystemDefinition> pool = new ArrayList<>(StarSystems.staticOptions());
        if (pool.isEmpty()) return List.of();
        StarSystemDefinition primary = pool.stream()
                .filter(template -> template.id().equals(primaryTemplateId))
                .findFirst().orElse(pool.get(0));
        List<StarSystemDefinition> out = new ArrayList<>(targetCount);
        out.add(primary);

        SplittableRandom random = new SplittableRandom(mix(seed ^ COMPOSITION_SALT));
        List<StarSystemDefinition> unique = new ArrayList<>(pool);
        unique.remove(primary);
        while (out.size() < targetCount && !unique.isEmpty()) {
            StarSystemDefinition picked = weightedPick(unique, settings, random);
            out.add(picked);
            unique.remove(picked);
        }
        while (out.size() < targetCount) out.add(weightedPick(pool, settings, random));
        return List.copyOf(out);
    }

    private static StarSystemDefinition weightedPick(List<StarSystemDefinition> pool,
                                                     GalaxyGenerationSettings settings,
                                                     SplittableRandom random) {
        if (pool == null || pool.isEmpty()) throw new IllegalArgumentException("Galaxy template pool is empty.");
        double total = 0;
        for (StarSystemDefinition template : pool) total += settings.weightFor(template);
        double draw = random.nextDouble(Math.max(0.0001, total));
        for (StarSystemDefinition template : pool) {
            draw -= settings.weightFor(template);
            if (draw <= 0) return template;
        }
        return pool.get(pool.size() - 1);
    }

    private static List<GalaxyInstanceSpec> instantiate(List<StarSystemDefinition> selected) {
        Map<String,Integer> counts = new LinkedHashMap<>();
        List<GalaxyInstanceSpec> systems = new ArrayList<>();
        for (StarSystemDefinition template : selected) {
            int copy = counts.merge(template.id(), 1, Integer::sum);
            String id = copy == 1 ? template.id() : template.id() + "_" + copy;
            String initialController = StarSystems.CORSAIR_SYSTEM_ID.equals(template.id()) ? Config.CORSAIRS_ID : "";
            systems.add(new GalaxyInstanceSpec(id, template.id(), SystemLifetime.STATIC, initialController));
        }
        return List.copyOf(systems);
    }

    private static List<StarSystemDefinition> orderedTemplates(String primaryTemplateId) {
        List<StarSystemDefinition> templates = new ArrayList<>(StarSystems.staticOptions());
        templates.sort((a, b) -> {
            boolean ap = a.id().equals(primaryTemplateId);
            boolean bp = b.id().equals(primaryTemplateId);
            if (ap != bp) return ap ? -1 : 1;
            return a.id().compareTo(b.id());
        });
        return templates;
    }

    private static List<GalaxyLinkSpec> proceduralLinks(List<GalaxyInstanceSpec> systems,
                                                        GalaxyGenerationSettings settings, long seed) {
        if (systems.size() < 2) return List.of();
        List<String> order = new ArrayList<>();
        for (GalaxyInstanceSpec system : systems) order.add(system.id());
        shuffle(order, new SplittableRandom(mix(seed ^ TOPOLOGY_SALT)));

        Set<String> seen = new LinkedHashSet<>();
        List<GalaxyLinkSpec> links = new ArrayList<>();
        Set<String> protectedFrontier = new LinkedHashSet<>();
        SplittableRandom random = new SplittableRandom(mix(seed ^ TOPOLOGY_SALT ^ 0x5EED5EEDL));

        switch (settings.topologyStyle()) {
            case RING -> ringBackbone(order, links, seen);
            case CLUSTERED -> clusteredBackbone(order, links, seen);
            case HUBS -> hubBackbone(order, links, seen);
            case FRONTIER -> frontierBackbone(order, settings.frontierFrequency(), links, seen,
                    protectedFrontier, random);
            case DENSE, MIXED -> randomTreeBackbone(order, links, seen, random);
        }

        int target = targetPermanentEdges(order.size(), settings);
        addExtraEdges(order, target, links, seen, protectedFrontier, settings.topologyStyle(), random);
        return List.copyOf(links);
    }

    private static void ringBackbone(List<String> order, List<GalaxyLinkSpec> links, Set<String> seen) {
        if (order.size() == 2) {
            addLink(links, seen, order.get(0), order.get(1));
            return;
        }
        for (int i = 0; i < order.size(); i++) addLink(links, seen, order.get(i), order.get((i + 1) % order.size()));
    }

    private static void clusteredBackbone(List<String> order, List<GalaxyLinkSpec> links, Set<String> seen) {
        int clusterSize = Math.max(3, (int)Math.round(Math.sqrt(order.size())));
        List<String> heads = new ArrayList<>();
        for (int start = 0; start < order.size(); start += clusterSize) {
            int end = Math.min(order.size(), start + clusterSize);
            heads.add(order.get(start));
            for (int i = start + 1; i < end; i++) addLink(links, seen, order.get(i - 1), order.get(i));
            if (end - start >= 3) addLink(links, seen, order.get(start), order.get(end - 1));
        }
        for (int i = 1; i < heads.size(); i++) addLink(links, seen, heads.get(i - 1), heads.get(i));
    }

    private static void hubBackbone(List<String> order, List<GalaxyLinkSpec> links, Set<String> seen) {
        int hubCount = Math.max(1, Math.min(4, (int)Math.ceil(order.size() / 12.0)));
        for (int i = 1; i < hubCount; i++) addLink(links, seen, order.get(i - 1), order.get(i));
        for (int i = hubCount; i < order.size(); i++) addLink(links, seen, order.get(i), order.get(i % hubCount));
        if (hubCount == 1 && order.size() > 2) addLink(links, seen, order.get(1), order.get(2));
    }

    private static void frontierBackbone(List<String> order, double frontierFrequency,
                                         List<GalaxyLinkSpec> links, Set<String> seen,
                                         Set<String> protectedFrontier, SplittableRandom random) {
        int frontierCount = Math.max(1, Math.min(order.size() - 2,
                (int)Math.round(order.size() * Math.max(0.10, frontierFrequency))));
        int coreCount = order.size() - frontierCount;
        for (int i = 1; i < coreCount; i++) addLink(links, seen, order.get(i - 1), order.get(i));
        for (int i = coreCount; i < order.size(); i++) {
            String leaf = order.get(i);
            protectedFrontier.add(leaf);
            addLink(links, seen, leaf, order.get(random.nextInt(coreCount)));
        }
    }

    private static void randomTreeBackbone(List<String> order, List<GalaxyLinkSpec> links, Set<String> seen,
                                           SplittableRandom random) {
        for (int i = 1; i < order.size(); i++) addLink(links, seen, order.get(i), order.get(random.nextInt(i)));
    }

    private static int targetPermanentEdges(int systems, GalaxyGenerationSettings settings) {
        if (systems < 2) return 0;
        double density = settings.permanentConnectivityDensity();
        double multiplier = switch (settings.topologyStyle()) {
            case RING -> 0.35 + density * 0.75;
            case CLUSTERED -> 0.45 + density * 1.10;
            case HUBS -> 0.30 + density * 0.85;
            case FRONTIER -> 0.10 + density * 0.35;
            case DENSE -> 1.00 + density * 2.25;
            case MIXED -> 0.55 + density * 1.35;
        };
        int minimum = settings.topologyStyle() == GalaxyTopologyStyle.RING && systems > 2 ? systems : systems - 1;
        int target = Math.max(minimum, (systems - 1) + (int)Math.round(systems * multiplier));
        int completeGraph = systems * (systems - 1) / 2;
        return Math.min(MAX_PERMANENT_LINKS, Math.min(completeGraph, target));
    }

    private static void addExtraEdges(List<String> order, int target, List<GalaxyLinkSpec> links, Set<String> seen,
                                      Set<String> protectedFrontier, GalaxyTopologyStyle style,
                                      SplittableRandom random) {
        if (links.size() >= target) return;
        List<Pair> candidates = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            for (int j = i + 1; j < order.size(); j++) {
                String a = order.get(i);
                String b = order.get(j);
                if (seen.contains(key(a, b))) continue;
                if (style == GalaxyTopologyStyle.FRONTIER
                        && (protectedFrontier.contains(a) || protectedFrontier.contains(b))) continue;
                candidates.add(new Pair(a, b));
            }
        }
        shufflePairs(candidates, random);
        for (Pair pair : candidates) {
            if (links.size() >= target) break;
            addLink(links, seen, pair.a(), pair.b());
        }
    }

    private static List<GalaxyLinkSpec> connectedLinks(List<GalaxyInstanceSpec> systems) {
        if (systems.size() < 2) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<GalaxyLinkSpec> links = new ArrayList<>();
        for (int i = 0; i < systems.size(); i++) addLink(links, seen, systems.get(i).id(), systems.get((i + 1) % systems.size()).id());
        for (int i = 0; i < systems.size(); i += 4) addLink(links, seen, systems.get(i).id(), systems.get((i + Math.min(4, systems.size() - 1)) % systems.size()).id());
        return List.copyOf(links);
    }

    private static void addLink(List<GalaxyLinkSpec> links, Set<String> seen, String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        String a = from.compareTo(to) <= 0 ? from : to;
        String b = from.compareTo(to) <= 0 ? to : from;
        if (seen.add(key(a, b))) links.add(new GalaxyLinkSpec(a, b, GalaxyLinkKind.PERMANENT));
    }

    private static String key(String from, String to) {
        return from.compareTo(to) <= 0 ? from + "->" + to : to + "->" + from;
    }

    private static void shuffle(List<String> values, SplittableRandom random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String value = values.get(i);
            values.set(i, values.get(j));
            values.set(j, value);
        }
    }

    private static void shufflePairs(List<Pair> values, SplittableRandom random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Pair value = values.get(i);
            values.set(i, values.get(j));
            values.set(j, value);
        }
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private record Pair(String a, String b) { }
}
