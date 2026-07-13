package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class ConnectionOverlayPanel extends JPanel {
    private final GameFrame owner;
    private final PeerNetwork network;
    private final JLabel title = new JLabel("CONNECTING TO SERVER", SwingConstants.CENTER);
    private final JLabel detail = new JLabel("Opening TCP connection...", SwingConstants.CENTER);
    private final JLabel elapsed = new JLabel("Elapsed: 0.0s", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar(0, 4);
    private final Timer timer;

    ConnectionOverlayPanel(GameFrame owner, PeerNetwork network) {
        super(new GridBagLayout());
        this.owner = owner;
        this.network = network;
        setOpaque(false);

        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        detail.setForeground(new Color(220, 238, 250));
        detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 14f));
        elapsed.setForeground(new Color(150, 190, 215));
        progress.setStringPainted(true);
        progress.setValue(0);

        JButton cancel = new JButton("CANCEL");
        cancel.addActionListener(e -> owner.showLobby("Connection cancelled."));

        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 135, 180)),
                BorderFactory.createEmptyBorder(28, 34, 28, 34)));
        card.setBackground(new Color(8, 18, 30, 242));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 0);
        card.add(title, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 16, 0);
        card.add(detail, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        card.add(progress, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 16, 0);
        card.add(elapsed, c);
        c.gridy++;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(cancel, c);

        GridBagConstraints root = new GridBagConstraints();
        root.gridx = 0;
        root.gridy = 0;
        root.weightx = 1;
        root.weighty = 1;
        root.fill = GridBagConstraints.NONE;
        add(card, root);

        timer = new Timer(100, e -> refresh());
        timer.start();
        refresh();
    }

    void stop() { timer.stop(); }

    private void refresh() {
        if (network == null || !network.clientMode()) {
            setVisible(false);
            return;
        }
        ClientConnectionProgress state = network.clientConnectionProgress();
        if (state.ready()) {
            setVisible(false);
            return;
        }
        setVisible(true);
        title.setText(state.title());
        detail.setText(state.detail());
        progress.setMaximum(state.stageCount());
        progress.setValue(state.stage());
        progress.setString("Step " + state.stage() + " of " + state.stageCount());
        elapsed.setText(String.format("Elapsed: %.1fs", state.elapsedMillis() / 1000.0));
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
