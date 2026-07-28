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
    private static final double WORLD_BUFFER_SCALE = 0.5;
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
        if (view.getWidth() > 0 && view.getHeight() > 0) {
            double zoom = transformScale(g.getTransform());
            BufferedImage fog = composeWorldFog(state, view, zoom);
            drawBuffer(g, fog, view);
        }
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
        if (state == null || state.exploredFogMask == null) return 0;
        int unexplored = UNEXPLORED.getRGB() & 0x00FFFFFF;
        int explored = EXPLORED.getRGB() & 0x00FFFFFF;
        int count = 0;
        for (int y = 0; y < state.exploredFogMask.getHeight(); y++) {
            for (int x = 0; x < state.exploredFogMask.getWidth(); x++) {
                int rgb = state.exploredFogMask.getRGB(x, y) & 0x00FFFFFF;
                if (rgb != unexplored && rgb != explored) count++;
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
        state.sensors = frame.sensors();
        for (VisibilityRules.Sensor sensor : state.sensors) reveal(state, sensor);
        state.explored.or(state.visible);
        paintExploration(state, state.sensors);
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

    private static BufferedImage composeWorldFog(SystemState state, Rectangle2D view, double zoom) {
        int width = clampBufferSize((int)Math.ceil(view.getWidth() * zoom * WORLD_BUFFER_SCALE));
        int height = clampBufferSize((int)Math.ceil(view.getHeight() * zoom * WORLD_BUFFER_SCALE));
        state.worldFogBuffer = ensureBuffer(state.worldFogBuffer, width, height);
        Graphics2D g = state.worldFogBuffer.createGraphics();
        prepareBuffer(g, width, height);
        drawExplorationSlice(g, state, view, width, height);
        carveSensors(g, state.sensors, view, width, height);
        g.dispose();
        return state.worldFogBuffer;
    }

    private static BufferedImage composeMinimapFog(SystemState state, int width, int height, long now) {
        if (width <= 0 || height <= 0) return null;
        boolean resized = state.minimapFogBuffer == null
                || state.minimapFogBuffer.getWidth() != width || state.minimapFogBuffer.getHeight() != height;
        state.minimapFogBuffer = ensureBuffer(state.minimapFogBuffer, width, height);
        if (!resized && now - state.lastMinimapRefreshNanos < MINIMAP_REFRESH_NANOS) return state.minimapFogBuffer;
        state.lastMinimapRefreshNanos = now;
        Graphics2D g = state.minimapFogBuffer.createGraphics();
        prepareBuffer(g, width, height);
        Rectangle2D wholeWorld = new Rectangle2D.Double(0, 0, state.worldWidth, state.worldHeight);
        drawExplorationSlice(g, state, wholeWorld, width, height);
        carveSensors(g, state.sensors, wholeWorld, width, height);
        g.dispose();
        return state.minimapFogBuffer;
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

    private static void carveSensors(Graphics2D g, List<VisibilityRules.Sensor> sensors, Rectangle2D view,
                                     int width, int height) {
        if (sensors == null || sensors.isEmpty()) return;
        g.setComposite(AlphaComposite.DstOut);
        double sx = width / Math.max(1.0, view.getWidth());
        double sy = height / Math.max(1.0, view.getHeight());
        for (VisibilityRules.Sensor sensor : sensors) {
            if (sensor == null || sensor.range() <= 0) continue;
            double centerX = (sensor.x() - view.getX()) * sx;
            double centerY = (sensor.y() - view.getY()) * sy;
            double radiusX = Math.max(0.5, sensor.range() * sx - 1.5);
            double radiusY = Math.max(0.5, sensor.range() * sy - 1.5);
            if (centerX + radiusX < 0 || centerY + radiusY < 0
                    || centerX - radiusX > width || centerY - radiusY > height) continue;
            BufferedImage stamp = gradientStamp(sensor.range());
            int x1 = (int)Math.floor(centerX - radiusX);
            int y1 = (int)Math.floor(centerY - radiusY);
            int x2 = (int)Math.ceil(centerX + radiusX);
            int y2 = (int)Math.ceil(centerY + radiusY);
            g.drawImage(stamp, x1, y1, x2, y2, 0, 0, stamp.getWidth(), stamp.getHeight(), null);
        }
        g.setComposite(AlphaComposite.SrcOver);
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

    private static void drawBuffer(Graphics2D g, BufferedImage buffer, Rectangle2D view) {
        if (buffer == null) return;
        Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform transform = new AffineTransform(
                view.getWidth() / buffer.getWidth(), 0, 0, view.getHeight() / buffer.getHeight(),
                view.getX(), view.getY());
        g.drawImage(buffer, transform, null);
        if (oldInterpolation == null) g.getRenderingHints().remove(RenderingHints.KEY_INTERPOLATION);
        else g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
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

    private static double transformScale(AffineTransform transform) {
        if (transform == null) return 1.0;
        double scale = Math.hypot(transform.getScaleX(), transform.getShearX());
        return Double.isFinite(scale) && scale > 0 ? scale : 1.0;
    }

    private static int clampBufferSize(int value) {
        return Math.max(2, Math.min(2048, value));
    }

    private static BufferedImage ensureBuffer(BufferedImage image, int width, int height) {
        if (image != null && image.getWidth() == width && image.getHeight() == height) return image;
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
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

    private static void observeWormholes(World world, VisibilityRules.Frame frame, SystemState state) {
        for (WormholeGate gate : world.wormholes) {
            if (gate == null || !frame.pointVisible(gate.x, gate.y)) continue;
            String key = gate.id == null || gate.id.isBlank()
                    ? gate.toSystemId + ':' + Math.round(gate.x) + ':' + Math.round(gate.y) : gate.id;
            state.wormholes.put(key, new KnownWormhole(key, gate.toSystemId, gate.x, gate.y));
        }
    }

    private static void drawContacts(Graphics2D g, World world, SystemState state, double scaleX, double scaleY,
                                     int offsetX, int offsetY) {
        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        double now = world.systemTime();
        for (LastKnownContact contact : new ArrayList<>(state.contacts.values())) {
            if (state.liveContacts.contains(contact.key())) continue;
            double age = Math.max(0, now - contact.lastSeenSystemTime());
            double projectionSeconds = Math.min(6, age);
            double predictedWorldX = contact.x() + contact.vx() * projectionSeconds;
            double predictedWorldY = contact.y() + contact.vy() * projectionSeconds;
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
        final BufferedImage exploredFogMask;
        final Map<String, LastKnownContact> contacts = new LinkedHashMap<>();
        final Set<String> liveContacts = new LinkedHashSet<>();
        final Map<String, KnownWormhole> wormholes = new LinkedHashMap<>();
        List<VisibilityRules.Sensor> sensors = List.of();
        BufferedImage worldFogBuffer;
        BufferedImage minimapFogBuffer;
        long lastUpdateNanos;
        long lastMinimapRefreshNanos;

        SystemState(String systemId, int columns, int rows, int worldWidth, int worldHeight) {
            this.systemId = systemId;
            this.columns = columns;
            this.rows = rows;
            this.worldWidth = worldWidth;
            this.worldHeight = worldHeight;
            maskWidth = Math.max(2, (int)Math.ceil(worldWidth / EXPLORATION_MASK_WORLD_UNITS));
            maskHeight = Math.max(2, (int)Math.ceil(worldHeight / EXPLORATION_MASK_WORLD_UNITS));
            explored = new BitSet(columns * rows);
            visible = new BitSet(columns * rows);
            exploredFogMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = exploredFogMask.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setColor(UNEXPLORED);
            g.fillRect(0, 0, maskWidth, maskHeight);
            g.dispose();
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
