package com.tndmadman.rts;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
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
import java.awt.event.KeyEvent;
import java.util.List;

final class CodexOverlay extends JPanel {
    private static final Color PANEL = new Color(10, 18, 30);
    private static final Color FIELD = new Color(7, 14, 24);
    private static final Color BORDER = new Color(80, 145, 188);
    private static final Color TEXT = new Color(229, 243, 252);
    private static final Color MUTED = new Color(166, 197, 220);

    private final JComponent returnFocus;
    private final JTextField searchField = new JTextField();
    private final JComboBox<CodexCategory> categoryBox = new JComboBox<>(CodexCategory.values());
    private final DefaultListModel<CodexEntry> listModel = new DefaultListModel<>();
    private final JList<CodexEntry> entryList = new JList<>(listModel);
    private final JTextArea details = new JTextArea();
    private final JLabel countLabel = new JLabel();

    CodexOverlay(JComponent returnFocus) {
        super(new GridBagLayout());
        this.returnFocus = returnFocus;
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
        constraints.insets = new Insets(34, 42, 34, 42);
        add(card, constraints);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        categoryBox.addActionListener(e -> refresh());
        entryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        installKeyBindings();
        refresh();
    }

    void toggle() {
        if (isVisible()) close(); else open();
    }

    void open() {
        if (isVisible()) return;
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
        if (returnFocus != null) {
            returnFocus.requestFocusInWindow();
            returnFocus.repaint();
        }
    }

    private JPanel buildCard() {
        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 2),
                BorderFactory.createEmptyBorder(20, 22, 18, 22)));
        card.setMinimumSize(new Dimension(720, 500));
        card.setPreferredSize(new Dimension(1040, 680));

        JPanel header = new JPanel(new BorderLayout(14, 10));
        header.setOpaque(false);
        JLabel title = new JLabel("STARCHEM CODEX");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("Loaded ships, stations, resources, research, crafting, NPC factions, and controls");
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

        JPanel filters = new JPanel(new BorderLayout(10, 0));
        filters.setOpaque(false);
        styleField(searchField);
        searchField.setToolTipText("Search names, IDs, stats, costs, unlocks, or descriptions");
        categoryBox.setForeground(TEXT);
        categoryBox.setBackground(FIELD);
        categoryBox.setBorder(BorderFactory.createLineBorder(BORDER));
        categoryBox.setPreferredSize(new Dimension(180, 34));
        filters.add(searchField, BorderLayout.CENTER);
        filters.add(categoryBox, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(filters, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entryList.setBackground(FIELD);
        entryList.setForeground(TEXT);
        entryList.setFixedCellHeight(48);
        entryList.setCellRenderer(new EntryRenderer());
        JScrollPane listScroll = new JScrollPane(entryList);
        styleScroll(listScroll);
        listScroll.setPreferredSize(new Dimension(330, 500));

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setBackground(FIELD);
        details.setForeground(TEXT);
        details.setCaretColor(TEXT);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        details.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JScrollPane detailScroll = new JScrollPane(details);
        styleScroll(detailScroll);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setOpaque(false);
        split.setBorder(null);
        split.setDividerSize(8);
        split.setResizeWeight(0.34);
        card.add(split, BorderLayout.CENTER);

        countLabel.setForeground(MUTED);
        countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN, 11f));
        JLabel help = new JLabel("Up/Down: select   Type to search   Escape: close", SwingConstants.RIGHT);
        help.setForeground(MUTED);
        help.setFont(help.getFont().deriveFont(Font.PLAIN, 11f));
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(countLabel, BorderLayout.WEST);
        footer.add(help, BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private void refresh() {
        CodexCategory category = (CodexCategory) categoryBox.getSelectedItem();
        CodexEntry selected = entryList.getSelectedValue();
        List<CodexEntry> filtered = CodexCatalog.filter(category, searchField.getText());
        listModel.clear();
        for (CodexEntry entry : filtered) listModel.addElement(entry);

        int selectedIndex = -1;
        if (selected != null) {
            for (int i = 0; i < listModel.size(); i++) {
                CodexEntry candidate = listModel.get(i);
                if (candidate.category() == selected.category() && candidate.id().equals(selected.id())) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (selectedIndex < 0 && !filtered.isEmpty()) selectedIndex = 0;
        if (selectedIndex >= 0) entryList.setSelectedIndex(selectedIndex);
        else details.setText("No codex entries match this filter.");
        countLabel.setText(filtered.size() + " of " + CodexCatalog.entries().size() + " entries");
    }

    private void showSelected() {
        CodexEntry entry = entryList.getSelectedValue();
        details.setText(entry == null ? "No codex entry selected." : entry.displayText());
        details.setCaretPosition(0);
    }

    private void installKeyBindings() {
        bind(KeyEvent.VK_ESCAPE, "close-codex", this::close);
        bind(KeyEvent.VK_UP, "previous-entry", () -> moveSelection(-1));
        bind(KeyEvent.VK_DOWN, "next-entry", () -> moveSelection(1));
    }

    private void bind(int keyCode, String name, Runnable action) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void moveSelection(int delta) {
        if (listModel.isEmpty()) return;
        int index = Math.max(0, Math.min(listModel.size() - 1, entryList.getSelectedIndex() + delta));
        entryList.setSelectedIndex(index);
        entryList.ensureIndexIsVisible(index);
    }

    private void styleField(JTextField field) {
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(FIELD);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
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

    private static final class EntryRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof CodexEntry entry) {
                label.setText("<html><b>" + escape(entry.title()) + "</b><br><span style='font-size:9px'>"
                        + escape(entry.category().label + " | " + entry.summary()) + "</span></html>");
            }
            label.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
            label.setBackground(selected ? new Color(38, 89, 122) : FIELD);
            label.setForeground(TEXT);
            return label;
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
