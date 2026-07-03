package com.tndmadman.rts;

import java.awt.*;
import java.util.List;

final class HangarHud {
    private final HudWindow window = new HudWindow(-1, 18, 360);

    void draw(Graphics2D g2, World world, int screenW) {
        if (window.x < 0) window.x = Math.max(16, screenW - 380);
        int bodyH = bodyHeight(world);
        window.draw(g2, "HANGARS", bodyH, new Color(90, 190, 245, 170));
        if (window.collapsed) return;
        int line = window.bodyY();
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId)) continue;
            line = drawStore(g2, base.type().name + " " + base.id, base.inventory, window.x + 14, line, PlayerRegistry.color(base.playerId));
        }
        for (Unit unit : world.units.values()) {
            if (!PlayerRegistry.isLocal(unit.playerId) || !MobileDepot.isDepot(unit)) continue;
            line = drawStore(g2, "Freighter #" + unit.unitId, unit.inventory, window.x + 14, line, new Color(120, 220, 255));
        }
        Unit unit = world.selectedUnit();
        if (unit != null && !MobileDepot.isDepot(unit)) drawStore(g2, "Selected ship", unit.inventory, window.x + 14, line, new Color(255, 235, 145));
    }

    boolean mousePressed(World world, int x, int y) { return window.press(x, y, bodyHeight(world)); }
    void mouseDragged(int x, int y, int screenW, int screenH) { window.drag(x, y, screenW, screenH); }
    void mouseReleased() { window.release(); }

    private int drawStore(Graphics2D g2, String title, java.util.EnumMap<Material, Double> store, int x, int y, Color titleColor) {
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

    private int bodyHeight(World world) {
        int rows = 2;
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) rows += 1 + Math.max(1, ResourceText.lines(base.inventory).size());
        for (Unit unit : world.units.values()) if (PlayerRegistry.isLocal(unit.playerId) && MobileDepot.isDepot(unit)) rows += 1 + Math.max(1, ResourceText.lines(unit.inventory).size());
        Unit unit = world.selectedUnit();
        if (unit != null && !MobileDepot.isDepot(unit)) rows += 1 + Math.max(1, ResourceText.lines(unit.inventory).size());
        return Math.min(492, Math.max(117, 8 + rows * 18));
    }
}
