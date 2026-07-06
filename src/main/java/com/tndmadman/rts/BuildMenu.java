package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class BuildMenu {
    private static final int WIDTH = 460;
    private static final int ROW_H = 92;
    private static final int COMPACT_ROW_H = 46;
    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 32;
    private static final int MARGIN = 4;
    private static final int ICON_W = 74;
    private final List<Entry> entries = new ArrayList<>();
    private String title = "BUILD MENU";
    private int x, y;
    private int scrollOffset;
    private int visibleRows;
    private int menuHeight;
    boolean visible;

    void showForBase(World world, Base base, int sx, int sy) { showForBase(world, null, base, sx, sy); }
    void showForUnit(World world, Unit unit, int sx, int sy) { showForUnit(world, null, unit, sx, sy); }

    void showForBase(World world, PeerNetwork network, Base base, int sx, int sy) {
        entries.clear();
        scrollOffset = 0;
        visibleRows = 0;
        menuHeight = HEADER_H;
        x = sx; y = sy; visible = true;
        BaseType def = base.type();
        List<ResearchTopic> topics = ResearchRules.forStation(def.id);
        title = topics.isEmpty() ? "BUILD MENU" : "RESEARCH MENU";
        boolean free = world.devFreeBuildFor(base.playerId) && PlayerRegistry.isLocal(base.playerId);
        for (String shipId : def.buildableShips) {
            ShipType ship = Rules.ship(shipId);
            boolean unlocked = ResearchRules.shipUnlocked(world, base.playerId, shipId);
            String detail = free ? "free (dev mode)" : unlocked ? Rules.formatCost(ship.buildCost) : "LOCKED: " + requiredResearch(shipId);
            entries.add(new Entry("Build " + ship.name, detail, defenseLine(ship), ship, weaponBadges(ship), false, false, () -> {
                if (network == null) world.buildShip(base.id, shipId);
                else network.build(base.playerId, base.id, shipId);
            }));
        }
        for (String packageId : def.basePackages) {
            BaseType pkg = Rules.base(packageId);
            entries.add(new Entry("Load " + pkg.name, free ? "free (dev mode)" : Rules.formatCost(pkg.buildCost), stationDefenseLine(pkg), null, List.of(), false, false, () -> {
                if (network == null) world.loadBasePackage(base.id, packageId);
                else network.basePackage(base.playerId, "LOAD", base.id, packageId);
            }));
        }
        for (CraftableItem item : CraftingRules.forStation(def.id)) {
            String detail = free ? "free (dev mode)" : Rules.formatCost(item.requiredResources) + " -> " + item.outputLabel();
            String info = item.description.isBlank() ? "Style: " + item.style : item.description;
            entries.add(new Entry("Manufacture " + item.name, detail, info, null, List.of(), false, false, () -> {
                if (network == null || network.statusLine().startsWith("HOST")) world.craftItem(base.id, item.id);
                else world.status = "Manufacturing commands are host/solo only right now.";
            }));
        }
        for (ResearchTopic topic : topics) {
            boolean completed = world.hasResearch(base.playerId, topic.id);
            entries.add(new Entry("Research " + topic.name, researchDetail(world, base, topic, free), topic.unlockLabel(), null, List.of(), completed, completed, () -> {
                if (network == null || network.statusLine().startsWith("HOST")) world.research(base.id, topic.id);
                else world.status = "Research commands are host/solo only right now.";
            }));
        }
    }

    void showForUnit(World world, PeerNetwork network, Unit unit, int sx, int sy) {
        entries.clear();
        scrollOffset = 0;
        visibleRows = 0;
        menuHeight = HEADER_H;
        title = "PLACE MENU";
        x = sx; y = sy; visible = true;
        if (!unit.basePackageType.isBlank()) {
            BaseType pkg = Rules.base(unit.basePackageType);
            entries.add(new Entry("Place " + pkg.name, "ready", stationDefenseLine(pkg), null, List.of(), false, false, () -> {
                if (network == null) world.placePackage(unit);
                else network.basePackage(unit.playerId, "PLACE", unit.key(), unit.basePackageType);
            }));
        }
    }

    boolean click(int sx, int sy) {
        if (!visible) return false;
        ensureClickLayout();
        if (hasOverflow()) {
            if (upButton().contains(sx, sy)) { page(-1); return true; }
            if (downButton().contains(sx, sy)) { page(1); return true; }
        }
        for (int slot = 0; slot < visibleRows; slot++) {
            int entryIndex = scrollOffset + slot;
            if (entryIndex >= entries.size()) break;
            Rectangle r = row(slot);
            if (r.contains(sx, sy)) {
                Entry entry = entries.get(entryIndex);
                if (!entry.disabled) {
                    entry.action.run();
                    visible = false;
                }
                return true;
            }
        }
        if (menuBounds().contains(sx, sy)) return true;
        visible = false;
        return false;
    }

    void draw(Graphics2D g2) {
        if (!visible || entries.isEmpty()) return;
        Rectangle clip = g2.getClipBounds();
        updateLayout(clip);
        keepOnScreen(clip);
        g2.setColor(new Color(0, 0, 0, 210));
        g2.fillRoundRect(x, y, WIDTH, menuHeight, 14, 14);
        g2.setColor(new Color(90, 190, 245, 190));
        g2.drawRoundRect(x, y, WIDTH, menuHeight, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString(title, x + 14, y + 20);
        for (int slot = 0; slot < visibleRows; slot++) {
            int entryIndex = scrollOffset + slot;
            if (entryIndex >= entries.size()) break;
            drawEntry(g2, row(slot), entries.get(entryIndex));
        }
        if (hasOverflow()) drawFooter(g2);
    }

    private void drawEntry(Graphics2D g2, Rectangle r, Entry e) {
        if (e.disabled) {
            g2.setColor(new Color(46, 53, 59, 190));
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g2.setColor(new Color(120, 130, 138, 120));
            g2.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g2.setColor(new Color(178, 185, 190));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.drawString(fit(g2, e.title, r.width - 22), r.x + 10, r.y + 17);
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            g2.drawString(fit(g2, e.detail, r.width - 22), r.x + 10, r.y + 34);
            return;
        }
        g2.setColor(new Color(18, 54, 82, 220));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2.setColor(new Color(120, 220, 255, 170));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        int textW = r.width - 22 - (e.shipIcon == null ? 0 : ICON_W);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString(fit(g2, e.title, textW), r.x + 10, r.y + 17);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(220, 225, 185));
        g2.drawString(fit(g2, e.detail, textW), r.x + 10, r.y + 35);
        g2.setColor(new Color(140, 210, 255));
        g2.drawString(fit(g2, e.defense, textW), r.x + 10, r.y + 51);
        if (!e.weapons.isEmpty()) drawWeaponBadges(g2, e.weapons, r.x + 10, r.y + 66, textW);
        else if (e.shipIcon != null) {
            g2.setColor(new Color(155, 170, 180));
            g2.drawString("Weapons: none", r.x + 10, r.y + 75);
        }
        if (e.shipIcon != null) drawShipIcon(g2, r, e.shipIcon);
    }

    private void drawWeaponBadges(Graphics2D g2, List<WeaponBadge> badges, int sx, int sy, int maxW) {
        Graphics2D b = (Graphics2D) g2.create();
        b.setFont(b.getFont().deriveFont(Font.BOLD, 10f));
        int x = sx;
        int y = sy;
        for (WeaponBadge badge : badges) {
            String label = badge.count > 1 ? badge.label + " x" + badge.count : badge.label;
            int w = b.getFontMetrics().stringWidth(label) + 14;
            if (x > sx && x + w > sx + maxW) { x = sx; y += 18; }
            if (y > sy + 18) break;
            Color c = badge.color;
            b.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 58));
            b.fillRoundRect(x, y, w, 15, 8, 8);
            b.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 190));
            b.drawRoundRect(x, y, w, 15, 8, 8);
            b.setColor(Color.WHITE);
            b.drawString(label, x + 7, y + 11);
            x += w + 5;
        }
        b.dispose();
    }

    private List<WeaponBadge> weaponBadges(ShipType ship) {
        Map<String, WeaponBadge> grouped = new LinkedHashMap<>();
        for (WeaponType weapon : WeaponRules.loadout(ship)) {
            String label = weaponLabel(weapon);
            WeaponBadge old = grouped.get(label);
            if (old == null) grouped.put(label, new WeaponBadge(label, 1, weapon.color));
            else grouped.put(label, new WeaponBadge(label, old.count + 1, old.color));
        }
        return List.copyOf(grouped.values());
    }

    private String defenseLine(ShipType ship) {
        return "HP " + whole(ship.maxHp) + " | SHD " + whole(ship.maxShield) + " | REG " + one(ship.shieldRegen) + "/s";
    }

    private String stationDefenseLine(BaseType station) {
        StationFuelRequirement fuel = StationFuelRules.requirement(station.id);
        String base = "HP " + whole(station.maxHp) + " | SHD " + whole(station.maxShield) + " | REG " + one(station.shieldRegen) + "/s";
        return fuel == null ? base : base + " | Fuel " + one(fuel.perSecond()) + "/s";
    }

    private String requiredResearch(String shipId) {
        ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(shipId);
        return topic == null ? "Research" : topic.name;
    }

    private String researchDetail(World world, Base base, ResearchTopic topic, boolean free) {
        if (world.hasResearch(base.playerId, topic.id)) return "completed";
        ResearchJob job = ResearchSystem.job(world, base.playerId, topic.id);
        if (job != null) return "researching | " + ResearchSystem.timeLabel(job);
        String missing = ResearchRules.missingPrerequisite(world, base.playerId, topic);
        if (!missing.isBlank()) return "requires " + missing;
        return (free ? "free" : Rules.formatCost(topic.requiredResources)) + " | " + whole(topic.timeSeconds) + "s";
    }

    private String whole(double value) { return String.valueOf((int)Math.round(value)); }
    private String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    private String weaponLabel(WeaponType weapon) {
        String id = weapon.id.toLowerCase(Locale.ROOT);
        if (weapon.screenWeapon) return "PD";
        if (id.contains("capital_torpedo")) return "CAP TORP";
        if (id.contains("torpedo")) return "TORP";
        if (weapon.movingShot || id.contains("missile")) return "MSL";
        if (id.contains("siege")) return "SIEGE";
        if (id.contains("lance")) return "LANCE";
        if (id.contains("fighter")) return "FTR";
        if (id.contains("cannon")) return "CANNON";
        if (id.contains("rail")) return "RAIL";
        if (weapon.beam) return "BEAM";
        return "GUN";
    }

    private void drawShipIcon(Graphics2D g2, Rectangle row, ShipType ship) {
        Rectangle box = new Rectangle(row.x + row.width - ICON_W + 8, row.y + 8, ICON_W - 18, row.height - 16);
        g2.setColor(new Color(5, 18, 28, 180));
        g2.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10);
        g2.setColor(new Color(120, 220, 255, 95));
        g2.drawRoundRect(box.x, box.y, box.width, box.height, 10, 10);

        Rectangle2D bounds = ShipShape.create(ship).getBounds2D();
        double scale = Math.min((box.width - 10) / Math.max(1.0, bounds.getWidth()), (box.height - 10) / Math.max(1.0, bounds.getHeight()));
        Graphics2D icon = (Graphics2D) g2.create();
        icon.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        icon.translate(box.getCenterX(), box.getCenterY());
        icon.scale(scale, scale);
        icon.translate(-bounds.getCenterX(), -bounds.getCenterY());
        ShipShape.draw(icon, ship, PlayerRegistry.color(PlayerRegistry.localId()));
        icon.dispose();
    }

    private void drawFooter(Graphics2D g2) {
        Rectangle up = upButton();
        Rectangle down = downButton();
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        drawButton(g2, up, scrollOffset == 0 ? "Top" : "▲ Up");
        int end = Math.min(entries.size(), scrollOffset + visibleRows);
        String label = end + " / " + entries.size();
        g2.setColor(new Color(220, 225, 185));
        g2.drawString(label, x + WIDTH / 2 - g2.getFontMetrics().stringWidth(label) / 2, up.y + 20);
        drawButton(g2, down, end >= entries.size() ? "Bottom" : "Down ▼");
    }

    private void drawButton(Graphics2D g2, Rectangle r, String text) {
        g2.setColor(new Color(18, 54, 82, 230));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g2.setColor(new Color(120, 220, 255, 160));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g2.setColor(Color.WHITE);
        int tx = r.x + r.width / 2 - g2.getFontMetrics().stringWidth(text) / 2;
        g2.drawString(text, tx, r.y + 20);
    }

    private void updateLayout(Rectangle clip) {
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - 1)));
        int available = clip == null ? ROW_H * Math.max(1, entries.size()) : Math.max(ROW_H, clip.height - MARGIN * 2 - HEADER_H);
        int used = 0;
        visibleRows = 0;
        for (int i = scrollOffset; i < entries.size(); i++) {
            int h = rowHeight(entries.get(i));
            int footer = i + 1 < entries.size() ? FOOTER_H : 0;
            if (visibleRows > 0 && used + h + footer > available) break;
            used += h;
            visibleRows++;
        }
        if (visibleRows <= 0 && !entries.isEmpty()) visibleRows = 1;
        menuHeight = HEADER_H + visibleHeight() + (hasOverflow() ? FOOTER_H : 0);
    }

    private void ensureClickLayout() { if (visibleRows <= 0) updateLayout(null); }

    private void page(int direction) {
        int step = Math.max(1, visibleRows - 1);
        scrollOffset = clampScroll(scrollOffset + direction * step);
    }

    private int clampScroll(int offset) {
        int max = Math.max(0, entries.size() - Math.max(1, visibleRows));
        return Math.max(0, Math.min(offset, max));
    }

    private boolean hasOverflow() { return entries.size() > scrollOffset + visibleRows; }

    private Rectangle menuBounds() { return new Rectangle(x, y, WIDTH, menuHeight); }

    private Rectangle upButton() {
        int fy = y + HEADER_H + visibleHeight();
        return new Rectangle(x + 10, fy + 4, 90, FOOTER_H - 8);
    }

    private Rectangle downButton() {
        int fy = y + HEADER_H + visibleHeight();
        return new Rectangle(x + WIDTH - 100, fy + 4, 90, FOOTER_H - 8);
    }

    private String fit(Graphics2D g2, String text, int maxW) {
        if (g2.getFontMetrics().stringWidth(text) <= maxW) return text;
        String s = text;
        while (s.length() > 3 && g2.getFontMetrics().stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private void keepOnScreen(Rectangle clip) {
        if (clip == null) return;
        x = (int)Calc.clamp(x, MARGIN, Math.max(MARGIN, clip.width - WIDTH - MARGIN));
        y = (int)Calc.clamp(y, MARGIN, Math.max(MARGIN, clip.height - menuHeight - MARGIN));
    }

    private Rectangle row(int slot) {
        int yy = y + HEADER_H;
        for (int i = 0; i < slot; i++) yy += rowHeight(entries.get(scrollOffset + i));
        Entry e = entries.get(scrollOffset + slot);
        return new Rectangle(x + 10, yy, WIDTH - 20, rowHeight(e) - 8);
    }

    private int visibleHeight() {
        int h = 0;
        for (int i = 0; i < visibleRows && scrollOffset + i < entries.size(); i++) h += rowHeight(entries.get(scrollOffset + i));
        return h;
    }

    private int rowHeight(Entry e) { return e.compact ? COMPACT_ROW_H : ROW_H; }

    private record Entry(String title, String detail, String defense, ShipType shipIcon, List<WeaponBadge> weapons, boolean disabled, boolean compact, Runnable action) { }
    private record WeaponBadge(String label, int count, Color color) { }
}
