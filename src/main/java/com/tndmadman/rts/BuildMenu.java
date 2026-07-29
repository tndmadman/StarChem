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
    private static final int SCROLLBAR_W = 6;
    private static final double PRECISE_SCROLL_THRESHOLD = 0.20;

    private final List<Entry> entries = new ArrayList<>();
    private String title = "BUILD MENU";
    private int x, y;
    private int scrollOffset;
    private int visibleRows;
    private int menuHeight;
    private int contentViewportHeight;
    private int maxScrollOffset;
    private boolean overflow;
    private double preciseWheelRemainder;
    boolean visible;

    void showForBase(World world, Base base, int sx, int sy) {
        showForBase(world, null, base, sx, sy);
    }

    void showForUnit(World world, Unit unit, int sx, int sy) {
        showForUnit(world, null, unit, sx, sy);
    }

    void showForBase(World world, PeerNetwork network, Base base, int sx, int sy) {
        resetMenu(sx, sy);
        BaseType def = base.type();
        List<ResearchTopic> topics = ResearchRules.forStation(def.id);
        title = def.name.toUpperCase(Locale.ROOT) + " PRODUCTION | "
                + base.productionQueue.size() + " QUEUED";
        boolean free = world.devFreeBuildFor(base.playerId)
                && PlayerRegistry.isLocal(base.playerId);

        addQueueEntries(world, network, base);

        for (String shipId : def.buildableShips) {
            if (!ResearchRules.shipUnlocked(world, base.playerId, shipId)) continue;
            ShipType ship = Rules.ship(shipId);
            String detail = free ? "free (dev mode)" : Rules.formatCost(ship.buildCost);
            detail += " | " + whole(ship.buildTimeSeconds) + "s";
            entries.add(new Entry(
                    "Build " + ship.name,
                    detail,
                    defenseLine(ship),
                    ship,
                    weaponBadges(ship),
                    false,
                    false,
                    false,
                    () -> sendProduction(world, network, base, "ENQUEUE",
                            ProductionJobKind.SHIP.name(), shipId)));
        }

        for (String packageId : def.basePackages) {
            if (!StationPackageResearchRules.unlocked(world, base.playerId, packageId)) continue;
            BaseType pkg = Rules.base(packageId);
            String detail = (free ? "free (dev mode)" : Rules.formatCost(pkg.buildCost))
                    + " | " + whole(pkg.buildTimeSeconds) + "s";
            entries.add(new Entry(
                    "Load " + pkg.name,
                    detail,
                    stationDefenseLine(pkg),
                    null,
                    List.of(),
                    false,
                    false,
                    false,
                    () -> sendProduction(world, network, base, "ENQUEUE",
                            ProductionJobKind.STATION_PACKAGE.name(), packageId)));
        }

        addCraftingEntries(world, network, base, free);

        for (ResearchTopic topic : topics) {
            boolean completed = world.hasResearch(base.playerId, topic.id);
            boolean queued = ProductionSystem.researchQueued(world, base.playerId, topic.id);
            entries.add(new Entry(
                    "Research " + topic.name,
                    researchDetail(world, base, topic, free),
                    topic.unlockLabel(),
                    null,
                    List.of(),
                    completed || queued,
                    completed || queued,
                    false,
                    () -> sendProduction(world, network, base, "ENQUEUE",
                            ProductionJobKind.RESEARCH.name(), topic.id)));
        }
    }

    private void addCraftingEntries(World world, PeerNetwork network, Base base, boolean free) {
        List<CraftableItem> craftables = CraftingRules.forStation(base.typeId);
        if (craftables.size() <= 8) {
            for (CraftableItem item : craftables) {
                addCraftableEntry(world, network, base, item, free);
            }
            return;
        }

        for (CraftingCategory category : CraftingRules.categoriesForStation(base.typeId)) {
            List<CraftableItem> inCategory =
                    CraftingRules.forStationAndCategory(base.typeId, category);
            int unlocked = 0;
            for (CraftableItem item : inCategory) {
                if (item.unlockedFor(world, base.playerId)) unlocked++;
            }
            if (unlocked <= 0) continue;
            String detail = unlocked
                    + (unlocked == 1 ? " recipe available" : " recipes available");
            entries.add(new Entry(
                    "Manufacturing | " + category.label,
                    detail,
                    "Open this recipe category",
                    null,
                    List.of(),
                    false,
                    true,
                    true,
                    () -> showCraftingCategory(world, network, base, category, free)));
        }
    }

    private void showCraftingCategory(World world, PeerNetwork network, Base base,
                                      CraftingCategory category, boolean free) {
        resetMenuState();
        visible = true;
        title = category.label.toUpperCase(Locale.ROOT) + " | "
                + base.productionQueue.size() + " QUEUED";
        entries.add(new Entry(
                "← Back to " + base.type().name + " production",
                "Return to ships, stations, and categories",
                "",
                null,
                List.of(),
                false,
                true,
                true,
                () -> showForBase(world, network, base, x, y)));
        for (CraftableItem item :
                CraftingRules.forStationAndCategory(base.typeId, category)) {
            addCraftableEntry(world, network, base, item, free);
        }
    }

    private void addCraftableEntry(World world, PeerNetwork network, Base base,
                                   CraftableItem item, boolean free) {
        if (!item.unlockedFor(world, base.playerId)) return;
        String detail = free
                ? "free (dev mode)"
                : Rules.formatCost(item.requiredResources) + " -> " + item.outputLabel();
        detail += " | " + whole(item.timeSeconds) + "s";
        String info = item.description.isBlank()
                ? "Style: " + item.style
                : item.description;
        entries.add(new Entry(
                "Manufacture " + item.name,
                detail,
                info,
                null,
                List.of(),
                false,
                false,
                false,
                () -> sendProduction(world, network, base, "ENQUEUE",
                        ProductionJobKind.CRAFTABLE.name(), item.id)));
    }

    private void addQueueEntries(World world, PeerNetwork network, Base base) {
        for (int i = 0; i < base.productionQueue.size(); i++) {
            ProductionJob job = base.productionQueue.get(i);
            String prefix = i == 0 ? "ACTIVE" : "QUEUE " + (i + 1);
            String action = job.resourcesReserved
                    ? "click to cancel and refund"
                    : "click to cancel";
            String detail = ProductionSystem.detail(base, job) + " | " + action;
            ShipType ship = queuedShip(job);
            entries.add(new Entry(
                    prefix + " | " + ProductionSystem.displayName(job),
                    detail,
                    ship == null ? "" : defenseLine(ship),
                    ship,
                    ship == null ? List.of() : weaponBadges(ship),
                    false,
                    ship == null,
                    false,
                    () -> sendProduction(world, network, base, "CANCEL", job.id, "")));

            if (i > 1) {
                entries.add(new Entry(
                        "Move up | " + ProductionSystem.displayName(job),
                        "Move one queue position earlier",
                        "",
                        null,
                        List.of(),
                        false,
                        true,
                        false,
                        () -> sendProduction(world, network, base, "MOVE", job.id, "-1")));
            }
            if (i > 0 && i < base.productionQueue.size() - 1) {
                entries.add(new Entry(
                        "Move down | " + ProductionSystem.displayName(job),
                        "Move one queue position later",
                        "",
                        null,
                        List.of(),
                        false,
                        true,
                        false,
                        () -> sendProduction(world, network, base, "MOVE", job.id, "1")));
            }
        }
    }

    private static ShipType queuedShip(ProductionJob job) {
        return job != null && job.kind == ProductionJobKind.SHIP
                ? Rules.findShip(job.itemId)
                : null;
    }

    private void sendProduction(World world, PeerNetwork network, Base base,
                                String action, String value, String extra) {
        if (network == null) {
            ProductionCommands.apply(world, base.playerId, action, base.id, value, extra);
        } else {
            network.production(base.playerId, action, base.id, value, extra);
        }
    }

    void showForUnit(World world, PeerNetwork network, Unit unit, int sx, int sy) {
        resetMenu(sx, sy);
        title = "PLACE MENU";
        if (!unit.basePackageType.isBlank()) {
            BaseType pkg = Rules.base(unit.basePackageType);
            entries.add(new Entry(
                    "Place " + pkg.name,
                    "ready",
                    stationDefenseLine(pkg),
                    null,
                    List.of(),
                    false,
                    false,
                    false,
                    () -> {
                        if (network == null) world.placePackage(unit);
                        else network.basePackage(unit.playerId, "PLACE",
                                unit.key(), unit.basePackageType);
                    }));
        }
    }

    boolean click(int sx, int sy) {
        if (!visible) return false;
        ensureClickLayout();

        if (hasOverflow()) {
            if (upButton().contains(sx, sy)) {
                page(-1);
                return true;
            }
            if (downButton().contains(sx, sy)) {
                page(1);
                return true;
            }
            if (scrollTrack().contains(sx, sy)) {
                Rectangle thumb = scrollThumb();
                if (sy < thumb.y) page(-1);
                else if (sy >= thumb.y + thumb.height) page(1);
                return true;
            }
        }

        for (int slot = 0; slot < visibleRows; slot++) {
            int entryIndex = scrollOffset + slot;
            if (entryIndex >= entries.size()) break;
            Rectangle row = row(slot);
            if (!row.contains(sx, sy)) continue;
            Entry entry = entries.get(entryIndex);
            if (!entry.disabled) {
                entry.action.run();
                if (!entry.keepOpen) visible = false;
            }
            return true;
        }

        if (menuBounds().contains(sx, sy)) return true;
        visible = false;
        preciseWheelRemainder = 0;
        return false;
    }

    boolean scroll(int sx, int sy, int wheelRotation,
                   int viewportWidth, int viewportHeight) {
        return scroll(sx, sy, (double)wheelRotation, viewportWidth, viewportHeight);
    }

    boolean scroll(int sx, int sy, double preciseWheelRotation,
                   int viewportWidth, int viewportHeight) {
        if (!visible || entries.isEmpty()) return false;

        Rectangle viewport = viewport(viewportWidth, viewportHeight);
        updateLayout(viewport);
        keepOnScreen(viewport);

        if (!menuBounds().contains(sx, sy)) {
            preciseWheelRemainder = 0;
            return false;
        }
        if (!hasOverflow() || preciseWheelRotation == 0) return true;

        int direction;
        int steps;
        double magnitude = Math.abs(preciseWheelRotation);

        if (magnitude >= 1.0) {
            direction = preciseWheelRotation > 0 ? 1 : -1;
            steps = Math.min(4, Math.max(1, (int)Math.round(magnitude)));
            preciseWheelRemainder = 0;
        } else {
            preciseWheelRemainder += preciseWheelRotation;
            if (Math.abs(preciseWheelRemainder) < PRECISE_SCROLL_THRESHOLD) {
                return true;
            }
            direction = preciseWheelRemainder > 0 ? 1 : -1;
            steps = Math.min(4, Math.max(1,
                    (int)(Math.abs(preciseWheelRemainder)
                            / PRECISE_SCROLL_THRESHOLD)));
            preciseWheelRemainder -=
                    direction * steps * PRECISE_SCROLL_THRESHOLD;
        }

        for (int i = 0; i < steps; i++) {
            int previous = scrollOffset;
            scrollOffset = clampScroll(scrollOffset + direction);
            if (scrollOffset == previous) {
                preciseWheelRemainder = 0;
                break;
            }
        }
        updateLayout(viewport);
        return true;
    }

    void draw(Graphics2D g2) {
        Rectangle clip = g2.getClipBounds();
        draw(g2, clip == null ? 0 : clip.width,
                clip == null ? 0 : clip.height);
    }

    void draw(Graphics2D g2, int viewportWidth, int viewportHeight) {
        if (!visible || entries.isEmpty()) return;

        Rectangle viewport = viewport(viewportWidth, viewportHeight);
        updateLayout(viewport);
        keepOnScreen(viewport);

        g2.setColor(new Color(0, 0, 0, 210));
        g2.fillRoundRect(x, y, WIDTH, menuHeight, 14, 14);
        g2.setColor(new Color(90, 190, 245, 190));
        g2.drawRoundRect(x, y, WIDTH, menuHeight, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString(title, x + 14, y + 20);

        if (hasOverflow()) drawScrollHint(g2);

        for (int slot = 0; slot < visibleRows; slot++) {
            int entryIndex = scrollOffset + slot;
            if (entryIndex >= entries.size()) break;
            drawEntry(g2, row(slot), entries.get(entryIndex));
        }

        if (hasOverflow()) {
            drawScrollBar(g2);
            drawFooter(g2);
        }
    }

    private void drawEntry(Graphics2D g2, Rectangle row, Entry entry) {
        if (entry.disabled) {
            g2.setColor(new Color(46, 53, 59, 190));
            g2.fillRoundRect(row.x, row.y, row.width, row.height, 10, 10);
            g2.setColor(new Color(120, 130, 138, 120));
            g2.drawRoundRect(row.x, row.y, row.width, row.height, 10, 10);
            g2.setColor(new Color(178, 185, 190));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.drawString(fit(g2, entry.title, row.width - 22),
                    row.x + 10, row.y + 17);
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            g2.drawString(fit(g2, entry.detail, row.width - 22),
                    row.x + 10, row.y + 34);
            return;
        }

        if (entry.compact) {
            g2.setColor(new Color(26, 62, 72, 225));
            g2.fillRoundRect(row.x, row.y, row.width, row.height, 10, 10);
            g2.setColor(new Color(255, 205, 105, 175));
            g2.drawRoundRect(row.x, row.y, row.width, row.height, 10, 10);
            g2.setColor(Color.WHITE);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.drawString(fit(g2, entry.title, row.width - 22),
                    row.x + 10, row.y + 16);
            g2.setColor(new Color(210, 225, 205));
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            g2.drawString(fit(g2, entry.detail, row.width - 22),
                    row.x + 10, row.y + 31);
            return;
        }

        g2.setColor(new Color(18, 54, 82, 220));
        g2.fillRoundRect(row.x, row.y, row.width, row.height, 10, 10);
        g2.setColor(new Color(120, 220, 255, 170));
        g2.drawRoundRect(row.x, row.y, row.width, row.height, 10, 10);

        int textWidth = row.width - 22 - (entry.shipIcon == null ? 0 : ICON_W);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString(fit(g2, entry.title, textWidth),
                row.x + 10, row.y + 17);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(220, 225, 185));
        g2.drawString(fit(g2, entry.detail, textWidth),
                row.x + 10, row.y + 35);
        g2.setColor(new Color(140, 210, 255));
        g2.drawString(fit(g2, entry.defense, textWidth),
                row.x + 10, row.y + 51);

        if (!entry.weapons.isEmpty()) {
            drawWeaponBadges(g2, entry.weapons, row.x + 10, row.y + 66, textWidth);
        } else if (entry.shipIcon != null) {
            g2.setColor(new Color(155, 170, 180));
            g2.drawString("Weapons: none", row.x + 10, row.y + 75);
        }
        if (entry.shipIcon != null) drawShipIcon(g2, row, entry.shipIcon);
    }

    private void drawWeaponBadges(Graphics2D g2, List<WeaponBadge> badges,
                                  int startX, int startY, int maxWidth) {
        Graphics2D badgesGraphics = (Graphics2D)g2.create();
        badgesGraphics.setFont(
                badgesGraphics.getFont().deriveFont(Font.BOLD, 10f));
        int x = startX;
        int y = startY;

        for (WeaponBadge badge : badges) {
            String label = badge.count > 1
                    ? badge.label + " x" + badge.count
                    : badge.label;
            int width = badgesGraphics.getFontMetrics().stringWidth(label) + 14;
            if (x > startX && x + width > startX + maxWidth) {
                x = startX;
                y += 18;
            }
            if (y > startY + 18) break;
            Color color = badge.color;
            badgesGraphics.setColor(new Color(
                    color.getRed(), color.getGreen(), color.getBlue(), 58));
            badgesGraphics.fillRoundRect(x, y, width, 15, 8, 8);
            badgesGraphics.setColor(new Color(
                    color.getRed(), color.getGreen(), color.getBlue(), 190));
            badgesGraphics.drawRoundRect(x, y, width, 15, 8, 8);
            badgesGraphics.setColor(Color.WHITE);
            badgesGraphics.drawString(label, x + 7, y + 11);
            x += width + 5;
        }
        badgesGraphics.dispose();
    }

    private List<WeaponBadge> weaponBadges(ShipType ship) {
        Map<String, WeaponBadge> grouped = new LinkedHashMap<>();
        for (WeaponType weapon : WeaponRules.loadout(ship)) {
            String label = weaponLabel(weapon);
            WeaponBadge old = grouped.get(label);
            if (old == null) {
                grouped.put(label, new WeaponBadge(label, 1, weapon.color));
            } else {
                grouped.put(label,
                        new WeaponBadge(label, old.count + 1, old.color));
            }
        }
        return List.copyOf(grouped.values());
    }

    private String defenseLine(ShipType ship) {
        return "HP " + whole(ship.maxHp)
                + " | SHD " + whole(ship.maxShield)
                + " | REG " + one(ship.shieldRegen) + "/s";
    }

    private String stationDefenseLine(BaseType station) {
        StationFuelRequirement fuel = StationFuelRules.requirement(station.id);
        String base = "HP " + whole(station.maxHp)
                + " | SHD " + whole(station.maxShield)
                + " | REG " + one(station.shieldRegen) + "/s";
        return fuel == null
                ? base
                : base + " | Fuel " + one(fuel.perSecond()) + "/s";
    }

    private String researchDetail(World world, Base base,
                                  ResearchTopic topic, boolean free) {
        if (world.hasResearch(base.playerId, topic.id)) return "completed";
        ProductionJob job =
                ProductionSystem.researchJob(world, base.playerId, topic.id);
        if (job != null) {
            return "queued | " + ProductionSystem.detail(base, job);
        }
        String missing =
                ProductionSystem.missingResearchPrerequisite(world, base, topic);
        if (!missing.isBlank()) return "requires " + missing;
        return (free ? "free" : Rules.formatCost(topic.requiredResources))
                + " | " + whole(topic.timeSeconds) + "s";
    }

    private String whole(double value) {
        return String.valueOf((int)Math.round(value));
    }

    private String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

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
        Rectangle box = new Rectangle(
                row.x + row.width - ICON_W + 8,
                row.y + 8,
                ICON_W - 18,
                row.height - 16);
        g2.setColor(new Color(5, 18, 28, 180));
        g2.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10);
        g2.setColor(new Color(120, 220, 255, 95));
        g2.drawRoundRect(box.x, box.y, box.width, box.height, 10, 10);

        Rectangle2D bounds = ShipShape.create(ship).getBounds2D();
        double scale = Math.min(
                (box.width - 10) / Math.max(1.0, bounds.getWidth()),
                (box.height - 10) / Math.max(1.0, bounds.getHeight()));
        Graphics2D icon = (Graphics2D)g2.create();
        icon.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        icon.translate(box.getCenterX(), box.getCenterY());
        icon.scale(scale, scale);
        icon.translate(-bounds.getCenterX(), -bounds.getCenterY());
        ShipShape.draw(icon, ship,
                PlayerRegistry.color(PlayerRegistry.localId()));
        icon.dispose();
    }

    private void drawScrollHint(Graphics2D g2) {
        String hint = "SCROLL  ↕";
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
        g2.setColor(new Color(160, 225, 255));
        int textWidth = g2.getFontMetrics().stringWidth(hint);
        g2.drawString(hint, x + WIDTH - textWidth - 15, y + 20);
    }

    private void drawScrollBar(Graphics2D g2) {
        Rectangle track = scrollTrack();
        Rectangle thumb = scrollThumb();
        g2.setColor(new Color(30, 70, 90, 210));
        g2.fillRoundRect(track.x, track.y, track.width, track.height,
                SCROLLBAR_W, SCROLLBAR_W);
        g2.setColor(new Color(130, 225, 255, 230));
        g2.fillRoundRect(thumb.x, thumb.y, thumb.width, thumb.height,
                SCROLLBAR_W, SCROLLBAR_W);
    }

    private void drawFooter(Graphics2D g2) {
        Rectangle up = upButton();
        Rectangle down = downButton();
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        drawButton(g2, up, scrollOffset == 0 ? "Top" : "▲ Up");
        int end = Math.min(entries.size(), scrollOffset + visibleRows);
        String label = "Mouse wheel  " + (scrollOffset + 1)
                + "-" + end + " / " + entries.size();
        g2.setColor(new Color(220, 225, 185));
        g2.drawString(label,
                x + WIDTH / 2
                        - g2.getFontMetrics().stringWidth(label) / 2,
                up.y + 20);
        drawButton(g2, down,
                scrollOffset >= maxScrollOffset ? "Bottom" : "Down ▼");
    }

    private void drawButton(Graphics2D g2, Rectangle rectangle, String text) {
        g2.setColor(new Color(18, 54, 82, 230));
        g2.fillRoundRect(rectangle.x, rectangle.y,
                rectangle.width, rectangle.height, 8, 8);
        g2.setColor(new Color(120, 220, 255, 160));
        g2.drawRoundRect(rectangle.x, rectangle.y,
                rectangle.width, rectangle.height, 8, 8);
        g2.setColor(Color.WHITE);
        int textX = rectangle.x + rectangle.width / 2
                - g2.getFontMetrics().stringWidth(text) / 2;
        g2.drawString(text, textX, rectangle.y + 20);
    }

    private Rectangle viewport(int width, int height) {
        return new Rectangle(
                0, 0, Math.max(0, width), Math.max(0, height));
    }

    private void updateLayout(Rectangle viewport) {
        int totalHeight = totalEntryHeight();
        int bodyBudget = viewport == null
                ? totalHeight
                : Math.max(ROW_H,
                        viewport.height - MARGIN * 2 - HEADER_H);

        overflow = totalHeight > bodyBudget;
        contentViewportHeight = overflow
                ? Math.max(ROW_H, bodyBudget - FOOTER_H)
                : totalHeight;

        maxScrollOffset = overflow
                ? calculateBottomOffset(contentViewportHeight)
                : 0;
        scrollOffset = clampScroll(scrollOffset);

        int used = 0;
        visibleRows = 0;
        for (int index = scrollOffset; index < entries.size(); index++) {
            int height = rowHeight(entries.get(index));
            if (visibleRows > 0
                    && used + height > contentViewportHeight) {
                break;
            }
            used += height;
            visibleRows++;
        }
        if (visibleRows <= 0 && !entries.isEmpty()) visibleRows = 1;

        menuHeight = HEADER_H
                + (overflow ? contentViewportHeight : visibleHeight())
                + (overflow ? FOOTER_H : 0);
    }

    private int calculateBottomOffset(int availableHeight) {
        if (entries.isEmpty()) return 0;
        int used = 0;
        int start = entries.size() - 1;
        for (int index = entries.size() - 1; index >= 0; index--) {
            int height = rowHeight(entries.get(index));
            if (index < entries.size() - 1
                    && used + height > availableHeight) {
                break;
            }
            used += height;
            start = index;
        }
        return Math.max(0, start);
    }

    private void ensureClickLayout() {
        if (visibleRows <= 0) updateLayout(null);
    }

    private void page(int direction) {
        int step = Math.max(1, visibleRows - 1);
        scrollOffset = clampScroll(scrollOffset + direction * step);
        preciseWheelRemainder = 0;
    }

    private int clampScroll(int offset) {
        return Math.max(0, Math.min(offset, maxScrollOffset));
    }

    private boolean hasOverflow() {
        return overflow;
    }

    private Rectangle menuBounds() {
        return new Rectangle(x, y, WIDTH, menuHeight);
    }

    private Rectangle upButton() {
        int footerY = y + HEADER_H + contentViewportHeight;
        return new Rectangle(x + 10, footerY + 4,
                90, FOOTER_H - 8);
    }

    private Rectangle downButton() {
        int footerY = y + HEADER_H + contentViewportHeight;
        return new Rectangle(x + WIDTH - 100, footerY + 4,
                90, FOOTER_H - 8);
    }

    private Rectangle scrollTrack() {
        int height = Math.max(24, contentViewportHeight - 8);
        return new Rectangle(
                x + WIDTH - SCROLLBAR_W - 3,
                y + HEADER_H + 4,
                SCROLLBAR_W,
                height);
    }

    private Rectangle scrollThumb() {
        Rectangle track = scrollTrack();
        int totalHeight = Math.max(1, totalEntryHeight());
        int thumbHeight = Math.max(
                28,
                (int)Math.round(
                        track.height * Math.min(
                                1.0,
                                contentViewportHeight / (double)totalHeight)));
        thumbHeight = Math.min(track.height, thumbHeight);
        int travel = Math.max(0, track.height - thumbHeight);
        int thumbY = track.y;
        if (maxScrollOffset > 0) {
            thumbY += (int)Math.round(
                    travel * (scrollOffset / (double)maxScrollOffset));
        }
        return new Rectangle(track.x, thumbY, track.width, thumbHeight);
    }

    private String fit(Graphics2D g2, String text, int maxWidth) {
        if (g2.getFontMetrics().stringWidth(text) <= maxWidth) return text;
        String shortened = text;
        while (shortened.length() > 3
                && g2.getFontMetrics().stringWidth(shortened + "...") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "...";
    }

    private void keepOnScreen(Rectangle viewport) {
        if (viewport == null) return;
        x = (int)Calc.clamp(
                x,
                MARGIN,
                Math.max(MARGIN, viewport.width - WIDTH - MARGIN));
        y = (int)Calc.clamp(
                y,
                MARGIN,
                Math.max(MARGIN, viewport.height - menuHeight - MARGIN));
    }

    private Rectangle row(int slot) {
        int rowY = y + HEADER_H;
        for (int index = 0; index < slot; index++) {
            rowY += rowHeight(entries.get(scrollOffset + index));
        }
        Entry entry = entries.get(scrollOffset + slot);
        return new Rectangle(
                x + 10,
                rowY,
                WIDTH - 20,
                rowHeight(entry) - 8);
    }

    private int visibleHeight() {
        int height = 0;
        for (int index = 0;
             index < visibleRows && scrollOffset + index < entries.size();
             index++) {
            height += rowHeight(entries.get(scrollOffset + index));
        }
        return height;
    }

    private int totalEntryHeight() {
        int height = 0;
        for (Entry entry : entries) height += rowHeight(entry);
        return height;
    }

    private int rowHeight(Entry entry) {
        return entry.compact ? COMPACT_ROW_H : ROW_H;
    }

    private void resetMenu(int screenX, int screenY) {
        x = screenX;
        y = screenY;
        visible = true;
        resetMenuState();
    }

    private void resetMenuState() {
        entries.clear();
        scrollOffset = 0;
        visibleRows = 0;
        menuHeight = HEADER_H;
        contentViewportHeight = 0;
        maxScrollOffset = 0;
        overflow = false;
        preciseWheelRemainder = 0;
    }

    List<String> entryTitlesForTest() {
        List<String> titles = new ArrayList<>();
        for (Entry entry : entries) titles.add(entry.title);
        return List.copyOf(titles);
    }

    List<String> visibleEntryTitlesForTest() {
        List<String> titles = new ArrayList<>();
        for (int index = 0;
             index < visibleRows && scrollOffset + index < entries.size();
             index++) {
            titles.add(entries.get(scrollOffset + index).title);
        }
        return List.copyOf(titles);
    }

    Rectangle menuBoundsForTest() {
        return new Rectangle(menuBounds());
    }

    int scrollOffsetForTest() {
        return scrollOffset;
    }

    int maxScrollOffsetForTest() {
        return maxScrollOffset;
    }

    boolean overflowForTest() {
        return overflow;
    }

    private record Entry(
            String title,
            String detail,
            String defense,
            ShipType shipIcon,
            List<WeaponBadge> weapons,
            boolean disabled,
            boolean compact,
            boolean keepOpen,
            Runnable action) { }

    private record WeaponBadge(String label, int count, Color color) { }
}
