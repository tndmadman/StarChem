package com.tndmadman.rts;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.LinkedHashSet;
import java.util.Set;

final class GameCamera {
    private static final double MIN_ZOOM = 0.36;
    private static final double MAX_ZOOM = 2.2;
    private double x;
    private double y;
    private double zoom = 0.9;
    private boolean initialized;
    private Set<String> lastLocalEntityKeys = Set.of();

    void update(World world, int screenW, int screenH, double dt) {
        Set<String> currentLocalEntityKeys = localEntityKeys(world);
        if (!initialized) {
            initialized = centerOnLocal(world, screenW, screenH);
        } else if (localEntitiesReplaced(currentLocalEntityKeys)) {
            centerOnLocal(world, screenW, screenH);
        }
        if (!currentLocalEntityKeys.isEmpty()) lastLocalEntityKeys = currentLocalEntityKeys;
        clampToWorld(world, screenW, screenH);
    }

    void panByScreen(double screenDx, double screenDy, World world, int screenW, int screenH) {
        x += screenDx / zoom;
        y += screenDy / zoom;
        initialized = true;
        clampToWorld(world, screenW, screenH);
    }

    void zoomAt(Point ignoredScreenPoint, int wheelRotation, World world, int screenW, int screenH) {
        if (wheelRotation == 0) return;
        double centerX = screenW / 2.0;
        double centerY = screenH / 2.0;
        double worldX = centerX / zoom + x;
        double worldY = centerY / zoom + y;
        zoom = Calc.clamp(zoom * Math.pow(1.12, -wheelRotation), MIN_ZOOM, MAX_ZOOM);
        x = worldX - centerX / zoom;
        y = worldY - centerY / zoom;
        initialized = true;
        clampToWorld(world, screenW, screenH);
    }

    void apply(java.awt.Graphics2D g2) {
        g2.scale(zoom, zoom);
        g2.translate(-x, -y);
    }

    Point2D screenToWorld(java.awt.Point p) {
        return new Point2D.Double(p.x / zoom + x, p.y / zoom + y);
    }

    private boolean centerOnLocal(World world, int screenW, int screenH) {
        Rectangle2D target = localTarget(world);
        if (target == null) return false;
        double viewW = screenW / zoom;
        double viewH = screenH / zoom;
        x = target.getCenterX() - viewW / 2.0;
        y = target.getCenterY() - viewH / 2.0;
        return true;
    }

    private boolean localEntitiesReplaced(Set<String> currentLocalEntityKeys) {
        if (lastLocalEntityKeys.isEmpty() || currentLocalEntityKeys.isEmpty()) return false;
        for (String key : currentLocalEntityKeys) {
            if (lastLocalEntityKeys.contains(key)) return false;
        }
        return true;
    }

    private Set<String> localEntityKeys(World world) {
        Set<String> keys = new LinkedHashSet<>();
        for (Unit u : world.units.values()) {
            if (PlayerRegistry.isLocal(u.playerId)) keys.add("U:" + u.key());
        }
        for (Base b : world.bases.values()) {
            if (PlayerRegistry.isLocal(b.playerId)) keys.add("B:" + b.id);
        }
        return keys;
    }

    private void clampToWorld(World world, int screenW, int screenH) {
        double viewW = screenW / zoom;
        double viewH = screenH / zoom;
        x = Calc.clamp(x, 0, Math.max(0, world.width - viewW));
        y = Calc.clamp(y, 0, Math.max(0, world.height - viewH));
    }

    private Rectangle2D localTarget(World world) {
        boolean found = false;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Unit u : world.units.values()) {
            if (!PlayerRegistry.isLocal(u.playerId)) continue;
            found = true;
            minX = Math.min(minX, u.x);
            minY = Math.min(minY, u.y);
            maxX = Math.max(maxX, u.x);
            maxY = Math.max(maxY, u.y);
        }
        for (Base b : world.bases.values()) {
            if (!PlayerRegistry.isLocal(b.playerId)) continue;
            found = true;
            minX = Math.min(minX, b.x);
            minY = Math.min(minY, b.y);
            maxX = Math.max(maxX, b.x);
            maxY = Math.max(maxY, b.y);
        }
        return found ? new Rectangle2D.Double(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY)) : null;
    }
}