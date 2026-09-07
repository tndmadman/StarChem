package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.concurrent.atomic.AtomicLong;

/** Draws all fleet-scale selection UI in one frame-level pass. */
final class FleetSelectionOverlay {
    private static final Stroke INTENT_STROKE = new BasicStroke(
            1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            0, new float[]{10f, 8f}, 0);
    private static final Stroke SELECTED_STROKE = new BasicStroke(2f);
    private static final Color SELECTED_COLOR = new Color(255, 245, 120);
    private static final double FORMATION_EXTENT_MIN = 56.0;
    private static final int ORDER_TYPE_COUNT = UnitOrderType.values().length;
    private static final int SLOT_COUNT = UnitTask.values().length * ORDER_TYPE_COUNT;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final AtomicLong FRAME_PASSES = new AtomicLong();
    private static volatile int lastMarkerCount;
    private static volatile int lastGroupCount;

    private FleetSelectionOverlay() { }

    static void drawFrame(Graphics2D g2, World world, SelectionRenderPolicy.Frame selection) {
        long started = System.nanoTime();
        FRAME_PASSES.incrementAndGet();
        if (g2 == null || world == null || selection == null
                || selection.selectedCount() <= SelectionRenderPolicy.FULL_LIMIT) {
            lastMarkerCount = 0;
            lastGroupCount = 0;
            PerformanceTrace.recordSelectionDraw(System.nanoTime() - started, 0, 0);
            return;
        }

        Scratch scratch = SCRATCH.get();
        scratch.reset();
        int groupCount = buildOrderGeometry(world, selection, scratch);
        int markerCount = buildSelectionMarkers(selection, scratch);
        drawOrderGeometry(g2, scratch, groupCount);
        drawSelectionMarkers(g2, selection, scratch, markerCount);

        lastMarkerCount = markerCount;
        lastGroupCount = groupCount;
        PerformanceTrace.recordSelectionDraw(System.nanoTime() - started, markerCount, groupCount);
    }

    private static int buildOrderGeometry(World world, SelectionRenderPolicy.Frame selection, Scratch scratch) {
        int groupCount = 0;
        for (Unit unit : selection.selectedUnits()) {
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
            Group group = scratch.groups[slot];
            if (!group.used) {
                if (groupCount >= SelectionRenderPolicy.MAX_AGGREGATE_GROUPS) continue;
                group.used = true;
                groupCount++;
            }
            group.add(unit.x, unit.y, targetX, targetY);
        }

        Path2D.Double path = scratch.orderPath;
        for (Group group : scratch.groups) {
            if (!group.used || group.count == 0) continue;
            double fromX = group.fromX / group.count;
            double fromY = group.fromY / group.count;
            double toX = group.toX / group.count;
            double toY = group.toY / group.count;
            path.moveTo(fromX, fromY);
            path.lineTo(toX, toY);
            addFormationExtent(path, group);
            int marker = scratch.orderMarkerCount++;
            scratch.orderMarkersX[marker] = toX;
            scratch.orderMarkersY[marker] = toY;
        }
        return groupCount;
    }

    private static int buildSelectionMarkers(SelectionRenderPolicy.Frame selection, Scratch scratch) {
        int markerCount = 0;
        boolean compact = selection.compactMarkers();
        Unit primary = selection.primary();
        for (Unit unit : selection.visibleSelectedUnits()) {
            if (unit == primary) continue;
            markerCount++;
            if (!compact) continue;
            double x = Math.rint(unit.x);
            double y = Math.rint(unit.y);
            scratch.selectionPath.moveTo(x - 24, y - 24);
            scratch.selectionPath.lineTo(x + 24, y - 24);
            scratch.selectionPath.lineTo(x + 24, y + 24);
            scratch.selectionPath.lineTo(x - 24, y + 24);
            scratch.selectionPath.closePath();
        }
        return markerCount;
    }

    private static void drawOrderGeometry(Graphics2D g2, Scratch scratch, int groupCount) {
        if (groupCount <= 0) return;
        Color owner = PlayerRegistry.color(PlayerRegistry.localId());
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        g2.setColor(new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 180));
        g2.setStroke(INTENT_STROKE);
        g2.draw(scratch.orderPath);
        for (int i = 0; i < scratch.orderMarkerCount; i++) {
            int x = (int)Math.round(scratch.orderMarkersX[i]);
            int y = (int)Math.round(scratch.orderMarkersY[i]);
            g2.drawOval(x - 9, y - 9, 18, 18);
            g2.drawLine(x - 13, y, x + 13, y);
            g2.drawLine(x, y - 13, x, y + 13);
        }
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    private static void drawSelectionMarkers(Graphics2D g2, SelectionRenderPolicy.Frame selection,
                                             Scratch scratch, int markerCount) {
        if (markerCount <= 0) return;
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        g2.setColor(SELECTED_COLOR);
        g2.setStroke(SELECTED_STROKE);
        if (selection.compactMarkers()) {
            // Hundreds of secondary selection rectangles become one Java2D path draw.
            g2.draw(scratch.selectionPath);
        } else {
            Unit primary = selection.primary();
            for (Unit unit : selection.visibleSelectedUnits()) {
                if (unit == primary) continue;
                g2.drawOval((int)Math.round(unit.x) - 26, (int)Math.round(unit.y) - 26, 52, 52);
            }
        }
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
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

    static long framePassCountForTest() { return FRAME_PASSES.get(); }
    static int lastMarkerCountForTest() { return lastMarkerCount; }
    static int lastGroupCountForTest() { return lastGroupCount; }

    private static final class Scratch {
        final Group[] groups = new Group[SLOT_COUNT];
        final Path2D.Double orderPath = new Path2D.Double();
        final Path2D.Double selectionPath = new Path2D.Double();
        final double[] orderMarkersX = new double[SelectionRenderPolicy.MAX_AGGREGATE_GROUPS];
        final double[] orderMarkersY = new double[SelectionRenderPolicy.MAX_AGGREGATE_GROUPS];
        int orderMarkerCount;

        Scratch() {
            for (int i = 0; i < groups.length; i++) groups[i] = new Group();
        }

        void reset() {
            for (Group group : groups) group.reset();
            orderPath.reset();
            selectionPath.reset();
            orderMarkerCount = 0;
        }
    }

    private static final class Group {
        boolean used;
        int count;
        double fromX;
        double fromY;
        double toX;
        double toY;
        double minX;
        double minY;
        double maxX;
        double maxY;

        void reset() {
            used = false;
            count = 0;
            fromX = 0;
            fromY = 0;
            toX = 0;
            toY = 0;
            minX = Double.POSITIVE_INFINITY;
            minY = Double.POSITIVE_INFINITY;
            maxX = Double.NEGATIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
        }

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
