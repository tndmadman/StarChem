package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalog-only economic bill of materials. Does not grant items, advance a
 * world, or bypass research authorization. The progression reachability
 * validator checks unlock order; this checks cumulative quantities and batches.
 */
public final class StarterEconomyBudgetValidator {
    private StarterEconomyBudgetValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
    }

    static void validateOrThrow() {
        Budget budget = new Budget();
        budget.consume(Rules.ship("prospector").buildCost);     // second miner
        budget.consume(Rules.ship("station_builder").buildCost);
        budget.consume(Rules.base("manufacturing").buildCost);
        Snapshot factory = budget.snapshot();
        budget.consume(Rules.ship("station_builder").buildCost);
        budget.consume(Rules.base("laboratory").buildCost);
        Snapshot lab = budget.snapshot();
        budget.consume(ResearchRules.topic("advanced_industry").requiredResources);
        Snapshot firstResearch = budget.snapshot();
        budget.consume(ResearchRules.topic("logistics_engineering").requiredResources);
        budget.consume(ResearchRules.topic("autonomous_logistics").requiredResources);
        budget.consume(Rules.ship("station_builder").buildCost);
        budget.consume(Rules.base("shipyard").buildCost);
        budget.consume(Rules.ship("hauler").buildCost);
        // Reserve reactor consumption throughout those three research topics.
        StationFuelRequirement fuel = StationFuelRules.requirement("laboratory");
        require(fuel != null && fuel.material() == Material.FUEL,
                "Lab fuel rules must be included in the cost audit");
        double researchSeconds = ResearchRules.topic("advanced_industry").timeSeconds
                + ResearchRules.topic("logistics_engineering").timeSeconds
                + ResearchRules.topic("autonomous_logistics").timeSeconds;
        budget.consume(Material.FUEL, researchSeconds * fuel.perSecond());
        Snapshot hauler = budget.snapshot();

        Set<Material> accessible = StarterProgressionValidator.starterMaterials();
        for (Material raw : budget.raw.keySet()) {
            require(raw.raw, "Budget stopped at nonraw material " + raw);
            require(accessible.contains(raw),
                    "First Hauler requires an inaccessible Sol Standard raw resource " + raw);
        }
        require(factory.batches == 0, "Factory must be constructible from unprocessed starter ore");
        require(lab.batches > 0 && lab.craftSeconds > 0,
                "Lab must require legitimate component manufacturing");
        require(firstResearch.batches >= lab.batches,
                "Research budget cannot use fewer batches than Lab construction");
        require(hauler.batches > lab.batches,
                "Hauler must require genuine additional logistics manufacturing");
        require(budget.crafted.getOrDefault(Material.RESEARCH_MATRIX, 0) >= 2,
                "First Lab requires at least two Research Matrix batches");
        require(budget.crafted.getOrDefault(Material.FUEL, 0) >= 4,
                "Research, Hauler components and actual Lab consumption require multiple fuel batches");

        System.out.println("StarChem first Manufacturing budget: " + factory);
        System.out.println("StarChem first Lab budget: " + lab);
        System.out.println("StarChem first Advanced Industry budget: " + firstResearch);
        System.out.println("StarChem first Hauler budget (including research Fuel burn): " + hauler);
        System.out.println("StarChem starter economy bill-of-materials validation passed.");
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalStateException(reason);
    }

    private static final class Budget {
        final EnumMap<Material, Double> stocks = new EnumMap<>(Material.class);
        final EnumMap<Material, Double> raw = new EnumMap<>(Material.class);
        final EnumMap<Material, Integer> crafted = new EnumMap<>(Material.class);
        double craftSeconds;

        void consume(List<Cost> costs) {
            for (Cost cost : costs) consume(cost.material(), cost.amount());
        }

        void consume(Material material, double amount) {
            consume(material, amount, EnumSet.noneOf(Material.class));
        }

        private void consume(Material material, double amount, Set<Material> visiting) {
            if (amount <= 0) return;
            if (material.raw) {
                raw.merge(material, amount, Double::sum);
                return;
            }
            require(visiting.add(material), "Crafting cost cycle involving " + material);
            try {
                double stocked = stocks.getOrDefault(material, 0.0);
                if (stocked + 1e-7 < amount) {
                    CraftableItem recipe = CraftingRules.preferredForOutput(material);
                    require(recipe != null, "Missing manufacturing recipe for " + material);
                    int batches = (int) Math.ceil((amount - stocked - 1e-7) / recipe.outputAmount);
                    require(batches > 0 && batches < 10_000,
                            "Unbounded crafting batch count for " + material);
                    for (Cost input : recipe.requiredResources) {
                        consume(input.material(), input.amount() * batches, visiting);
                    }
                    stocks.merge(material, recipe.outputAmount * batches, Double::sum);
                    crafted.merge(material, batches, Integer::sum);
                    craftSeconds += recipe.timeSeconds * batches;
                }
                stocks.merge(material, -amount, Double::sum);
                require(stocks.get(material) >= -1e-7, "Manufacturing stock underflow for " + material);
            } finally {
                visiting.remove(material);
            }
        }

        Snapshot snapshot() {
            int batches = crafted.values().stream().mapToInt(Integer::intValue).sum();
            return new Snapshot(Map.copyOf(raw), batches, Math.round(craftSeconds));
        }
    }

    record Snapshot(Map<Material, Double> raw, int batches, long craftSeconds) { }
}
