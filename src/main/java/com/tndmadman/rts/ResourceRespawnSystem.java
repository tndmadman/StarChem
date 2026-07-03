package com.tndmadman.rts;

final class ResourceRespawnSystem {
    void update(World world, double dt) {
        for (ResourceNode node : world.resources) node.updateRespawn(dt, world);
    }
}
