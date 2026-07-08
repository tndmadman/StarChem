package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class GalaxyMapOverlay {
    private static final double NODE_RADIUS = 34.0;
    private static final double ACTIVE_NODE_RADIUS = 42.0;

    void draw(Graphics2D g2, GalaxyMapSnapshot snapshot, int width, int height) {
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(2, 5, 10, 224));
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(10, 18, 30, 240));
        g.fillRoundRect(48, 54, Math.max(1, width - 96), Math.max(1, height - 108), 24, 24);
        g.setColor(new Color(92, 137, 180, 150));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(48, 54, Math.max(1, width - 96), Math.max(1, height - 108), 24, 24);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
        g.setColor(new Color(230, 244, 255));
        g.drawString("GALAXY MAP", 76, 92);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
        g.setColor(new Color(185, 211, 235));
        g.drawString("Click a system to travel/view it | M or Esc closes | Ctrl+M toggles audio", 76, 116);

        if (snapshot == null || snapshot.empty()) {
            g.setColor(new Color(230, 244, 255, 180));
            g.drawString("No active systems discovered yet.", 76, 148);
            g.dispose();
            return;
        }

        Map<String, NodeLayout> layout = layout(snapshot, width, height);
        drawGrid(g, width, height);
        drawLinks(g, snapshot, layout);
        drawNodes(g, snapshot, layout);
        drawLegend(g, width, height);
        g.dispose();
    }

    String systemAt(GalaxyMapSnapshot snapshot, int screenX, int screenY, int width, int height) {
        if (snapshot == null || snapshot.empty()) return "";
        Map<String, NodeLayout> layout = layout(snapshot, width, height);
        for (GalaxyMapSystem system : snapshot.systems()) {
            NodeLayout node = layout.get(system.id());
            if (node == null) continue;
            double radius = system.active() ? ACTIVE_NODE_RADIUS : NODE_RADIUS;
            if (Point2D.distance(screenX, screenY, node.x, node.y) <= radius + 10) return system.id();
        }
        return "";
    }

    private void drawGrid(Graphics2D g, int width, int height) {
        g.setColor(new Color(38, 57, 78, 70));
        for (int x = 80; x < width - 80; x += 96) g.drawLine(x, 134, x, height - 92);
        for (int y = 140; y < height - 92; y += 96) g.drawLine(64, y, width - 64, y);
    }

    private void drawLinks(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, NodeLayout> layout) {
        g.setStroke(new BasicStroke(2f));
        for (GalaxyMapLink link : snapshot.links()) {
            NodeLayout a = layout.get(link.fromSystemId());
            NodeLayout b = layout.get(link.toSystemId());
            if (a == null || b == null) continue;
            g.setColor(new Color(90, 186, 255, 105));
            g.draw(new Line2D.Double(a.x, a.y, b.x, b.y));
        }
    }

    private void drawNodes(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, NodeLayout> layout) {
        for (GalaxyMapSystem system : snapshot.systems()) {
            NodeLayout node = layout.get(system.id());
            if (node == null) continue;
            double radius = system.active() ? ACTIVE_NODE_RADIUS : NODE_RADIUS;
            Ellipse2D circle = new Ellipse2D.Double(node.x - radius, node.y - radius, radius * 2, radius * 2);

            Color fill = system.special() ? new Color(148, 65, 74, 230) : system.home() ? new Color(43, 91, 126, 235) : new Color(34, 58, 82, 235);
            if (system.active()) fill = new Color(54, 125, 105, 245);
            g.setColor(fill);
            g.fill(circle);
            g.setStroke(new BasicStroke(system.active() ? 3f : 2f));
            g.setColor(system.hasLocalAssets() ? new Color(255, 236, 150) : new Color(155, 204, 238));
            g.draw(circle);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
            g.setColor(Color.WHITE);
            drawCentered(g, system.name(), node.x, node.y - radius - 14);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            g.setColor(new Color(208, 229, 247));
            drawCentered(g, system.id(), node.x, node.y + 4);
            drawCentered(g, system.ships() + " ships | " + system.bases() + " bases", node.x, node.y + radius + 18);
            drawCentered(g, system.resources() + " resources", node.x, node.y + radius + 33);
            if (system.hasLocalAssets()) {
                g.setColor(new Color(255, 236, 150));
                drawCentered(g, "Your assets here", node.x, node.y + radius + 48);
            }
        }
    }

    private void drawLegend(Graphics2D g, int width, int height) {
        int x = 76;
        int y = Math.max(150, height - 68);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(new Color(208, 229, 247));
        g.drawString("Green = current view   Blue = player/home system   Red = special system   Gold ring = your assets", x, y);
    }

    private void drawCentered(Graphics2D g, String text, double cx, double y) {
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (int)Math.round(cx - w / 2.0), (int)Math.round(y));
    }

    private Map<String, NodeLayout> layout(GalaxyMapSnapshot snapshot, int width, int height) {
        Map<String, NodeLayout> out = new HashMap<>();
        List<GalaxyMapSystem> systems = snapshot.systems() == null ? List.of() : new ArrayList<>(snapshot.systems());
        int count = systems.size();
        double centerX = width / 2.0;
        double centerY = height / 2.0 + 18.0;
        if (count == 1) {
            out.put(systems.get(0).id(), new NodeLayout(centerX, centerY));
            return out;
        }
        double rx = Math.max(120.0, (width - 220.0) * 0.34);
        double ry = Math.max(100.0, (height - 240.0) * 0.31);
        for (int i = 0; i < count; i++) {
            GalaxyMapSystem system = systems.get(i);
            double angle = -Math.PI / 2.0 + i * Math.PI * 2.0 / count;
            double x = centerX + Math.cos(angle) * rx;
            double y = centerY + Math.sin(angle) * ry;
            out.put(system.id(), new NodeLayout(x, y));
        }
        return out;
    }

    private record NodeLayout(double x, double y) { }
}
