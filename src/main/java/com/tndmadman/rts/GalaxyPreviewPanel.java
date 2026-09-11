package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GalaxyPreviewPanel extends JPanel {
    private final GalaxyPreview preview;

    GalaxyPreviewPanel(GalaxyPreview preview) {
        this.preview = preview;
        setPreferredSize(new Dimension(760, 560));
        setMinimumSize(new Dimension(560, 420));
        setBackground(UiPalette.BACKDROP);
        setOpaque(true);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawHeader(g);
            if (preview == null || preview.snapshot() == null || preview.snapshot().empty()) {
                g.setColor(UiPalette.TEXT_MUTED);
                g.drawString("No galaxy could be generated for this setup.", 28, 78);
                return;
            }
            GalaxyMapSnapshot snapshot = preview.snapshot();
            Map<String,Point> layout = layout(snapshot.systems(), getWidth(), getHeight());
            drawLinks(g, snapshot.links(), layout);
            drawSystems(g, snapshot.systems(), layout, new HashSet<>(preview.startRegionSystemIds()));
            drawFooter(g, snapshot);
        } finally {
            g.dispose();
        }
    }

    private void drawHeader(Graphics2D g) {
        g.setFont(getFont().deriveFont(Font.BOLD, 20f));
        g.setColor(UiPalette.TEXT);
        g.drawString("GALAXY PREVIEW", 24, 32);
        if (preview == null) return;
        g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        g.setColor(UiPalette.TEXT_MUTED);
        GalaxyGenerationSettings settings = preview.settings();
        String summary = settings.targetSystemCount() + " systems | " + settings.topologyStyle().id()
                + " | seed " + preview.seed() + " | permanent topology only";
        g.drawString(summary, 24, 51);
    }

    private void drawLinks(Graphics2D g, List<GalaxyMapLink> links, Map<String,Point> layout) {
        g.setStroke(new BasicStroke(1.45f));
        g.setColor(new Color(112, 112, 103, 120));
        for (GalaxyMapLink link : links) {
            Point a = layout.get(link.fromSystemId());
            Point b = layout.get(link.toSystemId());
            if (a != null && b != null) g.draw(new Line2D.Double(a.x, a.y, b.x, b.y));
        }
    }

    private void drawSystems(Graphics2D g, List<GalaxyMapSystem> systems, Map<String,Point> layout,
                             Set<String> starts) {
        int count = systems.size();
        double radius = count > 48 ? 6.0 : count > 28 ? 7.5 : count > 16 ? 9.0 : 11.0;
        for (GalaxyMapSystem system : systems) {
            Point point = layout.get(system.id());
            if (point == null) continue;
            boolean start = starts.contains(system.id());
            double r = start ? radius + 4 : radius;
            Ellipse2D node = new Ellipse2D.Double(point.x - r, point.y - r, r * 2, r * 2);
            g.setColor(start ? new Color(137, 111, 65) : new Color(49, 54, 55));
            g.fill(node);
            g.setStroke(new BasicStroke(start ? 2.5f : 1.4f));
            g.setColor(start ? UiPalette.ACCENT : UiPalette.BORDER);
            g.draw(node);

            if (count <= 28 || start) {
                g.setFont(getFont().deriveFont(start ? Font.BOLD : Font.PLAIN, count > 18 ? 9f : 10f));
                g.setColor(UiPalette.TEXT);
                String label = system.name();
                int width = g.getFontMetrics().stringWidth(label);
                g.drawString(label, point.x - width / 2, (int)Math.round(point.y + r + 14));
            }
        }
    }

    private void drawFooter(Graphics2D g, GalaxyMapSnapshot snapshot) {
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(UiPalette.TEXT_MUTED);
        String footer = snapshot.links().size() + " permanent links | Gold nodes = candidate start regions"
                + " | Runtime events, ownership, resources and temporary wormholes are hidden";
        g.drawString(footer, 24, getHeight() - 18);
    }

    private Map<String,Point> layout(List<GalaxyMapSystem> systems, int width, int height) {
        Map<String,Point> out = new HashMap<>();
        if (systems == null || systems.isEmpty()) return out;
        int count = systems.size();
        int top = 82;
        int bottom = Math.max(top + 80, height - 55);
        double centerX = width / 2.0;
        double centerY = (top + bottom) / 2.0;
        int rings = count > 40 ? 3 : count > 20 ? 2 : 1;
        List<List<GalaxyMapSystem>> buckets = new ArrayList<>();
        for (int i = 0; i < rings; i++) buckets.add(new ArrayList<>());
        for (int i = 0; i < count; i++) buckets.get(i % rings).add(systems.get(i));

        double maxRadius = Math.max(90, Math.min(width * 0.42, (bottom - top) * 0.46));
        for (int ring = 0; ring < rings; ring++) {
            List<GalaxyMapSystem> bucket = buckets.get(ring);
            double radialScale = rings == 1 ? 1.0 : 0.55 + 0.45 * (ring + 1) / rings;
            double rx = maxRadius * radialScale;
            double ry = maxRadius * 0.72 * radialScale;
            double phase = ring * Math.PI / Math.max(2, bucket.size());
            for (int i = 0; i < bucket.size(); i++) {
                double angle = phase + Math.PI * 2.0 * i / bucket.size();
                int x = (int)Math.round(centerX + Math.cos(angle) * rx);
                int y = (int)Math.round(centerY + Math.sin(angle) * ry);
                out.put(bucket.get(i).id(), new Point(x, y));
            }
        }
        return out;
    }
}