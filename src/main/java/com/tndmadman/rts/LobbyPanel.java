package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class LobbyPanel extends JPanel {
    enum PasswordPromptMode { LOCAL_ACCOUNT, REMOTE_SIGN_IN }

    private final GameFrame owner;
    private final JTextField nameField = new JTextField(System.getProperty("user.name", "Player"), 18);
    private final JTextField addressField = new JTextField("127.0.0.1", 18);
    private final JTextField portField = new JTextField("50000", 8);
    private final DefaultListModel<String> lanServerModel = new DefaultListModel<>();
    private final JList<String> lanServerList = new JList<>(lanServerModel);
    private final DefaultListModel<String> recentServerModel = new DefaultListModel<>();
    private final JList<String> recentServerList = new JList<>(recentServerModel);
    private final JComboBox<StarSystemDefinition> systemBox = new JComboBox<>();
    private final JComboBox<Integer> galaxyCopiesBox = new JComboBox<>(new Integer[]{1, 2});
    private final JComboBox<SkirmishPreset> skirmishPresetBox = new JComboBox<>(SkirmishPreset.values());
    private final JComboBox<NpcDifficulty> npcDifficultyBox = new JComboBox<>(NpcDifficulty.values());
    private final JComboBox<VictoryConditionDefinition> victoryConditionBox = new JComboBox<>(
            VictoryConditionRules.all().toArray(new VictoryConditionDefinition[0]));
    private final JComboBox<DiplomacySystem.MatchMode> diplomacyModeBox =
            new JComboBox<>(DiplomacySystem.MatchMode.values());
    private final JCheckBox friendlyFireBox = new JCheckBox("Friendly fire");
    private final JCheckBox sharedVisionBox = new JCheckBox("Shared vision");
    private final JCheckBox sharedVictoryBox = new JCheckBox("Shared victory");
    private final JCheckBox devBox = new JCheckBox("Dev mode");
    private final JCheckBox spawnRaidersBox = new JCheckBox("Raiders", true);
    private final JCheckBox spawnFreeMinersBox = new JCheckBox("Free Miners", true);
    private final JCheckBox spawnCorsairsBox = new JCheckBox("Corsair Syndicate", true);
    private final JLabel statusLabel = new JLabel("Choose Solo or Join.");
    private final LanDiscoveryClient discoveryClient = new LanDiscoveryClient();
    private List<LanDiscoveryProtocol.DiscoveredServer> discoveredServers = List.of();
    private List<RecentServerStore.RecentServer> recentServers = List.of();
    private final Timer discoveryTimer;

    LobbyPanel(GameFrame owner) {
        super(new BorderLayout());
        this.owner = owner;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));

        styleField(nameField);
        styleField(addressField);
        styleField(portField);
        styleServerList(lanServerList);
        styleServerList(recentServerList);
        styleCombo(systemBox);
        styleCombo(galaxyCopiesBox);
        styleCombo(skirmishPresetBox);
        styleCombo(npcDifficultyBox);
        styleCombo(victoryConditionBox);
        styleCombo(diplomacyModeBox);
        diplomacyModeBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus) {
                Component component = super.getListCellRendererComponent(list, value, index, selected, focus);
                if (component instanceof JLabel label && value instanceof DiplomacySystem.MatchMode mode) {
                    label.setText(diplomacyModeLabel(mode));
                }
                return component;
            }
        });
        for (StarSystemDefinition system : StarSystems.options()) systemBox.addItem(system);
        styleCheck(friendlyFireBox);
        styleCheck(sharedVisionBox);
        styleCheck(sharedVictoryBox);
        styleCheck(devBox);
        styleCheck(spawnRaidersBox);
        styleCheck(spawnFreeMinersBox);
        styleCheck(spawnCorsairsBox);
        skirmishPresetBox.addActionListener(e -> applyPresetDefaults());
        diplomacyModeBox.addActionListener(e -> applyDiplomacyDefaults());
        applyPresetDefaults();
        applyDiplomacyDefaults();

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

        JButton solo = new MenuButton("SOLO");
        JButton connect = new MenuButton("JOIN");
        JButton refresh = new MenuButton("REFRESH LAN");
        JButton codex = new MenuButton("CODEX");
        JButton clearSignIns = new MenuButton("CLEAR SIGN-INS");
        JButton settings = new MenuButton("SETTINGS");

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(createServerBrowser(refresh), BorderLayout.NORTH);
        center.add(createSettingsScrollPane(), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        statusLabel.setForeground(new Color(215, 232, 245));
        JPanel statusRow = new JPanel(new BorderLayout(10, 0));
        statusRow.setOpaque(false);
        statusRow.add(label("Status"), BorderLayout.WEST);
        statusRow.add(statusLabel, BorderLayout.CENTER);
        footer.add(statusRow, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new GridLayout(2, 3, 8, 8));
        buttons.setOpaque(false);
        buttons.add(solo);
        buttons.add(connect);
        buttons.add(refresh);
        buttons.add(codex);
        buttons.add(clearSignIns);
        buttons.add(settings);
        footer.add(buttons, BorderLayout.CENTER);

        MenuCardPanel card = new MenuCardPanel(new BorderLayout(0, 14));
        card.setBorder(BorderFactory.createEmptyBorder(22, 28, 22, 28));
        card.add(header, BorderLayout.NORTH);
        card.add(center, BorderLayout.CENTER);
        card.add(footer, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);

        solo.addActionListener(e -> owner.launchGame(Config.solo(nameField.getText(), devBox.isSelected(),
                selectedSkirmishSettings(), selectedSystemId(), selectedGalaxyCopies())));
        connect.addActionListener(e -> startClient());
        refresh.addActionListener(e -> refreshLanServers());
        codex.addActionListener(e -> owner.toggleCodexFromLobby());
        clearSignIns.addActionListener(e -> clearSavedSignIns());
        settings.addActionListener(e -> owner.openLobbySettings());

        lanServerList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) applySelectedLanServer();
        });
        recentServerList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) applySelectedRecentServer();
        });
        installDoubleClickJoin(lanServerList);
        installDoubleClickJoin(recentServerList);

        discoveryClient.addListener(servers -> SwingUtilities.invokeLater(() -> updateLanServers(servers)));
        discoveryTimer = new Timer(3_000, e -> refreshLanServers());
        discoveryTimer.setInitialDelay(100);
        reloadRecentServers();
    }

    private JPanel createServerBrowser(JButton refresh) {
        JPanel browser = new JPanel(new GridLayout(1, 2, 12, 0));
        browser.setOpaque(false);
        browser.add(createServerListPanel("LAN servers", lanServerList,
                "Select to fill Address and Port. Double-click to join.", refresh));
        browser.add(createServerListPanel("Recent servers", recentServerList,
                "Previously joined servers. Double-click to reconnect.", null));
        return browser;
    }

    private JPanel createServerListPanel(String title, JList<String> list, String hint, JButton headingButton) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);

        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        heading.add(label(title), BorderLayout.WEST);
        if (headingButton != null) heading.add(headingButton, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);

        JScrollPane pane = new JScrollPane(list);
        pane.setPreferredSize(new Dimension(320, 78));
        pane.setMinimumSize(new Dimension(220, 60));
        pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        pane.setBorder(BorderFactory.createLineBorder(new Color(70, 135, 180)));
        pane.getViewport().setBackground(new Color(9, 18, 31));
        panel.add(pane, BorderLayout.CENTER);
        panel.add(help(hint), BorderLayout.SOUTH);
        return panel;
    }

    private JScrollPane createSettingsScrollPane() {
        JPanel grid = createSettingsGrid();
        grid.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        JScrollPane pane = new JScrollPane(grid);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        pane.setMinimumSize(new Dimension(0, 120));
        return pane;
    }

    private JPanel createSettingsGrid() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        int row = 0;
        addFormRow(grid, row++, "Commander name", nameField);
        addFormRow(grid, row++, "Address", addressField);
        addFormRow(grid, row++, "Port", portField);
        addFormRow(grid, row++, "JOIN accounts",
                help("Remote: sign in to an existing commander. Local: an unused name creates one."));
        addFormRow(grid, row++, "Solo starting home", systemBox);
        addFormRow(grid, row++, "Solo galaxy copies", galaxyCopiesBox);
        addFormRow(grid, row++, "Solo skirmish preset", skirmishPresetBox);
        addFormRow(grid, row++, "Solo NPC difficulty", npcDifficultyBox);
        addFormRow(grid, row++, "Solo victory condition", victoryConditionBox);
        addFormRow(grid, row++, "Solo diplomacy", diplomacyModeBox);
        addFormRow(grid, row++, "Diplomacy rules", diplomacyRulesPanel());
        addFormRow(grid, row++, "Options", devBox);
        addFormRow(grid, row++, "NPC Spawns", spawnRaidersBox);
        addFormRow(grid, row++, "", spawnFreeMinersBox);
        addFormRow(grid, row, "", spawnCorsairsBox);
        return grid;
    }

    private JPanel diplomacyRulesPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        panel.setOpaque(false);
        panel.add(friendlyFireBox);
        panel.add(sharedVisionBox);
        panel.add(sharedVictoryBox);
        return panel;
    }

    private void addFormRow(JPanel panel, int row, String labelText, Component component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.weightx = 0.0;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(2, 0, 2, 12);
        panel.add(label(labelText), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(2, 0, 2, 0);
        panel.add(component, fieldConstraints);
    }

    private void installDoubleClickJoin(JList<String> list) {
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && list.getSelectedIndex() >= 0) startClient();
            }
        });
    }

    @Override public void addNotify() {
        super.addNotify();
        discoveryTimer.start();
        refreshLanServers();
    }

    @Override public void removeNotify() {
        discoveryTimer.stop();
        discoveryClient.close();
        super.removeNotify();
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
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
    }

    private void styleServerList(JList<String> list) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(3);
        list.setFixedCellHeight(23);
        list.setForeground(Color.WHITE);
        list.setBackground(new Color(9, 18, 31));
        list.setSelectionForeground(Color.WHITE);
        list.setSelectionBackground(new Color(35, 92, 132));
        list.setFont(list.getFont().deriveFont(Font.PLAIN, 12f));
        list.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
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

    private void refreshLanServers() {
        setStatus("Searching for LAN servers...");
        discoveryClient.refresh();
    }

    private void updateLanServers(List<LanDiscoveryProtocol.DiscoveredServer> servers) {
        String selectedKey = selectedLanServerKey();
        discoveredServers = servers == null ? List.of() : List.copyOf(servers);
        lanServerModel.clear();
        if (discoveredServers.isEmpty()) {
            lanServerModel.addElement("No LAN servers found");
            lanServerList.clearSelection();
            if (statusLabel.getText().startsWith("Searching")) {
                setStatus("No LAN servers found. Direct connect remains available.");
            }
            return;
        }
        for (LanDiscoveryProtocol.DiscoveredServer server : discoveredServers) {
            lanServerModel.addElement(server.displayLabel());
        }
        int restoreIndex = indexOfLanServer(selectedKey);
        lanServerList.setSelectedIndex(restoreIndex >= 0 ? restoreIndex : 0);
        setStatus("Found " + discoveredServers.size() + " LAN server"
                + (discoveredServers.size() == 1 ? "." : "s."));
    }

    private String selectedLanServerKey() {
        int index = lanServerList.getSelectedIndex();
        if (index < 0 || index >= discoveredServers.size()) return "";
        LanDiscoveryProtocol.DiscoveredServer server = discoveredServers.get(index);
        return server.host() + ':' + server.port();
    }

    private int indexOfLanServer(String key) {
        if (key == null || key.isBlank()) return -1;
        for (int i = 0; i < discoveredServers.size(); i++) {
            LanDiscoveryProtocol.DiscoveredServer server = discoveredServers.get(i);
            if ((server.host() + ':' + server.port()).equals(key)) return i;
        }
        return -1;
    }

    private void applySelectedLanServer() {
        int index = lanServerList.getSelectedIndex();
        if (index < 0 || index >= discoveredServers.size()) return;
        LanDiscoveryProtocol.DiscoveredServer server = discoveredServers.get(index);
        recentServerList.clearSelection();
        addressField.setText(server.host());
        portField.setText(Integer.toString(server.port()));
        if (!server.compatible()) setStatus("Selected server uses incompatible version " + server.version() + ".");
    }

    private void reloadRecentServers() {
        String selectedKey = selectedRecentServerKey();
        recentServers = RecentServerStore.load();
        recentServerModel.clear();
        if (recentServers.isEmpty()) {
            recentServerModel.addElement("No recent servers");
            recentServerList.clearSelection();
            return;
        }
        for (RecentServerStore.RecentServer server : recentServers) {
            recentServerModel.addElement(server.displayLabel());
        }
        int restoreIndex = indexOfRecentServer(selectedKey);
        if (restoreIndex >= 0) recentServerList.setSelectedIndex(restoreIndex);
    }

    private String selectedRecentServerKey() {
        int index = recentServerList.getSelectedIndex();
        if (index < 0 || index >= recentServers.size()) return "";
        RecentServerStore.RecentServer server = recentServers.get(index);
        return server.host() + ':' + server.port();
    }

    private int indexOfRecentServer(String key) {
        if (key == null || key.isBlank()) return -1;
        for (int i = 0; i < recentServers.size(); i++) {
            RecentServerStore.RecentServer server = recentServers.get(i);
            if ((server.host() + ':' + server.port()).equals(key)) return i;
        }
        return -1;
    }

    private void applySelectedRecentServer() {
        int index = recentServerList.getSelectedIndex();
        if (index < 0 || index >= recentServers.size()) return;
        RecentServerStore.RecentServer server = recentServers.get(index);
        lanServerList.clearSelection();
        addressField.setText(server.host());
        portField.setText(Integer.toString(server.port()));
    }

    private String selectedSystemId() {
        Object selected = systemBox.getSelectedItem();
        return selected instanceof StarSystemDefinition system ? system.id() : StarSystems.DEFAULT_SYSTEM_ID;
    }

    private int selectedGalaxyCopies() {
        Object selected = galaxyCopiesBox.getSelectedItem();
        return selected instanceof Integer copies ? copies : 1;
    }

    private SkirmishSettings selectedSkirmishSettings() {
        Object selectedPreset = skirmishPresetBox.getSelectedItem();
        Object selectedDifficulty = npcDifficultyBox.getSelectedItem();
        Object selectedVictory = victoryConditionBox.getSelectedItem();
        Object selectedDiplomacy = diplomacyModeBox.getSelectedItem();
        SkirmishPreset preset = selectedPreset instanceof SkirmishPreset value ? value : SkirmishPreset.STANDARD;
        NpcDifficulty difficulty = selectedDifficulty instanceof NpcDifficulty value ? value : NpcDifficulty.NORMAL;
        String victoryConditionId = selectedVictory instanceof VictoryConditionDefinition value
                ? value.id() : VictoryConditionRules.defaultId();
        DiplomacySystem.MatchMode mode = selectedDiplomacy instanceof DiplomacySystem.MatchMode value
                ? value : DiplomacySystem.MatchMode.FFA;
        DiplomacyMatchSettings diplomacy = new DiplomacyMatchSettings(mode,
                friendlyFireBox.isSelected(), sharedVisionBox.isSelected(), sharedVictoryBox.isSelected());
        return new SkirmishSettings(preset, difficulty, disabledNpcFactions(), victoryConditionId, diplomacy);
    }

    private void applyPresetDefaults() {
        Object selected = skirmishPresetBox.getSelectedItem();
        SkirmishPreset preset = selected instanceof SkirmishPreset value ? value : SkirmishPreset.STANDARD;
        Set<String> disabled = preset.defaultDisabledFactionIds();
        spawnRaidersBox.setSelected(!disabled.contains(Config.RAIDERS_ID));
        spawnFreeMinersBox.setSelected(!disabled.contains(Config.FREE_MINERS_ID));
        spawnCorsairsBox.setSelected(!disabled.contains(Config.CORSAIRS_ID));
    }

    private void applyDiplomacyDefaults() {
        Object selected = diplomacyModeBox.getSelectedItem();
        DiplomacySystem.MatchMode mode = selected instanceof DiplomacySystem.MatchMode value
                ? value : DiplomacySystem.MatchMode.FFA;
        boolean enabled = mode != DiplomacySystem.MatchMode.FFA;
        friendlyFireBox.setEnabled(enabled);
        sharedVisionBox.setEnabled(enabled && mode != DiplomacySystem.MatchMode.COOP_VS_NPC);
        sharedVictoryBox.setEnabled(enabled && mode != DiplomacySystem.MatchMode.COOP_VS_NPC);
        if (!enabled) {
            friendlyFireBox.setSelected(false);
            sharedVisionBox.setSelected(false);
            sharedVictoryBox.setSelected(false);
        } else if (mode == DiplomacySystem.MatchMode.COOP_VS_NPC) {
            friendlyFireBox.setSelected(false);
            sharedVisionBox.setSelected(true);
            sharedVictoryBox.setSelected(true);
        } else {
            sharedVisionBox.setSelected(true);
            sharedVictoryBox.setSelected(true);
        }
    }

    private static String diplomacyModeLabel(DiplomacySystem.MatchMode mode) {
        if (mode == null) return "Free-for-all";
        return switch (mode) {
            case FFA -> "Free-for-all";
            case FIXED_TEAMS -> "Fixed teams";
            case COOP_VS_NPC -> "Co-op vs NPC";
            case LOCKED_ALLIANCES -> "Locked alliances";
        };
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
            Config config = Config.join(nameField.getText(), addressField.getText().trim(),
                    Config.parsePort(portField.getText()), devBox.isSelected());
            if (!ensurePlayerPassword(config)) return;
            RecentServerStore.record(addressField.getText().trim(), Config.parsePort(portField.getText()),
                    selectedServerName(), selectedServerVersion());
            reloadRecentServers();
            owner.launchGame(config);
        } catch (RuntimeException ex) {
            setStatus(ex.getMessage());
        }
    }

    private String selectedServerName() {
        int lanIndex = lanServerList.getSelectedIndex();
        if (lanIndex >= 0 && lanIndex < discoveredServers.size()) return discoveredServers.get(lanIndex).name();
        int recentIndex = recentServerList.getSelectedIndex();
        return recentIndex >= 0 && recentIndex < recentServers.size() ? recentServers.get(recentIndex).name() : "";
    }

    private String selectedServerVersion() {
        int lanIndex = lanServerList.getSelectedIndex();
        if (lanIndex >= 0 && lanIndex < discoveredServers.size()) return discoveredServers.get(lanIndex).version();
        int recentIndex = recentServerList.getSelectedIndex();
        return recentIndex >= 0 && recentIndex < recentServers.size() ? recentServers.get(recentIndex).version() : "";
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

        JTextArea explanation = new JTextArea(localAccount
                ? "Sign in to this local server. If the commander name is unused, StarChem creates a new account with this password."
                : "Sign in to an existing commander on this remote server. New remote accounts must be provisioned by the server operator.");
        explanation.setEditable(false);
        explanation.setFocusable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setColumns(38);
        explanation.setRows(localAccount ? 3 : 2);
        explanation.setFont(UIManager.getFont("Label.font"));
        explanation.setForeground(UIManager.getColor("Label.foreground"));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 10, 0);
        fields.add(explanation, constraints);

        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 6, 10);
        constraints.gridy++;
        fields.add(new JLabel("Password"), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 6, 0);
        fields.add(password, constraints);

        if (localAccount) {
            constraints.gridx = 0;
            constraints.gridy++;
            constraints.weightx = 0.0;
            constraints.fill = GridBagConstraints.NONE;
            constraints.insets = new Insets(0, 0, 6, 10);
            fields.add(new JLabel("Confirm password"), constraints);

            constraints.gridx = 1;
            constraints.weightx = 1.0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.insets = new Insets(0, 0, 6, 0);
            fields.add(confirm, constraints);
        }

        constraints.gridx = 0;
        constraints.gridy++;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 0, 0, 0);
        fields.add(remember, constraints);

        String title = localAccount ? "Local Commander Sign-In or Creation" : "Commander Sign-In";
        int result = JOptionPane.showConfirmDialog(this, fields, title,
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

    void requestFocusForName() {
        SwingUtilities.invokeLater(() -> {
            nameField.requestFocusInWindow();
            nameField.selectAll();
        });
    }
}
