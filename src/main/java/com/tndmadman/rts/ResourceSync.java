package com.tndmadman.rts;

import java.util.*;

final class ResourceSync {
    private static final int DIRTY_SENDS = 12;
    private static final Map<World, Map<Integer, Integer>> DIRTY = new WeakHashMap<>();

    private ResourceSync() { }

    static void mark(World world, ResourceNode node) {
        if (world == null || node == null) return;
        DIRTY.computeIfAbsent(world, w -> new LinkedHashMap<>()).put(node.id, DIRTY_SENDS);
    }

    static List<ResourceState> snapshot(World world) {
        Set<Integer> ids = new LinkedHashSet<>();
        Map<Integer, Integer> dirty = DIRTY.get(world);
        if (dirty != null) ids.addAll(dirty.keySet());
        for (Unit unit : world.units.values()) if (unit.automationResourceId > 0) ids.add(unit.automationResourceId);
        List<ResourceState> out = new ArrayList<>();
        for (Integer id : ids) {
            ResourceNode r = world.findResource(id);
            if (r != null) out.add(new ResourceState(r.id, r.name, r.kind.name(), r.material.name(), r.x, r.y, r.maxAmount, r.harvestRate, r.radius, r.amount, r.active, r.respawnTimer));
        }
        decay(dirty);
        return out;
    }

    private static void decay(Map<Integer, Integer> dirty) {
        if (dirty == null) return;
        Iterator<Map.Entry<Integer, Integer>> it = dirty.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left <= 0) it.remove();
            else entry.setValue(left);
        }
    }
}
