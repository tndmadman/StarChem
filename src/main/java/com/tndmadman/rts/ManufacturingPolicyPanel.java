package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-game production-policy workspace embedded directly in ManufacturingOverlay. */
final class ManufacturingPolicyPanel extends JPanel {
    private static final Color BACKDROP = new Color(10, 11, 13);
    private static final Color SURFACE = new Color(22, 24, 27);
    private static final Color SURFACE_2 = new Color(29, 32, 35);
    private static final Color FIELD = new Color(16, 18, 20);
    private static final Color BORDER = new Color(83, 79, 69);
    private static final Color TEXT = new Color(235, 231, 220);
    private static final Color MUTED = new Color(163, 160, 150);
    private static final Color BRONZE = new Color(205, 151, 73);
    private static final Color READY = new Color(89, 163, 120);
    private static final Color WARNING = new Color(213, 166, 79);
    private static final Color BLOCKED = new Color(190, 81, 65);
    private static final int MAX_REPEAT_LIMIT = 100_000;

    private static final String CARD_BROWSER = "browser";
    private static final String CARD_EDITOR = "editor";

    private final World world;
    private final PeerNetwork network;
    private final Runnable backAction;
    private final Runnable closeAction;

    private final JComboBox<BaseOption> stationCombo = new JComboBox<>();
    private final JPanel policyRows = verticalPanel();
    private final JPanel templateRows = verticalPanel();
    private final JPanel orphanRows = verticalPanel();
    private final JLabel stationSummary = label(" ", MUTED, 10f, Font.PLAIN);
    private final JLabel policySummary = label(" ", MUTED, 10f, Font.PLAIN);
    private final JTextField templateName = new JTextField();
    private final CardLayout bodyLayout = new CardLayout();
    private final JPanel body = new JPanel(bodyLayout);
    private final JPanel editorHost = new JPanel(new BorderLayout());
    private final Timer refreshTimer;

    private String preferredBaseId = "";
    private boolean active;
    private boolean updatingStations;

    ManufacturingPolicyPanel(World world, PeerNetwork network, Runnable backAction, Runnable closeAction) {
        super(new BorderLayout(0, 10));
        this.world = world;
        this.network = network;
        this.backAction = backAction == null ? () -> { } : backAction;
        this.closeAction = closeAction == null ? () -> { } : closeAction;

        setBackground(SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(95, 79, 56), 2),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        setMinimumSize(new Dimension(960, 620));
        setPreferredSize(new Dimension(1280, 820));

        add(buildHeader(), BorderLayout.NORTH);
        body.setOpaque(false);
        body.add(buildBrowser(), CARD_BROWSER);
        editorHost.setOpaque(false);
        body.add(editorHost, CARD_EDITOR);
        add(body, BorderLayout.CENTER);

        stationCombo.addActionListener(event -> {
            if (updatingStations) return;
            BaseOption option = (BaseOption) stationCombo.getSelectedItem();
            if (option != null) preferredBaseId = option.base.id;
            refreshContents();
        });

        refreshTimer = new Timer(500, event -> {
            if (active && isShowing()) refreshContents();
        });
        refreshTimer.setCoalesce(true);
        showBrowser();
    }

    void activate(String baseId) {
        active = true;
        if (baseId != null && !baseId.isBlank()) preferredBaseId = baseId;
        showBrowser();
        refreshAll();
        if (!refreshTimer.isRunning()) refreshTimer.start();
    }

    void deactivate() {
        active = false;
        refreshTimer.stop();
        showBrowser();
    }

    void disposePanel() {
        active = false;
        refreshTimer.stop();
    }

    private JComponent buildHeader() {
        JPanel root = new JPanel(new BorderLayout(12, 8));
        root.setOpaque(false);

        JPanel heading = verticalPanel();
        JLabel title = label("PRODUCTION POLICY COMMAND", TEXT, 22f, Font.BOLD);
        JLabel subtitle = label("Standing automation • templates • reserve floors • recovery", MUTED, 11f, Font.PLAIN);
        heading.add(title);
        heading.add(Box.createVerticalStrut(2));
        heading.add(subtitle);
        root.add(heading, BorderLayout.WEST);

        JPanel station = new JPanel(new BorderLayout(7, 3));
        station.setOpaque(false);
        station.add(label("STATION", BRONZE, 10f, Font.BOLD), BorderLayout.NORTH);
        styleCombo(stationCombo);
        station.add(stationCombo, BorderLayout.CENTER);
        station.add(stationSummary, BorderLayout.SOUTH);
        root.add(station, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton back = button("← PRODUCTION", BORDER);
        back.addActionListener(event -> backAction.run());
        JButton close = button("CLOSE", BRONZE);
        close.addActionListener(event -> closeAction.run());
        actions.add(back);
        actions.add(close);
        root.add(actions, BorderLayout.EAST);
        return root;
    }

    private JComponent buildBrowser() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(SURFACE);
        tabs.setForeground(TEXT);
        tabs.addTab("POLICIES", buildPoliciesTab());
        tabs.addTab("TEMPLATES", buildTemplatesTab());
        tabs.addTab("RECOVERY", buildRecoveryTab());
        return tabs;
    }

    private JComponent buildPoliciesTab() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.setOpaque(false);
        JButton create = button("CREATE POLICY", READY);
        create.addActionListener(event -> openEditor(null));
        JButton refresh = button("REFRESH", BORDER);
        refresh.addActionListener(event -> refreshAll());
        toolbar.add(create);
        toolbar.add(refresh);
        toolbar.add(policySummary);
        root.add(toolbar, BorderLayout.NORTH);
        root.add(scroll(policyRows), BorderLayout.CENTER);
        return root;
    }

    private JComponent buildTemplatesTab() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel save = new JPanel(new BorderLayout(7, 3));
        save.setOpaque(false);
        save.add(label("Save every policy at the selected station as a reusable template", MUTED, 10f, Font.PLAIN),
                BorderLayout.NORTH);
        styleField(templateName);
        templateName.setToolTipText("Template name");
        save.add(templateName, BorderLayout.CENTER);
        JButton saveButton = button("SAVE STATION TEMPLATE", BRONZE);
        saveButton.addActionListener(event -> saveTemplate());
        save.add(saveButton, BorderLayout.EAST);
        root.add(save, BorderLayout.NORTH);
        root.add(scroll(templateRows), BorderLayout.CENTER);
        return root;
    }

    private JComponent buildRecoveryTab() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(label("Policies whose station was lost can be reassigned to the selected compatible station.",
                MUTED, 11f, Font.PLAIN), BorderLayout.NORTH);
        root.add(scroll(orphanRows), BorderLayout.CENTER);
        return root;
    }

    private void showBrowser() {
        bodyLayout.show(body, CARD_BROWSER);
    }

    private void refreshAll() {
        refreshStations();
        refreshContents();
    }

    private void refreshStations() {
        Base before = selectedBase();
        String keep = before == null ? preferredBaseId : before.id;
        List<Base> bases = ownedProductionBases();
        DefaultComboBoxModel<BaseOption> model = new DefaultComboBoxModel<>();
        for (Base base : bases) model.addElement(new BaseOption(base));

        updatingStations = true;
        try {
            stationCombo.setModel(model);
            if (model.getSize() == 0) {
                preferredBaseId = "";
                stationSummary.setText("No owned production station in this system");
                return;
            }
            int selected = 0;
            for (int i = 0; i < model.getSize(); i++) {
                if (model.getElementAt(i).base.id.equals(keep)) {
                    selected = i;
                    break;
                }
            }
            stationCombo.setSelectedIndex(selected);
            preferredBaseId = model.getElementAt(selected).base.id;
        } finally {
            updatingStations = false;
        }
    }

    private void refreshContents() {
        Base base = selectedBase();
        refreshPolicies(base);
        refreshTemplates(base);
        refreshOrphans(base);
    }

    private void refreshPolicies(Base base) {
        policyRows.removeAll();
        if (base == null) {
            addMessage(policyRows, "Select an owned production station.");
            policySummary.setText(" ");
            finish(policyRows);
            return;
        }
        List<ProductionPolicySystem.PolicyView> policies = ProductionPolicySystem.viewsForBase(world, base);
        stationSummary.setText(base.type().name + " " + base.id + " • " + base.productionQueue.size() + " queued");
        policySummary.setText(policies.size() + " polic" + (policies.size() == 1 ? "y" : "ies"));
        if (policies.isEmpty()) addMessage(policyRows, "No standing policies at this station.");
        for (ProductionPolicySystem.PolicyView policy : policies) {
            policyRows.add(policyCard(base, policy));
            policyRows.add(Box.createVerticalStrut(6));
        }
        policyRows.add(Box.createVerticalGlue());
        finish(policyRows);
    }

    private JComponent policyCard(Base base, ProductionPolicySystem.PolicyView policy) {
        JPanel card = verticalPanel();
        Color edge = policy.enabled() ? statusColor(policy.status()) : BORDER;
        card.setBackground(SURFACE_2);
        card.setOpaque(true);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(edge), BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 118));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(label(policy.id() + "  •  " + readable(policy.type()) + "  •  " + policyItemLabel(policy),
                TEXT, 11f, Font.BOLD), BorderLayout.WEST);
        top.add(label(policy.enabled() ? policy.status().name().replace('_', ' ') : "PAUSED",
                edge, 10f, Font.BOLD), BorderLayout.EAST);
        card.add(top);

        if (!policy.reason().isBlank()) card.add(label(policy.reason(), statusColor(policy.status()), 10f, Font.PLAIN));
        String detail = "target " + whole(policy.targetAmount()) + " • batch " + policy.batchSize()
                + " • priority " + policy.priority() + " • outstanding " + policy.maxOutstandingJobs()
                + (policy.type() == ProductionPolicySystem.PolicyType.REPEAT
                ? " • completed " + policy.completedBatches()
                + (policy.repeatLimit() > 0 ? "/" + policy.repeatLimit() : " • unlimited") : "")
                + " • active jobs " + policy.jobIds().size();
        card.add(label(detail, MUTED, 10f, Font.PLAIN));
        card.add(Box.createVerticalStrut(4));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        JButton toggle = miniButton(policy.enabled() ? "PAUSE" : "RESUME");
        toggle.addActionListener(event -> sendPolicy(base, ProductionPolicySystem.COMMAND_TOGGLE,
                policy.id() + "~" + (policy.enabled() ? "0" : "1")));
        JButton edit = miniButton("EDIT");
        edit.addActionListener(event -> openEditor(policy));
        JButton up = miniButton("↑");
        up.addActionListener(event -> sendPolicy(base, ProductionPolicySystem.COMMAND_MOVE_UP, policy.id()));
        JButton down = miniButton("↓");
        down.addActionListener(event -> sendPolicy(base, ProductionPolicySystem.COMMAND_MOVE_DOWN, policy.id()));
        JButton delete = miniButton("DELETE");
        delete.setForeground(new Color(240, 155, 143));
        delete.addActionListener(event -> sendPolicy(base, ProductionPolicySystem.COMMAND_DELETE, policy.id()));
        actions.add(toggle);
        actions.add(edit);
        actions.add(up);
        actions.add(down);
        actions.add(delete);
        card.add(actions);
        return card;
    }

    private void refreshTemplates(Base base) {
        templateRows.removeAll();
        if (base == null) {
            addMessage(templateRows, "Select a production station to manage templates.");
            finish(templateRows);
            return;
        }

        JLabel starterTitle = label("BUILT-IN STARTERS", BRONZE, 11f, Font.BOLD);
        templateRows.add(starterTitle);
        templateRows.add(Box.createVerticalStrut(4));
        List<ProductionPolicyStarterTemplates.StarterView> starters = ProductionPolicyStarterTemplates.viewsFor(base);
        if (starters.isEmpty()) addMessage(templateRows, "No starter templates fit this station.");
        for (ProductionPolicyStarterTemplates.StarterView starter : starters) {
            templateRows.add(templateRow(starter.name(), starter.entryCount(), "APPLY",
                    () -> sendPolicy(base, ProductionPolicyStarterTemplates.COMMAND_APPLY, starter.id()), null));
            templateRows.add(Box.createVerticalStrut(4));
        }

        templateRows.add(Box.createVerticalStrut(10));
        templateRows.add(label("SAVED TEMPLATES", BRONZE, 11f, Font.BOLD));
        templateRows.add(Box.createVerticalStrut(4));
        List<ProductionPolicySystem.TemplateView> saved = ProductionPolicySystem.templateViews(world, base);
        if (saved.isEmpty()) addMessage(templateRows, "No saved production templates.");
        for (ProductionPolicySystem.TemplateView template : saved) {
            templateRows.add(templateRow(template.name() + "  [" + template.id() + "]", template.entryCount(),
                    "APPLY", () -> sendPolicy(base, ProductionPolicySystem.COMMAND_TEMPLATE_APPLY, template.id()),
                    () -> sendPolicy(base, ProductionPolicySystem.COMMAND_TEMPLATE_DELETE, template.id())));
            templateRows.add(Box.createVerticalStrut(4));
        }
        templateRows.add(Box.createVerticalGlue());
        finish(templateRows);
    }

    private JComponent templateRow(String name, int count, String actionText, Runnable action, Runnable delete) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(SURFACE_2);
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        row.add(label(name + "  •  " + count + " polic" + (count == 1 ? "y" : "ies"),
                TEXT, 10.5f, Font.PLAIN), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        JButton apply = miniButton(actionText);
        apply.addActionListener(event -> action.run());
        actions.add(apply);
        if (delete != null) {
            JButton remove = miniButton("DELETE");
            remove.addActionListener(event -> delete.run());
            actions.add(remove);
        }
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private void refreshOrphans(Base base) {
        orphanRows.removeAll();
        List<ProductionPolicyRecoveryBridge.OrphanView> orphans =
                ProductionPolicyRecoveryBridge.orphanViews(world, playerId());
        if (orphans.isEmpty()) addMessage(orphanRows, "No orphaned production policies.");
        for (ProductionPolicyRecoveryBridge.OrphanView orphan : orphans) {
            JPanel row = verticalPanel();
            row.setOpaque(true);
            row.setBackground(SURFACE_2);
            row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BLOCKED),
                    BorderFactory.createEmptyBorder(7, 9, 7, 9)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 94));
            row.add(label(orphan.id() + "  •  " + readable(orphan.type()) + "  •  " + orphan.itemId(),
                    TEXT, 11f, Font.BOLD));
            row.add(label("Lost station " + orphan.stationId() + " • target " + whole(orphan.targetAmount()),
                    MUTED, 10f, Font.PLAIN));
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            actions.setOpaque(false);
            JButton recover = miniButton("REASSIGN TO SELECTED STATION");
            recover.setEnabled(base != null && compatible(base, orphan.kind(), orphan.itemId()));
            recover.addActionListener(event -> {
                if (base != null) sendPolicy(base, ProductionPolicyRecoveryBridge.COMMAND_RECOVER_HERE, orphan.id());
            });
            JButton delete = miniButton("DELETE ORPHAN");
            delete.addActionListener(event -> {
                Base commandBase = base != null ? base : firstOwnedProductionBase();
                if (commandBase != null) sendPolicy(commandBase,
                        ProductionPolicyRecoveryBridge.COMMAND_DELETE_ORPHAN, orphan.id());
            });
            actions.add(recover);
            actions.add(delete);
            row.add(actions);
            orphanRows.add(row);
            orphanRows.add(Box.createVerticalStrut(6));
        }
        orphanRows.add(Box.createVerticalGlue());
        finish(orphanRows);
    }

    private void saveTemplate() {
        Base base = selectedBase();
        if (base == null) return;
        String name = templateName.getText().trim();
        if (name.isBlank()) {
            world.status = "Enter a template name first.";
            return;
        }
        sendPolicy(base, ProductionPolicySystem.COMMAND_TEMPLATE_SAVE, name);
        templateName.setText("");
    }

    private void openEditor(ProductionPolicySystem.PolicyView existing) {
        Base base = selectedBase();
        if (base == null) return;
        List<ItemChoice> choices = itemChoices(base);
        if (choices.isEmpty()) {
            world.status = base.type().name + " has no outputs that support standing policies.";
            return;
        }
        editorHost.removeAll();
        editorHost.add(buildEditor(base, choices, existing), BorderLayout.CENTER);
        editorHost.revalidate();
        editorHost.repaint();
        bodyLayout.show(body, CARD_EDITOR);
    }

    private JComponent buildEditor(Base base, List<ItemChoice> choices, ProductionPolicySystem.PolicyView existing) {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(SURFACE);
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BRONZE),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));

        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        heading.add(label(existing == null ? "CREATE PRODUCTION POLICY" : "EDIT " + existing.id(),
                TEXT, 18f, Font.BOLD), BorderLayout.WEST);
        JButton back = button("← POLICIES", BORDER);
        back.addActionListener(event -> showBrowser());
        heading.add(back, BorderLayout.EAST);
        root.add(heading, BorderLayout.NORTH);

        JComboBox<ItemChoice> item = new JComboBox<>(choices.toArray(ItemChoice[]::new));
        styleCombo(item);
        JComboBox<ProductionPolicySystem.PolicyType> type = new JComboBox<>();
        styleCombo(type);
        JTextField target = new JTextField(existing == null ? "10" : whole(existing.targetAmount()));
        styleField(target);
        JSpinner batch = spinner(existing == null ? 1 : existing.batchSize(), 1, ProductionPolicySystem.MAX_BATCH_SIZE);
        JSpinner priority = spinner(existing == null ? 50 : existing.priority(), 0, 100);
        JSpinner maxOutstanding = spinner(existing == null ? 2 : existing.maxOutstandingJobs(),
                1, ProductionPolicySystem.MAX_OUTSTANDING_PER_POLICY);
        JSpinner repeatLimit = spinner(existing == null ? 0 : existing.repeatLimit(), 0, MAX_REPEAT_LIMIT);
        JTextField stationReserve = new JTextField(existing == null ? "" : reserveTextFor(existing.id(), "stationReserve"));
        JTextField networkReserve = new JTextField(existing == null ? "" : reserveTextFor(existing.id(), "networkReserve"));
        styleField(stationReserve);
        styleField(networkReserve);
        JCheckBox replaceReserves = new JCheckBox("Replace reserve floors with these values");
        replaceReserves.setOpaque(false);
        replaceReserves.setForeground(TEXT);
        replaceReserves.setSelected(existing == null);
        stationReserve.setEnabled(existing == null);
        networkReserve.setEnabled(existing == null);
        replaceReserves.addActionListener(event -> {
            stationReserve.setEnabled(replaceReserves.isSelected());
            networkReserve.setEnabled(replaceReserves.isSelected());
        });

        if (existing != null) selectExisting(item, existing);
        Runnable refreshTypes = () -> {
            ItemChoice selected = (ItemChoice) item.getSelectedItem();
            ProductionPolicySystem.PolicyType wanted = existing != null && selected != null && selected.matches(existing)
                    ? existing.type() : null;
            DefaultComboBoxModel<ProductionPolicySystem.PolicyType> model = new DefaultComboBoxModel<>();
            if (selected != null && selected.kind == ProductionJobKind.SHIP) {
                model.addElement(ProductionPolicySystem.PolicyType.MAINTAIN_FLEET);
            } else {
                model.addElement(ProductionPolicySystem.PolicyType.MAINTAIN_STOCK);
                model.addElement(ProductionPolicySystem.PolicyType.REPEAT);
            }
            type.setModel(model);
            if (wanted != null) type.setSelectedItem(wanted);
            boolean repeat = type.getSelectedItem() == ProductionPolicySystem.PolicyType.REPEAT;
            target.setEnabled(!repeat);
            repeatLimit.setEnabled(repeat);
        };
        item.addActionListener(event -> refreshTypes.run());
        type.addActionListener(event -> {
            boolean repeat = type.getSelectedItem() == ProductionPolicySystem.PolicyType.REPEAT;
            target.setEnabled(!repeat);
            repeatLimit.setEnabled(repeat);
        });
        refreshTypes.run();

        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 7));
        fields.setOpaque(false);
        addField(fields, "Output / loadout", item);
        addField(fields, "Policy type", type);
        addField(fields, "Target amount / fleet size", target);
        addField(fields, "Batch size", batch);
        addField(fields, "Priority (0-100)", priority);
        addField(fields, "Max outstanding jobs", maxOutstanding);
        addField(fields, "Repeat limit (0 = unlimited)", repeatLimit);
        addField(fields, "Station reserve floors", stationReserve);
        addField(fields, "Network reserve floors", networkReserve);

        JPanel editorContent = verticalPanel();
        editorContent.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        editorContent.add(fields);
        editorContent.add(Box.createVerticalStrut(8));
        editorContent.add(label("Reserve format: IRON:50,COPPER:25 • blank means no reserve floor", MUTED, 10f, Font.PLAIN));
        if (existing != null) editorContent.add(replaceReserves);
        editorContent.add(Box.createVerticalGlue());
        root.add(scroll(editorContent), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton cancel = button("CANCEL", BORDER);
        cancel.addActionListener(event -> showBrowser());
        JButton save = button(existing == null ? "CREATE POLICY" : "SAVE POLICY", READY);
        save.addActionListener(event -> {
            if (saveEditor(base, existing, item, type, target, batch, priority, maxOutstanding,
                    repeatLimit, stationReserve, networkReserve, replaceReserves.isSelected())) {
                showBrowser();
                refreshAll();
            }
        });
        actions.add(cancel);
        actions.add(save);
        root.add(actions, BorderLayout.SOUTH);
        return root;
    }

    private boolean saveEditor(Base base, ProductionPolicySystem.PolicyView existing,
                               JComboBox<ItemChoice> itemBox,
                               JComboBox<ProductionPolicySystem.PolicyType> typeBox,
                               JTextField targetField, JSpinner batch, JSpinner priority,
                               JSpinner maxOutstanding, JSpinner repeatLimit,
                               JTextField stationReserveField, JTextField networkReserveField,
                               boolean replaceReserves) {
        ItemChoice item = (ItemChoice) itemBox.getSelectedItem();
        ProductionPolicySystem.PolicyType type = (ProductionPolicySystem.PolicyType) typeBox.getSelectedItem();
        if (item == null || type == null) return false;
        if (item.kind == ProductionJobKind.SHIP && type != ProductionPolicySystem.PolicyType.MAINTAIN_FLEET) {
            world.status = "Ship policies use Maintain Fleet. Repeat ship production is disabled.";
            return false;
        }
        double target = type == ProductionPolicySystem.PolicyType.REPEAT ? 0 : positive(targetField.getText());
        if (type != ProductionPolicySystem.PolicyType.REPEAT && target <= 0) {
            world.status = "Policy target must be greater than zero.";
            return false;
        }

        EnumMap<Material, Double> stationReserve = new EnumMap<>(Material.class);
        EnumMap<Material, Double> networkReserve = new EnumMap<>(Material.class);
        if (existing == null || replaceReserves) {
            if (!parseReserves(stationReserveField.getText(), stationReserve)
                    || !parseReserves(networkReserveField.getText(), networkReserve)) {
                world.status = "Reserve floors must use MATERIAL:AMOUNT pairs, for example IRON:50,COPPER:25.";
                return false;
            }
        }

        String encoded = ProductionPolicyWire.encodeSpec(existing == null ? "" : existing.id(), type,
                item.kind, item.itemId, item.loadoutId, target,
                ((Number) batch.getValue()).intValue(), ((Number) priority.getValue()).intValue(),
                ((Number) maxOutstanding.getValue()).intValue(),
                type == ProductionPolicySystem.PolicyType.REPEAT ? ((Number) repeatLimit.getValue()).intValue() : 0,
                stationReserve, networkReserve);
        String command = existing == null ? ProductionPolicySystem.COMMAND_CREATE
                : replaceReserves ? ProductionPolicySystem.COMMAND_UPDATE
                : ProductionPolicyCommandBridge.COMMAND_UPDATE_KEEP_RESERVES;
        sendPolicy(base, command, encoded);
        return true;
    }

    private void sendPolicy(Base base, String command, String payload) {
        if (base == null) return;
        if (network == null) {
            boolean accepted = ProductionCommands.apply(world, base.playerId, "POLICY", base.id, command, payload);
            if (!accepted && (world.status == null || world.status.isBlank())) {
                world.status = "Production policy request rejected.";
            }
        } else {
            network.production(base.playerId, "POLICY", base.id, command, payload);
            world.status = "Requested production policy update from server.";
        }
        Timer oneShot = new Timer(network == null ? 30 : 250, event -> refreshAll());
        oneShot.setRepeats(false);
        oneShot.start();
    }

    private List<ItemChoice> itemChoices(Base base) {
        List<ItemChoice> out = new ArrayList<>();
        for (String hullId : base.type().buildableShips) {
            ShipType ship = Rules.findShip(hullId);
            if (ship == null) continue;
            List<ShipLoadoutDefinition> loadouts = new ArrayList<>(WeaponRules.loadoutsForHull(hullId));
            ShipLoadoutDefinition fallback = WeaponRules.defaultLoadout(hullId);
            if (loadouts.isEmpty() && fallback != null) loadouts.add(fallback);
            for (ShipLoadoutDefinition loadout : loadouts) {
                if (loadout == null) continue;
                String variant = loadouts.size() > 1 ? " — " + loadout.displayName() : "";
                out.add(new ItemChoice(ProductionJobKind.SHIP, hullId, loadout.id(),
                        "SHIP • " + ship.name + variant));
            }
        }
        for (CraftableItem craftable : CraftingRules.forStation(base.typeId)) {
            if (craftable == null) continue;
            out.add(new ItemChoice(ProductionJobKind.CRAFTABLE, craftable.id, "",
                    "MATERIAL • " + craftable.name));
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void selectExisting(JComboBox<ItemChoice> combo, ProductionPolicySystem.PolicyView existing) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            ItemChoice choice = combo.getItemAt(i);
            if (choice.matches(existing)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private String reserveTextFor(String policyId, String key) {
        Map<String, Object> snapshot = ProductionPolicySystem.capture(world);
        for (Object item : ServerSaveStore.list(snapshot.get("policies"))) {
            Map<String, Object> row = ServerSaveStore.object(item);
            if (!policyId.equals(ServerSaveStore.string(row, "id", ""))) continue;
            return reserveText(ServerSaveStore.restoreMaterialMap(row.get(key)));
        }
        return "";
    }

    private static String reserveText(Map<Material, Double> values) {
        if (values == null || values.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Material material : Material.values()) {
            double amount = values.getOrDefault(material, 0.0);
            if (amount > 0.0001) parts.add(material.name() + ":" + whole(amount));
        }
        return String.join(",", parts);
    }

    private static boolean parseReserves(String text, EnumMap<Material, Double> out) {
        out.clear();
        if (text == null || text.trim().isEmpty()) return true;
        for (String raw : text.split(",")) {
            String[] pair = raw.trim().split(":", 2);
            if (pair.length != 2) return false;
            try {
                Material material = Material.valueOf(pair[0].trim().toUpperCase(Locale.ROOT));
                double amount = Double.parseDouble(pair[1].trim());
                if (!Double.isFinite(amount) || amount < 0 || amount > 1_000_000) return false;
                if (amount > 0.0001) out.put(material, amount);
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    private List<Base> ownedProductionBases() {
        List<Base> out = new ArrayList<>();
        String ownerId = playerId();
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && ownerId.equals(base.playerId)
                    && !StationControls.nonProduction(base.typeId)) out.add(base);
        }
        out.sort((a, b) -> {
            int byType = a.type().name.compareToIgnoreCase(b.type().name);
            return byType != 0 ? byType : a.id.compareTo(b.id);
        });
        return out;
    }

    private Base selectedBase() {
        BaseOption option = (BaseOption) stationCombo.getSelectedItem();
        return option == null ? null : option.base;
    }

    private Base firstOwnedProductionBase() {
        List<Base> bases = ownedProductionBases();
        return bases.isEmpty() ? null : bases.get(0);
    }

    private String playerId() {
        if (network != null) {
            String id = network.localPlayerId();
            if (id != null && !id.isBlank()) return id;
        }
        return PlayerRegistry.localId();
    }

    private String policyItemLabel(ProductionPolicySystem.PolicyView policy) {
        if (policy.kind() == ProductionJobKind.SHIP) {
            ShipType ship = Rules.findShip(policy.itemId());
            ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, policy.itemId(), policy.loadoutId());
            return (ship == null ? policy.itemId() : ship.name)
                    + (loadout == null ? "" : " — " + loadout.displayName());
        }
        CraftableItem item = CraftingRules.item(policy.itemId());
        return item == null ? policy.itemId() : item.name;
    }

    private static boolean compatible(Base base, ProductionJobKind kind, String itemId) {
        if (base == null || kind == null || itemId == null) return false;
        if (kind == ProductionJobKind.SHIP) return base.type().buildableShips.contains(itemId);
        if (kind == ProductionJobKind.CRAFTABLE) {
            CraftableItem item = CraftingRules.item(itemId);
            return item != null && item.canCraftAt(base.typeId);
        }
        return false;
    }

    private static Color statusColor(ProductionPolicySystem.PolicyStatus status) {
        if (status == null) return MUTED;
        return switch (status) {
            case SATISFIED -> READY;
            case PRODUCING -> BRONZE;
            case WAITING_FOR_RESOURCES, RESERVE_PROTECTED -> WARNING;
            case BLOCKED_RESEARCH, NO_COMPATIBLE_STATION, ORPHANED -> BLOCKED;
            case PAUSED -> MUTED;
        };
    }

    private static String readable(ProductionPolicySystem.PolicyType type) {
        return type == null ? "POLICY" : type.name().replace('_', ' ');
    }

    private static double positive(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) && value > 0 && value <= 1_000_000 ? value : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static JSpinner spinner(int value, int min, int max) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            defaultEditor.getTextField().setBackground(FIELD);
            defaultEditor.getTextField().setForeground(TEXT);
            defaultEditor.getTextField().setCaretColor(TEXT);
        }
        return spinner;
    }

    private static void addField(JPanel panel, String name, JComponent component) {
        panel.add(label(name, MUTED, 10.5f, Font.PLAIN));
        panel.add(component);
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JScrollPane scroll(JPanel panel) {
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(58, 56, 52)));
        scroll.getViewport().setBackground(FIELD);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        return scroll;
    }

    private static void finish(JPanel panel) {
        panel.revalidate();
        panel.repaint();
    }

    private static void addMessage(JPanel panel, String text) {
        JLabel message = label(text, MUTED, 11f, Font.PLAIN);
        message.setBorder(BorderFactory.createEmptyBorder(10, 8, 8, 8));
        panel.add(message);
    }

    private static JLabel label(String text, Color color, float size, int style) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style, size));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JButton button(String text, Color edge) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(52, 47, 39));
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        return button;
    }

    private static JButton miniButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(48, 46, 42));
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(78, 72, 62)),
                BorderFactory.createEmptyBorder(3, 7, 3, 7)));
        button.setMargin(new Insets(0, 0, 0, 0));
        return button;
    }

    private static void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(7, 8, 7, 8)));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(TEXT);
        combo.setBackground(FIELD);
        combo.setBorder(BorderFactory.createLineBorder(BORDER));
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record BaseOption(Base base) {
        @Override public String toString() {
            return base.type().name + " " + base.id + "  •  queue " + base.productionQueue.size();
        }
    }

    private static final class ItemChoice {
        final ProductionJobKind kind;
        final String itemId;
        final String loadoutId;
        final String label;

        ItemChoice(ProductionJobKind kind, String itemId, String loadoutId, String label) {
            this.kind = kind;
            this.itemId = itemId == null ? "" : itemId;
            this.loadoutId = loadoutId == null ? "" : loadoutId;
            this.label = label == null ? this.itemId : label;
        }

        boolean matches(ProductionPolicySystem.PolicyView policy) {
            if (policy == null || policy.kind() != kind || !policy.itemId().equals(itemId)) return false;
            return kind != ProductionJobKind.SHIP || policy.loadoutId().equals(loadoutId)
                    || policy.loadoutId().isBlank();
        }

        @Override public String toString() { return label; }
    }
}
