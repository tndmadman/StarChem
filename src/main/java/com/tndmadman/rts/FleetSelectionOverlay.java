package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.Map;
import java.util.WeakHashMap;

/** Draws bounded, aggregate command intent for large selections. */
final class FleetSelectionOverlay {
    private static final Stroke INTENT_STROKE = new BasicStroke(
            1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            0, new float[]{10f, 8f}, 0);
    private static final double FORMATION_EXTENT_MIN = 56.0;
    // Aggregate route/order geometry is informational, not simulation state. Updating it
    // at 20 Hz keeps motion visually responsive while guaranteeing it cannot be rebuilt
    // multiple times inside a slow render frame.
    private static final long REDRAW_NANOS = 50_000_000L;
    private static final int ORDER_TYPE_COUNT = UnitOrderType.values().length;
    private static final int SLOT_COUNT = UnitTask.values().length * ORDER_TYPE_COUNT;
    private static final Map<World, Long> LAST_DRAW = new WeakHashMap<>();
    private static volatile World fastWorld;
    private static volatile long fastDrawNanos;

    private FleetSelectionOverlay() { }

    /**
     * World currently invokes order rendering per visible unit. The lock-free fast
     * path makes every call after the first selected unit a constant-time no-op.
     */
    static void drawOnce(Graphics2D g2, World world) {
        if (g2 == null || world == null || !SelectionRenderPolicy.aggregate(world)) return;
        long now = System.nanoTime();
        if (fastWorld == world && now - fastDrawNanos < REDRAW_NANOS) return;
        synchronized (LAST_DRAW) {
            Long last = LAST_DRAW.get(world);
            if (last != null && now - last < REDRAW_NANOS) {
                fastWorld = world;
                fastDrawNanos = last;
                return;
            }
            LAST_DRAW.put(world, now);
            fastWorld = world;
            fastDrawNanos = now;
        }
        draw(g2, world);
    }

    private static void draw(Graphics2D g2, World world) {
        Group[] groups = new Group[SLOT_COUNT];
        int groupCount = 0;
        for (Unit unit : world.units.values()) {
            if (!unit.selected || !PlayerRegistry.isLocal(unit.playerId)) continue;

            double targetX;
            double targetY;
            boolean hasTarget = false;
            if (unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION || unit.task == UnitTask.ATTACK) {
                targetX = unit.targetX;
                targetY = unit.targetY;
                hasTarget = GameplayCommandNumbers.finite(targetX, targetY);
            } else if (unit.task == UnitTask.AUTO_HARVEST && unit.automationResourceId >= 0) {
                ResourceNode node = world.findResource(unit.automationResourceId);
                targetX = node == null ? 0 : node.x;
                targetY = node == null ? 0 : node.y;
                hasTarget = node != null;
            } else {
                targetX = 0;
                targetY = 0;
            }

            if (!hasTarget) {
                switch (unit.orderType) {
                    case PATROL -> {
                        targetX = unit.orderPhase == 0 ? unit.orderX1 : unit.orderX2;
                        targetY = unit.orderPhase == 0 ? unit.orderY1 : unit.orderY2;
                        hasTarget = true;
                    }
                    case GUARD, ESCORT -> {
                        targetX = UnitOrderSystem.anchorX(world, unit);
                        targetY = UnitOrderSystem.anchorY(world, unit);
                        hasTarget = true;
                    }
                    case HOLD -> {
                        targetX = unit.orderX1;
                        targetY = unit.orderY1;
                        hasTarget = true;
                    }
                    case ATTACK_MOVE -> {
                        targetX = unit.orderX2;
                        targetY = unit.orderY2;
                        hasTarget = true;
                    }
                    case NONE -> { }
                }
            }
            if (!hasTarget || !GameplayCommandNumbers.finite(targetX, targetY)) continue;

            int slot = unit.task.ordinal() * ORDER_TYPE_COUNT + unit.orderType.ordinal();
            Group group = groups[slot];
            if (group == null) {
                if (groupCount >= SelectionRenderPolicy.MAX_AGGREGATE_GROUPS) continue;
                group = new Group();
                groups[slot] = group;
                groupCount++;
            }
            group.add(unit.x, unit.y, targetX, targetY);
        }
        if (groupCount == 0) return;

        Graphics2D overlay = (Graphics2D)g2.create();
        Color owner = PlayerRegistry.color(PlayerRegistry.localId());
        overlay.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 180));
        overlay.setStroke(INTENT_STROKE);
        if (SelectionRenderPolicy.selectedCount(world) > SelectionRenderPolicy.COMPACT_LIMIT) {
            overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }

        Path2D.Double path = new Path2D.Double();
        double[] markersX = new double[groupCount];
        double[] markersY = new double[groupCount];
        int markerCount = 0;
        for (Group group : groups) {
            if (group == null || group.count == 0) continue;
            double fromX = group.fromX / group.count;
            double fromY = group.fromY / group.count;
            double toX = group.toX / group.count;
            double toY = group.toY / group.count;
            if (RenderCulling.segmentVisible(overlay, fromX, fromY, toX, toY, 28)) {
                path.moveTo(fromX, fromY);
                path.lineTo(toX, toY);
            }
            addFormationExtent(path, group);
            markersX[markerCount] = toX;
            markersY[markerCount] = toY;
            markerCount++;
        }
        overlay.draw(path);
        for (int i = 0; i < markerCount; i++) {
            int x = (int)Math.round(markersX[i]);
            int y = (int)Math.round(markersY[i]);
            overlay.drawOval(x - 9, y - 9, 18, 18);
            overlay.drawLine(x - 13, y, x + 13, y);
            overlay.drawLine(x, y - 13, x, y + 13);
        }
        overlay.dispose();
    }

    private static void addFormationExtent(Path2D.Double path, Group group) {
        if (group.count < 3) return;
        double width = group.maxX - group.minX;
        double height = group.maxY - group.minY;
        if (Math.max(width, height) < FORMATION_EXTENT_MIN) return;
        if (width > height * 1.8) {
            double y = (group.minY + group.maxY) * 0.5;
            path.moveTo(group.minX, y);
            path.lineTo(group.maxX, y);
        } else if (height > width * 1.8) {
            double x = (group.minX + group.maxX) * 0.5;
            path.moveTo(x, group.minY);
            path.lineTo(x, group.maxY);
        } else {
            path.moveTo(group.minX, group.minY);
            path.lineTo(group.maxX, group.minY);
            path.lineTo(group.maxX, group.maxY);
            path.lineTo(group.minX, group.maxY);
            path.closePath();
        }
    }

    private static final class Group {
        int count;
        double fromX;
        double fromY;
        double toX;
        double toY;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        void add(double x, double y, double targetX, double targetY) {
            count++;
            fromX += x;
            fromY += y;
            toX += targetX;
            toY += targetY;
            minX = Math.min(minX, targetX);
            minY = Math.min(minY, targetY);
            maxX = Math.max(maxX, targetX);
            maxY = Math.max(maxY, targetY);
        }
    }
}
