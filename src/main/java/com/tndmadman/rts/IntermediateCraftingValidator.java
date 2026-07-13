package com.tndmadman.rts;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class IntermediateCraftingValidator {
    private IntermediateCraftingValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem intermediate crafting validation passed.");
    }

    static void validateOrThrow() {
        validateMaterialMetadata();
        validateRecipes();
        validateRecipeGraph();
        validateRecipeUnlockAlignment();
        validateResearchGates();
        validateShipUnlockAlignment();
        validateBootstrap();
        validateUsage();
    }

    private static void validateMaterialMetadata() {
        require(MaterialRules.definitions().size() == Material.values().length,
                "JSON material metadata count does not match Material enum");
        for (Material material : Material.values()) {
            MaterialDefinition definition = MaterialRules.definition(material.name());
            require(definition.displayName().equals(material.label), "runtime label is stale for " + material);
            require(definition.color().equals(material.color), "runtime color is stale for " + material);
            require(definition.family() == material.family, "runtime family is stale for " + material);
            require(definition.tier() == material.tier, "runtime tier is stale for " + material);
            require(definition.raw() == material.raw, "runtime raw flag is stale for " + material);
        }
    }

    private static void validateRecipes() {
        List<CraftableItem> items = CraftingRules.all();
        require(items.size() == 60, "expected 60 JSON recipes including fuel and reclamation recipes, found " + items.size());

        Set<String> ids = new LinkedHashSet<>();
        EnumSet<Material> manufacturedOutputs = EnumSet.noneOf(Material.class);
        for (CraftableItem item : items) {
            require(ids.add(item.id), "duplicate recipe ID " + item.id);
            require(item.category != null, "recipe category is missing for " + item.id);
            require(item.stationTypes.contains("manufacturing"), "recipe is not assigned to manufacturing: " + item.id);
            require(item.outputAmount > 0, "recipe output must be positive: " + item.id);
            require(item.timeSeconds > 0, "recipe time must be positive: " + item.id);
            require(!item.requiredResources.isEmpty(), "recipe has no inputs: " + item.id);
            for (Cost cost : item.requiredResources) {
                require(cost.material() != null && cost.amount() > 0, "recipe has invalid input: " + item.id);
            }
            manufacturedOutputs.add(item.outputMaterial);
            for (String topicId : item.requiresResearch) {
                require(ResearchRules.topic(topicId) != null, "recipe references unknown research " + topicId + ": " + item.id);
            }
        }

        int manufacturedMaterials = 0;
        for (Material material : Material.values()) {
            if (material.raw || material.family == MaterialFamily.SALVAGE) continue;
            manufacturedMaterials++;
            require(manufacturedOutputs.contains(material), "manufactured material has no JSON recipe: " + material);
        }
        require(manufacturedMaterials == 57, "expected Fuel plus 56 manufactured intermediates, found " + manufacturedMaterials);
    }

    private static void validateRecipeGraph() {
        EnumSet<Material> complete = EnumSet.noneOf(Material.class);
        EnumSet<Material> visiting = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) visit(material, complete, visiting);
    }

    private static void visit(Material material, Set<Material> complete, Set<Material> visiting) {
        if (complete.contains(material)) return;
        CraftableItem recipe = CraftingRules.preferredForOutput(material);
        if (recipe == null) {
            complete.add(material);
            return;
        }
        require(visiting.add(material), "crafting cycle detected at " + material);
        for (Cost input : recipe.requiredResources) visit(input.material(), complete, visiting);
        visiting.remove(material);
        complete.add(material);
    }

    private static void validateRecipeUnlockAlignment() {
        for (CraftableItem item : CraftingRules.all()) {
            Set<String> available = declaredResearchClosure(item.requiresResearch);
            Set<String> dependencies = researchNeeded(item.requiredResources);
            require(available.containsAll(dependencies),
                    "recipe " + item.id + " is unlocked before dependency research "
                            + difference(dependencies, available));
        }
    }

    private static void validateResearchGates() {
        for (ResearchTopic topic : ResearchRules.all()) {
            Set<String> available = prerequisiteClosure(topic);
            Set<String> costResearch = researchNeeded(topic.requiredResources);
            require(available.containsAll(costResearch),
                    topic.id + " requires components gated by unavailable research "
                            + difference(costResearch, available));

            Set<String> reachableStations = reachableStations(available);
            boolean canReachResearchStation = false;
            for (String stationId : topic.stationTypes) {
                BaseType station = Rules.findBase(stationId);
                require(station != null, topic.id + " references unknown research station " + stationId);
                Set<String> stationResearch = researchNeeded(station.buildCost);
                if (available.containsAll(stationResearch) && reachableStations.contains(stationId)) {
                    canReachResearchStation = true;
                }
            }
            require(canReachResearchStation,
                    topic.id + " cannot reach a usable research station before the topic is completed");

            Set<Material> salvage = salvageNeeded(topic.requiredResources);
            if (!salvage.isEmpty()) {
                require(hasAvailableArmedShip(available),
                        topic.id + " requires salvage " + salvage + " before any offensive ship is available");
                require(hasAvailableSalvageCollector(available),
                        topic.id + " requires salvage " + salvage + " before any tractor-equipped collector is available");
            }
        }
    }

    private static void validateShipUnlockAlignment() {
        for (ShipType ship : Rules.SHIPS.values()) {
            Set<String> dependencies = researchNeeded(ship.buildCost);
            boolean gated = false;
            boolean aligned = dependencies.isEmpty();
            Set<String> declared = new LinkedHashSet<>();
            for (ResearchTopic topic : ResearchRules.all()) {
                if (!topic.unlocks.ships.contains(ship.id)) continue;
                gated = true;
                Set<String> gate = declaredResearchClosure(List.of(topic.id));
                declared.addAll(gate);
                if (gate.containsAll(dependencies)) aligned = true;
            }
            if (!gated) declared.clear();
            require(aligned,
                    "ship " + ship.id + " is available before component research "
                            + difference(dependencies, declared));
        }
    }

    private static Set<String> reachableStations(Set<String> availableResearch) {
        Set<String> reachable = new LinkedHashSet<>();
        reachable.add(Rules.DEFAULT_BASE);
        boolean changed;
        do {
            changed = false;
            for (String stationId : Set.copyOf(reachable)) {
                BaseType producer = Rules.findBase(stationId);
                if (producer == null) continue;
                for (String packageId : producer.basePackages) {
                    BaseType packaged = Rules.findBase(packageId);
                    if (packaged == null || reachable.contains(packageId)) continue;
                    if (!availableResearch.containsAll(researchNeeded(packaged.buildCost))) continue;
                    reachable.add(packageId);
                    changed = true;
                }
            }
        } while (changed);
        return reachable;
    }

    private static Set<String> declaredResearchClosure(List<String> topicIds) {
        Set<String> available = new LinkedHashSet<>();
        for (String topicId : topicIds) {
            ResearchTopic topic = ResearchRules.topic(topicId);
            require(topic != null, "unknown research topic " + topicId);
            available.addAll(prerequisiteClosure(topic));
            available.add(topicId);
        }
        return available;
    }

    private static Set<String> prerequisiteClosure(ResearchTopic topic) {
        Set<String> available = new LinkedHashSet<>();
        for (String required : topic.requires) {
            collectPrerequisite(required, available, new LinkedHashSet<>());
        }
        return available;
    }

    private static void collectPrerequisite(String topicId, Set<String> complete, Set<String> visiting) {
        if (complete.contains(topicId)) return;
        require(visiting.add(topicId), "research dependency cycle detected at " + topicId);
        ResearchTopic topic = ResearchRules.topic(topicId);
        require(topic != null, "unknown research prerequisite " + topicId);
        for (String required : topic.requires) collectPrerequisite(required, complete, visiting);
        visiting.remove(topicId);
        complete.add(topicId);
    }

    private static Set<String> researchNeeded(List<Cost> costs) {
        Set<String> required = new LinkedHashSet<>();
        for (Cost cost : costs) {
            required.addAll(researchNeeded(cost.material(), EnumSet.noneOf(Material.class)));
        }
        return required;
    }

    private static Set<String> researchNeeded(Material material, Set<Material> visiting) {
        require(visiting.add(material), "crafting dependency cycle detected while resolving " + material);
        try {
            List<CraftableItem> recipes = CraftingRules.recipesForOutput(material);
            if (recipes.isEmpty()) return Set.of();
            Set<String> best = null;
            for (CraftableItem recipe : recipes) {
                Set<String> option = new LinkedHashSet<>(recipe.requiresResearch);
                for (Cost input : recipe.requiredResources) {
                    option.addAll(researchNeeded(input.material(), visiting));
                }
                if (best == null || option.size() < best.size()) best = option;
            }
            return best == null ? Set.of() : Set.copyOf(best);
        } finally {
            visiting.remove(material);
        }
    }

    private static Set<Material> salvageNeeded(List<Cost> costs) {
        Set<Material> required = EnumSet.noneOf(Material.class);
        for (Cost cost : costs) {
            required.addAll(salvageNeeded(cost.material(), EnumSet.noneOf(Material.class)));
        }
        return required;
    }

    private static Set<Material> salvageNeeded(Material material, Set<Material> visiting) {
        require(visiting.add(material), "crafting dependency cycle detected while resolving salvage for " + material);
        try {
            List<CraftableItem> recipes = CraftingRules.recipesForOutput(material);
            if (recipes.isEmpty()) {
                return material.family == MaterialFamily.SALVAGE ? EnumSet.of(material) : Set.of();
            }
            Set<Material> best = null;
            for (CraftableItem recipe : recipes) {
                Set<Material> option = EnumSet.noneOf(Material.class);
                for (Cost input : recipe.requiredResources) {
                    option.addAll(salvageNeeded(input.material(), visiting));
                }
                if (best == null || option.size() < best.size()) best = option;
            }
            return best == null ? Set.of() : Set.copyOf(best);
        } finally {
            visiting.remove(material);
        }
    }

    private static boolean hasAvailableArmedShip(Set<String> availableResearch) {
        for (ShipType ship : Rules.SHIPS.values()) {
            if (shipAvailableBefore(ship.id, availableResearch) && WeaponRules.armed(ship)) return true;
        }
        return false;
    }

    private static boolean hasAvailableSalvageCollector(Set<String> availableResearch) {
        for (ShipType ship : Rules.SHIPS.values()) {
            if (shipAvailableBefore(ship.id, availableResearch)
                    && ship.tractorBeamCount > 0 && ship.cargoCapacity > 0) return true;
        }
        return false;
    }

    private static boolean shipAvailableBefore(String shipId, Set<String> availableResearch) {
        boolean gated = false;
        for (ResearchTopic topic : ResearchRules.all()) {
            if (!topic.unlocks.ships.contains(shipId)) continue;
            gated = true;
            if (availableResearch.contains(topic.id)) return true;
        }
        return !gated;
    }

    private static Set<String> difference(Set<String> required, Set<String> available) {
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(available);
        return missing;
    }

    private static void validateBootstrap() {
        ShipType prospector = Rules.ship("prospector");
        ShipType deployer = Rules.ship("station_builder");
        BaseType outpost = Rules.base("outpost");
        BaseType manufacturing = Rules.base("manufacturing");
        BaseType laboratory = Rules.base("laboratory");
        require(prospector != null && deployer != null && outpost != null && manufacturing != null && laboratory != null,
                "starter economy definitions are missing");
        require(allRaw(prospector.buildCost), "Prospector must remain raw-resource craftable");
        require(allRaw(deployer.buildCost), "Deployer must remain raw-resource craftable");
        require(deployer.baseBuilder, "Deployer must remain capable of placing station packages");
        require(allRaw(manufacturing.buildCost), "Manufacturing Plant package must remain raw-resource craftable");
        require(outpost.basePackages.contains("manufacturing"), "Outpost must be able to bootstrap a Manufacturing Plant");
        require(outpost.basePackages.contains("laboratory"), "Outpost must be able to bootstrap a Research Lab");
        require(researchNeeded(laboratory.buildCost).isEmpty(),
                "Research Lab package must not require research-gated components");
    }

    private static boolean allRaw(List<Cost> costs) {
        for (Cost cost : costs) if (!cost.material().raw) return false;
        return true;
    }

    private static void validateUsage() {
        EnumSet<Material> used = EnumSet.noneOf(Material.class);
        for (CraftableItem item : CraftingRules.all()) {
            for (Cost cost : item.requiredResources) used.add(cost.material());
        }
        for (ShipType ship : Rules.SHIPS.values()) for (Cost cost : ship.buildCost) used.add(cost.material());
        for (BaseType base : Rules.BASES.values()) for (Cost cost : base.buildCost) used.add(cost.material());
        for (ResearchTopic topic : ResearchRules.all()) for (Cost cost : topic.requiredResources) used.add(cost.material());

        for (Material material : Material.values()) {
            if (material.raw || material == Material.FUEL || material.family == MaterialFamily.SALVAGE) continue;
            require(used.contains(material), "manufactured material is not used by any recipe or final cost: " + material);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
