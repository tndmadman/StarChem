package com.tndmadman.rts;

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
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
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
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Full-screen, input-blocking fitting console for built-in, private, and server-published fits. */
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

    /* Fallback dispatcher for unusual focus states. GameFrame owns the normal modal Escape path. */
    private static volatile ShipFittingWindow activeWindow;
    static {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            ShipFittingWindow active = activeWindow;
            if (active == null || !active.visible()
                    || event.getID() != KeyEvent.KEY_PRESSED
                    || event.getKeyCode() != KeyEvent.VK_ESCAPE
                    || event.isControlDown() || event.isAltDown() || event.isMetaDown()) return false;
            active.close();
            event.consume();
            return true;
        });
    }

    private final JPanel glass = new GlassSurface();
    private final Timer refreshTimer = new Timer(150, event -> pollAuthoritativeState());
    private Component parent;
    private JRootPane rootPane;
    private Component previousGlass;
    private boolean previousGlassVisible;
    private World world;
    private PeerNetwork network;
    private Unit unit;
    private int selectedTab;
    private String notice = "";
    private Color noticeColor = MUTED;
    private JLabel noticeLabel;
    private JLabel stationLabel;

    private String draftName = "";
    private ShipFitSpec draftSpec = new ShipFitSpec("", List.of(), List.of());
    private String selectedPrivateFitId = "";
    private boolean changingDraft;

    private long observedCatalogRevision = -1;
    private String observedUnitState = "";
    private String observedWorldStatus = "";
    private boolean pendingServerRequest;

    ShipFittingWindow() {
        glass.setOpaque(false);
        glass.setVisible(false);
        glass.setFocusable(true);
        MouseAdapter blocker = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { glass.requestFocusInWindow(); }
            @Override public void mouseReleased(MouseEvent event) { }
            @Override public void mouseClicked(MouseEvent event) { }
        };
        glass.addMouseListener(blocker);
        glass.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent event) { }
            @Override public void mouseMoved(MouseEvent event) { }
        });
        glass.addMouseWheelListener((MouseWheelEvent event) -> { });
        String closeAction = "close-fitting";
        glass.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), closeAction);
        glass.getActionMap().put(closeAction, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { close(); }
        });
        refreshTimer.setCoalesce(true);
    }

    void showForUnit(Component parent, World world, PeerNetwork network, Unit unit) {
        if (parent == null || world == null || unit == null) return;
        if (activeWindow != null && activeWindow != this) activeWindow.close();
        close();
        JRootPane foundRoot = SwingUtilities.getRootPane(parent);
        if (foundRoot == null) return;

        this.parent = parent;
        this.rootPane = foundRoot;
        this.previousGlass = foundRoot.getGlassPane();
        this.previousGlassVisible = previousGlass != null && previousGlass.isVisible();
        this.world = world;
        this.network = network;
        this.unit = unit;
        this.selectedTab = 0;
        this.notice = "Configure the fit, then recall it to an owned shipyard. Close with ESC or the CLOSE button.";
        this.noticeColor = MUTED;
        this.selectedPrivateFitId = "";
        loadInstalledDraft();
        observedCatalogRevision = WorldFitCatalog.revision(world);
        observedUnitState = unitState();
        observedWorldStatus = world.status;
        pendingServerRequest = false;

        foundRoot.setGlassPane(glass);
        activeWindow = this;
        rebuild();
        glass.setVisible(true);
        glass.requestFocusInWindow();
        refreshTimer.start();
        submitNetwork("REFRESH", "", null, null, null, null, false);
    }

    void close() {
        if (!visible() && parent == null) return;
        Component returnFocus = parent;
        refreshTimer.stop();
        glass.setVisible(false);
        glass.removeAll();
        if (rootPane != null && rootPane.getGlassPane() == glass && previousGlass != null) {
            rootPane.setGlassPane(previousGlass);
            previousGlass.setVisible(previousGlassVisible);
        }
        if (activeWindow == this) activeWindow = null;
        parent = null;
        rootPane = null;
        previousGlass = null;
        world = null;
        network = null;
        unit = null;
        noticeLabel = null;
        stationLabel = null;
        pendingServerRequest = false;
        if (returnFocus != null) SwingUtilities.invokeLater(() -> {
            returnFocus.requestFocusInWindow();
            returnFocus.repaint();
        });
    }

    boolean visible() { return glass.isVisible() && activeWindow == this; }

    static boolean active() {
        ShipFittingWindow active = activeWindow;
        return active != null && active.visible();
    }

    static boolean closeActive() {
        ShipFittingWindow active = activeWindow;
        if (active == null || !active.visible()) return false;
        active.close();
        return true;
    }

    private void rebuild() {
        if (world == null || unit == null || !visibleOrOpening()) return;
        glass.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(16, 18, 16, 18);
        glass.add(content(), c);
        glass.revalidate();
        glass.repaint();
    }

    private boolean visibleOrOpening() { return activeWindow == this && rootPane != null; }

    private JComponent content() {
        JPanel root = new ConsoleSurface(new BorderLayout(0, 8));
        root.setPreferredSize(new Dimension(WIDTH, HEIGHT));
        root.setMinimumSize(new Dimension(760, 540));
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
        noticeLabel = label(notice, 10, Font.BOLD, noticeColor);
        footer.add(noticeLabel, BorderLayout.CENTER);
        stationLabel = stationStatusLabel();
        footer.add(stationLabel, BorderLayout.EAST);
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
                setNotice("Refit cancellation submitted.", WARNING, false);
            });
            state.add(cancel);
        }
        JButton close = button("CLOSE [ESC]");
        close.setToolTipText("Close fitting and return to the game.");
        close.addActionListener(event -> close());
        state.add(close);
        right.add(state, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent tabBar() {
        JPanel bar = panel(new GridLayout(1, 3, 5, 0), BACKGROUND);
        String[] names = {"GAME FITS", "MY FITS", "SERVER FITS"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            JButton tab = tabButton(names[i], selectedTab == i);
            tab.addActionListener(event -> {
                selectedTab = index;
                rebuild();
            });
            bar.add(tab);
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
            refit.addActionListener(event -> submitRefit(fit.displayName(),
                    new ShipFitSpec(fit.hullId(), fit.weaponIds(), ShipModuleRules.moduleIds(fit)), false));
            JButton copy = button("COPY TO MY FITS");
            copy.addActionListener(event -> {
                try {
                    PrivateShipFit saved = ClientFitStore.save(commanderName(), "", fit.displayName() + " Copy",
                            new ShipFitSpec(fit.hullId(), fit.weaponIds(), ShipModuleRules.moduleIds(fit)));
                    selectedPrivateFitId = saved.id();
                    draftName = saved.name();
                    draftSpec = saved.spec();
                    setNotice("Copied " + fit.displayName() + " into your private library.", GOOD, false);
                    selectedTab = 1;
                    rebuild();
                } catch (RuntimeException ex) {
                    setNotice(ex.getMessage(), BAD, false);
                }
            });
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
        normalizeDraft(fits);

        JPanel body = verticalList();
        JPanel editor = panel(new BorderLayout(8, 8), PANEL);
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, 610));
        editor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(9, 10, 9, 10)));

        JComboBox<PrivateChoice> fitChoice = new JComboBox<>();
        fitChoice.addItem(new PrivateChoice(null));
        for (PrivateShipFit fit : fits) fitChoice.addItem(new PrivateChoice(fit));
        styleCombo(fitChoice);
        selectPrivate(fitChoice, selectedPrivateFitId);
        JTextField name = new JTextField(draftName, 22);
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

        changingDraft = true;
        loadSpec(weaponSlots, moduleSlots, draftSpec);
        changingDraft = false;

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

        JPanel libraryActions = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), PANEL);
        JButton saveNew = button("SAVE NEW");
        JButton saveChanges = button("SAVE CHANGES");
        JButton delete = button("DELETE");
        JButton setStandard = button("SET CLASS STANDARD");
        JButton clearStandard = button("CLEAR STANDARD");
        libraryActions.add(saveNew); libraryActions.add(saveChanges); libraryActions.add(delete);
        libraryActions.add(setStandard); libraryActions.add(clearStandard);

        JPanel applyActions = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), PANEL);
        JButton refit = button("RECALL + REFIT SELECTED");
        JButton refitClass = button("RECALL + REFIT CLASS");
        JButton publish = button("PUBLISH TO SERVER");
        applyActions.add(refit); applyActions.add(refitClass); applyActions.add(publish);

        Runnable updateEditor = () -> {
            if (changingDraft) return;
            draftName = name.getText();
            draftSpec = specFrom(unit.shipTypeId, weaponSlots, moduleSlots);
            refreshLiveModuleRack(liveModuleRack, draftSpec.moduleIds(), moduleSlotCount);
            PlayerFitRules.Validation validation = PlayerFitRules.validate(draftSpec);
            boolean named = !PlayerFitRules.cleanName(draftName).isBlank();
            saveNew.setEnabled(validation.valid() && named);
            saveChanges.setEnabled(validation.valid() && named && !selectedPrivateFitId.isBlank());
            delete.setEnabled(!selectedPrivateFitId.isBlank());
            setStandard.setEnabled(!selectedPrivateFitId.isBlank());
            clearStandard.setEnabled(standard != null);
            publish.setEnabled(validation.valid() && named);
            ShipLoadoutDefinition previewDefinition = validation.valid()
                    ? PlayerFitRules.definition(named ? draftName : "Unsaved Fit", draftSpec) : null;
            refitClass.setEnabled(previewDefinition != null
                    && RefitQueuePlanner.bestStation(world, unit, previewDefinition,
                    world.devFreeBuildFor(unit.playerId)) != null);
            if (!validation.valid()) {
                refit.setEnabled(false);
                preview.setForeground(BAD);
                preview.setText(validation.reason());
                return;
            }
            ShipLoadoutDefinition definition = PlayerFitRules.definition(draftName, draftSpec);
            FittingOption option = evaluate(world, unit, definition);
            refit.setEnabled(option.ready());
            refit.setToolTipText(option.reason());
            preview.setForeground(option.ready() || option.current() ? MUTED : WARNING);
            preview.setText("GUNS // " + weaponSummary(definition)
                    + "\nUTILITY // " + ShipModuleRules.summary(draftSpec.moduleIds())
                    + "\nCOMBAT // max range " + whole(WeaponRules.maxRange(definition))
                    + "   |   refit " + whole(definition.refitTimeSeconds()) + "s"
                    + "\nCONVERSION // " + refitCostSummary(unit, definition)
                    + (definition.requiredResearch().isEmpty() ? "" : "\nRESEARCH // " + String.join(", ", definition.requiredResearch()))
                    + "\nSTATUS // " + option.reason());
        };

        fitChoice.addActionListener(event -> {
            if (changingDraft) return;
            PrivateChoice choice = (PrivateChoice)fitChoice.getSelectedItem();
            PrivateShipFit selected = choice == null ? null : choice.fit;
            selectedPrivateFitId = selected == null ? "" : selected.id();
            if (selected == null) loadInstalledDraft();
            else {
                draftName = selected.name();
                draftSpec = selected.spec();
            }
            rebuild();
        });
        for (JComboBox<WeaponChoice> slot : weaponSlots) slot.addActionListener(event -> updateEditor.run());
        for (JComboBox<ModuleChoice> slot : moduleSlots) slot.addActionListener(event -> updateEditor.run());
        name.getDocument().addDocumentListener(new SimpleDocumentListener(updateEditor));

        saveNew.addActionListener(event -> savePrivate(commander, "", name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots)));
        saveChanges.addActionListener(event -> savePrivate(commander, selectedPrivateFitId, name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots)));
        delete.addActionListener(event -> {
            try {
                if (selectedPrivateFitId.isBlank() || !ClientFitStore.delete(commander, selectedPrivateFitId)) {
                    setNotice("Select a saved private fit first.", BAD, false);
                    return;
                }
                selectedPrivateFitId = "";
                loadInstalledDraft();
                setNotice("Private fit deleted.", GOOD, false);
                rebuild();
            } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
        });
        setStandard.addActionListener(event -> {
            try {
                if (selectedPrivateFitId.isBlank()) throw new IllegalArgumentException("Save and select the fit first.");
                ClientFitStore.setStandard(commander, unit.shipTypeId, selectedPrivateFitId);
                setNotice("Class standard updated.", GOOD, false);
                rebuild();
            } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
        });
        clearStandard.addActionListener(event -> {
            try {
                ClientFitStore.setStandard(commander, unit.shipTypeId, "");
                setNotice("Class standard cleared.", GOOD, false);
                rebuild();
            } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
        });
        refit.addActionListener(event -> submitRefit(name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots), false));
        refitClass.addActionListener(event -> submitRefit(name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots), true));
        publish.addActionListener(event -> submitNetwork("PUBLISH", name.getText(),
                specFrom(unit.shipTypeId, weaponSlots, moduleSlots), null, null, null, true));

        JPanel south = panel(new BorderLayout(0, 7), PANEL);
        south.add(liveModuleRack, BorderLayout.NORTH);
        south.add(preview, BorderLayout.CENTER);
        JPanel rows = panel(new GridLayout(0, 1, 0, 5), PANEL);
        rows.add(libraryActions);
        rows.add(applyActions);
        south.add(rows, BorderLayout.SOUTH);
        editor.add(south, BorderLayout.SOUTH);
        updateEditor.run();
        body.add(editor);
        body.add(Box.createVerticalStrut(10));

        if (fits.isEmpty()) body.add(statusPanel("No private fits saved for commander " + commander
                + ". Build one above or copy a game/server fit.", WARNING));
        for (PrivateShipFit fit : fits) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            String badge = standard != null && standard.id().equals(fit.id()) ? "CLASS STANDARD" : "PRIVATE";
            JPanel actions = panel(new GridLayout(0, 1, 0, 5), PANEL);

            JButton refitSaved = button("RECALL + REFIT");
            FittingOption savedOption = evaluate(world, unit, definition);
            refitSaved.setEnabled(savedOption.ready());
            refitSaved.setToolTipText(savedOption.reason());
            refitSaved.addActionListener(event -> submitRefit(fit.name(), fit.spec(), false));

            JButton edit = button("LOAD IN EDITOR");
            edit.addActionListener(event -> {
                selectedPrivateFitId = fit.id();
                draftName = fit.name();
                draftSpec = fit.spec();
                rebuild();
            });

            boolean isStandard = standard != null && standard.id().equals(fit.id());
            JButton makeStandard = button(isStandard ? "CLASS STANDARD" : "SET CLASS STANDARD");
            makeStandard.setEnabled(!isStandard);
            makeStandard.addActionListener(event -> {
                try {
                    ClientFitStore.setStandard(commander, unit.shipTypeId, fit.id());
                    setNotice("Class standard updated.", GOOD, false);
                    rebuild();
                } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
            });

            JButton publishSaved = button("PUBLISH TO SERVER");
            publishSaved.addActionListener(event -> submitNetwork("PUBLISH", fit.name(), fit.spec(),
                    null, null, null, true));

            JButton deleteSaved = button("DELETE");
            deleteSaved.addActionListener(event -> {
                try {
                    if (!ClientFitStore.delete(commander, fit.id())) {
                        setNotice("Private fit no longer exists.", BAD, false);
                        return;
                    }
                    if (fit.id().equals(selectedPrivateFitId)) {
                        selectedPrivateFitId = "";
                        loadInstalledDraft();
                    }
                    setNotice("Private fit deleted.", GOOD, false);
                    rebuild();
                } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
            });

            actions.add(refitSaved);
            actions.add(edit);
            actions.add(makeStandard);
            actions.add(publishSaved);
            actions.add(deleteSaved);
            body.add(fitCard(fit.name(), definition, badge, actions));
            body.add(Box.createVerticalStrut(8));
        }
        return scroll(body);
    }

    private JComponent serverFitsTab() {
        JPanel root = panel(new BorderLayout(0, 8), BACKGROUND);
        JPanel top = panel(new BorderLayout(), BACKGROUND);
        top.add(label("COMMUNITY FIT CATALOG // SERVER-AUTHORIZED SHARED FITS",
                9, Font.BOLD, MUTED), BorderLayout.WEST);
        JButton refresh = button("REFRESH CATALOG");
        refresh.addActionListener(event -> submitNetwork("REFRESH", "", null, null, null, null, true));
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
            JButton refit = button("RECALL + REFIT");
            FittingOption option = evaluate(world, unit, definition);
            refit.setEnabled(option.ready());
            refit.setToolTipText(option.reason());
            refit.addActionListener(event -> submitRefit(fit.name(), fit.spec(), false));
            JButton saveCopy = button("SAVE PRIVATE COPY");
            saveCopy.addActionListener(event -> {
                try {
                    PrivateShipFit saved = ClientFitStore.importPublished(commanderName(), fit);
                    selectedPrivateFitId = saved.id();
                    draftName = saved.name();
                    draftSpec = saved.spec();
                    selectedTab = 1;
                    setNotice("Private copy saved.", GOOD, false);
                    rebuild();
                } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
            });
            actions.add(refit);
            actions.add(saveCopy);
            if (PlayerRegistry.localId().equals(fit.ownerPlayerId())) {
                JButton remove = button("UNPUBLISH");
                remove.addActionListener(event -> submitNetwork("UNPUBLISH", "", null,
                        null, null, fit.id(), true));
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
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 224));
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
                + whole(fit.refitTimeSeconds()) + "s", 10, Font.PLAIN, MUTED));
        details.add(label("CONVERSION  //  " + refitCostSummary(unit, fit),
                10, Font.PLAIN, MUTED));
        card.add(details, BorderLayout.CENTER);
        if (actions != null) card.add(actions, BorderLayout.EAST);
        return card;
    }

    private void savePrivate(String commander, String existingId, String name, ShipFitSpec spec) {
        try {
            PrivateShipFit saved = ClientFitStore.save(commander, existingId, name, spec);
            selectedPrivateFitId = saved.id();
            draftName = saved.name();
            draftSpec = saved.spec();
            setNotice(existingId == null || existingId.isBlank() ? "Private fit saved." : "Private fit updated.", GOOD, false);
            rebuild();
        } catch (RuntimeException ex) { setNotice(ex.getMessage(), BAD, false); }
    }

    private void submitRefit(String name, ShipFitSpec spec, boolean entireClass) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) { setNotice(validation.reason(), BAD, false); return; }
        ShipLoadoutDefinition definition = PlayerFitRules.definition(name, spec);
        FittingOption option = evaluate(world, unit, definition);
        if (!entireClass && !option.ready()) {
            setNotice(option.reason(), option.current() ? MUTED : BAD, false);
            return;
        }
        Base base = option.base();
        if (base == null) {
            base = RefitQueuePlanner.bestStation(world, unit, definition,
                    world.devFreeBuildFor(unit.playerId));
        }
        if (base == null) {
            setNotice("No owned refit-capable station can service this fit.", BAD, false);
            return;
        }
        submitNetwork(entireClass ? "REFIT_CLASS" : "REFIT", name, spec, base.id,
                entireClass ? null : unit.key(), null, true);
    }

    private boolean submitNetwork(String action, String name, ShipFitSpec spec,
                                  String baseId, String unitKey, String publishedId, boolean showNotice) {
        boolean submitted = FitNetworkBridge.submit(network, world, action, name, spec, baseId, unitKey, publishedId);
        if (!submitted) {
            if (showNotice) setNotice(world == null ? "Could not submit the fit request." : world.status, BAD, false);
            return false;
        }
        observedWorldStatus = world.status;
        pendingServerRequest = network != null && network.clientMode();
        if (showNotice) {
            String message = pendingServerRequest ? "Fit request submitted; awaiting authoritative server response." : world.status;
            setNotice(message, pendingServerRequest ? WARNING : GOOD, false);
        }
        return true;
    }

    private void setNotice(String message, Color color, boolean updateWorld) {
        notice = message == null || message.isBlank() ? "The fitting action could not be completed." : message;
        noticeColor = color == null ? MUTED : color;
        if (updateWorld && world != null) world.status = notice;
        if (noticeLabel != null) {
            noticeLabel.setText(notice);
            noticeLabel.setForeground(noticeColor);
        }
        if (stationLabel != null) updateStationLabel();
        glass.repaint();
    }

    private void pollAuthoritativeState() {
        if (!visible() || world == null || unit == null) return;
        Unit live = world.units.get(unit.key());
        if (live == null || live.hp <= 0 || !PlayerRegistry.isLocal(live.playerId)) {
            close();
            return;
        }
        unit = live;
        String status = world.status == null ? "" : world.status;
        if (pendingServerRequest && !status.equals(observedWorldStatus)
                && !status.startsWith("Fit request submitted")) {
            pendingServerRequest = false;
            setNotice(status, resultColor(status), false);
        }
        observedWorldStatus = status;
        long revision = WorldFitCatalog.revision(world);
        String state = unitState();
        if (revision != observedCatalogRevision || !state.equals(observedUnitState)) {
            observedCatalogRevision = revision;
            observedUnitState = state;
            rebuild();
        } else if (stationLabel != null) {
            updateStationLabel();
        }
    }

    private Color resultColor(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("reject") || lower.contains("could not") || lower.contains("invalid")
                || lower.contains("not found") || lower.contains("requires") || lower.startsWith("need ")
                || lower.startsWith("no available") || lower.startsWith("no owned")) return BAD;
        return GOOD;
    }

    private String unitState() {
        ActiveRefit active = activeRefit(world, unit);
        return unit.loadoutId + '|' + (active == null ? "" : active.base.id + '|' + active.job.id + '|' + active.job.loadoutId);
    }

    private void loadInstalledDraft() {
        if (unit == null) return;
        ShipLoadoutDefinition installed = WeaponRules.resolveForHull(unit.shipTypeId, unit.loadoutId);
        draftName = "";
        draftSpec = installed == null
                ? new ShipFitSpec(unit.shipTypeId, List.of(), List.of())
                : new ShipFitSpec(unit.shipTypeId, installed.weaponIds(), ShipModuleRules.moduleIds(installed));
    }

    private void normalizeDraft(List<PrivateShipFit> fits) {
        if (!unit.shipTypeId.equals(draftSpec.hullId())) loadInstalledDraft();
        if (selectedPrivateFitId.isBlank()) return;
        boolean exists = fits.stream().anyMatch(fit -> selectedPrivateFitId.equals(fit.id()));
        if (!exists) {
            selectedPrivateFitId = "";
            loadInstalledDraft();
        }
    }

    private String commanderName() {
        String name = PlayerRegistry.baseName(PlayerRegistry.localId());
        return name == null || name.isBlank() ? world.localPlayerName : name;
    }

    private void sendProduction(String playerId, String action, String baseId, String value, String extra) {
        if (network == null) ProductionCommands.apply(world, playerId, action, baseId, value, extra);
        else network.production(playerId, action, baseId, value, extra);
    }

    static String refitCostSummary(Unit unit, ShipLoadoutDefinition fit) {
        if (fit == null) return "Unavailable";
        if (unit == null || !fit.hullId().equals(unit.shipTypeId)) {
            List<Cost> installation = RefitQuote.fullInstallationCost(fit);
            return (installation.isEmpty() ? "No installation materials"
                    : "Install " + Rules.formatCost(installation))
                    + " • source conversion varies";
        }
        try {
            RefitQuote quote = RefitQuote.between(unit, fit);
            String required = quote.requiredMaterials().isEmpty()
                    ? "No added materials"
                    : "Add " + Rules.formatCost(quote.requiredMaterials());
            String removed = quote.removedComponents().isEmpty()
                    ? "Nothing removed"
                    : "Scrap " + String.join(", ", quote.removedComponents());
            return required + " • " + removed;
        } catch (RuntimeException ex) {
            return "Conversion unavailable";
        }
    }

    static FittingOption evaluate(World world, Unit unit, ShipLoadoutDefinition loadout) {
        if (world == null || unit == null || loadout == null
                || !unit.shipTypeId.equals(loadout.hullId())) {
            return new FittingOption(null, false, false, "Fit does not match this hull.");
        }
        boolean free = world.devFreeBuildFor(unit.playerId);
        Base currentStation = nearestRefitBase(world, unit);
        if (loadout.id().equals(unit.loadoutId)) {
            return new FittingOption(currentStation, true, false, "Currently installed.");
        }
        ActiveRefit active = activeRefit(world, unit);
        if (active != null) {
            return new FittingOption(active.base, false, false, "A refit is already queued.");
        }
        if (!free && !WeaponRules.unlocked(world, unit.playerId, loadout)) {
            return new FittingOption(null, false, false,
                    "Research required: "
                            + WeaponRules.missingResearchLabel(world, unit.playerId, loadout) + ".");
        }
        Base base = RefitQueuePlanner.bestStation(world, unit, loadout, free);
        if (base == null) {
            return new FittingOption(null, false, false,
                    "No owned refit-capable station can fund this conversion.");
        }
        return new FittingOption(base, false, true,
                "Ship will be recalled automatically to " + base.type().name + ".");
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

    private JLabel stationStatusLabel() {
        JLabel label = label("", 9, Font.BOLD, MUTED);
        updateStationLabel(label);
        return label;
    }

    private void updateStationLabel() { updateStationLabel(stationLabel); }

    private void updateStationLabel(JLabel label) {
        if (label == null || world == null || unit == null) return;
        Base nearest = nearestRefitBase(world, unit);
        if (nearest == null) {
            label.setText("NO SHIPYARD LINK");
            label.setForeground(BAD);
            return;
        }
        double distance = Calc.distance(nearest.x, nearest.y, unit.x, unit.y);
        label.setText("SHIPYARD LINK // " + nearest.id + " // DIST " + whole(distance) + " // AUTO-RECALL ENABLED");
        label.setForeground(GOOD);
    }

    private static ShipFitSpec specFrom(String hullId, List<JComboBox<WeaponChoice>> weapons,
                                        List<JComboBox<ModuleChoice>> modules) {
        List<String> weaponIds = new ArrayList<>();
        for (JComboBox<WeaponChoice> slot : weapons) {
            WeaponChoice choice = (WeaponChoice)slot.getSelectedItem();
            if (choice != null && !choice.id.isBlank()) weaponIds.add(choice.id);
        }
        List<String> moduleIds = new ArrayList<>();
        for (JComboBox<ModuleChoice> slot : modules) {
            ModuleChoice choice = (ModuleChoice)slot.getSelectedItem();
            if (choice != null && !choice.id().isBlank()) moduleIds.add(choice.id());
        }
        return new ShipFitSpec(hullId, weaponIds, moduleIds);
    }

    private static void loadSpec(List<JComboBox<WeaponChoice>> weapons,
                                 List<JComboBox<ModuleChoice>> modules, ShipFitSpec spec) {
        for (int i = 0; i < weapons.size(); i++) selectId(weapons.get(i), i < spec.weaponIds().size() ? spec.weaponIds().get(i) : "");
        for (int i = 0; i < modules.size(); i++) selectModuleId(modules.get(i), i < spec.moduleIds().size() ? spec.moduleIds().get(i) : "");
    }

    private static void selectId(JComboBox<WeaponChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).id.equals(id)) {
            combo.setSelectedIndex(i);
            return;
        }
        combo.setSelectedIndex(0);
    }

    private static void selectModuleId(JComboBox<ModuleChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).id().equals(id)) {
            combo.setSelectedIndex(i);
            return;
        }
        combo.setSelectedIndex(0);
    }

    private static void selectPrivate(JComboBox<PrivateChoice> combo, String id) {
        if (id == null || id.isBlank()) { combo.setSelectedIndex(0); return; }
        for (int i = 0; i < combo.getItemCount(); i++) {
            PrivateShipFit fit = combo.getItemAt(i).fit;
            if (fit != null && id.equals(fit.id())) { combo.setSelectedIndex(i); return; }
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
        for (int i = 0; i < shown; i++) strip.add(moduleTile(i < modules.size() ? modules.get(i) : null, iconSize, labels));
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
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
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

    private static final class GlassSurface extends JPanel {
        GlassSurface() { super(new GridBagLayout()); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setColor(new Color(0, 0, 0, 185));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.dispose();
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
