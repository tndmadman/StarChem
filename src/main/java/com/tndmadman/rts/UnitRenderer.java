package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Line2D;

final class UnitRenderer {
    private static boolean miningRangeOverlayVisible;

    private UnitRenderer() { }

    static boolean miningRangeOverlayVisible() { return miningRangeOverlayVisible; }

    static void toggleMiningRangeOverlay() {
        miningRangeOverlayVisible = !miningRangeOverlayVisible;
    }

    static void draw(Graphics2D g2, Unit unit, Color ignoredColor, boolean ignoredOwner) {
        Color playerColor = PlayerRegistry.color(unit.playerId);
        boolean owner = PlayerRegistry.isLocal(unit.playerId);
        Graphics2D s = (Graphics2D) g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        s.translate(unit.x, unit.y);
        s.rotate(unit.heading);
        ShipShape.draw(s, unit.type(), playerColor);
        s.dispose();
        drawBars(g2, unit);
        drawName(g2, unit, playerColor);
        if (!unit.basePackageType.isBlank()) {
            g2.setColor(new Color(255,230,130));
            g2.drawString("PKG", (int)unit.x - 12, (int)unit.y + 45);
        }
        if (unit.selected && owner) {
            g2.setColor(new Color(255,245,120));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval((int)unit.x - 26, (int)unit.y - 26, 52, 52);
            drawCargo(g2, unit);
        }
        World world = PlayerRegistry.activeWorld();
        if (owner && unit.type().scoutRange > 0 && shouldDrawScoutCircle(unit)) {
            double range = world == null ? unit.type().scoutRange : VisibilityRules.unitSensorRange(world, unit);
            drawRangeCircle(g2, unit, playerColor, range);
        }
        if (owner && shouldDrawTractorCircle(unit)) drawRangeCircle(g2, unit, playerColor, unit.type().tractorRange);
    }

    static void drawRoute(Graphics2D g2, Unit unit, Color ignoredColor) {
        if (!PlayerRegistry.isLocal(unit.playerId)) return;
        if (Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) <= 4) return;
        Color color = PlayerRegistry.color(unit.playerId);
        Graphics2D r = (Graphics2D) g2.create();
        r.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 160));
        r.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{9f, 8f}, 0));
        r.draw(new Line2D.Double(unit.x, unit.y, unit.targetX, unit.targetY));
        r.dispose();
    }

    static void drawWorkLine(Graphics2D g2, Unit unit, ResourceNode node) {
        Graphics2D b = (Graphics2D) g2.create();
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
        g2.setColor(new Color(20,20,20));
        g2.fillRect((int)unit.x - barW/2, (int)unit.y - 30, barW, 5);
        g2.setColor(new Color(80,230,90));
        g2.fillRect((int)unit.x - barW/2, (int)unit.y - 30, (int)(barW * unit.hp / Math.max(1, unit.type().maxHp)), 5);
        if (unit.type().cargoCapacity > 0) {
            g2.setColor(new Color(20,20,20));
            g2.fillRect((int)unit.x - barW/2, (int)unit.y + 27, barW, 4);
            g2.setColor(new Color(110,200,255));
            g2.fillRect((int)unit.x - barW/2, (int)unit.y + 27, (int)(barW * unit.cargoUsed() / unit.type().cargoCapacity), 4);
        }
    }

    private static void drawRangeCircle(Graphics2D g2, Unit unit, Color playerColor, double range) {
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 35));
        g2.fillOval((int)(unit.x - range), (int)(unit.y - range), (int)(range * 2), (int)(range * 2));
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 100));
        g2.drawOval((int)(unit.x - range), (int)(unit.y - range), (int)(range * 2), (int)(range * 2));
    }
}
