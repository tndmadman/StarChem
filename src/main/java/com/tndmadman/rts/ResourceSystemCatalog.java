package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ResourceSystemCatalog {
    private static final List<Entry> ENTRIES = build();

    private ResourceSystemCatalog() { }

    static List<Entry> entries() { return ENTRIES; }
    static int systemTemplateCount() { return StarSystems.options().size(); }

    static Entry entry(Material material) {
        for (Entry entry : ENTRIES) if (entry.material() == material) return entry;
        throw new IllegalArgumentException("Unknown catalog material: " + material);
    }

    private static List<Entry> build() {
        EnumMap<Material, LinkedHashMap<String, AvailabilityBuilder>> availability = new EnumMap<>(Material.class);
        for (Material material : Material.values()) availability.put(material, new LinkedHashMap<>());

        for (StarSystemDefinition system : StarSystems.options()) {
            for (ResourceBelt belt : system.resourceBelts()) {
                for (Material material : new LinkedHashSet<>(belt.materials)) {
                    LinkedHashMap<String, AvailabilityBuilder> systems = availability.get(material);
                    if (systems == null) continue;
                    systems.computeIfAbsent(system.id(), ignored -> new AvailabilityBuilder(system)).nodeKinds.add(belt.kind);
                }
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (Material material : Material.values()) {
            List<SystemAvailability> systems = new ArrayList<>();
            for (AvailabilityBuilder builder : availability.get(material).values()) systems.add(builder.build());
            entries.add(new Entry(material, systems));
        }
        return List.copyOf(entries);
    }

    record Entry(Material material, List<SystemAvailability> systems) {
        Entry {
            if (material == null) throw new IllegalArgumentException("Catalog entry material is required.");
            systems = systems == null ? List.of() : List.copyOf(systems);
        }

        boolean naturallyAvailable() { return !systems.isEmpty(); }

        String sourceLabel() {
            if (material.raw) return naturallyAvailable() ? "Natural resource" : "Raw resource unavailable";
            if (material.family == MaterialFamily.SALVAGE) return "Salvage material";
            return "Manufactured material";
        }
    }

    record SystemAvailability(String systemId, String systemName, String role, Set<NodeKind> nodeKinds) {
        SystemAvailability {
            systemId = systemId == null ? "" : systemId;
            systemName = systemName == null || systemName.isBlank() ? systemId : systemName;
            role = role == null || role.isBlank() ? "standard" : role;
            nodeKinds = nodeKinds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(nodeKinds));
        }
    }

    private static final class AvailabilityBuilder {
        private final StarSystemDefinition system;
        private final EnumSet<NodeKind> nodeKinds = EnumSet.noneOf(NodeKind.class);

        private AvailabilityBuilder(StarSystemDefinition system) { this.system = system; }

        private SystemAvailability build() {
            return new SystemAvailability(system.id(), system.name(), system.role(), nodeKinds);
        }
    }
}