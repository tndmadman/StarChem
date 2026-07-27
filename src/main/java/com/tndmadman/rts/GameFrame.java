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

final class GameFrame extends JFrame {
    private static final String ACTIVE_SESSION_NOTICE = "Session is already active on another connection.";
    private static final String DUPLICATE_NAME_NOTICE = "Duplicate player names are not allowed on this server. Choose a different name.";
    private static final String PASSWORD_REENTRY_NOTICE = "Re-enter the player password for this verified server identity.";
    private static final String SAVED_SIGN_IN_REFRESH_NOTICE =
            "The saved sign-in no longer matches this server. Enter the commander password again.";
    private static final String RESOURCE_CATALOG_ACTION = "toggle-resource-catalog";
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

    GameFrame(Config config) {
        super(BuildInfo.display());
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
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
            @Override public void windowClosing(WindowEvent event) { stopActiveGame(); }
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

    void launchGame(Config config) {
        GalaxyRuntimeOptions.configure(config);
        if (config.role() == NetworkRole.SERVER) {
            showLobby("Graphical HOST was removed. Start run-starchem-server.bat, then choose JOIN.");
            return;
        }
        stopActiveGame();
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId,
                config.role() == NetworkRole.SOLO);
        SkirmishRuntime.bind(world, config.skirmishSettings);
        DevTimerSettings.configure(world, config.disableProductionTimers);
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
        gamePanel = new GamePanel(world, this, activeNetwork, config.devMode, devAuthorityNetwork);
        resourceCatalogOverlay = new ResourceCatalogOverlay(gamePanel, world);
        codexOverlay = new CodexOverlay(gamePanel);
        narrationSettingsOverlay = new NarrationSettingsOverlay();
        if (soloSession) TutorialPreferenceVersion.ensureCurrent();
        tutorialOverlay = new TutorialOverlay(world, soloSession);
        inGameMenuOverlay = new InGameMenuOverlay(
                soloSession,
                this::closeInGameMenu,
                this::openGameSettingsFromMenu,
                this::startTutorialFromMenu,
                this::returnToLobbyFromMenu,
                this::quitGameFromMenu);

        installResourceCatalogHotkey(gamePanel);
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
                    + " Press I for catalog; F1 for codex; F8 for narration; ESC for menu"
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
        if (resourceCatalogOverlay.isSearchFocused()) return;
        if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
        if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
        resourceCatalogOverlay.toggle();
    }

    private void installCodexHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), CODEX_ACTION);
        target.getActionMap().put(CODEX_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (codexOverlay == null || modalMenuVisible()) return;
                if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
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
        return menuVisible() || settingsPanel.isOpen();
    }

    private void openInGameMenu() {
        if (inGameMenuOverlay == null || modalMenuVisible()) return;
        if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
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

    private void returnToLobbyFromMenu() {
        showLobby("Returned to the main menu.");
    }

    private void quitGameFromMenu() {
        stopActiveGame();
        dispose();
        System.exit(0);
    }

    private void stopActiveGame() {
        World stoppingWorld = activeWorld;
        settingsOverlay.setVisible(false);
        while (settingsPanel.isOpen()) {
            if (settingsPanel.handleEscapePressed() == SettingsPanel.Result.BACK) break;
        }
        if (inGameMenuOverlay != null) inGameMenuOverlay.close();
        soloPausedByMenu = false;
        if (gamePanel != null) gamePanel.stop();
        if (tutorialOverlay != null) tutorialOverlay.stop();
        if (gameMenuDispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(gameMenuDispatcher);
            gameMenuDispatcherInstalled = false;
        }
        WorldRuntimeCleanup.discard(stoppingWorld);
        if (resourceCatalogOverlay != null) resourceCatalogOverlay.setVisible(false);
        if (codexOverlay != null) codexOverlay.close();
        if (narrationSettingsOverlay != null) narrationSettingsOverlay.close();
        if (endStatePanel != null) endStatePanel.stop();
        if (connectionOverlayPanel != null) connectionOverlayPanel.stop();
        if (networkTimer != null) networkTimer.stop();
        if (network != null) network.shutdown();
        resetDeveloperSimulationState(stoppingWorld);
        gamePanel = null;
        resourceCatalogOverlay = null;
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
