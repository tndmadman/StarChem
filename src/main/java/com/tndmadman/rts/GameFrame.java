package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

final class GameFrame extends JFrame {
    private static final String ACTIVE_SESSION_NOTICE = "Session is already active on another connection.";
    private static final String DUPLICATE_NAME_NOTICE = "Duplicate player names are not allowed on this server. Choose a different name.";
    private static final String PASSWORD_REENTRY_NOTICE = "Re-enter the player password for this verified server identity.";
    private static final String SAVED_SIGN_IN_REFRESH_NOTICE =
            "The saved sign-in no longer matches this server. Enter the commander password again.";
    private static final String RESOURCE_CATALOG_ACTION = "toggle-resource-catalog";
    private static final String MANUFACTURING_ACTION = "toggle-manufacturing-console";
    private static final String CODEX_ACTION = "toggle-codex";
    private static final String NARRATION_SETTINGS_ACTION = "toggle-narration-settings";
    private static final String TUTORIAL_ACTION = "toggle-first-run-tutorial";

    private final JLayeredPane root = new JLayeredPane();
    private final MenuBackdrop backdrop = new MenuBackdrop();
    private final GameSettings gameSettings = GameSettings.load();
    private final LobbyPanel menuPanel = new LobbyPanel(this);
    private final SettingsPanel settingsPanel;
    private final JPanel settingsOverlay;
    private final KeyEventDispatcher gameMenuDispatcher = this::dispatchGameMenuKey;

    private GamePanel gamePanel;
    private ResourceCatalogOverlay resourceCatalogOverlay;
    private ManufacturingOverlay manufacturingOverlay;
    private CodexOverlay codexOverlay;
    private NarrationSettingsOverlay narrationSettingsOverlay;
    private TutorialOverlay tutorialOverlay;
    private InGameMenuOverlay inGameMenuOverlay;
    private EndStatePanel endStatePanel;
    private ConnectionOverlayPanel connectionOverlayPanel;
    private PeerNetwork network;
    private Timer networkTimer;
    private World activeWorld;
    private boolean gameMenuDispatcherInstalled;
    private boolean soloSession;
    private boolean soloPausedByMenu;
    private final SoloSaveService soloSaves = new SoloSaveService();
    private String activeSoloSlotId;
    private Config activeSoloConfig;
    private Timer soloAutosaveTimer;
    private boolean soloBusy;

    GameFrame(Config config) {
        super(BuildInfo.display());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1280, 900);
        setMinimumSize(new Dimension(900, 720));
        setLocationRelativeTo(null);
        setContentPane(root);

        settingsPanel = new SettingsPanel(gameSettings, this::applyDisplaySettings);
        settingsOverlay = createSettingsOverlay();
        applyInitialDisplaySettings();

        root.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { layoutLayers(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { requestExit(true); }
        });
        if (config.showLobby) showLobby("Choose Solo or Join.");
        else launchGame(config);
    }

    GameSettings gameSettings() { return gameSettings; }

    void showLobby(String status) {
        stopActiveGame();
        root.removeAll();
        root.add(backdrop, JLayeredPane.DEFAULT_LAYER);
        root.add(menuPanel, JLayeredPane.PALETTE_LAYER);
        codexOverlay = new CodexOverlay(menuPanel);
        root.add(codexOverlay, JLayeredPane.POPUP_LAYER);
        settingsOverlay.setVisible(false);
        root.add(settingsOverlay, JLayeredPane.DRAG_LAYER);
        menuPanel.setStatus(status);
        setTitle(BuildInfo.display() + " - Menu");
        layoutLayers();
        root.revalidate();
        root.repaint();
        menuPanel.requestFocusForName();
    }

    void toggleCodexFromLobby() {
        if (codexOverlay != null && !settingsPanel.isOpen()) codexOverlay.toggle();
    }

    void openLobbySettings() { openSettings(SettingsPanel.Source.LOBBY); }

    void launchNewSoloGame(Config config) {
        if (config == null || config.role() != NetworkRole.SOLO) {
            throw new IllegalArgumentException("New solo game requires solo configuration.");
        }
        launchGame(config);
    }

    void launchGame(Config config) {
        GalaxyRuntimeOptions.configure(config);
        if (config.role() == NetworkRole.SERVER) {
            showLobby("Graphical HOST was removed. Start run-starchem-server.bat, then choose JOIN.");
            return;
        }
        stopActiveGame();
        if (config.role() == NetworkRole.SOLO) {
            try {
                activeSoloSlotId = soloSaves.create(config.playerName + " Campaign").id();
            } catch (IOException | IllegalArgumentException ex) {
                showLobby("Unable to create solo campaign slot: " + ex.getMessage());
                return;
            }
        }
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId,
                config.role() == NetworkRole.SOLO);
        PlayerRegistry.activate(world);
        if (config.role() == NetworkRole.SOLO) SoloSaveService.activate(world, activeSoloSlotId);
        SkirmishRuntime.bind(world, config.skirmishSettings);
        DevTimerSettings.configure(world, config.disableProductionTimers);
        GalaxyEventDirector.configurePolicy(world, config, false);
        try {
            network = PeerNetwork.start(config, world);
        } catch (IOException ex) {
            showLobby("Network failed: " + ex.getMessage());
            return;
        }
        if (network != null) {
            PeerNetwork peer = network;
            networkTimer = new Timer(16, event -> {
                peer.tick();
                String notice = connectionNotice(world.status);
                if (!notice.isBlank()) {
                    showLobby(notice);
                    return;
                }
                if (peer.connectionFailed() && !peer.serverCertificateTrustRequired()) {
                    String failure = peer.failureMessage();
                    if (passwordRetryRequired(failure)) {
                        showLobby(SAVED_SIGN_IN_REFRESH_NOTICE);
                        SwingUtilities.invokeLater(menuPanel::retryJoinAfterCredentialReset);
                    } else {
                        showLobby(failure);
                    }
                }
            });
            networkTimer.start();
        }
        showGame(config, world, network, null);
        if (config.role() == NetworkRole.SOLO) {
            SwingUtilities.invokeLater(() -> saveSolo("initial", activeSoloSlotId, null, null));
        }
    }

    static String connectionNotice(String status) {
        return status != null && status.startsWith(ACTIVE_SESSION_NOTICE) ? DUPLICATE_NAME_NOTICE : "";
    }

    static boolean passwordRetryRequired(String failure) {
        return PASSWORD_REENTRY_NOTICE.equals(failure);
    }

    private void showGame(Config config, World world, PeerNetwork activeNetwork,
                          PeerNetwork devAuthorityNetwork) {
        activeWorld = world;
        soloSession = config.role() == NetworkRole.SOLO;
        activeSoloConfig = soloSession ? config : null;
        if (soloSession) {
            soloAutosaveTimer = new Timer(Math.max(30, config.autosaveSeconds) * 1000,
                    event -> {
                        if (!soloBusy && !soloPausedByMenu && activeWorld == world) {
                            saveSolo("autosave", activeSoloSlotId, null, null);
                        }
                    });
            soloAutosaveTimer.start();
        }
        gamePanel = new GamePanel(world, this, activeNetwork, config.devMode, devAuthorityNetwork);
        if (soloSession) gamePanel.restoreSoloView(world.soloUiSaveState());
        resourceCatalogOverlay = new ResourceCatalogOverlay(gamePanel, world);
        manufacturingOverlay = new ManufacturingOverlay(gamePanel, world, activeNetwork);
        codexOverlay = new CodexOverlay(gamePanel);
        narrationSettingsOverlay = new NarrationSettingsOverlay();
        if (soloSession) TutorialPreferenceVersion.ensureCurrent();
        // Solo tutorial progression is archived with the campaign, not the global preference node.
        tutorialOverlay = new TutorialOverlay(world, soloSession, false);
        if (soloSession) tutorialOverlay.restoreSoloState(world.soloUiSaveState().get("tutorial"));
        inGameMenuOverlay = new InGameMenuOverlay(
                soloSession,
                this::closeInGameMenu,
                this::openGameSettingsFromMenu,
                this::startTutorialFromMenu,
                this::returnToLobbyFromMenu,
                this::quitGameFromMenu,
                this::saveSoloFromMenu,
                this::saveAsSoloFromMenu,
                this::openSoloLoadDialog);

        installResourceCatalogHotkey(gamePanel);
        installManufacturingHotkey(gamePanel);
        installCodexHotkey(gamePanel);
        installNarrationSettingsHotkey(gamePanel);
        installTutorialHotkey(gamePanel);
        installGameMenuDispatcher();

        endStatePanel = new EndStatePanel(world, this, activeNetwork);
        connectionOverlayPanel = activeNetwork != null && activeNetwork.clientMode()
                ? new ConnectionOverlayPanel(this, activeNetwork) : null;

        root.removeAll();
        root.add(gamePanel, JLayeredPane.DEFAULT_LAYER);
        root.add(resourceCatalogOverlay, JLayeredPane.PALETTE_LAYER);
        root.add(manufacturingOverlay, Integer.valueOf(JLayeredPane.PALETTE_LAYER.intValue() + 20));
        root.add(tutorialOverlay, JLayeredPane.PALETTE_LAYER);
        root.add(narrationSettingsOverlay, JLayeredPane.POPUP_LAYER);
        root.add(codexOverlay, JLayeredPane.POPUP_LAYER);
        root.add(endStatePanel, JLayeredPane.MODAL_LAYER);
        if (connectionOverlayPanel != null) root.add(connectionOverlayPanel, JLayeredPane.DRAG_LAYER);
        root.add(inGameMenuOverlay, JLayeredPane.DRAG_LAYER);
        settingsOverlay.setVisible(false);
        root.add(settingsOverlay, Integer.valueOf(JLayeredPane.DRAG_LAYER.intValue() + 50));

        if (!world.status.contains("Press I")) {
            world.status = world.status
                    + " Press I for catalog; F9 manufacturing; F1 for codex; F8 for narration; ESC for menu"
                    + (soloSession
                    ? "; F2 tutorial; F3 skip step; F4 skip section; F5 restart; F6 skip tutorial."
                    : ".");
        }
        String scenario = soloSession ? " - " + SkirmishRuntime.settings(world).displayLabel() : "";
        setTitle(BuildInfo.display() + " - " + config.modeLabel() + " - " + config.playerName
                + " - " + world.systemName() + scenario + (config.devMode ? " - DEV" : ""));
        layoutLayers();
        root.revalidate();
        root.repaint();
        SwingUtilities.invokeLater(() -> {
            gamePanel.start();
            tutorialOverlay.start();
        });
    }

    private JPanel createSettingsOverlay() {
        JPanel overlay = new JPanel() {
            {
                setOpaque(false);
                setFocusable(true);
                setVisible(false);
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mousePressed(java.awt.event.MouseEvent event) {
                        SettingsPanel.Result result = settingsPanel.click(event.getX(), event.getY());
                        repaint();
                        if (result == SettingsPanel.Result.BACK) finishSettings();
                    }
                });
                addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                    @Override public void mouseMoved(java.awt.event.MouseEvent event) {
                        settingsPanel.updateHover(event.getX(), event.getY());
                        repaint();
                    }

                    @Override public void mouseDragged(java.awt.event.MouseEvent event) {
                        if (settingsPanel.drag(event.getX(), event.getY())) repaint();
                    }
                });
                addMouseWheelListener(event -> {
                    settingsPanel.handleMouseWheel(event.getWheelRotation());
                    repaint();
                });
                addKeyListener(new java.awt.event.KeyAdapter() {
                    @Override public void keyPressed(KeyEvent event) {
                        if (settingsPanel.handleKeyPressed(event)) {
                            repaint();
                            return;
                        }
                        if (event.getKeyCode() == KeyEvent.VK_ESCAPE
                                && settingsPanel.handleEscapePressed() == SettingsPanel.Result.BACK) {
                            finishSettings();
                        }
                    }
                });
            }

            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (settingsPanel.isOpen()) {
                    settingsPanel.draw((Graphics2D) graphics, getWidth(), getHeight());
                }
            }
        };
        return overlay;
    }

    private void openSettings(SettingsPanel.Source source) {
        if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
        if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
        if (manufacturingOverlay != null && manufacturingOverlay.isVisible()) manufacturingOverlay.close();
        if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
        settingsPanel.open(source);
        settingsOverlay.setVisible(true);
        root.moveToFront(settingsOverlay);
        settingsOverlay.requestFocusInWindow();
        settingsOverlay.repaint();
        root.repaint();
    }

    private void finishSettings() {
        SettingsPanel.Source source = settingsPanel.getSource();
        applyDisplaySettings();
        settingsOverlay.setVisible(false);
        if (source == SettingsPanel.Source.IN_GAME && inGameMenuOverlay != null) {
            inGameMenuOverlay.open();
        } else {
            menuPanel.requestFocusForName();
        }
        root.repaint();
    }

    private void installResourceCatalogHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0, true), RESOURCE_CATALOG_ACTION);
        target.getActionMap().put(RESOURCE_CATALOG_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { toggleResourceCatalogFromGame(); }
        });
    }

    void toggleResourceCatalogFromGame() {
        if (resourceCatalogOverlay == null || modalMenuVisible()) return;
        if (resourceCatalogOverlay.isSearchFocused()
                || manufacturingOverlay != null && manufacturingOverlay.isSearchFocused()) return;
        if (manufacturingOverlay != null && manufacturingOverlay.isVisible()) manufacturingOverlay.close();
        if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
        if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
        resourceCatalogOverlay.toggle();
    }

    private void installManufacturingHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), MANUFACTURING_ACTION);
        target.getActionMap().put(MANUFACTURING_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { toggleManufacturingFromGame(); }
        });
    }

    void toggleManufacturingFromGame() {
        if (manufacturingOverlay == null || modalMenuVisible()) return;
        if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
        if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
        if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
        manufacturingOverlay.toggle();
        if (manufacturingOverlay.isVisible()) root.moveToFront(manufacturingOverlay);
    }

    private void installCodexHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), CODEX_ACTION);
        target.getActionMap().put(CODEX_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (codexOverlay == null || modalMenuVisible()) return;
                if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
                if (manufacturingOverlay != null && manufacturingOverlay.isVisible()) manufacturingOverlay.close();
                if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
                codexOverlay.toggle();
            }
        });
    }

    private void installNarrationSettingsHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), NARRATION_SETTINGS_ACTION);
        target.getActionMap().put(NARRATION_SETTINGS_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { toggleNarrationFromGame(); }
        });
    }

    void toggleNarrationFromGame() {
        if (narrationSettingsOverlay == null || modalMenuVisible()) return;
        if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
        if (manufacturingOverlay != null && manufacturingOverlay.isVisible()) manufacturingOverlay.close();
        if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
        narrationSettingsOverlay.toggle();
    }

    private void installTutorialHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), TUTORIAL_ACTION);
        target.getActionMap().put(TUTORIAL_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (tutorialOverlay != null && !modalMenuVisible()) tutorialOverlay.toggle();
            }
        });
    }

    private void installGameMenuDispatcher() {
        if (gameMenuDispatcherInstalled) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(gameMenuDispatcher);
        gameMenuDispatcherInstalled = true;
    }

    private boolean dispatchGameMenuKey(KeyEvent event) {
        if (gamePanel == null) return false;
        if (settingsPanel.isOpen()) {
            if (event.getID() == KeyEvent.KEY_PRESSED) {
                if (settingsPanel.handleKeyPressed(event)) {
                    settingsOverlay.repaint();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE
                        && settingsPanel.handleEscapePressed() == SettingsPanel.Result.BACK) {
                    finishSettings();
                }
            }
            return true;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED
                || event.getKeyCode() != KeyEvent.VK_ESCAPE
                || event.isControlDown() || event.isAltDown() || event.isMetaDown()
                || event.getSource() instanceof JTextComponent) return false;
        if (ShipFittingWindow.closeActive()) return true;
        if (gamePanel.handleEscapeBeforeMenu()) return true;
        if (manufacturingOverlay != null && manufacturingOverlay.isVisible()) {
            manufacturingOverlay.close();
            return true;
        }
        if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) {
            resourceCatalogOverlay.close();
            return true;
        }
        if (codexOverlay != null && codexOverlay.isVisible()) {
            codexOverlay.close();
            return true;
        }
        if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) {
            narrationSettingsOverlay.close();
            return true;
        }
        if (menuVisible()) closeInGameMenu();
        else openInGameMenu();
        return true;
    }

    private boolean menuVisible() {
        return inGameMenuOverlay != null && inGameMenuOverlay.isVisible();
    }

    private boolean modalMenuVisible() {
        return menuVisible() || settingsPanel.isOpen() || ShipFittingWindow.active();
    }

    private void openInGameMenu() {
        if (inGameMenuOverlay == null || modalMenuVisible()) return;
        if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
        if (manufacturingOverlay != null && manufacturingOverlay.isVisible()) manufacturingOverlay.close();
        if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
        if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
        if (tutorialOverlay != null) tutorialOverlay.stop();
        if (soloSession && gamePanel != null) {
            gamePanel.stop();
            soloPausedByMenu = true;
        }
        inGameMenuOverlay.open();
    }

    private void closeInGameMenu() {
        if (inGameMenuOverlay == null || !menuVisible()) return;
        inGameMenuOverlay.close();
        if (soloPausedByMenu && gamePanel != null) {
            gamePanel.start();
            soloPausedByMenu = false;
        }
        if (tutorialOverlay != null) tutorialOverlay.start();
        if (gamePanel != null) gamePanel.requestFocusInWindow();
    }

    private void openGameSettingsFromMenu() {
        if (inGameMenuOverlay != null) inGameMenuOverlay.close();
        openSettings(SettingsPanel.Source.IN_GAME);
    }

    private void startTutorialFromMenu() {
        if (tutorialOverlay != null && !tutorialOverlay.active()) tutorialOverlay.toggle();
        closeInGameMenu();
    }

    private void saveSoloFromMenu() {
        if (soloSession) saveSolo("manual", activeSoloSlotId, null, null);
    }

    private void saveAsSoloFromMenu() {
        if (!soloSession || soloBusy) return;
        String name = JOptionPane.showInputDialog(this, "New campaign slot name:", "Save As",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null) return;
        try {
            SoloSaveService.Slot slot = soloSaves.create(name);
            saveSolo("manual", slot.id(), () -> {
                activeSoloSlotId = slot.id();
                SoloSaveService.activate(activeWorld, slot.id());
                FogOfWarView.flushToPersistence(activeWorld);
                JOptionPane.showMessageDialog(this, "Saved to new slot: " + slot.name());
            }, () -> {
                try { soloSaves.delete(slot.id()); }
                catch (IOException ignored) { }
            });
        } catch (IOException | IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Could not create slot",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveSolo(String reason, String slotId, Runnable onSuccess, Runnable onFailure) {
        if (!soloSession || soloBusy || activeWorld == null || slotId == null) return;
        soloBusy = true;
        World target = activeWorld;
        Config config = activeSoloConfig;
        boolean resumeAfter = !soloPausedByMenu && !menuVisible();
        if (gamePanel != null) gamePanel.stop();
        if (gamePanel != null) {
            Map<String,Object> view = new LinkedHashMap<>(gamePanel.captureSoloView());
            if (tutorialOverlay != null) view.put("tutorial", tutorialOverlay.captureSoloState());
            target.setSoloUiSaveState(view);
        }
        final SoloSaveService.CapturedSave snapshot;
        try {
            snapshot = soloSaves.capture(slotId, target, config, reason);
        } catch (IOException | RuntimeException ex) {
            soloBusy = false;
            if (target == activeWorld && resumeAfter && gamePanel != null) gamePanel.start();
            target.status = "Save capture failed; current session preserved.";
            if (!"autosave".equals(reason) && !"initial".equals(reason)) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Save Capture Error",
                        JOptionPane.ERROR_MESSAGE);
            }
            if (onFailure != null) onFailure.run();
            return;
        }
        // Disk I/O proceeds from a frozen archive payload, not from a live World.
        if (target == activeWorld && resumeAfter && gamePanel != null) gamePanel.start();
        new SwingWorker<Void,Void>() {
            @Override protected Void doInBackground() throws Exception {
                soloSaves.write(snapshot);
                return null;
            }

            @Override protected void done() {
                Exception failure = null;
                try { get(); }
                catch (Exception ex) { failure = ex; }
                soloBusy = false;
                if (failure == null) {
                    if (target == activeWorld) target.status = "Solo campaign saved.";
                    if (onSuccess != null) onSuccess.run();
                    else if ("manual".equals(reason)) {
                        JOptionPane.showMessageDialog(GameFrame.this, "Save completed successfully.");
                    }
                } else {
                    if (target == activeWorld) target.status = "Save failed; previous valid save preserved.";
                    if (!"autosave".equals(reason) && !"initial".equals(reason)) {
                        JOptionPane.showMessageDialog(GameFrame.this, "Save failed: " + failure.getMessage(),
                                "Solo Save Error", JOptionPane.ERROR_MESSAGE);
                    }
                    if (onFailure != null) onFailure.run();
                }
            }
        }.execute();
    }

    void continueSolo() {
        if (soloBusy) return;
        soloBusy = true;
        menuPanel.setStatus("Finding the latest valid solo campaign...");
        new SwingWorker<SoloSaveService.Slot,Void>() {
            @Override protected SoloSaveService.Slot doInBackground() throws Exception {
                return soloSaves.mostRecentValid();
            }
            @Override protected void done() {
                soloBusy = false;
                try {
                    SoloSaveService.Slot slot = get();
                    if (slot == null) {
                        menuPanel.setStatus("No valid solo campaign saves were found.");
                        return;
                    }
                    loadSolo(slot.id());
                } catch (Exception ex) {
                    menuPanel.setStatus("Could not inspect saves: " + ex.getMessage());
                }
            }
        }.execute();
    }

    void openSoloLoadDialog() {
        if (soloBusy) return;
        try {
            while (true) {
                List<SoloSaveService.Slot> slots = soloSaves.list();
                if (slots.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No solo campaign slots found.");
                    return;
                }
                JList<SoloSaveService.Slot> list = new JList<>(
                        slots.toArray(new SoloSaveService.Slot[0]));
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setSelectedIndex(0);
                JScrollPane scroll = new JScrollPane(list);
                scroll.setPreferredSize(new Dimension(610, Math.min(310, 42 * slots.size())));
                int chosen = JOptionPane.showOptionDialog(this, scroll, "Solo Campaigns",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                        new String[]{"Load", "Recovery...", "Rename", "Delete", "Cancel"}, "Load");
                if (chosen == 4 || chosen == JOptionPane.CLOSED_OPTION) return;
                SoloSaveService.Slot selected = list.getSelectedValue();
                if (selected == null) continue;
                if (chosen == 0) {
                    loadSolo(selected.id());
                    return;
                }
                if (chosen == 1) {
                    List<SoloSaveService.RecoveryPoint> recovery = soloSaves.recoveryPoints(selected.id());
                    if (recovery.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No recovery points are available.");
                        continue;
                    }
                    JList<SoloSaveService.RecoveryPoint> points = new JList<>(
                            recovery.toArray(new SoloSaveService.RecoveryPoint[0]));
                    points.setSelectedIndex(0);
                    JScrollPane recoveryScroll = new JScrollPane(points);
                    recoveryScroll.setPreferredSize(new Dimension(690, Math.min(300, recovery.size() * 42)));
                    if (JOptionPane.showConfirmDialog(this, recoveryScroll, "Choose Saved Snapshot",
                            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                        SoloSaveService.RecoveryPoint point = points.getSelectedValue();
                        if (point != null) {
                            loadSolo(selected.id(), point.fileName());
                            return;
                        }
                    }
                }
                if (chosen == 2) {
                    String name = JOptionPane.showInputDialog(this, "Rename campaign slot:",
                            selected.name());
                    if (name != null) soloSaves.rename(selected.id(), name);
                }
                if (chosen == 3) {
                    if (soloSession && selected.id().equals(activeSoloSlotId)) {
                        JOptionPane.showMessageDialog(this, "Cannot delete the currently running campaign.");
                    } else if (JOptionPane.showConfirmDialog(this,
                            "Permanently delete " + selected.name() + " and its recovery files?",
                            "Delete Save Slot", JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                        soloSaves.delete(selected.id());
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Solo Slots",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadSolo(String slotId) { loadSolo(slotId, null); }

    private void loadSolo(String slotId, String snapshot) {
        if (soloBusy) return;
        if (soloSession && JOptionPane.showConfirmDialog(this,
                "Load this campaign? Unsaved changes in the current game will be lost.",
                "Load Solo Campaign", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        soloBusy = true;
        World previous = activeWorld;
        boolean resumeAfterFailure = soloSession && !soloPausedByMenu;
        if (soloSession && gamePanel != null) gamePanel.stop();
        new SwingWorker<World,Void>() {
            @Override protected World doInBackground() throws Exception {
                Config config = Config.solo("Player");
                return snapshot == null ? soloSaves.load(slotId, config)
                        : soloSaves.loadSnapshot(slotId, snapshot, config);
            }
            @Override protected void done() {
                try {
                    World restored = get();
                    stopActiveGame();
                    activeSoloSlotId = slotId;
                    PlayerRegistry.activate(restored);
                    SoloSaveService.activate(restored, slotId);
                    Config loadedConfig = Config.solo(restored.localPlayerName, false,
                            SkirmishRuntime.settings(restored), restored.systemId(),
                            GalaxyRuntimeOptions.copiesPerTemplate());
                    showGame(loadedConfig, restored, null, null);
                    restored.status = "Loaded solo campaign.";
                } catch (Exception ex) {
                    if (previous != null) {
                        PlayerRegistry.activate(previous);
                        SystemAudio.listenTo(previous);
                        if (resumeAfterFailure && gamePanel != null) gamePanel.start();
                    }
                    JOptionPane.showMessageDialog(GameFrame.this,
                            "Could not load campaign. Current session was preserved.\n" + ex.getMessage(),
                            "Load Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    soloBusy = false;
                }
            }
        }.execute();
    }

    private void returnToLobbyFromMenu() { requestExit(false); }
    private void quitGameFromMenu() { requestExit(true); }

    private void requestExit(boolean quit) {
        if (soloBusy) {
            JOptionPane.showMessageDialog(this, "A save or load is still in progress.");
            return;
        }
        if (!soloSession) { exitConfirmed(quit); return; }
        int choice = JOptionPane.showOptionDialog(this,
                "Save the current solo campaign before leaving?",
                quit ? "Quit StarChem" : "Return to Main Menu",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Save & Exit", "Exit Without Saving", "Cancel"}, "Save & Exit");
        if (choice == 0) saveSolo("manual", activeSoloSlotId,
                () -> exitConfirmed(quit), () -> saveExitFailed(quit));
        else if (choice == 1) exitConfirmed(quit);
    }

    private void saveExitFailed(boolean quit) {
        int choice = JOptionPane.showOptionDialog(this,
                "The campaign could not be saved. The current game is still open.",
                "Save & Exit Failed", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null,
                new String[]{"Retry Save", "Exit Without Saving", "Cancel"}, "Retry Save");
        if (choice == 0) saveSolo("manual", activeSoloSlotId,
                () -> exitConfirmed(quit), () -> saveExitFailed(quit));
        else if (choice == 1) exitConfirmed(quit);
    }

    private void exitConfirmed(boolean quit) {
        if (quit) {
            stopActiveGame();
            dispose();
            System.exit(0);
        } else {
            showLobby("Returned to the main menu.");
        }
    }

    private void stopActiveGame() {
        if (soloAutosaveTimer != null) soloAutosaveTimer.stop();
        soloAutosaveTimer = null;
        World stoppingWorld = activeWorld;
        settingsOverlay.setVisible(false);
        while (settingsPanel.isOpen()) {
            if (settingsPanel.handleEscapePressed() == SettingsPanel.Result.BACK) break;
        }
        if (inGameMenuOverlay != null) inGameMenuOverlay.close();
        ShipFittingWindow.closeActive();
        soloPausedByMenu = false;
        if (gamePanel != null) gamePanel.stop();
        if (tutorialOverlay != null) tutorialOverlay.stop();
        if (gameMenuDispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(gameMenuDispatcher);
            gameMenuDispatcherInstalled = false;
        }
        if (manufacturingOverlay != null) manufacturingOverlay.disposeOverlay();
        WorldRuntimeCleanup.discard(stoppingWorld);
        if (resourceCatalogOverlay != null) resourceCatalogOverlay.setVisible(false);
        if (codexOverlay != null) codexOverlay.close();
        if (narrationSettingsOverlay != null) narrationSettingsOverlay.close();
        if (endStatePanel != null) endStatePanel.stop();
        if (connectionOverlayPanel != null) connectionOverlayPanel.stop();
        if (networkTimer != null) networkTimer.stop();
        if (network != null) network.shutdown();
        resetDeveloperSimulationState(stoppingWorld);
        // Teardown only after the UI loop and network threads have been stopped.
        if (stoppingWorld != null) stoppingWorld.dispose();
        gamePanel = null;
        resourceCatalogOverlay = null;
        manufacturingOverlay = null;
        codexOverlay = null;
        narrationSettingsOverlay = null;
        tutorialOverlay = null;
        inGameMenuOverlay = null;
        endStatePanel = null;
        connectionOverlayPanel = null;
        network = null;
        networkTimer = null;
        activeWorld = null;
        soloSession = false;
        activeSoloSlotId = null;
        activeSoloConfig = null;
    }

    static void resetDeveloperSimulationState(World world) {
        if (world == null) return;
        world.aiDevSettings.resetToDefaults();
        DevTimerSettings.configure(world, false);
    }

    private void layoutLayers() {
        int width = Math.max(1, root.getWidth());
        int height = Math.max(1, root.getHeight());
        backdrop.setBounds(0, 0, width, height);
        if (gamePanel != null) gamePanel.setBounds(0, 0, width, height);
        settingsOverlay.setBounds(0, 0, width, height);
        if (resourceCatalogOverlay != null) resourceCatalogOverlay.setBounds(0, 0, width, height);
        if (manufacturingOverlay != null) manufacturingOverlay.setBounds(0, 0, width, height);
        if (tutorialOverlay != null) tutorialOverlay.setBounds(0, 0, width, height);
        if (codexOverlay != null) codexOverlay.setBounds(0, 0, width, height);
        if (narrationSettingsOverlay != null) narrationSettingsOverlay.setBounds(0, 0, width, height);
        if (inGameMenuOverlay != null) inGameMenuOverlay.setBounds(0, 0, width, height);
        if (endStatePanel != null) endStatePanel.setBounds(0, 0, width, height);
        if (connectionOverlayPanel != null) connectionOverlayPanel.setBounds(0, 0, width, height);
        int menuWidth = Math.min(760, Math.max(560, width - 160));
        int menuHeight = Math.min(860, Math.max(700, height - 30));
        menuPanel.setBounds((width - menuWidth) / 2, (height - menuHeight) / 2,
                menuWidth, menuHeight);
    }

    private void applyInitialDisplaySettings() {
        if (gameSettings.isFullscreen()) {
            setUndecorated(true);
            setExtendedState(JFrame.MAXIMIZED_BOTH);
        } else {
            setResolution(gameSettings.selectedResolution());
        }
    }

    private void applyDisplaySettings() {
        setFullscreen(gameSettings.isFullscreen());
        setResolution(gameSettings.selectedResolution());
        layoutLayers();
        root.revalidate();
        root.repaint();
    }

    private void setFullscreen(boolean fullscreen) {
        if (fullscreen == isUndecorated()) return;
        boolean wasVisible = isVisible();
        if (wasVisible) setVisible(false);
        dispose();
        setUndecorated(fullscreen);
        setExtendedState(fullscreen ? JFrame.MAXIMIZED_BOTH : JFrame.NORMAL);
        if (wasVisible) setVisible(true);
        layoutLayers();
    }

    private void setResolution(Dimension requested) {
        if (requested == null || isUndecorated()) return;
        Dimension size = requested;
        if (size.width == 0 || size.height == 0) {
            GraphicsConfiguration configuration = getGraphicsConfiguration();
            if (configuration == null) return;
            Rectangle bounds = configuration.getBounds();
            size = new Dimension(bounds.width, bounds.height);
        }
        setSize(size);
        setLocationRelativeTo(null);
        validate();
        layoutLayers();
    }
}
