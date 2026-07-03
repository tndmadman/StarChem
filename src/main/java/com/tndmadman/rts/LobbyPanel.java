package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class LobbyPanel extends JPanel {
    private final GameFrame owner;
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 18);
    private final JTextField addressField = new JTextField("127.0.0.1", 18);
    private final JTextField portField = new JTextField("50000", 8);
    private final JLabel statusLabel = new JLabel("Choose Solo, Host, or Join.");

    LobbyPanel(GameFrame owner) {
        super(new BorderLayout());
        this.owner = owner;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(26, 34, 26, 34));

        JLabel title = new JLabel("STAR  CHEM");
        title.setForeground(new Color(230, 248, 255));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 42f));
        JLabel subtitle = new JLabel("Multiplayer Fleet Command");
        subtitle.setForeground(new Color(120, 205, 255));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.BOLD, 15f));
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 4));
        header.setOpaque(false);
        header.add(title);
        header.add(subtitle);

        JPanel box = new JPanel(new GridLayout(0, 2, 10, 10));
        box.setOpaque(false);
        box.add(label("Commander"));
        box.add(nameField);
        box.add(label("Address"));
        box.add(addressField);
        box.add(label("Port"));
        box.add(portField);
        JButton solo = new MenuButton("SOLO");
        JButton serve = new MenuButton("HOST");
        JButton connect = new MenuButton("JOIN");
        box.add(solo);
        box.add(serve);
        box.add(connect);
        statusLabel.setForeground(new Color(215, 232, 245));
        box.add(statusLabel);

        MenuCardPanel card = new MenuCardPanel(new BorderLayout(0, 18));
        card.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));
        card.add(header, BorderLayout.NORTH);
        card.add(box, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText())));
        serve.addActionListener(e -> startServer());
        connect.addActionListener(e -> startClient());
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(220, 238, 250));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    private void startServer() {
        try { owner.launchGame(Config.host(nameField.getText(), Config.parsePort(portField.getText()))); }
        catch (RuntimeException ex) { setStatus(ex.getMessage()); }
    }

    private void startClient() {
        try { owner.launchGame(Config.join(nameField.getText(), addressField.getText().trim(), Config.parsePort(portField.getText()))); }
        catch (RuntimeException ex) { setStatus(ex.getMessage()); }
    }

    void setStatus(String status) { statusLabel.setText(status); }
    void requestFocusForName() { SwingUtilities.invokeLater(() -> { nameField.requestFocusInWindow(); nameField.selectAll(); }); }
}
