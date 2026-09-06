package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;

/** Draws bounded, aggregate command intent for large selections. */
final class FleetSelectionOverlay {
    private static final Stroke INTENT_STROKE = new BasicStroke(
            1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            0, new float[]{10f, 8f}, 0);
    private static final double FORMATION_EXTENT_MIN = 56.0;

    private FleetSelectionOverlay() { }

    static void draw(Graphics2D g2, World world) {
        if (g2 == null || world == null || !SelectionRenderPolicy.aggregate(world)) return;

        int slots = UnitTask.values().length * UnitOrderType.values().length;
        Group[] groups = new Group[slots];
        int groupCount = 0;
        for (Unit unit : world.units.values()) {
            if (!unit.selected || !PlayerRegistry.isLocal(unit.playerId)) continue;
            Target target = target(world, unit);
            if (target == null) continue;
            int slot = unit.task.ordinal() * UnitOrderType.values().length + unit.orderType.ordinal();
            Group group = groups[slot];
            if (group == null) {
                if (groupCount >= SelectionRenderPolicy.MAX_AGGREGATE_GROUPS) continue;
                group = new Group();
                groups[slot] = group;
                groupCount++;
            }
            group.add(unit.x, unit.y, target.x, target.y);
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

    private static Target target(World world, Unit unit) {
        if (unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION || unit.task == UnitTask.ATTACK) {
            if (GameplayCommandNumbers.finite(unit.targetX, unit.targetY)) return new Target(unit.targetX, unit.targetY);
        }
        if (unit.task == UnitTask.AUTO_HARVEST && unit.automationResourceId >= 0) {
            ResourceNode node = world.findResource(unit.automationResourceId);
            if (node != null) return new Target(node.x, node.y);
        }
        return switch (unit.orderType) {
            case PATROL -> unit.orderPhase == 0
                    ? new Target(unit.orderX1, unit.orderY1)
                    : new Target(unit.orderX2, unit.orderY2);
            case GUARD, ESCORT -> new Target(UnitOrderSystem.anchorX(world, unit), UnitOrderSystem.anchorY(world, unit));
            case HOLD -> new Target(unit.orderX1, unit.orderY1);
            case ATTACK_MOVE -> new Target(unit.orderX2, unit.orderY2);
            case NONE -> null;
        };
    }

    private record Target(double x, double y) { }

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
