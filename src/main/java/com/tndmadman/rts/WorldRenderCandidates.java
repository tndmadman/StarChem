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
    // Issue #396 station art can extend well beyond legacy interaction bounds and its UI sits farther still.
    private static final double BASE_MARGIN = 280.0;
    private static final double RESOURCE_MARGIN = 120.0;
    private static final double ITEM_MARGIN = 64.0;
    private static final Map<World, Scratch> SCRATCH = Collections.synchronizedMap(new WeakHashMap<>());

    private WorldRenderCandidates() { }

    /**
     * Resolve the spatial index once for a complete world paint. Previously every
     * base/resource/item/unit layer rescanned the live entity counts to verify the
     * same index, which scales poorly with large fleets and does no useful work
     * between consecutive layers of one draw call.
     *
     * The returned scratch lists are valid until the next frame for this world.
     * Painting is synchronous on the owning render thread.
     */
    static Frame prepareFrame(World world, Graphics2D g2) {
        Rectangle clip = g2 == null ? null : g2.getClipBounds();
        WorldSpatialIndex index = index(world, clip);
        Iterable<Base> bases;
        Iterable<ResourceNode> resources;
        Iterable<WorldItem> items;
        Iterable<Unit> units;
        if (index == null) {
            bases = world.bases.values();
            resources = world.resources;
            items = world.items;
            units = world.units.values();
        } else {
            Scratch scratch = scratch(world);
            bases = index.basesIn(clip, BASE_MARGIN, scratch.bases);
            resources = index.resourcesIn(clip, RESOURCE_MARGIN, scratch.resources);
            items = index.itemsIn(clip, ITEM_MARGIN, scratch.items);
            units = index.unitsIn(clip, UNIT_MARGIN, scratch.units);
        }
        SelectionRenderPolicy.Frame selection = SelectionRenderPolicy.beginFrame(world, g2, units);
        FleetSelectionOverlay.drawFrame(g2, world, selection);
        return new Frame(bases, resources, items, units);
    }

    record Frame(Iterable<Base> bases, Iterable<ResourceNode> resources,
                 Iterable<WorldItem> items, Iterable<Unit> units) { }

    static Iterable<Unit> units(World world, Graphics2D g2) {
        Rectangle clip = g2 == null ? null : g2.getClipBounds();
        WorldSpatialIndex index = index(world, clip);
        Iterable<Unit> visible;
        if (index == null) {
            visible = world.units.values();
        } else {
            Scratch scratch = scratch(world);
            visible = index.unitsIn(clip, UNIT_MARGIN, scratch.units);
        }

        // World.draw asks for visible units once. Use that point as the frame boundary:
        // build selection state once, then paint all fleet-scale selection UI once.
        SelectionRenderPolicy.Frame selection = SelectionRenderPolicy.beginFrame(world, g2, visible);
        FleetSelectionOverlay.drawFrame(g2, world, selection);
        return visible;
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

    static void clear(World world) {
        if (world != null) SCRATCH.remove(world);
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
