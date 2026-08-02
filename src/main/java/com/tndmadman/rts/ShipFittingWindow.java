package com.tndmadman.rts;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-game fitting console for built-in, private, and server-published ship fits. */
final class ShipFittingWindow {
    private static final int WIDTH = 1120;
    private static final int HEIGHT = 760;
    private static final Color BACKGROUND = new Color(3, 9, 16);
    private static final Color PANEL = new Color(7, 17, 28);
    private static final Color PANEL_ALT = new Color(10, 25, 39);
    private static final Color FIELD = new Color(9, 25, 38);
    private static final Color FIELD_HOVER = new Color(14, 42, 61);
    private static final Color BORDER = new Color(90, 190, 245);
    private static final Color BORDER_SOFT = new Color(43, 89, 119);
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(185, 215, 232);
    private static final Color GOOD = new Color(125, 226, 166);
    private static final Color WARNING = new Color(255, 198, 104);
    private static final Color BAD = new Color(255, 126, 126);

    private final StickyPopupMenu popup = new StickyPopupMenu();
    private final KeyEventDispatcher escapeDispatcher = this::dispatchEscape;
    private boolean escapeDispatcherInstalled;
    private Component parent;
    private World world;
    private PeerNetwork network;
    private Unit unit;
    private int selectedTab;
    private String notice = "";
    private Color noticeColor = MUTED;

    ShipFittingWindow() {
        popup.setBorder(BorderFactory.createEmptyBorder());
        popup.setLayout(new BorderLayout());
        popup.setFocusable(true);
        popup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-fitting");
        popup.getActionMap().put("close-fitting", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { close(); }
        });
    }

    void showForUnit(Component parent, World world, PeerNetwork network, Unit unit) {
        if (parent == null || world == null || unit == null) return;
        close();
        this.parent = parent;
        this.world = world;
        this.network = network;
        this.unit = unit;
        selectedTab = 0;
        notice = "Fit editing is local. Applying a fit recalls the ship to the nearest owned shipyard.";
        noticeColor = MUTED;
        rebuild();
        int x = Math.max(8, (parent.getWidth() - WIDTH) / 2);
        int y = Math.max(8, (parent.getHeight() - HEIGHT) / 2);
        popup.show(parent, x, y);
        installEscapeDispatcher();
        popup.requestFocusInWindow();
        FitNetworkBridge.refresh(network, world);
    }

    void close() {
        if (!popup.isVisible() && parent == null) return;
        Component returnFocus = parent;
        popup.hideExplicitly();
        uninstallEscapeDispatcher();
        parent = null;
        world = null;
        network = null;
        unit = null;
        notice = "";
        if (returnFocus != null) {
            SwingUtilities.invokeLater(() -> {
                returnFocus.requestFocusInWindow();
                returnFocus.repaint();
            });
        }
    }

    boolean visible() { return popup.isVisible(); }

    private boolean dispatchEscape(KeyEvent event) {
        if (!visible() || event.getID() != KeyEvent.KEY_PRESSED
                || event.getKeyCode() != KeyEvent.VK_ESCAPE
                || event.isControlDown() || event.isAltDown() || event.isMetaDown()) return false;
        close();
        event.consume();
        return true;
    }

    private void installEscapeDispatcher() {
        if (escapeDispatcherInstalled) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escapeDispatcher);
        escapeDispatcherInstalled = true;
    }

    private void uninstallEscapeDispatcher() {
        if (!escapeDispatcherInstalled) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escapeDispatcher);
        escapeDispatcherInstalled = false;
    }

    private void rebuild() {
        if (world == null || unit == null) return;
        popup.removeAll();
        popup.add(content(), BorderLayout.CENTER);
        popup.setPopupSize(WIDTH, HEIGHT);
        popup.revalidate();
        popup.repaint();
    }

    private JComponent content() {
        JPanel root = new ConsoleSurface(new BorderLayout(0, 8));
        root.setPreferredSize(new Dimension(WIDTH, HEIGHT));
        root.setBorder(BorderFactory.createLineBorder(BORDER, 2));
        root.add(header(), BorderLayout.NORTH);

        JPanel center = panel(new BorderLayout(0, 8), BACKGROUND);
        center.setBorder(new EmptyBorder(0, 10, 0, 10));
        center.add(tabBar(), BorderLayout.NORTH);
        center.add(switch (selectedTab) {
            case 1 -> myFitsTab();
            case 2 -> serverFitsTab();
            default -> gameFitsTab();
        }, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        JPanel footer = panel(new BorderLayout(8, 0), PANEL);
        footer.setBorder(new EmptyBorder(7, 11, 8, 11));
        footer.add(label(notice, 10, Font.BOLD, noticeColor), BorderLayout.CENTER);
        footer.add(stationStatus(), BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private JComponent header() {
        JPanel header = panel(new BorderLayout(12, 4), PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SOFT),
                new EmptyBorder(10, 13, 9, 12)));
        ShipLoadoutDefinition current = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);
        JPanel text = panel(new GridLayout(0, 1, 0, 2), PANEL);
        text.add(label("STARSHIP FITTING ARRAY", 11, Font.BOLD, BORDER));
        text.add(label(unit.type().name.toUpperCase(Locale.ROOT) + "  //  HULL " + unit.unitId, 20, Font.BOLD, TEXT));
        text.add(label("Installed: " + (current == null ? unit.loadoutId : current.displayName())
                + "  •  Guns: " + (current == null ? "Unavailable" : weaponSummary(current))
                + "  •  Utility: " + ShipModuleRules.summary(ShipModuleRules.moduleIds(current)),
                10, Font.PLAIN, MUTED));
        header.add(text, BorderLayout.CENTER);

        JPanel right = panel(new BorderLayout(8, 0), PANEL);
        right.add(moduleStrip(current == null ? List.of() : ShipModuleRules.moduleIds(current),
                ShipModuleRules.moduleSlotCount(unit.shipTypeId), 46, false), BorderLayout.WEST);
        JPanel state = panel(new GridLayout(0, 1, 0, 3), PANEL);
        ActiveRefit active = activeRefit(world, unit);
        state.add(label(active == null ? "STATUS // AVAILABLE" : "STATUS // RECALL OR REFIT ACTIVE",
                10, Font.BOLD, active == null ? GOOD : WARNING));
        if (active != null) {
            ShipLoadoutDefinition target = WeaponRules.findLoadout(active.job.loadoutId);
            state.add(label("Target: " + (target == null ? active.job.loadoutId : target.displayName()),
                    10, Font.BOLD, WARNING));
            JButton cancel = button("CANCEL REFIT");
            cancel.addActionListener(event -> {
                sendProduction(active.base.playerId, "CANCEL", active.base.id, active.job.id, "");
                setNotice("Refit cancellation submitted.", WARNING);
            });
            state.add(cancel);
        }
        state.add(label("ESC // CLOSE", 10, Font.BOLD, MUTED));
        right.add(state, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent tabBar() {
        JPanel bar = panel(new GridLayout(1, 3, 5, 0), BACKGROUND);
        String[] names = {"GAME FITS", "MY FITS", "SERVER FITS"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            JButton button = tabButton(names[i], selectedTab == i);
            button.addActionListener(event -> { selectedTab = index; rebuild(); });
            bar.add(button);
        }
        return bar;
    }

    private JComponent gameFitsTab() {
        JPanel list = verticalList();
        List<ShipLoadoutDefinition> variants = new ArrayList<>(WeaponRules.loadoutsForHull(unit.shipTypeId));
        variants.sort(Comparator.comparing((ShipLoadoutDefinition fit) -> !fit.defaultForHull())
                .thenComparing(ShipLoadoutDefinition::displayName));
        if (variants.isEmpty()) list.add(statusPanel("No built-in fit definition exists for this hull.", BAD));
        for (ShipLoadoutDefinition fit : variants) {
            JPanel actions = panel(new GridLayout(0, 1, 0, 6), PANEL);
            JButton refit = button("RECALL + REFIT");
            FittingOption option = evaluate(world, unit, fit);
            refit.setEnabled(option.ready());
            refit.setToolTipText(option.reason());
            refit.addActionListener(event -> {
                Base base = nearestRefitBase(world, unit);
                if (base == null) { setNotice("No owned refit-capable shipyard exists in this system.", BAD); return; }
                sendProduction(unit.playerId, "REFIT", base.id, unit.key(), fit.id());
                setNotice("Ship recalled to " + base.type().name + " for " + fit.displayName() + ".", GOOD);
            });
            JButton copy = button("COPY TO MY FITS");
            copy.addActionListener(event -> runAndRefresh(() -> ClientFitStore.save(commanderName(), "",
                    fit.displayName() + " Copy",
                    new ShipFitSpec(fit.hullId(), fit.weaponIds(), ShipModuleRules.moduleIds(fit))),
                    "Copied " + fit.displayName() + " into your private library."));
            actions.add(refit);
            actions.add(copy);
            list.add(fitCard(fit.displayName(), fit,
                    fit.defaultForHull() ? "BUILT-IN DEFAULT" : "BUILT-IN", actions));
            list.add(Box.createVerticalStrut(8));
        }
        return scroll(list);
    }

    private JComponent myFitsTab() {
        String commander = commanderName();
        List<PrivateShipFit> fits = ClientFitStore.fits(commander, unit.shipTypeId);
        PrivateShipFit standard = ClientFitStore.standard(commander, unit.shipTypeId);
        ShipLoadoutDefinition installed = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);

        JPanel root = panel(new BorderLayout(8, 8), BACKGROUND);
        JPanel editor = panel(new BorderLayout(8, 8), PANEL);
        editor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(9, 10, 9, 10)));

        JComboBox<PrivateChoice> fitChoice = new JComboBox<>();
        fitChoice.addItem(new PrivateChoice(null));
        for (PrivateShipFit fit : fits) fitChoice.addItem(new PrivateChoice(fit));
        styleCombo(fitChoice);
        JTextField name = new JTextField(22);
        styleField(name);

        JPanel top = panel(new GridBagLayout(), PANEL);
        GridBagConstraints c = constraints();
        top.add(label("SAVED FIT", 9, Font.BOLD, MUTED), c);
        c.gridx++; c.weightx = 0.42; c.fill = GridBagConstraints.HORIZONTAL; top.add(fitChoice, c);
        c.gridx++; c.weightx = 0; c.fill = GridBagConstraints.NONE; top.add(label("FIT NAME", 9, Font.BOLD, MUTED), c);
        c.gridx++; c.weightx = 0.58; c.fill = GridBagConstraints.HORIZONTAL; top.add(name, c);
        editor.add(top, BorderLayout.NORTH);

        List<JComboBox<WeaponChoice>> weaponSlots = new ArrayList<>();
        List<JComboBox<ModuleChoice>> moduleSlots = new ArrayList<>();
        JPanel fittingGrid = panel(new GridLayout(1, 2, 10, 0), PANEL);

        JPanel weaponRows = panel(new GridLayout(0, 1, 0, 6), FIELD);
        List<WeaponType> allowedWeapons = PlayerFitRules.allowedWeapons(unit.shipTypeId);
        int weaponSlotCount = PlayerFitRules.slotCount(unit.shipTypeId);
        for (int i = 0; i < weaponSlotCount; i++) {
            JComboBox<WeaponChoice> combo = new JComboBox<>();
            combo.addItem(new WeaponChoice("", "Empty hardpoint"));
            for (WeaponType weapon : allowedWeapons) combo.addItem(new WeaponChoice(weapon.id, weapon.name));
            styleCombo(combo);
            weaponSlots.add(combo);
            weaponRows.add(slotRow("HARDPOINT " + (i + 1), combo));
        }
        fittingGrid.add(section("WEAPON HARDPOINTS", weaponSlotCount, weaponRows));

        JPanel moduleRows = panel(new GridLayout(0, 1, 0, 7), FIELD);
        int moduleSlotCount = ShipModuleRules.moduleSlotCount(unit.shipTypeId);
        List<ShipModuleDefinition> allowedModules = ShipModuleRules.allowedModules(unit.shipTypeId);
        for (int i = 0; i < moduleSlotCount; i++) {
            JComboBox<ModuleChoice> combo = new JComboBox<>();
            combo.addItem(ModuleChoice.empty());
            for (ShipModuleDefinition module : allowedModules) combo.addItem(new ModuleChoice(module));
            styleModuleCombo(combo);
            moduleSlots.add(combo);
            moduleRows.add(slotRow("UTILITY " + (i + 1), combo));
        }
        if (moduleSlotCount == 0) moduleRows.add(statusPanel("This hull has no configured utility sockets.", WARNING));
        fittingGrid.add(section("UTILITY MODULES", moduleSlotCount, moduleRows));
        editor.add(fittingGrid, BorderLayout.CENTER);

        JPanel liveModuleRack = panel(new FlowLayout(FlowLayout.LEFT, 7, 3), PANEL_ALT);
        liveModuleRack.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(5, 7, 5, 7)));

        JTextArea preview = new JTextArea(5, 58);
        preview.setEditable(false);
        preview.setFocusable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setForeground(MUTED);
        preview.setBackground(FIELD);
        preview.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(7, 9, 7, 9)));

        Runnable updatePreview = () -> {
            ShipFitSpec spec = specFrom(unit.shipTypeId, weaponSlots, moduleSlots);
            refreshLiveModuleRack(liveModuleRack, spec.moduleIds(), moduleSlotCount);
            PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
            if (!validation.valid()) { preview.setForeground(BAD); preview.setText(validation.reason()); return; }
            ShipLoadoutDefinition definition = PlayerFitRules.definition(name.getText(), spec);
            preview.setForeground(MUTED);
            preview.setText("GUNS // " + weaponSummary(definition)
                    + "\nUTILITY // " + ShipModuleRules.summary(spec.moduleIds())
                    + "\nCOMBAT // max range " + whole(WeaponRules.maxRange(definition))
                    + "   |   refit " + whole(definition.refitTimeSeconds()) + "s"
                    + "\nCOST // " + (definition.refitCost().isEmpty() ? "None" : Rules.formatCost(definition.refitCost()))
                    + (definition.requiredResearch().isEmpty() ? "" : "\nRESEARCH // " + String.join(", ", definition.requiredResearch())));
        };
        for (JComboBox<WeaponChoice> slot : weaponSlots) slot.addActionListener(event -> updatePreview.run());
        for (JComboBox<ModuleChoice> slot : moduleSlots) slot.addActionListener(event -> updatePreview.run());
        name.getDocument().addDocumentListener(new SimpleDocumentListener(updatePreview));

        fitChoice.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            PrivateShipFit selected = choice == null ? null : choice.fit;
            ShipFitSpec spec = selected == null
                    ? installed == null ? new ShipFitSpec(unit.shipTypeId, List.of(), List.of())
                    : new ShipFitSpec(unit.shipTypeId, installed.weaponIds(), ShipModuleRules.moduleIds(installed))
                    : selected.spec();
            name.setText(selected == null ? "" : selected.name());
            loadSpec(weaponSlots, moduleSlots, spec);
            updatePreview.run();
        });

        JPanel libraryActions = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), PANEL);
        JButton saveNew = button("SAVE NEW");
        saveNew.addActionListener(event -> runAndRefresh(() -> ClientFitStore.save(commander, "", name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots)), "Private fit saved."));
        JButton saveChanges = button("SAVE CHANGES");
        saveChanges.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            if (choice == null || choice.fit == null) { setNotice("Select a saved private fit first.", BAD); return; }
            runAndRefresh(() -> ClientFitStore.save(commander, choice.fit.id(), name.getText(),
                    specFrom(unit.shipTypeId, weaponSlots, moduleSlots)), "Private fit updated.");
        });
        JButton delete = button("DELETE");
        delete.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            if (choice == null || choice.fit == null) { setNotice("Select a saved private fit first.", BAD); return; }
            runAndRefresh(() -> ClientFitStore.delete(commander, choice.fit.id()), "Private fit deleted.");
        });
        JButton setStandard = button("SET CLASS STANDARD");
        setStandard.addActionListener(event -> {
            PrivateChoice choice = (PrivateChoice) fitChoice.getSelectedItem();
            if (choice == null || choice.fit == null) { setNotice("Save and select the fit first.", BAD); return; }
            runAndRefresh(() -> ClientFitStore.setStandard(commander, unit.shipTypeId, choice.fit.id()),
                    "Class standard updated.");
        });
        JButton clearStandard = button("CLEAR STANDARD");
        clearStandard.setEnabled(standard != null);
        clearStandard.addActionListener(event -> runAndRefresh(
                () -> ClientFitStore.setStandard(commander, unit.shipTypeId, ""), "Class standard cleared."));
        libraryActions.add(saveNew); libraryActions.add(saveChanges); libraryActions.add(delete);
        libraryActions.add(setStandard); libraryActions.add(clearStandard);

        JPanel applyActions = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), PANEL);
        JButton refit = button("RECALL + REFIT SELECTED");
        refit.addActionListener(event -> submitRefit(name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots), false));
        JButton refitClass = button("RECALL + REFIT CLASS");
        refitClass.addActionListener(event -> submitRefit(name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots), true));
        JButton publish = button("PUBLISH TO SERVER");
        publish.addActionListener(event -> {
            ShipFitSpec spec = specFrom(unit.shipTypeId, weaponSlots, moduleSlots);
            PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
            if (!validation.valid()) { setNotice(validation.reason(), BAD); return; }
            if (FitNetworkBridge.submit(network, world, "PUBLISH", name.getText(), spec, null, null, null)) {
                setNotice("Fit publication submitted to the server.", GOOD);
            }
        });
        applyActions.add(refit); applyActions.add(refitClass); applyActions.add(publish);

        JPanel south = panel(new BorderLayout(0, 7), PANEL);
        south.add(liveModuleRack, BorderLayout.NORTH);
        south.add(preview, BorderLayout.CENTER);
        JPanel actionRows = panel(new GridLayout(0, 1, 0, 5), PANEL);
        actionRows.add(libraryActions);
        actionRows.add(applyActions);
        south.add(actionRows, BorderLayout.SOUTH);
        editor.add(south, BorderLayout.SOUTH);
        root.add(editor, BorderLayout.NORTH);

        JPanel saved = verticalList();
        if (fits.isEmpty()) saved.add(statusPanel("No private fits saved for commander " + commander
                + ". Build one above or copy a game/server fit.", WARNING));
        for (PrivateShipFit fit : fits) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            String badge = standard != null && standard.id().equals(fit.id()) ? "CLASS STANDARD" : "PRIVATE";
            saved.add(fitCard(fit.name(), definition, badge, null));
            saved.add(Box.createVerticalStrut(8));
        }
        root.add(scroll(saved), BorderLayout.CENTER);
        fitChoice.setSelectedIndex(0);
        loadSpec(weaponSlots, moduleSlots, installed == null
                ? new ShipFitSpec(unit.shipTypeId, List.of(), List.of())
                : new ShipFitSpec(unit.shipTypeId, installed.weaponIds(), ShipModuleRules.moduleIds(installed)));
        updatePreview.run();
        return root;
    }

    private JComponent serverFitsTab() {
        JPanel root = panel(new BorderLayout(0, 8), BACKGROUND);
        JPanel top = panel(new BorderLayout(), BACKGROUND);
        top.add(label("COMMUNITY FIT CATALOG // SAVING A COPY CREATES AN INDEPENDENT PRIVATE FIT",
                9, Font.BOLD, MUTED), BorderLayout.WEST);
        JButton refresh = button("REFRESH CATALOG");
        refresh.addActionListener(event -> {
            FitNetworkBridge.refresh(network, world);
            setNotice("Server fit catalog refresh requested.", MUTED);
        });
        top.add(refresh, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        JPanel list = verticalList();
        List<PublishedFit> published = WorldFitCatalog.published(world).stream()
                .filter(fit -> unit.shipTypeId.equals(fit.spec().hullId()))
                .sorted(Comparator.comparing(PublishedFit::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (published.isEmpty()) list.add(statusPanel(
                "No player-published fits exist for this ship class on this server.", WARNING));
        for (PublishedFit fit : published) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            JPanel actions = panel(new GridLayout(0, 1, 0, 6), PANEL);
            JButton saveCopy = button("SAVE PRIVATE COPY");
            saveCopy.addActionListener(event -> runAndRefresh(
                    () -> ClientFitStore.importPublished(commanderName(), fit), "Private copy saved."));
            JButton refit = button("RECALL + REFIT");
            refit.addActionListener(event -> submitRefit(fit.name(), fit.spec(), false));
            actions.add(refit);
            actions.add(saveCopy);
            if (PlayerRegistry.localId().equals(fit.ownerPlayerId())) {
                JButton remove = button("UNPUBLISH");
                remove.addActionListener(event -> {
                    if (FitNetworkBridge.submit(network, world, "UNPUBLISH", "", null,
                            null, null, fit.id())) setNotice("Unpublish request submitted.", WARNING);
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
        JPanel card = panel(new BorderLayout(12, 7), PANEL);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 176));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(9, 11, 9, 11)));

        List<String> moduleIds = ShipModuleRules.moduleIds(fit);
        JPanel visual = panel(new BorderLayout(0, 4), PANEL_ALT);
        visual.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(6, 7, 6, 7)));
        visual.add(label("UTILITY RACK", 8, Font.BOLD, BORDER), BorderLayout.NORTH);
        visual.add(moduleStrip(moduleIds, ShipModuleRules.moduleSlotCount(fit.hullId()), 48, true), BorderLayout.CENTER);
        card.add(visual, BorderLayout.WEST);

        JPanel details = panel(new GridLayout(0, 1, 0, 3), PANEL);
        details.add(label(title.toUpperCase(Locale.ROOT) + (badge == null || badge.isBlank() ? "" : "  //  " + badge),
                14, Font.BOLD, TEXT));
        details.add(label("GUNS  //  " + weaponSummary(fit), 10, Font.PLAIN, MUTED));
        details.add(label("UTILITY  //  " + ShipModuleRules.summary(moduleIds), 10, Font.PLAIN, MUTED));
        details.add(label("RANGE " + whole(WeaponRules.maxRange(fit)) + "   •   REFIT "
                + whole(fit.refitTimeSeconds()) + "s   •   COST "
                + (fit.refitCost().isEmpty() ? "None" : Rules.formatCost(fit.refitCost())),
                10, Font.PLAIN, MUTED));
        card.add(details, BorderLayout.CENTER);
        if (actions != null) card.add(actions, BorderLayout.EAST);
        return card;
    }

    private void submitRefit(String name, ShipFitSpec spec, boolean entireClass) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) { setNotice(validation.reason(), BAD); return; }
        Base base = nearestRefitBase(world, unit);
        if (base == null) { setNotice("No owned refit-capable shipyard exists in this system.", BAD); return; }
        String action = entireClass ? "REFIT_CLASS" : "REFIT";
        if (FitNetworkBridge.submit(network, world, action, name, spec, base.id,
                entireClass ? null : unit.key(), null)) {
            setNotice(entireClass
                    ? "Eligible ships are being recalled to the shipyard for class refitting."
                    : "Selected ship is being recalled to the shipyard for refitting.", GOOD);
        }
    }

    private void runAndRefresh(Runnable action, String success) {
        try {
            action.run();
            notice = success;
            noticeColor = GOOD;
            rebuild();
        } catch (RuntimeException ex) {
            setNotice(ex.getMessage(), BAD);
        }
    }

    private void setNotice(String message, Color color) {
        notice = message == null || message.isBlank() ? "The fitting action could not be completed." : message;
        noticeColor = color == null ? MUTED : color;
        if (world != null) world.status = notice;
        rebuild();
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
        Base base = nearestRefitBase(world, unit);
        if (loadout.id().equals(unit.loadoutId)) return new FittingOption(base, true, false, "Currently installed.");
        ActiveRefit active = activeRefit(world, unit);
        if (active != null) return new FittingOption(active.base, false, false, "A refit is already queued.");
        if (base == null) return new FittingOption(null, false, false, "Requires an owned refit-capable shipyard in this system.");
        boolean free = world.devFreeBuildFor(unit.playerId);
        if (!free && !WeaponRules.unlocked(world, unit.playerId, loadout)) return new FittingOption(base, false, false,
                "Research required: " + WeaponRules.missingResearchLabel(world, unit.playerId, loadout) + ".");
        if (!free && !HangarStore.canAfford(base.inventory, WeaponRules.refitCost(loadout))) {
            return new FittingOption(base, false, false, "Shipyard lacks required materials.");
        }
        return new FittingOption(base, false, true, "Ship will be recalled automatically to " + base.type().name + ".");
    }

    static Base nearestRefitBase(World world, Unit unit) {
        if (world == null || unit == null) return null;
        Base best = null;
        double distance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (base.hp <= 0 || !unit.playerId.equals(base.playerId) || !base.type().canRefitShips) continue;
            double candidate = Calc.distance(base.x, base.y, unit.x, unit.y);
            if (candidate < distance) { best = base; distance = candidate; }
        }
        return best;
    }

    static Base refitBaseInRange(World world, Unit unit) {
        if (world == null || unit == null) return null;
        Base best = null;
        double distance = Double.MAX_VALUE;
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

    private JComponent stationStatus() {
        Base nearest = nearestRefitBase(world, unit);
        if (nearest == null) return label("NO SHIPYARD LINK", 9, Font.BOLD, BAD);
        double distance = Calc.distance(nearest.x, nearest.y, unit.x, unit.y);
        return label("SHIPYARD LINK // " + nearest.id + " // DIST " + whole(distance)
                + " // AUTO-RECALL ENABLED", 9, Font.BOLD, GOOD);
    }

    private static ShipFitSpec specFrom(String hullId, List<JComboBox<WeaponChoice>> weapons,
                                        List<JComboBox<ModuleChoice>> modules) {
        List<String> weaponIds = new ArrayList<>();
        for (JComboBox<WeaponChoice> slot : weapons) {
            WeaponChoice choice = (WeaponChoice) slot.getSelectedItem();
            if (choice != null && !choice.id.isBlank()) weaponIds.add(choice.id);
        }
        List<String> moduleIds = new ArrayList<>();
        for (JComboBox<ModuleChoice> slot : modules) {
            ModuleChoice choice = (ModuleChoice) slot.getSelectedItem();
            if (choice != null && !choice.id().isBlank()) moduleIds.add(choice.id());
        }
        return new ShipFitSpec(hullId, weaponIds, moduleIds);
    }

    private static void loadSpec(List<JComboBox<WeaponChoice>> weapons,
                                 List<JComboBox<ModuleChoice>> modules, ShipFitSpec spec) {
        for (int i = 0; i < weapons.size(); i++) {
            String id = i < spec.weaponIds().size() ? spec.weaponIds().get(i) : "";
            selectId(weapons.get(i), id);
        }
        for (int i = 0; i < modules.size(); i++) {
            String id = i < spec.moduleIds().size() ? spec.moduleIds().get(i) : "";
            selectModuleId(modules.get(i), id);
        }
    }

    private static void selectId(JComboBox<WeaponChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).id.equals(id)) { combo.setSelectedIndex(i); return; }
        }
        combo.setSelectedIndex(0);
    }

    private static void selectModuleId(JComboBox<ModuleChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).id().equals(id)) { combo.setSelectedIndex(i); return; }
        }
        combo.setSelectedIndex(0);
    }

    private static String weaponSummary(ShipLoadoutDefinition loadout) {
        Map<String,Integer> counts = new LinkedHashMap<>();
        Map<String,WeaponType> weapons = new LinkedHashMap<>();
        for (WeaponType weapon : WeaponRules.loadout(loadout)) {
            counts.merge(weapon.id, 1, Integer::sum);
            weapons.put(weapon.id, weapon);
        }
        if (counts.isEmpty()) return "Unarmed";
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String,Integer> entry : counts.entrySet()) {
            WeaponType weapon = weapons.get(entry.getKey());
            labels.add((entry.getValue() > 1 ? entry.getValue() + "× " : "") + weapon.name);
        }
        return String.join("  •  ", labels);
    }

    private static JPanel section(String title, int slots, JComponent body) {
        JPanel wrapper = panel(new BorderLayout(0, 6), FIELD);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(7, 8, 8, 8)));
        wrapper.add(label(title + "  //  " + slots + " SLOT" + (slots == 1 ? "" : "S"),
                10, Font.BOLD, BORDER), BorderLayout.NORTH);
        wrapper.add(body, BorderLayout.CENTER);
        return wrapper;
    }

    private static JPanel slotRow(String title, JComponent control) {
        JPanel row = panel(new BorderLayout(8, 0), FIELD);
        JLabel label = label(title, 9, Font.BOLD, MUTED);
        label.setPreferredSize(new Dimension(88, 26));
        row.add(label, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private static JPanel moduleStrip(List<String> moduleIds, int slotCount, int iconSize, boolean labels) {
        JPanel strip = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), labels ? PANEL_ALT : PANEL);
        List<ShipModuleDefinition> modules = ShipModuleRules.modules(moduleIds);
        int shown = Math.max(slotCount, modules.size());
        for (int i = 0; i < shown; i++) {
            ShipModuleDefinition module = i < modules.size() ? modules.get(i) : null;
            strip.add(moduleTile(module, iconSize, labels));
        }
        if (shown == 0) strip.add(label("NO UTILITY SOCKETS", 8, Font.BOLD, MUTED));
        return strip;
    }

    private static JComponent moduleTile(ShipModuleDefinition module, int iconSize, boolean showLabel) {
        JLabel icon = new JLabel(module == null ? ShipModuleVisuals.emptyIcon(iconSize) : ShipModuleVisuals.icon(module, iconSize));
        icon.setToolTipText(module == null ? "Empty utility socket"
                : module.displayName() + " — " + module.description() + " — " + ShipModuleRules.effectSummary(module));
        if (!showLabel) return icon;
        JPanel tile = panel(new BorderLayout(0, 3), PANEL_ALT);
        tile.setBorder(new EmptyBorder(1, 2, 1, 2));
        tile.add(icon, BorderLayout.CENTER);
        JLabel text = label(module == null ? "EMPTY" : module.displayName().toUpperCase(Locale.ROOT), 8, Font.BOLD,
                module == null ? MUTED : module.color());
        text.setHorizontalAlignment(SwingConstants.CENTER);
        tile.add(text, BorderLayout.SOUTH);
        return tile;
    }

    private static void refreshLiveModuleRack(JPanel rack, List<String> moduleIds, int slotCount) {
        rack.removeAll();
        rack.add(label("LIVE MODULE RACK", 8, Font.BOLD, BORDER));
        rack.add(new JSeparator(SwingConstants.VERTICAL));
        JPanel strip = moduleStrip(moduleIds, slotCount, 42, true);
        strip.setBackground(PANEL_ALT);
        rack.add(strip);
        for (ShipModuleDefinition module : ShipModuleRules.modules(moduleIds)) {
            rack.add(label(ShipModuleRules.effectSummary(module), 9, Font.PLAIN, MUTED));
        }
        rack.revalidate();
        rack.repaint();
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
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.getVerticalScrollBar().setUnitIncrement(28);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(15, 0));
        return scroll;
    }

    private static JPanel statusPanel(String text, Color color) {
        JPanel panel = panel(new BorderLayout(), FIELD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(10, 11, 10, 11)));
        panel.add(label(text, 10, Font.BOLD, color));
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
        label.setFont(label.getFont().deriveFont(style, (float)size));
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setForeground(TEXT);
        button.setBackground(FIELD);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(5, 9, 5, 9)));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.addChangeListener(event -> button.setBackground(
                button.getModel().isRollover() || button.getModel().isPressed() ? FIELD_HOVER : FIELD));
        return button;
    }

    private static JButton tabButton(String text, boolean selected) {
        JButton button = button(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setForeground(selected ? Color.WHITE : MUTED);
        button.setBackground(selected ? new Color(17, 65, 91) : FIELD);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? BORDER : BORDER_SOFT, selected ? 2 : 1),
                new EmptyBorder(7, 10, 7, 10)));
        return button;
    }

    private static void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(5, 7, 5, 7)));
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setForeground(TEXT);
        combo.setBackground(FIELD);
        combo.setFocusable(false);
        combo.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setForeground(TEXT);
                label.setBackground(selected ? FIELD_HOVER : FIELD);
                label.setBorder(new EmptyBorder(4, 7, 4, 7));
                return label;
            }
        });
    }

    private static void styleModuleCombo(JComboBox<ModuleChoice> combo) {
        combo.setForeground(TEXT);
        combo.setBackground(FIELD);
        combo.setFocusable(false);
        combo.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        combo.setMaximumRowCount(8);
        combo.setRenderer(new ModuleChoiceRenderer());
        combo.setPreferredSize(new Dimension(280, 58));
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        c.insets = new Insets(0, 0, 0, 7);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    record FittingOption(Base base, boolean current, boolean ready, String reason) { }
    record ActiveRefit(Base base, ProductionJob job) { }
    private record WeaponChoice(String id, String label) { @Override public String toString() { return label; } }

    private record ModuleChoice(String id, ShipModuleDefinition module) {
        ModuleChoice(ShipModuleDefinition module) { this(module == null ? "" : module.id(), module); }
        static ModuleChoice empty() { return new ModuleChoice("", null); }
        @Override public String toString() { return module == null ? "Empty utility socket" : module.displayName(); }
    }

    private static final class ModuleChoiceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focus) {
            JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focus);
            ModuleChoice choice = value instanceof ModuleChoice moduleChoice ? moduleChoice : ModuleChoice.empty();
            ShipModuleDefinition module = choice.module();
            label.setIcon(module == null ? ShipModuleVisuals.emptyIcon(42) : ShipModuleVisuals.icon(module, 42));
            label.setIconTextGap(10);
            label.setText(module == null
                    ? "<html><b>EMPTY UTILITY SOCKET</b><br><span style='font-size:9px'>No module installed</span></html>"
                    : "<html><b>" + escape(module.displayName()) + "</b><br><span style='font-size:9px'>"
                    + escape(ShipModuleRules.effectSummary(module)) + "</span></html>");
            label.setForeground(TEXT);
            label.setBackground(selected ? FIELD_HOVER : FIELD);
            label.setBorder(new EmptyBorder(5, 7, 5, 7));
            label.setToolTipText(module == null ? "Empty utility socket" : module.description());
            return label;
        }
    }

    private static final class PrivateChoice {
        final PrivateShipFit fit;
        PrivateChoice(PrivateShipFit fit) { this.fit = fit; }
        @Override public String toString() { return fit == null ? "New fit from installed setup" : fit.name(); }
    }

    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable action;
        SimpleDocumentListener(Runnable action) { this.action = action; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
    }

    /** Prevents Swing menu-selection changes and outside clicks from dismissing the fitting console. */
    private static final class StickyPopupMenu extends JPopupMenu {
        private boolean explicitClose;

        @Override public void setVisible(boolean visible) {
            if (!visible && !explicitClose) return;
            super.setVisible(visible);
        }

        void hideExplicitly() {
            explicitClose = true;
            try {
                super.setVisible(false);
            } finally {
                explicitClose = false;
            }
        }
    }

    private static final class ConsoleSurface extends JPanel {
        ConsoleSurface(LayoutManager layout) {
            super(layout);
            setOpaque(true);
            setBackground(BACKGROUND);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setPaint(new GradientPaint(0, 0, new Color(8, 22, 35), 0, getHeight(), BACKGROUND));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(new Color(35, 94, 126, 32));
            for (int y = 18; y < getHeight(); y += 22) g.drawLine(0, y, getWidth(), y);
            for (int x = 20; x < getWidth(); x += 48) g.drawLine(x, 0, x, getHeight());
            g.dispose();
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
