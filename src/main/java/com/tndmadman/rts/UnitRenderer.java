package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;

final class UnitRenderer {
    private static final Stroke ROUTE_STROKE =
            new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke SELECTED_STROKE = new BasicStroke(2f);
    private static final Stroke WORK_STROKE =
            new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke WEAPON_RANGE_STROKE =
            new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{12f, 7f}, 0);
    private static final Color SELECTED_COLOR = new Color(255, 245, 120);
    private static final Color FAR_SHADOW = new Color(0, 0, 0, 165);
    private static final Color PACKAGE_COLOR = new Color(255, 230, 130);
    private static final Color BAR_BACKGROUND = new Color(20, 20, 20);
    private static final Color HP_COLOR = new Color(80, 230, 90);
    private static final Color DAMAGE_COLOR = new Color(255, 112, 88);
    private static final Color CARGO_COLOR = new Color(110, 200, 255);
    private static final Color CARGO_TEXT = new Color(220, 238, 250);
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
        boolean selectedOwner = unit.selected && owner;
        World world = PlayerRegistry.activeWorld();
        double scale = SelectionRenderPolicy.scale(g2);

        SelectionRenderPolicy.Snapshot selection = selectedOwner && world != null
                ? SelectionRenderPolicy.snapshot(world) : null;
        int selectedCount = selection == null ? (selectedOwner ? 1 : 0) : selection.selectedCount();
        boolean aggregateSelection = selectedOwner && selectedCount > SelectionRenderPolicy.FULL_LIMIT;
        boolean primarySelection = selectedOwner && (selection == null || selection.primary() == unit);
        boolean exactSelectedDetail = selectedOwner
                && (selection == null || selectedCount <= SelectionRenderPolicy.FULL_LIMIT || primarySelection);
        boolean forceCheapHull = selectedOwner && selection != null
                && selectedCount > SelectionRenderPolicy.COMPACT_LIMIT && !primarySelection;
        boolean compactMarker = selectedOwner && selection != null
                && selectedCount > SelectionRenderPolicy.COMPACT_LIMIT && !primarySelection;

        // Body culling is intentionally independent of huge tactical overlays. A weapon
        // range intersecting the viewport must not force an off-screen ship hull, text,
        // bars and cargo panel through the renderer.
        boolean bodyVisible = RenderCulling.visible(g2, unit.x, unit.y, 96);

        double weaponRange = 0;
        boolean weaponRangeVisible = false;
        if (exactSelectedDetail) {
            weaponRange = displayedWeaponRange(world, unit);
            weaponRangeVisible = weaponRange > 0
                    && RenderCulling.visible(g2, unit.x, unit.y, weaponRange + 8);
        }

        boolean scoutRequested = owner && unit.type().scoutRange > 0 && shouldDrawScoutCircle(unit)
                && (!aggregateSelection || exactSelectedDetail);
        double scoutRange = 0;
        boolean scoutVisible = false;
        if (scoutRequested) {
            scoutRange = world == null ? unit.type().scoutRange : VisibilityRules.unitSensorRange(world, unit);
            scoutVisible = RenderCulling.visible(g2, unit.x, unit.y, scoutRange + 4);
        }

        boolean tractorRequested = owner && shouldDrawTractorCircle(unit)
                && (!aggregateSelection || exactSelectedDetail);
        double tractorRange = tractorRequested ? unit.type().tractorRange : 0;
        boolean tractorVisible = tractorRequested
                && RenderCulling.visible(g2, unit.x, unit.y, tractorRange + 4);
        boolean fillWeaponRange = selectedCount <= 1;

        if (!bodyVisible) {
            drawVisibleOverlays(g2, world, unit, playerColor, weaponRange, weaponRangeVisible,
                    fillWeaponRange, scoutRange, scoutVisible, tractorRange, tractorVisible);
            return;
        }

        boolean damaged = unit.hp < unit.type().maxHp * 0.995;

        // Selection no longer disables the same hull LOD used by unselected fleets.
        // At far zoom mass-selection secondaries stop after a far marker + one selection
        // rectangle instead of falling through into text/bar/range work.
        if (scale < 0.24) {
            drawFarMarker(g2, unit, playerColor, scale);
            if (!selectedOwner) return;
            if (!exactSelectedDetail) {
                drawSelectionMarker(g2, unit, compactMarker);
                if (damaged) drawDamageMarker(g2, unit);
                return;
            }
        } else if (forceCheapHull || scale < 0.78) {
            drawCachedHull(g2, unit, playerColor);
        } else {
            drawDetailedHull(g2, unit, playerColor);
        }

        boolean fleetSecondary = aggregateSelection && !primarySelection;
        if (fleetSecondary) {
            if (damaged) drawDamageMarker(g2, unit);
        } else if (exactSelectedDetail || damaged || scale >= 0.52) {
            drawBars(g2, unit);
        }
        if (exactSelectedDetail || (!fleetSecondary && scale >= 0.62)) drawName(g2, unit, playerColor);
        if (!unit.basePackageType.isBlank() && (exactSelectedDetail || (!fleetSecondary && scale >= 0.62))) {
            g2.setColor(PACKAGE_COLOR);
            g2.drawString("PKG", (int)unit.x - 12, (int)unit.y + 45);
        }

        if (selectedOwner) {
            drawSelectionMarker(g2, unit, compactMarker);
            if (exactSelectedDetail) drawCargo(g2, unit);
        }

        drawVisibleOverlays(g2, world, unit, playerColor, weaponRange, weaponRangeVisible,
                fillWeaponRange, scoutRange, scoutVisible, tractorRange, tractorVisible);
    }

    private static void drawVisibleOverlays(Graphics2D g2, World world, Unit unit, Color playerColor,
                                            double weaponRange, boolean weaponRangeVisible,
                                            boolean fillWeaponRange,
                                            double scoutRange, boolean scoutVisible,
                                            double tractorRange, boolean tractorVisible) {
        if (weaponRangeVisible) {
            drawWeaponRangeCircle(g2, unit, weaponRange, weaponRangeColor(world, unit), fillWeaponRange);
        }
        if (scoutVisible) drawRangeCircle(g2, unit, playerColor, scoutRange);
        if (tractorVisible) drawRangeCircle(g2, unit, playerColor, tractorRange);
    }

    private static void drawSelectionMarker(Graphics2D g2, Unit unit, boolean compact) {
        if (compact) {
            int x = (int)Math.round(unit.x);
            int y = (int)Math.round(unit.y);
            g2.setColor(SELECTED_COLOR);
            // One primitive per mass-selected ship instead of eight corner-line calls.
            g2.drawRect(x - 24, y - 24, 48, 48);
            return;
        }
        Stroke oldStroke = g2.getStroke();
        g2.setColor(SELECTED_COLOR);
        g2.setStroke(SELECTED_STROKE);
        g2.drawOval((int)unit.x - 26, (int)unit.y - 26, 52, 52);
        g2.setStroke(oldStroke);
    }

    private static void drawDamageMarker(Graphics2D g2, Unit unit) {
        g2.setColor(DAMAGE_COLOR);
        g2.fillRect((int)Math.round(unit.x) - 6, (int)Math.round(unit.y) - 31, 12, 3);
    }

    private static void drawDetailedHull(Graphics2D g2, Unit unit, Color playerColor) {
        Graphics2D s = (Graphics2D)g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        s.translate(unit.x, unit.y);
        s.rotate(unit.heading);
        ShipShape.draw(s, unit.type(), playerColor);
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

    static double displayedWeaponRange(World world, Unit unit) {
        return AttackRangeRules.effectiveWeaponRange(world, unit);
    }

    static void drawRoute(Graphics2D g2, Unit unit, Color ignoredColor) {
        if (g2 == null || unit == null || !PlayerRegistry.isLocal(unit.playerId) || !unit.selected) return;
        World world = PlayerRegistry.activeWorld();
        if (world != null && SelectionRenderPolicy.snapshot(world).selectedCount() > SelectionRenderPolicy.FULL_LIMIT) return;
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
        int diameter = (int)Math.round(range * 2);
        int x = (int)Math.round(unit.x - range);
        int y = (int)Math.round(unit.y - range);
        if (fill) {
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 18));
            g2.fillOval(x, y, diameter, diameter);
        }
        Stroke oldStroke = g2.getStroke();
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 155));
        g2.setStroke(WEAPON_RANGE_STROKE);
        g2.drawOval(x, y, diameter, diameter);
        g2.setStroke(oldStroke);
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
        g2.setColor(CARGO_TEXT);
        g2.drawString(text, x, y);
    }

    private static void drawBars(Graphics2D g2, Unit unit) {
        int barW = 36;
        g2.setColor(BAR_BACKGROUND);
        g2.fillRect((int)unit.x - barW / 2, (int)unit.y - 30, barW, 5);
        g2.setColor(HP_COLOR);
        g2.fillRect((int)unit.x - barW / 2, (int)unit.y - 30,
                (int)(barW * unit.hp / Math.max(1, unit.type().maxHp)), 5);
        if (unit.type().cargoCapacity > 0) {
            g2.setColor(BAR_BACKGROUND);
            g2.fillRect((int)unit.x - barW / 2, (int)unit.y + 27, barW, 4);
            g2.setColor(CARGO_COLOR);
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
