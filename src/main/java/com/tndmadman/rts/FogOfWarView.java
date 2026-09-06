package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class FogOfWarView {
    static final int CELL_SIZE = 128;
    private static final double EXPLORATION_MASK_WORLD_UNITS = 64.0;
    private static final int FOG_TILE_WORLD_UNITS = 1024;
    private static final int FOG_TILE_PIXELS = 64;
    private static final int MAX_CACHED_TILES = 128;
    private static final double EDGE_FEATHER_WORLD_UNITS = 96.0;
    private static final long UPDATE_INTERVAL_NANOS = 50_000_000L;
    private static final long MINIMAP_REFRESH_NANOS = 100_000_000L;
    private static final double CONTACT_CLEAR_CONFIRM_SECONDS = 2.0;
    private static final double CONTACT_MEMORY_SECONDS = 45.0;
    private static final int MAX_CONTACTS = 512;
    private static final int GRADIENT_STAMP_SIZE = 128;
    private static final Color UNEXPLORED = new Color(1, 3, 7);
    private static final Color EXPLORED = new Color(8, 14, 22);
    private static final Map<World, WorldState> STATES = new WeakHashMap<>();
    private static final Map<Integer, BufferedImage> GRADIENT_STAMPS = new LinkedHashMap<>();

    private FogOfWarView() { }

    static synchronized void drawWorld(Graphics2D source, World world) {
        SystemState state = update(world, System.nanoTime());
        if (source == null || world == null || state == null) return;
        Graphics2D g = (Graphics2D)source.create();
        Rectangle2D view = visibleWorldBounds(g, world);
        drawVisibleTiles(g, state, view);
        drawContacts(g, world, state, 1.0, 1.0, 0, 0);
        g.dispose();
    }

    static synchronized void drawMinimap(Graphics2D source, World world, Rectangle map) {
        long now = System.nanoTime();
        SystemState state = update(world, now);
        if (source == null || world == null || map == null || state == null) return;
        Graphics2D g = (Graphics2D)source.create();
        BufferedImage fog = composeMinimapFog(state, map.width, map.height, now);
        if (fog != null) g.drawImage(fog, map.x, map.y, null);
        double sx = map.width / Math.max(1.0, world.width);
        double sy = map.height / Math.max(1.0, world.height);
        drawContacts(g, world, state, sx, sy, map.x, map.y);
        g.dispose();
    }

    static boolean currentlyVisible(World world, double x, double y) {
        return VisibilityRules.pointVisible(world, PlayerRegistry.localId(), x, y);
    }

    static synchronized boolean explored(World world, double x, double y) {
        SystemState state = update(world, System.nanoTime());
        if (state == null) return false;
        int cell = state.cell(x, y);
        return cell >= 0 && state.explored.get(cell);
    }

    static synchronized List<KnownWormhole> knownWormholes(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? List.of() : List.copyOf(state.wormholes.values());
    }

    static synchronized int lastKnownContactCount(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.contacts.size();
    }

    static synchronized int recentHiddenContactCount(World world) {
        SystemState state = update(world, System.nanoTime());
        if (state == null) return 0;
        int count = 0;
        for (String key : state.contacts.keySet()) if (!state.liveContacts.contains(key)) count++;
        return count;
    }

    static synchronized int exploredCellCount(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.explored.cardinality();
    }

    static synchronized int smoothTransitionPixelCount(World world) {
        SystemState state = update(world, System.nanoTime());
        if (state == null) return 0;
        int count = 0;
        for (BufferedImage tile : state.tileImages.values()) {
            int unexplored = UNEXPLORED.getRGB() & 0x00FFFFFF;
            int explored = EXPLORED.getRGB() & 0x00FFFFFF;
            for (int y = 0; y < tile.getHeight(); y++) {
                for (int x = 0; x < tile.getWidth(); x++) {
                    int rgb = tile.getRGB(x, y) & 0x00FFFFFF;
                    if (rgb != unexplored && rgb != explored) count++;
                }
            }
        }
        return count;
    }

    static synchronized long sensorCoverageRebuildCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.sensorCoverageRebuilds;
    }

    static synchronized long fullFogCompositionCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.fullFogCompositions;
    }

    static synchronized long partialFogCompositionCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.partialFogCompositions;
    }

    static synchronized long visualFogRebuildCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.tileRebuilds;
    }

    static synchronized int dirtyFogTileCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.dirtyTiles.cardinality();
    }

    static synchronized void clearDirtyFogTilesForTest(World world) {
        WorldState worldState = STATES.get(world);
        if (worldState == null) return;
        for (SystemState state : worldState.systems.values()) {
            state.dirtyTiles.clear();
            state.minimapDirtyTiles.clear();
        }
    }

    static synchronized int cachedFogTileCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.tileImages.size();
    }

    static synchronized int fogTileCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.tileColumns * state.tileRows;
    }

    static synchronized int currentCoverageCountForTest(World world, double x, double y) {
        SystemState state = update(world, System.nanoTime());
        if (state == null) return 0;
        int cell = state.cell(x, y);
        return cell < 0 ? 0 : state.visibleCoverage[cell];
    }

    static synchronized long minimapPatchCountForTest(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.minimapPatches;
    }

    static synchronized void forceMinimapRefreshForTest(World world) {
        WorldState worldState = STATES.get(world);
        if (worldState != null) {
            for (SystemState state : worldState.systems.values()) state.lastMinimapRefreshNanos = 0;
        }
    }

    static synchronized void forceRefreshForTest(World world) {
        WorldState worldState = STATES.get(world);
        if (worldState != null) for (SystemState state : worldState.systems.values()) state.lastUpdateNanos = 0;
        update(world, System.nanoTime());
    }

    static synchronized void clearCachedStateForTest(World world) {
        if (world != null) STATES.remove(world);
    }

    private static SystemState update(World world, long now) {
        if (world == null || world.width <= 0 || world.height <= 0) return null;
        String playerId = PlayerRegistry.localId();
        String systemId = world.activeSystemId();
        if (playerId == null || playerId.isBlank() || systemId == null || systemId.isBlank()) return null;
        WorldState worldState = STATES.computeIfAbsent(world, ignored -> new WorldState());
        String key = playerId + '|' + systemId;
        int columns = Math.max(1, (int)Math.ceil(world.width / (double)CELL_SIZE));
        int rows = Math.max(1, (int)Math.ceil(world.height / (double)CELL_SIZE));
        long environmentSeed = world.systemSeed();
        SystemState state = worldState.systems.get(key);
        if (state == null || state.columns != columns || state.rows != rows
                || state.worldWidth != world.width || state.worldHeight != world.height
                || state.environmentSeed != environmentSeed) {
            state = new SystemState(systemId, environmentSeed, columns, rows, world.width, world.height);
            state.restore(FogOfWarPersistence.load(playerId, systemId, environmentSeed, columns, rows));
            worldState.systems.put(key, state);
        }
        if (now - state.lastUpdateNanos < UPDATE_INTERVAL_NANOS) return state;
        state.lastUpdateNanos = now;

        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        SensorUpdate sensorUpdate = updateSensors(state, frame.sensors());
        if (sensorUpdate.changed()) {
            state.fogRevision++;
            if (sensorUpdate.explorationChanged()) paintExploration(state, sensorUpdate.explorationSensors());
        }

        observeContacts(world, playerId, frame, state);
        boolean wormholesChanged = observeWormholes(world, frame, state);
        if (sensorUpdate.explorationChanged() || wormholesChanged) {
            FogOfWarPersistence.saveLater(playerId, systemId, environmentSeed, columns, rows,
                    state.explored, state.wormholes.values());
        }
        return state;
    }

    private static SensorUpdate updateSensors(SystemState state, List<VisibilityRules.Sensor> nextSensors) {
        state.nextSensorsScratch.clear();
        int duplicate = 0;
        if (nextSensors != null) {
            for (VisibilityRules.Sensor sensor : nextSensors) {
                if (sensor == null || sensor.range() <= 0) continue;
                String key = sensor.sourceKey();
                if (key == null || key.isBlank()) key = "SENSOR:" + duplicate++;
                String baseKey = key;
                while (state.nextSensorsScratch.containsKey(key)) key = baseKey + "#" + duplicate++;
                state.nextSensorsScratch.put(key, sensor);
            }
        }

        boolean changed = false;
        boolean explorationChanged = false;
        state.explorationSensorsScratch.clear();

        Iterator<Map.Entry<String, SensorCoverage>> oldIterator = state.sensorCoverage.entrySet().iterator();
        while (oldIterator.hasNext()) {
            Map.Entry<String, SensorCoverage> entry = oldIterator.next();
            if (state.nextSensorsScratch.containsKey(entry.getKey())) continue;
            SensorCoverage old = entry.getValue();
            removeCoverage(state, old.cells);
            detachSensorTiles(state, entry.getKey(), old.tiles);
            markTilesDirty(state, old.tiles);
            oldIterator.remove();
            changed = true;
        }

        for (Map.Entry<String, VisibilityRules.Sensor> entry : state.nextSensorsScratch.entrySet()) {
            String key = entry.getKey();
            VisibilityRules.Sensor sensor = entry.getValue();
            SensorCoverage coverage = state.sensorCoverage.get(key);
            if (coverage != null && sameSensorGeometry(coverage.sensor, sensor)) continue;

            if (coverage == null) {
                coverage = new SensorCoverage();
                state.sensorCoverage.put(key, coverage);
            } else {
                removeCoverage(state, coverage.cells);
                detachSensorTiles(state, key, coverage.tiles);
                markTilesDirty(state, coverage.tiles);
                coverage.cells.clear();
                coverage.tiles.clear();
            }

            coverage.sensor = sensor;
            coveredCells(state, sensor, coverage.cells);
            coveredTiles(state, sensor, coverage.tiles);
            boolean newlyExplored = addCoverage(state, coverage.cells);
            attachSensorTiles(state, key, coverage.tiles);
            markTilesDirty(state, coverage.tiles);
            state.sensorCoverageRebuilds++;
            changed = true;
            if (newlyExplored) {
                explorationChanged = true;
                state.explorationSensorsScratch.add(sensor);
            }
        }

        return new SensorUpdate(changed, explorationChanged, List.copyOf(state.explorationSensorsScratch));
    }

    private static void coveredCells(SystemState state, VisibilityRules.Sensor sensor, BitSet out) {
        out.clear();
        if (sensor == null || sensor.range() <= 0) return;
        int minColumn = clampCell((int)Math.floor((sensor.x() - sensor.range()) / CELL_SIZE), state.columns);
        int maxColumn = clampCell((int)Math.floor((sensor.x() + sensor.range()) / CELL_SIZE), state.columns);
        int minRow = clampCell((int)Math.floor((sensor.y() - sensor.range()) / CELL_SIZE), state.rows);
        int maxRow = clampCell((int)Math.floor((sensor.y() + sensor.range()) / CELL_SIZE), state.rows);
        for (int row = minRow; row <= maxRow; row++) {
            double top = row * (double)CELL_SIZE;
            double bottom = Math.min(state.worldHeight, top + CELL_SIZE);
            double nearestY = Calc.clamp(sensor.y(), top, bottom);
            for (int column = minColumn; column <= maxColumn; column++) {
                double left = column * (double)CELL_SIZE;
                double right = Math.min(state.worldWidth, left + CELL_SIZE);
                double nearestX = Calc.clamp(sensor.x(), left, right);
                double dx = nearestX - sensor.x();
                double dy = nearestY - sensor.y();
                if (dx * dx + dy * dy <= sensor.rangeSquared()) out.set(state.index(column, row));
            }
        }
    }

    private static void coveredTiles(SystemState state, VisibilityRules.Sensor sensor, BitSet out) {
        out.clear();
        if (sensor == null || sensor.range() <= 0) return;
        int minColumn = clampCell((int)Math.floor((sensor.x() - sensor.range()) / FOG_TILE_WORLD_UNITS), state.tileColumns);
        int maxColumn = clampCell((int)Math.floor((sensor.x() + sensor.range()) / FOG_TILE_WORLD_UNITS), state.tileColumns);
        int minRow = clampCell((int)Math.floor((sensor.y() - sensor.range()) / FOG_TILE_WORLD_UNITS), state.tileRows);
        int maxRow = clampCell((int)Math.floor((sensor.y() + sensor.range()) / FOG_TILE_WORLD_UNITS), state.tileRows);
        for (int row = minRow; row <= maxRow; row++) {
            double top = row * (double)FOG_TILE_WORLD_UNITS;
            double bottom = Math.min(state.worldHeight, top + FOG_TILE_WORLD_UNITS);
            double nearestY = Calc.clamp(sensor.y(), top, bottom);
            for (int column = minColumn; column <= maxColumn; column++) {
                double left = column * (double)FOG_TILE_WORLD_UNITS;
                double right = Math.min(state.worldWidth, left + FOG_TILE_WORLD_UNITS);
                double nearestX = Calc.clamp(sensor.x(), left, right);
                double dx = nearestX - sensor.x();
                double dy = nearestY - sensor.y();
                if (dx * dx + dy * dy <= sensor.rangeSquared()) out.set(state.tileIndex(column, row));
            }
        }
    }

    private static boolean addCoverage(SystemState state, BitSet cells) {
        boolean explorationChanged = false;
        for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1)) {
            int count = state.visibleCoverage[cell];
            if (count < Integer.MAX_VALUE) state.visibleCoverage[cell] = count + 1;
            if (count == 0) state.visible.set(cell);
            if (!state.explored.get(cell)) {
                state.explored.set(cell);
                explorationChanged = true;
            }
        }
        return explorationChanged;
    }

    private static void removeCoverage(SystemState state, BitSet cells) {
        if (cells == null || cells.isEmpty()) return;
        for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1)) {
            int count = state.visibleCoverage[cell];
            if (count <= 1) {
                state.visibleCoverage[cell] = 0;
                state.visible.clear(cell);
            } else {
                state.visibleCoverage[cell] = count - 1;
            }
        }
    }

    private static void attachSensorTiles(SystemState state, String key, BitSet tiles) {
        for (int tile = tiles.nextSetBit(0); tile >= 0; tile = tiles.nextSetBit(tile + 1)) {
            state.tileSensors.computeIfAbsent(tile, ignored -> new LinkedHashSet<>()).add(key);
        }
    }

    private static void detachSensorTiles(SystemState state, String key, BitSet tiles) {
        for (int tile = tiles.nextSetBit(0); tile >= 0; tile = tiles.nextSetBit(tile + 1)) {
            Set<String> keys = state.tileSensors.get(tile);
            if (keys == null) continue;
            keys.remove(key);
            if (keys.isEmpty()) state.tileSensors.remove(tile);
        }
    }

    private static void markTilesDirty(SystemState state, BitSet tiles) {
        if (tiles == null || tiles.isEmpty()) return;
        state.dirtyTiles.or(tiles);
        state.minimapDirtyTiles.or(tiles);
    }

    private static boolean sameSensorGeometry(VisibilityRules.Sensor first, VisibilityRules.Sensor second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return same(first.x(), second.x()) && same(first.y(), second.y()) && same(first.range(), second.range());
    }

    private static void paintExploration(SystemState state, List<VisibilityRules.Sensor> sensors) {
        if (sensors == null || sensors.isEmpty()) return;
        Graphics2D g = state.exploredFogMask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(EXPLORED);
        for (VisibilityRules.Sensor sensor : sensors) {
            if (sensor == null || sensor.range() <= 0) continue;
            double radius = sensor.range() / EXPLORATION_MASK_WORLD_UNITS;
            double x = sensor.x() / EXPLORATION_MASK_WORLD_UNITS - radius;
            double y = sensor.y() / EXPLORATION_MASK_WORLD_UNITS - radius;
            g.fill(new Ellipse2D.Double(x, y, radius * 2.0, radius * 2.0));
        }
        g.dispose();
    }

    private static void drawVisibleTiles(Graphics2D g, SystemState state, Rectangle2D view) {
        if (view == null || view.isEmpty()) return;
        int minColumn = clampCell((int)Math.floor(view.getMinX() / FOG_TILE_WORLD_UNITS), state.tileColumns);
        int maxColumn = clampCell((int)Math.floor(Math.max(view.getMinX(), view.getMaxX() - 0.0001)
                / FOG_TILE_WORLD_UNITS), state.tileColumns);
        int minRow = clampCell((int)Math.floor(view.getMinY() / FOG_TILE_WORLD_UNITS), state.tileRows);
        int maxRow = clampCell((int)Math.floor(Math.max(view.getMinY(), view.getMaxY() - 0.0001)
                / FOG_TILE_WORLD_UNITS), state.tileRows);
        Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                int tile = state.tileIndex(column, row);
                BufferedImage image = tileImage(state, tile);
                int x1 = column * FOG_TILE_WORLD_UNITS;
                int y1 = row * FOG_TILE_WORLD_UNITS;
                int x2 = Math.min(state.worldWidth, x1 + FOG_TILE_WORLD_UNITS);
                int y2 = Math.min(state.worldHeight, y1 + FOG_TILE_WORLD_UNITS);
                g.drawImage(image, x1, y1, x2, y2, 0, 0, image.getWidth(), image.getHeight(), null);
            }
        }
        if (oldInterpolation == null) g.getRenderingHints().remove(RenderingHints.KEY_INTERPOLATION);
        else g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
    }

    private static BufferedImage tileImage(SystemState state, int tile) {
        BufferedImage image = state.tileImages.get(tile);
        boolean cached = image != null;
        if (image == null) {
            image = new BufferedImage(FOG_TILE_PIXELS, FOG_TILE_PIXELS, BufferedImage.TYPE_INT_ARGB_PRE);
            state.tileImages.put(tile, image);
            trimTileCache(state);
        }
        if (!cached || state.dirtyTiles.get(tile)) {
            rebuildTile(state, tile, image);
            if (cached) state.partialFogCompositions++;
            else state.fullFogCompositions++;
        }
        return image;
    }

    private static void trimTileCache(SystemState state) {
        while (state.tileImages.size() > MAX_CACHED_TILES) {
            Iterator<Map.Entry<Integer, BufferedImage>> it = state.tileImages.entrySet().iterator();
            if (!it.hasNext()) return;
            it.next();
            it.remove();
        }
    }

    private static void rebuildTile(SystemState state, int tile, BufferedImage image) {
        int column = tile % state.tileColumns;
        int row = tile / state.tileColumns;
        double x = column * (double)FOG_TILE_WORLD_UNITS;
        double y = row * (double)FOG_TILE_WORLD_UNITS;
        double width = Math.max(1, Math.min(FOG_TILE_WORLD_UNITS, state.worldWidth - x));
        double height = Math.max(1, Math.min(FOG_TILE_WORLD_UNITS, state.worldHeight - y));
        Rectangle2D view = new Rectangle2D.Double(x, y, width, height);

        Graphics2D g = image.createGraphics();
        prepareBuffer(g, image.getWidth(), image.getHeight());
        drawExplorationSlice(g, state, view, image.getWidth(), image.getHeight());
        carveTileSensors(g, state, tile, view, image.getWidth(), image.getHeight());
        g.dispose();

        state.dirtyTiles.clear(tile);
        state.tileRebuilds++;
    }

    private static void carveTileSensors(Graphics2D g, SystemState state, int tile, Rectangle2D view,
                                         int width, int height) {
        Set<String> keys = state.tileSensors.get(tile);
        if (keys == null || keys.isEmpty()) return;
        g.setComposite(AlphaComposite.DstOut);
        double sx = width / Math.max(1.0, view.getWidth());
        double sy = height / Math.max(1.0, view.getHeight());
        for (String key : keys) {
            SensorCoverage coverage = state.sensorCoverage.get(key);
            VisibilityRules.Sensor sensor = coverage == null ? null : coverage.sensor;
            if (sensor == null || sensor.range() <= 0) continue;
            double centerX = (sensor.x() - view.getX()) * sx;
            double centerY = (sensor.y() - view.getY()) * sy;
            double radiusX = Math.max(0.5, sensor.range() * sx);
            double radiusY = Math.max(0.5, sensor.range() * sy);
            BufferedImage stamp = gradientStamp(sensor.range());
            int x1 = (int)Math.floor(centerX - radiusX);
            int y1 = (int)Math.floor(centerY - radiusY);
            int x2 = (int)Math.ceil(centerX + radiusX);
            int y2 = (int)Math.ceil(centerY + radiusY);
            g.drawImage(stamp, x1, y1, x2, y2, 0, 0, stamp.getWidth(), stamp.getHeight(), null);
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static BufferedImage composeMinimapFog(SystemState state, int width, int height, long now) {
        if (width <= 0 || height <= 0) return null;
        boolean resized = state.minimapFogBuffer == null
                || state.minimapFogBuffer.getWidth() != width || state.minimapFogBuffer.getHeight() != height;
        if (resized) {
            state.minimapFogBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = state.minimapFogBuffer.createGraphics();
            prepareBuffer(g, width, height);
            g.dispose();
            state.minimapFogRevision = -1;
        }
        if (!resized && (state.minimapFogRevision == state.fogRevision
                || now - state.lastMinimapRefreshNanos < MINIMAP_REFRESH_NANOS)) {
            return state.minimapFogBuffer;
        }

        state.lastMinimapRefreshNanos = now;
        Graphics2D g = state.minimapFogBuffer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (resized || state.minimapFogRevision < 0) {
            for (int tile = 0; tile < state.tileColumns * state.tileRows; tile++) {
                drawMinimapTile(g, state, tile, width, height);
            }
            state.minimapDirtyTiles.clear();
        } else {
            for (int tile = state.minimapDirtyTiles.nextSetBit(0); tile >= 0;
                 tile = state.minimapDirtyTiles.nextSetBit(tile + 1)) {
                drawMinimapTile(g, state, tile, width, height);
            }
            state.minimapDirtyTiles.clear();
        }
        g.dispose();
        state.minimapFogRevision = state.fogRevision;
        return state.minimapFogBuffer;
    }

    private static void drawMinimapTile(Graphics2D g, SystemState state, int tile, int width, int height) {
        BufferedImage image = tileImage(state, tile);
        int column = tile % state.tileColumns;
        int row = tile / state.tileColumns;
        double wx1 = column * (double)FOG_TILE_WORLD_UNITS;
        double wy1 = row * (double)FOG_TILE_WORLD_UNITS;
        double wx2 = Math.min(state.worldWidth, wx1 + FOG_TILE_WORLD_UNITS);
        double wy2 = Math.min(state.worldHeight, wy1 + FOG_TILE_WORLD_UNITS);
        int x1 = (int)Math.floor(wx1 / state.worldWidth * width);
        int y1 = (int)Math.floor(wy1 / state.worldHeight * height);
        int x2 = (int)Math.ceil(wx2 / state.worldWidth * width);
        int y2 = (int)Math.ceil(wy2 / state.worldHeight * height);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, x1, y1, x2, y2, 0, 0, image.getWidth(), image.getHeight(), null);
        state.minimapPatches++;
    }

    private static void prepareBuffer(Graphics2D g, int width, int height) {
        g.setComposite(AlphaComposite.Src);
        g.setColor(UNEXPLORED);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static void drawExplorationSlice(Graphics2D g, SystemState state, Rectangle2D view,
                                             int width, int height) {
        double scaleX = EXPLORATION_MASK_WORLD_UNITS * width / Math.max(1.0, view.getWidth());
        double scaleY = EXPLORATION_MASK_WORLD_UNITS * height / Math.max(1.0, view.getHeight());
        double translateX = -view.getX() * width / Math.max(1.0, view.getWidth());
        double translateY = -view.getY() * height / Math.max(1.0, view.getHeight());
        AffineTransform transform = new AffineTransform(scaleX, 0, 0, scaleY, translateX, translateY);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(state.exploredFogMask, transform, null);
    }

    private static BufferedImage gradientStamp(double sensorRange) {
        double inner = Calc.clamp(1.0 - EDGE_FEATHER_WORLD_UNITS / Math.max(1.0, sensorRange), 0.08, 0.92);
        int bucket = Math.max(2, Math.min(18, (int)Math.round(inner * 20.0)));
        BufferedImage cached = GRADIENT_STAMPS.get(bucket);
        if (cached != null) return cached;
        BufferedImage stamp = new BufferedImage(GRADIENT_STAMP_SIZE, GRADIENT_STAMP_SIZE,
                BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = stamp.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float center = (GRADIENT_STAMP_SIZE - 1) * 0.5f;
        float radius = center;
        float stop = bucket / 20.0f;
        RadialGradientPaint gradient = new RadialGradientPaint(
                new Point2D.Float(center, center), radius,
                new float[]{0f, stop, 1f},
                new Color[]{new Color(255, 255, 255, 255), new Color(255, 255, 255, 255),
                        new Color(255, 255, 255, 0)});
        g.setPaint(gradient);
        g.fill(new Ellipse2D.Float(0, 0, GRADIENT_STAMP_SIZE - 1, GRADIENT_STAMP_SIZE - 1));
        g.dispose();
        GRADIENT_STAMPS.put(bucket, stamp);
        return stamp;
    }

    private static Rectangle2D visibleWorldBounds(Graphics2D g, World world) {
        Rectangle clip = g.getClipBounds();
        if (clip == null) return new Rectangle2D.Double(0, 0, world.width, world.height);
        double x1 = Calc.clamp(clip.getMinX(), 0, world.width);
        double y1 = Calc.clamp(clip.getMinY(), 0, world.height);
        double x2 = Calc.clamp(clip.getMaxX(), 0, world.width);
        double y2 = Calc.clamp(clip.getMaxY(), 0, world.height);
        return new Rectangle2D.Double(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }

    private static boolean same(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private static void observeContacts(World world, String playerId, VisibilityRules.Frame frame, SystemState state) {
        state.liveContacts.clear();
        double time = world.systemTime();
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || IntelWarfareSystem.allied(world, playerId, unit.playerId)) continue;
            IntelWarfareSystem.DetectionStage stage = frame.unitStage(unit);
            if (!stage.atLeast(IntelWarfareSystem.DetectionStage.CONTACT)) continue;
            String key = "U:" + unit.key();
            state.liveContacts.add(key);
            LastKnownContact previous = state.contacts.get(key);
            double elapsed = previous == null ? 0 : Math.max(0.05, time - previous.lastSeenSystemTime());
            double vx = previous == null ? 0 : (unit.x - previous.x()) / elapsed;
            double vy = previous == null ? 0 : (unit.y - previous.y()) / elapsed;
            String type = stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)
                    ? unit.shipTypeId : contactLabel(unit, stage);
            state.contacts.put(key, new LastKnownContact(key, unit.playerId, type, false, unit.x, unit.y,
                    vx, vy, stage, time, false));
        }
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || IntelWarfareSystem.allied(world, playerId, base.playerId)) continue;
            IntelWarfareSystem.DetectionStage stage = frame.baseStage(base);
            if (!stage.atLeast(IntelWarfareSystem.DetectionStage.CONTACT)) continue;
            String key = "B:" + base.id;
            state.liveContacts.add(key);
            String type = stage.atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED)
                    ? base.typeId : "STATION CONTACT";
            state.contacts.put(key, new LastKnownContact(key, base.playerId, type, true, base.x, base.y,
                    0, 0, stage, time, IntelWarfareSystem.isDecoy(base.typeId)
                    && !stage.atLeast(IntelWarfareSystem.DetectionStage.DETAILED)));
        }
        Iterator<Map.Entry<String, LastKnownContact>> iterator = state.contacts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LastKnownContact> entry = iterator.next();
            LastKnownContact contact = entry.getValue();
            double age = Math.max(0, time - contact.lastSeenSystemTime());
            if (age > CONTACT_MEMORY_SECONDS
                    || !state.liveContacts.contains(entry.getKey()) && frame.pointVisible(contact.x(), contact.y())
                    && age >= CONTACT_CLEAR_CONFIRM_SECONDS) iterator.remove();
        }
        while (state.contacts.size() > MAX_CONTACTS) {
            Iterator<String> keys = state.contacts.keySet().iterator();
            if (!keys.hasNext()) break;
            keys.next();
            keys.remove();
        }
    }

    private static String contactLabel(Unit unit, IntelWarfareSystem.DetectionStage stage) {
        if (stage == IntelWarfareSystem.DetectionStage.CONTACT) return "UNKNOWN CONTACT";
        double size = unit.type().size.scale;
        if (size <= 1.15) return "SMALL SHIP CONTACT";
        if (size >= 2.6) return "LARGE SHIP CONTACT";
        return "SHIP CONTACT";
    }

    private static boolean observeWormholes(World world, VisibilityRules.Frame frame, SystemState state) {
        boolean changed = false;
        for (WormholeGate gate : world.wormholes) {
            if (gate == null || !frame.pointVisible(gate.x, gate.y)) continue;
            String key = gate.id == null || gate.id.isBlank()
                    ? gate.toSystemId + ':' + Math.round(gate.x) + ':' + Math.round(gate.y) : gate.id;
            KnownWormhole observed = new KnownWormhole(key, gate.toSystemId, gate.x, gate.y);
            changed |= !observed.equals(state.wormholes.put(key, observed));
        }
        return changed;
    }

    private static void drawContacts(Graphics2D g, World world, SystemState state, double scaleX, double scaleY,
                                     int offsetX, int offsetY) {
        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        double now = world.systemTime();
        for (LastKnownContact contact : state.contacts.values()) {
            if (state.liveContacts.contains(contact.key())) continue;
            double age = Math.max(0, now - contact.lastSeenSystemTime());
            double projectionSeconds = Math.min(6, age);
            double predictedWorldX = contact.x() + contact.vx() * projectionSeconds;
            double predictedWorldY = contact.y() + contact.vy() * projectionSeconds;
            if (scaleX >= 0.8 && !RenderCulling.visible(g, predictedWorldX, predictedWorldY, 220)) continue;
            double x = offsetX + predictedWorldX * scaleX;
            double y = offsetY + predictedWorldY * scaleY;
            double uncertaintyWorld = IntelWarfareSystem.uncertainty(contact.stage(), age);
            double uncertainty = Math.max(4, uncertaintyWorld * Math.max(0.001, (scaleX + scaleY) * 0.5));
            if (scaleX >= 0.8) uncertainty = Math.max(12, uncertainty);
            else uncertainty = Math.min(32, uncertainty);
            Color owner = PlayerRegistry.color(contact.ownerId());
            int alpha = Math.max(65, (int)Math.round(190 * (1.0 - age / CONTACT_MEMORY_SECONDS)));
            g.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), alpha));
            g.setStroke(new BasicStroke((float)Math.max(1, 1.8 * Math.max(0.45, scaleX)), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 0, new float[]{6f, 5f}, (float)(age * 2)));
            g.draw(new Ellipse2D.Double(x - uncertainty, y - uncertainty, uncertainty * 2, uncertainty * 2));
            double marker = Math.max(4, (contact.base() ? 10 : 7) * Math.max(0.4, Math.min(1.0, scaleX)));
            g.drawLine((int)Math.round(x - marker), (int)Math.round(y), (int)Math.round(x + marker), (int)Math.round(y));
            g.drawLine((int)Math.round(x), (int)Math.round(y - marker), (int)Math.round(x), (int)Math.round(y + marker));

            double speed = Math.hypot(contact.vx(), contact.vy());
            if (speed > 1 && scaleX >= 0.15) {
                double length = Math.min(120, speed * 0.7) * Math.max(0.15, scaleX);
                double dx = contact.vx() / speed * length;
                double dy = contact.vy() / speed * length;
                g.setStroke(new BasicStroke((float)Math.max(1, 1.4 * Math.max(0.45, scaleX))));
                g.drawLine((int)Math.round(x), (int)Math.round(y), (int)Math.round(x + dx), (int)Math.round(y + dy));
            }
            if (scaleX >= 0.8) {
                g.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
                String warning = contact.decoySuspected() ? " | ANOMALOUS" : "";
                String label = "LAST KNOWN " + contact.typeId() + " | " + Math.round(age) + "s ago"
                        + " | ±" + Math.round(uncertaintyWorld) + warning;
                g.drawString(label, (int)Math.round(x + marker + 7), (int)Math.round(y - 4));
            }
        }
        g.setStroke(oldStroke);
        g.setFont(oldFont);
    }

    private static int clampCell(int value, int count) {
        return Math.max(0, Math.min(Math.max(0, count - 1), value));
    }

    record KnownWormhole(String id, String toSystemId, double x, double y) { }
    record LastKnownContact(String key, String ownerId, String typeId, boolean base, double x, double y,
                            double vx, double vy, IntelWarfareSystem.DetectionStage stage,
                            double lastSeenSystemTime, boolean decoySuspected) { }
    private record SensorUpdate(boolean changed, boolean explorationChanged,
                                List<VisibilityRules.Sensor> explorationSensors) { }

    private static final class SensorCoverage {
        VisibilityRules.Sensor sensor;
        final BitSet cells = new BitSet();
        final BitSet tiles = new BitSet();
    }

    private static final class WorldState {
        final Map<String, SystemState> systems = new LinkedHashMap<>();
    }

    private static final class SystemState {
        final String systemId;
        final long environmentSeed;
        final int columns;
        final int rows;
        final int worldWidth;
        final int worldHeight;
        final int maskWidth;
        final int maskHeight;
        final int tileColumns;
        final int tileRows;
        final BitSet explored;
        final BitSet visible;
        final int[] visibleCoverage;
        final BufferedImage exploredFogMask;
        final Map<String, SensorCoverage> sensorCoverage = new LinkedHashMap<>();
        final Map<Integer, LinkedHashSet<String>> tileSensors = new LinkedHashMap<>();
        final BitSet dirtyTiles = new BitSet();
        final BitSet minimapDirtyTiles = new BitSet();
        final LinkedHashMap<Integer, BufferedImage> tileImages =
                new LinkedHashMap<>(32, 0.75f, true);
        final Map<String, VisibilityRules.Sensor> nextSensorsScratch = new LinkedHashMap<>();
        final List<VisibilityRules.Sensor> explorationSensorsScratch = new ArrayList<>();
        final Map<String, LastKnownContact> contacts = new LinkedHashMap<>();
        final Set<String> liveContacts = new LinkedHashSet<>();
        final Map<String, KnownWormhole> wormholes = new LinkedHashMap<>();
        BufferedImage minimapFogBuffer;
        long fogRevision;
        long minimapFogRevision = -1;
        long lastUpdateNanos;
        long lastMinimapRefreshNanos;
        long sensorCoverageRebuilds;
        long tileRebuilds;
        long fullFogCompositions;
        long partialFogCompositions;
        long minimapPatches;

        SystemState(String systemId, long environmentSeed, int columns, int rows, int worldWidth, int worldHeight) {
            this.systemId = systemId;
            this.environmentSeed = environmentSeed;
            this.columns = columns;
            this.rows = rows;
            this.worldWidth = worldWidth;
            this.worldHeight = worldHeight;
            maskWidth = Math.max(2, (int)Math.ceil(worldWidth / EXPLORATION_MASK_WORLD_UNITS));
            maskHeight = Math.max(2, (int)Math.ceil(worldHeight / EXPLORATION_MASK_WORLD_UNITS));
            tileColumns = Math.max(1, (int)Math.ceil(worldWidth / (double)FOG_TILE_WORLD_UNITS));
            tileRows = Math.max(1, (int)Math.ceil(worldHeight / (double)FOG_TILE_WORLD_UNITS));
            explored = new BitSet(columns * rows);
            visible = new BitSet(columns * rows);
            visibleCoverage = new int[columns * rows];
            exploredFogMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = exploredFogMask.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setColor(UNEXPLORED);
            g.fillRect(0, 0, maskWidth, maskHeight);
            g.dispose();
        }

        void restore(FogOfWarPersistence.Stored stored) {
            if (stored == null) return;
            explored.or(stored.explored());
            for (KnownWormhole gate : stored.wormholes()) {
                if (gate != null && gate.id() != null && !gate.id().isBlank()) wormholes.put(gate.id(), gate);
            }
            rebuildExplorationMask();
        }

        private void rebuildExplorationMask() {
            Graphics2D g = exploredFogMask.createGraphics();
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(EXPLORED);
            for (int cell = explored.nextSetBit(0); cell >= 0; cell = explored.nextSetBit(cell + 1)) {
                int column = cell % columns;
                int row = cell / columns;
                int x1 = (int)Math.floor(column * CELL_SIZE / EXPLORATION_MASK_WORLD_UNITS);
                int y1 = (int)Math.floor(row * CELL_SIZE / EXPLORATION_MASK_WORLD_UNITS);
                int x2 = (int)Math.ceil((column + 1.0) * CELL_SIZE / EXPLORATION_MASK_WORLD_UNITS);
                int y2 = (int)Math.ceil((row + 1.0) * CELL_SIZE / EXPLORATION_MASK_WORLD_UNITS);
                g.fillRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
            }
            g.dispose();
        }

        int index(int column, int row) {
            if (column < 0 || row < 0 || column >= columns || row >= rows) return -1;
            return row * columns + column;
        }

        int tileIndex(int column, int row) {
            return row * tileColumns + column;
        }

        int cell(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0
                    || x > worldWidth || y > worldHeight) return -1;
            int column = clampCell((int)Math.floor(x / CELL_SIZE), columns);
            int row = clampCell((int)Math.floor(y / CELL_SIZE), rows);
            return index(column, row);
        }
    }
}
