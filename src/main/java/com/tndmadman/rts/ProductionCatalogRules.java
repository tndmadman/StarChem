package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.Set;

/** Shared discovery and display-state rules for the player-facing production catalog. */
final class ProductionCatalogRules {
    private ProductionCatalogRules() { }

    /**
     * Return every hull referenced by a registered station production definition.
     *
     * This deliberately does not depend on the stations the current player happens to own. The
     * Production Manager can therefore show legitimate outputs before the player owns the station
     * that can manufacture them, while station routing still decides whether the choice is usable.
     */
    static Set<String> buildableHullIds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (BaseType station : Rules.BASES.values()) {
            if (station != null) out.addAll(station.buildableShips);
        }
        return Set.copyOf(out);
    }

    /** Return every station package referenced by a registered station production definition. */
    static Set<String> buildableStationPackageIds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (BaseType station : Rules.BASES.values()) {
            if (station != null) out.addAll(station.basePackages);
        }
        return Set.copyOf(out);
    }

    /**
     * Current player-level research display state. This is intentionally centralized here so the
     * Production Manager does not grow another copy of research-state rules.
     */
    static String researchLockedReason(World world, String playerId, ResearchTopic topic) {
        if (world == null || topic == null) return "Research unavailable";
        if (world.hasResearch(playerId, topic.id)) return "Completed";
        if (ProductionSystem.researchQueued(world, playerId, topic.id)) return "Already queued";
        String prerequisite = ResearchRules.missingPrerequisite(world, playerId, topic);
        return prerequisite.isBlank() ? "" : "Requires " + prerequisite;
    }
}
