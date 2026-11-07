package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.HashSet;
import java.util.Set;

/**
 * Selected-belt tactical intelligence, never a full orbital-ring representation.
 * Rendering consumes already-authorized, throttled tracking views. No node scans
 * or authoritative geometry calculations take place in the paint path.
 */
final class RadarBeltTrackingOverlay {
    private static final Color ARROW = new Color(90, 233, 245, 210);
    private static final Color TARGET = new Color(255, 217, 117, 235);
    private static final double MAX_WORLD_BEARING = 350.0;

    private RadarBeltTrackingOverlay() { }

    static void drawWorld(Graphics2D source, World world, Rectangle2D viewport) {
        if (source == null || world == null || viewport == null) return;
        RadarBeltTrackingSystem.View view = RadarBeltTrackingSystem.view(world, PlayerRegistry.localId());
        if (!world.activeSystemId().equals(view.systemId()) || view.radars().isEmpty()) return;
        Graphics2D g = (Graphics2D)source.create();
        Stroke oldStroke = g.getStroke();
        g.clip(viewport);
        Set<String> marked = new HashSet<>();
        for (RadarBeltTrackingSystem.RadarView radar : view.radars()) {
            if (radar.paused()) continue;
            for (String id : radar.selected()) {
                RadarBeltTrackingSystem.Track track = view.tracks().get(id);
                if (track == null || !track.active()) continue;
                double distance = Math.hypot(track.x() - radar.x(), track.y() - radar.y());
                if (!Double.isFinite(distance) || distance < 1) continue;
                double dx = (track.x() - radar.x()) / distance;
                double dy = (track.y() - radar.y()) / distance;
                if (viewport.contains(radar.x(), radar.y())) {
                    double length = Math.min(MAX_WORLD_BEARING, distance);
                    double tipX = radar.x() + dx * length;
                    double tipY = radar.y() + dy * length;
                    g.setColor(ARROW);
                    g.setStroke(new BasicStroke(2.4f));
                    g.draw(new Line2D.Double(radar.x(), radar.y(), tipX, tipY));
                    arrowHead(g, tipX, tipY, dx, dy, 18);
                } else if (!viewport.contains(track.x(), track.y())) {
                    // The radar and the cluster are off-screen: show only one viewport-edge direction.
                    double cx = viewport.getCenterX(), cy = viewport.getCenterY();
                    double vx = track.x() - cx, vy = track.y() - cy;
                    double scale = Math.min(
                            (viewport.getWidth() * 0.5 - 35) / Math.max(1, Math.abs(vx)),
                            (viewport.getHeight() * 0.5 - 35) / Math.max(1, Math.abs(vy)));
                    double edgeX = cx + vx * Math.max(0, scale);
                    double edgeY = cy + vy * Math.max(0, scale);
                    double angle = Math.atan2(vy, vx);
                    g.setColor(ARROW);
                    g.setStroke(new BasicStroke(2.4f));
                    arrowHead(g, edgeX, edgeY, Math.cos(angle), Math.sin(angle), 24);
                }
                if (marked.add(id) && viewport.contains(track.x(), track.y())) {
                    g.setColor(TARGET);
                    g.setStroke(new BasicStroke(2.6f));
                    // Local cluster highlight only; the authored orbit never becomes a rendered ring.
                    g.draw(new Ellipse2D.Double(track.x() - 40, track.y() - 40, 80, 80));
                    g.draw(new Line2D.Double(track.x() - 60, track.y(), track.x() - 46, track.y()));
                    g.draw(new Line2D.Double(track.x() + 46, track.y(), track.x() + 60, track.y()));
                    Font old = g.getFont();
                    g.setFont(old.deriveFont(Font.BOLD, 16f));
                    g.drawString(track.name(), (float)(track.x() + 55), (float)(track.y() - 45));
                    g.setFont(old);
                }
            }
        }
        g.setStroke(oldStroke);
        g.dispose();
    }

    static void drawMinimap(Graphics2D source, World world, Rectangle map) {
        if (source == null || world == null || map == null || map.isEmpty()) return;
        RadarBeltTrackingSystem.View view = RadarBeltTrackingSystem.view(world, PlayerRegistry.localId());
        if (!world.activeSystemId().equals(view.systemId()) || view.radars().isEmpty()) return;
        Graphics2D g = (Graphics2D)source.create();
        g.clip(map);
        g.setStroke(new BasicStroke(1.2f));
        Set<String> marked = new HashSet<>();
        for (RadarBeltTrackingSystem.RadarView radar : view.radars()) {
            if (radar.paused()) continue;
            Point2D origin = minimapPoint(world, map, radar.x(), radar.y());
            for (String id : radar.selected()) {
                RadarBeltTrackingSystem.Track track = view.tracks().get(id);
                if (track == null || !track.active()) continue;
                Point2D target = minimapPoint(world, map, track.x(), track.y());
                g.setColor(ARROW);
                g.draw(new Line2D.Double(origin, target));
                if (!marked.add(id)) continue;
                g.setColor(TARGET);
                g.fill(new Ellipse2D.Double(target.getX() - 3.5, target.getY() - 3.5, 7, 7));
                g.setColor(Color.BLACK);
                g.draw(new Ellipse2D.Double(target.getX() - 3.5, target.getY() - 3.5, 7, 7));
            }
        }
        g.dispose();
    }

    private static Point2D minimapPoint(World world, Rectangle map, double x, double y) {
        return new Point2D.Double(map.x + Math.max(0, Math.min(1, x / Math.max(1, world.width))) * map.width,
                map.y + Math.max(0, Math.min(1, y / Math.max(1, world.height))) * map.height);
    }

    private static void arrowHead(Graphics2D g, double x, double y, double dx, double dy, double size) {
        double bx = x - dx * size, by = y - dy * size;
        double side = size * 0.40;
        g.draw(new Line2D.Double(x, y, bx + dy * side, by - dx * side));
        g.draw(new Line2D.Double(x, y, bx - dy * side, by + dx * side));
    }
}
