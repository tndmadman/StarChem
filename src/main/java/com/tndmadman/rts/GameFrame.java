package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

final class GameFrame extends JFrame {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final LobbyPanel lobbyPanel = new LobbyPanel(this);
    private GamePanel gamePanel;
    private PeerNetwork network;
    private Timer networkTimer;

    GameFrame(Config config) {
        super("StarChem");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);
        setContentPane(root);
        root.add(lobbyPanel, "lobby");
        if (config.showLobby) showLobby("Choose Solo, Host, or Join.");
        else launchGame(config);
    }

    void showLobby(String status) {
        if (gamePanel != null) gamePanel.stop();
        if (networkTimer != null) networkTimer.stop();
        if (network != null) network.shutdown();
        network = null;
        networkTimer = null;
        lobbyPanel.setStatus(status);
        setTitle("StarChem - Lobby");
        cards.show(root, "lobby");
        lobbyPanel.requestFocusForName();
    }

    void launchGame(Config config) {
        if (gamePanel != null) gamePanel.stop();
        if (networkTimer != null) networkTimer.stop();
        if (network != null) network.shutdown();
        World world = new World(config.playerName);
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
        gamePanel = new GamePanel(world, this, network);
        root.add(gamePanel, "game");
        setTitle("StarChem - " + config.modeLabel() + " - " + config.playerName);
        cards.show(root, "game");
        revalidate();
        repaint();
        SwingUtilities.invokeLater(gamePanel::start);
    }
}
