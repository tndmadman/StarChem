package com.tndmadman.rts;

import java.awt.*;

final class DevMenu {
    private static final int TARGET_Y = 18;
    private static final int TOGGLE_Y = 48;
    private static final int RESOURCE_Y = 88;
    private static final int ROW_H = 19;
    private static final double SPAWN_AMOUNT = 500.0;

    private final HudWindow window = new HudWindow(18, 205, 292);
    private int targetIndex;

    boolean click(World world, int sx, int sy, boolean canEdit) {
        if (!window.contains(sx, sy, bodyHeight())) return false;
        if (sy <= window.y + 28) return window.press(sx, sy, bodyHeight());
        if (window.collapsed || !canEdit) return true;
        int localY = sy - window.bodyY();
        if (hit(localY, TARGET_Y)) {
            if (localStationCount(world) > 0) targetIndex++;
            return true;
        }
        if (hit(localY, TOGGLE_Y)) {
            toggleFreeCrafting(world);
            return true;
        }
        Base base = target(world);
        if (base == null) return true;
        int row = (localY - (RESOURCE_Y - 14)) / ROW_H;
        if (row >= 0 && row < Material.values().length) {
            Material material = Material.values()[row];
            HangarStore.add(base.inventory, material, SPAWN_AMOUNT);
            world.status = "Dev added " + (int)SPAWN_AMOUNT + " " + material.label + " to " + base.id + " hangar.";
        }
        return true;
    }

    void drag(int sx, int sy, int screenW, int screenH) { window.drag(sx, sy, screenW, screenH); }
    void release() { window.release(); }

    void draw(Graphics2D g2, World world, boolean canEdit) {
        window.draw(g2, "DEV CRAFTING", bodyHeight(), new Color(255, 180, 80, 180));
        if (window.collapsed) return;
        int x = window.x + 12;
        int y = window.bodyY();
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        if (!canEdit) {
            g2.setColor(new Color(255, 225, 150));
            g2.drawString("Host/solo only", x, y + 16);
            return;
        }
        Base base = target(world);
        drawStationLine(g2, base, x, y + TARGET_Y);
        drawToggle(g2, world.devFreeBuildFor(PlayerRegistry.localId()), x, y + TOGGLE_Y);
        if (base == null) {
            g2.setColor(new Color(255, 225, 150));
            g2.drawString("No local station hangar", x, y + RESOURCE_Y);
            return;
        }
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Add 500 to selected hangar:", x, y + RESOURCE_Y - 22);
        int line = y + RESOURCE_Y;
        for (Material material : Material.values()) {
            g2.setColor(material.color);
            g2.drawString("+500 " + material.label, x + 4, line);
            line += ROW_H;
        }
    }

    private void drawStationLine(Graphics2D g2, Base base, int x, int y) {
        g2.setColor(Color.WHITE);
        String target = base == null ? "Station hangar: none" : "Station hangar: " + base.type().name + " " + base.id;
        g2.drawString(target, x, y);
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Click station line to cycle", x, y + 18);
    }

    private void drawToggle(Graphics2D g2, boolean enabled, int x, int y) {
        g2.setColor(enabled ? new Color(120, 255, 170) : new Color(255, 225, 150));
        g2.drawString((enabled ? "[x]" : "[ ]") + " Free crafting", x, y);
    }

    private void toggleFreeCrafting(World world) {
        String playerId = PlayerRegistry.localId();
        boolean next = !world.devFreeBuildFor(playerId);
        world.setDevFreeBuild(playerId, next);
        world.status = "Free crafting " + (next ? "enabled." : "disabled.");
    }

    private boolean hit(int localY, int baseline) { return localY >= baseline - 14 && localY <= baseline + 6; }
    private int bodyHeight() { return RESOURCE_Y + Material.values().length * ROW_H + 10; }

    private int localStationCount(World world) {
        int count = 0;
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) count++;
        return count;
    }

    private Base target(World world) {
        int count = localStationCount(world);
        if (count == 0) return null;
        int want = Math.floorMod(targetIndex, count);
        int i = 0;
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId)) continue;
            if (i++ == want) return base;
        }
        return null;
    }
}
