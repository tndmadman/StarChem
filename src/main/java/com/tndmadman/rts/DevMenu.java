package com.tndmadman.rts;

import java.awt.*;

final class DevMenu {
    private int targetIndex;

    boolean click(World world, int sx, int sy, boolean canEdit) {
        if (sx < 18 || sx > 310 || sy < 215 || sy > 455) return false;
        if (!canEdit) return true;
        Base base = target(world);
        if (base == null) return true;
        if (sy >= 235 && sy <= 255) { targetIndex++; return true; }
        int row = (sy - 278) / 22;
        if (row >= 0 && row < Material.values().length) {
            HangarStore.add(base.inventory, Material.values()[row], 500);
            world.status = "Dev added 500 " + ResourceText.displayName(Material.values()[row]) + " to " + base.id + ".";
        }
        return true;
    }

    void draw(Graphics2D g2, World world, boolean canEdit) {
        int x = 18, y = 205;
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRoundRect(x, y, 292, 255, 14, 14);
        g2.setColor(new Color(255, 180, 80, 180));
        g2.drawRoundRect(x, y, 292, 255, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("DEV RESOURCES", x + 12, y + 20);
        Base base = target(world);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        if (!canEdit) {
            g2.drawString("Host/solo only", x + 12, y + 44);
            return;
        }
        if (base == null) {
            g2.drawString("No local station", x + 12, y + 44);
            return;
        }
        g2.setColor(Color.WHITE);
        g2.drawString("Target: " + base.type().name + " " + base.id, x + 12, y + 44);
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Click target line to cycle", x + 12, y + 64);
        int line = y + 92;
        for (Material material : Material.values()) {
            g2.setColor(material.color);
            g2.drawString("+500 " + ResourceText.displayName(material), x + 16, line);
            line += 22;
        }
    }

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
