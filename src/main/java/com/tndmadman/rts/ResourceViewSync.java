package com.tndmadman.rts;

import java.util.*;

final class ResourceViewSync {
    private ResourceViewSync() { }

    static void apply(World world, Iterable<ResourceState> states) {
        apply(world, states, false);
    }

    static void replace(World world, Iterable<ResourceState> states) {
        apply(world, states, true);
    }

    private static void apply(World world, Iterable<ResourceState> states, boolean replace) {
        List<ResourceState> list = new ArrayList<>();
        for (ResourceState s : states) list.add(s);
        ResourceNetDebug.resourceViewStart(world, replace, list);
        CelestialPacketCache.apply(world);
        if (replace) world.resources.clear();
        for (ResourceState s : list) {
            ResourceNode node = world.findResource(s.id());
            if (node == null) {
                node = new ResourceNode(s.id(), s.name(), NodeKind.valueOf(s.kind()), Material.valueOf(s.material()), s.x(), s.y(), s.maxAmount(), s.harvestRate(), s.radius());
                world.resources.add(node);
            }
            ResourceOrbitSync.apply(node, s);
        }
        CelestialPacketCache.clear();
        ResourceNetDebug.resourceViewEnd(world, replace);
    }
}
