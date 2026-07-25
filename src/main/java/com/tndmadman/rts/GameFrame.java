package com.tndmadman.rts;

import javax.swing.*;
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
    private static final String RESOURCE_CATALOG_ACTION = "toggle-resource-catalog";
    private static final String CODEX_ACTION = "toggle-codex";
    private static final String NARRATION_SETTINGS_ACTION = "toggle-narration-settings";

    private final JLayeredPane root = new JLayeredPane();
    private final MenuBackdrop backdrop = new MenuBackdrop();
    private final LobbyPanel menuPanel = new LobbyPanel(this);
    private GamePanel gamePanel;
    private ResourceCatalogOverlay resourceCatalogOverlay;
    private CodexOverlay codexOverlay;
    private NarrationSettingsOverlay narrationSettingsOverlay;
    private EndStatePanel endStatePanel;
    private ConnectionOverlayPanel connectionOverlayPanel;
    private PeerNetwork network;
    private Timer networkTimer;
    private World activeWorld;

    GameFrame(Config config) {
        super(BuildInfo.display());
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);
        setContentPane(root);
        root.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { layoutLayers(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { stopActiveGame(); }
        });
        if (config.showLobby) showLobby("Choose Solo or Join.");
        else launchGame(config);
    }

    void showLobby(String status) {
        stopActiveGame();
        root.removeAll();
        root.add(backdrop, JLayeredPane.DEFAULT_LAYER);
        root.add(menuPanel, JLayeredPane.PALETTE_LAYER);
        codexOverlay = new CodexOverlay(menuPanel);
        root.add(codexOverlay, JLayeredPane.POPUP_LAYER);
        menuPanel.setStatus(status);
        setTitle(BuildInfo.display() + " - Menu");
        layoutLayers();
        root.revalidate();
        root.repaint();
        menuPanel.requestFocusForName();
    }

    void toggleCodexFromLobby() {
        if (codexOverlay != null) codexOverlay.toggle();
    }

    void launchGame(Config config) {
        GalaxyRuntimeOptions.configure(config);
        if (config.role() == NetworkRole.SERVER) {
            showLobby("Graphical HOST was removed. Start run-starchem-server.bat, then choose JOIN.");
            return;
        }
        stopActiveGame();
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId, config.role() == NetworkRole.SOLO);
        DevTimerSettings.configure(world, config.disableProductionTimers);
        try {
            network = PeerNetwork.start(config, world);
        } catch (IOException ex) {
            showLobby("Network failed: " + ex.getMessage());
            return;
        }
        if (network != null) {
            PeerNetwork peer = network;
            networkTimer = new Timer(16, e -> {
                peer.tick();
                String notice = connectionNotice(world.status);
                if (!notice.isBlank()) {
                    showLobby(notice);
                    return;
                }
                if (peer.connectionFailed() && !peer.serverCertificateTrustRequired()) showLobby(peer.failureMessage());
            });
            networkTimer.start();
        }
        showGame(config, world, network, null);
    }

    static String connectionNotice(String status) {
        return status != null && status.startsWith(ACTIVE_SESSION_NOTICE) ? DUPLICATE_NAME_NOTICE : "";
    }

    private void showGame(Config config, World world, PeerNetwork activeNetwork, PeerNetwork devAuthorityNetwork) {
        activeWorld = world;
        gamePanel = new GamePanel(world, this, activeNetwork, config.devMode, devAuthorityNetwork);
        resourceCatalogOverlay = new ResourceCatalogOverlay(gamePanel, world);
        codexOverlay = new CodexOverlay(gamePanel);
        narrationSettingsOverlay = new NarrationSettingsOverlay();
        installResourceCatalogHotkey(gamePanel);
        installCodexHotkey(gamePanel);
        installNarrationSettingsHotkey(gamePanel);
        endStatePanel = new EndStatePanel(world, this, activeNetwork);
        connectionOverlayPanel = activeNetwork != null && activeNetwork.clientMode()
                ? new ConnectionOverlayPanel(this, activeNetwork) : null;
        root.removeAll();
        root.add(gamePanel, JLayeredPane.DEFAULT_LAYER);
        root.add(resourceCatalogOverlay, JLayeredPane.PALETTE_LAYER);
        root.add(narrationSettingsOverlay, JLayeredPane.POPUP_LAYER);
        root.add(codexOverlay, JLayeredPane.POPUP_LAYER);
        root.add(endStatePanel, JLayeredPane.MODAL_LAYER);
        if (connectionOverlayPanel != null) root.add(connectionOverlayPanel, JLayeredPane.DRAG_LAYER);
        if (!world.status.contains("Press I")) world.status = world.status + " Press I for catalog; F1 for codex; F8 for narration.";
        setTitle(BuildInfo.display() + " - " + config.modeLabel() + " - " + config.playerName + " - " + world.systemName() + (config.devMode ? " - DEV" : ""));
        layoutLayers();
        root.revalidate();
        root.repaint();
        SwingUtilities.invokeLater(gamePanel::start);
    }

    private void installResourceCatalogHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0, true), RESOURCE_CATALOG_ACTION);
        target.getActionMap().put(RESOURCE_CATALOG_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (resourceCatalogOverlay == null) return;
                if (resourceCatalogOverlay.isSearchFocused()) return;
                if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
                if (narrationSettingsOverlay != null && narrationSettingsOverlay.isVisible()) narrationSettingsOverlay.close();
                resourceCatalogOverlay.toggle();
            }
        });
    }

    private void installCodexHotkey(JComponent target) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), CODEX_ACTION);
        target.getActionMap().put(CODEX_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (codexOverlay == null) return;
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
            @Override public void actionPerformed(ActionEvent e) {
                if (narrationSettingsOverlay == null) return;
                if (resourceCatalogOverlay != null && resourceCatalogOverlay.isVisible()) resourceCatalogOverlay.close();
                if (codexOverlay != null && codexOverlay.isVisible()) codexOverlay.close();
                narrationSettingsOverlay.toggle();
            }
        });
    }

    private void stopActiveGame() {
        World stoppingWorld = activeWorld;
        if (gamePanel != null) gamePanel.stop();
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
        endStatePanel = null;
        connectionOverlayPanel = null;
        network = null;
        networkTimer = null;
        activeWorld = null;
    }


    static void resetDeveloperSimulationState(World world) {
        if (world == null) return;
        world.aiDevSettings.resetToDefaults();
        DevTimerSettings.configure(world, false);
    }

    private void layoutLayers() {
        int w = Math.max(1, root.getWidth());
        int h = Math.max(1, root.getHeight());
        backdrop.setBounds(0, 0, w, h);
        if (gamePanel != null) gamePanel.setBounds(0, 0, w, h);
        if (resourceCatalogOverlay != null) resourceCatalogOverlay.setBounds(0, 0, w, h);
        if (codexOverlay != null) codexOverlay.setBounds(0, 0, w, h);
        if (narrationSettingsOverlay != null) narrationSettingsOverlay.setBounds(0, 0, w, h);
        if (endStatePanel != null) endStatePanel.setBounds(0, 0, w, h);
        if (connectionOverlayPanel != null) connectionOverlayPanel.setBounds(0, 0, w, h);
        int mw = Math.min(760, Math.max(560, w - 160));
        int mh = Math.min(700, Math.max(580, h - 100));
        menuPanel.setBounds((w - mw) / 2, (h - mh) / 2, mw, mh);
    }
}
