from pathlib import Path

ROOT = Path('src/main/java/com/tndmadman/rts')


def replace_between(path: Path, start_marker: str, end_marker: str, replacement: str):
    text = path.read_text(encoding='utf-8')
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f'{path}: start marker not found: {start_marker!r}')
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'{path}: end marker not found: {end_marker!r}')
    path.write_text(text[:start] + replacement + text[end:], encoding='utf-8')


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {text.count(old)}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


# 1) Units render as hull/LOD only. Selection no longer unlocks per-ship bars,
# names, cargo, damage markers, selection rings, or automatic weapon ranges.
unit_renderer = ROOT / 'UnitRenderer.java'
unit_draw = '''    static void draw(Graphics2D g2, Unit unit, Color ignoredColor, boolean ignoredOwner) {
        if (g2 == null || unit == null) return;
        Color playerColor = PlayerRegistry.color(unit.playerId);
        SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.currentFrame();
        double scale = frame == null ? SelectionRenderPolicy.scale(g2) : frame.scale();

        // Selected fleets deliberately use the exact same hull-only renderer as ordinary
        // ships. Selection information is presented once in SelectionSummaryHud instead
        // of multiplying text/bars/rings by hundreds of ships.
        if (frame != null && unit.selected && PlayerRegistry.isLocal(unit.playerId)) {
            frame.noteSelectedDraw(false);
        }

        boolean bodyVisible = RenderCulling.visible(g2, unit.x, unit.y, 96);
        if (bodyVisible) {
            if (scale < 0.24) {
                drawFarMarker(g2, unit, playerColor, scale);
            } else if (scale < 0.78) {
                drawCachedHull(g2, unit, playerColor);
            } else {
                drawDetailedHull(g2, unit, playerColor);
            }
        }

        // Mining/sensor ranges remain available only when the player explicitly toggles
        // that overlay. Merely selecting a ship never adds world-space UI anymore.
        if (miningRangeOverlayVisible && PlayerRegistry.isLocal(unit.playerId)) {
            World world = frame == null ? PlayerRegistry.activeWorld() : frame.world();
            double scoutRange = unit.type().scoutRange > 0
                    ? (world == null ? unit.type().scoutRange : VisibilityRules.unitSensorRange(world, unit)) : 0;
            boolean scoutVisible = scoutRange > 0
                    && RenderCulling.visible(g2, unit.x, unit.y, scoutRange + 4);
            double tractorRange = unit.type().tractorBeamCount > 0 ? unit.type().tractorRange : 0;
            boolean tractorVisible = tractorRange > 0
                    && RenderCulling.visible(g2, unit.x, unit.y, tractorRange + 4);
            if (scoutVisible) drawRangeCircle(g2, unit, playerColor, scoutRange);
            if (tractorVisible) drawRangeCircle(g2, unit, playerColor, tractorRange);
        }
    }

'''
replace_between(unit_renderer,
                '    static void draw(Graphics2D g2, Unit unit, Color ignoredColor, boolean ignoredOwner) {',
                '    private static void drawVisibleOverlays',
                unit_draw)

# 2) Shield bars were drawn by WeaponSystem independently of UnitRenderer. Remove that
# pass while preserving projectile and weapon-beam rendering.
weapon_system = ROOT / 'WeaponSystem.java'
replace_once(weapon_system,
'''        for (Unit unit : unitsToDraw) {
            if (!RenderCulling.visible(g2, unit.x, unit.y, 90)) continue;
            drawUnitShieldBar(g2, unit);
        }
''', '')
replace_between(weapon_system,
                '    private void drawUnitShieldBar(Graphics2D g2, Unit unit) {',
                '    private void drawMovingShot',
                '')

# 3) The old fleet overlay still painted per-ship selection markers and aggregate order
# geometry. Keep the one-pass instrumentation hook, but draw no selection decoration.
fleet_overlay = ROOT / 'FleetSelectionOverlay.java'
fleet_overlay.write_text('''package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame hook for selected fleets. Selection is intentionally HUD-only: world-space ships
 * remain hull-only regardless of selection size.
 */
final class FleetSelectionOverlay {
    private static final AtomicLong FRAME_PASSES = new AtomicLong();
    private static volatile int lastMarkerCount;
    private static volatile int lastGroupCount;

    private FleetSelectionOverlay() { }

    static void drawFrame(Graphics2D g2, World world, SelectionRenderPolicy.Frame selection) {
        long started = System.nanoTime();
        FRAME_PASSES.incrementAndGet();
        lastMarkerCount = 0;
        lastGroupCount = 0;
        PerformanceTrace.recordSelectionDraw(System.nanoTime() - started, 0, 0);
    }

    static long framePassCountForTest() { return FRAME_PASSES.get(); }
    static int lastMarkerCountForTest() { return lastMarkerCount; }
    static int lastGroupCountForTest() { return lastGroupCount; }
}
''', encoding='utf-8')

# 4) A single screen-space aggregate panel replaces all per-ship health/shield/name UI.
summary_hud = ROOT / 'SelectionSummaryHud.java'
summary_hud.write_text('''package com.tndmadman.rts;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
''', encoding='utf-8')

# 5) Draw the aggregate panel in screen space, after the normal HUD and after the camera
# transform has been restored.
game_panel = ROOT / 'GamePanel.java'
replace_once(game_panel,
'''        drawHud(g2);
        drawControlGroupHud(g2);
''',
'''        drawHud(g2);
        SelectionSummaryHud.draw(g2, world, getWidth(), getHeight());
        drawControlGroupHud(g2);
''')

# 6) Update the performance regression gate to enforce hull-only selected rendering and
# verify that the aggregate summary sees the complete selection.
validator = ROOT / 'SelectionPerformanceValidator.java'
new_structure = '''    private static void validateSingleFrameStructure(World world, Graphics2D g2, int count,
                                                     Unit primary, Unit secondary) {
        long contextBefore = SelectionRenderPolicy.frameBuildCountForTest();
        long overlayBefore = FleetSelectionOverlay.framePassCountForTest();
        world.draw(g2);
        long contextDelta = SelectionRenderPolicy.frameBuildCountForTest() - contextBefore;
        long overlayDelta = FleetSelectionOverlay.framePassCountForTest() - overlayBefore;

        require(contextDelta == 1,
                "World.draw built selection context " + contextDelta + " times instead of once for " + count + " ships.");
        require(overlayDelta == 1,
                "World.draw ran selection frame hook " + overlayDelta + " times instead of once for " + count + " ships.");

        SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.current(world);
        require(frame != null, "Selection frame was not available after World.draw.");
        require(frame.selectedCount() == count,
                "Selection frame count mismatch: expected " + count + ", got " + frame.selectedCount());
        require(frame.visibleSelectedUnits().size() == count,
                "Visible selected count mismatch: expected " + count + ", got " + frame.visibleSelectedUnits().size());
        require(frame.primary() == primary, "Primary selected ship changed during frame context build.");
        require(frame.detailedSelectedDraws() == 0,
                "Hull-only selection unexpectedly rendered detailed per-ship UI.");
        require(frame.fleetSecondaryDraws() == count,
                "Expected every selected ship to use the cheap hull-only path; got "
                        + frame.fleetSecondaryDraws() + " of " + count + ".");
        require(FleetSelectionOverlay.lastMarkerCountForTest() == 0,
                "Hull-only selection rendered world-space selection markers.");
        require(FleetSelectionOverlay.lastGroupCountForTest() == 0,
                "Hull-only selection rendered aggregate world-space order geometry.");

        SelectionSummaryHud.Summary summary = SelectionSummaryHud.summarize(world, frame);
        require(summary.shipCount() == count,
                "Aggregate selection HUD count mismatch: expected " + count + ", got " + summary.shipCount());
        require(summary.hpMax() > 0 && summary.hpNow() > 0,
                "Aggregate selection HUD did not total ship HP.");
        require(summary.shieldMax() >= 0 && summary.shieldNow() >= 0,
                "Aggregate selection HUD returned invalid shield totals.");
        require(summary.dps() >= 0 && Double.isFinite(summary.dps()),
                "Aggregate selection HUD returned invalid DPS.");
    }

'''
replace_between(validator,
                '    private static void validateSingleFrameStructure(World world, Graphics2D g2, int count,',
                '    /**\n     * Real clients can paint a World that is not PlayerRegistry.activeWorld().',
                new_structure)

new_decoy = '''    private static void validateDifferentRegistryWorld(World renderedWorld, Graphics2D g2,
                                                       int count, Unit primary) {
        World registryWorld = new World("Selection registry decoy", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(registryWorld);
        PlayerRegistry.reset("P1", "Observer", 0x50BEFF);
        try {
            renderedWorld.draw(g2);
            SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.current(renderedWorld);
            require(frame != null, "Render frame disappeared when active registry world differed.");
            require(frame.primary() == primary, "Primary selection changed with a different registry world.");
            require(frame.detailedSelectedDraws() == 0,
                    "Different registry world re-enabled detailed per-ship selection UI.");
            require(frame.fleetSecondaryDraws() == count,
                    "Different registry world bypassed hull-only selection rendering: expected "
                            + count + ", got " + frame.fleetSecondaryDraws());
            SelectionSummaryHud.Summary summary = SelectionSummaryHud.summarize(renderedWorld, frame);
            require(summary.shipCount() == count,
                    "Aggregate selection HUD lost ships when registry world differed.");
        } finally {
            PlayerRegistry.activate(renderedWorld);
        }
    }

'''
replace_between(validator,
                '    private static void validateDifferentRegistryWorld(World renderedWorld, Graphics2D g2,',
                '    private static void setSelected',
                new_decoy)

print('Applied hull-only selection rendering and aggregate fleet HUD patch.')
