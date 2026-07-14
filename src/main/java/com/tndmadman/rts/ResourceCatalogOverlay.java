package com.tndmadman.rts;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

final class ResourceCatalogOverlay extends JPanel {
    private static final int RESOURCES_TAB = 0;
    private static final int SYSTEMS_TAB = 1;
    private static final Color PANEL = new Color(10, 18, 30);
    private static final Color FIELD = new Color(7, 14, 24);
    private static final Color BORDER = new Color(80, 145, 188);
    private static final Color TEXT = new Color(229, 243, 252);
    private static final Color MUTED = new Color(166, 197, 220);

    private final GamePanel returnFocus;
    private final World world;
    private final JTextField searchField = new JTextField();
    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultListModel<ResourceSystemCatalog.Entry> resourceModel = new DefaultListModel<>();
    private final JList<ResourceSystemCatalog.Entry> resourceList = new JList<>(resourceModel);
    private final JTextArea resourceDetails = new JTextArea();
    private final CatalogVisuals.ResourcePreview resourcePreview = new CatalogVisuals.ResourcePreview();
    private final DefaultListModel<ResourceSystemCatalog.SystemEntry> systemModel = new DefaultListModel<>();
    private final JList<ResourceSystemCatalog.SystemEntry> systemList = new JList<>(systemModel);
    private final JTextArea systemDetails = new JTextArea();
    private final CatalogVisuals.SystemPreview systemPreview = new CatalogVisuals.SystemPreview();
    private final JLabel countLabel = new JLabel();
    private String previousStatus = "";

    ResourceCatalogOverlay(GamePanel returnFocus, World world) {
        super(new GridBagLayout());
        this.returnFocus = returnFocus;
        this.world = world;
        setOpaque(false);
        setVisible(false);
        setFocusable(true);

        JPanel card = buildCard();
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(28, 34, 28, 34);
        add(card, constraints);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        tabs.addChangeListener(e -> updateCount());
        resourceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelectedResource();
        });
        systemList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelectedSystem();
        });
        installKeyBindings();
        refresh();
    }

    void toggle() {
        if (isVisible()) close(); else open();
    }

    void open() {
        if (isVisible()) return;
        previousStatus = world.status;
        world.status = "Resource catalog open. Search resources and items or switch to the Systems tab; press Escape to close.";
        setVisible(true);
        refresh();
        SwingUtilities.invokeLater(() -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        });
    }

    void close() {
        if (!isVisible()) return;
        setVisible(false);
        if (world.status.startsWith("Resource catalog open.")) world.status = previousStatus;
        if (returnFocus != null) {
            returnFocus.requestFocusInWindow();
            returnFocus.repaint();
        }
    }

    boolean isSearchFocused() {
        return isVisible() && searchField.isFocusOwner();
    }

    private JPanel buildCard() {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setBackground(PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 2),
                BorderFactory.createEmptyBorder(18, 20, 16, 20)));
        card.setMinimumSize(new Dimension(780, 540));
        card.setPreferredSize(new Dimension(1120, 730));

        JPanel header = new JPanel(new BorderLayout(14, 10));
        header.setOpaque(false);
        JLabel title = new JLabel("RESOURCE & SYSTEM CATALOG");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("Visual material index, build-cost usage, system templates, belts, and orbital distances");
        subtitle.setForeground(MUTED);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);

        JButton close = new MenuButton("CLOSE");
        close.addActionListener(e -> close());
        header.add(heading, BorderLayout.CENTER);
        header.add(close, BorderLayout.EAST);

        styleField(searchField);
        searchField.setToolTipText("Search names, IDs, material families, systems, belts, node types, celestial bodies, or orbit distances");
        JLabel searchLabel = new JLabel("SEARCH");
        searchLabel.setForeground(MUTED);
        searchLabel.setFont(searchLabel.getFont().deriveFont(Font.BOLD, 11f));
        JPanel search = new JPanel(new BorderLayout(10, 0));
        search.setOpaque(false);
        search.add(searchLabel, BorderLayout.WEST);
        search.add(searchField, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(search, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        tabs.setBackground(PANEL);
        tabs.setForeground(TEXT);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 12f));
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab("RESOURCES & ITEMS", buildResourceTab());
        tabs.addTab("SYSTEMS", buildSystemTab());
        card.add(tabs, BorderLayout.CENTER);

        countLabel.setForeground(MUTED);
        countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN, 11f));
        JLabel help = new JLabel("Type to search   Alt+1/Alt+2: switch tabs   Up/Down: select   Esc: close", SwingConstants.RIGHT);
        help.setForeground(MUTED);
        help.setFont(help.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(countLabel, BorderLayout.WEST);
        footer.add(help, BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildResourceTab() {
        styleList(resourceList, 62);
        resourceList.setCellRenderer(new ResourceRenderer());
        configureDetails(resourceDetails);
        JPanel detail = buildVisualDetails(resourcePreview, resourceDetails);
        return buildSplit(resourceList, detail);
    }

    private JComponent buildSystemTab() {
        styleList(systemList, 62);
        systemList.setCellRenderer(new SystemRenderer());
        configureDetails(systemDetails);
        JPanel detail = buildVisualDetails(systemPreview, systemDetails);
        return buildSplit(systemList, detail);
    }

    private JPanel buildVisualDetails(JComponent visual, JTextArea details) {
        JPanel panel = new JPanel(new BorderLayout(0, 9));
        panel.setBackground(FIELD);
        panel.add(visual, BorderLayout.NORTH);
        JScrollPane detailScroll = new JScrollPane(details);
        styleScroll(detailScroll);
        panel.add(detailScroll, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildSplit(JList<?> list, JComponent details) {
        JScrollPane listScroll = new JScrollPane(list);
        styleScroll(listScroll);
        listScroll.setPreferredSize(new Dimension(350, 500));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, details);
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        split.setDividerSize(8);
        split.setResizeWeight(0.32);
        return split;
    }

    private void refresh() {
        refreshResources();
        refreshSystems();
        updateCount();
    }

    private void refreshResources() {
        ResourceSystemCatalog.Entry selected = resourceList.getSelectedValue();
        Material selectedMaterial = selected == null ? null : selected.material();
        List<ResourceSystemCatalog.Entry> filtered = ResourceSystemCatalog.filterEntries(searchField.getText());
        resourceModel.clear();
        for (ResourceSystemCatalog.Entry entry : filtered) resourceModel.addElement(entry);

        int selectedIndex = -1;
        if (selectedMaterial != null) {
            for (int i = 0; i < resourceModel.size(); i++) {
                if (resourceModel.get(i).material() == selectedMaterial) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (selectedIndex < 0 && !filtered.isEmpty()) selectedIndex = 0;
        if (selectedIndex >= 0) resourceList.setSelectedIndex(selectedIndex);
        else {
            resourcePreview.setEntry(null);
            resourceDetails.setText("No resources or items match this search.");
        }
    }

    private void refreshSystems() {
        ResourceSystemCatalog.SystemEntry selected = systemList.getSelectedValue();
        String selectedId = selected == null ? "" : selected.id();
        List<ResourceSystemCatalog.SystemEntry> filtered = ResourceSystemCatalog.filterSystems(searchField.getText());
        systemModel.clear();
        for (ResourceSystemCatalog.SystemEntry system : filtered) systemModel.addElement(system);

        int selectedIndex = -1;
        if (!selectedId.isBlank()) {
            for (int i = 0; i < systemModel.size(); i++) {
                if (systemModel.get(i).id().equals(selectedId)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (selectedIndex < 0 && !filtered.isEmpty()) selectedIndex = 0;
        if (selectedIndex >= 0) systemList.setSelectedIndex(selectedIndex);
        else {
            systemPreview.setSystem(null);
            systemDetails.setText("No systems match this search.");
        }
    }

    private void showSelectedResource() {
        ResourceSystemCatalog.Entry entry = resourceList.getSelectedValue();
        resourcePreview.setEntry(entry);
        resourceDetails.setText(entry == null ? "No resource or item selected." : entry.displayText());
        resourceDetails.setCaretPosition(0);
    }

    private void showSelectedSystem() {
        ResourceSystemCatalog.SystemEntry system = systemList.getSelectedValue();
        systemPreview.setSystem(system);
        systemDetails.setText(system == null ? "No system selected." : system.displayText());
        systemDetails.setCaretPosition(0);
    }

    private void updateCount() {
        if (tabs.getSelectedIndex() == SYSTEMS_TAB) {
            countLabel.setText(systemModel.size() + " of " + ResourceSystemCatalog.systems().size() + " systems");
        } else {
            countLabel.setText(resourceModel.size() + " of " + ResourceSystemCatalog.entries().size() + " resources and items");
        }
    }

    private void installKeyBindings() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-resource-catalog", this::close);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focus-resource-search", () -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK), "resource-tab", () -> tabs.setSelectedIndex(RESOURCES_TAB));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK), "system-tab", () -> tabs.setSelectedIndex(SYSTEMS_TAB));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "previous-catalog-entry", () -> moveSelection(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "next-catalog-entry", () -> moveSelection(1));
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void moveSelection(int delta) {
        if (tabs.getSelectedIndex() == SYSTEMS_TAB) moveSelection(systemList, systemModel.size(), delta);
        else moveSelection(resourceList, resourceModel.size(), delta);
    }

    private void moveSelection(JList<?> list, int size, int delta) {
        if (size <= 0) return;
        int index = Math.max(0, Math.min(size - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    private void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    private void styleList(JList<?> list, int rowHeight) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(FIELD);
        list.setForeground(TEXT);
        list.setFixedCellHeight(rowHeight);
    }

    private void configureDetails(JTextArea details) {
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setBackground(FIELD);
        details.setForeground(TEXT);
        details.setCaretColor(TEXT);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        details.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
    }

    private void styleScroll(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createLineBorder(new Color(54, 92, 122)));
        scroll.getViewport().setBackground(FIELD);
        scroll.getVerticalScrollBar().setUnitIncrement(22);
    }

    @Override protected void paintComponent(Graphics graphics) {
        graphics.setColor(new Color(1, 4, 8, 220));
        graphics.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(graphics);
    }

    private static final class ResourceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof ResourceSystemCatalog.Entry entry) {
                label.setIcon(CatalogVisuals.materialIcon(entry.material(), 42));
                label.setIconTextGap(11);
                label.setText("<html><b>" + escape(entry.material().label) + "</b><br><span style='font-size:9px'>"
                        + escape(entry.summary()) + "</span></html>");
            } else {
                label.setIcon(null);
            }
            styleLabel(label, selected);
            return label;
        }
    }

    private static final class SystemRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof ResourceSystemCatalog.SystemEntry system) {
                label.setIcon(CatalogVisuals.systemIcon(system, 42));
                label.setIconTextGap(11);
                label.setText("<html><b>" + escape(system.name()) + "</b><br><span style='font-size:9px'>"
                        + escape(system.summary()) + "</span></html>");
            } else {
                label.setIcon(null);
            }
            styleLabel(label, selected);
            return label;
        }
    }

    private static void styleLabel(JLabel label, boolean selected) {
        label.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        label.setBackground(selected ? new Color(38, 89, 122) : FIELD);
        label.setForeground(TEXT);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
