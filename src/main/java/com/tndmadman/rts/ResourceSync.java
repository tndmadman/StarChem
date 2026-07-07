package com.tndmadman.rts;

import java.util.*;

final class ResourceSync {
    private static final int DIRTY_SENDS = 12;
    private static final int FULL_SENDS = 3;
    private static final Map<World, Map<Integer, Integer>> DIRTY = new WeakHashMap<>();
    private static final Map<World, Integer> FULL = new WeakHashMap<>();

    private ResourceSync() { }

    static void mark(World world, ResourceNode node) {
        if (world == null || node == null) return;
        DIRTY.computeIfAbsent(world, w -> new LinkedHashMap<>()).put(node.id, DIRTY_SENDS);
    }

    static void markFull(World world) {
        if (world != null) FULL.put(world, FULL_SENDS);
    }

    static List<ResourceState> snapshot(World world) {
        int fullLeft = FULL.getOrDefault(world, 0);
        if (fullLeft > 0) {
            FULL.put(world, fullLeft - 1);
            return all(world);
        }
        Set<Integer> ids = new LinkedHashSet<>();
        Map<Integer, Integer> dirty = DIRTY.get(world);
        if (dirty != null) ids.addAll(dirty.keySet());
        for (Unit unit : world.units.values()) if (unit.automationResourceId > 0) ids.add(unit.automationResourceId);
        List<ResourceState> out = new ArrayList<>();
        for (Integer id : ids) {
            ResourceNode r = world.findResource(id);
            if (r != null) out.add(state(r));
        }
        decay(dirty);
        return out;
    }

    private static List<ResourceState> all(World world) {
        List<ResourceState> out = new ArrayList<>();
        for (ResourceNode r : world.resources) out.add(state(r));
        return out;
    }

    private static ResourceState state(ResourceNode r) {
        return new ResourceState(r.id, r.name, r.kind.name(), r.material.name(), r.x, r.y, r.maxAmount, r.harvestRate, r.radius, r.amount, r.active, r.respawnTimer);
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
