package com.tndmadman.rts;

import java.awt.*;

final class HangarHud {
    private HangarHud() { }

    static void draw(Graphics2D g2, World world, int screenW) {
        int x = Math.max(16, screenW - 360);
        int y = 18;
        int line = y + 42;
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRoundRect(x, y, 340, 170, 14, 14);
        g2.setColor(new Color(90, 190, 245, 170));
        g2.drawRoundRect(x, y, 340, 170, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString("HANGARS", x + 14, y + 20);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId)) continue;
            g2.setColor(PlayerRegistry.color(base.playerId));
            g2.drawString(base.type().name + " " + base.id, x + 14, line);
            g2.setColor(new Color(220, 238, 250));
            g2.drawString(ResourceText.shortLine(base.inventory), x + 145, line);
            line += 20;
        }
        Unit unit = world.selectedUnit();
        if (unit != null) {
            g2.setColor(new Color(255, 235, 145));
            g2.drawString("Ship cargo", x + 14, line);
            g2.setColor(new Color(220, 238, 250));
            g2.drawString(ResourceText.shortLine(unit.inventory), x + 145, line);
        }
    }
}
