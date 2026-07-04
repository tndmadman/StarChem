package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class EndStatePanel extends JPanel {
    private final World world;
    private final GameFrame owner;
    private final PeerNetwork network;
    private final JButton restart = new JButton("RESPAWN");
    private final JButton lobby = new JButton("DISCONNECT");
    private final Timer timer;

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
        JLabel title = new JLabel("FLEET DESTROYED", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel help = new JLabel("You are off the leaderboard until you respawn.", SwingConstants.CENTER);
        help.setForeground(new Color(220, 238, 250));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.setOpaque(false);
        buttons.add(restart);
        buttons.add(lobby);
        card.add(title);
        card.add(help);
        card.add(buttons);
        add(card);
        restart.addActionListener(e -> restartPlayer());
        lobby.addActionListener(e -> owner.showLobby("Disconnected after fleet loss."));
        timer = new Timer(250, e -> setVisible(finished()));
        timer.start();
    }

    void stop() { timer.stop(); }

    private void restartPlayer() {
        String playerId = PlayerRegistry.localId();
        if (network == null) WorldNetAccess.respawnPlayer(world, playerId);
        else network.respawn(playerId);
        setVisible(false);
    }

    private boolean finished() {
        String playerId = PlayerRegistry.localId();
        if ("WAIT".equals(playerId)) return false;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId) && unit.hp > 0) return false;
        for (Base base : world.bases.values()) if (base.playerId.equals(playerId) && base.hp > 0) return false;
        return true;
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
