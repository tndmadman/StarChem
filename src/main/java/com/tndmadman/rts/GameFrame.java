package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

final class GameFrame extends JFrame {
    private final JLayeredPane root = new JLayeredPane();
    private final MenuBackdrop backdrop = new MenuBackdrop();
    private final LobbyPanel menuPanel = new LobbyPanel(this);
    private GamePanel gamePanel;
    private EndStatePanel endStatePanel;
    private PeerNetwork network;
    private Timer networkTimer;

    GameFrame(Config config) {
        super("StarChem");
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
        if (config.showLobby) showLobby("Choose Solo, Host, or Join.");
        else launchGame(config);
    }

    void showLobby(String status) {
        stopActiveGame();
        root.removeAll();
        root.add(backdrop, JLayeredPane.DEFAULT_LAYER);
        root.add(menuPanel, JLayeredPane.PALETTE_LAYER);
        menuPanel.setStatus(status);
        setTitle("StarChem - Menu");
        layoutLayers();
        root.revalidate();
        root.repaint();
        menuPanel.requestFocusForName();
    }

    void launchGame(Config config) {
        stopActiveGame();
        World world = new World(config.playerName, config.disabledNpcFactionIds, config.systemId);
        try {
            network = PeerNetwork.start(config, world);
        } catch (IOException ex) {
            showLobby("Network failed: " + ex.getMessage());
            return;
        }
        if (network != null) {
            PeerNetwork peer = network;
            networkTimer = new Timer(16, e -> peer.tick());
            networkTimer.start();
        }
        gamePanel = new GamePanel(world, this, network, config.devMode);
        endStatePanel = new EndStatePanel(world, this, network);
        root.removeAll();
        root.add(gamePanel, JLayeredPane.DEFAULT_LAYER);
        root.add(endStatePanel, JLayeredPane.MODAL_LAYER);
        setTitle("StarChem - " + config.modeLabel() + " - " + config.playerName + " - " + world.systemName() + (config.devMode ? " - DEV" : ""));
        layoutLayers();
        root.revalidate();
        root.repaint();
        SwingUtilities.invokeLater(gamePanel::start);
    }

    private void stopActiveGame() {
        if (gamePanel != null) gamePanel.stop();
        if (endStatePanel != null) endStatePanel.stop();
        if (networkTimer != null) networkTimer.stop();
        if (network != null) network.shutdown();
        gamePanel = null;
        endStatePanel = null;
        network = null;
        networkTimer = null;
    }

    private void layoutLayers() {
        int w = Math.max(1, root.getWidth());
        int h = Math.max(1, root.getHeight());
        backdrop.setBounds(0, 0, w, h);
        if (gamePanel != null) gamePanel.setBounds(0, 0, w, h);
        if (endStatePanel != null) endStatePanel.setBounds(0, 0, w, h);
        int mw = Math.min(760, Math.max(560, w - 160));
        int mh = Math.min(660, Math.max(540, h - 120));
        menuPanel.setBounds((w - mw) / 2, (h - mh) / 2, mw, mh);
    }
}
