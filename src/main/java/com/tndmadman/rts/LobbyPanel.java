package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;

final class LobbyPanel extends JPanel {
    enum PasswordPromptMode { LOCAL_ACCOUNT, REMOTE_SIGN_IN }

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
    private final JLabel statusLabel = new JLabel("Choose Solo or Join.");

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
        JLabel subtitle = new JLabel("Solo and Dedicated Multiplayer");
        subtitle.setForeground(new Color(120, 205, 255));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.BOLD, 15f));
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 4));
        header.setOpaque(false);
        header.add(title);
        header.add(subtitle);

        JPanel box = new JPanel(new GridLayout(0, 2, 10, 10));
        box.setOpaque(false);
        box.add(label("Commander name"));
        box.add(nameField);
        box.add(label("Address"));
        box.add(addressField);
        box.add(label("Port"));
        box.add(portField);
        box.add(label("JOIN accounts"));
        box.add(help("Remote: sign in to an existing commander. Local: an unused name creates one."));
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
        JButton connect = new MenuButton("JOIN");
        JButton codex = new MenuButton("CODEX");
        JButton clearSignIns = new MenuButton("CLEAR SIGN-INS");
        box.add(solo);
        box.add(connect);
        box.add(codex);
        box.add(clearSignIns);
        box.add(label(""));
        statusLabel.setForeground(new Color(215, 232, 245));
        box.add(label("Status"));
        box.add(statusLabel);

        MenuCardPanel card = new MenuCardPanel(new BorderLayout(0, 18));
        card.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));
        card.add(header, BorderLayout.NORTH);
        card.add(box, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText(), devBox.isSelected(), disabledNpcFactions(), selectedSystemId(), selectedGalaxyCopies())));
        connect.addActionListener(e -> startClient());
        codex.addActionListener(e -> owner.toggleCodexFromLobby());
        clearSignIns.addActionListener(e -> clearSavedSignIns());
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(220, 238, 250));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    private JLabel help(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(150, 190, 215));
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
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

    private void startClient() {
        try {
            Config config = Config.join(nameField.getText(), addressField.getText().trim(), Config.parsePort(portField.getText()), devBox.isSelected(), disabledNpcFactions(), selectedSystemId(), selectedGalaxyCopies());
            if (!ensurePlayerPassword(config)) return;
            owner.launchGame(config);
        }
        catch (RuntimeException ex) { setStatus(ex.getMessage()); }
    }

    void retryJoinAfterCredentialReset() {
        setStatus("Saved sign-in expired. Enter the commander password to continue.");
        startClient();
    }

    private void clearSavedSignIns() {
        int result = JOptionPane.showConfirmDialog(this,
                "Clear all remembered multiplayer sign-ins?\n\nTrusted server certificates and the client device identity will be kept.",
                "Clear Saved Sign-Ins", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;
        try {
            PendingPlayerPassword.clearAll();
            int removed = ClientSessionPropertiesStore.clearSavedCredentials();
            setStatus(removed == 0 ? "No saved sign-ins were found." : "Cleared all saved multiplayer sign-ins.");
        } catch (RuntimeException ex) {
            setStatus(ex.getMessage() == null ? "Could not clear saved sign-ins." : ex.getMessage());
        }
    }

    static PasswordPromptMode passwordPromptMode(Config config) {
        return config != null && config.serverAddress != null && config.serverAddress.getAddress() != null
                && config.serverAddress.getAddress().isLoopbackAddress()
                ? PasswordPromptMode.LOCAL_ACCOUNT : PasswordPromptMode.REMOTE_SIGN_IN;
    }

    static boolean passwordConfirmationRequired(Config config) {
        return passwordPromptMode(config) == PasswordPromptMode.LOCAL_ACCOUNT;
    }

    private boolean ensurePlayerPassword(Config config) {
        if (SessionTokenStore.scopedCredential(config).valid()) return true;
        PasswordPromptMode mode = passwordPromptMode(config);
        boolean localAccount = mode == PasswordPromptMode.LOCAL_ACCOUNT;
        JPasswordField password = new JPasswordField(18);
        JPasswordField confirm = new JPasswordField(18);
        JCheckBox remember = new JCheckBox("Remember sign-in on this computer", true);
        styleField(password);
        styleField(confirm);
        styleCheck(remember);

        JTextArea explanation = new JTextArea(localAccount
                ? "Sign in to this local server. If the commander name is unused, StarChem creates a new account with this password."
                : "Sign in to an existing commander on this remote server. New remote accounts must be provisioned by the server operator.");
        explanation.setEditable(false);
        explanation.setFocusable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setRows(localAccount ? 3 : 2);

        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(explanation);
        panel.add(new JLabel("Password"));
        panel.add(password);
        if (localAccount) {
            panel.add(new JLabel("Confirm password"));
            panel.add(confirm);
        }
        panel.add(remember);
        String title = localAccount ? "Local Commander Sign-In or Creation" : "Commander Sign-In";
        int result = JOptionPane.showConfirmDialog(this, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            setStatus("Join cancelled.");
            return false;
        }
        char[] first = password.getPassword();
        char[] second = localAccount ? confirm.getPassword() : new char[0];
        try {
            if (first.length < 6) {
                setStatus("Password must be at least 6 characters.");
                return false;
            }
            if (localAccount && !java.util.Arrays.equals(first, second)) {
                setStatus("Passwords did not match.");
                return false;
            }
            PendingPlayerPassword.remember(config, first, remember.isSelected());
            return true;
        } finally {
            java.util.Arrays.fill(first, '\0');
            java.util.Arrays.fill(second, '\0');
        }
    }

    void setStatus(String status) { statusLabel.setText(status); }
    void requestFocusForName() { SwingUtilities.invokeLater(() -> { nameField.requestFocusInWindow(); nameField.selectAll(); }); }
}
