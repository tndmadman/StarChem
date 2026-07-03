package com.tndmadman.rts;

final class ResourceRespawnSystem {
    void update(World world, double dt) {
        for (ResourceNode node : world.resources) {
            boolean wasActive = node.active;
            node.updateRespawn(dt, world);
            if (!wasActive && node.active) ResourceSync.mark(world, node);
        }
    }
}
