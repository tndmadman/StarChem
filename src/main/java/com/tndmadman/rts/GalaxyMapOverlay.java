package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GalaxyMapOverlay {
    void draw(Graphics2D g2, GalaxyMapSnapshot snapshot, int width, int height) {
        snapshot = visibleSnapshot(snapshot);
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(2, 5, 10, 224));
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(10, 18, 30, 240));
        g.fillRoundRect(38, 42, Math.max(1, width - 76), Math.max(1, height - 84), 24, 24);
        g.setColor(new Color(92, 137, 180, 150));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(38, 42, Math.max(1, width - 76), Math.max(1, height - 84), 24, 24);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
        g.setColor(new Color(230, 244, 255));
        g.drawString("GALAXY MAP", 66, 78);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(new Color(185, 211, 235));
        g.drawString("Click a linked system to view it | Ring color shows controller or active claimant", 66, 101);

        if (snapshot == null || snapshot.empty()) {
            g.setColor(new Color(230, 244, 255, 180));
            g.drawString("No active systems discovered yet.", 66, 134);
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
        snapshot = visibleSnapshot(snapshot);
        if (snapshot == null || snapshot.empty()) return "";
        Map<String, NodeLayout> layout = layout(snapshot, width, height);
        int count = snapshot.systems().size();
        for (GalaxyMapSystem system : snapshot.systems()) {
            NodeLayout node = layout.get(system.id());
            if (node == null) continue;
            double radius = nodeRadius(count, system.active());
            if (Point2D.distance(screenX, screenY, node.x, node.y) <= radius + 10) return system.id();
        }
        return "";
    }

    private GalaxyMapSnapshot visibleSnapshot(GalaxyMapSnapshot snapshot) {
        if (snapshot == null || snapshot.empty()) return snapshot;
        Set<String> linkedSystemIds = new LinkedHashSet<>();
        if (snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                linkedSystemIds.add(link.fromSystemId());
                linkedSystemIds.add(link.toSystemId());
            }
        }
        List<GalaxyMapSystem> visibleSystems = new ArrayList<>();
        Set<String> visibleIds = new LinkedHashSet<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (isVisibleSystem(system, linkedSystemIds)) {
                visibleSystems.add(system);
                visibleIds.add(system.id());
            }
        }
        List<GalaxyMapLink> visibleLinks = new ArrayList<>();
        if (snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                if (visibleIds.contains(link.fromSystemId()) && visibleIds.contains(link.toSystemId())) visibleLinks.add(link);
            }
        }
        return new GalaxyMapSnapshot(snapshot.activeSystemId(), List.copyOf(visibleSystems), List.copyOf(visibleLinks));
    }

    private boolean isVisibleSystem(GalaxyMapSystem system, Set<String> linkedSystemIds) {
        if (system == null) return false;
        if (system.staticSystem() || linkedSystemIds.contains(system.id())) return true;
        if (system.home() || system.special()) return true;
        return system.ships() > 0 || system.bases() > 0 || system.hasLocalAssets();
    }

    private void drawGrid(Graphics2D g, int width, int height) {
        g.setColor(new Color(38, 57, 78, 60));
        for (int x = 70; x < width - 70; x += 88) g.drawLine(x, 116, x, height - 72);
        for (int y = 120; y < height - 72; y += 88) g.drawLine(52, y, width - 52, y);
    }

    private void drawLinks(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, NodeLayout> layout) {
        g.setStroke(new BasicStroke(1.7f));
        for (GalaxyMapLink link : snapshot.links()) {
            NodeLayout a = layout.get(link.fromSystemId());
            NodeLayout b = layout.get(link.toSystemId());
            if (a == null || b == null) continue;
            g.setColor(new Color(90, 186, 255, 92));
            g.draw(new Line2D.Double(a.x, a.y, b.x, b.y));
        }
    }

    private void drawNodes(Graphics2D g, GalaxyMapSnapshot snapshot, Map<String, NodeLayout> layout) {
        int count = snapshot.systems().size();
        for (GalaxyMapSystem system : snapshot.systems()) {
            NodeLayout node = layout.get(system.id());
            if (node == null) continue;
            double radius = nodeRadius(count, system.active());
            Ellipse2D circle = new Ellipse2D.Double(node.x - radius, node.y - radius, radius * 2, radius * 2);

            Color fill = system.home() ? new Color(43, 91, 126, 235)
                    : system.special() ? new Color(48, 64, 82, 240)
                    : new Color(34, 58, 82, 235);
            if (system.active()) fill = new Color(48, 112, 96, 245);
            g.setColor(fill);
            g.fill(circle);

            Color controlColor = new Color(system.controlColorRgb() & 0xFFFFFF);
            g.setStroke(new BasicStroke(system.active() ? 4.5f : 3.2f));
            g.setColor(controlColor);
            g.draw(circle);

            if (system.hasLocalAssets()) {
                double innerRadius = Math.max(6, radius - 6);
                g.setStroke(new BasicStroke(2f));
                g.setColor(new Color(255, 236, 150));
                g.draw(new Ellipse2D.Double(node.x - innerRadius, node.y - innerRadius, innerRadius * 2, innerRadius * 2));
            }

            int titleSize = count > 20 ? 10 : 12;
            int detailSize = count > 20 ? 9 : 10;
            g.setFont(g.getFont().deriveFont(Font.BOLD, (float)titleSize));
            g.setColor(Color.WHITE);
            drawCentered(g, system.name(), node.x, node.y - radius - 10);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, (float)detailSize));
            g.setColor(new Color(208, 229, 247));
            drawCentered(g, system.id(), node.x, node.y + 3);
            drawCentered(g, system.ships() + "S  " + system.bases() + "B  " + system.resources() + "R", node.x, node.y + radius + 13);
            g.setColor(controlColor);
            drawCentered(g, system.controlLabel(), node.x, node.y + radius + 26);
        }
    }

    private void drawLegend(Graphics2D g, int width, int height) {
        int x = 66;
        int y = Math.max(136, height - 50);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(new Color(208, 229, 247));
        g.drawString("Outer ring = controller/claimant   Gray = neutral   Gold inner ring = your assets   Green fill = current view", x, y);
    }

    private double nodeRadius(int count, boolean active) {
        double base = count > 24 ? 20 : count > 16 ? 24 : count > 10 ? 29 : 34;
        return active ? base + 5 : base;
    }

    private void drawCentered(Graphics2D g, String text, double cx, double y) {
        String safe = text == null ? "" : text;
        int w = g.getFontMetrics().stringWidth(safe);
        g.drawString(safe, (int)Math.round(cx - w / 2.0), (int)Math.round(y));
    }

    private Map<String, NodeLayout> layout(GalaxyMapSnapshot snapshot, int width, int height) {
        Map<String, NodeLayout> out = new HashMap<>();
        List<GalaxyMapSystem> staticSystems = new ArrayList<>();
        List<GalaxyMapSystem> homes = new ArrayList<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system.home()) homes.add(system); else staticSystems.add(system);
        }

        double centerX = width / 2.0;
        double centerY = height / 2.0 + 10;
        placeStaticRings(out, staticSystems, centerX, centerY, width, height);
        placeHomes(out, homes, centerX, centerY);
        return out;
    }

    private void placeStaticRings(Map<String, NodeLayout> out, List<GalaxyMapSystem> systems,
                                  double centerX, double centerY, int width, int height) {
        int count = systems.size();
        if (count == 0) return;
        int ringCapacity = count > 16 ? (int)Math.ceil(count / 2.0) : count;
        int rings = count > 16 ? 2 : 1;
        double maxRx = Math.max(150, (width - 190) * 0.43);
        double maxRy = Math.max(120, (height - 210) * 0.39);
        int index = 0;
        for (int ring = 0; ring < rings; ring++) {
            int remaining = count - index;
            int onRing = Math.min(ringCapacity, remaining);
            double scale = rings == 1 ? 1.0 : ring == 0 ? 1.0 : 0.66;
            double phase = ring == 0 ? -Math.PI / 2 : -Math.PI / 2 + Math.PI / Math.max(2, onRing);
            for (int i = 0; i < onRing; i++) {
                GalaxyMapSystem system = systems.get(index++);
                double angle = phase + i * Math.PI * 2.0 / onRing;
                out.put(system.id(), new NodeLayout(centerX + Math.cos(angle) * maxRx * scale,
                        centerY + Math.sin(angle) * maxRy * scale));
            }
        }
    }

    private void placeHomes(Map<String, NodeLayout> out, List<GalaxyMapSystem> homes, double centerX, double centerY) {
        int count = homes.size();
        for (int i = 0; i < count; i++) {
            double angle = -Math.PI / 2 + i * Math.PI * 2.0 / Math.max(1, count);
            double radius = count <= 1 ? 0 : 75 + count * 5;
            out.put(homes.get(i).id(), new NodeLayout(centerX + Math.cos(angle) * radius, centerY + Math.sin(angle) * radius));
        }
    }

    private record NodeLayout(double x, double y) { }
}
