package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;

final class LobbyPanel extends JPanel {
    private final GameFrame owner;
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 18);
    private final JTextField addressField = new JTextField("127.0.0.1", 18);
    private final JTextField portField = new JTextField("50000", 8);
    private final JComboBox<StarSystemDefinition> systemBox = new JComboBox<>();
    private final JComboBox<Integer> galaxyCopiesBox = new JComboBox<>(new Integer[]{1, 2});
    private final JCheckBox devBox = new JCheckBox("Dev mode");
    private final JCheckBox spawnRaidersBox = new JCheckBox("Raiders", true);
    private final JCheckBox spawnFreeMinersBox = new JCheckBox("Free Miners", true);
    private final JCheckBox spawnCorsairsBox = new JCheckBox("Corsair Syndicate", true);
    private final JLabel statusLabel = new JLabel("Choose Solo, Host, or Join.");

    LobbyPanel(GameFrame owner) {
        super(new BorderLayout());
        this.owner = owner;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(26, 34, 26, 34));
        styleField(nameField);
        styleField(addressField);
        styleField(portField);
        styleCombo(systemBox);
        styleCombo(galaxyCopiesBox);
        for (StarSystemDefinition system : StarSystems.options()) systemBox.addItem(system);
        styleCheck(devBox);
        styleCheck(spawnRaidersBox);
        styleCheck(spawnFreeMinersBox);
        styleCheck(spawnCorsairsBox);

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
        box.add(label("Starting home"));
        box.add(systemBox);
        box.add(label("Copies per system"));
        box.add(galaxyCopiesBox);
        box.add(label("Options"));
        box.add(devBox);
        box.add(label("NPC Spawns"));
        box.add(spawnRaidersBox);
        box.add(label(""));
        box.add(spawnFreeMinersBox);
        box.add(label(""));
        box.add(spawnCorsairsBox);
        JButton solo = new MenuButton("SOLO");
        JButton serve = new MenuButton("HOST");
        JButton connect = new MenuButton("JOIN");
        JButton codex = new MenuButton("CODEX");
        box.add(solo);
        box.add(serve);
        box.add(connect);
        box.add(codex);
        statusLabel.setForeground(new Color(215, 232, 245));
        box.add(label("Status"));
        box.add(statusLabel);

        MenuCardPanel card = new MenuCardPanel(new BorderLayout(0, 18));
        card.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));
        card.add(header, BorderLayout.NORTH);
        card.add(box, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText(), devBox.isSelected(), disabledNpcFactions(), selectedSystemId(), selectedGalaxyCopies())));
        serve.addActionListener(e -> startServer());
        connect.addActionListener(e -> startClient());
        codex.addActionListener(e -> owner.toggleCodexFromLobby());
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(220, 238, 250));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    private void styleField(JTextField field) {
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBackground(new Color(9, 18, 31));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 135, 180)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    private void styleCombo(JComboBox<?> box) {
        box.setForeground(Color.WHITE);
        box.setBackground(new Color(9, 18, 31));
        box.setBorder(BorderFactory.createLineBorder(new Color(70, 135, 180)));
    }

    private void styleCheck(JCheckBox box) {
        box.setOpaque(false);
        box.setForeground(new Color(220, 238, 250));
        box.setFont(box.getFont().deriveFont(Font.BOLD, 13f));
    }

    private String selectedSystemId() {
        Object selected = systemBox.getSelectedItem();
        return selected instanceof StarSystemDefinition system ? system.id() : StarSystems.DEFAULT_SYSTEM_ID;
    }

    private int selectedGalaxyCopies() {
        Object selected = galaxyCopiesBox.getSelectedItem();
        return selected instanceof Integer copies ? copies : 1;
    }

    private Set<String> disabledNpcFactions() {
        Set<String> disabled = new LinkedHashSet<>();
        if (!spawnRaidersBox.isSelected()) disabled.add(Config.RAIDERS_ID);
        if (!spawnFreeMinersBox.isSelected()) disabled.add(Config.FREE_MINERS_ID);
        if (!spawnCorsairsBox.isSelected()) disabled.add(Config.CORSAIRS_ID);
        return disabled;
    }

    private void startServer() {
        try { owner.launchGame(Config.host(nameField.getText(), Config.parsePort(portField.getText()), devBox.isSelected(), disabledNpcFactions(), selectedSystemId(), selectedGalaxyCopies())); }
        catch (RuntimeException ex) { setStatus(ex.getMessage()); }
    }

    private void startClient() {
        try { owner.launchGame(Config.join(nameField.getText(), addressField.getText().trim(), Config.parsePort(portField.getText()), devBox.isSelected(), disabledNpcFactions(), selectedSystemId(), selectedGalaxyCopies())); }
        catch (RuntimeException ex) { setStatus(ex.getMessage()); }
    }

    void setStatus(String status) { statusLabel.setText(status); }
    void requestFocusForName() { SwingUtilities.invokeLater(() -> { nameField.requestFocusInWindow(); nameField.selectAll(); }); }
}
