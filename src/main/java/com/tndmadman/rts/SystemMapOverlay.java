package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

final class SystemMapOverlay {
    private static final int NODE_RADIUS = 36;
    private static final int NODE_HIT_RADIUS = 48;

    void draw(Graphics2D g2, World world, int width, int height) {
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 205));
        g.fillRect(0, 0, width, height);

        Rectangle2D panel = panel(width, height);
        g.setColor(new Color(8, 14, 24, 238));
        g.fillRoundRect((int)panel.getX(), (int)panel.getY(), (int)panel.getWidth(), (int)panel.getHeight(), 22, 22);
        g.setColor(new Color(90, 145, 190, 175));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect((int)panel.getX(), (int)panel.getY(), (int)panel.getWidth(), (int)panel.getHeight(), 22, 22);

        drawHeader(g, snapshot, panel);
        Map<String, Point2D> points = layout(snapshot, panel);
        drawLinks(g, snapshot, points);
        drawNodes(g, snapshot, points);
        drawFooter(g, panel);
        g.dispose();
    }

    String systemAt(int screenX, int screenY, World world, int width, int height) {
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        Map<String, Point2D> points = layout(snapshot, panel(width, height));
        for (GalaxyMapSystem system : snapshot.systems()) {
            Point2D p = points.get(system.id());
            if (p != null && p.distance(screenX, screenY) <= NODE_HIT_RADIUS) return system.id();
        }
        return "";
    }

    private Rectangle2D panel(int width, int height) {
        int margin = Math.max(28, Math.min(width, height) / 18);
        return new Rectangle2D.Double(margin, margin, Math.max(200, width - margin * 2), Math.max(160, height - margin * 2));
    }

    private void drawHeader(Graphics2D g, GalaxyMapSnapshot snapshot, Rectangle2D panel) {
        g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
        g.setColor(Color.WHITE);
        g.drawString("Galaxy Map", (int)panel.getX() + 28, (int)panel.getY() + 40);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
        g.setColor(new Color(205, 225, 240));
        g.drawString(snapshot.systems().size() + " active system(s) | Click a system to travel/view | M or Esc closes", (int)panel.getX() + 28, (int)panel.getY() + 64);
    }

    private void drawLinks(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, Point2D> points) {
        g.setStroke(new BasicStroke(2.2f));
        for (GalaxyMapLink link : snapshot.links()) {
            Point2D a = points.get(link.fromSystemId());
            Point2D b = points.get(link.toSystemId());
            if (a == null || b == null) continue;
            g.setColor(new Color(70, 165, 255, 110));
            g.draw(new Line2D.Double(a, b));
            drawMidpointPulse(g, a, b);
        }
    }

    private void drawMidpointPulse(Graphics2D g, Point2D a, Point2D b) {
        double mx = (a.getX() + b.getX()) * 0.5;
        double my = (a.getY() + b.getY()) * 0.5;
        g.setColor(new Color(150, 220, 255, 150));
        g.fill(new Ellipse2D.Double(mx - 3, my - 3, 6, 6));
    }

    private void drawNodes(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, Point2D> points) {
        Font labelFont = g.getFont().deriveFont(Font.BOLD, 12f);
        Font smallFont = g.getFont().deriveFont(Font.PLAIN, 11f);
        for (GalaxyMapSystem system : snapshot.systems()) {
            Point2D p = points.get(system.id());
            if (p == null) continue;
            int x = (int)Math.round(p.getX());
            int y = (int)Math.round(p.getY());
            Color fill = system.active() ? new Color(80, 190, 255, 235) : system.special() ? new Color(210, 90, 85, 225) : system.home() ? new Color(95, 220, 135, 225) : new Color(115, 145, 180, 225);
            Color ring = system.active() ? new Color(255, 245, 150, 240) : new Color(175, 210, 240, 170);
            g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 42));
            g.fillOval(x - NODE_HIT_RADIUS, y - NODE_HIT_RADIUS, NODE_HIT_RADIUS * 2, NODE_HIT_RADIUS * 2);
            g.setColor(fill);
            g.fillOval(x - NODE_RADIUS, y - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);
            g.setColor(ring);
            g.setStroke(new BasicStroke(system.active() ? 3.2f : 1.7f));
            g.drawOval(x - NODE_RADIUS, y - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);
            g.setColor(new Color(0, 0, 0, 135));
            g.fillOval(x - 10, y - 10, 20, 20);
            g.setColor(Color.WHITE);
            g.setFont(labelFont);
            String initials = initials(system.name());
            int sw = g.getFontMetrics().stringWidth(initials);
            g.drawString(initials, x - sw / 2, y + 5);
            drawSystemLabel(g, system, x, y, smallFont, labelFont);
        }
    }

    private void drawSystemLabel(Graphics2D g, GalaxyMapSystem system, int x, int y, Font smallFont, Font labelFont) {
        String name = truncate(system.name(), 22);
        String stats = system.ships() + " ship(s) | " + system.bases() + " base(s) | " + system.resources() + " res";
        g.setFont(labelFont);
        FontMetrics labelMetrics = g.getFontMetrics();
        int nameW = labelMetrics.stringWidth(name);
        g.setFont(smallFont);
        int statsW = g.getFontMetrics().stringWidth(stats);
        int boxW = Math.max(nameW, statsW) + 18;
        int boxH = 38;
        int bx = x - boxW / 2;
        int by = y + NODE_RADIUS + 9;
        g.setColor(new Color(0, 0, 0, 155));
        g.fillRoundRect(bx, by, boxW, boxH, 10, 10);
        g.setFont(labelFont);
        g.setColor(Color.WHITE);
        g.drawString(name, x - nameW / 2, by + 15);
        g.setFont(smallFont);
        g.setColor(new Color(210, 230, 240));
        g.drawString(stats, x - statsW / 2, by + 31);
    }

    private void drawFooter(Graphics2D g, Rectangle2D panel) {
        int y = (int)(panel.getMaxY() - 24);
        int x = (int)panel.getX() + 28;
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(new Color(210, 230, 240));
        g.drawString("Legend: cyan = current view, green = player home, red = special system, lines = wormholes", x, y);
    }

    private Map<String, Point2D> layout(GalaxyMapSnapshot snapshot, Rectangle2D panel) {
        Map<String, Point2D> points = new LinkedHashMap<>();
        List<GalaxyMapSystem> systems = snapshot.systems();
        if (systems.isEmpty()) return points;
        double cx = panel.getCenterX();
        double cy = panel.getCenterY() + 20;
        if (systems.size() == 1) {
            points.put(systems.get(0).id(), new Point2D.Double(cx, cy));
            return points;
        }
        double rx = Math.max(90, panel.getWidth() * 0.34);
        double ry = Math.max(80, panel.getHeight() * 0.28);
        for (int i = 0; i < systems.size(); i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI * 2.0 / systems.size();
            points.put(systems.get(i).id(), new Point2D.Double(cx + Math.cos(angle) * rx, cy + Math.sin(angle) * ry));
        }
        return points;
    }

    private String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
