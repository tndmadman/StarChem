package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only next-action guidance based on authoritative catalogs and owned
 * visible inventories. Never grants unlocks or looks up undiscovered nodes.
 */
final class ProductionRequirementGuide {
    private static final int MAX_DEPTH = 8;

    private ProductionRequirementGuide() { }

    static String nextAction(World world, String playerId, List<Cost> costs) {
        if (world == null || playerId == null || costs == null || costs.isEmpty()) return "";
        EnumMap<Material, Double> held = new EnumMap<>(Material.class);
        Set<String> stations = new HashSet<>();
        for (Base base : world.bases.values()) {
            if (!playerId.equals(base.playerId) || base.hp <= 0) continue;
            stations.add(base.typeId);
            for (var item : base.inventory.entrySet()) {
                held.merge(item.getKey(), Math.max(0, item.getValue()), Double::sum);
            }
        }
        for (Cost cost : costs) {
            double shortage = cost.amount() - held.getOrDefault(cost.material(), 0.0);
            if (shortage <= 0.001) continue;
            List<String> chain = new ArrayList<>();
            return explain(world, playerId, cost.material(), shortage,
                    held, stations, new HashSet<>(), chain, 0);
        }
        return "All listed materials are owned. Transfer them to the producing station and queue the job.";
    }

    private static String explain(World world, String playerId, Material material, double amount,
                                  EnumMap<Material, Double> held, Set<String> stations,
                                  Set<Material> visiting, List<String> chain, int depth) {
        if (depth >= MAX_DEPTH || !visiting.add(material)) {
            return "Check the recipe chain for " + material.label + " in the Resource Catalog.";
        }
        try {
            if (material.raw) {
                return "Mine or obtain " + whole(amount) + " " + material.label
                        + " and deliver it to an owned station.";
            }
            CraftableItem recipe = CraftingRules.preferredForOutput(material);
            if (recipe == null) {
                return "Find an acquisition route for " + material.label
                        + " in the Resource Catalog (not a standard manufacturing recipe).";
            }
            if (!ResearchPolicy.unlocked(world, playerId, ResearchUnlockKind.CRAFTABLE, recipe.id)) {
                return "Research " + ResearchPolicy.missingUnlockLabel(world, playerId,
                        ResearchUnlockKind.CRAFTABLE, recipe.id)
                        + " to unlock " + recipe.name + ".";
            }
            for (String researchId : recipe.requiresResearch) {
                if (!world.hasResearch(playerId, researchId)) {
                    ResearchTopic topic = ResearchRules.topic(researchId);
                    return "Research " + (topic == null ? researchId : topic.name)
                            + " to manufacture " + recipe.name + ".";
                }
            }
            String station = "";
            for (String allowed : recipe.stationTypes) {
                if (stations.contains(allowed)) {
                    station = allowed;
                    break;
                }
            }
            if (station.isBlank()) {
                return "Construct a compatible station (" + String.join(", ", recipe.stationTypes)
                        + ") to manufacture " + recipe.name + ".";
            }
            int batches = (int) Math.ceil(amount / recipe.outputAmount);
            for (Cost input : recipe.requiredResources) {
                double needed = input.amount() * batches
                        - held.getOrDefault(input.material(), 0.0);
                if (needed > 0.001) {
                    chain.add(recipe.name);
                    String next = explain(world, playerId, input.material(), needed,
                            held, stations, visiting, chain, depth + 1);
                    return String.join(" → ", chain) + " → " + next;
                }
            }
            return "Queue " + batches + " batch(es) of " + recipe.name
                    + " at " + station + " and transport its output to the target station.";
        } finally {
            visiting.remove(material);
        }
    }

    private static String whole(double value) {
        return Long.toString((long) Math.ceil(value - 1e-6));
    }
}
