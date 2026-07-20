package com.tndmadman.rts;

import java.awt.*;
import java.util.List;

final class AiDevPanel {
    private static final int ROW = 18;
    private static final int ROW_BASELINE_Y = 170;
    private static final int ROW_COUNT = 23;
    private final HudWindow window = new HudWindow(330, 205, 430);
    private boolean overlay = true;
    private boolean pathLines = true;

    boolean click(World world, PeerNetwork devAuthorityNetwork, int sx, int sy, boolean canEdit) {
        if (!window.contains(sx, sy, bodyHeight())) return false;
        if (sy <= window.y + 28) return window.press(sx, sy, bodyHeight());
        if (window.collapsed || !canEdit) return true;
        AiDevSettings settings = settings(world, devAuthorityNetwork);
        int row = clickedRow(sy - window.bodyY());
        if (row < 0 || row >= ROW_COUNT) return true;
        switch (row) {
            case 0 -> overlay = !overlay;
            case 1 -> pathLines = !pathLines;
            case 2 -> runDevCommand(world, devAuthorityNetwork, "togglePauseAi", () -> settings.pauseAi = !settings.pauseAi);
            case 3 -> runDevCommand(world, devAuthorityNetwork, "stepAi", () -> settings.stepAi = true);
            case 4 -> runDevCommand(world, devAuthorityNetwork, "toggleFastAi", () -> settings.fastAi = !settings.fastAi);
            case 5 -> runDevCommand(world, devAuthorityNetwork, "toggleFreezePlayerUnits", () -> settings.freezePlayerUnits = !settings.freezePlayerUnits);
            case 6 -> runDevCommand(world, devAuthorityNetwork, "toggleFreezeNpcCombat", () -> settings.freezeNpcCombat = !settings.freezeNpcCombat);
            case 7 -> runDevCommand(world, devAuthorityNetwork, "toggleDisableAttacks", () -> settings.disableAttacks = !settings.disableAttacks);
            case 8 -> runDevCommand(world, devAuthorityNetwork, "toggleDisableEconomy", () -> settings.disableEconomy = !settings.disableEconomy);
            case 9 -> runDevCommand(world, devAuthorityNetwork, "togglePreset", settings::togglePreset);
            case 10 -> runDevCommand(world, devAuthorityNetwork, "spawnCorsairs", () -> AiDevCommands.spawnCorsairs(world));
            case 11 -> runDevCommand(world, devAuthorityNetwork, "killCorsairs", () -> AiDevCommands.killCorsairs(world));
            case 12 -> runDevCommand(world, devAuthorityNetwork, "resetCorsairs", () -> AiDevCommands.resetCorsairs(world));
            case 13 -> runDevCommand(world, devAuthorityNetwork, "giveCorsairResources", () -> AiDevCommands.giveCorsairResources(world));
            case 14 -> runDevCommand(world, devAuthorityNetwork, "givePlayerResources", () -> AiDevCommands.givePlayerResources(world));
            case 15 -> runDevCommand(world, devAuthorityNetwork, "spawnLootField", () -> AiDevCommands.spawnLootField(world));
            case 16 -> runDevCommand(world, devAuthorityNetwork, "spawnAttackWave", () -> AiDevCommands.spawnAttackWave(world));
            case 17 -> runDevCommand(world, devAuthorityNetwork, "forceRaid", () -> AiDevCommands.forceRaid(world));
            case 18 -> runDevCommand(world, devAuthorityNetwork, "forceStation", () -> AiDevCommands.forceStation(world));
            case 19 -> runDevCommand(world, devAuthorityNetwork, "forceResearch", () -> AiDevCommands.forceResearch(world));
            case 20 -> runDevCommand(world, devAuthorityNetwork, "forceCraft", () -> AiDevCommands.forceCraft(world));
            case 21 -> AiDevCommands.copySnapshot(world);
            case 22 -> AiDevCommands.hotReload(settingsWorld(world, devAuthorityNetwork));
            default -> { }
        }
        return true;
    }

    void drag(int sx, int sy, int screenW, int screenH) { window.drag(sx, sy, screenW, screenH); }
    void release() { window.release(); }

    void draw(Graphics2D g2, World world, PeerNetwork devAuthorityNetwork, boolean canEdit) {
        window.draw(g2, "AI DEVTOOLS", bodyHeight(), new Color(180, 120, 255, 190));
        if (window.collapsed) return;
        int x = window.x + 12;
        int y = window.bodyY();
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        if (!canEdit) { g2.setColor(new Color(255, 225, 150)); g2.drawString("Host dev approval required", x, y + 16); return; }

        AiDevSettings settings = settings(world, devAuthorityNetwork);
        NpcFaction f = AiDevSnapshot.corsairs();
        g2.setColor(Color.WHITE);
        int yy = y + 14;
        for (String line : AiDevSnapshot.summary(world, f, settings)) { g2.drawString(line, x, yy); yy += 15; }
        g2.setColor(new Color(255, 225, 150));
        g2.drawString("Blocked: " + AiDevSnapshot.blockedReason(world, f), x, yy);

        String[] rows = rows(settings);
        for (int i = 0; i < rows.length; i++) drawRow(g2, x, y + ROW_BASELINE_Y + i * ROW, rows[i]);

        int logY = y + ROW_BASELINE_Y + rows.length * ROW + 16;
        g2.setColor(new Color(190, 220, 255));
        g2.drawString("Brain file: " + trim(AiBrainLog.status(), 56), x, logY);
        logY += 15;
        g2.drawString("Recent AI events:", x, logY);
        logY += 15;
        List<String> log = AiDevLog.lines(7);
        g2.setColor(new Color(220, 235, 245));
        for (String line : log) { g2.drawString(trim(line, 62), x, logY); logY += 14; }
    }

    boolean overlayEnabled() { return overlay; }
    boolean pathLinesEnabled() { return pathLines; }
    boolean toggleOverlay() { overlay = !overlay; return overlay; }

    private String[] rows(AiDevSettings settings) {
        return new String[]{
                check("Overlay", overlay),
                check("Path / target lines", pathLines),
                check("Pause AI only", settings.pauseAi),
                "[>] Step AI once",
                check("Speed AI decisions 5x", settings.fastAi),
                check("Freeze player units", settings.freezePlayerUnits),
                check("Freeze NPC combat", settings.freezeNpcCombat),
                check("Disable attacks, keep economy", settings.disableAttacks),
                check("Disable economy, keep combat", settings.disableEconomy),
                "Preset: " + settings.difficultyPreset().label,
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

    private AiDevSettings settings(World world, PeerNetwork devAuthorityNetwork) {
        return settingsWorld(world, devAuthorityNetwork).aiDevSettings;
    }

    private World settingsWorld(World world, PeerNetwork devAuthorityNetwork) {
        return devAuthorityNetwork == null ? world : devAuthorityNetwork.devSettingsWorld(world);
    }

    private void runDevCommand(World world, PeerNetwork devAuthorityNetwork, String command, Runnable localAction) {
        if (devAuthorityNetwork != null) devAuthorityNetwork.devAiCommand(PlayerRegistry.localId(), command);
        else localAction.run();
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
    private int bodyHeight() { return ROW_BASELINE_Y + ROW_COUNT * ROW + 140; }
}
