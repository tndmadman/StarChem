package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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
    private static final long CACHE_NANOS = 50_000_000L;
    private static final int ORDER_TYPE_COUNT = UnitOrderType.values().length;
    private static final int SLOT_COUNT = UnitTask.values().length * ORDER_TYPE_COUNT;
    private static final Map<World, OverlayCache> CACHES = new WeakHashMap<>();
    private static volatile World fastWorld;
    private static volatile OverlayCache fastCache;

    private FleetSelectionOverlay() { }

    /**
     * World invokes order rendering once per visible unit. Pick one visible selected
     * unit as the render anchor, so aggregate intent is painted exactly once per pass.
     * Geometry itself is rebuilt at 20 Hz but the cached geometry is painted every
     * frame; immediate-mode rendering must never skip the paint just because geometry
     * did not change.
     */
    static void drawForUnit(Graphics2D g2, World world, Unit unit,
                            SelectionRenderPolicy.Snapshot selection) {
        if (g2 == null || world == null || unit == null || selection == null
                || selection.selectedCount() <= SelectionRenderPolicy.FULL_LIMIT
                || !unit.selected || !PlayerRegistry.isLocal(unit.playerId)) return;

        long now = System.nanoTime();
        OverlayCache cache = cache(world);
        if (cache.anchor == null || now >= cache.anchorExpiresNanos
                || !cache.anchor.selected || !PlayerRegistry.isLocal(cache.anchor.playerId)) {
            refreshAnchor(g2, world, cache, now);
        }
        if (cache.anchor != unit) return;

        if (!cache.geometryReady || now >= cache.geometryExpiresNanos
                || cache.selectedCount != selection.selectedCount()) {
            rebuildGeometry(world, cache, selection.selectedCount(), now);
        }
        drawCached(g2, cache);
    }

    private static OverlayCache cache(World world) {
        OverlayCache fast = fastCache;
        if (fastWorld == world && fast != null) return fast;
        synchronized (CACHES) {
            OverlayCache cache = CACHES.computeIfAbsent(world, ignored -> new OverlayCache());
            fastWorld = world;
            fastCache = cache;
            return cache;
        }
    }

    private static void refreshAnchor(Graphics2D g2, World world, OverlayCache cache, long now) {
        Rectangle clip = g2.getClipBounds();
        Unit anchor = null;
        for (Unit candidate : world.units.values()) {
            if (!candidate.selected || !PlayerRegistry.isLocal(candidate.playerId)) continue;
            if (clip == null || visible(clip, candidate.x, candidate.y, 96.0)) {
                anchor = candidate;
                break;
            }
        }
        cache.anchor = anchor;
        cache.anchorExpiresNanos = now + CACHE_NANOS;
    }

    private static boolean visible(Rectangle clip, double x, double y, double radius) {
        return x + radius >= clip.getMinX() && x - radius <= clip.getMaxX()
                && y + radius >= clip.getMinY() && y - radius <= clip.getMaxY();
    }

    private static void rebuildGeometry(World world, OverlayCache cache, int selectedCount, long now) {
        for (Group group : cache.groups) group.reset();
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
            Group group = cache.groups[slot];
            if (!group.used) {
                if (groupCount >= SelectionRenderPolicy.MAX_AGGREGATE_GROUPS) continue;
                group.used = true;
                groupCount++;
            }
            group.add(unit.x, unit.y, targetX, targetY);
        }

        Path2D.Double path = cache.path;
        path.reset();
        cache.markerCount = 0;
        for (Group group : cache.groups) {
            if (!group.used || group.count == 0) continue;
            double fromX = group.fromX / group.count;
            double fromY = group.fromY / group.count;
            double toX = group.toX / group.count;
            double toY = group.toY / group.count;
            path.moveTo(fromX, fromY);
            path.lineTo(toX, toY);
            addFormationExtent(path, group);
            int marker = cache.markerCount++;
            cache.markersX[marker] = toX;
            cache.markersY[marker] = toY;
        }

        Color owner = PlayerRegistry.color(PlayerRegistry.localId());
        cache.color = new Color(owner.getRed(), owner.getGreen(), owner.getBlue(), 180);
        cache.selectedCount = selectedCount;
        cache.geometryReady = true;
        cache.geometryExpiresNanos = now + CACHE_NANOS;
    }

    private static void drawCached(Graphics2D g2, OverlayCache cache) {
        if (!cache.geometryReady || (cache.markerCount == 0 && cache.path.getCurrentPoint() == null)) return;
        Color oldColor = g2.getColor();
        Stroke oldStroke = g2.getStroke();
        g2.setColor(cache.color);
        g2.setStroke(INTENT_STROKE);
        g2.draw(cache.path);
        for (int i = 0; i < cache.markerCount; i++) {
            int x = (int)Math.round(cache.markersX[i]);
            int y = (int)Math.round(cache.markersY[i]);
            g2.drawOval(x - 9, y - 9, 18, 18);
            g2.drawLine(x - 13, y, x + 13, y);
            g2.drawLine(x, y - 13, x, y + 13);
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

    private static final class OverlayCache {
        final Group[] groups = new Group[SLOT_COUNT];
        final Path2D.Double path = new Path2D.Double();
        final double[] markersX = new double[SelectionRenderPolicy.MAX_AGGREGATE_GROUPS];
        final double[] markersY = new double[SelectionRenderPolicy.MAX_AGGREGATE_GROUPS];
        Unit anchor;
        long anchorExpiresNanos;
        long geometryExpiresNanos;
        boolean geometryReady;
        int selectedCount;
        int markerCount;
        Color color = Color.WHITE;

        OverlayCache() {
            for (int i = 0; i < groups.length; i++) groups[i] = new Group();
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
