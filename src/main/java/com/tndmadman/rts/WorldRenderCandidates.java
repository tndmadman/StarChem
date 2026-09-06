package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Scratch-backed visible-entity queries used by World.draw without per-frame list allocation. */
final class WorldRenderCandidates {
    private static final double UNIT_MARGIN = 180.0;
    private static final double BASE_MARGIN = 180.0;
    private static final double RESOURCE_MARGIN = 120.0;
    private static final double ITEM_MARGIN = 64.0;
    private static final Map<World, Scratch> SCRATCH = Collections.synchronizedMap(new WeakHashMap<>());

    private WorldRenderCandidates() { }

    static Iterable<Unit> units(World world, Graphics2D g2) {
        Rectangle clip = g2 == null ? null : g2.getClipBounds();
        WorldSpatialIndex index = index(world, clip);
        if (index == null) return world.units.values();
        Scratch scratch = scratch(world);
        return index.unitsIn(clip, UNIT_MARGIN, scratch.units);
    }

    static Iterable<Base> bases(World world, Graphics2D g2) {
        Rectangle clip = g2 == null ? null : g2.getClipBounds();
        WorldSpatialIndex index = index(world, clip);
        if (index == null) return world.bases.values();
        Scratch scratch = scratch(world);
        return index.basesIn(clip, BASE_MARGIN, scratch.bases);
    }

    static Iterable<ResourceNode> resources(World world, Graphics2D g2) {
        Rectangle clip = g2 == null ? null : g2.getClipBounds();
        WorldSpatialIndex index = index(world, clip);
        if (index == null) return world.resources;
        Scratch scratch = scratch(world);
        return index.resourcesIn(clip, RESOURCE_MARGIN, scratch.resources);
    }

    static Iterable<WorldItem> items(World world, Graphics2D g2) {
        Rectangle clip = g2 == null ? null : g2.getClipBounds();
        WorldSpatialIndex index = index(world, clip);
        if (index == null) return world.items;
        Scratch scratch = scratch(world);
        return index.itemsIn(clip, ITEM_MARGIN, scratch.items);
    }

    private static WorldSpatialIndex index(World world, Rectangle clip) {
        if (world == null || clip == null) return null;
        WorldSpatialIndex index = WorldSpatialIndex.forWorld(world);
        return index.matches(world) ? index : null;
    }

    private static Scratch scratch(World world) {
        synchronized (SCRATCH) {
            return SCRATCH.computeIfAbsent(world, ignored -> new Scratch());
        }
    }

    private static final class Scratch {
        final List<Unit> units = new ArrayList<>();
        final List<Base> bases = new ArrayList<>();
        final List<ResourceNode> resources = new ArrayList<>();
        final List<WorldItem> items = new ArrayList<>();
    }
}
