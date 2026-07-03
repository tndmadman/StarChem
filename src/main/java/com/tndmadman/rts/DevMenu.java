package com.tndmadman.rts;

import java.awt.*;

final class DevMenu {
    private final HudWindow window = new HudWindow(18, 205, 292);
    private int targetIndex;

    boolean click(World world, int sx, int sy, boolean canEdit) {
        if (!window.contains(sx, sy, bodyHeight())) return false;
        if (sy <= window.y + 28) return window.press(sx, sy, bodyHeight());
        if (window.collapsed || !canEdit) return true;
        Base base = target(world);
        if (base == null) return true;
        int localY = sy - window.bodyY();
        if (localY >= 18 && localY <= 38) { targetIndex++; return true; }
        int row = (localY - 66) / 22;
        if (row >= 0 && row < Material.values().length) {
            HangarStore.add(base.inventory, Material.values()[row], 500);
            world.status = "Dev added 500 " + ResourceText.displayName(Material.values()[row]) + " to " + base.id + ".";
        }
        return true;
    }

    void drag(int sx, int sy, int screenW, int screenH) { window.drag(sx, sy, screenW, screenH); }
    void release() { window.release(); }

    void draw(Graphics2D g2, World world, boolean canEdit) {
        window.draw(g2, "DEV RESOURCES", bodyHeight(), new Color(255, 180, 80, 180));
        if (window.collapsed) return;
        int x = window.x + 12;
        int y = window.bodyY();
        Base base = target(world);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        if (!canEdit) {
            g2.setColor(new Color(255, 225, 150));
            g2.drawString("Host/solo only", x, y + 16);
            return;
        }
        if (base == null) {
            g2.setColor(new Color(255, 225, 150));
            g2.drawString("No local station", x, y + 16);
            return;
        }
        g2.setColor(Color.WHITE);
        g2.drawString("Target: " + base.type().name + " " + base.id, x, y + 16);
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Click target line to cycle", x, y + 38);
        int line = y + 66;
        for (Material material : Material.values()) {
            g2.setColor(material.color);
            g2.drawString("+500 " + ResourceText.displayName(material), x + 4, line);
            line += 22;
        }
    }

    private int bodyHeight() { return 227; }

    private Base target(World world) {
        Base found = null;
        int i = 0, localCount = 0;
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) localCount++;
        if (localCount == 0) return null;
        int want = Math.floorMod(targetIndex, localCount);
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId)) continue;
            if (i++ == want) found = base;
        }
        return found;
    }
}
