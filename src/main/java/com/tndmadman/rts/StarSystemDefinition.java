package com.tndmadman.rts;

import java.util.List;

record StarSystemDefinition(
        String id,
        String name,
        String role,
        int width,
        int height,
        List<CelestialBodyDefinition> bodies,
        List<ResourceBelt> resourceBelts,
        List<Material> spawnMaterials
) {
    @Override public String toString() { return name; }
}
