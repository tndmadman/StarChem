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
        validateResearchGates();
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

    private static void validateResearchGates() {
        for (ResearchTopic topic : ResearchRules.all()) {
            for (Cost cost : topic.requiredResources) {
                for (CraftableItem recipe : CraftingRules.recipesForOutput(cost.material())) {
                    require(!recipe.requiresResearch.contains(topic.id),
                            topic.id + " requires " + cost.material() + " but its recipe requires the same research");
                }
            }
        }
    }

    private static void validateBootstrap() {
        ShipType prospector = Rules.ship("prospector");
        ShipType deployer = Rules.ship("station_builder");
        BaseType outpost = Rules.base("outpost");
        BaseType manufacturing = Rules.base("manufacturing");
        require(prospector != null && deployer != null && outpost != null && manufacturing != null,
                "starter economy definitions are missing");
        require(allRaw(prospector.buildCost), "Prospector must remain raw-resource craftable");
        require(allRaw(deployer.buildCost), "Deployer must remain raw-resource craftable");
        require(allRaw(manufacturing.buildCost), "Manufacturing Plant package must remain raw-resource craftable");
        require(outpost.basePackages.contains("manufacturing"), "Outpost must be able to bootstrap a Manufacturing Plant");
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
