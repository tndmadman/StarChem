package com.tndmadman.rts;

import java.util.EnumSet;
import java.util.Set;

public final class MaterialCoverageValidator {
    private MaterialCoverageValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem material coverage validation passed.");
    }

    static void validateOrThrow() {
        EnumSet<Material> raw = EnumSet.noneOf(Material.class);
        int metals = 0;
        int gases = 0;
        for (Material material : Material.values()) {
            if (!material.raw) continue;
            raw.add(material);
            if (material.family == MaterialFamily.METAL) metals++;
            if (material.family == MaterialFamily.GAS) gases++;
        }
        require(raw.size() >= 24, "raw material catalog was not tripled");
        require(metals >= 10, "expanded catalog lacks metal variety");
        require(gases >= 8, "expanded catalog lacks gas variety");
        require(StarSystems.options().size() >= 14, "star-system template variety was not doubled");

        EnumSet<Material> supplied = EnumSet.noneOf(Material.class);
        for (StarSystemDefinition system : StarSystems.options()) {
            require(!system.tags().isEmpty(), system.id() + " has no strategic tags");
            EnumSet<Material> local = EnumSet.noneOf(Material.class);
            int nodes = 0;
            for (ResourceBelt belt : system.resourceBelts()) {
                require(!belt.materials.isEmpty(), system.id() + " contains an empty resource belt");
                nodes += belt.count;
                for (Material material : belt.materials) {
                    require(material.raw, system.id() + " places processed material " + material + " in a raw belt");
                    local.add(material);
                    supplied.add(material);
                }
            }
            require(nodes <= 420, system.id() + " creates an excessive resource-node count");
            for (Material material : system.spawnMaterials()) {
                require(local.contains(material), system.id() + " advertises spawn material " + material + " without a producing belt");
            }
        }
        require(supplied.containsAll(raw), "one or more raw materials are absent from every system template: " + difference(raw, supplied));

        Set<Material> starter = Set.of(Material.IRON, Material.COPPER, Material.SILICATES, Material.ICE,
                Material.HYDROGEN, Material.HELIUM, Material.METHANE);
        require(materials(StarSystems.get(StarSystems.DEFAULT_SYSTEM_ID)).containsAll(starter),
                "default system is not starter progression complete");
        require(materials(StarSystems.get(StarSystems.PLAYER_HOME_SYSTEM_ID)).containsAll(starter),
                "protected home template is not starter progression complete");

        require(costChainContains(Rules.ship("titan").buildCost, Material.URANIUM, Material.XENON, Material.TUNGSTEN),
                "late-game economy does not use strategic resources");
        require(costChainContains(Rules.ship("freighter").buildCost, Material.ALUMINUM, Material.NICKEL, Material.CARBON),
                "industrial economy does not use expanded common resources");
    }

    private static EnumSet<Material> materials(StarSystemDefinition system) {
        EnumSet<Material> result = EnumSet.noneOf(Material.class);
        for (ResourceBelt belt : system.resourceBelts()) result.addAll(belt.materials);
        return result;
    }

    private static boolean costChainContains(java.util.List<Cost> cost, Material... materials) {
        EnumSet<Material> present = EnumSet.noneOf(Material.class);
        for (Cost entry : cost) collectInputs(entry.material(), present, EnumSet.noneOf(Material.class));
        for (Material material : materials) if (!present.contains(material)) return false;
        return true;
    }

    private static void collectInputs(Material material, EnumSet<Material> present, EnumSet<Material> visiting) {
        if (!present.add(material) || !visiting.add(material)) return;
        CraftableItem recipe = CraftingRules.preferredForOutput(material);
        if (recipe != null) {
            for (Cost input : recipe.requiredResources) collectInputs(input.material(), present, visiting);
        }
        visiting.remove(material);
    }

    private static EnumSet<Material> difference(EnumSet<Material> expected, EnumSet<Material> actual) {
        EnumSet<Material> missing = EnumSet.copyOf(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
