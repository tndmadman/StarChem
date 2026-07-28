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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
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
    private static final double MASK_WORLD_UNITS = 32.0;
    private static final double EDGE_FEATHER_WORLD_UNITS = 96.0;
    private static final long UPDATE_INTERVAL_NANOS = 80_000_000L;
    private static final double CONTACT_CLEAR_CONFIRM_SECONDS = 2.0;
    private static final int MAX_CONTACTS = 512;
    private static final Color UNEXPLORED = new Color(1, 3, 7);
    private static final Color EXPLORED = new Color(8, 14, 22);
    private static final Map<World, WorldState> STATES = new WeakHashMap<>();

    private FogOfWarView() { }

    static synchronized void drawWorld(Graphics2D source, World world) {
        SystemState state = update(world, System.nanoTime());
        if (source == null || world == null || state == null) return;
        Graphics2D g = (Graphics2D)source.create();
        drawFog(g, state, 0, 0, world.width, world.height);
        drawContacts(g, world, state, 1.0, 1.0, 0, 0);
        g.dispose();
    }

    static synchronized void drawMinimap(Graphics2D source, World world, Rectangle map) {
        SystemState state = update(world, System.nanoTime());
        if (source == null || world == null || map == null || state == null) return;
        Graphics2D g = (Graphics2D)source.create();
        double sx = map.width / Math.max(1.0, world.width);
        double sy = map.height / Math.max(1.0, world.height);
        drawFog(g, state, map.x, map.y, map.width, map.height);
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
        if (state == null || state.fogMask == null) return 0;
        int count = 0;
        for (int y = 0; y < state.fogMask.getHeight(); y++) {
            for (int x = 0; x < state.fogMask.getWidth(); x++) {
                int alpha = state.fogMask.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < 255) count++;
            }
        }
        return count;
    }

    static synchronized void forceRefreshForTest(World world) {
        WorldState worldState = STATES.get(world);
        if (worldState != null) for (SystemState state : worldState.systems.values()) state.lastUpdateNanos = 0;
        update(world, System.nanoTime());
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
        SystemState state = worldState.systems.get(key);
        if (state == null || state.columns != columns || state.rows != rows
                || state.worldWidth != world.width || state.worldHeight != world.height) {
            state = new SystemState(systemId, columns, rows, world.width, world.height);
            worldState.systems.put(key, state);
        }
        if (now - state.lastUpdateNanos < UPDATE_INTERVAL_NANOS) return state;
        state.lastUpdateNanos = now;
        state.visible.clear();
        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        for (VisibilityRules.Sensor sensor : frame.sensors()) reveal(state, sensor);
        state.explored.or(state.visible);
        paintExploration(state, frame.sensors());
        rebuildFogMask(state, frame.sensors());
        observeContacts(world, playerId, frame, state);
        observeWormholes(world, frame, state);
        return state;
    }

    private static void reveal(SystemState state, VisibilityRules.Sensor sensor) {
        if (sensor == null || sensor.range() <= 0) return;
        int minColumn = clampCell((int)Math.floor((sensor.x() - sensor.range()) / CELL_SIZE), state.columns);
        int maxColumn = clampCell((int)Math.floor((sensor.x() + sensor.range()) / CELL_SIZE), state.columns);
        int minRow = clampCell((int)Math.floor((sensor.y() - sensor.range()) / CELL_SIZE), state.rows);
        int maxRow = clampCell((int)Math.floor((sensor.y() + sensor.range()) / CELL_SIZE), state.rows);
        for (int row = minRow; row <= maxRow; row++) {
            double top = row * (double)CELL_SIZE;
            double bottom = top + CELL_SIZE;
            double nearestY = Calc.clamp(sensor.y(), top, bottom);
            for (int column = minColumn; column <= maxColumn; column++) {
                double left = column * (double)CELL_SIZE;
                double right = left + CELL_SIZE;
                double nearestX = Calc.clamp(sensor.x(), left, right);
                double dx = nearestX - sensor.x();
                double dy = nearestY - sensor.y();
                if (dx * dx + dy * dy <= sensor.rangeSquared()) state.visible.set(state.index(column, row));
            }
        }
    }

    private static void paintExploration(SystemState state, List<VisibilityRules.Sensor> sensors) {
        Graphics2D g = state.exploredMask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        for (VisibilityRules.Sensor sensor : sensors) {
            if (sensor == null || sensor.range() <= 0) continue;
            double radius = sensor.range() / MASK_WORLD_UNITS;
            double x = sensor.x() / MASK_WORLD_UNITS - radius;
            double y = sensor.y() / MASK_WORLD_UNITS - radius;
            g.fill(new Ellipse2D.Double(x, y, radius * 2.0, radius * 2.0));
        }
        g.dispose();
    }

    private static void rebuildFogMask(SystemState state, List<VisibilityRules.Sensor> sensors) {
        Raster explored = state.exploredMask.getRaster();
        for (int y = 0; y < state.maskHeight; y++) {
            for (int x = 0; x < state.maskWidth; x++) {
                double coverage = smoothedCoverage(explored, x, y, state.maskWidth, state.maskHeight);
                int red = blend(UNEXPLORED.getRed(), EXPLORED.getRed(), coverage);
                int green = blend(UNEXPLORED.getGreen(), EXPLORED.getGreen(), coverage);
                int blue = blend(UNEXPLORED.getBlue(), EXPLORED.getBlue(), coverage);
                state.fogMask.setRGB(x, y, 0xFF000000 | red << 16 | green << 8 | blue);
            }
        }

        Graphics2D g = state.fogMask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.DstOut);
        for (VisibilityRules.Sensor sensor : sensors) carveVisibleSensor(g, sensor);
        g.dispose();
    }

    private static double smoothedCoverage(Raster raster, int x, int y, int width, int height) {
        int weighted = 0;
        int totalWeight = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int sampleY = Math.max(0, Math.min(height - 1, y + dy));
            int wy = dy == 0 ? 2 : 1;
            for (int dx = -1; dx <= 1; dx++) {
                int sampleX = Math.max(0, Math.min(width - 1, x + dx));
                int wx = dx == 0 ? 2 : 1;
                int weight = wx * wy;
                weighted += raster.getSample(sampleX, sampleY, 0) * weight;
                totalWeight += weight;
            }
        }
        return weighted / (255.0 * totalWeight);
    }

    private static void carveVisibleSensor(Graphics2D g, VisibilityRules.Sensor sensor) {
        if (sensor == null || sensor.range() <= 0) return;
        float radius = (float)Math.max(1.0, sensor.range() / MASK_WORLD_UNITS);
        float centerX = (float)(sensor.x() / MASK_WORLD_UNITS);
        float centerY = (float)(sensor.y() / MASK_WORLD_UNITS);
        float inner = (float)Calc.clamp(1.0 - EDGE_FEATHER_WORLD_UNITS / sensor.range(), 0.08, 0.92);
        RadialGradientPaint fade = new RadialGradientPaint(
                new Point2D.Float(centerX, centerY), radius,
                new float[]{0f, inner, 1f},
                new Color[]{new Color(255, 255, 255, 255), new Color(255, 255, 255, 255),
                        new Color(255, 255, 255, 0)});
        g.setPaint(fade);
        g.fill(new Ellipse2D.Float(centerX - radius, centerY - radius, radius * 2f, radius * 2f));
    }

    private static int blend(int hidden, int explored, double amount) {
        return (int)Math.round(hidden + (explored - hidden) * Calc.clamp(amount, 0, 1));
    }

    private static void observeContacts(World world, String playerId, VisibilityRules.Frame frame, SystemState state) {
        state.liveContacts.clear();
        double time = world.systemTime();
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || playerId.equals(unit.playerId) || !frame.unitVisible(unit)) continue;
            String key = "U:" + unit.key();
            state.liveContacts.add(key);
            state.contacts.put(key, new LastKnownContact(key, unit.playerId, unit.shipTypeId, false, unit.x, unit.y, time));
        }
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || playerId.equals(base.playerId) || !frame.baseVisible(base)) continue;
            String key = "B:" + base.id;
            state.liveContacts.add(key);
            state.contacts.put(key, new LastKnownContact(key, base.playerId, base.typeId, true, base.x, base.y, time));
        }
        Iterator<Map.Entry<String, LastKnownContact>> iterator = state.contacts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LastKnownContact> entry = iterator.next();
            LastKnownContact contact = entry.getValue();
            double age = Math.max(0, time - contact.lastSeenSystemTime());
            if (!state.liveContacts.contains(entry.getKey()) && frame.pointVisible(contact.x(), contact.y())
                    && age >= CONTACT_CLEAR_CONFIRM_SECONDS) iterator.remove();
        }
        while (state.contacts.size() > MAX_CONTACTS) {
            Iterator<String> keys = state.contacts.keySet().iterator();
            if (!keys.hasNext()) break;
            keys.next();
            keys.remove();
        }
    }

    private static void observeWormholes(World world, VisibilityRules.Frame frame, SystemState state) {
        for (WormholeGate gate : world.wormholes) {
            if (gate == null || !frame.pointVisible(gate.x, gate.y)) continue;
            String key = gate.id == null || gate.id.isBlank()
                    ? gate.toSystemId + ':' + Math.round(gate.x) + ':' + Math.round(gate.y) : gate.id;
            state.wormholes.put(key, new KnownWormhole(key, gate.toSystemId, gate.x, gate.y));
        }
    }

    private static void drawFog(Graphics2D g, SystemState state, int offsetX, int offsetY, int width, int height) {
        if (state.fogMask == null) return;
        Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(state.fogMask, offsetX, offsetY, offsetX + width, offsetY + height,
                0, 0, state.maskWidth, state.maskHeight, null);
        if (oldInterpolation == null) g.getRenderingHints().remove(RenderingHints.KEY_INTERPOLATION);
        else g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
    }

    private static void drawContacts(Graphics2D g, World world, SystemState state, double scaleX, double scaleY,
                                     int offsetX, int offsetY) {
        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        for (LastKnownContact contact : new ArrayList<>(state.contacts.values())) {
            if (state.liveContacts.contains(contact.key())) continue;
            double x = offsetX + contact.x() * scaleX;
            double y = offsetY + contact.y() * scaleY;
            double radius = Math.max(4, (contact.base() ? 18 : 12) * Math.max(0.35, Math.min(1.0, scaleX)));
            Color owner = PlayerRegistry.color(contact.ownerId());
            g.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 175));
            g.setStroke(new BasicStroke((float)Math.max(1, 1.8 * Math.max(0.45, scaleX)), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 0, new float[]{5f, 4f}, 0));
            g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            g.drawLine((int)Math.round(x - radius), (int)Math.round(y), (int)Math.round(x + radius), (int)Math.round(y));
            g.drawLine((int)Math.round(x), (int)Math.round(y - radius), (int)Math.round(x), (int)Math.round(y + radius));
            if (scaleX >= 0.8) {
                g.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
                g.drawString("LAST KNOWN " + contact.typeId(), (int)Math.round(x + radius + 5), (int)Math.round(y - 3));
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
                            double lastSeenSystemTime) { }

    private static final class WorldState {
        final Map<String, SystemState> systems = new LinkedHashMap<>();
    }

    private static final class SystemState {
        final String systemId;
        final int columns;
        final int rows;
        final int worldWidth;
        final int worldHeight;
        final int maskWidth;
        final int maskHeight;
        final BitSet explored;
        final BitSet visible;
        final BufferedImage exploredMask;
        final BufferedImage fogMask;
        final Map<String, LastKnownContact> contacts = new LinkedHashMap<>();
        final Set<String> liveContacts = new LinkedHashSet<>();
        final Map<String, KnownWormhole> wormholes = new LinkedHashMap<>();
        long lastUpdateNanos;

        SystemState(String systemId, int columns, int rows, int worldWidth, int worldHeight) {
            this.systemId = systemId;
            this.columns = columns;
            this.rows = rows;
            this.worldWidth = worldWidth;
            this.worldHeight = worldHeight;
            maskWidth = Math.max(2, (int)Math.ceil(worldWidth / MASK_WORLD_UNITS));
            maskHeight = Math.max(2, (int)Math.ceil(worldHeight / MASK_WORLD_UNITS));
            explored = new BitSet(columns * rows);
            visible = new BitSet(columns * rows);
            exploredMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_BYTE_GRAY);
            fogMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_ARGB_PRE);
        }

        int index(int column, int row) { return row * columns + column; }

        int cell(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) return -1;
            int column = (int)Math.floor(x / CELL_SIZE);
            int row = (int)Math.floor(y / CELL_SIZE);
            if (column < 0 || row < 0 || column >= columns || row >= rows) return -1;
            return index(column, row);
        }
    }
}
