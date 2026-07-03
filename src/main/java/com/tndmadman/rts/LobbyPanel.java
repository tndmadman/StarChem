package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class LobbyPanel extends JPanel {
    private final GameFrame owner;
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 18);
    private final JTextField addressField = new JTextField("127.0.0.1", 18);
    private final JTextField portField = new JTextField("50000", 8);
    private final JLabel statusLabel = new JLabel("Choose a mode.");

    LobbyPanel(GameFrame owner) {
        super(new BorderLayout());
        this.owner = owner;
        setBorder(BorderFactory.createEmptyBorder(40, 60, 40, 60));
        JPanel box = new JPanel(new GridLayout(0, 2, 8, 8));
        box.add(new JLabel("Name"));
        box.add(nameField);
        box.add(new JLabel("Address"));
        box.add(addressField);
        box.add(new JLabel("Port"));
        box.add(portField);
        JButton solo = new JButton("Solo");
        JButton serve = new JButton("Host");
        JButton connect = new JButton("Join");
        box.add(solo);
        box.add(serve);
        box.add(connect);
        box.add(statusLabel);
        add(new JLabel("StarChem"), BorderLayout.NORTH);
        add(box, BorderLayout.CENTER);
        solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText())));
        serve.addActionListener(e -> startServer());
        connect.addActionListener(e -> startClient());
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
