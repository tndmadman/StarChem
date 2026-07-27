package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class EndStatePanel extends JPanel {
    private final World world;
    private final GameFrame owner;
    private final PeerNetwork network;
    private final JLabel title = new JLabel("FLEET DESTROYED", SwingConstants.CENTER);
    private final JLabel help = new JLabel("You are off the leaderboard until you respawn.", SwingConstants.CENTER);
    private final JButton restart = new JButton("RESPAWN");
    private final JButton lobby = new JButton("DISCONNECT");
    private final Timer timer;
    private boolean victoryMode;
    private boolean victoryDismissed;

    EndStatePanel(World world, GameFrame owner, PeerNetwork network) {
        super(new GridBagLayout());
        this.world = world;
        this.owner = owner;
        this.network = network;
        setOpaque(false);
        setVisible(false);
        JPanel card = new JPanel(new GridLayout(0, 1, 0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(26, 34, 26, 34));
        card.setBackground(new Color(8, 18, 30, 235));
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        help.setForeground(new Color(220, 238, 250));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.setOpaque(false);
        buttons.add(restart);
        buttons.add(lobby);
        card.add(title);
        card.add(help);
        card.add(buttons);
        add(card);
        restart.addActionListener(e -> primaryAction());
        lobby.addActionListener(e -> leaveMatch());
        timer = new Timer(250, e -> refresh());
        timer.start();
    }

    void stop() { timer.stop(); }

    private void primaryAction() {
        if (victoryMode) {
            victoryDismissed = true;
            setVisible(false);
            return;
        }
        restartPlayer();
    }

    private void restartPlayer() {
        String playerId = PlayerRegistry.localId();
        if (network == null) WorldNetAccess.respawnPlayer(world, playerId);
        else network.respawn(playerId);
        setVisible(false);
    }

    private void leaveMatch() {
        owner.showLobby(victoryMode
                ? "Disconnected after objective victory."
                : "Disconnected after fleet loss.");
    }

    private void refresh() {
        if (!ready()) {
            setVisible(false);
            return;
        }

        ObjectiveView objective = ObjectiveSystem.view(world);
        if (objective.completed() && !victoryDismissed) {
            victoryMode = true;
            title.setText("MATCH OBJECTIVE COMPLETE");
            String by = objective.completedBy().isBlank() ? "" : " by " + objective.completedBy();
            help.setText(objective.title() + " was completed" + by + ".");
            restart.setText("CONTINUE PLAYING");
            lobby.setText(network == null ? "RETURN TO LOBBY" : "DISCONNECT");
            setVisible(true);
            return;
        }

        if (fleetDestroyed()) {
            victoryMode = false;
            title.setText("FLEET DESTROYED");
            help.setText("You are off the leaderboard until you respawn.");
            restart.setText("RESPAWN");
            lobby.setText(network == null ? "RETURN TO LOBBY" : "DISCONNECT");
            setVisible(true);
            return;
        }

        setVisible(false);
    }

    private boolean ready() {
        return network == null || !network.clientMode()
                || network.clientReady() && !network.clientReconnecting();
    }

    private boolean fleetDestroyed() {
        String playerId = PlayerRegistry.localId();
        return !"WAIT".equals(playerId) && !world.hasLiveAssets(playerId);
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
