package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Player fitting manager: built-in defaults, private commander fits, and server-published fits. */
final class ShipFittingWindow {
    private static final Color BACKGROUND = new Color(5, 12, 20);
    private static final Color PANEL = new Color(10, 24, 36);
    private static final Color BORDER = new Color(78, 173, 226);
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(177, 207, 224);
    private static final Color GOOD = new Color(125, 226, 166);
    private static final Color WARNING = new Color(255, 198, 104);
    private static final Color BAD = new Color(255, 126, 126);

    private JDialog dialog;
    private Component parent;
    private World world;
    private PeerNetwork network;
    private Unit unit;

    void showForUnit(Component parent, World world, PeerNetwork network, Unit unit) {
        if (parent == null || world == null || unit == null) return;
        close();
        this.parent = parent;
        this.world = world;
        this.network = network;
        this.unit = unit;
        Window owner = SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner, "Ship Fitting - " + unit.type().name + " #" + unit.unitId,
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (dialog == event.getWindow()) dialog = null;
            }
        });
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        rebuild();
        dialog.setMinimumSize(new Dimension(860, 620));
        dialog.setSize(980, 760);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        FitNetworkBridge.refresh(network, world);
    }

    void close() {
        if (dialog != null) dialog.dispose();
        dialog = null;
        parent = null;
        world = null;
        network = null;
        unit = null;
    }

    boolean visible() { return dialog != null && dialog.isVisible(); }

    private void rebuild() {
        if (dialog == null || world == null || unit == null) return;
        dialog.setContentPane(content());
        dialog.revalidate();
        dialog.repaint();
    }

    private JComponent content() {
        JPanel root = panel(new BorderLayout(0, 10), BACKGROUND);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        root.add(header(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("GAME FITS", gameFitsTab());
        tabs.addTab("MY FITS", myFitsTab());
        tabs.addTab("SERVER FITS", serverFitsTab());
        root.add(tabs, BorderLayout.CENTER);
        root.add(stationStatus(world, unit), BorderLayout.SOUTH);
        return root;
    }

    private JComponent header() {
        JPanel header = panel(new BorderLayout(12, 8), PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(10, 12, 10, 12)));
        ShipLoadoutDefinition current = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);
        JPanel text = panel(new GridLayout(0, 1, 0, 3), PANEL);
        text.add(label(unit.type().name + " #" + unit.unitId + "  |  FITTING MANAGER", 18, Font.BOLD, TEXT));
        text.add(label("Installed: " + (current == null ? unit.loadoutId : current.displayName()), 12, Font.BOLD, GOOD));
        text.add(label(current == null ? "Weapons unavailable" : weaponSummary(current), 11, Font.PLAIN, MUTED));
        header.add(text, BorderLayout.CENTER);

        ActiveRefit active = activeRefit(world, unit);
        JPanel state = panel(new GridLayout(0, 1, 0, 4), PANEL);
        state.add(label("State: " + (active != null ? "REFITTING" : readyState(unit) ? "READY" : "NOT READY"),
                11, Font.BOLD, active != null ? WARNING : readyState(unit) ? GOOD : WARNING));
        if (active != null) {
            ShipLoadoutDefinition target = WeaponRules.findLoadout(active.job.loadoutId);
            state.add(label("Queued: " + (target == null ? active.job.loadoutId : target.displayName()), 11, Font.BOLD, WARNING));
            JButton cancel = button("CANCEL REFIT");
            cancel.addActionListener(event -> {
                sendProduction(active.base.playerId, "CANCEL", active.base.id, active.job.id, "");
                close();
            });
            state.add(cancel);
        }
        header.add(state, BorderLayout.EAST);
        return header;
    }

    private JComponent gameFitsTab() {
        JPanel list = verticalList();
        List<ShipLoadoutDefinition> variants = new ArrayList<>(WeaponRules.loadoutsForHull(unit.shipTypeId));
        variants.sort(Comparator.comparing((ShipLoadoutDefinition fit) -> !fit.defaultForHull())
                .thenComparing(ShipLoadoutDefinition::displayName));
        for (ShipLoadoutDefinition fit : variants) {
            JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 7, 0), PANEL);
            JButton refit = button("REFIT SELECTED");
            FittingOption option = evaluate(world, unit, fit);
            refit.setEnabled(option.ready());
            refit.addActionListener(event -> {
                Base base = refitBaseInRange(world, unit);
                if (base == null) { showError("Move the selected ship into an owned shipyard's refit range first."); return; }
                sendProduction(unit.playerId, "REFIT", base.id, unit.key(), fit.id());
                close();
            });
            JButton copy = button("COPY TO MY FITS");
            copy.addActionListener(event -> runAndRefresh(() -> ClientFitStore.save(commanderName(), "",
                    fit.displayName() + " Copy", new ShipFitSpec(fit.hullId(), fit.weaponIds()))));
            actions.add(copy);
            actions.add(refit);
            list.add(fitCard(fit.displayName(), fit, fit.defaultForHull() ? "BUILT-IN DEFAULT" : "BUILT-IN", actions));
            list.add(Box.createVerticalStrut(8));
        }
        return scroll(list);
    }

    private JComponent myFitsTab() {
        String commander = commanderName();
        List<PrivateShipFit> fits = ClientFitStore.fits(commander, unit.shipTypeId);
        PrivateShipFit standard = ClientFitStore.standard(commander, unit.shipTypeId);

        JPanel root = panel(new BorderLayout(10, 10), BACKGROUND);
        JPanel editor = panel(new BorderLayout(10, 10), PANEL);
        editor.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), new EmptyBorder(10, 10, 10, 10)));

        JComboBox<PrivateChoice> fitChoice = new JComboBox<>();
        fitChoice.addItem(new PrivateChoice(null));
        for (PrivateShipFit fit : fits) fitChoice.addItem(new PrivateChoice(fit));
        JTextField name = new JTextField(28);
        styleField(name);

        JPanel top = panel(new FlowLayout(FlowLayout.LEFT, 8, 0), PANEL);
        top.add(label("Saved fit", 11, Font.BOLD, MUTED));
        top.add(fitChoice);
        top.add(label("Name", 11, Font.BOLD, MUTED));
        top.add(name);
        editor.add(top, BorderLayout.NORTH);

        List<JComboBox<WeaponChoice>> slots = new ArrayList<>();
        JPanel slotPanel = panel(new GridLayout(0, 2, 8, 7), PANEL);
        int slotCount = PlayerFitRules.slotCount(unit.shipTypeId);
        List<WeaponType> allowed = PlayerFitRules.allowedWeapons(unit.shipTypeId);
        for (int i = 0; i < slotCount; i++) {
            slotPanel.add(label("Weapon slot " + (i + 1), 11, Font.BOLD, TEXT));
            JComboBox<WeaponChoice> combo = new JComboBox<>();
            combo.addItem(new WeaponChoice("", "Empty"));
            for (WeaponType weapon : allowed) combo.addItem(new WeaponChoice(weapon.id, weapon.name));
            slots.add(combo);
            slotPanel.add(combo);
        }
        editor.add(slotPanel, BorderLayout.CENTER);

        JTextArea preview = new JTextArea(5, 54);
        preview.setEditable(false);
        preview.setFocusable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setForeground(MUTED);
        preview.setBackground(BACKGROUND);
        preview.setBorder(new EmptyBorder(7, 7, 7, 7));

        Runnable updatePreview = () -> {
            ShipFitSpec spec = specFrom(unit.shipTypeId, slots);
            PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
            if (!validation.valid()) {
                preview.setText(validation.reason());
                return;
            }
            ShipLoadoutDefinition definition = PlayerFitRules.definition(name.getText(), spec);
            preview.setText("Weapons: " + weaponSummary(definition) + "\nRange: " + whole(WeaponRules.maxRange(definition))
                    + "  |  Refit time: " + whole(definition.refitTimeSeconds()) + "s\nRefit cost: "
                    + (definition.refitCost().isEmpty() ? "None" : Rules.formatCost(definition.refitCost()))
                    + (definition.requiredResearch().isEmpty() ? "" : "\nResearch: " + String.join(", ", definition.requiredResearch())));
        };
        for (JComboBox<WeaponChoice> slot : slots) slot.addActionListener(event -> updatePreview.run());
        name.getDocument().addDocumentListener(new SimpleDocumentListener(updatePreview));

        fitChoice.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            PrivateShipFit selected = choice == null ? null : choice.fit;
            name.setText(selected == null ? "" : selected.name());
            loadSpec(slots, selected == null ? new ShipFitSpec(unit.shipTypeId, List.of()) : selected.spec());
            updatePreview.run();
        });

        JPanel buttons = panel(new FlowLayout(FlowLayout.LEFT, 7, 0), PANEL);
        JButton saveNew = button("SAVE NEW");
        saveNew.addActionListener(event -> runAndRefresh(() -> ClientFitStore.save(commander, "", name.getText(), specFrom(unit.shipTypeId, slots))));
        JButton saveChanges = button("SAVE CHANGES");
        saveChanges.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            if (choice == null || choice.fit == null) { showError("Select a saved private fit first."); return; }
            runAndRefresh(() -> ClientFitStore.save(commander, choice.fit.id(), name.getText(), specFrom(unit.shipTypeId, slots)));
        });
        JButton delete = button("DELETE");
        delete.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            if (choice == null || choice.fit == null) { showError("Select a saved private fit first."); return; }
            runAndRefresh(() -> ClientFitStore.delete(commander, choice.fit.id()));
        });
        JButton setStandard = button("SET CLASS STANDARD");
        setStandard.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            if (choice == null || choice.fit == null) { showError("Save and select the fit first."); return; }
            runAndRefresh(() -> ClientFitStore.setStandard(commander, unit.shipTypeId, choice.fit.id()));
        });
        JButton clearStandard = button("CLEAR STANDARD");
        clearStandard.setEnabled(standard != null);
        clearStandard.addActionListener(event -> runAndRefresh(() -> ClientFitStore.setStandard(commander, unit.shipTypeId, "")));
        buttons.add(saveNew); buttons.add(saveChanges); buttons.add(delete); buttons.add(setStandard); buttons.add(clearStandard);

        JPanel actions = panel(new FlowLayout(FlowLayout.LEFT, 7, 0), PANEL);
        JButton refit = button("REFIT SELECTED");
        refit.addActionListener(event -> submitRefit(name.getText(), specFrom(unit.shipTypeId, slots), false));
        JButton refitClass = button("REFIT ALL ELIGIBLE " + unit.type().name.toUpperCase(Locale.ROOT) + "S");
        refitClass.addActionListener(event -> submitRefit(name.getText(), specFrom(unit.shipTypeId, slots), true));
        JButton publish = button("PUBLISH TO SERVER");
        publish.addActionListener(event -> {
            ShipFitSpec spec = specFrom(unit.shipTypeId, slots);
            PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
            if (!validation.valid()) { showError(validation.reason()); return; }
            FitNetworkBridge.submit(network, world, "PUBLISH", name.getText(), spec, null, null, null);
            rebuild();
        });
        actions.add(refit); actions.add(refitClass); actions.add(publish);

        JPanel south = panel(new BorderLayout(0, 7), PANEL);
        south.add(preview, BorderLayout.CENTER);
        JPanel row = panel(new GridLayout(0, 1, 0, 5), PANEL);
        row.add(buttons);
        row.add(actions);
        south.add(row, BorderLayout.SOUTH);
        editor.add(south, BorderLayout.SOUTH);
        root.add(editor, BorderLayout.NORTH);

        JPanel saved = verticalList();
        if (fits.isEmpty()) saved.add(statusPanel("No private fits saved for commander " + commander + ". Create one above or copy a game/server fit.", WARNING));
        for (PrivateShipFit fit : fits) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            String badge = standard != null && standard.id().equals(fit.id()) ? "CLASS STANDARD" : "PRIVATE";
            saved.add(fitCard(fit.name(), definition, badge, null));
            saved.add(Box.createVerticalStrut(8));
        }
        root.add(scroll(saved), BorderLayout.CENTER);
        fitChoice.setSelectedIndex(0);
        updatePreview.run();
        return root;
    }

    private JComponent serverFitsTab() {
        JPanel root = panel(new BorderLayout(0, 8), BACKGROUND);
        JButton refresh = button("REFRESH SERVER CATALOG");
        refresh.addActionListener(event -> { FitNetworkBridge.refresh(network, world); rebuild(); });
        JPanel top = panel(new FlowLayout(FlowLayout.RIGHT, 0, 0), BACKGROUND);
        top.add(refresh);
        root.add(top, BorderLayout.NORTH);

        JPanel list = verticalList();
        List<PublishedFit> published = WorldFitCatalog.published(world).stream()
                .filter(fit -> unit.shipTypeId.equals(fit.spec().hullId()))
                .sorted(Comparator.comparing(PublishedFit::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (published.isEmpty()) list.add(statusPanel("No player-published fits exist for this ship class on the server.", WARNING));
        for (PublishedFit fit : published) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 7, 0), PANEL);
            JButton saveCopy = button("SAVE PRIVATE COPY");
            saveCopy.addActionListener(event -> runAndRefresh(() -> ClientFitStore.importPublished(commanderName(), fit)));
            JButton refit = button("REFIT SELECTED");
            refit.addActionListener(event -> submitRefit(fit.name(), fit.spec(), false));
            actions.add(saveCopy); actions.add(refit);
            if (PlayerRegistry.localId().equals(fit.ownerPlayerId())) {
                JButton remove = button("UNPUBLISH");
                remove.addActionListener(event -> {
                    FitNetworkBridge.submit(network, world, "UNPUBLISH", "", null, null, null, fit.id());
                    rebuild();
                });
                actions.add(remove);
            }
            list.add(fitCard(fit.name(), definition, "BY " + fit.ownerName(), actions));
            list.add(Box.createVerticalStrut(8));
        }
        root.add(scroll(list), BorderLayout.CENTER);
        return root;
    }

    private JPanel fitCard(String title, ShipLoadoutDefinition fit, String badge, JComponent actions) {
        JPanel card = panel(new BorderLayout(12, 8), PANEL);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 175));
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), new EmptyBorder(10, 12, 10, 12)));
        JPanel details = panel(new GridLayout(0, 1, 0, 4), PANEL);
        details.add(label(title + (badge == null || badge.isBlank() ? "" : "  [" + badge + "]"), 15, Font.BOLD, TEXT));
        details.add(label("Weapons: " + weaponSummary(fit), 11, Font.PLAIN, MUTED));
        details.add(label("Range: " + whole(WeaponRules.maxRange(fit)) + "  |  Refit: " + whole(fit.refitTimeSeconds()) + "s", 11, Font.PLAIN, MUTED));
        details.add(label("Cost: " + (fit.refitCost().isEmpty() ? "None" : Rules.formatCost(fit.refitCost())), 11, Font.PLAIN, MUTED));
        card.add(details, BorderLayout.CENTER);
        if (actions != null) card.add(actions, BorderLayout.EAST);
        return card;
    }

    private void submitRefit(String name, ShipFitSpec spec, boolean entireClass) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) { showError(validation.reason()); return; }
        Base base = refitBaseInRange(world, unit);
        if (base == null) { showError("Move the selected ship into an owned shipyard's refit range first."); return; }
        String action = entireClass ? "REFIT_CLASS" : "REFIT";
        if (FitNetworkBridge.submit(network, world, action, name, spec, base.id, entireClass ? null : unit.key(), null)) close();
    }

    private void runAndRefresh(Runnable action) {
        try { action.run(); rebuild(); }
        catch (RuntimeException ex) { showError(ex.getMessage()); }
    }

    private void showError(String message) {
        String safe = message == null || message.isBlank() ? "The fitting action could not be completed." : message;
        if (dialog != null) JOptionPane.showMessageDialog(dialog, safe, "Ship Fitting", JOptionPane.WARNING_MESSAGE);
        if (world != null) world.status = safe;
    }

    private String commanderName() {
        String name = PlayerRegistry.baseName(PlayerRegistry.localId());
        return name == null || name.isBlank() ? world.localPlayerName : name;
    }

    private void sendProduction(String playerId, String action, String baseId, String value, String extra) {
        if (network == null) ProductionCommands.apply(world, playerId, action, baseId, value, extra);
        else network.production(playerId, action, baseId, value, extra);
    }

    static FittingOption evaluate(World world, Unit unit, ShipLoadoutDefinition loadout) {
        if (world == null || unit == null || loadout == null || !unit.shipTypeId.equals(loadout.hullId())) {
            return new FittingOption(null, false, false, "Fit does not match this hull.");
        }
        Base base = refitBaseInRange(world, unit);
        if (loadout.id().equals(unit.loadoutId)) return new FittingOption(base, true, false, "Currently installed.");
        ActiveRefit active = activeRefit(world, unit);
        if (active != null) return new FittingOption(active.base, false, false, "A refit is already queued.");
        if (base == null) return new FittingOption(nearestRefitBase(world, unit), false, false,
                nearestRefitBase(world, unit) == null ? "Requires an owned shipyard." : "Move ship into shipyard refit range.");
        boolean free = world.devFreeBuildFor(unit.playerId);
        if (!free && !WeaponRules.unlocked(world, unit.playerId, loadout)) return new FittingOption(base, false, false,
                "Research required: " + WeaponRules.missingResearchLabel(world, unit.playerId, loadout) + ".");
        if (!readyState(unit)) return new FittingOption(base, false, false, "Ship must be idle, stationary, and out of combat.");
        if (!free && !HangarStore.canAfford(base.inventory, WeaponRules.refitCost(loadout))) return new FittingOption(base, false, false, "Shipyard lacks required materials.");
        return new FittingOption(base, false, true, "Ready to refit at " + base.type().name + ".");
    }

    static Base nearestRefitBase(World world, Unit unit) {
        if (world == null || unit == null) return null;
        Base best = null; double distance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (base.hp <= 0 || !unit.playerId.equals(base.playerId) || !base.type().canRefitShips) continue;
            double candidate = Calc.distance(base.x, base.y, unit.x, unit.y);
            if (candidate < distance) { best = base; distance = candidate; }
        }
        return best;
    }

    static Base refitBaseInRange(World world, Unit unit) {
        if (world == null || unit == null) return null;
        Base best = null; double distance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!base.canRefit(unit)) continue;
            double candidate = Calc.distance(base.x, base.y, unit.x, unit.y);
            if (candidate < distance) { best = base; distance = candidate; }
        }
        return best;
    }

    static ActiveRefit activeRefit(World world, Unit unit) {
        if (world == null || unit == null) return null;
        for (Base base : world.bases.values()) for (ProductionJob job : base.productionQueue) {
            if (job.kind == ProductionJobKind.REFIT && unit.key().equals(job.subjectUnitKey)) return new ActiveRefit(base, job);
        }
        return null;
    }

    static boolean readyState(Unit unit) {
        return unit != null && unit.task == UnitTask.IDLE && unit.attackTarget.isBlank()
                && Calc.distance(unit.x, unit.y, unit.targetX, unit.targetY) <= 2
                && unit.weaponFlashTimer <= 0 && unit.weaponCooldown <= 0 && unit.shieldDelayTimer <= 0;
    }

    private JComponent stationStatus(World world, Unit unit) {
        Base inRange = refitBaseInRange(world, unit);
        if (inRange != null) return statusPanel("Connected to " + inRange.type().name + " " + inRange.id
                + "  |  distance " + whole(Calc.distance(inRange.x, inRange.y, unit.x, unit.y)) + " / "
                + whole(inRange.type().refitRange) + "  |  Private fits are saved under commander " + commanderName() + ".", GOOD);
        Base nearest = nearestRefitBase(world, unit);
        if (nearest == null) return statusPanel("No owned refit-capable shipyard exists in this system. Fits can still be created and saved.", BAD);
        return statusPanel("Fit editing is available anywhere. Move within " + whole(nearest.type().refitRange) + " of "
                + nearest.type().name + " " + nearest.id + " to apply a fit. Current distance: "
                + whole(Calc.distance(nearest.x, nearest.y, unit.x, unit.y)) + ".", WARNING);
    }

    private static ShipFitSpec specFrom(String hullId, List<JComboBox<WeaponChoice>> slots) {
        List<String> weapons = new ArrayList<>();
        for (JComboBox<WeaponChoice> slot : slots) {
            WeaponChoice choice = (WeaponChoice) slot.getSelectedItem();
            if (choice != null && !choice.id.isBlank()) weapons.add(choice.id);
        }
        return new ShipFitSpec(hullId, weapons);
    }

    private static void loadSpec(List<JComboBox<WeaponChoice>> slots, ShipFitSpec spec) {
        for (int i = 0; i < slots.size(); i++) {
            String id = i < spec.weaponIds().size() ? spec.weaponIds().get(i) : "";
            JComboBox<WeaponChoice> combo = slots.get(i);
            for (int j = 0; j < combo.getItemCount(); j++) if (combo.getItemAt(j).id.equals(id)) { combo.setSelectedIndex(j); break; }
        }
    }

    private static String weaponSummary(ShipLoadoutDefinition loadout) {
        Map<String,Integer> counts = new LinkedHashMap<>();
        Map<String,WeaponType> weapons = new LinkedHashMap<>();
        for (WeaponType weapon : WeaponRules.loadout(loadout)) { counts.merge(weapon.id, 1, Integer::sum); weapons.put(weapon.id, weapon); }
        if (counts.isEmpty()) return "Unarmed";
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String,Integer> entry : counts.entrySet()) {
            WeaponType weapon = weapons.get(entry.getKey());
            labels.add((entry.getValue() > 1 ? entry.getValue() + "× " : "") + weapon.name
                    + " [" + whole(weapon.range) + " range, " + whole(weapon.damage) + " dmg]");
        }
        return String.join("  •  ", labels);
    }

    private static JPanel verticalList() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BACKGROUND);
        panel.setBorder(new EmptyBorder(5, 5, 5, 5));
        return panel;
    }

    private static JScrollPane scroll(JComponent content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(42, 86, 116)));
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(26);
        return scroll;
    }

    private static JPanel statusPanel(String text, Color color) {
        JPanel panel = panel(new BorderLayout(), PANEL);
        panel.setBorder(new EmptyBorder(7, 9, 7, 9));
        panel.add(label(text, 11, Font.BOLD, color));
        return panel;
    }

    private static JPanel panel(LayoutManager layout, Color color) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(color);
        return panel;
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style, (float) size));
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(7, 12, 7, 12));
        return button;
    }

    private static void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(BACKGROUND);
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    record FittingOption(Base base, boolean current, boolean ready, String reason) { }
    record ActiveRefit(Base base, ProductionJob job) { }
    private record WeaponChoice(String id, String label) { @Override public String toString() { return label; } }
    private static final class PrivateChoice {
        final PrivateShipFit fit;
        PrivateChoice(PrivateShipFit fit) { this.fit = fit; }
        @Override public String toString() { return fit == null ? "New fit" : fit.name(); }
    }

    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable action;
        SimpleDocumentListener(Runnable action) { this.action = action; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
    }
}
