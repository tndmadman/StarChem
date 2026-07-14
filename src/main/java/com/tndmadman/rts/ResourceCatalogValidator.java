package com.tndmadman.rts;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ResourceCatalogValidator {
    private ResourceCatalogValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem resource catalog validation passed.");
    }

    static void validateOrThrow() {
        validateResources();
        validateSystems();
        validateSearch();
    }

    private static void validateResources() {
        List<ResourceSystemCatalog.Entry> entries = ResourceSystemCatalog.entries();
        require(entries.size() == Material.values().length,
                "catalog entry count does not match the loaded material catalog");

        EnumSet<Material> seen = EnumSet.noneOf(Material.class);
        for (ResourceSystemCatalog.Entry entry : entries) {
            Material material = entry.material();
            require(seen.add(material), "duplicate catalog entry for " + material);
            require(entry.sourceLabel() != null && !entry.sourceLabel().isBlank(),
                    "catalog entry has no source label for " + material);
            require(entry.displayText().contains(material.label),
                    "catalog details omit the material label for " + material);

            if (material.raw) require(!entry.systems().isEmpty(), "raw material has no system mapping: " + material);
            else require(entry.systems().isEmpty(), "processed material was mapped to a natural system belt: " + material);

            Set<String> systemIds = new LinkedHashSet<>();
            for (ResourceSystemCatalog.SystemAvailability availability : entry.systems()) {
                require(systemIds.add(availability.systemId()),
                        material + " has duplicate system mapping for " + availability.systemId());
                StarSystemDefinition system = exactSystem(availability.systemId());
                require(system != null, material + " references unknown system " + availability.systemId());
                require(system.name().equals(availability.systemName()),
                        material + " system display name is stale for " + availability.systemId());
                require(system.role().equals(availability.role()),
                        material + " system role is stale for " + availability.systemId());

                EnumSet<NodeKind> actualKinds = EnumSet.noneOf(NodeKind.class);
                for (ResourceBelt belt : system.resourceBelts()) {
                    boolean present = belt.materials.contains(material)
                            || belt.materials.isEmpty() && material == Material.IRON;
                    if (present) actualKinds.add(belt.kind);
                }
                require(!actualKinds.isEmpty(), material + " is not present in " + availability.systemId());
                require(actualKinds.equals(availability.nodeKinds()),
                        material + " node kinds do not match " + availability.systemId());
            }
        }

        require(seen.size() == Material.values().length, "one or more materials are missing from the catalog");
    }

    private static void validateSystems() {
        List<ResourceSystemCatalog.SystemEntry> entries = ResourceSystemCatalog.systems();
        require(entries.size() == StarSystems.options().size(), "catalog system template count is stale");
        require(ResourceSystemCatalog.systemTemplateCount() == entries.size(), "reported system count is stale");

        Set<String> seenIds = new LinkedHashSet<>();
        for (ResourceSystemCatalog.SystemEntry entry : entries) {
            require(seenIds.add(entry.id()), "duplicate system catalog entry for " + entry.id());
            StarSystemDefinition system = exactSystem(entry.id());
            require(system != null, "system catalog references unknown system " + entry.id());
            require(system.name().equals(entry.name()), "system catalog name is stale for " + entry.id());
            require(system.role().equals(entry.role()), "system catalog role is stale for " + entry.id());
            require(system.width() == entry.width() && system.height() == entry.height(),
                    "system catalog map dimensions are stale for " + entry.id());
            require(system.tags().equals(entry.tags()), "system catalog tags are stale for " + entry.id());
            require(system.modifiers().equals(entry.modifiers()), "system catalog modifiers are stale for " + entry.id());
            require(system.resourceBelts().size() == entry.spawns().size(),
                    "system catalog spawn count is stale for " + entry.id());
            require(system.bodies().size() == entry.bodies().size(),
                    "system catalog celestial count is stale for " + entry.id());

            for (int i = 0; i < system.resourceBelts().size(); i++) {
                ResourceBelt belt = system.resourceBelts().get(i);
                ResourceSystemCatalog.SpawnBand spawn = entry.spawns().get(i);
                List<Material> expectedMaterials = belt.materials.isEmpty()
                        ? List.of(Material.IRON)
                        : List.copyOf(new LinkedHashSet<>(belt.materials));
                require(belt.name.equals(spawn.name()), "spawn band name is stale in " + entry.id());
                require(belt.kind == spawn.kind(), "spawn node type is stale in " + entry.id());
                require(expectedMaterials.equals(spawn.materials()), "spawn materials are stale in " + entry.id());
                require(same(belt.orbit, spawn.orbit()), "spawn orbit is stale in " + entry.id());
                require(same(belt.width, spawn.width()), "spawn orbit width is stale in " + entry.id());
                require(same(belt.arc, spawn.arc()), "spawn arc is stale in " + entry.id());
                require(belt.count == spawn.count(), "spawn node count is stale in " + entry.id());
                require(same(belt.amount, spawn.amount()), "spawn amount is stale in " + entry.id());
                require(same(belt.harvestRate, spawn.harvestRate()), "spawn harvest rate is stale in " + entry.id());
                require(same(belt.radius, spawn.radius()), "spawn node radius is stale in " + entry.id());
            }

            require(entry.displayText().contains("NATURAL RESOURCE SPAWNS"),
                    "system details omit spawn information for " + entry.id());
            if (!entry.spawns().isEmpty()) {
                require(entry.displayText().contains("Orbit around primary"),
                        "system details omit orbit information for " + entry.id());
            }
        }
    }

    private static void validateSearch() {
        for (ResourceSystemCatalog.Entry entry : ResourceSystemCatalog.entries()) {
            require(ResourceSystemCatalog.filterEntries(entry.material().label).contains(entry),
                    "resource search cannot find " + entry.material());
        }
        for (ResourceSystemCatalog.SystemEntry system : ResourceSystemCatalog.systems()) {
            require(ResourceSystemCatalog.filterSystems(system.id()).contains(system),
                    "system search cannot find " + system.id());
            for (ResourceSystemCatalog.SpawnBand spawn : system.spawns()) {
                require(ResourceSystemCatalog.filterSystems(spawn.name()).contains(system),
                        "system search cannot find belt " + spawn.name());
                for (Material material : spawn.materials()) {
                    require(ResourceSystemCatalog.filterSystems(material.label).contains(system),
                            "system search cannot find " + system.id() + " by material " + material);
                }
            }
        }
    }

    private static StarSystemDefinition exactSystem(String id) {
        for (StarSystemDefinition system : StarSystems.options()) if (system.id().equals(id)) return system;
        return null;
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < 0.000001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}