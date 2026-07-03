package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class GameFrame extends JFrame {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final LobbyPanel lobbyPanel = new LobbyPanel(this);
    private GamePanel gamePanel;

    GameFrame(Config config) {
        super("StarChem");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);
        setContentPane(root);
        root.add(lobbyPanel, "lobby");
        if (config.showLobby) showLobby("Choose Solo.");
        else launchGame(config);
    }

    void showLobby(String status) {
        if (gamePanel != null) gamePanel.stop();
        lobbyPanel.setStatus(status);
        setTitle("StarChem - Lobby");
        cards.show(root, "lobby");
        lobbyPanel.requestFocusForName();
    }

    void launchGame(Config config) {
        if (gamePanel != null) gamePanel.stop();
        World world = new World(config.playerName);
        gamePanel = new GamePanel(world, this);
        root.add(gamePanel, "game");
        setTitle("StarChem - " + config.modeLabel() + " - " + config.playerName);
        cards.show(root, "game");
        revalidate();
        repaint();
        SwingUtilities.invokeLater(gamePanel::start);
    }
}
