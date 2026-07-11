package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

record StarSystemDefinition(
        String id,
        String name,
        String role,
        int width,
        int height,
        List<CelestialBodyDefinition> bodies,
        List<ResourceBelt> resourceBelts,
        List<Material> spawnMaterials,
        Set<String> tags,
        SystemModifiers modifiers
) {
    StarSystemDefinition(String id, String name, String role, int width, int height,
                         List<CelestialBodyDefinition> bodies, List<ResourceBelt> resourceBelts,
                         List<Material> spawnMaterials) {
        this(id, name, role, width, height, bodies, resourceBelts, spawnMaterials, Set.of(), SystemModifiers.STANDARD);
    }

    StarSystemDefinition {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        role = role == null || role.isBlank() ? "standard" : role.trim();
        bodies = bodies == null ? List.of() : List.copyOf(bodies);
        resourceBelts = resourceBelts == null ? List.of() : List.copyOf(resourceBelts);
        spawnMaterials = spawnMaterials == null ? List.of() : List.copyOf(spawnMaterials);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        modifiers = modifiers == null ? SystemModifiers.STANDARD : modifiers;
    }

    boolean hasTag(String tag) {
        if (tag == null || tag.isBlank()) return false;
        for (String value : tags) if (value.equalsIgnoreCase(tag)) return true;
        return false;
    }

    @Override public String toString() { return name; }
}
