package com.tndmadman.rts;

import java.util.*;

final class ResourceSync {
    private static final Map<World, Set<Integer>> DIRTY = new WeakHashMap<>();

    private ResourceSync() { }

    static void mark(World world, ResourceNode node) {
        if (world == null || node == null) return;
        DIRTY.computeIfAbsent(world, w -> new LinkedHashSet<>()).add(node.id);
    }

    static List<ResourceState> snapshot(World world) {
        Set<Integer> ids = new LinkedHashSet<>();
        Set<Integer> dirty = DIRTY.get(world);
        if (dirty != null) ids.addAll(dirty);
        for (Unit unit : world.units.values()) if (unit.automationResourceId > 0) ids.add(unit.automationResourceId);
        List<ResourceState> out = new ArrayList<>();
        for (Integer id : ids) {
            ResourceNode r = world.findResource(id);
            if (r != null) out.add(new ResourceState(r.id, r.name, r.kind.name(), r.material.name(), r.x, r.y, r.maxAmount, r.harvestRate, r.radius, r.amount, r.active, r.respawnTimer));
        }
        if (dirty != null) dirty.clear();
        return out;
    }
}
