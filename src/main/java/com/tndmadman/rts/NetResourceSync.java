package com.tndmadman.rts;

import java.util.HashSet;
import java.util.Set;

final class NetResourceSync {
    private NetResourceSync() { }

    static void apply(World world, Iterable<ResourceState> states) {
        Set<Integer> live = new HashSet<>();
        for (ResourceState s : states) {
            live.add(s.id());
            ResourceNode node = world.findResource(s.id());
            if (node == null) {
                node = new ResourceNode(s.id(), s.name(), NodeKind.valueOf(s.kind()), Material.valueOf(s.material()), s.x(), s.y(), s.maxAmount(), s.harvestRate(), s.radius());
                world.resources.add(node);
            }
            node.x = s.x();
            node.y = s.y();
            node.amount = s.amount();
            node.active = s.active();
            node.respawnTimer = s.respawnTimer();
        }
        world.resources.removeIf(node -> !live.contains(node.id));
    }
}
