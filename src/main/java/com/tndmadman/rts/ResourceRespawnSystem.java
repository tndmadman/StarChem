package com.tndmadman.rts;

final class ResourceRespawnSystem {
    void update(World world, double dt) {
        for (ResourceNode node : world.resources) {
            if (node == null) continue;
            // Planet/moon deposits are recycled only by CelestialExtractionSystem after another
            // fracture charge. Generic belt respawn must never silently reactivate them.
            if (node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank()) continue;
            boolean wasActive = node.active;
            node.updateRespawn(dt, world);
            if (!wasActive && node.active) ResourceSync.mark(world, node);
        }
    }
}
