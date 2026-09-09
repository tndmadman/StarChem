package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;

/** Renders ship hulls and deliberate gameplay effects, never per-ship status UI. */
final class UnitRenderer {
    private static final Stroke ROUTE_STROKE =
            new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke WORK_STROKE =
            new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Color FAR_SHADOW = new Color(0, 0, 0, 165);
    private static boolean miningRangeOverlayVisible;

    private UnitRenderer() { }

    static boolean miningRangeOverlayVisible() { return miningRangeOverlayVisible; }

    static void toggleMiningRangeOverlay() {
        miningRangeOverlayVisible = !miningRangeOverlayVisible;
    }

    static void draw(Graphics2D g2, Unit unit, Color ignoredColor, boolean ignoredOwner) {
        if (g2 == null || unit == null) return;
        Color playerColor = PlayerRegistry.color(unit.playerId);
        SelectionRenderPolicy.Frame frame = SelectionRenderPolicy.currentFrame();
        double scale = frame == null ? SelectionRenderPolicy.scale(g2) : frame.scale();
        ShipType shipType = unit.type();

        // Selection deliberately does not change the ship renderer. Hundreds of selected
        // ships therefore cost essentially the same to paint as hundreds of unselected ships.
        if (frame != null && unit.selected && PlayerRegistry.isLocal(unit.playerId)) {
            frame.noteSelectedDraw(false);
        }

        double visualRadius = Math.max(96,
                ShipVisualCatalog.forType(shipType).renderRadius(shipType.size.scale));
        if (RenderCulling.visible(g2, unit.x, unit.y, visualRadius)) {
            // Propulsion is deliberately rendered before the hull so exhaust stays behind the ship.
            CombatVfxSystem.drawPropulsion(g2, unit, scale);
            if (scale < 0.24) {
                drawFarMarker(g2, unit, playerColor, scale);
            } else if (scale < 0.78) {
                drawCachedHull(g2, unit, playerColor);
            } else {
                drawDetailedHull(g2, unit, playerColor);
            }
            DamageStateEffects.drawUnit(g2, unit, scale);
        }

        // Sensor/mining ranges are still available when explicitly toggled. Selection by
        // itself never turns on a ring, name, HP/cargo bar, weapon range, or status label.
        if (miningRangeOverlayVisible && PlayerRegistry.isLocal(unit.playerId)) {
            World world = frame == null ? PlayerRegistry.activeWorld() : frame.world();
            double scoutRange = shipType.scoutRange > 0
                    ? (world == null ? shipType.scoutRange : VisibilityRules.unitSensorRange(world, unit)) : 0;
            if (scoutRange > 0 && RenderCulling.visible(g2, unit.x, unit.y, scoutRange + 4)) {
                drawRangeCircle(g2, unit, playerColor, scoutRange);
            }
            double tractorRange = shipType.tractorBeamCount > 0 ? shipType.tractorRange : 0;
            if (tractorRange > 0 && RenderCulling.visible(g2, unit.x, unit.y, tractorRange + 4)) {
                drawRangeCircle(g2, unit, playerColor, tractorRange);
            }
        }
    }

    private static void drawDetailedHull(Graphics2D g2, Unit unit, Color playerColor) {
        Graphics2D s = (Graphics2D)g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        s.translate(unit.x, unit.y);
        s.rotate(unit.heading);
        ShipShape.draw(s, unit.type(), playerColor, ShipVisualStyle.variantIndex(unit));
        s.dispose();
    }

    private static void drawCachedHull(Graphics2D g2, Unit unit, Color playerColor) {
        ShipSpriteCache.Sprite sprite = ShipSpriteCache.sprite(unit, playerColor);
        if (sprite == null) {
            drawDetailedHull(g2, unit, playerColor);
            return;
        }
        int size = sprite.worldSize();
        int x = (int)Math.round(unit.x - size / 2.0);
        int y = (int)Math.round(unit.y - size / 2.0);
        g2.drawImage(sprite.image(), x, y, size, size, null);
    }

    private static void drawFarMarker(Graphics2D g2, Unit unit, Color playerColor, double scale) {
        int radius = scale < 0.12 ? 8 : 6;
        g2.setColor(FAR_SHADOW);
        g2.fillOval((int)unit.x - radius - 2, (int)unit.y - radius - 2,
                (radius + 2) * 2, (radius + 2) * 2);
        g2.setColor(playerColor);
        g2.fillOval((int)unit.x - radius, (int)unit.y - radius, radius * 2, radius * 2);
    }

    static double displayedWeaponRange(World world, Unit unit) {
        return AttackRangeRules.effectiveWeaponRange(world, unit);
    }

    static void drawRoute(Graphics2D g2, Unit unit, Color ignoredColor) {
        if (g2 == null || unit == null || !PlayerRegistry.isLocal(unit.playerId) || !unit.selected) return;
        SelectionRenderPolicy.Frame selection = SelectionRenderPolicy.currentFrame();
        if (selection != null && selection.aggregate()) return;
        double dx = unit.targetX - unit.x;
        double dy = unit.targetY - unit.y;
        if (dx * dx + dy * dy <= 16) return;
        if (!RenderCulling.segmentVisible(g2, unit.x, unit.y, unit.targetX, unit.targetY, 24)) return;
        Color color = PlayerRegistry.color(unit.playerId);
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 145));
        g2.setStroke(ROUTE_STROKE);
        g2.drawLine((int)Math.round(unit.x), (int)Math.round(unit.y),
                (int)Math.round(unit.targetX), (int)Math.round(unit.targetY));
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    static void drawWorkLine(Graphics2D g2, Unit unit, ResourceNode node) {
        if (g2 == null || unit == null || node == null) return;
        if (!RenderCulling.segmentVisible(g2, unit.x, unit.y, node.x, node.y, 18)) return;
        Stroke oldStroke = g2.getStroke();
        Color oldColor = g2.getColor();
        g2.setStroke(WORK_STROKE);
        Color m = node.material.color;
        g2.setColor(new Color(m.getRed(), m.getGreen(), m.getBlue(), 150));
        g2.drawLine((int)unit.x, (int)unit.y, (int)node.x, (int)node.y);
        g2.setStroke(oldStroke);
        g2.setColor(oldColor);
    }

    private static void drawRangeCircle(Graphics2D g2, Unit unit, Color playerColor, double range) {
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 35));
        g2.fillOval((int)(unit.x - range), (int)(unit.y - range), (int)(range * 2), (int)(range * 2));
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 100));
        g2.drawOval((int)(unit.x - range), (int)(unit.y - range), (int)(range * 2), (int)(range * 2));
    }
}
