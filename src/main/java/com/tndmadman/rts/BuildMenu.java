package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

/**
 * Native Swing production popup shared by every station type.
 *
 * The old implementation painted a fake menu and fake scrollbar into GamePanel.
 * This version owns a real JScrollPane, so Swing delivers wheel and scrollbar
 * input directly to the menu instead of relying on camera-event forwarding.
 */
final class BuildMenu {
    private static final int WIDTH = 500;
    private static final int MIN_HEIGHT = 240;
    private static final int MAX_HEIGHT = 650;
    private static final int ROW_H = 96;
    private static final int COMPACT_ROW_H = 58;
    private static final int MARGIN = 4;
    private static final double PRECISE_SCROLL_THRESHOLD = 0.20;

    private static final Color PANEL = new Color(5, 13, 22);
    private static final Color FIELD = new Color(9, 25, 38);
    private static final Color BORDER = new Color(90, 190, 245);
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(185, 215, 232);

    private final List<Entry> entries = new ArrayList<>();
    private final JPanel content = new JPanel();
    private final JScrollPane scrollPane = new JScrollPane(content);
    private final JLabel titleLabel = new JLabel("BUILD MENU");
    private final JLabel scrollHint = new JLabel("MOUSE WHEEL / DRAG SCROLLBAR");
    private final JLabel footer = new JLabel("Mouse wheel  •  drag scrollbar  •  Page Up / Page Down");
    private final JPopupMenu popup = new JPopupMenu();

    private String title = "BUILD MENU";
    private int x;
    private int y;
    private int testViewportWidth = 800;
    private int testViewportHeight = 480;
    private double preciseWheelRemainder;
    boolean visible;

    BuildMenu() {
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(FIELD);
        content.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        content.addMouseWheelListener(this::forwardWheel);

        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(54, 92, 122)));
        scrollPane.getViewport().setBackground(FIELD);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setWheelScrollingEnabled(true);
        scrollPane.getVerticalScrollBar().setUnitIncrement(34);
        scrollPane.getVerticalScrollBar().setBlockIncrement(190);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(17, 0));

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setBackground(PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(9, 12, 8, 8));
        titleLabel.setForeground(TEXT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        scrollHint.setForeground(new Color(145, 220, 255));
        scrollHint.setFont(scrollHint.getFont().deriveFont(Font.BOLD, 10f));

        JButton close = new JButton("CLOSE");
        close.setFocusable(false);
        close.addActionListener(event -> hide());
        header.add(titleLabel, BorderLayout.CENTER);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setOpaque(false);
        headerRight.add(scrollHint);
        headerRight.add(close);
        header.add(headerRight, BorderLayout.EAST);

        footer.setForeground(MUTED);
        footer.setBackground(PANEL);
        footer.setOpaque(true);
        footer.setHorizontalAlignment(SwingConstants.CENTER);
        footer.setFont(footer.getFont().deriveFont(Font.PLAIN, 10f));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 8, 7, 8));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(PANEL);
        Border outer = BorderFactory.createLineBorder(BORDER, 2);
        card.setBorder(outer);
        card.add(header, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);
        card.add(footer, BorderLayout.SOUTH);

        MouseWheelListenerBridge wheelBridge = new MouseWheelListenerBridge();
        header.addMouseWheelListener(wheelBridge);
        titleLabel.addMouseWheelListener(wheelBridge);
        scrollHint.addMouseWheelListener(wheelBridge);
        footer.addMouseWheelListener(wheelBridge);
        close.addMouseWheelListener(wheelBridge);
        card.addMouseWheelListener(wheelBridge);

        popup.setBorder(BorderFactory.createEmptyBorder());
        popup.setLayout(new BorderLayout());
        popup.add(card, BorderLayout.CENTER);
        popup.setFocusable(true);

        popup.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close-production-menu");
        popup.getActionMap().put("close-production-menu", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                hide();
            }
        });

        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                visible = true;
            }

            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
                visible = false;
                preciseWheelRemainder = 0;
            }

            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
                visible = false;
                preciseWheelRemainder = 0;
            }
        });
    }

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
            if (ship == null) continue;
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
            if (pkg == null) continue;
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

        openAt(sx, sy);
    }

    void showForUnit(World world, PeerNetwork network, Unit unit, int sx, int sy) {
        resetMenu(sx, sy);
        title = "PLACE MENU";
        if (!unit.basePackageType.isBlank()) {
            BaseType pkg = Rules.base(unit.basePackageType);
            if (pkg != null) {
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
        openAt(sx, sy);
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
        openAt(x, y);
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

    private void openAt(int requestedX, int requestedY) {
        if (popup.isVisible()) popup.setVisible(false);
        rebuildContent();
        titleLabel.setText(title);
        preciseWheelRemainder = 0;
        scrollPane.getVerticalScrollBar().setValue(0);

        Component invoker = findGamePanelInvoker();
        Dimension popupSize = popupSize(invoker);
        popup.setPopupSize(popupSize);
        popup.setPreferredSize(popupSize);

        int availableWidth = invoker == null ? testViewportWidth : Math.max(1, invoker.getWidth());
        int availableHeight = invoker == null ? testViewportHeight : Math.max(1, invoker.getHeight());
        x = clamp(requestedX, MARGIN, Math.max(MARGIN, availableWidth - popupSize.width - MARGIN));
        y = clamp(requestedY, MARGIN, Math.max(MARGIN, availableHeight - popupSize.height - MARGIN));
        visible = true;

        if (invoker != null && invoker.isShowing()) {
            popup.show(invoker, x, y);
            SwingUtilities.invokeLater(() -> {
                scrollPane.requestFocusInWindow();
                scrollPane.getVerticalScrollBar().setValue(0);
            });
        }
    }

    private void rebuildContent() {
        content.removeAll();
        if (entries.isEmpty()) {
            JLabel empty = new JLabel("No available production actions.");
            empty.setForeground(MUTED);
            empty.setBorder(BorderFactory.createEmptyBorder(18, 14, 18, 14));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(empty);
        } else {
            for (Entry entry : entries) {
                JButton row = createEntryButton(entry);
                content.add(row);
                content.add(Box.createVerticalStrut(5));
            }
        }
        content.revalidate();
        content.repaint();
    }

    private JButton createEntryButton(Entry entry) {
        JButton button = new JButton(entryHtml(entry));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setHorizontalTextPosition(SwingConstants.LEFT);
        button.setIconTextGap(12);
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setForeground(entry.disabled ? new Color(178, 185, 190) : TEXT);
        button.setBackground(entry.disabled
                ? new Color(46, 53, 59)
                : entry.compact ? new Color(26, 62, 72) : new Color(18, 54, 82));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(entry.disabled
                        ? new Color(95, 105, 112)
                        : entry.compact ? new Color(255, 205, 105) : new Color(120, 220, 255)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        int height = entry.compact ? COMPACT_ROW_H : ROW_H;
        Dimension size = new Dimension(WIDTH - 35, height);
        button.setPreferredSize(size);
        button.setMinimumSize(new Dimension(220, height));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setEnabled(!entry.disabled);
        button.addMouseWheelListener(this::forwardWheel);
        if (entry.shipIcon != null) button.setIcon(new ShipPreviewIcon(entry.shipIcon));

        button.addActionListener(event -> {
            if (entry.disabled) return;
            entry.action.run();
            if (!entry.keepOpen) hide();
        });
        return button;
    }

    private String entryHtml(Entry entry) {
        StringBuilder html = new StringBuilder("<html><div style='width:360px'>");
        html.append("<b>").append(escape(entry.title)).append("</b>");
        if (!entry.detail.isBlank()) {
            html.append("<br><span style='color:#dce1b9'>")
                    .append(escape(entry.detail)).append("</span>");
        }
        if (!entry.defense.isBlank()) {
            html.append("<br><span style='color:#8cd2ff'>")
                    .append(escape(entry.defense)).append("</span>");
        }
        String weapons = weaponText(entry.weapons);
        if (!weapons.isBlank()) {
            html.append("<br><span style='color:#b7dceb'>")
                    .append(escape(weapons)).append("</span>");
        }
        html.append("</div></html>");
        return html.toString();
    }

    private String weaponText(List<WeaponBadge> badges) {
        if (badges == null || badges.isEmpty()) return "";
        StringJoiner joiner = new StringJoiner("  ");
        for (WeaponBadge badge : badges) {
            joiner.add(badge.count > 1
                    ? badge.label + " x" + badge.count
                    : badge.label);
        }
        return "Weapons: " + joiner;
    }

    private Dimension popupSize(Component invoker) {
        int availableWidth = invoker == null ? testViewportWidth : Math.max(1, invoker.getWidth());
        int availableHeight = invoker == null ? testViewportHeight : Math.max(1, invoker.getHeight());
        int width = Math.min(WIDTH, Math.max(260, availableWidth - MARGIN * 2));

        int contentHeight = 0;
        for (Entry entry : entries) contentHeight += (entry.compact ? COMPACT_ROW_H : ROW_H) + 5;
        int desired = 82 + Math.max(70, contentHeight);
        int height = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, desired));
        height = Math.min(height, Math.max(150, availableHeight - MARGIN * 2));
        return new Dimension(width, height);
    }

    private Component findGamePanelInvoker() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof GamePanel) return focus;
        Component ancestor = focus == null ? null
                : SwingUtilities.getAncestorOfClass(GamePanel.class, focus);
        if (ancestor != null) return ancestor;

        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        return findGamePanel(active);
    }

    private Component findGamePanel(Component root) {
        if (root == null) return null;
        if (root instanceof GamePanel) return root;
        if (!(root instanceof Container container)) return null;
        for (Component child : container.getComponents()) {
            Component found = findGamePanel(child);
            if (found != null) return found;
        }
        return null;
    }

    private void hide() {
        popup.setVisible(false);
        visible = false;
        preciseWheelRemainder = 0;
    }

    boolean click(int sx, int sy) {
        if (!visible) return false;
        Rectangle bounds = menuBoundsForTest();
        if (bounds.contains(sx, sy)) return true;
        hide();
        return false;
    }

    boolean scroll(int sx, int sy, int wheelRotation,
                   int viewportWidth, int viewportHeight) {
        return scroll(sx, sy, (double)wheelRotation, viewportWidth, viewportHeight);
    }

    boolean scroll(int sx, int sy, double preciseWheelRotation,
                   int viewportWidth, int viewportHeight) {
        testViewportWidth = Math.max(1, viewportWidth);
        testViewportHeight = Math.max(1, viewportHeight);
        if (!visible || entries.isEmpty()) return false;
        if (!menuBoundsForTest().contains(sx, sy)) {
            preciseWheelRemainder = 0;
            return false;
        }
        if (preciseWheelRotation == 0) return true;

        int units = preciseScrollUnits(preciseWheelRotation);
        if (units != 0) {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            int delta = units * Math.max(1, bar.getUnitIncrement(units));
            bar.setValue(clamp(bar.getValue() + delta, 0, maxScrollValue()));
        }
        return true;
    }

    private int preciseScrollUnits(double rotation) {
        double magnitude = Math.abs(rotation);
        if (magnitude >= 1.0) {
            preciseWheelRemainder = 0;
            return rotation > 0
                    ? Math.min(4, Math.max(1, (int)Math.round(magnitude)))
                    : -Math.min(4, Math.max(1, (int)Math.round(magnitude)));
        }

        preciseWheelRemainder += rotation;
        if (Math.abs(preciseWheelRemainder) < PRECISE_SCROLL_THRESHOLD) return 0;
        int direction = preciseWheelRemainder > 0 ? 1 : -1;
        int steps = Math.min(4, Math.max(1,
                (int)(Math.abs(preciseWheelRemainder) / PRECISE_SCROLL_THRESHOLD)));
        preciseWheelRemainder -= direction * steps * PRECISE_SCROLL_THRESHOLD;
        return direction * steps;
    }

    void draw(Graphics2D graphics) {
        Rectangle clip = graphics.getClipBounds();
        draw(graphics, clip == null ? testViewportWidth : clip.width,
                clip == null ? testViewportHeight : clip.height);
    }

    void draw(Graphics2D graphics, int viewportWidth, int viewportHeight) {
        testViewportWidth = Math.max(1, viewportWidth);
        testViewportHeight = Math.max(1, viewportHeight);
        if (!visible) return;
        Dimension size = popupSize(null);
        popup.setPopupSize(size);
        popup.setPreferredSize(size);
        popup.setSize(size);
        popup.doLayout();
        scrollPane.setSize(Math.max(1, size.width - 4), Math.max(1, size.height - 70));
        Dimension viewSize = content.getPreferredSize();
        viewSize.width = Math.max(1, scrollPane.getViewport().getExtentSize().width);
        scrollPane.getViewport().setViewSize(viewSize);
        scrollPane.doLayout();
        content.setSize(viewSize);
        content.doLayout();
    }

    private void forwardWheel(MouseWheelEvent event) {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        int direction = event.getWheelRotation() >= 0 ? 1 : -1;
        int units = event.getUnitsToScroll();
        if (units == 0) units = direction;
        int delta = units * Math.max(1, bar.getUnitIncrement(direction));
        bar.setValue(clamp(bar.getValue() + delta, 0, maxScrollValue()));
        event.consume();
    }

    private int maxScrollValue() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        return Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
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

    private String whole(double value) {
        return String.valueOf((int)Math.round(value));
    }

    private String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void resetMenu(int screenX, int screenY) {
        hide();
        x = screenX;
        y = screenY;
        visible = true;
        resetMenuState();
    }

    private void resetMenuState() {
        entries.clear();
        preciseWheelRemainder = 0;
        content.removeAll();
        scrollPane.getVerticalScrollBar().setValue(0);
    }

    List<String> entryTitlesForTest() {
        List<String> titles = new ArrayList<>();
        for (Entry entry : entries) titles.add(entry.title);
        return List.copyOf(titles);
    }

    List<String> visibleEntryTitlesForTest() {
        if (entries.isEmpty()) return List.of();
        int value = scrollPane.getVerticalScrollBar().getValue();
        int extent = Math.max(1, scrollPane.getViewport().getExtentSize().height);
        int top = 0;
        List<String> titles = new ArrayList<>();
        for (Entry entry : entries) {
            int height = (entry.compact ? COMPACT_ROW_H : ROW_H) + 5;
            int bottom = top + height;
            if (bottom >= value && top <= value + extent) titles.add(entry.title);
            top = bottom;
        }
        return List.copyOf(titles);
    }

    Rectangle menuBoundsForTest() {
        Dimension size = popupSize(null);
        return new Rectangle(x, y, size.width, size.height);
    }

    int scrollOffsetForTest() {
        return scrollPane.getVerticalScrollBar().getValue();
    }

    int maxScrollOffsetForTest() {
        return maxScrollValue();
    }

    boolean overflowForTest() {
        return maxScrollValue() > 0;
    }

    JScrollPane scrollPaneForTest() {
        return scrollPane;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private final class MouseWheelListenerBridge implements java.awt.event.MouseWheelListener {
        @Override public void mouseWheelMoved(MouseWheelEvent event) {
            forwardWheel(event);
        }
    }

    private final class ShipPreviewIcon implements Icon {
        private final ShipType ship;

        private ShipPreviewIcon(ShipType ship) {
            this.ship = ship;
        }

        @Override public int getIconWidth() {
            return 72;
        }

        @Override public int getIconHeight() {
            return 72;
        }

        @Override public void paintIcon(Component component, Graphics graphics, int iconX, int iconY) {
            Graphics2D icon = (Graphics2D)graphics.create();
            icon.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            icon.setColor(new Color(5, 18, 28));
            icon.fillRoundRect(iconX, iconY, getIconWidth(), getIconHeight(), 10, 10);
            icon.setColor(new Color(120, 220, 255, 95));
            icon.drawRoundRect(iconX, iconY, getIconWidth() - 1, getIconHeight() - 1, 10, 10);

            Rectangle2D bounds = ShipShape.create(ship).getBounds2D();
            double scale = Math.min(
                    (getIconWidth() - 12) / Math.max(1.0, bounds.getWidth()),
                    (getIconHeight() - 12) / Math.max(1.0, bounds.getHeight()));
            icon.translate(iconX + getIconWidth() / 2.0, iconY + getIconHeight() / 2.0);
            icon.scale(scale, scale);
            icon.translate(-bounds.getCenterX(), -bounds.getCenterY());
            ShipShape.draw(icon, ship,
                    PlayerRegistry.color(PlayerRegistry.localId()));
            icon.dispose();
        }
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
