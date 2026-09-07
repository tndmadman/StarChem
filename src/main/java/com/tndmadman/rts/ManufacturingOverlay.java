package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Centralized player-facing production console.
 *
 * The simulation remains station-queue based and host authoritative. This overlay only changes the
 * player's abstraction: choose what to build first, then let AUTO select a compatible owned station
 * or explicitly pin the request to one. All mutations still travel through ProductionCommands / PROD.
 */
final class ManufacturingOverlay extends JPanel {
    private static final Map<World, WeakReference<ManufacturingOverlay>> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Color BACKDROP = new Color(5, 6, 8, 224);
    private static final Color SURFACE = new Color(22, 24, 27);
    private static final Color SURFACE_2 = new Color(29, 32, 35);
    private static final Color SURFACE_3 = new Color(38, 41, 44);
    private static final Color FIELD = new Color(16, 18, 20);
    private static final Color BORDER = new Color(83, 79, 69);
    private static final Color TEXT = new Color(235, 231, 220);
    private static final Color MUTED = new Color(163, 160, 150);
    private static final Color BRONZE = new Color(205, 151, 73);
    private static final Color BRONZE_DARK = new Color(111, 77, 34);
    private static final Color READY = new Color(89, 163, 120);
    private static final Color WARNING = new Color(213, 166, 79);
    private static final Color BLOCKED = new Color(190, 81, 65);
    private static final Color RESEARCH = new Color(137, 111, 174);
    private static final Color SHIP = new Color(184, 134, 76);
    private static final Color MATERIAL = new Color(92, 151, 116);
    private static final Color STATION = new Color(167, 119, 71);
    private static final Color REFIT = new Color(125, 135, 143);

    private final GamePanel returnFocus;
    private final World world;
    private final PeerNetwork network;

    private final DefaultListModel<Section> sectionModel = new DefaultListModel<>();
    private final JList<Section> sectionList = new JList<>(sectionModel);
    private final DefaultListModel<ProductionChoice> choiceModel = new DefaultListModel<>();
    private final JList<ProductionChoice> choiceList = new JList<>(choiceModel);
    private final JTextField search = new JTextField();
    private final JLabel systemLabel = new JLabel();
    private final JLabel catalogCount = new JLabel();

    private final JPanel detailPanel = new JPanel();
    private final JLabel detailTitle = new JLabel("Select an output");
    private final JLabel detailKind = new JLabel(" ");
    private final JTextArea detailDescription = new JTextArea();
    private final JLabel detailState = new JLabel(" ");
    private final JPanel costPanel = new JPanel();
    private final JComboBox<StationOption> stationCombo = new JComboBox<>();
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JButton buildButton = new JButton("QUEUE PRODUCTION");
    private final JPanel automationPanel = new JPanel();
    private final JSpinner policyTarget = new JSpinner(new SpinnerNumberModel(25, 1, 1_000_000, 1));
    private final JSpinner policyBatch = new JSpinner(new SpinnerNumberModel(1, 1, 32, 1));
    private final JButton maintainButton = new JButton("MAINTAIN");
    private final JButton repeatButton = new JButton("REPEAT");
    private final JLabel policyStatus = new JLabel(" ");

    private final JPanel resourceRows = new JPanel();
    private final JLabel resourceSummary = new JLabel();
    private final JPanel queueRows = new JPanel();
    private final JLabel queueSummary = new JLabel();

    private final javax.swing.Timer liveTimer;
    private String previousStatus = "";
    private String preferredBaseId = "";
    private int timerTicks;

    ManufacturingOverlay(GamePanel returnFocus, World world, PeerNetwork network) {
        super(new GridBagLayout());
        this.returnFocus = returnFocus;
        this.world = world;
        this.network = network;
        setOpaque(false);
        setVisible(false);
        setFocusable(true);

        for (Section section : Section.values()) sectionModel.addElement(section);
        sectionList.setSelectedValue(Section.ALL, true);

        JPanel card = buildCard();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(20, 24, 20, 24);
        add(card, gbc);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshCatalog(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshCatalog(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshCatalog(); }
        });
        sectionList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) refreshCatalog();
        });
        choiceList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedChoice();
        });
        stationCombo.addActionListener(event -> refreshPolicyStatus());
        buildButton.addActionListener(event -> queueSelected());
        maintainButton.addActionListener(event -> createPolicy(false));
        repeatButton.addActionListener(event -> createPolicy(true));

        installKeyBindings();
        liveTimer = new javax.swing.Timer(80, event -> {
            timerTicks++;
            queueRows.repaint();
            if (timerTicks % 4 == 0 && isVisible()) refreshLiveData();
            if (timerTicks % 12 == 0 && isVisible()) refreshCatalogPreservingSelection();
        });
        liveTimer.setCoalesce(true);
        liveTimer.start();
        INSTANCES.put(world, new WeakReference<>(this));
        refreshCatalog();
        refreshLiveData();
    }

    static boolean openForStation(World world, String baseId) {
        WeakReference<ManufacturingOverlay> reference = INSTANCES.get(world);
        ManufacturingOverlay overlay = reference == null ? null : reference.get();
        if (overlay == null) return false;
        overlay.preferStation(baseId);
        overlay.open();
        return true;
    }

    void toggle() {
        if (isVisible()) close();
        else open();
    }

    void open() {
        if (isVisible()) return;
        previousStatus = world.status == null ? "" : world.status;
        world.status = "Manufacturing console open. Choose an output; AUTO routes work to compatible owned stations.";
        setVisible(true);
        refreshCatalogPreservingSelection();
        refreshLiveData();
        SwingUtilities.invokeLater(() -> {
            search.requestFocusInWindow();
            search.selectAll();
        });
    }

    void close() {
        if (!isVisible()) return;
        setVisible(false);
        if (world.status != null && world.status.startsWith("Manufacturing console open.")) {
            world.status = previousStatus;
        }
        if (returnFocus != null) {
            returnFocus.requestFocusInWindow();
            returnFocus.repaint();
        }
    }

    void disposeOverlay() {
        liveTimer.stop();
        close();
        WeakReference<ManufacturingOverlay> reference = INSTANCES.get(world);
        if (reference != null && reference.get() == this) INSTANCES.remove(world);
    }

    boolean isSearchFocused() {
        return isVisible() && search.isFocusOwner();
    }

    void preferStation(String baseId) {
        preferredBaseId = baseId == null ? "" : baseId;
        if (isVisible()) populateStations(choiceList.getSelectedValue());
    }

    private JPanel buildCard() {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(95, 79, 56), 2),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        card.setMinimumSize(new Dimension(960, 620));
        card.setPreferredSize(new Dimension(1280, 820));

        card.add(buildHeader(), BorderLayout.NORTH);

        JPanel upper = new JPanel(new BorderLayout(10, 0));
        upper.setOpaque(false);
        upper.add(buildSections(), BorderLayout.WEST);

        JSplitPane catalogSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildCatalog(), buildDetails());
        styleSplit(catalogSplit, 0.42);

        JSplitPane resourceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                catalogSplit, buildResources());
        styleSplit(resourceSplit, 0.80);
        upper.add(resourceSplit, BorderLayout.CENTER);
        card.add(upper, BorderLayout.CENTER);
        card.add(buildQueues(), BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildHeader() {
        JPanel root = new JPanel(new BorderLayout(12, 8));
        root.setOpaque(false);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("MANUFACTURING COMMAND");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("Item-first production • automatic station routing • live queues and industrial policy");
        subtitle.setForeground(MUTED);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        heading.add(title);
        heading.add(Box.createVerticalStrut(2));
        heading.add(subtitle);
        root.add(heading, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        systemLabel.setForeground(BRONZE);
        systemLabel.setFont(systemLabel.getFont().deriveFont(Font.BOLD, 11f));
        right.add(systemLabel);
        JButton close = button("CLOSE", BRONZE_DARK);
        close.addActionListener(event -> close());
        right.add(close);
        root.add(right, BorderLayout.EAST);

        styleField(search);
        search.setToolTipText("Search ships, recipes, materials, stations, loadouts, and research");
        JLabel searchLabel = smallLabel("SEARCH OUTPUTS", MUTED);
        JPanel searchRow = new JPanel(new BorderLayout(10, 0));
        searchRow.setOpaque(false);
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        root.add(searchRow, BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildSections() {
        sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sectionList.setBackground(SURFACE_2);
        sectionList.setForeground(TEXT);
        sectionList.setFixedCellHeight(42);
        sectionList.setFixedCellWidth(150);
        sectionList.setBorder(BorderFactory.createLineBorder(BORDER));
        sectionList.setCellRenderer(new SectionRenderer());
        JScrollPane scroll = new JScrollPane(sectionList);
        styleScroll(scroll);
        scroll.setPreferredSize(new Dimension(154, 400));
        return scroll;
    }

    private JComponent buildCatalog() {
        choiceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        choiceList.setBackground(FIELD);
        choiceList.setForeground(TEXT);
        choiceList.setFixedCellHeight(64);
        choiceList.setCellRenderer(new ChoiceRenderer());
        JScrollPane scroll = new JScrollPane(choiceList);
        styleScroll(scroll);

        catalogCount.setForeground(MUTED);
        catalogCount.setBorder(BorderFactory.createEmptyBorder(5, 4, 0, 0));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(SURFACE_2);
        panel.setBorder(titledBorder("AVAILABLE PRODUCTION"));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(catalogCount, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildDetails() {
        detailPanel.setBackground(SURFACE_2);
        detailPanel.setBorder(titledBorder("BUILD ORDER"));
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));

        detailTitle.setForeground(TEXT);
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 21f));
        detailTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailKind.setForeground(BRONZE);
        detailKind.setFont(detailKind.getFont().deriveFont(Font.BOLD, 10f));
        detailKind.setAlignmentX(Component.LEFT_ALIGNMENT);

        detailDescription.setEditable(false);
        detailDescription.setLineWrap(true);
        detailDescription.setWrapStyleWord(true);
        detailDescription.setRows(3);
        detailDescription.setBackground(SURFACE_2);
        detailDescription.setForeground(MUTED);
        detailDescription.setBorder(BorderFactory.createEmptyBorder(5, 0, 7, 0));
        detailDescription.setAlignmentX(Component.LEFT_ALIGNMENT);

        detailState.setForeground(MUTED);
        detailState.setAlignmentX(Component.LEFT_ALIGNMENT);

        costPanel.setOpaque(false);
        costPanel.setLayout(new BoxLayout(costPanel, BoxLayout.Y_AXIS));
        costPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        styleCombo(stationCombo);
        stationCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        stationCombo.setAlignmentX(Component.LEFT_ALIGNMENT);

        styleSpinner(quantity);
        JPanel quantityRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        quantityRow.setOpaque(false);
        quantityRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        quantityRow.add(smallLabel("QUANTITY", MUTED));
        quantityRow.add(quantity);
        quantityRow.add(deltaButton("+1", 1));
        quantityRow.add(deltaButton("+5", 5));
        quantityRow.add(deltaButton("+10", 10));

        styleActionButton(buildButton, BRONZE);
        buildButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        buildButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        configureAutomationPanel();

        detailPanel.add(detailTitle);
        detailPanel.add(Box.createVerticalStrut(2));
        detailPanel.add(detailKind);
        detailPanel.add(detailDescription);
        detailPanel.add(detailState);
        detailPanel.add(Box.createVerticalStrut(8));
        detailPanel.add(sectionRule("REQUIREMENTS"));
        detailPanel.add(costPanel);
        detailPanel.add(Box.createVerticalStrut(8));
        detailPanel.add(sectionRule("BUILD LOCATION"));
        detailPanel.add(stationCombo);
        detailPanel.add(Box.createVerticalStrut(7));
        detailPanel.add(quantityRow);
        detailPanel.add(Box.createVerticalStrut(7));
        detailPanel.add(buildButton);
        detailPanel.add(Box.createVerticalStrut(10));
        detailPanel.add(automationPanel);

        JScrollPane scroll = new JScrollPane(detailPanel);
        styleScroll(scroll);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private void configureAutomationPanel() {
        automationPanel.setBackground(new Color(27, 28, 30));
        automationPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(72, 66, 56)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        automationPanel.setLayout(new BoxLayout(automationPanel, BoxLayout.Y_AXIS));
        automationPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = smallLabel("AUTOMATION", BRONZE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        automationPanel.add(title);
        automationPanel.add(Box.createVerticalStrut(5));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controls.setOpaque(false);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(smallLabel("Target", MUTED));
        styleSpinner(policyTarget);
        controls.add(policyTarget);
        controls.add(smallLabel("Batch", MUTED));
        styleSpinner(policyBatch);
        controls.add(policyBatch);
        automationPanel.add(controls);
        automationPanel.add(Box.createVerticalStrut(5));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        styleActionButton(maintainButton, READY);
        styleActionButton(repeatButton, new Color(139, 105, 70));
        actions.add(maintainButton);
        actions.add(repeatButton);
        automationPanel.add(actions);
        policyStatus.setForeground(MUTED);
        policyStatus.setFont(policyStatus.getFont().deriveFont(Font.PLAIN, 10f));
        policyStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        automationPanel.add(Box.createVerticalStrut(4));
        automationPanel.add(policyStatus);
    }

    private JComponent buildResources() {
        resourceRows.setOpaque(false);
        resourceRows.setLayout(new BoxLayout(resourceRows, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(resourceRows);
        styleScroll(scroll);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        resourceSummary.setForeground(MUTED);
        resourceSummary.setBorder(BorderFactory.createEmptyBorder(5, 3, 0, 3));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(SURFACE_2);
        panel.setBorder(titledBorder("NETWORK INVENTORY"));
        panel.setPreferredSize(new Dimension(220, 400));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(resourceSummary, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildQueues() {
        queueRows.setOpaque(false);
        queueRows.setLayout(new BoxLayout(queueRows, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(queueRows);
        styleScroll(scroll);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(900, 225));

        queueSummary.setForeground(MUTED);
        queueSummary.setBorder(BorderFactory.createEmptyBorder(4, 3, 0, 3));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(SURFACE_2);
        panel.setBorder(titledBorder("LIVE PRODUCTION QUEUES"));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(queueSummary, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshCatalog() {
        String selectedKey = choiceList.getSelectedValue() == null ? "" : choiceList.getSelectedValue().key;
        List<ProductionChoice> choices = allChoices();
        Section section = sectionList.getSelectedValue();
        if (section == null) section = Section.ALL;
        String query = search.getText().trim().toLowerCase(Locale.ROOT);

        choiceModel.clear();
        int selectedIndex = -1;
        for (ProductionChoice choice : choices) {
            if (!section.accepts(choice)) continue;
            if (!query.isBlank() && !choice.searchText().contains(query)) continue;
            if (choice.key.equals(selectedKey)) selectedIndex = choiceModel.size();
            choiceModel.addElement(choice);
        }
        catalogCount.setText(choiceModel.size() + " outputs • active system");
        if (selectedIndex < 0 && !choiceModel.isEmpty()) selectedIndex = 0;
        if (selectedIndex >= 0) choiceList.setSelectedIndex(selectedIndex);
        else clearDetails();
    }

    private void refreshCatalogPreservingSelection() {
        refreshCatalog();
    }

    private List<ProductionChoice> allChoices() {
        String playerId = playerId();
        boolean free = world.devFreeBuildFor(playerId);
        List<Base> bases = ownedBases();
        LinkedHashMap<String, ProductionChoice> out = new LinkedHashMap<>();

        Set<String> hulls = new LinkedHashSet<>();
        Set<String> packages = new LinkedHashSet<>();
        for (Base base : bases) {
            hulls.addAll(base.type().buildableShips);
            packages.addAll(base.type().basePackages);
        }

        for (String hullId : hulls) {
            ShipType ship = Rules.findShip(hullId);
            if (ship == null) continue;
            List<ShipLoadoutDefinition> loadouts = new ArrayList<>(WeaponRules.loadoutsForHull(hullId));
            if (loadouts.isEmpty() && WeaponRules.defaultLoadout(hullId) != null) {
                loadouts.add(WeaponRules.defaultLoadout(hullId));
            }
            for (ShipLoadoutDefinition loadout : loadouts) {
                String locked = "";
                if (!free && !ResearchRules.shipUnlocked(world, playerId, hullId)) {
                    ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(hullId);
                    locked = topic == null ? "Research required" : "Requires " + topic.name;
                } else if (!free && !WeaponRules.unlocked(world, playerId, loadout)) {
                    locked = "Requires " + WeaponRules.missingResearchLabel(world, playerId, loadout);
                }
                String variant = loadouts.size() > 1 ? " • " + loadout.displayName() : "";
                String description = "Hull " + ship.name + variant + " • " + whole(ship.maxHp) + " hull"
                        + (ship.maxShield > 0 ? " • " + whole(ship.maxShield) + " shield" : "")
                        + " • speed " + whole(ship.speed);
                ProductionChoice choice = new ProductionChoice(
                        "SHIP:" + loadout.id(), ProductionJobKind.SHIP, ship.name,
                        "SHIP" + variant, description, WeaponRules.buildCost(ship, loadout),
                        ship.buildTimeSeconds, null, null, 0, loadout.id(), hullId, loadout.id(), locked);
                out.put(choice.key, choice);
            }
        }

        for (CraftableItem item : CraftingRules.all()) {
            if (!hasCompatibleBase(bases, item)) continue;
            String locked = free || item.unlockedFor(world, playerId)
                    ? "" : "Requires " + item.missingResearchLabel(world, playerId);
            String description = item.description == null || item.description.isBlank()
                    ? "Manufactured " + item.outputMaterial.label : item.description;
            ProductionChoice choice = new ProductionChoice(
                    "CRAFT:" + item.id, ProductionJobKind.CRAFTABLE, item.name,
                    item.category.label.toUpperCase(Locale.ROOT), description, item.requiredResources,
                    item.timeSeconds, item.category, item.outputMaterial, item.outputAmount,
                    item.id, "", "", locked);
            out.put(choice.key, choice);
        }

        for (String packageId : packages) {
            BaseType station = Rules.findBase(packageId);
            if (station == null) continue;
            String locked = free || StationPackageResearchRules.unlocked(world, playerId, packageId)
                    ? "" : "Requires " + StationPackageResearchRules.requiredResearchName(packageId);
            ProductionChoice choice = new ProductionChoice(
                    "STATION:" + packageId, ProductionJobKind.STATION_PACKAGE, station.name,
                    "STATION PACKAGE", "Packaged structure loaded into an available Deployer for placement.",
                    station.buildCost, station.buildTimeSeconds, null, null, 1,
                    packageId, "", "", locked);
            out.put(choice.key, choice);
        }

        for (ResearchTopic topic : ResearchRules.all()) {
            if (!hasResearchBase(bases, topic)) continue;
            String locked = "";
            if (world.hasResearch(playerId, topic.id)) locked = "Completed";
            else if (ProductionSystem.researchQueued(world, playerId, topic.id)) locked = "Already queued";
            else {
                String prerequisite = ResearchRules.missingPrerequisite(world, playerId, topic);
                if (!prerequisite.isBlank()) locked = "Requires " + prerequisite;
            }
            ProductionChoice choice = new ProductionChoice(
                    "RESEARCH:" + topic.id, ProductionJobKind.RESEARCH, topic.name,
                    "RESEARCH", topic.description + (topic.unlockLabel().isBlank() ? "" : " • " + topic.unlockLabel()),
                    topic.requiredResources, topic.timeSeconds, null, null, 0,
                    topic.id, "", "", locked);
            out.put(choice.key, choice);
        }

        List<ProductionChoice> choices = new ArrayList<>(out.values());
        choices.sort(Comparator.comparing((ProductionChoice choice) -> choice.kind.ordinal())
                .thenComparing(choice -> choice.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(choice -> choice.subtitle, String.CASE_INSENSITIVE_ORDER));
        return choices;
    }

    private void showSelectedChoice() {
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice == null) {
            clearDetails();
            return;
        }
        detailTitle.setText(choice.name);
        detailKind.setText(choice.subtitle + "   •   " + whole(choice.timeSeconds) + "s");
        detailKind.setForeground(kindColor(choice.kind));
        detailDescription.setText(choice.description);
        detailDescription.setCaretPosition(0);
        if (choice.lockedReason.isBlank()) {
            detailState.setText("READY TO ROUTE");
            detailState.setForeground(READY);
        } else {
            detailState.setText(choice.lockedReason.toUpperCase(Locale.ROOT));
            detailState.setForeground(choice.lockedReason.equals("Completed") ? READY : BLOCKED);
        }
        populateCosts(choice);
        populateStations(choice);
        updateAutomation(choice);
        refreshResources();
        revalidate();
        repaint();
    }

    private void clearDetails() {
        detailTitle.setText("No matching output");
        detailKind.setText(" ");
        detailDescription.setText("Change the production category or search text.");
        detailState.setText(" ");
        costPanel.removeAll();
        stationCombo.setModel(new DefaultComboBoxModel<>());
        buildButton.setEnabled(false);
        automationPanel.setVisible(false);
    }

    private void populateCosts(ProductionChoice choice) {
        costPanel.removeAll();
        EnumMap<Material, Double> totals = aggregateInventory();
        if (choice.cost.isEmpty()) {
            JLabel free = smallLabel(world.devFreeBuildFor(playerId()) ? "DEV FREE BUILD" : "No material input", READY);
            free.setAlignmentX(Component.LEFT_ALIGNMENT);
            costPanel.add(free);
        } else {
            for (Cost cost : choice.cost) {
                double available = totals.getOrDefault(cost.material(), 0.0);
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
                JLabel name = smallLabel(cost.material().label, TEXT);
                JLabel amount = smallLabel(whole(available) + " / " + whole(cost.amount()),
                        available + 0.0001 >= cost.amount() ? READY : WARNING);
                row.add(name, BorderLayout.WEST);
                row.add(amount, BorderLayout.EAST);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                costPanel.add(row);
            }
        }
        costPanel.revalidate();
        costPanel.repaint();
    }

    private void populateStations(ProductionChoice choice) {
        DefaultComboBoxModel<StationOption> model = new DefaultComboBoxModel<>();
        if (choice == null) {
            stationCombo.setModel(model);
            return;
        }
        List<Base> eligible = eligibleStations(choice);
        Base recommended = recommendedStation(choice, eligible, null);
        if (recommended != null) model.addElement(StationOption.auto(recommended));
        for (Base base : eligible) model.addElement(StationOption.manual(base));
        stationCombo.setModel(model);

        if (!preferredBaseId.isBlank()) {
            for (int i = 1; i < model.getSize(); i++) {
                StationOption option = model.getElementAt(i);
                if (option.base != null && preferredBaseId.equals(option.base.id)) {
                    stationCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        boolean enabled = choice.lockedReason.isBlank() && !eligible.isEmpty();
        buildButton.setEnabled(enabled);
        buildButton.setText(eligible.isEmpty() ? "NO COMPATIBLE STATION" : "QUEUE PRODUCTION");
        refreshPolicyStatus();
    }

    private void updateAutomation(ProductionChoice choice) {
        boolean supported = choice != null
                && (choice.kind == ProductionJobKind.SHIP || choice.kind == ProductionJobKind.CRAFTABLE);
        automationPanel.setVisible(supported);
        if (!supported) return;
        maintainButton.setText(choice.kind == ProductionJobKind.SHIP ? "MAINTAIN FLEET" : "MAINTAIN STOCK");
        maintainButton.setEnabled(choice.lockedReason.isBlank() && !eligibleStations(choice).isEmpty());
        repeatButton.setEnabled(maintainButton.isEnabled());
        refreshPolicyStatus();
    }

    private void refreshPolicyStatus() {
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice == null || !automationPanel.isVisible()) return;
        Base base = selectedStation(choice);
        if (base == null) {
            policyStatus.setText("No compatible station available.");
            return;
        }
        List<ProductionPolicySystem.PolicyView> policies = ProductionPolicySystem.viewsForBase(world, base);
        int matching = 0;
        for (ProductionPolicySystem.PolicyView policy : policies) {
            if (policy.kind() == choice.kind && policy.itemId().equals(choice.itemIdForPolicy())) matching++;
        }
        policyStatus.setText(base.type().name + " " + base.id + " • " + matching + " matching polic"
                + (matching == 1 ? "y" : "ies") + " • " + policies.size() + " total");
    }

    private void queueSelected() {
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice == null || !choice.lockedReason.isBlank()) return;
        int count = ((Number)quantity.getValue()).intValue();
        StationOption option = (StationOption)stationCombo.getSelectedItem();
        List<Base> route;
        if (option != null && !option.auto && option.base != null) {
            route = new ArrayList<>();
            for (int i = 0; i < count; i++) route.add(option.base);
        } else {
            route = autoRoute(choice, count);
        }
        if (route.isEmpty()) {
            world.status = "No compatible owned station can produce " + choice.name + ".";
            detailState.setText("NO COMPATIBLE STATION");
            detailState.setForeground(BLOCKED);
            return;
        }

        int accepted = 0;
        for (Base base : route) {
            if (sendProduction(base, "ENQUEUE", choice.kind.name(), choice.commandItemId)) accepted++;
        }
        world.status = accepted == 1
                ? "Production request queued: " + choice.name + "."
                : "Queued " + accepted + " production requests for " + choice.name + ".";
        preferredBaseId = "";
        refreshLiveData();
        refreshCatalogPreservingSelection();
    }

    private void createPolicy(boolean repeat) {
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice == null || !choice.lockedReason.isBlank()
                || (choice.kind != ProductionJobKind.SHIP && choice.kind != ProductionJobKind.CRAFTABLE)) return;
        Base base = selectedStation(choice);
        if (base == null) return;

        ProductionPolicySystem.PolicyType type = repeat
                ? ProductionPolicySystem.PolicyType.REPEAT
                : choice.kind == ProductionJobKind.SHIP
                ? ProductionPolicySystem.PolicyType.MAINTAIN_FLEET
                : ProductionPolicySystem.PolicyType.MAINTAIN_STOCK;
        double target = repeat ? 0 : ((Number)policyTarget.getValue()).doubleValue();
        int batch = ((Number)policyBatch.getValue()).intValue();
        EnumMap<Material, Double> noReserves = new EnumMap<>(Material.class);
        String encoded = ProductionPolicyWire.encodeSpec("", type, choice.kind, choice.itemIdForPolicy(),
                choice.kind == ProductionJobKind.SHIP ? choice.loadoutId : "", target,
                batch, 50, 3, 0, noReserves, noReserves);
        boolean accepted;
        if (network == null) {
            accepted = ProductionCommands.apply(world, base.playerId, "POLICY", base.id,
                    ProductionPolicySystem.COMMAND_CREATE, encoded);
        } else {
            network.production(base.playerId, "POLICY", base.id,
                    ProductionPolicySystem.COMMAND_CREATE, encoded);
            accepted = true;
        }
        if (accepted) {
            world.status = repeat
                    ? "Created repeat production policy for " + choice.name + "."
                    : "Created " + (choice.kind == ProductionJobKind.SHIP ? "fleet" : "stock")
                    + " maintenance policy for " + choice.name + ".";
            refreshPolicyStatus();
            refreshLiveData();
        }
    }

    private List<Base> autoRoute(ProductionChoice choice, int count) {
        List<Base> eligible = eligibleStations(choice);
        if (eligible.isEmpty() || count <= 0) return List.of();
        Map<String, Integer> predicted = new LinkedHashMap<>();
        for (Base base : eligible) predicted.put(base.id, base.productionQueue.size());
        List<Base> route = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Base selected = recommendedStation(choice, eligible, predicted);
            if (selected == null) break;
            route.add(selected);
            predicted.put(selected.id, predicted.getOrDefault(selected.id, selected.productionQueue.size()) + 1);
        }
        return route;
    }

    private Base selectedStation(ProductionChoice choice) {
        StationOption option = (StationOption)stationCombo.getSelectedItem();
        if (option != null && !option.auto && option.base != null) return option.base;
        return recommendedStation(choice, eligibleStations(choice), null);
    }

    private Base recommendedStation(ProductionChoice choice, List<Base> eligible, Map<String, Integer> predicted) {
        if (eligible.isEmpty()) return null;
        List<Base> sorted = new ArrayList<>(eligible);
        sorted.sort(Comparator
                .comparing((Base base) -> !StationFuelRules.isOperational(base))
                .thenComparing(base -> !HangarStore.canAfford(base.inventory, choice.cost))
                .thenComparingInt(base -> predicted == null
                        ? base.productionQueue.size()
                        : predicted.getOrDefault(base.id, base.productionQueue.size()))
                .thenComparing(base -> base.id));
        return sorted.get(0);
    }

    private List<Base> eligibleStations(ProductionChoice choice) {
        List<Base> out = new ArrayList<>();
        for (Base base : ownedBases()) if (choice.compatible(base)) out.add(base);
        out.sort(Comparator.comparing((Base base) -> base.type().name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(base -> base.id));
        return out;
    }

    private void refreshLiveData() {
        if (world == null) return;
        systemLabel.setText(world.systemName().toUpperCase(Locale.ROOT) + "  •  F9");
        refreshResources();
        refreshQueues();
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice != null) {
            populateCosts(choice);
            refreshPolicyStatus();
        }
    }

    private void refreshResources() {
        resourceRows.removeAll();
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice == null) {
            addInventoryMessage("Select an output to inspect its material locations.");
            resourceSummary.setText("Recipe-aware inventory • owned stations");
            finishResourceRefresh();
            return;
        }

        LinkedHashMap<Material, Double> requirements = new LinkedHashMap<>();
        for (Cost cost : choice.cost) {
            if (cost == null || cost.material() == null || cost.amount() <= 0) continue;
            requirements.merge(cost.material(), cost.amount(), Double::sum);
        }
        if (requirements.isEmpty()) {
            addInventoryMessage("This order has no material inputs to locate.");
            resourceSummary.setText(choice.name + " • no recipe inventory required");
            finishResourceRefresh();
            return;
        }

        List<Material> materials = new ArrayList<>(requirements.keySet());
        materials.sort(Comparator.comparing(material -> material.label, String.CASE_INSENSITIVE_ORDER));
        EnumMap<Material, Double> located = new EnumMap<>(Material.class);
        String owner = playerId();
        String activeId = world.activeSystemId();
        String activeName = world.systemName();
        List<WorldSystemState> states = world.policySystemStates();
        for (WorldSystemState state : states) {
            if (state != null && activeId.equals(state.id) && state.definition != null) {
                activeName = state.definition.name();
                break;
            }
        }

        int systems = 0;
        int stations = appendRecipeSystem(activeId, activeName, world.bases.values(),
                materials, owner, true, located);
        if (stations > 0) systems++;

        for (WorldSystemState state : states) {
            if (state == null || state.id == null || state.id.equals(activeId)) continue;
            String name = state.definition == null ? state.id : state.definition.name();
            int found = appendRecipeSystem(state.id, name, state.bases.values(),
                    materials, owner, false, located);
            if (found > 0) {
                systems++;
                stations += found;
            }
        }

        int shortages = 0;
        for (Map.Entry<Material, Double> requirement : requirements.entrySet()) {
            double available = located.getOrDefault(requirement.getKey(), 0.0);
            if (available + 0.0001 < requirement.getValue()) {
                shortages++;
                addInventoryShortfall(requirement.getKey(), available, requirement.getValue());
            }
        }

        if (systems == 0) {
            addInventoryMessage("Required materials are not stored in any known owned station.");
        }
        resourceRows.add(Box.createVerticalGlue());
        resourceSummary.setText(materials.size() + " recipe materials • " + systems + " system"
                + (systems == 1 ? "" : "s") + " • " + stations + " station"
                + (stations == 1 ? "" : "s")
                + (shortages == 0 ? " • stock located" : " • " + shortages + " short"));
        finishResourceRefresh();
    }

    private int appendRecipeSystem(String systemId, String systemName, Iterable<Base> candidates,
                                   List<Material> materials, String owner, boolean current,
                                   EnumMap<Material, Double> located) {
        List<Base> stations = new ArrayList<>();
        for (Base base : candidates) {
            if (base == null || base.hp <= 0 || !owner.equals(base.playerId)) continue;
            boolean hasRecipeStock = false;
            for (Material material : materials) {
                if (base.inventory.getOrDefault(material, 0.0) > 0.0001) {
                    hasRecipeStock = true;
                    break;
                }
            }
            if (hasRecipeStock) stations.add(base);
        }
        if (stations.isEmpty()) return 0;
        stations.sort(Comparator.comparing((Base base) -> base.type().name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(base -> base.id));

        JPanel systemRow = new JPanel(new BorderLayout());
        systemRow.setOpaque(false);
        systemRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        systemRow.setBorder(BorderFactory.createEmptyBorder(5, 2, 2, 2));
        String safeName = systemName == null || systemName.isBlank() ? systemId : systemName;
        JLabel system = smallLabel("▼  " + safeName.toUpperCase(Locale.ROOT)
                + (current ? "  • CURRENT" : "  • " + systemId), current ? BRONZE : TEXT);
        system.setFont(system.getFont().deriveFont(Font.BOLD, 11f));
        systemRow.add(system, BorderLayout.WEST);
        resourceRows.add(systemRow);

        for (Base base : stations) {
            JPanel stationRow = new JPanel(new BorderLayout());
            stationRow.setOpaque(false);
            stationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 23));
            stationRow.setBorder(BorderFactory.createEmptyBorder(2, 17, 1, 2));
            JLabel station = smallLabel("└─ " + base.type().name + " " + base.id, TEXT);
            station.setFont(station.getFont().deriveFont(Font.BOLD, 10.5f));
            stationRow.add(station, BorderLayout.WEST);
            resourceRows.add(stationRow);

            for (Material material : materials) {
                double amount = base.inventory.getOrDefault(material, 0.0);
                if (amount <= 0.0001) continue;
                located.merge(material, amount, Double::sum);
                JPanel materialRow = new JPanel(new BorderLayout(6, 0));
                materialRow.setOpaque(false);
                materialRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
                materialRow.setBorder(BorderFactory.createEmptyBorder(1, 38, 1, 3));
                JLabel name = smallLabel("↳  " + material.label, MUTED);
                name.setIcon(CatalogVisuals.materialIcon(material, 16));
                name.setIconTextGap(5);
                JLabel amountLabel = smallLabel(whole(amount), BRONZE);
                materialRow.add(name, BorderLayout.WEST);
                materialRow.add(amountLabel, BorderLayout.EAST);
                resourceRows.add(materialRow);
            }
        }
        resourceRows.add(Box.createVerticalStrut(4));
        return stations.size();
    }

    private void addInventoryShortfall(Material material, double available, double required) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
        row.setBorder(BorderFactory.createEmptyBorder(4, 3, 2, 3));
        JLabel name = smallLabel("⚠  " + material.label + " shortfall", WARNING);
        name.setIcon(CatalogVisuals.materialIcon(material, 16));
        name.setIconTextGap(5);
        JLabel amount = smallLabel(whole(available) + " / " + whole(required), WARNING);
        row.add(name, BorderLayout.WEST);
        row.add(amount, BorderLayout.EAST);
        resourceRows.add(row);
    }

    private void addInventoryMessage(String text) {
        JLabel empty = smallLabel(text, MUTED);
        empty.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        empty.setAlignmentX(Component.LEFT_ALIGNMENT);
        resourceRows.add(empty);
    }

    private void finishResourceRefresh() {
        resourceRows.revalidate();
        resourceRows.repaint();
    }

    private void refreshQueues() {
        queueRows.removeAll();
        List<Base> bases = ownedBases();
        bases.sort(Comparator.comparing((Base base) -> base.type().name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(base -> base.id));
        int jobs = 0;
        int activeStations = 0;
        for (Base base : bases) {
            if (StationControls.nonProduction(base.typeId)) continue;
            if (!base.productionQueue.isEmpty()) activeStations++;
            jobs += base.productionQueue.size();
            queueRows.add(buildStationQueue(base));
            queueRows.add(Box.createVerticalStrut(6));
        }
        if (bases.isEmpty()) {
            JLabel empty = smallLabel("No owned production stations in this system.", MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            queueRows.add(empty);
        }
        queueSummary.setText(jobs + " jobs • " + activeStations + " active station queues • host-authoritative");
        queueRows.revalidate();
        queueRows.repaint();
    }

    private JComponent buildStationQueue(Base base) {
        JPanel card = new JPanel();
        card.setBackground(new Color(28, 30, 32));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(69, 66, 60)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel title = new JLabel(base.type().name + "  " + base.id);
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        boolean operational = StationFuelRules.isOperational(base);
        JLabel state = smallLabel((operational ? "ONLINE" : "OFFLINE") + "  •  "
                + base.productionQueue.size() + " queued",
                operational ? READY : BLOCKED);
        header.add(title, BorderLayout.WEST);
        header.add(state, BorderLayout.EAST);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(header);

        if (base.productionQueue.isEmpty()) {
            JLabel idle = smallLabel("Idle — ready for routed work", MUTED);
            idle.setBorder(BorderFactory.createEmptyBorder(5, 0, 1, 0));
            idle.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(idle);
            return card;
        }

        for (int index = 0; index < base.productionQueue.size(); index++) {
            card.add(buildJobRow(base, base.productionQueue.get(index), index));
            if (index < base.productionQueue.size() - 1) card.add(Box.createVerticalStrut(3));
        }
        return card;
    }

    private JComponent buildJobRow(Base base, ProductionJob job, int index) {
        JPanel row = new JPanel(new BorderLayout(7, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel order = smallLabel(index == 0 ? "▶" : Integer.toString(index + 1),
                index == 0 ? kindColor(job.kind) : MUTED);
        order.setPreferredSize(new Dimension(20, 30));
        row.add(order, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout(6, 0));
        center.setOpaque(false);
        JLabel name = smallLabel(ProductionSystem.displayName(world, job), TEXT);
        String blocked = job.blockedReason == null ? "" : job.blockedReason;
        JLabel detail = smallLabel(blocked.isBlank()
                        ? (index == 0 ? whole(job.remaining) + "s remaining" : "queued")
                        : blocked,
                blocked.isBlank() ? MUTED : BLOCKED);
        JPanel labels = new JPanel(new BorderLayout());
        labels.setOpaque(false);
        labels.add(name, BorderLayout.WEST);
        labels.add(detail, BorderLayout.EAST);
        center.add(labels, BorderLayout.NORTH);
        AnimatedProgress progress = new AnimatedProgress(job.progress(), job.kind, !blocked.isBlank(), index == 0);
        progress.setPreferredSize(new Dimension(260, 9));
        center.add(progress, BorderLayout.SOUTH);
        row.add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        actions.setOpaque(false);
        JButton up = miniButton("↑");
        up.setEnabled(index > 1);
        up.addActionListener(event -> sendProduction(base, "MOVE", job.id, "-1"));
        JButton down = miniButton("↓");
        down.setEnabled(index > 0 && index < base.productionQueue.size() - 1);
        down.addActionListener(event -> sendProduction(base, "MOVE", job.id, "1"));
        JButton cancel = miniButton("×");
        cancel.setToolTipText("Cancel and refund reserved resources when applicable");
        cancel.addActionListener(event -> sendProduction(base, "CANCEL", job.id, ""));
        actions.add(up);
        actions.add(down);
        actions.add(cancel);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private boolean sendProduction(Base base, String action, String value, String extra) {
        if (network == null) return ProductionCommands.apply(world, base.playerId, action, base.id, value, extra);
        network.production(base.playerId, action, base.id, value, extra);
        return true;
    }

    private EnumMap<Material, Double> aggregateInventory() {
        EnumMap<Material, Double> totals = new EnumMap<>(Material.class);
        for (Base base : ownedBases()) {
            for (Map.Entry<Material, Double> entry : base.inventory.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) continue;
                totals.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        return totals;
    }

    private List<Base> ownedBases() {
        List<Base> out = new ArrayList<>();
        String owner = playerId();
        for (Base base : world.bases.values()) {
            if (base.hp > 0 && owner.equals(base.playerId)) out.add(base);
        }
        return out;
    }

    private String playerId() {
        if (network != null) {
            String id = network.localPlayerId();
            if (id != null && !id.isBlank()) return id;
        }
        return PlayerRegistry.localId();
    }

    private static boolean hasCompatibleBase(List<Base> bases, CraftableItem item) {
        for (Base base : bases) if (item.canCraftAt(base.typeId)) return true;
        return false;
    }

    private static boolean hasResearchBase(List<Base> bases, ResearchTopic topic) {
        for (Base base : bases) if (topic.canResearchAt(base.typeId)) return true;
        return false;
    }

    private void installKeyBindings() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-manufacturing", this::close);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                "focus-manufacturing-search", () -> {
                    search.requestFocusInWindow();
                    search.selectAll();
                });
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private JButton deltaButton(String text, int delta) {
        JButton button = miniButton(text);
        button.addActionListener(event -> {
            int current = ((Number)quantity.getValue()).intValue();
            quantity.setValue(Math.min(100, current + delta));
        });
        return button;
    }

    private static JButton button(String text, Color borderColor) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(SURFACE_3);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        return button;
    }

    private static JButton miniButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(48, 46, 42));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(78, 72, 62)),
                BorderFactory.createEmptyBorder(3, 7, 3, 7)));
        button.setMargin(new Insets(0, 0, 0, 0));
        return button;
    }

    private static void styleActionButton(JButton button, Color accent) {
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(52, 47, 39));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 1),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
    }

    private static void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(TEXT);
        combo.setBackground(FIELD);
        combo.setBorder(BorderFactory.createLineBorder(BORDER));
    }

    private static void styleSpinner(JSpinner spinner) {
        spinner.setPreferredSize(new Dimension(76, 28));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            defaultEditor.getTextField().setBackground(FIELD);
            defaultEditor.getTextField().setForeground(TEXT);
            defaultEditor.getTextField().setCaretColor(TEXT);
        }
    }

    private static void styleScroll(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createLineBorder(new Color(58, 56, 52)));
        scroll.getViewport().setBackground(FIELD);
        scroll.getVerticalScrollBar().setUnitIncrement(22);
    }

    private static void styleSplit(JSplitPane split, double weight) {
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(7);
        split.setResizeWeight(weight);
    }

    private static Border titledBorder(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BORDER), title,
                        0, 0, UIManager.getFont("Label.font").deriveFont(Font.BOLD, 10f), BRONZE),
                BorderFactory.createEmptyBorder(5, 6, 5, 6));
    }

    private static JComponent sectionRule(String title) {
        JPanel panel = new JPanel(new BorderLayout(7, 0));
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        JLabel label = smallLabel(title, MUTED);
        JSeparator line = new JSeparator();
        line.setForeground(new Color(65, 62, 56));
        panel.add(label, BorderLayout.WEST);
        panel.add(line, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JLabel smallLabel(String text, Color color) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        if (Math.abs(value) >= 1000) return String.format(Locale.ROOT, "%,.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Color kindColor(ProductionJobKind kind) {
        return switch (kind) {
            case SHIP -> SHIP;
            case CRAFTABLE -> MATERIAL;
            case STATION_PACKAGE -> STATION;
            case RESEARCH -> RESEARCH;
            case REFIT -> REFIT;
        };
    }

    @Override protected void paintComponent(Graphics graphics) {
        graphics.setColor(BACKDROP);
        graphics.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(graphics);
    }

    private enum Section {
        ALL("All Production"),
        SHIPS("Ships"),
        MATERIALS("Processed"),
        CHEMICALS("Chemicals"),
        ELECTRONICS("Electronics"),
        INDUSTRY("Industry"),
        DEFENSE("Power & Defense"),
        WEAPONS("Weapons"),
        STATIONS("Stations"),
        RESEARCH("Research");

        final String label;

        Section(String label) { this.label = label; }

        boolean accepts(ProductionChoice choice) {
            if (this == ALL) return true;
            if (this == SHIPS) return choice.kind == ProductionJobKind.SHIP;
            if (this == STATIONS) return choice.kind == ProductionJobKind.STATION_PACKAGE;
            if (this == RESEARCH) return choice.kind == ProductionJobKind.RESEARCH;
            if (choice.kind != ProductionJobKind.CRAFTABLE || choice.category == null) return false;
            return switch (this) {
                case MATERIALS -> choice.category == CraftingCategory.MATERIALS;
                case CHEMICALS -> choice.category == CraftingCategory.CHEMICALS;
                case ELECTRONICS -> choice.category == CraftingCategory.ELECTRONICS;
                case INDUSTRY -> choice.category == CraftingCategory.INDUSTRY
                        || choice.category == CraftingCategory.CAPITAL;
                case DEFENSE -> choice.category == CraftingCategory.POWER_DEFENSE;
                case WEAPONS -> choice.category == CraftingCategory.WEAPONS;
                default -> false;
            };
        }

        @Override public String toString() { return label; }
    }

    private static final class ProductionChoice {
        final String key;
        final ProductionJobKind kind;
        final String name;
        final String subtitle;
        final String description;
        final List<Cost> cost;
        final double timeSeconds;
        final CraftingCategory category;
        final Material outputMaterial;
        final double outputAmount;
        final String commandItemId;
        final String hullId;
        final String loadoutId;
        final String lockedReason;

        ProductionChoice(String key, ProductionJobKind kind, String name, String subtitle,
                         String description, List<Cost> cost, double timeSeconds,
                         CraftingCategory category, Material outputMaterial, double outputAmount,
                         String commandItemId, String hullId, String loadoutId, String lockedReason) {
            this.key = key;
            this.kind = kind;
            this.name = name;
            this.subtitle = subtitle;
            this.description = description == null ? "" : description;
            this.cost = cost == null ? List.of() : List.copyOf(cost);
            this.timeSeconds = Math.max(0, timeSeconds);
            this.category = category;
            this.outputMaterial = outputMaterial;
            this.outputAmount = outputAmount;
            this.commandItemId = commandItemId;
            this.hullId = hullId == null ? "" : hullId;
            this.loadoutId = loadoutId == null ? "" : loadoutId;
            this.lockedReason = lockedReason == null ? "" : lockedReason;
        }

        boolean compatible(Base base) {
            if (base == null || base.hp <= 0) return false;
            return switch (kind) {
                case SHIP -> base.type().buildableShips.contains(hullId);
                case CRAFTABLE -> {
                    CraftableItem item = CraftingRules.item(commandItemId);
                    yield item != null && item.canCraftAt(base.typeId);
                }
                case STATION_PACKAGE -> base.type().basePackages.contains(commandItemId);
                case RESEARCH -> {
                    ResearchTopic topic = ResearchRules.topic(commandItemId);
                    yield topic != null && topic.canResearchAt(base.typeId);
                }
                case REFIT -> false;
            };
        }

        String itemIdForPolicy() {
            return kind == ProductionJobKind.SHIP ? hullId : commandItemId;
        }

        String searchText() {
            return (name + " " + subtitle + " " + description + " " + commandItemId + " "
                    + hullId + " " + (category == null ? "" : category.label) + " "
                    + (outputMaterial == null ? "" : outputMaterial.label)).toLowerCase(Locale.ROOT);
        }
    }

    private static final class StationOption {
        final boolean auto;
        final Base base;
        final String label;

        private StationOption(boolean auto, Base base, String label) {
            this.auto = auto;
            this.base = base;
            this.label = label;
        }

        static StationOption auto(Base recommended) {
            return new StationOption(true, null, "AUTO  •  recommended: "
                    + recommended.type().name + " " + recommended.id);
        }

        static StationOption manual(Base base) {
            String supply = StationFuelRules.isOperational(base) ? "online" : "offline";
            return new StationOption(false, base, base.type().name + " " + base.id
                    + "  •  queue " + base.productionQueue.size() + "  •  " + supply);
        }

        @Override public String toString() { return label; }
    }

    private static final class SectionRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
            label.setText(value instanceof Section section ? section.label : String.valueOf(value));
            label.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 8));
            label.setForeground(selected ? TEXT : MUTED);
            label.setBackground(selected ? new Color(67, 51, 31) : SURFACE_2);
            label.setFont(label.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN, 11f));
            return label;
        }
    }

    private static final class ChoiceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
            if (!(value instanceof ProductionChoice choice)) return label;
            String lock = choice.lockedReason.isBlank() ? "" : "  •  " + choice.lockedReason;
            label.setText("<html><b>" + escape(choice.name) + "</b><br><span style='font-size:9px'>"
                    + escape(choice.subtitle + lock) + "</span></html>");
            label.setIcon(choice.outputMaterial == null
                    ? new ProductionGlyph(choice.kind, 42)
                    : CatalogVisuals.materialIcon(choice.outputMaterial, 42));
            label.setIconTextGap(10);
            label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            label.setForeground(choice.lockedReason.isBlank() ? TEXT : new Color(173, 147, 132));
            label.setBackground(selected ? new Color(67, 51, 31) : FIELD);
            return label;
        }

        private static String escape(String text) {
            return (text == null ? "" : text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static final class ProductionGlyph implements Icon {
        private final ProductionJobKind kind;
        private final int size;

        ProductionGlyph(ProductionJobKind kind, int size) {
            this.kind = kind;
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color accent = kindColor(kind);
            g.setColor(new Color(12, 13, 15));
            g.fillRoundRect(x + 1, y + 1, size - 2, size - 2, 9, 9);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
            g.fillRoundRect(x + 4, y + 4, size - 8, size - 8, 7, 7);
            g.setColor(accent);
            g.setStroke(new BasicStroke(2f));
            int cx = x + size / 2;
            int cy = y + size / 2;
            switch (kind) {
                case SHIP -> {
                    Polygon ship = new Polygon();
                    ship.addPoint(cx, y + 7);
                    ship.addPoint(x + size - 8, y + size - 9);
                    ship.addPoint(cx, y + size - 15);
                    ship.addPoint(x + 8, y + size - 9);
                    g.drawPolygon(ship);
                    g.drawLine(cx, y + 9, cx, y + size - 16);
                }
                case STATION_PACKAGE -> {
                    Polygon hex = new Polygon();
                    for (int i = 0; i < 6; i++) {
                        double angle = Math.PI / 6 + i * Math.PI / 3;
                        hex.addPoint((int)Math.round(cx + Math.cos(angle) * 13),
                                (int)Math.round(cy + Math.sin(angle) * 13));
                    }
                    g.drawPolygon(hex);
                    g.drawOval(cx - 5, cy - 5, 10, 10);
                }
                case RESEARCH -> {
                    g.drawOval(cx - 13, cy - 6, 26, 12);
                    g.drawOval(cx - 6, cy - 13, 12, 26);
                    g.fillOval(cx - 3, cy - 3, 6, 6);
                }
                default -> {
                    g.drawRect(cx - 11, cy - 11, 22, 22);
                    g.drawLine(cx - 8, cy + 7, cx + 8, cy - 7);
                }
            }
            g.dispose();
        }
    }

    private static final class AnimatedProgress extends JComponent {
        private final double fraction;
        private final ProductionJobKind kind;
        private final boolean blocked;
        private final boolean active;

        AnimatedProgress(double fraction, ProductionJobKind kind, boolean blocked, boolean active) {
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.kind = kind;
            this.blocked = blocked;
            this.active = active;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g.setColor(new Color(12, 13, 14));
            g.fillRoundRect(0, 0, w, h, h, h);
            int fill = (int)Math.round(w * fraction);
            Color base = blocked ? BLOCKED : kindColor(kind);
            if (fill > 0) {
                g.setPaint(new GradientPaint(0, 0, base.darker(), Math.max(1, fill), 0, base.brighter()));
                g.fillRoundRect(0, 0, fill, h, h, h);
                if (active && !blocked && fill > 10) {
                    Shape oldClip = g.getClip();
                    g.clipRect(0, 0, fill, h);
                    long millis = System.nanoTime() / 1_000_000L;
                    int sweep = (int)((millis / 7) % Math.max(1, w + 40)) - 20;
                    g.setColor(new Color(255, 241, 199, 80));
                    g.setStroke(new BasicStroke(Math.max(2f, h * 0.45f)));
                    g.drawLine(sweep - 8, h, sweep + 8, 0);
                    g.setClip(oldClip);
                }
            }
            g.setColor(new Color(92, 86, 75));
            g.drawRoundRect(0, 0, Math.max(0, w - 1), Math.max(0, h - 1), h, h);
            g.dispose();
        }
    }
}
