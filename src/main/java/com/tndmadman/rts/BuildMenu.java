package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class BuildMenu {
    private final List<Entry> entries = new ArrayList<>();
    private int x, y, width = 270;
    boolean visible;

    void showForBase(World world, Base base, int sx, int sy) { showForBase(world, null, base, sx, sy); }
    void showForUnit(World world, Unit unit, int sx, int sy) { showForUnit(world, null, unit, sx, sy); }

    void showForBase(World world, PeerNetwork network, Base base, int sx, int sy) {
        entries.clear();
        x = sx; y = sy; visible = true;
        BaseType def = base.type();
        for (String shipId : def.buildableShips) {
            ShipType ship = Rules.ship(shipId);
            entries.add(new Entry("Build " + ship.name, Rules.formatCost(ship.buildCost), () -> {
                if (network == null) world.buildShip(base.id, shipId);
                else network.build(base.playerId, base.id, shipId);
            }));
        }
        for (String packageId : def.basePackages) {
            BaseType pkg = Rules.base(packageId);
            entries.add(new Entry("Load " + pkg.name, Rules.formatCost(pkg.buildCost), () -> {
                if (network == null) world.loadBasePackage(base.id, packageId);
                else network.basePackage(base.playerId, "LOAD", base.id, packageId);
            }));
        }
    }

    void showForUnit(World world, PeerNetwork network, Unit unit, int sx, int sy) {
        entries.clear();
        x = sx; y = sy; visible = true;
        if (!unit.basePackageType.isBlank()) {
            BaseType pkg = Rules.base(unit.basePackageType);
            entries.add(new Entry("Place " + pkg.name, "ready", () -> {
                if (network == null) world.placePackage(unit);
                else network.basePackage(unit.playerId, "PLACE", unit.key(), unit.basePackageType);
            }));
        }
    }

    boolean click(int sx, int sy) {
        if (!visible) return false;
        for (int i = 0; i < entries.size(); i++) {
            Rectangle r = row(i);
            if (r.contains(sx, sy)) {
                entries.get(i).action.run();
                visible = false;
                return true;
            }
        }
        visible = false;
        return false;
    }

    void draw(Graphics2D g2) {
        if (!visible || entries.isEmpty()) return;
        int height = 32 + entries.size() * 42;
        g2.setColor(new Color(0, 0, 0, 210));
        g2.fillRoundRect(x, y, width, height, 14, 14);
        g2.setColor(new Color(90, 190, 245, 190));
        g2.drawRoundRect(x, y, width, height, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString("BUILD MENU", x + 14, y + 20);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        for (int i = 0; i < entries.size(); i++) {
            Rectangle r = row(i);
            g2.setColor(new Color(18, 54, 82, 220));
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g2.setColor(new Color(120, 220, 255, 170));
            g2.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            Entry e = entries.get(i);
            g2.setColor(Color.WHITE);
            g2.drawString(e.title, r.x + 10, r.y + 16);
            g2.setColor(new Color(220, 225, 185));
            g2.drawString(e.detail, r.x + 10, r.y + 32);
        }
    }

    private Rectangle row(int index) { return new Rectangle(x + 10, y + 30 + index * 42, width - 20, 36); }
    private record Entry(String title, String detail, Runnable action) { }
}
