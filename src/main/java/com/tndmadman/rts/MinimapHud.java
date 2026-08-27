package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MinimapHud {
    private static final int MARGIN = 16;
    private static final int MIN_WIDTH = 190;
    private static final int MAX_WIDTH = 280;
    private static final int MAX_MAP_HEIGHT = 190;
    private static final int TITLE_HEIGHT = 24;
    private static final long PING_LIFETIME_NANOS = 3_800_000_000L;

    private final Map<String, TrackedContact> previousContacts = new LinkedHashMap<>();
    private final List<Ping> pings = new ArrayList<>();
    private String trackedSystemId = "";
    private boolean contactsInitialized;

    void draw(Graphics2D source, World world, GameCamera camera, int screenW, int screenH) {
        if (source == null || world == null || camera == null || screenW <= 0 || screenH <= 0) return;
        Layout layout = layout(world, screenW, screenH);
        updateContactPings(world);

        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawFrame(g, layout);
        drawResources(g, world, layout.map);
        drawBases(g, world, layout.map);
        drawUnits(g, world, layout.map);
        FogOfWarView.drawMinimap(g, world, layout.map);
        drawWormholes(g, world, layout.map);
        drawPings(g, world, layout.map);
        drawCamera(g, camera.visibleWorldRect(screenW, screenH), world, layout.map);
        g.dispose();
    }

    boolean click(World world, GameCamera camera, int mouseX, int mouseY, int screenW, int screenH) {
        if (world == null || camera == null) return false;
        Layout layout = layout(world, screenW, screenH);
        if (!layout.outer.contains(mouseX, mouseY)) return false;
        if (!layout.map.contains(mouseX, mouseY)) return true;

        double nx = (mouseX - layout.map.x) / Math.max(1.0, layout.map.width);
        double ny = (mouseY - layout.map.y) / Math.max(1.0, layout.map.height);
        double worldX = Calc.clamp(nx * world.width, 0, world.width);
        double worldY = Calc.clamp(ny * world.height, 0, world.height);
        camera.centerAt(worldX, worldY, world, screenW, screenH);
        world.status = FogOfWarView.explored(world, worldX, worldY)
                ? "Camera moved from tactical minimap." : "Camera moved into unexplored space.";
        return true;
    }

    Rectangle bounds(World world, int screenW, int screenH) {
        return new Rectangle(layout(world, screenW, screenH).outer);
    }

    Point pointForWorld(World world, double worldX, double worldY, int screenW, int screenH) {
        Layout layout = layout(world, screenW, screenH);
        return new Point(
                (int)Math.round(layout.map.x + Calc.clamp(worldX / Math.max(1.0, world.width), 0, 1) * layout.map.width),
                (int)Math.round(layout.map.y + Calc.clamp(worldY / Math.max(1.0, world.height), 0, 1) * layout.map.height));
    }

    int pingCount() {
        prunePings();
        return pings.size();
    }

    private void drawFrame(Graphics2D g, Layout layout) {
        g.setColor(new Color(4, 9, 16, 218));
        g.fillRoundRect(layout.outer.x, layout.outer.y, layout.outer.width, layout.outer.height, 14, 14);
        g.setColor(new Color(110, 185, 225, 185));
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(layout.outer.x, layout.outer.y, layout.outer.width, layout.outer.height, 14, 14);
        g.setColor(new Color(8, 15, 24, 230));
        g.fillRect(layout.map.x, layout.map.y, layout.map.width, layout.map.height);
        g.setColor(new Color(82, 120, 150, 150));
        g.drawRect(layout.map.x, layout.map.y, layout.map.width, layout.map.height);

        Font old = g.getFont();
        g.setFont(old.deriveFont(Font.BOLD, 11f));
        g.setColor(new Color(225, 241, 250));
        g.drawString("TACTICAL", layout.outer.x + 10, layout.outer.y + 16);
        g.setFont(old.deriveFont(9f));
        g.setColor(new Color(150, 185, 205));
        String hint = "click to pan";
        int hintWidth = g.getFontMetrics().stringWidth(hint);
        g.drawString(hint, layout.outer.x + layout.outer.width - hintWidth - 10, layout.outer.y + 16);
        g.setFont(old);
    }

    private void drawResources(Graphics2D g, World world, Rectangle map) {
        for (ResourceNode node : world.resources) {
            if (node == null || !node.active) continue;
            Point2D p = mapPoint(world, map, node.x, node.y);
            Color c = node.material.color;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 135));
            g.fill(new Ellipse2D.Double(p.getX() - 1.2, p.getY() - 1.2, 2.4, 2.4));
        }
    }

    private void drawWormholes(Graphics2D g, World world, Rectangle map) {
        g.setColor(new Color(90, 235, 255, 225));
        for (FogOfWarView.KnownWormhole gate : FogOfWarView.knownWormholes(world)) {
            Point2D p = mapPoint(world, map, gate.x(), gate.y());
            int x = (int)Math.round(p.getX());
            int y = (int)Math.round(p.getY());
            Polygon diamond = new Polygon(new int[]{x, x + 4, x, x - 4}, new int[]{y - 4, y, y + 4, y}, 4);
            g.drawPolygon(diamond);
        }
    }

    private void drawBases(Graphics2D g, World world, Rectangle map) {
        for (Base base : world.bases.values()) {
            Point2D p = mapPoint(world, map, base.x, base.y);
            Color color = PlayerRegistry.color(base.playerId);
            int size = PlayerRegistry.isLocal(base.playerId) ? 7 : 6;
            int x = (int)Math.round(p.getX()) - size / 2;
            int y = (int)Math.round(p.getY()) - size / 2;
            g.setColor(color);
            g.fillRect(x, y, size, size);
            g.setColor(PlayerRegistry.isLocal(base.playerId) ? Color.WHITE : new Color(20, 20, 20));
            g.drawRect(x, y, size, size);
        }
    }

    private void drawUnits(Graphics2D g, World world, Rectangle map) {
        for (Unit unit : world.units.values()) {
            Point2D p = mapPoint(world, map, unit.x, unit.y);
            Color color = PlayerRegistry.color(unit.playerId);
            double size = PlayerRegistry.isLocal(unit.playerId) ? 4.6 : 4.0;
            g.setColor(color);
            g.fill(new Ellipse2D.Double(p.getX() - size / 2, p.getY() - size / 2, size, size));
            if (PlayerRegistry.isLocal(unit.playerId)) {
                g.setColor(new Color(245, 250, 255, 210));
                g.draw(new Ellipse2D.Double(p.getX() - size / 2 - 1, p.getY() - size / 2 - 1, size + 2, size + 2));
            }
        }
    }

    private void drawCamera(Graphics2D g, Rectangle2D visible, World world, Rectangle map) {
        double sx = map.width / Math.max(1.0, world.width);
        double sy = map.height / Math.max(1.0, world.height);
        Rectangle2D cameraRect = new Rectangle2D.Double(
                map.x + visible.getX() * sx,
                map.y + visible.getY() * sy,
                Math.max(2, visible.getWidth() * sx),
                Math.max(2, visible.getHeight() * sy));
        g.setColor(new Color(255, 242, 145, 225));
        g.setStroke(new BasicStroke(1.3f));
        g.draw(cameraRect);
    }

    private void drawPings(Graphics2D g, World world, Rectangle map) {
        prunePings();
        long now = System.nanoTime();
        for (Ping ping : pings) {
            double age = (now - ping.createdNanos) / (double)PING_LIFETIME_NANOS;
            double radius = 4 + 15 * Math.min(1, age);
            int alpha = (int)Math.round(220 * Math.max(0, 1 - age));
            Point2D point = mapPoint(world, map, ping.worldX, ping.worldY);
            g.setColor(new Color(ping.color.getRed(), ping.color.getGreen(), ping.color.getBlue(), alpha));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Ellipse2D.Double(point.getX() - radius, point.getY() - radius, radius * 2, radius * 2));
        }
    }

    private void updateContactPings(World world) {
        String systemId = world.activeSystemId();
        if (!systemId.equals(trackedSystemId)) {
            trackedSystemId = systemId;
            previousContacts.clear();
            pings.clear();
            contactsInitialized = false;
        }

        Map<String, TrackedContact> current = currentContacts(world);
        if (contactsInitialized) {
            for (Map.Entry<String, TrackedContact> entry : current.entrySet()) {
                if (previousContacts.containsKey(entry.getKey())) continue;
                TrackedContact contact = entry.getValue();
                if (contact.kind == ContactKind.ENEMY) addWorldPing(contact.x, contact.y, new Color(255, 105, 90));
                else if (contact.kind == ContactKind.WORMHOLE) addWorldPing(contact.x, contact.y, new Color(80, 230, 255));
            }
            for (Map.Entry<String, TrackedContact> entry : previousContacts.entrySet()) {
                if (current.containsKey(entry.getKey())) continue;
                TrackedContact contact = entry.getValue();
                if (contact.kind == ContactKind.LOCAL) addWorldPing(contact.x, contact.y, new Color(255, 80, 70));
            }
        }
        previousContacts.clear();
        previousContacts.putAll(current);
        contactsInitialized = true;
    }

    private Map<String, TrackedContact> currentContacts(World world) {
        Map<String, TrackedContact> out = new LinkedHashMap<>();
        VisibilityRules.Frame visibility = VisibilityRules.frame(world, PlayerRegistry.localId());
        for (Unit unit : world.units.values()) {
            boolean local = PlayerRegistry.isLocal(unit.playerId);
            if (!local && !visibility.unitVisible(unit)) continue;
            ContactKind kind = local ? ContactKind.LOCAL : ContactKind.ENEMY;
            out.put("U:" + unit.key(), new TrackedContact(unit.x, unit.y, kind));
        }
        for (Base base : world.bases.values()) {
            boolean local = PlayerRegistry.isLocal(base.playerId);
            if (!local && !visibility.baseVisible(base)) continue;
            ContactKind kind = local ? ContactKind.LOCAL : ContactKind.ENEMY;
            out.put("B:" + base.id, new TrackedContact(base.x, base.y, kind));
        }
        for (FogOfWarView.KnownWormhole gate : FogOfWarView.knownWormholes(world)) {
            String key = "W:" + gate.id();
            out.put(key, new TrackedContact(gate.x(), gate.y(), ContactKind.WORMHOLE));
        }
        return out;
    }

    private void addWorldPing(double worldX, double worldY, Color color) {
        pings.add(new Ping(worldX, worldY, color, System.nanoTime()));
        while (pings.size() > 12) pings.remove(0);
    }

    private Layout layout(World world, int screenW, int screenH) {
        int maxAllowedWidth = Math.max(100, screenW - MARGIN * 2);
        int width = Math.min(maxAllowedWidth, Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, screenW / 5)));
        int availableMapW = Math.max(84, width - 16);
        int availableMapH = Math.max(80, Math.min(MAX_MAP_HEIGHT, screenH / 4));
        double aspect = Math.max(0.2, world.width / Math.max(1.0, world.height));
        int mapW = availableMapW;
        int mapH = (int)Math.round(mapW / aspect);
        if (mapH > availableMapH) {
            mapH = availableMapH;
            mapW = (int)Math.round(mapH * aspect);
        }
        mapW = Math.max(80, Math.min(availableMapW, mapW));
        mapH = Math.max(70, Math.min(availableMapH, mapH));
        int outerH = TITLE_HEIGHT + mapH + 12;
        int outerX = Math.max(MARGIN, screenW - width - MARGIN);
        int bottomY = screenH - outerH - MARGIN;
        int outerY = bottomY >= 154 ? bottomY : Math.max(MARGIN, bottomY);
        Rectangle outer = new Rectangle(outerX, outerY, width, outerH);
        Rectangle map = new Rectangle(outerX + (width - mapW) / 2, outerY + TITLE_HEIGHT, mapW, mapH);
        return new Layout(outer, map);
    }

    private Point2D mapPoint(World world, Rectangle map, double worldX, double worldY) {
        double nx = Calc.clamp(worldX / Math.max(1.0, world.width), 0, 1);
        double ny = Calc.clamp(worldY / Math.max(1.0, world.height), 0, 1);
        return new Point2D.Double(map.x + nx * map.width, map.y + ny * map.height);
    }

    private void prunePings() {
        long now = System.nanoTime();
        pings.removeIf(ping -> now - ping.createdNanos >= PING_LIFETIME_NANOS);
    }

    private enum ContactKind { LOCAL, ENEMY, WORMHOLE }
    private record Layout(Rectangle outer, Rectangle map) { }
    private record TrackedContact(double x, double y, ContactKind kind) { }
    private record Ping(double worldX, double worldY, Color color, long createdNanos) { }
}
