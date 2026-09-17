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
     * Return the same effective research block that the authoritative queue would apply at an owned
     * compatible station. A topic is display-ready when at least one compatible station would accept
     * it, including the queue rule that allows a dependency already queued at that same station.
     */
    static String researchLockedReason(World world, String playerId, ResearchTopic topic) {
        if (world == null || playerId == null || playerId.isBlank() || topic == null) return "Research unavailable";
        if (world.hasResearch(playerId, topic.id)) return "Completed";
        if (ProductionSystem.researchQueued(world, playerId, topic.id)) return "Already queued";

        String firstBlockedReason = "";
        boolean compatibleStation = false;
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || !playerId.equals(base.playerId) || !topic.canResearchAt(base.typeId)) {
                continue;
            }
            compatibleStation = true;
            String blocked = ResearchPolicy.blockedResearchReason(world, base, topic);
            if (blocked.isBlank()) return "";
            if (firstBlockedReason.isBlank()) firstBlockedReason = blocked;
        }
        if (!compatibleStation) return "";
        return sentenceCase(firstBlockedReason);
    }

    private static String sentenceCase(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() == 1) return value.toUpperCase();
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
