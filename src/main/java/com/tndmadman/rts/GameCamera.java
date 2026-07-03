package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

final class GameCamera {
    private double x;
    private double y;
    private double zoom = 0.9;

    void update(World world, int screenW, int screenH, double dt) {
        Rectangle2D target = localTarget(world);
        if (target == null) target = world.localBounds();
        if (target == null) return;

        double padding = 360;
        double targetZoom = Math.min(
                Math.max(520, screenW - 130) / (target.getWidth() + padding),
                Math.max(360, screenH - 130) / (target.getHeight() + padding));
        targetZoom = Calc.clamp(targetZoom, 0.36, 1.18);

        double viewW = screenW / targetZoom;
        double viewH = screenH / targetZoom;
        double targetX = Calc.clamp(target.getCenterX() - viewW / 2.0, 0, Math.max(0, world.width - viewW));
        double targetY = Calc.clamp(target.getCenterY() - viewH / 2.0, 0, Math.max(0, world.height - viewH));
        double t = Calc.clamp(dt * 3.6, 0, 1);
        x = Calc.lerp(x, targetX, t);
        y = Calc.lerp(y, targetY, t);
        zoom = Calc.lerp(zoom, targetZoom, t);
    }

    void apply(java.awt.Graphics2D g2) {
        g2.scale(zoom, zoom);
        g2.translate(-x, -y);
    }

    Point2D screenToWorld(java.awt.Point p) {
        return new Point2D.Double(p.x / zoom + x, p.y / zoom + y);
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
