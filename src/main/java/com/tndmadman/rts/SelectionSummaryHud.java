package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One cheap screen-space status panel for the complete local ship selection. */
final class SelectionSummaryHud {
    private static final Color PANEL = new Color(5, 11, 16, 224);
    private static final Color PANEL_INNER = new Color(15, 24, 31, 220);
    private static final Color TEXT = new Color(225, 238, 246);
    private static final Color MUTED = new Color(145, 169, 184);
    private static final Color HP = new Color(103, 224, 119);
    private static final Color SHIELD = new Color(98, 186, 255);
    private static final Color DPS = new Color(255, 188, 105);
    private static final Color DAMAGE = new Color(255, 123, 105);
    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font VALUE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 14);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    private SelectionSummaryHud() { }

    /** Draw using the current screen-space clip, so callers do not need GamePanel dimensions. */
    static void draw(Graphics2D g2, World world) {
        if (g2 == null || world == null) return;
        Rectangle clip = g2.getClipBounds();
        if (clip == null) return;
        draw(g2, world, clip.x + clip.width, clip.y + clip.height);
    }

    static void draw(Graphics2D g2, World world, int screenWidth, int screenHeight) {
        if (g2 == null || world == null || screenWidth < 260 || screenHeight < 170) return;
        SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.current(world);
        if (frame == null || frame.selectedCount() <= 0) return;
        Summary summary = summarize(world, frame);
        if (summary.shipCount() <= 0) return;

        int panelW = Math.min(640, screenWidth - 24);
        int panelH = 112;
        int x = (screenWidth - panelW) / 2;
        int y = screenHeight - panelH - 18;
        Color accent = PlayerRegistry.color(PlayerRegistry.localId());

        Graphics2D s = (Graphics2D) g2.create();
        try {
            s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            s.setColor(PANEL);
            s.fillRoundRect(x, y, panelW, panelH, 14, 14);
            s.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 185));
            s.drawRoundRect(x, y, panelW - 1, panelH - 1, 14, 14);
            s.setColor(PANEL_INNER);
            s.fillRoundRect(x + 8, y + 31, panelW - 16, 51, 9, 9);

            s.setFont(TITLE_FONT);
            s.setColor(TEXT);
            s.drawString("FLEET SELECTION", x + 14, y + 21);
            String countText = summary.shipCount() + (summary.shipCount() == 1 ? " SHIP" : " SHIPS");
            int countW = s.getFontMetrics().stringWidth(countText);
            s.setColor(accent);
            s.drawString(countText, x + panelW - 14 - countW, y + 21);

            int metricsX = x + 16;
            int metricsW = panelW - 32;
            int cellW = metricsW / 4;
            drawMetric(s, metricsX, y + 45, cellW, "HP",
                    shortNumber(summary.hpNow()) + " / " + shortNumber(summary.hpMax()), HP);
            drawMetric(s, metricsX + cellW, y + 45, cellW, "SHIELD",
                    summary.shieldMax() > 0
                            ? shortNumber(summary.shieldNow()) + " / " + shortNumber(summary.shieldMax()) : "--",
                    SHIELD);
            drawMetric(s, metricsX + cellW * 2, y + 45, cellW, "MAX DPS",
                    shortNumber(summary.dps()), DPS);
            drawMetric(s, metricsX + cellW * 3, y + 45, cellW, "DAMAGED",
                    Integer.toString(summary.damaged()), summary.damaged() > 0 ? DAMAGE : TEXT);

            s.setFont(BODY_FONT);
            s.setColor(MUTED);
            String composition = clipText(s.getFontMetrics(), summary.composition(), panelW - 28);
            s.drawString(composition, x + 14, y + 101);
        } finally {
            s.dispose();
        }
    }

    private static void drawMetric(Graphics2D g2, int x, int y, int width,
                                   String label, String value, Color valueColor) {
        g2.setFont(LABEL_FONT);
        g2.setColor(MUTED);
        g2.drawString(label, x, y);
        g2.setFont(VALUE_FONT);
        g2.setColor(valueColor);
        g2.drawString(clipText(g2.getFontMetrics(), value, Math.max(36, width - 8)), x, y + 23);
    }

    static Summary summarize(World world, SelectionRenderPolicy.Frame frame) {
        if (world == null || frame == null || frame.selectedCount() <= 0) return Summary.EMPTY;
        int count = 0;
        int damaged = 0;
        double hpNow = 0;
        double hpMax = 0;
        double shieldNow = 0;
        double shieldMax = 0;
        double dps = 0;
        Map<String, Integer> composition = new LinkedHashMap<>();

        for (Unit unit : frame.selectedUnits()) {
            if (unit == null || !PlayerRegistry.isLocal(unit.playerId)) continue;
            ShipType type = unit.type();
            count++;
            hpNow += Math.max(0, unit.hp);
            hpMax += Math.max(0, type.maxHp);
            shieldNow += Math.max(0, unit.shield);
            shieldMax += Math.max(0, type.maxShield);
            if (unit.hp < type.maxHp * 0.995 || unit.shield < type.maxShield * 0.995) damaged++;

            WeaponVolley volley = WeaponRules.volley(world, unit, 0.0);
            if (volley.damage() > 0) dps += volley.damage() / Math.max(0.2, volley.cooldownSeconds());
            composition.merge(type.name, 1, Integer::sum);
        }
        return new Summary(count, hpNow, hpMax, shieldNow, shieldMax, dps, damaged,
                compositionText(composition));
    }

    private static String compositionText(Map<String, Integer> composition) {
        if (composition.isEmpty()) return "No ships selected";
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(composition.entrySet());
        rows.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareToIgnoreCase(b.getKey());
        });
        StringBuilder out = new StringBuilder();
        int shown = Math.min(4, rows.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append("   •   ");
            Map.Entry<String, Integer> row = rows.get(i);
            out.append(row.getKey()).append(" ×").append(row.getValue());
        }
        if (rows.size() > shown) out.append("   •   +").append(rows.size() - shown).append(" types");
        return out.toString();
    }

    private static String shortNumber(double value) {
        if (!Double.isFinite(value)) return "--";
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000.0);
        if (abs >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        if (abs >= 100 || Math.abs(value - Math.rint(value)) < 0.05) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String clipText(FontMetrics fm, String text, int maxWidth) {
        if (text == null || text.isBlank()) return "--";
        if (fm.stringWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        int limit = Math.max(0, maxWidth - fm.stringWidth(ellipsis));
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) > limit) end--;
        return end <= 0 ? ellipsis : text.substring(0, end).stripTrailing() + ellipsis;
    }

    record Summary(int shipCount, double hpNow, double hpMax,
                   double shieldNow, double shieldMax, double dps,
                   int damaged, String composition) {
        private static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0, "");
    }
}
