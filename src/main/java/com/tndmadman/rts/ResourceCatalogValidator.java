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
        List<ResourceSystemCatalog.Entry> entries = ResourceSystemCatalog.entries();
        require(entries.size() == Material.values().length,
                "catalog entry count does not match the loaded material catalog");

        EnumSet<Material> seen = EnumSet.noneOf(Material.class);
        for (ResourceSystemCatalog.Entry entry : entries) {
            Material material = entry.material();
            require(seen.add(material), "duplicate catalog entry for " + material);
            require(entry.sourceLabel() != null && !entry.sourceLabel().isBlank(),
                    "catalog entry has no source label for " + material);

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
                    if (belt.materials.contains(material)) actualKinds.add(belt.kind);
                }
                require(!actualKinds.isEmpty(), material + " is not present in " + availability.systemId());
                require(actualKinds.equals(availability.nodeKinds()),
                        material + " node kinds do not match " + availability.systemId());
            }
        }

        require(seen.size() == Material.values().length, "one or more materials are missing from the catalog");
        require(ResourceSystemCatalog.systemTemplateCount() == StarSystems.options().size(),
                "catalog system template count is stale");
    }

    private static StarSystemDefinition exactSystem(String id) {
        for (StarSystemDefinition system : StarSystems.options()) if (system.id().equals(id)) return system;
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}