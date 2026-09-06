package com.tndmadman.rts;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lightweight uniform-grid spatial index shared by simulation and render hot paths.
 *
 * <p>The index is intentionally rebuilt once after movement instead of trying to keep
 * individual cells incrementally synchronized while the world is mutating. A linear
 * rebuild is cheap for the fleet sizes StarChem targets and gives every downstream
 * query an immutable-for-the-pass view of entity membership.</p>
 */
final class WorldSpatialIndex {
    private static final double CELL_SIZE = 512.0;
    private static final Map<World, WorldSpatialIndex> INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<Long, Cell> cells = new HashMap<>();
    private String systemId = "";
    private long revision;
    private int indexedUnits;
    private int indexedBases;
    private int indexedShots;
    private int indexedResources;
    private int indexedItems;

    private WorldSpatialIndex() { }

    static WorldSpatialIndex forWorld(World world) {
        if (world == null) throw new IllegalArgumentException("world");
        synchronized (INDEXES) {
            return INDEXES.computeIfAbsent(world, ignored -> new WorldSpatialIndex());
        }
    }

    static WorldSpatialIndex rebuild(World world) {
        WorldSpatialIndex index = forWorld(world);
        index.rebuildNow(world);
        return index;
    }

    private void rebuildNow(World world) {
        long started = System.nanoTime();
        cells.clear();
        systemId = world.activeSystemId() == null ? "" : world.activeSystemId();
        indexedUnits = indexedBases = indexedShots = indexedResources = indexedItems = 0;

        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0) continue;
            cell(unit.x, unit.y).units.add(unit);
            indexedUnits++;
        }
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0) continue;
            cell(base.x, base.y).bases.add(base);
            indexedBases++;
        }
        for (ProjectileShot shot : world.shots) {
            if (shot == null) continue;
            cell(shot.x, shot.y).shots.add(shot);
            indexedShots++;
        }
        for (ResourceNode resource : world.resources) {
            if (resource == null) continue;
            cell(resource.x, resource.y).resources.add(resource);
            indexedResources++;
        }
        for (WorldItem item : world.items) {
            if (item == null || item.empty()) continue;
            cell(item.x, item.y).items.add(item);
            indexedItems++;
        }
        revision++;
        PerformanceTrace.recordSpatialRebuild(System.nanoTime() - started,
                indexedUnits + indexedBases + indexedShots + indexedResources + indexedItems);
    }

    List<Unit> unitsWithin(double x, double y, double radius, List<Unit> out) {
        out.clear();
        if (!(radius > 0) || !Double.isFinite(radius)) return out;
        double radiusSquared = radius * radius;
        int minX = cellCoordinate(x - radius);
        int maxX = cellCoordinate(x + radius);
        int minY = cellCoordinate(y - radius);
        int maxY = cellCoordinate(y + radius);
        int candidates = 0;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                Cell cell = cells.get(key(cx, cy));
                if (cell == null) continue;
                candidates += cell.units.size();
                for (Unit unit : cell.units) {
                    double dx = unit.x - x;
                    double dy = unit.y - y;
                    if (dx * dx + dy * dy <= radiusSquared) out.add(unit);
                }
            }
        }
        PerformanceTrace.recordSpatialCandidates(candidates);
        return out;
    }

    List<Base> basesWithin(double x, double y, double radius, List<Base> out) {
        out.clear();
        if (!(radius > 0) || !Double.isFinite(radius)) return out;
        double radiusSquared = radius * radius;
        int minX = cellCoordinate(x - radius);
        int maxX = cellCoordinate(x + radius);
        int minY = cellCoordinate(y - radius);
        int maxY = cellCoordinate(y + radius);
        int candidates = 0;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                Cell cell = cells.get(key(cx, cy));
                if (cell == null) continue;
                candidates += cell.bases.size();
                for (Base base : cell.bases) {
                    double dx = base.x - x;
                    double dy = base.y - y;
                    if (dx * dx + dy * dy <= radiusSquared) out.add(base);
                }
            }
        }
        PerformanceTrace.recordSpatialCandidates(candidates);
        return out;
    }

    List<ProjectileShot> shotsWithin(double x, double y, double radius, List<ProjectileShot> out) {
        out.clear();
        if (!(radius > 0) || !Double.isFinite(radius)) return out;
        double radiusSquared = radius * radius;
        int minX = cellCoordinate(x - radius);
        int maxX = cellCoordinate(x + radius);
        int minY = cellCoordinate(y - radius);
        int maxY = cellCoordinate(y + radius);
        int candidates = 0;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                Cell cell = cells.get(key(cx, cy));
                if (cell == null) continue;
                candidates += cell.shots.size();
                for (ProjectileShot shot : cell.shots) {
                    double dx = shot.x - x;
                    double dy = shot.y - y;
                    if (dx * dx + dy * dy <= radiusSquared) out.add(shot);
                }
            }
        }
        PerformanceTrace.recordSpatialCandidates(candidates);
        return out;
    }

    List<Unit> unitsIn(Rectangle2D bounds, double margin, List<Unit> out) {
        out.clear();
        if (bounds == null) return out;
        rectangle(bounds, margin, cell -> cell.units, unit -> unit.x, unit -> unit.y, out);
        return out;
    }

    List<Base> basesIn(Rectangle2D bounds, double margin, List<Base> out) {
        out.clear();
        if (bounds == null) return out;
        rectangle(bounds, margin, cell -> cell.bases, base -> base.x, base -> base.y, out);
        return out;
    }

    List<ResourceNode> resourcesIn(Rectangle2D bounds, double margin, List<ResourceNode> out) {
        out.clear();
        if (bounds == null) return out;
        rectangle(bounds, margin, cell -> cell.resources, resource -> resource.x, resource -> resource.y, out);
        return out;
    }

    List<WorldItem> itemsIn(Rectangle2D bounds, double margin, List<WorldItem> out) {
        out.clear();
        if (bounds == null) return out;
        rectangle(bounds, margin, cell -> cell.items, item -> item.x, item -> item.y, out);
        return out;
    }

    private <T> void rectangle(Rectangle2D bounds, double margin, CellList<T> list,
                               Coordinate<T> x, Coordinate<T> y, List<T> out) {
        double minWorldX = bounds.getMinX() - margin;
        double maxWorldX = bounds.getMaxX() + margin;
        double minWorldY = bounds.getMinY() - margin;
        double maxWorldY = bounds.getMaxY() + margin;
        int minX = cellCoordinate(minWorldX);
        int maxX = cellCoordinate(maxWorldX);
        int minY = cellCoordinate(minWorldY);
        int maxY = cellCoordinate(maxWorldY);
        int candidates = 0;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                Cell cell = cells.get(key(cx, cy));
                if (cell == null) continue;
                List<T> values = list.get(cell);
                candidates += values.size();
                for (T value : values) {
                    double vx = x.get(value);
                    double vy = y.get(value);
                    if (vx >= minWorldX && vx <= maxWorldX && vy >= minWorldY && vy <= maxWorldY) out.add(value);
                }
            }
        }
        PerformanceTrace.recordSpatialCandidates(candidates);
    }

    boolean matches(World world) {
        if (world == null) return false;
        String active = world.activeSystemId() == null ? "" : world.activeSystemId();
        return active.equals(systemId) && indexedUnits == aliveUnitCount(world)
                && indexedBases == aliveBaseCount(world) && indexedShots == world.shots.size()
                && indexedResources == world.resources.size() && indexedItems == liveItemCount(world);
    }

    long revision() { return revision; }

    private Cell cell(double x, double y) {
        int cx = cellCoordinate(x);
        int cy = cellCoordinate(y);
        return cells.computeIfAbsent(key(cx, cy), ignored -> new Cell());
    }

    private static int cellCoordinate(double coordinate) {
        return (int)Math.floor(coordinate / CELL_SIZE);
    }

    private static long key(int x, int y) {
        return ((long)x << 32) ^ (y & 0xffffffffL);
    }

    private static int aliveUnitCount(World world) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit != null && unit.hp > 0) count++;
        return count;
    }

    private static int aliveBaseCount(World world) {
        int count = 0;
        for (Base base : world.bases.values()) if (base != null && base.hp > 0) count++;
        return count;
    }

    private static int liveItemCount(World world) {
        int count = 0;
        for (WorldItem item : world.items) if (item != null && !item.empty()) count++;
        return count;
    }

    private interface CellList<T> { List<T> get(Cell cell); }
    private interface Coordinate<T> { double get(T value); }

    private static final class Cell {
        final List<Unit> units = new ArrayList<>();
        final List<Base> bases = new ArrayList<>();
        final List<ProjectileShot> shots = new ArrayList<>();
        final List<ResourceNode> resources = new ArrayList<>();
        final List<WorldItem> items = new ArrayList<>();
    }
}
