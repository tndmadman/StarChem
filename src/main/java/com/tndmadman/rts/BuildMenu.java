package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

/**
 * Compact native Swing production popup shared by every station type.
 *
 * Production requirements live in hover tooltips so the normal list can stay
 * small. Every row carries a preview icon for the ship, station, material,
 * research topic, category, or queue action it represents.
 */
final class BuildMenu {
    private static final int WIDTH = 460;
    private static final int MIN_HEIGHT = 210;
    private static final int MAX_HEIGHT = 610;
    private static final int ROW_H = 68;
    private static final int COMPACT_ROW_H = 44;
    private static final int ICON_SIZE = 52;
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
    private final JLabel scrollHint = new JLabel("HOVER FOR RESOURCES");
    private final JLabel footer = new JLabel("Hover for requirements  •  wheel or drag to scroll");
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
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        content.addMouseWheelListener(this::forwardWheel);

        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(54, 92, 122)));
        scrollPane.getViewport().setBackground(FIELD);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setWheelScrollingEnabled(true);
        scrollPane.getVerticalScrollBar().setUnitIncrement(30);
        scrollPane.getVerticalScrollBar().setBlockIncrement(180);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(16, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(7, 10, 6, 7));
        titleLabel.setForeground(TEXT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
        scrollHint.setForeground(new Color(145, 220, 255));
        scrollHint.setFont(scrollHint.getFont().deriveFont(Font.BOLD, 9f));

        JButton close = new JButton("CLOSE");
        close.setFocusable(false);
        close.setMargin(new Insets(2, 7, 2, 7));
        close.addActionListener(event -> hide());
        header.add(titleLabel, BorderLayout.CENTER);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        headerRight.setOpaque(false);
        headerRight.add(scrollHint);
        headerRight.add(close);
        header.add(headerRight, BorderLayout.EAST);

        footer.setForeground(MUTED);
        footer.setBackground(PANEL);
        footer.setOpaque(true);
        footer.setHorizontalAlignment(SwingConstants.CENTER);
        footer.setFont(footer.getFont().deriveFont(Font.PLAIN, 9f));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 6, 5, 6));

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
            entries.add(new Entry(
                    "Build " + ship.name,
                    timeDetail("Build", ship.buildTimeSeconds, free),
                    defenseLine(ship),
                    new ShipPreviewIcon(ship),
                    requirementTooltip("Build " + ship.name, ship.buildCost, free,
                            defenseLine(ship), weaponText(weaponBadges(ship))),
                    false,
                    false,
                    false,
                    () -> sendProduction(world, network, base, "ENQUEUE",
                            ProductionJobKind.SHIP.name(), shipId)));
        }

        for (String packageId : def.basePackages) {
            if (!StationPackageResearchRules.unlocked(world, base.playerId, packageId)) continue;
            BaseType station = Rules.base(packageId);
            if (station == null) continue;
            entries.add(new Entry(
                    "Load " + station.name,
                    timeDetail("Build", station.buildTimeSeconds, free),
                    stationDefenseLine(station),
                    new StationPreviewIcon(station),
                    requirementTooltip("Load " + station.name, station.buildCost, free,
                            stationDefenseLine(station), "Carried and placed by a Deployer."),
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
                    researchSummary(world, base, topic, free),
                    topic.unlockLabel(),
                    new ResearchPreviewIcon(topic),
                    requirementTooltip("Research " + topic.name, topic.requiredResources, free,
                            topic.unlockLabel(), "Research time: " + whole(topic.timeSeconds) + "s"),
                    completed || queued,
                    false,
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
            BaseType station = Rules.base(unit.basePackageType);
            if (station != null) {
                entries.add(new Entry(
                        "Place " + station.name,
                        "Package ready for placement",
                        stationDefenseLine(station),
                        new StationPreviewIcon(station),
                        simpleTooltip("Place " + station.name,
                                "Station package is loaded and ready for placement.",
                                stationDefenseLine(station)),
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
            for (CraftableItem item : craftables) addCraftableEntry(world, network, base, item, free);
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
            String detail = unlocked + (unlocked == 1 ? " recipe" : " recipes");
            entries.add(new Entry(
                    "Manufacturing | " + category.label,
                    detail,
                    "Open recipe category",
                    new CategoryPreviewIcon(category),
                    simpleTooltip(category.label,
                            "Open this category, then hover a recipe to see required resources."),
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
                "Return to production categories",
                "",
                new NavigationPreviewIcon("←"),
                simpleTooltip("Back", "Return to the station production menu."),
                false,
                true,
                true,
                () -> showForBase(world, network, base, x, y)));
        for (CraftableItem item : CraftingRules.forStationAndCategory(base.typeId, category)) {
            addCraftableEntry(world, network, base, item, free);
        }
        openAt(x, y);
    }

    void showCraftingCategoryForTest(World world, Base base, CraftingCategory category) {
        resetMenu(760, 440);
        boolean free = world.devFreeBuildFor(base.playerId)
                && PlayerRegistry.isLocal(base.playerId);
        showCraftingCategory(world, null, base, category, free);
    }

    private void addCraftableEntry(World world, PeerNetwork network, Base base,
                                   CraftableItem item, boolean free) {
        if (!item.unlockedFor(world, base.playerId)) return;
        String description = item.description.isBlank() ? "Style: " + item.style : item.description;
        entries.add(new Entry(
                "Manufacture " + item.name,
                (free ? "Free build • " : "") + item.outputLabel()
                        + " • " + whole(item.timeSeconds) + "s",
                description,
                new MaterialPreviewIcon(item.outputMaterial),
                requirementTooltip("Manufacture " + item.name, item.requiredResources, free,
                        "Produces " + item.outputLabel(), description),
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
            String detail = ProductionSystem.detail(base, job) + " • " + action;
            entries.add(new Entry(
                    prefix + " | " + ProductionSystem.displayName(job),
                    detail,
                    queueSecondary(job),
                    iconForJob(job),
                    simpleTooltip(ProductionSystem.displayName(job),
                            ProductionSystem.detail(base, job), action),
                    false,
                    job.kind != ProductionJobKind.SHIP,
                    false,
                    () -> sendProduction(world, network, base, "CANCEL", job.id, "")));

            if (i > 1) {
                entries.add(new Entry(
                        "Move up | " + ProductionSystem.displayName(job),
                        "Move one queue position earlier",
                        "",
                        new NavigationPreviewIcon("↑"),
                        simpleTooltip("Move up", "Move this job one queue position earlier."),
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
                        new NavigationPreviewIcon("↓"),
                        simpleTooltip("Move down", "Move this job one queue position later."),
                        false,
                        true,
                        false,
                        () -> sendProduction(world, network, base, "MOVE", job.id, "1")));
            }
        }
    }

    private String queueSecondary(ProductionJob job) {
        return switch (job.kind) {
            case SHIP -> {
                ShipType ship = Rules.findShip(job.itemId);
                yield ship == null ? "" : defenseLine(ship);
            }
            case STATION_PACKAGE -> {
                BaseType station = Rules.base(job.itemId);
                yield station == null ? "" : stationDefenseLine(station);
            }
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(job.itemId);
                yield item == null ? "" : "Produces " + item.outputLabel();
            }
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(job.itemId);
                yield topic == null ? "" : topic.unlockLabel();
            }
        };
    }

    private Icon iconForJob(ProductionJob job) {
        return switch (job.kind) {
            case SHIP -> {
                ShipType ship = Rules.findShip(job.itemId);
                yield ship == null ? new NavigationPreviewIcon("•") : new ShipPreviewIcon(ship);
            }
            case STATION_PACKAGE -> {
                BaseType station = Rules.base(job.itemId);
                yield station == null ? new NavigationPreviewIcon("•") : new StationPreviewIcon(station);
            }
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(job.itemId);
                yield item == null ? new NavigationPreviewIcon("•")
                        : new MaterialPreviewIcon(item.outputMaterial);
            }
            case RESEARCH -> {
                ResearchTopic topic = ResearchRules.topic(job.itemId);
                yield topic == null ? new NavigationPreviewIcon("•")
                        : new ResearchPreviewIcon(topic);
            }
        };
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
            empty.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 12));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(empty);
        } else {
            for (Entry entry : entries) {
                content.add(createEntryButton(entry));
                content.add(Box.createVerticalStrut(4));
            }
        }
        content.revalidate();
        content.repaint();
    }

    private JButton createEntryButton(Entry entry) {
        JButton button = new JButton(entryHtml(entry));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setVerticalAlignment(SwingConstants.CENTER);
        button.setHorizontalTextPosition(SwingConstants.RIGHT);
        button.setIconTextGap(9);
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
                BorderFactory.createEmptyBorder(3, 7, 3, 7)));
        int height = entry.compact ? COMPACT_ROW_H : ROW_H;
        Dimension size = new Dimension(WIDTH - 32, height);
        button.setPreferredSize(size);
        button.setMinimumSize(new Dimension(220, height));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setEnabled(!entry.disabled);
        button.setIcon(entry.icon);
        button.setToolTipText(entry.tooltip);
        button.addMouseWheelListener(this::forwardWheel);

        button.addActionListener(event -> {
            if (entry.disabled) return;
            entry.action.run();
            if (!entry.keepOpen) hide();
        });
        return button;
    }

    private String entryHtml(Entry entry) {
        StringBuilder html = new StringBuilder("<html><div style='width:310px'>");
        html.append("<b>").append(escape(entry.title)).append("</b>");
        if (!entry.detail.isBlank()) {
            html.append("<br><span style='color:#dce1b9'>")
                    .append(escape(entry.detail)).append("</span>");
        }
        if (!entry.compact && !entry.secondary.isBlank()) {
            html.append("<br><span style='color:#8cd2ff'>")
                    .append(escape(entry.secondary)).append("</span>");
        }
        html.append("</div></html>");
        return html.toString();
    }

    private String requirementTooltip(String heading, List<Cost> costs, boolean free,
                                      String... extraLines) {
        String required = costs == null || costs.isEmpty()
                ? "None"
                : Rules.formatCost(costs);
        String label = free && costs != null && !costs.isEmpty()
                ? "Required resources (waived in free build)"
                : "Required resources";
        List<String> lines = new ArrayList<>();
        lines.add(label + ": " + required);
        if (extraLines != null) {
            for (String line : extraLines) {
                if (line != null && !line.isBlank()) lines.add(line);
            }
        }
        return tooltipHtml(heading, lines);
    }

    private String simpleTooltip(String heading, String... lines) {
        return tooltipHtml(heading, lines == null ? List.of() : Arrays.asList(lines));
    }

    private String tooltipHtml(String heading, List<String> lines) {
        StringBuilder html = new StringBuilder("<html><div style='width:360px'><b>")
                .append(escape(heading)).append("</b>");
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            html.append("<br>").append(escape(line));
        }
        return html.append("</div></html>").toString();
    }

    private Dimension popupSize(Component invoker) {
        int availableWidth = invoker == null ? testViewportWidth : Math.max(1, invoker.getWidth());
        int availableHeight = invoker == null ? testViewportHeight : Math.max(1, invoker.getHeight());
        int width = Math.min(WIDTH, Math.max(260, availableWidth - MARGIN * 2));

        int contentHeight = 0;
        for (Entry entry : entries) contentHeight += (entry.compact ? COMPACT_ROW_H : ROW_H) + 4;
        int desired = 66 + Math.max(62, contentHeight);
        int height = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, desired));
        height = Math.min(height, Math.max(145, availableHeight - MARGIN * 2));
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
        if (!visible || popup.isVisible()) return;
        Dimension size = popupSize(null);
        popup.setPopupSize(size);
        popup.setPreferredSize(size);
        popup.setSize(size);
        popup.doLayout();
        scrollPane.setSize(Math.max(1, size.width - 4), Math.max(1, size.height - 58));
        scrollPane.doLayout();
        Dimension viewSize = content.getPreferredSize();
        viewSize.width = Math.max(1, scrollPane.getViewport().getExtentSize().width);
        scrollPane.getViewport().setViewSize(viewSize);
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

    private String timeDetail(String label, double seconds, boolean free) {
        return (free ? "Free build • " : "") + label + " time " + whole(seconds) + "s";
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
        return fuel == null ? base : base + " | Fuel " + one(fuel.perSecond()) + "/s";
    }

    private String researchSummary(World world, Base base,
                                   ResearchTopic topic, boolean free) {
        if (world.hasResearch(base.playerId, topic.id)) return "Completed";
        ProductionJob job = ProductionSystem.researchJob(world, base.playerId, topic.id);
        if (job != null) return "Queued • " + ProductionSystem.detail(base, job);
        String missing = ProductionSystem.missingResearchPrerequisite(world, base, topic);
        if (!missing.isBlank()) return "Requires " + missing + " first";
        return (free ? "Free research • " : "Research time ")
                + whole(topic.timeSeconds) + "s";
    }

    private List<WeaponBadge> weaponBadges(ShipType ship) {
        Map<String, WeaponBadge> grouped = new LinkedHashMap<>();
        for (WeaponType weapon : WeaponRules.loadout(ship)) {
            String label = weaponLabel(weapon);
            WeaponBadge old = grouped.get(label);
            if (old == null) {
                grouped.put(label, new WeaponBadge(label, 1, weapon.color));
            } else {
                grouped.put(label, new WeaponBadge(label, old.count + 1, old.color));
            }
        }
        return List.copyOf(grouped.values());
    }

    private String weaponText(List<WeaponBadge> badges) {
        if (badges == null || badges.isEmpty()) return "Weapons: none";
        StringJoiner joiner = new StringJoiner("  ");
        for (WeaponBadge badge : badges) {
            joiner.add(badge.count > 1 ? badge.label + " x" + badge.count : badge.label);
        }
        return "Weapons: " + joiner;
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

    List<String> entryDetailsForTest() {
        List<String> details = new ArrayList<>();
        for (Entry entry : entries) details.add(entry.detail);
        return List.copyOf(details);
    }

    String entryDetailForTest(String title) {
        for (Entry entry : entries) if (entry.title.equals(title)) return entry.detail;
        return "";
    }

    String entryTooltipForTest(String title) {
        for (Entry entry : entries) if (entry.title.equals(title)) return entry.tooltip;
        return "";
    }

    boolean entryHasIconForTest(String title) {
        for (Entry entry : entries) if (entry.title.equals(title)) return entry.icon != null;
        return false;
    }

    int normalRowHeightForTest() {
        return ROW_H;
    }

    List<String> visibleEntryTitlesForTest() {
        if (entries.isEmpty()) return List.of();
        int value = scrollPane.getVerticalScrollBar().getValue();
        int extent = Math.max(1, scrollPane.getViewport().getExtentSize().height);
        int top = 0;
        List<String> titles = new ArrayList<>();
        for (Entry entry : entries) {
            int height = (entry.compact ? COMPACT_ROW_H : ROW_H) + 4;
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

    private abstract static class PreviewIcon implements Icon {
        private final Color accent;

        private PreviewIcon(Color accent) {
            this.accent = accent == null ? new Color(120, 220, 255) : accent;
        }

        @Override public int getIconWidth() { return ICON_SIZE; }
        @Override public int getIconHeight() { return ICON_SIZE; }

        @Override public final void paintIcon(Component component, Graphics graphics, int iconX, int iconY) {
            Graphics2D icon = (Graphics2D)graphics.create();
            icon.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            icon.setClip(iconX, iconY, ICON_SIZE, ICON_SIZE);
            icon.setColor(new Color(5, 18, 28));
            icon.fillRoundRect(iconX, iconY, ICON_SIZE, ICON_SIZE, 9, 9);
            icon.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 105));
            icon.drawRoundRect(iconX, iconY, ICON_SIZE - 1, ICON_SIZE - 1, 9, 9);
            paintPreview(icon, iconX, iconY, accent);
            icon.dispose();
        }

        abstract void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent);
    }

    private static final class ShipPreviewIcon extends PreviewIcon {
        private final ShipType ship;

        private ShipPreviewIcon(ShipType ship) {
            super(PlayerRegistry.color(PlayerRegistry.localId()));
            this.ship = ship;
        }

        @Override void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent) {
            Rectangle2D bounds = ShipShape.create(ship).getBounds2D();
            double scale = Math.min(
                    (ICON_SIZE - 10) / Math.max(1.0, bounds.getWidth()),
                    (ICON_SIZE - 10) / Math.max(1.0, bounds.getHeight()));
            icon.translate(iconX + ICON_SIZE / 2.0, iconY + ICON_SIZE / 2.0);
            icon.scale(scale, scale);
            icon.translate(-bounds.getCenterX(), -bounds.getCenterY());
            ShipShape.draw(icon, ship, accent);
        }
    }

    private static final class StationPreviewIcon extends PreviewIcon {
        private final BaseType station;

        private StationPreviewIcon(BaseType station) {
            super(PlayerRegistry.color(PlayerRegistry.localId()));
            this.station = station;
        }

        @Override void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent) {
            try {
                Base preview = new Base("MENU_PREVIEW:" + station.id,
                        PlayerRegistry.localId(), station.id, 0, 0);
                double worldDiameter = Math.max(150.0, station.buildRadius * 2.4);
                double scale = (ICON_SIZE - 8) / worldDiameter;
                icon.translate(iconX + ICON_SIZE / 2.0, iconY + ICON_SIZE / 2.0);
                icon.scale(scale, scale);
                preview.draw(icon, accent, new EnumMap<>(Material.class), true);
            } catch (RuntimeException ignored) {
                drawFallbackStation(icon, iconX, iconY, accent);
            }
        }

        private void drawFallbackStation(Graphics2D icon, int iconX, int iconY, Color accent) {
            double cx = iconX + ICON_SIZE / 2.0;
            double cy = iconY + ICON_SIZE / 2.0;
            Polygon hull = new Polygon();
            for (int index = 0; index < 6; index++) {
                double angle = Math.PI / 6 + index * Math.PI / 3.0;
                hull.addPoint((int)Math.round(cx + Math.cos(angle) * 18),
                        (int)Math.round(cy + Math.sin(angle) * 18));
            }
            icon.setColor(new Color(20, 29, 42));
            icon.fillPolygon(hull);
            icon.setColor(accent);
            icon.setStroke(new BasicStroke(2f));
            icon.drawPolygon(hull);
            icon.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 110));
            icon.fillOval((int)cx - 9, (int)cy - 9, 18, 18);
        }
    }

    private static final class MaterialPreviewIcon extends PreviewIcon {
        private final Material material;

        private MaterialPreviewIcon(Material material) {
            super(material == null ? new Color(155, 185, 205) : material.color);
            this.material = material;
        }

        @Override void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent) {
            int cx = iconX + ICON_SIZE / 2;
            int cy = iconY + ICON_SIZE / 2;
            Path2D hex = new Path2D.Double();
            for (int index = 0; index < 6; index++) {
                double angle = Math.PI / 6 + index * Math.PI / 3.0;
                double px = cx + Math.cos(angle) * 17;
                double py = cy + Math.sin(angle) * 17;
                if (index == 0) hex.moveTo(px, py); else hex.lineTo(px, py);
            }
            hex.closePath();
            icon.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
            icon.fill(hex);
            icon.setColor(accent.brighter());
            icon.setStroke(new BasicStroke(2f));
            icon.draw(hex);
            String text = material == null ? "?" : abbreviation(material.label);
            icon.setFont(icon.getFont().deriveFont(Font.BOLD, text.length() > 2 ? 9f : 11f));
            FontMetrics metrics = icon.getFontMetrics();
            icon.setColor(Color.WHITE);
            icon.drawString(text, cx - metrics.stringWidth(text) / 2, cy + metrics.getAscent() / 2 - 1);
        }
    }

    private static final class ResearchPreviewIcon extends PreviewIcon {
        private final ResearchTopic topic;

        private ResearchPreviewIcon(ResearchTopic topic) {
            super(new Color(95, 225, 255));
            this.topic = topic;
        }

        @Override void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent) {
            int cx = iconX + ICON_SIZE / 2;
            int cy = iconY + ICON_SIZE / 2;
            icon.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            icon.setColor(new Color(210, 250, 255));
            icon.drawLine(cx - 5, cy - 17, cx + 5, cy - 17);
            icon.drawLine(cx - 3, cy - 17, cx - 3, cy - 4);
            icon.drawLine(cx + 3, cy - 17, cx + 3, cy - 4);
            Path2D flask = new Path2D.Double();
            flask.moveTo(cx - 3, cy - 4);
            flask.lineTo(cx - 13, cy + 14);
            flask.quadTo(cx, cy + 21, cx + 13, cy + 14);
            flask.lineTo(cx + 3, cy - 4);
            flask.closePath();
            icon.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 95));
            icon.fill(flask);
            icon.setColor(accent.brighter());
            icon.draw(flask);
            int phase = Math.abs(topic.id.hashCode()) % 8;
            icon.setColor(new Color(255, 225, 105));
            icon.fillOval(cx - 8 + phase / 3, cy + 5, 4, 4);
            icon.fillOval(cx + 3 - phase / 4, cy + 10, 3, 3);
        }
    }

    private static final class CategoryPreviewIcon extends PreviewIcon {
        private final CraftingCategory category;

        private CategoryPreviewIcon(CraftingCategory category) {
            super(new Color(255, 190, 90));
            this.category = category;
        }

        @Override void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent) {
            int cx = iconX + ICON_SIZE / 2;
            int cy = iconY + ICON_SIZE / 2;
            icon.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 75));
            icon.fillOval(cx - 17, cy - 17, 34, 34);
            icon.setColor(accent);
            icon.setStroke(new BasicStroke(4f));
            icon.drawOval(cx - 13, cy - 13, 26, 26);
            for (int index = 0; index < 8; index++) {
                double angle = index * Math.PI / 4.0;
                icon.drawLine((int)(cx + Math.cos(angle) * 13), (int)(cy + Math.sin(angle) * 13),
                        (int)(cx + Math.cos(angle) * 18), (int)(cy + Math.sin(angle) * 18));
            }
            icon.fillOval(cx - 4, cy - 4, 8, 8);
            String initial = category.label.isBlank() ? "" : category.label.substring(0, 1).toUpperCase(Locale.ROOT);
            icon.setFont(icon.getFont().deriveFont(Font.BOLD, 8f));
            icon.setColor(Color.WHITE);
            icon.drawString(initial, cx - 3, cy + 3);
        }
    }

    private static final class NavigationPreviewIcon extends PreviewIcon {
        private final String symbol;

        private NavigationPreviewIcon(String symbol) {
            super(new Color(255, 205, 105));
            this.symbol = symbol;
        }

        @Override void paintPreview(Graphics2D icon, int iconX, int iconY, Color accent) {
            icon.setFont(icon.getFont().deriveFont(Font.BOLD, 25f));
            FontMetrics metrics = icon.getFontMetrics();
            int tx = iconX + (ICON_SIZE - metrics.stringWidth(symbol)) / 2;
            int ty = iconY + (ICON_SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
            icon.setColor(accent);
            icon.drawString(symbol, tx, ty);
        }
    }

    private static String abbreviation(String label) {
        if (label == null || label.isBlank()) return "?";
        String[] parts = label.trim().split("\\s+");
        if (parts.length > 1) {
            StringBuilder result = new StringBuilder();
            for (String part : parts) {
                if (!part.isBlank()) result.append(Character.toUpperCase(part.charAt(0)));
                if (result.length() == 3) break;
            }
            return result.toString();
        }
        String compact = parts[0].replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return compact.substring(0, Math.min(3, compact.length()));
    }

    private record Entry(
            String title,
            String detail,
            String secondary,
            Icon icon,
            String tooltip,
            boolean disabled,
            boolean compact,
            boolean keepOpen,
            Runnable action) { }

    private record WeaponBadge(String label, int count, Color color) { }
}
