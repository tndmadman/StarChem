package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
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
    private static final long UPDATE_INTERVAL_NANOS = 80_000_000L;
    private static final int MAX_CONTACTS = 512;
    private static final Color UNEXPLORED = new Color(1, 3, 7);
    private static final Color EXPLORED = new Color(8, 14, 22);
    private static final Map<World, WorldState> STATES = new WeakHashMap<>();

    private FogOfWarView() { }

    static synchronized void drawWorld(Graphics2D source, World world) {
        SystemState state = update(world, System.nanoTime());
        if (source == null || world == null || state == null) return;
        Graphics2D g = (Graphics2D)source.create();
        drawFog(g, state, 0, 0, world.width, world.height, 1.0, 1.0);
        drawContacts(g, world, state, 1.0, 1.0, 0, 0);
        g.dispose();
    }

    static synchronized void drawMinimap(Graphics2D source, World world, Rectangle map) {
        SystemState state = update(world, System.nanoTime());
        if (source == null || world == null || map == null || state == null) return;
        Graphics2D g = (Graphics2D)source.create();
        double sx = map.width / Math.max(1.0, world.width);
        double sy = map.height / Math.max(1.0, world.height);
        drawFog(g, state, map.x, map.y, map.width, map.height, sx, sy);
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

    static synchronized int exploredCellCount(World world) {
        SystemState state = update(world, System.nanoTime());
        return state == null ? 0 : state.explored.cardinality();
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
        if (state == null || state.columns != columns || state.rows != rows) {
            state = new SystemState(systemId, columns, rows);
            worldState.systems.put(key, state);
        }
        if (now - state.lastUpdateNanos < UPDATE_INTERVAL_NANOS) return state;
        state.lastUpdateNanos = now;
        state.visible.clear();
        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        for (VisibilityRules.Sensor sensor : frame.sensors()) reveal(state, sensor);
        state.explored.or(state.visible);
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

    private static void observeContacts(World world, String playerId, VisibilityRules.Frame frame, SystemState state) {
        Set<String> live = new LinkedHashSet<>();
        double time = world.systemTime();
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || playerId.equals(unit.playerId) || !frame.unitVisible(unit)) continue;
            String key = "U:" + unit.key();
            live.add(key);
            state.contacts.put(key, new LastKnownContact(key, unit.playerId, unit.shipTypeId, false, unit.x, unit.y, time));
        }
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || playerId.equals(base.playerId) || !frame.baseVisible(base)) continue;
            String key = "B:" + base.id;
            live.add(key);
            state.contacts.put(key, new LastKnownContact(key, base.playerId, base.typeId, true, base.x, base.y, time));
        }
        Iterator<Map.Entry<String, LastKnownContact>> iterator = state.contacts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LastKnownContact> entry = iterator.next();
            LastKnownContact contact = entry.getValue();
            if (!live.contains(entry.getKey()) && frame.pointVisible(contact.x(), contact.y())) iterator.remove();
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

    private static void drawFog(Graphics2D g, SystemState state, int offsetX, int offsetY, int width, int height,
                                double scaleX, double scaleY) {
        for (int row = 0; row < state.rows; row++) {
            int column = 0;
            while (column < state.columns) {
                int index = state.index(column, row);
                int mode = state.visible.get(index) ? 0 : state.explored.get(index) ? 1 : 2;
                if (mode == 0) {
                    column++;
                    continue;
                }
                int end = column + 1;
                while (end < state.columns) {
                    int next = state.index(end, row);
                    int nextMode = state.visible.get(next) ? 0 : state.explored.get(next) ? 1 : 2;
                    if (nextMode != mode) break;
                    end++;
                }
                g.setColor(mode == 1 ? EXPLORED : UNEXPLORED);
                int x = offsetX + (int)Math.floor(column * CELL_SIZE * scaleX);
                int y = offsetY + (int)Math.floor(row * CELL_SIZE * scaleY);
                int x2 = offsetX + (int)Math.ceil(Math.min(width / Math.max(scaleX, 0.000001), end * (double)CELL_SIZE) * scaleX);
                int y2 = offsetY + (int)Math.ceil(Math.min(height / Math.max(scaleY, 0.000001), (row + 1.0) * CELL_SIZE) * scaleY);
                g.fillRect(x, y, Math.max(1, x2 - x), Math.max(1, y2 - y));
                column = end;
            }
        }
    }

    private static void drawContacts(Graphics2D g, World world, SystemState state, double scaleX, double scaleY,
                                     int offsetX, int offsetY) {
        BasicStroke oldStroke = (BasicStroke)g.getStroke();
        Font oldFont = g.getFont();
        for (LastKnownContact contact : new ArrayList<>(state.contacts.values())) {
            int cell = state.cell(contact.x(), contact.y());
            if (cell >= 0 && state.visible.get(cell)) continue;
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
        final BitSet explored;
        final BitSet visible;
        final Map<String, LastKnownContact> contacts = new LinkedHashMap<>();
        final Map<String, KnownWormhole> wormholes = new LinkedHashMap<>();
        long lastUpdateNanos;

        SystemState(String systemId, int columns, int rows) {
            this.systemId = systemId;
            this.columns = columns;
            this.rows = rows;
            explored = new BitSet(columns * rows);
            visible = new BitSet(columns * rows);
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
