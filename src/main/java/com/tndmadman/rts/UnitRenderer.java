package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Line2D;

final class UnitRenderer {
    private static final Stroke ROUTE_STROKE =
            new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static boolean miningRangeOverlayVisible;

    private UnitRenderer() { }

    static boolean miningRangeOverlayVisible() { return miningRangeOverlayVisible; }

    static void toggleMiningRangeOverlay() {
        miningRangeOverlayVisible = !miningRangeOverlayVisible;
    }

    static void draw(Graphics2D g2, Unit unit, Color ignoredColor, boolean ignoredOwner) {
        if (g2 == null || unit == null) return;
        Color playerColor = PlayerRegistry.color(unit.playerId);
        boolean owner = PlayerRegistry.isLocal(unit.playerId);
        World world = PlayerRegistry.activeWorld();

        double cullRadius = 96;
        if (unit.selected && owner) cullRadius = Math.max(cullRadius, displayedWeaponRange(world, unit) + 8);
        if (owner && unit.type().scoutRange > 0 && shouldDrawScoutCircle(unit)) {
            cullRadius = Math.max(cullRadius,
                    world == null ? unit.type().scoutRange : VisibilityRules.unitSensorRange(world, unit));
        }
        if (owner && shouldDrawTractorCircle(unit)) cullRadius = Math.max(cullRadius, unit.type().tractorRange);
        if (!RenderCulling.visible(g2, unit.x, unit.y, cullRadius)) return;

        Graphics2D s = (Graphics2D)g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        s.translate(unit.x, unit.y);
        s.rotate(unit.heading);
        ShipShape.draw(s, unit.type(), playerColor);
        s.dispose();

        drawBars(g2, unit);
        drawName(g2, unit, playerColor);
        if (!unit.basePackageType.isBlank()) {
            g2.setColor(new Color(255, 230, 130));
            g2.drawString("PKG", (int)unit.x - 12, (int)unit.y + 45);
        }
        if (unit.selected && owner) {
            g2.setColor(new Color(255, 245, 120));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval((int)unit.x - 26, (int)unit.y - 26, 52, 52);
            drawCargo(g2, unit);
            double weaponRange = displayedWeaponRange(world, unit);
            if (weaponRange > 0) {
                boolean fill = world == null || world.selectedCount() <= 1;
                drawWeaponRangeCircle(g2, unit, weaponRange, weaponRangeColor(world, unit), fill);
            }
        }
        if (owner && unit.type().scoutRange > 0 && shouldDrawScoutCircle(unit)) {
            double range = world == null ? unit.type().scoutRange : VisibilityRules.unitSensorRange(world, unit);
            drawRangeCircle(g2, unit, playerColor, range);
        }
        if (owner && shouldDrawTractorCircle(unit)) {
            drawRangeCircle(g2, unit, playerColor, unit.type().tractorRange);
        }
    }

    static double displayedWeaponRange(World world, Unit unit) {
        return AttackRangeRules.effectiveWeaponRange(world, unit);
    }

    static void drawRoute(Graphics2D g2, Unit unit, Color ignoredColor) {
        if (g2 == null || unit == null || !PlayerRegistry.isLocal(unit.playerId)) return;
        if (Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) <= 4) return;
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
        Graphics2D b = (Graphics2D)g2.create();
        b.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color m = node.material.color;
        b.setColor(new Color(m.getRed(), m.getGreen(), m.getBlue(), 150));
        b.draw(new Line2D.Double(unit.x, unit.y, node.x, node.y));
        b.dispose();
    }

    private static boolean shouldDrawScoutCircle(Unit unit) {
        if (unit.type().harvestKinds.isEmpty()) return true;
        return miningRangeOverlayVisible;
    }

    private static boolean shouldDrawTractorCircle(Unit unit) {
        return miningRangeOverlayVisible && unit.type().tractorBeamCount > 0 && unit.type().tractorRange > 0;
    }

    private static Color weaponRangeColor(World world, Unit unit) {
        WeaponType longest = null;
        for (WeaponType weapon : WeaponRules.loadout(world, unit)) {
            if (weapon.screenWeapon) continue;
            if (longest == null || weapon.range > longest.range) longest = weapon;
        }
        return longest == null || longest.color == null ? new Color(255, 174, 84) : longest.color;
    }

    private static void drawWeaponRangeCircle(Graphics2D g2, Unit unit, double range, Color color, boolean fill) {
        Graphics2D r = (Graphics2D)g2.create();
        r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int diameter = (int)Math.round(range * 2);
        int x = (int)Math.round(unit.x - range);
        int y = (int)Math.round(unit.y - range);
        if (fill) {
            r.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 18));
            r.fillOval(x, y, diameter, diameter);
        }
        r.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 155));
        r.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{12f, 7f}, 0));
        r.drawOval(x, y, diameter, diameter);
        r.dispose();
    }

    private static void drawName(Graphics2D g2, Unit unit, Color color) {
        String text = PlayerRegistry.name(unit.playerId);
        int tw = g2.getFontMetrics().stringWidth(text);
        int x = (int)unit.x - tw / 2;
        int y = (int)unit.y - 42;
        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRoundRect(x - 5, y - 12, tw + 10, 16, 7, 7);
        g2.setColor(color);
        g2.drawString(text, x, y);
    }

    private static void drawCargo(Graphics2D g2, Unit unit) {
        String text = "Cargo: " + ResourceText.shortLine(unit.inventory);
        int tw = g2.getFontMetrics().stringWidth(text);
        int x = (int)unit.x - tw / 2;
        int y = (int)unit.y + 55;
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect(x - 6, y - 13, tw + 12, 18, 8, 8);
        g2.setColor(new Color(220, 238, 250));
        g2.drawString(text, x, y);
    }

    private static void drawBars(Graphics2D g2, Unit unit) {
        int barW = 36;
        g2.setColor(new Color(20, 20, 20));
        g2.fillRect((int)unit.x - barW / 2, (int)unit.y - 30, barW, 5);
        g2.setColor(new Color(80, 230, 90));
        g2.fillRect((int)unit.x - barW / 2, (int)unit.y - 30,
                (int)(barW * unit.hp / Math.max(1, unit.type().maxHp)), 5);
        if (unit.type().cargoCapacity > 0) {
            g2.setColor(new Color(20, 20, 20));
            g2.fillRect((int)unit.x - barW / 2, (int)unit.y + 27, barW, 4);
            g2.setColor(new Color(110, 200, 255));
            g2.fillRect((int)unit.x - barW / 2, (int)unit.y + 27,
                    (int)(barW * unit.cargoUsed() / unit.type().cargoCapacity), 4);
        }
    }

    private static void drawRangeCircle(Graphics2D g2, Unit unit, Color playerColor, double range) {
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 35));
        g2.fillOval((int)(unit.x - range), (int)(unit.y - range), (int)(range * 2), (int)(range * 2));
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 100));
        g2.drawOval((int)(unit.x - range), (int)(unit.y - range), (int)(range * 2), (int)(range * 2));
    }
}
