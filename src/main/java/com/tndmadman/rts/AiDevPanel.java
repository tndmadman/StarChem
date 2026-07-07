package com.tndmadman.rts;

import java.awt.*;
import java.util.List;

final class AiDevPanel {
    private static final int ROW = 18;
    private static final int ROW_BASELINE_Y = 96;
    private final HudWindow window = new HudWindow(330, 205, 430);

    boolean click(World world, int sx, int sy, boolean canEdit) {
        if (!window.contains(sx, sy, bodyHeight())) return false;
        if (sy <= window.y + 28) return window.press(sx, sy, bodyHeight());
        if (window.collapsed || !canEdit) return true;
        int row = clickedRow(sy - window.bodyY());
        if (row < 0 || row >= rows().length) return true;
        switch (row) {
            case 0 -> AiDevSettings.overlay = !AiDevSettings.overlay;
            case 1 -> AiDevSettings.pathLines = !AiDevSettings.pathLines;
            case 2 -> AiDevSettings.pauseAi = !AiDevSettings.pauseAi;
            case 3 -> AiDevSettings.stepAi = true;
            case 4 -> AiDevSettings.fastAi = !AiDevSettings.fastAi;
            case 5 -> AiDevSettings.freezePlayerUnits = !AiDevSettings.freezePlayerUnits;
            case 6 -> AiDevSettings.freezeNpcCombat = !AiDevSettings.freezeNpcCombat;
            case 7 -> AiDevSettings.disableAttacks = !AiDevSettings.disableAttacks;
            case 8 -> AiDevSettings.disableEconomy = !AiDevSettings.disableEconomy;
            case 9 -> AiDevSettings.togglePreset();
            case 10 -> AiDevCommands.spawnCorsairs(world);
            case 11 -> AiDevCommands.killCorsairs(world);
            case 12 -> AiDevCommands.resetCorsairs(world);
            case 13 -> AiDevCommands.giveCorsairResources(world);
            case 14 -> AiDevCommands.givePlayerResources(world);
            case 15 -> AiDevCommands.spawnLootField(world);
            case 16 -> AiDevCommands.spawnAttackWave(world);
            case 17 -> AiDevCommands.forceRaid(world);
            case 18 -> AiDevCommands.forceStation(world);
            case 19 -> AiDevCommands.forceResearch(world);
            case 20 -> AiDevCommands.forceCraft(world);
            case 21 -> AiDevCommands.copySnapshot(world);
            case 22 -> AiDevCommands.hotReload(world);
            default -> { }
        }
        return true;
    }

    void drag(int sx, int sy, int screenW, int screenH) { window.drag(sx, sy, screenW, screenH); }
    void release() { window.release(); }

    void draw(Graphics2D g2, World world, boolean canEdit) {
        window.draw(g2, "AI DEVTOOLS", bodyHeight(), new Color(180, 120, 255, 190));
        if (window.collapsed) return;
        int x = window.x + 12;
        int y = window.bodyY();
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        if (!canEdit) { g2.setColor(new Color(255, 225, 150)); g2.drawString("Host/solo only", x, y + 16); return; }

        NpcFaction f = AiDevSnapshot.corsairs();
        g2.setColor(Color.WHITE);
        int yy = y + 14;
        for (String line : AiDevSnapshot.summary(world, f)) { g2.drawString(line, x, yy); yy += 15; }
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Blocked: " + AiDevSnapshot.blockedReason(world, f), x, yy); yy += 18;

        String[] rows = rows();
        for (int i = 0; i < rows.length; i++) drawRow(g2, x, y + ROW_BASELINE_Y + i * ROW, rows[i]);

        int logY = y + ROW_BASELINE_Y + rows.length * ROW + 16;
        g2.setColor(new Color(190, 220, 255));
        g2.drawString("Brain log:", x, logY);
        logY += 15;
        List<String> log = AiDevLog.lines(7);
        g2.setColor(new Color(220, 235, 245));
        for (String line : log) { g2.drawString(trim(line, 62), x, logY); logY += 14; }
    }

    private String[] rows() {
        return new String[]{
                check("Overlay", AiDevSettings.overlay),
                check("Path / target lines", AiDevSettings.pathLines),
                check("Pause AI only", AiDevSettings.pauseAi),
                "[>] Step AI once",
                check("Speed AI decisions 5x", AiDevSettings.fastAi),
                check("Freeze player units", AiDevSettings.freezePlayerUnits),
                check("Freeze NPC combat", AiDevSettings.freezeNpcCombat),
                check("Disable attacks, keep economy", AiDevSettings.disableAttacks),
                check("Disable economy, keep combat", AiDevSettings.disableEconomy),
                "Preset: " + NpcDifficultyPreset.current().label,
                "Spawn Corsairs now",
                "Kill all Corsairs",
                "Reset Corsair AI state",
                "Give Corsairs resources",
                "Give player resources",
                "Spawn loot field",
                "Spawn enemy attack wave",
                "Force Corsair raid",
                "Force Corsair station deploy",
                "Force Corsair research attempt",
                "Force Corsair fuel craft",
                "Copy AI snapshot",
                "Reload npc config request"
        };
    }

    private int clickedRow(int localY) {
        int firstTop = ROW_BASELINE_Y - ROW / 2;
        if (localY < firstTop) return -1;
        return (localY - firstTop) / ROW;
    }

    private void drawRow(Graphics2D g2, int x, int y, String text) {
        g2.setColor(text.startsWith("[x]") ? new Color(120, 255, 170) : new Color(255, 225, 150));
        g2.drawString(text, x + 4, y);
    }

    private String check(String label, boolean on) { return (on ? "[x] " : "[ ] ") + label; }
    private String trim(String text, int max) { return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "..."; }
    private int bodyHeight() { return ROW_BASELINE_Y + rows().length * ROW + 120; }
}
