package com.tndmadman.rts;

import java.awt.*;
import java.util.List;

final class HangarHud {
    private HangarHud() { }

    static void draw(Graphics2D g2, World world, int screenW) {
        int x = Math.max(16, screenW - 380);
        int y = 18;
        int h = height(world);
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect(x, y, 360, h, 14, 14);
        g2.setColor(new Color(90, 190, 245, 170));
        g2.drawRoundRect(x, y, 360, h, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString("HANGARS", x + 14, y + 20);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        int line = y + 42;
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId)) continue;
            line = drawStore(g2, base.type().name + " " + base.id, base.inventory, x + 14, line, PlayerRegistry.color(base.playerId));
        }
        Unit unit = world.selectedUnit();
        if (unit != null) drawStore(g2, "Selected ship", unit.inventory, x + 14, line, new Color(255, 235, 145));
    }

    private static int drawStore(Graphics2D g2, String title, java.util.EnumMap<Material, Double> store, int x, int y, Color titleColor) {
        g2.setColor(titleColor);
        g2.drawString(title, x, y);
        int line = y + 16;
        List<String> rows = ResourceText.lines(store);
        g2.setColor(new Color(220, 238, 250));
        if (rows.isEmpty()) {
            g2.drawString("empty", x + 14, line);
            return line + 22;
        }
        for (String row : rows) {
            g2.drawString(row, x + 14, line);
            line += 16;
        }
        return line + 8;
    }

    private static int height(World world) {
        int rows = 2;
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) rows += 1 + Math.max(1, ResourceText.lines(base.inventory).size());
        Unit unit = world.selectedUnit();
        if (unit != null) rows += 1 + Math.max(1, ResourceText.lines(unit.inventory).size());
        return Math.min(520, Math.max(145, 30 + rows * 18));
    }
}
