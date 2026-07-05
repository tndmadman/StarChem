package com.tndmadman.rts;

import java.awt.*;
import java.util.Locale;

final class ShieldDebugOverlay {
    void draw(Graphics2D g2, World world, int width) {
        Unit unit = world.selectedUnit();
        int x = 14;
        int y = 112;
        int w = 330;
        int h = unit == null ? 88 : 148;
        g2.setColor(new Color(0, 0, 0, 178));
        g2.fillRoundRect(x, y, w, h, 12, 12);
        g2.setColor(new Color(80, 180, 255, 170));
        g2.drawRoundRect(x, y, w, h, 12, 12);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString("DEV SHIELD DEBUG", x + 12, y + 20);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(220, 238, 250));
        g2.drawString("Shots: " + world.shots.size() + " | Local bases: " + localBases(world), x + 12, y + 42);
        if (unit == null) {
            g2.drawString("Select a ship to inspect shield state.", x + 12, y + 64);
            return;
        }
        ShipType type = unit.type();
        g2.drawString(type.name + " #" + unit.unitId + " | " + unit.task, x + 12, y + 64);
        g2.drawString("HP: " + one(unit.hp) + " / " + one(type.maxHp), x + 12, y + 84);
        g2.drawString("Shield: " + one(unit.shield) + " / " + one(type.maxShield), x + 12, y + 104);
        String regen = unit.shieldDelayTimer > 0 ? "paused " + one(unit.shieldDelayTimer) + "s" : one(type.shieldRegen) + "/s";
        g2.drawString("Regen: " + regen + " | Delay: " + one(type.shieldRegenDelay) + "s", x + 12, y + 124);
        g2.drawString("Target: " + (unit.attackTarget.isBlank() ? "none" : unit.attackTarget), x + 12, y + 144);
    }

    private int localBases(World world) {
        int count = 0;
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) count++;
        return count;
    }

    private String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
}
