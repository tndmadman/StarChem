package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import javax.swing.ListCellRenderer;
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
import java.util.List;
import java.util.Locale;

/** Standalone fitting library and class designer. A live ship is optional apply context. */
final class ShipFitStudioWindow {
    private static final int WIDTH = 1180;
    private static final int HEIGHT = 790;
    private static final Color VOID = new Color(2, 7, 13);
    private static final Color BACKGROUND = new Color(4, 12, 21);
    private static final Color PANEL = new Color(8, 21, 34);
    private static final Color PANEL_ALT = new Color(12, 31, 48);
    private static final Color FIELD = new Color(9, 27, 42);
    private static final Color FIELD_HOVER = new Color(18, 53, 75);
    private static final Color CYAN = new Color(91, 218, 255);
    private static final Color BLUE = new Color(72, 151, 255);
    private static final Color PURPLE = new Color(191, 112, 255);
    private static final Color ORANGE = new Color(255, 180, 82);
    private static final Color GREEN = new Color(117, 233, 166);
    private static final Color RED = new Color(255, 116, 126);
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(181, 215, 232);
    private static final Color BORDER_SOFT = new Color(48, 99, 128);

    private static volatile ShipFitStudioWindow activeWindow;

    private final JPanel glass = new GlassSurface();
    private final Timer refreshTimer = new Timer(300, event -> pollState());
    private Component parent;
    private JRootPane rootPane;
    private Component previousGlass;
    private boolean previousGlassVisible;
    private World world;
    private PeerNetwork network;
    private String contextUnitKey = "";
    private String hullId = "";
    private int selectedTab;
    private String draftName = "";
    private ShipFitSpec draftSpec = new ShipFitSpec("", List.of(), List.of());
    private String selectedPrivateFitId = "";
    private String notice = "Select a hull and build a fit.";
    private Color noticeColor = MUTED;
    private JLabel noticeLabel;
    private JLabel linkLabel;
    private long observedCatalogRevision = -1;
    private boolean changingDraft;

    ShipFitStudioWindow() {
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
        glass.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-fit-studio");
        glass.getActionMap().put("close-fit-studio", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { close(); }
        });
        refreshTimer.setCoalesce(true);
    }

    static boolean active() {
        ShipFitStudioWindow current = activeWindow;
        return current != null && current.visible();
    }

    void show(Component parent, World world, PeerNetwork network, Unit selectedUnit) {
        if (parent == null || world == null) return;
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
        this.contextUnitKey = validLocalUnit(selectedUnit) ? selectedUnit.key() : "";
        this.hullId = selectedUnit != null && validLocalUnit(selectedUnit)
                ? selectedUnit.shipTypeId : initialHullId();
        this.selectedTab = 0;
        this.selectedPrivateFitId = "";
        this.notice = contextUnit().isPresent()
                ? "Selected ship linked. Design, save, publish, or send the fit to its shipyard."
                : "Class design mode. No ship selection is required to create, save, or publish a fit.";
        this.noticeColor = MUTED;
        loadStartingDraft();
        observedCatalogRevision = WorldFitCatalog.revision(world);

        foundRoot.setGlassPane(glass);
        activeWindow = this;
        rebuild();
        glass.setVisible(true);
        glass.requestFocusInWindow();
        refreshTimer.start();
        FitNetworkBridge.refresh(network, world);
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
        contextUnitKey = "";
        noticeLabel = null;
        linkLabel = null;
        if (returnFocus != null) SwingUtilities.invokeLater(() -> {
            returnFocus.requestFocusInWindow();
            returnFocus.repaint();
        });
    }

    private boolean visible() { return activeWindow == this && glass.isVisible(); }

    private void rebuild() {
        if (world == null || rootPane == null || activeWindow != this) return;
        glass.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(14, 16, 14, 16);
        glass.add(content(), c);
        glass.revalidate();
        glass.repaint();
    }

    private JComponent content() {
        JPanel root = new ConsoleSurface(new BorderLayout(0, 8));
        root.setPreferredSize(new Dimension(WIDTH, HEIGHT));
        root.setMinimumSize(new Dimension(800, 570));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CYAN, 2),
                BorderFactory.createLineBorder(new Color(24, 69, 96), 1)));
        root.add(header(), BorderLayout.NORTH);

        JPanel center = panel(new BorderLayout(0, 8), BACKGROUND);
        center.setBorder(new EmptyBorder(0, 10, 0, 10));
        center.add(tabBar(), BorderLayout.NORTH);
        center.add(switch (selectedTab) {
            case 1 -> gameFitsTab();
            case 2 -> myFitsTab();
            case 3 -> serverFitsTab();
            default -> editorTab();
        }, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        JPanel footer = panel(new BorderLayout(8, 0), PANEL);
        footer.setBorder(new EmptyBorder(7, 11, 8, 11));
        noticeLabel = label(notice, 10, Font.BOLD, noticeColor);
        footer.add(noticeLabel, BorderLayout.CENTER);
        linkLabel = label(contextLabel(), 9, Font.BOLD, contextUnit().isPresent() ? GREEN : ORANGE);
        linkLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        footer.add(linkLabel, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private JComponent header() {
        ShipType hull = Rules.findShip(hullId);
        JPanel header = panel(new BorderLayout(12, 0), PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_SOFT),
                new EmptyBorder(9, 12, 9, 12)));

        JLabel hullArt = new JLabel(FittingItemVisuals.hullIcon(hull, 68));
        hullArt.setBorder(BorderFactory.createLineBorder(new Color(76, 155, 198)));
        header.add(hullArt, BorderLayout.WEST);

        JPanel identity = panel(new BorderLayout(9, 4), PANEL);
        JPanel titles = panel(new GridLayout(0, 1, 0, 1), PANEL);
        titles.add(label("STARCHEM // FITTING STUDIO", 11, Font.BOLD, PURPLE));
        titles.add(label(hull == null ? "UNKNOWN HULL" : hull.name.toUpperCase(Locale.ROOT), 22, Font.BOLD, TEXT));
        titles.add(label(contextUnit().isPresent()
                ? "LIVE SHIP CONTEXT // " + contextUnit().get().unitId + " // apply controls enabled"
                : "CLASS DESIGN MODE // build fits without selecting or owning a live ship",
                10, Font.BOLD, contextUnit().isPresent() ? GREEN : ORANGE));
        identity.add(titles, BorderLayout.CENTER);

        JComboBox<HullChoice> hullChoice = new JComboBox<>();
        List<ShipType> hulls = fittingHulls();
        for (ShipType candidate : hulls) hullChoice.addItem(new HullChoice(candidate));
        styleCombo(hullChoice, new HullChoiceRenderer(), 270, 54);
        selectHull(hullChoice, hullId);
        hullChoice.addActionListener(event -> {
            HullChoice choice = (HullChoice)hullChoice.getSelectedItem();
            if (choice == null || choice.ship == null || choice.ship.id.equals(hullId)) return;
            hullId = choice.ship.id;
            selectedPrivateFitId = "";
            loadStartingDraft();
            selectedTab = 0;
            setNotice("Switched to " + choice.ship.name + " class design.", CYAN, false);
            rebuild();
        });
        JPanel chooser = panel(new BorderLayout(0, 3), PANEL);
        chooser.add(label("ACTIVE HULL CLASS", 8, Font.BOLD, MUTED), BorderLayout.NORTH);
        chooser.add(hullChoice, BorderLayout.CENTER);
        identity.add(chooser, BorderLayout.EAST);
        header.add(identity, BorderLayout.CENTER);

        JPanel controls = panel(new GridLayout(0, 1, 0, 5), PANEL);
        JButton refresh = button("SYNC SERVER FITS", BLUE);
        refresh.addActionListener(event -> submit("REFRESH", "", null, null, null, null));
        JButton close = button("CLOSE [ESC]", RED);
        close.addActionListener(event -> close());
        controls.add(refresh);
        controls.add(close);
        header.add(controls, BorderLayout.EAST);
        return header;
    }

    private JComponent tabBar() {
        JPanel bar = panel(new GridLayout(1, 4, 6, 0), BACKGROUND);
        String[] names = {"FIT EDITOR", "GAME FITS", "MY FITS", "SERVER FITS"};
        Color[] colors = {CYAN, BLUE, PURPLE, ORANGE};
        for (int i = 0; i < names.length; i++) {
            int tab = i;
            JButton button = tabButton(names[i], selectedTab == i, colors[i]);
            button.addActionListener(event -> {
                selectedTab = tab;
                rebuild();
            });
            bar.add(button);
        }
        return bar;
    }

    private JComponent editorTab() {
        String commander = commanderName();
        List<PrivateShipFit> fits = ClientFitStore.fits(commander, hullId);
        PrivateShipFit standard = ClientFitStore.standard(commander, hullId);
        normalizeDraft(fits);

        JPanel editor = panel(new BorderLayout(9, 9), PANEL);
        editor.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(9, 10, 9, 10)));

        JComboBox<PrivateChoice> savedChoice = new JComboBox<>();
        savedChoice.addItem(new PrivateChoice(null));
        for (PrivateShipFit fit : fits) savedChoice.addItem(new PrivateChoice(fit));
        styleCombo(savedChoice, new PrivateChoiceRenderer(), 330, 46);
        selectPrivate(savedChoice, selectedPrivateFitId);
        JTextField name = new JTextField(draftName, 24);
        styleField(name);

        JPanel top = panel(new GridBagLayout(), PANEL);
        GridBagConstraints c = constraints();
        top.add(label("SAVED FIT", 9, Font.BOLD, PURPLE), c);
        c.gridx++; c.weightx = .42; c.fill = GridBagConstraints.HORIZONTAL; top.add(savedChoice, c);
        c.gridx++; c.weightx = 0; c.fill = GridBagConstraints.NONE; top.add(label("FIT NAME", 9, Font.BOLD, CYAN), c);
        c.gridx++; c.weightx = .58; c.fill = GridBagConstraints.HORIZONTAL; top.add(name, c);
        editor.add(top, BorderLayout.NORTH);

        List<JComboBox<WeaponChoice>> weaponSlots = new ArrayList<>();
        List<JComboBox<ModuleChoice>> moduleSlots = new ArrayList<>();
        JPanel fittingGrid = panel(new GridLayout(1, 2, 10, 0), PANEL);

        JPanel weaponRows = verticalPanel(FIELD);
        List<WeaponType> allowedWeapons = PlayerFitRules.allowedWeapons(hullId);
        int weaponSlotCount = PlayerFitRules.slotCount(hullId);
        for (int i = 0; i < weaponSlotCount; i++) {
            JComboBox<WeaponChoice> combo = new JComboBox<>();
            combo.addItem(WeaponChoice.empty());
            for (WeaponType weapon : allowedWeapons) combo.addItem(new WeaponChoice(weapon));
            styleCombo(combo, new WeaponChoiceRenderer(), 330, 54);
            weaponSlots.add(combo);
            weaponRows.add(slotRow("HARDPOINT " + (i + 1), combo, CYAN));
            weaponRows.add(Box.createVerticalStrut(5));
        }
        if (weaponSlotCount == 0) weaponRows.add(statusPanel("This hull has no configurable weapon hardpoints.", ORANGE));
        fittingGrid.add(section("WEAPON HARDPOINTS", weaponSlotCount, weaponRows, CYAN));

        JPanel moduleRows = verticalPanel(FIELD);
        List<ShipModuleDefinition> allowedModules = ShipModuleRules.allowedModules(hullId);
        int moduleSlotCount = ShipModuleRules.moduleSlotCount(hullId);
        for (int i = 0; i < moduleSlotCount; i++) {
            JComboBox<ModuleChoice> combo = new JComboBox<>();
            combo.addItem(ModuleChoice.empty());
            for (ShipModuleDefinition module : allowedModules) combo.addItem(new ModuleChoice(module));
            styleCombo(combo, new ModuleChoiceRenderer(), 330, 58);
            moduleSlots.add(combo);
            moduleRows.add(slotRow("UTILITY " + (i + 1), combo, PURPLE));
            moduleRows.add(Box.createVerticalStrut(5));
        }
        if (moduleSlotCount == 0) moduleRows.add(statusPanel("This hull has no configurable utility sockets.", ORANGE));
        fittingGrid.add(section("UTILITY MODULES", moduleSlotCount, moduleRows, PURPLE));
        editor.add(fittingGrid, BorderLayout.CENTER);

        changingDraft = true;
        loadSpec(weaponSlots, moduleSlots, draftSpec);
        changingDraft = false;

        JPanel visualRack = panel(new FlowLayout(FlowLayout.LEFT, 8, 4), PANEL_ALT);
        visualRack.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(74, 136, 169)), new EmptyBorder(5, 7, 5, 7)));
        JTextArea stats = new JTextArea(5, 62);
        stats.setEditable(false);
        stats.setFocusable(false);
        stats.setLineWrap(true);
        stats.setWrapStyleWord(true);
        stats.setForeground(MUTED);
        stats.setBackground(FIELD);
        stats.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(7, 9, 7, 9)));

        JButton saveNew = button("SAVE NEW", GREEN);
        JButton saveChanges = button("SAVE CHANGES", CYAN);
        JButton delete = button("DELETE", RED);
        JButton standardButton = button("SET CLASS STANDARD", PURPLE);
        JButton clearStandard = button("CLEAR STANDARD", ORANGE);
        JButton publish = button("PUBLISH TO SERVER", ORANGE);
        JButton applySelected = button("REFIT SELECTED SHIP", CYAN);
        JButton applyClass = button("REFIT SHIP CLASS", PURPLE);

        Runnable updateEditor = () -> {
            if (changingDraft) return;
            draftName = name.getText();
            draftSpec = specFrom(hullId, weaponSlots, moduleSlots);
            PlayerFitRules.Validation validation = PlayerFitRules.validate(draftSpec);
            boolean named = !PlayerFitRules.cleanName(draftName).isBlank();
            ShipLoadoutDefinition definition = validation.valid()
                    ? PlayerFitRules.definition(named ? draftName : "Unsaved Fit", draftSpec) : null;
            refreshVisualRack(visualRack, draftSpec);
            saveNew.setEnabled(validation.valid() && named);
            saveChanges.setEnabled(validation.valid() && named && !selectedPrivateFitId.isBlank());
            delete.setEnabled(!selectedPrivateFitId.isBlank());
            standardButton.setEnabled(!selectedPrivateFitId.isBlank());
            clearStandard.setEnabled(standard != null);
            publish.setEnabled(validation.valid() && named);
            Base classBase = classRefitBase();
            applyClass.setEnabled(validation.valid() && named && classBase != null);
            Unit live = contextUnit().orElse(null);
            ShipFittingWindow.FittingOption option = definition == null || live == null
                    ? null : ShipFittingWindow.evaluate(world, live, definition);
            applySelected.setEnabled(option != null && option.ready());
            applySelected.setToolTipText(option == null
                    ? "Select exactly one live ship of this hull to enable direct refitting."
                    : option.reason());
            if (!validation.valid()) {
                stats.setForeground(RED);
                stats.setText(validation.reason());
            } else {
                stats.setForeground(option != null && option.ready() ? GREEN : MUTED);
                stats.setText("GUNS // " + weaponSummary(definition)
                        + "\nUTILITY // " + ShipModuleRules.summary(draftSpec.moduleIds())
                        + "\nCOMBAT // max range " + whole(WeaponRules.maxRange(definition))
                        + "   |   refit " + whole(definition.refitTimeSeconds()) + "s"
                        + "\nCOST // " + (definition.refitCost().isEmpty() ? "None" : Rules.formatCost(definition.refitCost()))
                        + "\nCONTEXT // " + (option == null ? "Class design; no matching live ship selected." : option.reason()));
            }
        };

        savedChoice.addActionListener(event -> {
            if (changingDraft) return;
            PrivateChoice choice = (PrivateChoice)savedChoice.getSelectedItem();
            PrivateShipFit selected = choice == null ? null : choice.fit;
            selectedPrivateFitId = selected == null ? "" : selected.id();
            if (selected == null) loadStartingDraft();
            else {
                draftName = selected.name();
                draftSpec = selected.spec();
            }
            rebuild();
        });
        for (JComboBox<WeaponChoice> combo : weaponSlots) combo.addActionListener(event -> updateEditor.run());
        for (JComboBox<ModuleChoice> combo : moduleSlots) combo.addActionListener(event -> updateEditor.run());
        name.getDocument().addDocumentListener(new SimpleDocumentListener(updateEditor));

        saveNew.addActionListener(event -> savePrivate("", name.getText(), specFrom(hullId, weaponSlots, moduleSlots)));
        saveChanges.addActionListener(event -> savePrivate(selectedPrivateFitId, name.getText(), specFrom(hullId, weaponSlots, moduleSlots)));
        delete.addActionListener(event -> deletePrivate(selectedPrivateFitId));
        standardButton.addActionListener(event -> setClassStandard(selectedPrivateFitId));
        clearStandard.addActionListener(event -> setClassStandard(""));
        publish.addActionListener(event -> publish(name.getText(), specFrom(hullId, weaponSlots, moduleSlots)));
        applySelected.addActionListener(event -> applyFit(name.getText(), specFrom(hullId, weaponSlots, moduleSlots), false));
        applyClass.addActionListener(event -> applyFit(name.getText(), specFrom(hullId, weaponSlots, moduleSlots), true));

        JPanel actions = panel(new GridLayout(0, 1, 0, 5), PANEL);
        JPanel library = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), PANEL);
        library.add(saveNew); library.add(saveChanges); library.add(delete);
        library.add(standardButton); library.add(clearStandard); library.add(publish);
        JPanel apply = panel(new FlowLayout(FlowLayout.LEFT, 6, 0), PANEL);
        apply.add(applySelected); apply.add(applyClass);
        actions.add(library);
        actions.add(apply);

        JPanel south = panel(new BorderLayout(0, 7), PANEL);
        south.add(visualRack, BorderLayout.NORTH);
        south.add(stats, BorderLayout.CENTER);
        south.add(actions, BorderLayout.SOUTH);
        editor.add(south, BorderLayout.SOUTH);
        updateEditor.run();
        return editor;
    }

    private JComponent gameFitsTab() {
        JPanel list = verticalPanel(BACKGROUND);
        List<ShipLoadoutDefinition> variants = new ArrayList<>(WeaponRules.loadoutsForHull(hullId));
        variants.sort(Comparator.comparing((ShipLoadoutDefinition fit) -> !fit.defaultForHull())
                .thenComparing(ShipLoadoutDefinition::displayName));
        if (variants.isEmpty()) list.add(statusPanel("No authored game fits exist for this hull.", RED));
        for (ShipLoadoutDefinition fit : variants) {
            JPanel actions = cardActions();
            JButton edit = button("LOAD IN EDITOR", CYAN);
            edit.addActionListener(event -> loadInEditor(fit.displayName(), spec(fit)));
            JButton copy = button("SAVE PRIVATE COPY", PURPLE);
            copy.addActionListener(event -> savePrivate("", fit.displayName() + " Copy", spec(fit)));
            JButton apply = button("REFIT SELECTED", GREEN);
            Unit live = contextUnit().orElse(null);
            ShipFittingWindow.FittingOption option = live == null ? null : ShipFittingWindow.evaluate(world, live, fit);
            apply.setEnabled(option != null && option.ready());
            apply.setToolTipText(option == null ? "No matching live ship selected." : option.reason());
            apply.addActionListener(event -> applyFit(fit.displayName(), spec(fit), false));
            actions.add(edit); actions.add(copy); actions.add(apply);
            list.add(fitCard(fit.displayName(), fit, fit.defaultForHull() ? "GAME DEFAULT" : "GAME FIT", actions));
            list.add(Box.createVerticalStrut(8));
        }
        return scroll(list);
    }

    private JComponent myFitsTab() {
        JPanel list = verticalPanel(BACKGROUND);
        String commander = commanderName();
        List<PrivateShipFit> fits = ClientFitStore.fits(commander, hullId);
        PrivateShipFit standard = ClientFitStore.standard(commander, hullId);
        if (fits.isEmpty()) list.add(statusPanel("No private fits saved for this hull. Use FIT EDITOR to create one.", ORANGE));
        for (PrivateShipFit fit : fits) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            JPanel actions = cardActions();
            JButton edit = button("EDIT", CYAN);
            edit.addActionListener(event -> {
                selectedPrivateFitId = fit.id();
                loadInEditor(fit.name(), fit.spec());
            });
            JButton makeStandard = button(standard != null && standard.id().equals(fit.id())
                    ? "CLASS STANDARD" : "SET STANDARD", PURPLE);
            makeStandard.setEnabled(standard == null || !standard.id().equals(fit.id()));
            makeStandard.addActionListener(event -> setClassStandard(fit.id()));
            JButton publish = button("PUBLISH", ORANGE);
            publish.addActionListener(event -> publish(fit.name(), fit.spec()));
            JButton apply = button("REFIT SELECTED", GREEN);
            Unit live = contextUnit().orElse(null);
            ShipFittingWindow.FittingOption option = live == null ? null : ShipFittingWindow.evaluate(world, live, definition);
            apply.setEnabled(option != null && option.ready());
            apply.setToolTipText(option == null ? "No matching live ship selected." : option.reason());
            apply.addActionListener(event -> applyFit(fit.name(), fit.spec(), false));
            JButton delete = button("DELETE", RED);
            delete.addActionListener(event -> deletePrivate(fit.id()));
            actions.add(edit); actions.add(makeStandard); actions.add(publish); actions.add(apply); actions.add(delete);
            String badge = standard != null && standard.id().equals(fit.id()) ? "PRIVATE // CLASS STANDARD" : "PRIVATE";
            list.add(fitCard(fit.name(), definition, badge, actions));
            list.add(Box.createVerticalStrut(8));
        }
        return scroll(list);
    }

    private JComponent serverFitsTab() {
        JPanel root = panel(new BorderLayout(0, 8), BACKGROUND);
        JPanel heading = panel(new BorderLayout(), BACKGROUND);
        heading.add(label("SERVER FIT EXCHANGE // AUTHORIZED COMMUNITY DESIGNS", 9, Font.BOLD, ORANGE), BorderLayout.WEST);
        JButton refresh = button("REFRESH", BLUE);
        refresh.addActionListener(event -> submit("REFRESH", "", null, null, null, null));
        heading.add(refresh, BorderLayout.EAST);
        root.add(heading, BorderLayout.NORTH);

        JPanel list = verticalPanel(BACKGROUND);
        List<PublishedFit> published = WorldFitCatalog.published(world).stream()
                .filter(fit -> hullId.equals(fit.spec().hullId()))
                .sorted(Comparator.comparing(PublishedFit::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (published.isEmpty()) list.add(statusPanel("No server-published fits exist for this hull.", ORANGE));
        for (PublishedFit fit : published) {
            ShipLoadoutDefinition definition = PlayerFitRules.definition(fit.name(), fit.spec());
            JPanel actions = cardActions();
            JButton edit = button("LOAD IN EDITOR", CYAN);
            edit.addActionListener(event -> loadInEditor(fit.name() + " Copy", fit.spec()));
            JButton copy = button("SAVE PRIVATE COPY", PURPLE);
            copy.addActionListener(event -> {
                try {
                    PrivateShipFit saved = ClientFitStore.importPublished(commanderName(), fit);
                    selectedPrivateFitId = saved.id();
                    setNotice("Imported " + fit.name() + " into your private library.", GREEN, false);
                    selectedTab = 2;
                    rebuild();
                } catch (RuntimeException ex) { setNotice(ex.getMessage(), RED, false); }
            });
            JButton apply = button("REFIT SELECTED", GREEN);
            Unit live = contextUnit().orElse(null);
            ShipFittingWindow.FittingOption option = live == null ? null : ShipFittingWindow.evaluate(world, live, definition);
            apply.setEnabled(option != null && option.ready());
            apply.setToolTipText(option == null ? "No matching live ship selected." : option.reason());
            apply.addActionListener(event -> applyFit(fit.name(), fit.spec(), false));
            actions.add(edit); actions.add(copy); actions.add(apply);
            if (PlayerRegistry.localId().equals(fit.ownerPlayerId())) {
                JButton remove = button("UNPUBLISH", RED);
                remove.addActionListener(event -> submit("UNPUBLISH", "", null, null, null, fit.id()));
                actions.add(remove);
            }
            list.add(fitCard(fit.name(), definition, "BY " + fit.ownerName(), actions));
            list.add(Box.createVerticalStrut(8));
        }
        root.add(scroll(list), BorderLayout.CENTER);
        return root;
    }

    private JPanel fitCard(String title, ShipLoadoutDefinition fit, String badge, JComponent actions) {
        Color accent = fitAccent(fit);
        JPanel card = panel(new BorderLayout(12, 7), PANEL);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 226));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 2), new EmptyBorder(9, 11, 9, 11)));

        JPanel visual = panel(new BorderLayout(6, 5), PANEL_ALT);
        visual.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SOFT), new EmptyBorder(6, 7, 6, 7)));
        visual.add(new JLabel(FittingItemVisuals.hullIcon(Rules.findShip(fit.hullId()), 58)), BorderLayout.WEST);
        JPanel racks = panel(new GridLayout(0, 1, 0, 4), PANEL_ALT);
        racks.add(itemStrip(fit.weaponIds(), 42));
        racks.add(moduleStrip(ShipModuleRules.moduleIds(fit), 42));
        visual.add(racks, BorderLayout.CENTER);
        card.add(visual, BorderLayout.WEST);

        JPanel details = panel(new GridLayout(0, 1, 0, 3), PANEL);
        details.add(label(title.toUpperCase(Locale.ROOT) + "  //  " + badge, 14, Font.BOLD, TEXT));
        details.add(label("GUNS  //  " + weaponSummary(fit), 10, Font.PLAIN, MUTED));
        details.add(label("UTILITY  //  " + ShipModuleRules.summary(ShipModuleRules.moduleIds(fit)), 10, Font.PLAIN, MUTED));
        details.add(label("RANGE " + whole(WeaponRules.maxRange(fit)) + "   •   REFIT "
                + whole(fit.refitTimeSeconds()) + "s   •   COST "
                + (fit.refitCost().isEmpty() ? "None" : Rules.formatCost(fit.refitCost())),
                10, Font.PLAIN, accent));
        card.add(details, BorderLayout.CENTER);
        if (actions != null) card.add(actions, BorderLayout.EAST);
        return card;
    }

    private void refreshVisualRack(JPanel rack, ShipFitSpec spec) {
        rack.removeAll();
        rack.add(label("LIVE FIT VISUAL", 9, Font.BOLD, CYAN));
        rack.add(new JSeparator(SwingConstants.VERTICAL));
        rack.add(new JLabel(FittingItemVisuals.hullIcon(Rules.findShip(spec.hullId()), 48)));
        for (String weaponId : spec.weaponIds()) {
            WeaponType weapon = WeaponRules.WEAPONS.get(weaponId);
            JLabel icon = new JLabel(FittingItemVisuals.weaponIcon(weapon, 48));
            icon.setToolTipText(weapon == null ? weaponId : weapon.name + " // seed " + FittingItemVisuals.weaponSeed(weapon.id));
            rack.add(icon);
        }
        for (String moduleId : spec.moduleIds()) {
            ShipModuleDefinition module = ShipModuleRules.module(moduleId);
            JLabel icon = new JLabel(module == null ? ShipModuleVisuals.emptyIcon(48) : ShipModuleVisuals.icon(module, 48));
            icon.setToolTipText(module == null ? moduleId : module.displayName() + " // seed " + module.seed());
            rack.add(icon);
        }
        rack.revalidate();
        rack.repaint();
    }

    private JPanel itemStrip(List<String> weaponIds, int size) {
        JPanel strip = panel(new FlowLayout(FlowLayout.LEFT, 5, 0), PANEL_ALT);
        if (weaponIds.isEmpty()) strip.add(label("NO WEAPONS", 8, Font.BOLD, MUTED));
        for (String id : weaponIds) {
            WeaponType weapon = WeaponRules.WEAPONS.get(id);
            JLabel icon = new JLabel(FittingItemVisuals.weaponIcon(weapon, size));
            icon.setToolTipText(weapon == null ? id : weapon.name + " // seed " + FittingItemVisuals.weaponSeed(id));
            strip.add(icon);
        }
        return strip;
    }

    private JPanel moduleStrip(List<String> moduleIds, int size) {
        JPanel strip = panel(new FlowLayout(FlowLayout.LEFT, 5, 0), PANEL_ALT);
        if (moduleIds.isEmpty()) strip.add(label("NO UTILITY MODULES", 8, Font.BOLD, MUTED));
        for (ShipModuleDefinition module : ShipModuleRules.modules(moduleIds)) {
            JLabel icon = new JLabel(ShipModuleVisuals.icon(module, size));
            icon.setToolTipText(module.displayName() + " // seed " + module.seed() + " // " + ShipModuleRules.effectSummary(module));
            strip.add(icon);
        }
        return strip;
    }

    private void loadInEditor(String name, ShipFitSpec spec) {
        selectedPrivateFitId = "";
        draftName = name == null ? "" : name;
        draftSpec = spec == null ? new ShipFitSpec(hullId, List.of(), List.of()) : spec;
        selectedTab = 0;
        setNotice("Loaded fit into the editor.", CYAN, false);
        rebuild();
    }

    private void savePrivate(String existingId, String name, ShipFitSpec spec) {
        try {
            PrivateShipFit saved = ClientFitStore.save(commanderName(), existingId, name, spec);
            selectedPrivateFitId = saved.id();
            draftName = saved.name();
            draftSpec = saved.spec();
            setNotice(existingId == null || existingId.isBlank() ? "Private fit saved." : "Private fit updated.", GREEN, false);
            rebuild();
        } catch (RuntimeException ex) { setNotice(ex.getMessage(), RED, false); }
    }

    private void deletePrivate(String id) {
        try {
            if (id == null || id.isBlank() || !ClientFitStore.delete(commanderName(), id)) {
                setNotice("Select a saved private fit first.", RED, false);
                return;
            }
            if (id.equals(selectedPrivateFitId)) selectedPrivateFitId = "";
            loadStartingDraft();
            setNotice("Private fit deleted.", GREEN, false);
            rebuild();
        } catch (RuntimeException ex) { setNotice(ex.getMessage(), RED, false); }
    }

    private void setClassStandard(String id) {
        try {
            ClientFitStore.setStandard(commanderName(), hullId, id == null ? "" : id);
            setNotice(id == null || id.isBlank() ? "Class standard cleared." : "Class standard updated.", GREEN, false);
            rebuild();
        } catch (RuntimeException ex) { setNotice(ex.getMessage(), RED, false); }
    }

    private void publish(String name, ShipFitSpec spec) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) { setNotice(validation.reason(), RED, false); return; }
        if (PlayerFitRules.cleanName(name).isBlank()) { setNotice("Fit name is required before publishing.", RED, false); return; }
        submit("PUBLISH", name, spec, null, null, null);
    }

    private void applyFit(String name, ShipFitSpec spec, boolean classWide) {
        PlayerFitRules.Validation validation = PlayerFitRules.validate(spec);
        if (!validation.valid()) { setNotice(validation.reason(), RED, false); return; }
        String cleanName = PlayerFitRules.cleanName(name);
        if (cleanName.isBlank()) cleanName = "Custom Fit";
        if (classWide) {
            Base base = classRefitBase();
            if (base == null) { setNotice("No owned refit-capable shipyard exists in this system.", RED, false); return; }
            submit("REFIT_CLASS", cleanName, spec, base.id, null, null);
            return;
        }
        Unit live = contextUnit().orElse(null);
        if (live == null || !hullId.equals(live.shipTypeId)) {
            setNotice("Select exactly one live " + hullName() + " ship to apply this fit directly.", RED, false);
            return;
        }
        ShipLoadoutDefinition definition = PlayerFitRules.definition(cleanName, spec);
        ShipFittingWindow.FittingOption option = ShipFittingWindow.evaluate(world, live, definition);
        if (!option.ready()) { setNotice(option.reason(), option.current() ? MUTED : RED, false); return; }
        Base base = ShipFittingWindow.nearestRefitBase(world, live);
        if (base == null) { setNotice("No owned refit-capable shipyard exists in this system.", RED, false); return; }
        submit("REFIT", cleanName, spec, base.id, live.key(), null);
    }

    private boolean submit(String action, String name, ShipFitSpec spec,
                           String baseId, String unitKey, String publishedId) {
        boolean ok = FitNetworkBridge.submit(network, world, action, name, spec, baseId, unitKey, publishedId);
        setNotice(ok ? world.status : "Could not submit the fit request.", ok ? GREEN : RED, false);
        observedCatalogRevision = WorldFitCatalog.revision(world);
        return ok;
    }

    private Base classRefitBase() {
        if (world == null) return null;
        for (Base base : world.bases.values()) {
            if (base.hp > 0 && PlayerRegistry.isLocal(base.playerId) && base.type().canRefitShips) return base;
        }
        return null;
    }

    private void pollState() {
        if (!visible() || world == null) return;
        if (!contextUnitKey.isBlank() && contextUnit().isEmpty()) {
            contextUnitKey = "";
            setNotice("The selected ship is no longer available. Continuing in class design mode.", ORANGE, false);
            if (linkLabel != null) {
                linkLabel.setText(contextLabel());
                linkLabel.setForeground(ORANGE);
            }
        }
        long revision = WorldFitCatalog.revision(world);
        if (revision != observedCatalogRevision) {
            observedCatalogRevision = revision;
            if (selectedTab == 3) rebuild();
        }
    }

    private java.util.Optional<Unit> contextUnit() {
        if (world == null || contextUnitKey.isBlank()) return java.util.Optional.empty();
        Unit unit = world.units.get(contextUnitKey);
        if (!validLocalUnit(unit) || !hullId.equals(unit.shipTypeId)) return java.util.Optional.empty();
        return java.util.Optional.of(unit);
    }

    private static boolean validLocalUnit(Unit unit) {
        return unit != null && unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId);
    }

    private String initialHullId() {
        for (ShipType ship : fittingHulls()) if (PlayerFitRules.slotCount(ship.id) > 0) return ship.id;
        return fittingHulls().isEmpty() ? Rules.STARTING_SHIP : fittingHulls().get(0).id;
    }

    private List<ShipType> fittingHulls() {
        return Rules.SHIPS.values().stream()
                .filter(ship -> PlayerFitRules.slotCount(ship.id) > 0
                        || ShipModuleRules.moduleSlotCount(ship.id) > 0
                        || !WeaponRules.loadoutsForHull(ship.id).isEmpty())
                .sorted(Comparator.comparing(ship -> ship.name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void loadStartingDraft() {
        Unit live = contextUnit().orElse(null);
        if (live != null) {
            ShipLoadoutDefinition installed = WeaponRules.resolveForHull(live.shipTypeId, live.loadoutId);
            if (installed != null) {
                draftName = installed.displayName() + " Copy";
                draftSpec = spec(installed);
                return;
            }
        }
        PrivateShipFit standard = ClientFitStore.standard(commanderName(), hullId);
        if (standard != null) {
            draftName = standard.name();
            draftSpec = standard.spec();
            selectedPrivateFitId = standard.id();
            return;
        }
        ShipLoadoutDefinition defaultFit = WeaponRules.defaultLoadout(hullId);
        draftName = defaultFit == null ? "" : defaultFit.displayName() + " Copy";
        draftSpec = defaultFit == null
                ? new ShipFitSpec(hullId, List.of(), List.of()) : spec(defaultFit);
    }

    private void normalizeDraft(List<PrivateShipFit> fits) {
        if (!hullId.equals(draftSpec.hullId())) loadStartingDraft();
        if (selectedPrivateFitId.isBlank()) return;
        boolean exists = fits.stream().anyMatch(fit -> selectedPrivateFitId.equals(fit.id()));
        if (!exists) selectedPrivateFitId = "";
    }

    private String commanderName() {
        String name = PlayerRegistry.baseName(PlayerRegistry.localId());
        return name == null || name.isBlank() ? world.localPlayerName : name;
    }

    private String hullName() {
        ShipType hull = Rules.findShip(hullId);
        return hull == null ? hullId : hull.name;
    }

    private String contextLabel() {
        Unit unit = contextUnit().orElse(null);
        if (unit != null) return "LINKED SHIP // " + unit.type().name + " #" + unit.unitId;
        Base base = classRefitBase();
        return base == null ? "DESIGN MODE // NO SHIPYARD LINK"
                : "DESIGN MODE // CLASS SHIPYARD " + base.id;
    }

    private void setNotice(String message, Color color, boolean updateWorld) {
        notice = message == null || message.isBlank() ? "Fitting action complete." : message;
        noticeColor = color == null ? MUTED : color;
        if (updateWorld && world != null) world.status = notice;
        if (noticeLabel != null) {
            noticeLabel.setText(notice);
            noticeLabel.setForeground(noticeColor);
        }
        glass.repaint();
    }

    private static ShipFitSpec spec(ShipLoadoutDefinition fit) {
        return new ShipFitSpec(fit.hullId(), fit.weaponIds(), ShipModuleRules.moduleIds(fit));
    }

    private static ShipFitSpec specFrom(String hullId, List<JComboBox<WeaponChoice>> weapons,
                                        List<JComboBox<ModuleChoice>> modules) {
        List<String> weaponIds = new ArrayList<>();
        for (JComboBox<WeaponChoice> combo : weapons) {
            WeaponChoice choice = (WeaponChoice)combo.getSelectedItem();
            if (choice != null && !choice.id().isBlank()) weaponIds.add(choice.id());
        }
        List<String> moduleIds = new ArrayList<>();
        for (JComboBox<ModuleChoice> combo : modules) {
            ModuleChoice choice = (ModuleChoice)combo.getSelectedItem();
            if (choice != null && !choice.id().isBlank()) moduleIds.add(choice.id());
        }
        return new ShipFitSpec(hullId, weaponIds, moduleIds);
    }

    private static void loadSpec(List<JComboBox<WeaponChoice>> weapons,
                                 List<JComboBox<ModuleChoice>> modules, ShipFitSpec spec) {
        for (int i = 0; i < weapons.size(); i++) {
            String id = i < spec.weaponIds().size() ? spec.weaponIds().get(i) : "";
            selectWeapon(weapons.get(i), id);
        }
        for (int i = 0; i < modules.size(); i++) {
            String id = i < spec.moduleIds().size() ? spec.moduleIds().get(i) : "";
            selectModule(modules.get(i), id);
        }
    }

    private static void selectWeapon(JComboBox<WeaponChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).id().equals(id)) {
            combo.setSelectedIndex(i);
            return;
        }
        combo.setSelectedIndex(0);
    }

    private static void selectModule(JComboBox<ModuleChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).id().equals(id)) {
            combo.setSelectedIndex(i);
            return;
        }
        combo.setSelectedIndex(0);
    }

    private static void selectPrivate(JComboBox<PrivateChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            PrivateShipFit fit = combo.getItemAt(i).fit;
            if (fit != null && fit.id().equals(id)) { combo.setSelectedIndex(i); return; }
        }
        combo.setSelectedIndex(0);
    }

    private static void selectHull(JComboBox<HullChoice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            HullChoice choice = combo.getItemAt(i);
            if (choice.ship != null && choice.ship.id.equals(id)) { combo.setSelectedIndex(i); return; }
        }
    }

    private static String weaponSummary(ShipLoadoutDefinition fit) {
        List<String> labels = new ArrayList<>();
        for (String id : fit.weaponIds()) {
            WeaponType weapon = WeaponRules.WEAPONS.get(id);
            labels.add(weapon == null ? id : weapon.name);
        }
        return labels.isEmpty() ? "Unarmed" : String.join("  •  ", labels);
    }

    private static Color fitAccent(ShipLoadoutDefinition fit) {
        if (fit != null) for (String id : fit.weaponIds()) {
            WeaponType weapon = WeaponRules.WEAPONS.get(id);
            if (weapon != null) return weapon.color;
        }
        return CYAN;
    }

    private static JPanel section(String title, int slots, JComponent body, Color accent) {
        JPanel wrapper = panel(new BorderLayout(0, 6), FIELD);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent), new EmptyBorder(7, 8, 8, 8)));
        wrapper.add(label(title + "  //  " + slots + " SLOT" + (slots == 1 ? "" : "S"),
                10, Font.BOLD, accent), BorderLayout.NORTH);
        wrapper.add(body, BorderLayout.CENTER);
        return wrapper;
    }

    private static JPanel slotRow(String title, JComponent control, Color accent) {
        JPanel row = panel(new BorderLayout(8, 0), FIELD);
        JLabel label = label(title, 9, Font.BOLD, accent);
        label.setPreferredSize(new Dimension(92, 30));
        row.add(label, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private static JPanel cardActions() {
        return panel(new GridLayout(0, 1, 0, 5), PANEL);
    }

    private static JPanel verticalPanel(Color color) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(color);
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
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color), new EmptyBorder(10, 11, 10, 11)));
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

    private static JButton button(String text, Color accent) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setForeground(TEXT);
        button.setBackground(new Color(10, 31, 47));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent), new EmptyBorder(5, 9, 5, 9)));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.addChangeListener(event -> button.setBackground(button.getModel().isRollover()
                || button.getModel().isPressed() ? blend(accent, FIELD, .30) : new Color(10, 31, 47)));
        return button;
    }

    private static JButton tabButton(String text, boolean selected, Color accent) {
        JButton button = button(text, accent);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setForeground(selected ? Color.WHITE : MUTED);
        button.setBackground(selected ? blend(accent, FIELD, .28) : FIELD);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? accent : BORDER_SOFT, selected ? 2 : 1),
                new EmptyBorder(7, 10, 7, 10)));
        return button;
    }

    private static void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(CYAN);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CYAN), new EmptyBorder(6, 8, 6, 8)));
    }

    private static <T> void styleCombo(JComboBox<T> combo, ListCellRenderer<? super T> renderer,
                                       int width, int height) {
        combo.setForeground(TEXT);
        combo.setBackground(FIELD);
        combo.setFocusable(true);
        combo.setMaximumRowCount(10);
        combo.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        combo.setRenderer(renderer);
        combo.setPreferredSize(new Dimension(width, height));
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

    private static Color blend(Color first, Color second, double ratio) {
        double r = Math.max(0, Math.min(1, ratio));
        return new Color((int)Math.round(first.getRed() * r + second.getRed() * (1 - r)),
                (int)Math.round(first.getGreen() * r + second.getGreen() * (1 - r)),
                (int)Math.round(first.getBlue() * r + second.getBlue() * (1 - r)));
    }

    private static String whole(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < .001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record HullChoice(ShipType ship) { @Override public String toString() { return ship == null ? "Unknown" : ship.name; } }
    private record WeaponChoice(String id, WeaponType weapon) {
        WeaponChoice(WeaponType weapon) { this(weapon == null ? "" : weapon.id, weapon); }
        static WeaponChoice empty() { return new WeaponChoice("", null); }
        @Override public String toString() { return weapon == null ? "Empty hardpoint" : weapon.name; }
    }
    private record ModuleChoice(String id, ShipModuleDefinition module) {
        ModuleChoice(ShipModuleDefinition module) { this(module == null ? "" : module.id(), module); }
        static ModuleChoice empty() { return new ModuleChoice("", null); }
        @Override public String toString() { return module == null ? "Empty utility socket" : module.displayName(); }
    }
    private static final class PrivateChoice {
        final PrivateShipFit fit;
        PrivateChoice(PrivateShipFit fit) { this.fit = fit; }
        @Override public String toString() { return fit == null ? "New fit / current class setup" : fit.name(); }
    }

    private static final class HullChoiceRenderer extends JLabel implements ListCellRenderer<HullChoice> {
        HullChoiceRenderer() { setOpaque(true); setBorder(new EmptyBorder(4, 7, 4, 7)); }
        @Override public Component getListCellRendererComponent(JList<? extends HullChoice> list, HullChoice value,
                                                                int index, boolean selected, boolean focus) {
            ShipType ship = value == null ? null : value.ship;
            setIcon(FittingItemVisuals.hullIcon(ship, 42));
            setIconTextGap(9);
            setText(ship == null ? "UNKNOWN HULL" : "<html><b>" + escape(ship.name) + "</b><br><span style='font-size:9px'>seed "
                    + ship.seed + " // " + PlayerFitRules.slotCount(ship.id) + " weapons // "
                    + ShipModuleRules.moduleSlotCount(ship.id) + " utility</span></html>");
            setForeground(TEXT);
            setBackground(selected ? FIELD_HOVER : FIELD);
            return this;
        }
    }

    private static final class WeaponChoiceRenderer extends JLabel implements ListCellRenderer<WeaponChoice> {
        WeaponChoiceRenderer() { setOpaque(true); setBorder(new EmptyBorder(4, 7, 4, 7)); }
        @Override public Component getListCellRendererComponent(JList<? extends WeaponChoice> list, WeaponChoice value,
                                                                int index, boolean selected, boolean focus) {
            WeaponType weapon = value == null ? null : value.weapon;
            setIcon(FittingItemVisuals.weaponIcon(weapon, 42));
            setIconTextGap(9);
            setText(weapon == null ? "<html><b>EMPTY HARDPOINT</b><br><span style='font-size:9px'>No weapon installed</span></html>"
                    : "<html><b>" + escape(weapon.name) + "</b><br><span style='font-size:9px'>seed "
                    + FittingItemVisuals.weaponSeed(weapon.id) + " // range " + whole(weapon.range)
                    + " // damage " + whole(weapon.damage) + "</span></html>");
            setForeground(TEXT);
            setBackground(selected ? FIELD_HOVER : FIELD);
            setToolTipText(weapon == null ? "Empty hardpoint" : weapon.name);
            return this;
        }
    }

    private static final class ModuleChoiceRenderer extends JLabel implements ListCellRenderer<ModuleChoice> {
        ModuleChoiceRenderer() { setOpaque(true); setBorder(new EmptyBorder(4, 7, 4, 7)); }
        @Override public Component getListCellRendererComponent(JList<? extends ModuleChoice> list, ModuleChoice value,
                                                                int index, boolean selected, boolean focus) {
            ShipModuleDefinition module = value == null ? null : value.module;
            setIcon(module == null ? ShipModuleVisuals.emptyIcon(42) : ShipModuleVisuals.icon(module, 42));
            setIconTextGap(9);
            setText(module == null ? "<html><b>EMPTY UTILITY SOCKET</b><br><span style='font-size:9px'>No module installed</span></html>"
                    : "<html><b>" + escape(module.displayName()) + "</b><br><span style='font-size:9px'>seed "
                    + module.seed() + " // " + escape(ShipModuleRules.effectSummary(module)) + "</span></html>");
            setForeground(TEXT);
            setBackground(selected ? FIELD_HOVER : FIELD);
            setToolTipText(module == null ? "Empty utility socket" : module.description());
            return this;
        }
    }

    private static final class PrivateChoiceRenderer extends JLabel implements ListCellRenderer<PrivateChoice> {
        PrivateChoiceRenderer() { setOpaque(true); setBorder(new EmptyBorder(5, 7, 5, 7)); }
        @Override public Component getListCellRendererComponent(JList<? extends PrivateChoice> list, PrivateChoice value,
                                                                int index, boolean selected, boolean focus) {
            PrivateShipFit fit = value == null ? null : value.fit;
            ShipType hull = fit == null ? null : Rules.findShip(fit.spec().hullId());
            setIcon(FittingItemVisuals.hullIcon(hull, 34));
            setIconTextGap(8);
            setText(fit == null ? "New fit / current class setup" : fit.name());
            setForeground(TEXT);
            setBackground(selected ? FIELD_HOVER : FIELD);
            return this;
        }
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
            g.setPaint(new GradientPaint(0, 0, new Color(1, 4, 9, 236), getWidth(), getHeight(), new Color(8, 3, 19, 226)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.dispose();
        }
    }

    private static final class ConsoleSurface extends JPanel {
        ConsoleSurface(LayoutManager layout) { super(layout); setOpaque(true); setBackground(BACKGROUND); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setPaint(new GradientPaint(0, 0, new Color(10, 30, 48), getWidth(), getHeight(), VOID));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(new Color(77, 176, 221, 25));
            for (int y = 16; y < getHeight(); y += 23) g.drawLine(0, y, getWidth(), y);
            g.setColor(new Color(192, 92, 255, 20));
            for (int x = 18; x < getWidth(); x += 52) g.drawLine(x, 0, x, getHeight());
            g.dispose();
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
