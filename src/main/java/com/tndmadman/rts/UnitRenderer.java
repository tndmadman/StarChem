package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

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

        // Selection deliberately does not change the ship renderer. Hundreds of selected
        // ships therefore cost essentially the same to paint as hundreds of unselected ships.
        if (frame != null && unit.selected && PlayerRegistry.isLocal(unit.playerId)) {
            frame.noteSelectedDraw(false);
        }

        if (RenderCulling.visible(g2, unit.x, unit.y, 120)) {
            if (scale >= 0.24) drawPropulsionEffect(g2, unit);
            if (scale < 0.24) {
                drawFarMarker(g2, unit, playerColor, scale);
            } else if (scale < 0.78) {
                drawCachedHull(g2, unit, playerColor);
            } else {
                drawDetailedHull(g2, unit, playerColor);
            }
            if (scale >= 0.24 && unit.weaponFlashTimer > 0) drawWeaponFlash(g2, unit);
        }

        // Sensor/mining ranges are still available when explicitly toggled. Selection by
        // itself never turns on a ring, name, HP/cargo bar, weapon range, or status label.
        if (miningRangeOverlayVisible && PlayerRegistry.isLocal(unit.playerId)) {
            World world = frame == null ? PlayerRegistry.activeWorld() : frame.world();
            double scoutRange = unit.type().scoutRange > 0
                    ? (world == null ? unit.type().scoutRange : VisibilityRules.unitSensorRange(world, unit)) : 0;
            if (scoutRange > 0 && RenderCulling.visible(g2, unit.x, unit.y, scoutRange + 4)) {
                drawRangeCircle(g2, unit, playerColor, scoutRange);
            }
            double tractorRange = unit.type().tractorBeamCount > 0 ? unit.type().tractorRange : 0;
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
        if (ShowcaseShipRenderer.supports(unit.type())) ShowcaseShipRenderer.draw(s, unit.type(), playerColor);
        else ShipShape.draw(s, unit.type(), playerColor);
        ShipLighting.draw(s, unit.type());
        s.dispose();
    }

    private static void drawCachedHull(Graphics2D g2, Unit unit, Color playerColor) {
        BufferedImage sprite = ShipSpriteCache.sprite(unit, playerColor);
        if (sprite == null) {
            drawDetailedHull(g2, unit, playerColor);
            return;
        }
        int size = ShipSpriteCache.imageSize();
        g2.drawImage(sprite, (int)Math.round(unit.x - size / 2.0),
                (int)Math.round(unit.y - size / 2.0), null);
    }

    private static void drawFarMarker(Graphics2D g2, Unit unit, Color playerColor, double scale) {
        int radius = scale < 0.12 ? 8 : 6;
        g2.setColor(FAR_SHADOW);
        g2.fillOval((int)unit.x - radius - 2, (int)unit.y - radius - 2,
                (radius + 2) * 2, (radius + 2) * 2);
        g2.setColor(playerColor);
        g2.fillOval((int)unit.x - radius, (int)unit.y - radius, radius * 2, radius * 2);
    }

    private static void drawPropulsionEffect(Graphics2D g2, Unit unit) {
        double remaining = Math.hypot(unit.targetX - unit.x, unit.targetY - unit.y);
        boolean moving = unit.afterburnerActive || (unit.task != UnitTask.IDLE && remaining > 2.0);
        if (!moving) return;

        Graphics2D fx = (Graphics2D)g2.create();
        fx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double shipScale = Math.max(0.7, unit.type().size.scale);
        double rearDistance = 18.0 * shipScale;
        double trailLength = (unit.afterburnerActive ? 34.0 : 17.0) * shipScale;
        double cos = Math.cos(unit.heading);
        double sin = Math.sin(unit.heading);
        // ShipShape's engine modules live on +X in local hull space.
        double rearX = unit.x + cos * rearDistance;
        double rearY = unit.y + sin * rearDistance;
        double tailX = rearX + cos * trailLength;
        double tailY = rearY + sin * trailLength;

        fx.setStroke(new BasicStroke((float)(unit.afterburnerActive ? 7.0 : 4.2),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        fx.setColor(new Color(72, 145, 255, unit.afterburnerActive ? 92 : 58));
        fx.drawLine((int)Math.round(rearX), (int)Math.round(rearY),
                (int)Math.round(tailX), (int)Math.round(tailY));
        fx.setStroke(new BasicStroke((float)(unit.afterburnerActive ? 3.2 : 2.0),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        fx.setColor(new Color(130, 210, 255, unit.afterburnerActive ? 190 : 135));
        fx.drawLine((int)Math.round(rearX), (int)Math.round(rearY),
                (int)Math.round(tailX - cos * trailLength * 0.25),
                (int)Math.round(tailY - sin * trailLength * 0.25));
        double core = Math.max(2.2, 3.4 * shipScale);
        fx.setColor(new Color(228, 248, 255, 220));
        fx.fill(new Ellipse2D.Double(rearX - core * 0.5, rearY - core * 0.5, core, core));
        fx.dispose();
    }

    private static void drawWeaponFlash(Graphics2D g2, Unit unit) {
        Graphics2D fx = (Graphics2D)g2.create();
        fx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double shipScale = Math.max(0.7, unit.type().size.scale);
        double noseDistance = 23.0 * shipScale;
        double cos = Math.cos(unit.heading);
        double sin = Math.sin(unit.heading);
        // Turret barrels and cockpits project toward -X in ShipShape local space.
        double x = unit.x - cos * noseDistance;
        double y = unit.y - sin * noseDistance;
        double flash = (6.0 + Math.min(1.0, unit.weaponFlashTimer * 8.0) * 7.0) * shipScale;

        fx.setColor(new Color(255, 180, 65, 72));
        fx.fill(new Ellipse2D.Double(x - flash, y - flash, flash * 2, flash * 2));
        fx.setStroke(new BasicStroke((float)Math.max(1.0, 1.7 * shipScale),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        fx.setColor(new Color(255, 236, 164, 215));
        fx.drawLine((int)Math.round(x + cos * flash * 0.35), (int)Math.round(y + sin * flash * 0.35),
                (int)Math.round(x - cos * flash), (int)Math.round(y - sin * flash));
        fx.dispose();
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
