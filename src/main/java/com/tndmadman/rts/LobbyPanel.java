package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class LobbyPanel extends JPanel {
    private final GameFrame owner;
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 18);
    private final JLabel statusLabel = new JLabel("Choose Solo.");

    LobbyPanel(GameFrame owner) {
        super(new BorderLayout(16, 16));
        this.owner = owner;
        setBorder(BorderFactory.createEmptyBorder(42, 60, 42, 60));
        setBackground(new Color(4, 8, 15));

        JLabel title = new JLabel("STAR  CHEM");
        title.setForeground(new Color(224, 245, 255));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 48f));
        JLabel subtitle = new JLabel("Modular fleet command prototype");
        subtitle.setForeground(new Color(112, 190, 235));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.BOLD, 16f));
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 7));
        header.setOpaque(false);
        header.add(title);
        header.add(subtitle);
        add(header, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8, 8, 8, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        styleField(nameField);
        JLabel label = new JLabel("Commander");
        label.setForeground(new Color(218,235,248));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        c.gridx = 0; c.gridy = 0; form.add(label, c);
        c.gridx = 1; form.add(nameField, c);
        JButton solo = new MenuButton("SOLO");
        c.gridx = 0; c.gridy = 1; c.gridwidth = 2; form.add(solo, c);
        statusLabel.setForeground(new Color(210, 228, 245));
        c.gridy = 2; form.add(statusLabel, c);
        JPanel card = new MenuCardPanel(new BorderLayout());
        card.add(form);
        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        center.add(card);
        add(center);
        solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText())));
    }

    void setStatus(String status) { statusLabel.setText(status); }
    void requestFocusForName() { SwingUtilities.invokeLater(() -> { nameField.requestFocusInWindow(); nameField.selectAll(); }); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        int w = getWidth(), h = getHeight();
        g2.setPaint(new GradientPaint(0, 0, new Color(4,8,15), w, h, new Color(12,25,44)));
        g2.fillRect(0, 0, w, h);
        g2.setColor(new Color(50,130,190,38));
        g2.fillOval(w-360, -160, 520, 520);
        g2.dispose();
    }

    private void styleField(JTextField f) {
        f.setForeground(Color.WHITE);
        f.setCaretColor(Color.WHITE);
        f.setBackground(new Color(9,18,31));
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(70,115,150)), BorderFactory.createEmptyBorder(8,10,8,10)));
    }
}
