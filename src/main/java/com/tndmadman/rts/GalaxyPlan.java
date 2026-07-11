package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record GalaxyInstanceSpec(
        String id,
        String templateId,
        SystemLifetime lifetime,
        String initialControllerId
) { }

record GalaxyLinkSpec(String fromSystemId, String toSystemId) { }

record GalaxyPlan(
        int copiesPerTemplate,
        String entrySystemId,
        List<GalaxyInstanceSpec> systems,
        List<GalaxyLinkSpec> links
) { }

final class GalaxyPlanner {
    private GalaxyPlanner() { }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies) {
        return standard(primaryTemplateId, requestedCopies, 0L, GalaxyTopologyRules.load());
    }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies, long galaxySeed) {
        return standard(primaryTemplateId, requestedCopies, galaxySeed, GalaxyTopologyRules.load());
    }

    static GalaxyPlan standard(String primaryTemplateId, int requestedCopies, long galaxySeed,
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
        if (seen.add(a + "->" + b)) links.add(new GalaxyLinkSpec(a, b));
    }
}
