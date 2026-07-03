package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class BuildMenu {
    private static final int WIDTH = 430;
    private static final int ROW_H = 78;
    private final List<Entry> entries = new ArrayList<>();
    private int x, y;
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
        keepOnScreen(g2.getClipBounds());
        int height = 34 + entries.size() * ROW_H;
        g2.setColor(new Color(0, 0, 0, 210));
        g2.fillRoundRect(x, y, WIDTH, height, 14, 14);
        g2.setColor(new Color(90, 190, 245, 190));
        g2.drawRoundRect(x, y, WIDTH, height, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString("BUILD MENU", x + 14, y + 20);
        for (int i = 0; i < entries.size(); i++) drawEntry(g2, row(i), entries.get(i));
    }

    private void drawEntry(Graphics2D g2, Rectangle r, Entry e) {
        g2.setColor(new Color(18, 54, 82, 220));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2.setColor(new Color(120, 220, 255, 170));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString(fit(g2, e.title, r.width - 20), r.x + 10, r.y + 17);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(220, 225, 185));
        int yLine = r.y + 35;
        for (String line : wrap(g2, e.detail, r.width - 20, 3)) {
            g2.drawString(line, r.x + 10, yLine);
            yLine += 15;
        }
    }

    private List<String> wrap(Graphics2D g2, String text, int maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String part : text.split(", ")) {
            String next = line.isBlank() ? part : line + ", " + part;
            if (g2.getFontMetrics().stringWidth(next) <= maxW) line = next;
            else if (line.isBlank()) out.add(fit(g2, part, maxW));
            else { out.add(line); line = part; }
            if (out.size() == maxLines) break;
        }
        if (!line.isBlank() && out.size() < maxLines) out.add(fit(g2, line, maxW));
        return out.isEmpty() ? List.of(fit(g2, text, maxW)) : out;
    }

    private String fit(Graphics2D g2, String text, int maxW) {
        if (g2.getFontMetrics().stringWidth(text) <= maxW) return text;
        String s = text;
        while (s.length() > 3 && g2.getFontMetrics().stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private void keepOnScreen(Rectangle clip) {
        if (clip == null) return;
        int h = 34 + entries.size() * ROW_H;
        x = (int)Calc.clamp(x, 4, Math.max(4, clip.width - WIDTH - 4));
        y = (int)Calc.clamp(y, 4, Math.max(4, clip.height - h - 4));
    }

    private Rectangle row(int index) { return new Rectangle(x + 10, y + 30 + index * ROW_H, WIDTH - 20, ROW_H - 8); }
    private record Entry(String title, String detail, Runnable action) { }
}
