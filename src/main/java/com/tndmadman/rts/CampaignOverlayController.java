package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * Modal campaign interactions rendered inside StarChem's own layered game frame.
 * This panel deliberately owns the entire hit-test area, so clicks never reach
 * the simulation or lobby beneath the active dialog.
 */
final class CampaignOverlayController extends JPanel {
    record Choice(String label, Runnable action) { }

    private final JPanel card = new JPanel(new BorderLayout(0, 16));
    private final Runnable restoreFocus;
    private Runnable cancelAction;
    private boolean busy;

    CampaignOverlayController(Runnable restoreFocus) {
        super(new GridBagLayout());
        this.restoreFocus = restoreFocus;
        setOpaque(false);
        setFocusable(true);
        setVisible(false);
        addMouseListener(new java.awt.event.MouseAdapter() { });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() { });

        card.setBackground(new Color(15, 30, 45));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(82, 199, 240), 2),
                BorderFactory.createEmptyBorder(22, 24, 22, 24)));

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke("ESCAPE"), "campaign-cancel");
        getActionMap().put("campaign-cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { cancel(); }
        });
    }

    static Choice choice(String label, Runnable action) {
        return new Choice(label, action);
    }

    boolean isOpen() { return isVisible(); }

    void showChoices(String title, String description, Choice... choices) {
        showDialog(title, description, null, choices);
    }

    /** A committed I/O operation cannot be dismissed mid-write or mid-restore. */
    void showBusy(String title, String description) {
        showDialog(title, description, null);
        busy = true;
    }

    void showDialog(String title, String description, JComponent content, Choice... choices) {
        busy = false;
        card.removeAll();
        JLabel heading = new JLabel(title);
        heading.setForeground(new Color(135, 223, 255));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        card.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        if (description != null && !description.isBlank()) {
            JTextArea explanation = new JTextArea(description);
            explanation.setLineWrap(true);
            explanation.setWrapStyleWord(true);
            explanation.setEditable(false);
            explanation.setFocusable(false);
            explanation.setOpaque(false);
            explanation.setForeground(new Color(222, 235, 248));
            explanation.setFont(explanation.getFont().deriveFont(14f));
            explanation.setColumns(42);
            explanation.setRows(Math.max(2, Math.min(4, (description.length() / 58) + 1)));
            body.add(explanation, BorderLayout.NORTH);
        }
        if (content != null) {
            body.add(content, BorderLayout.CENTER);
            content.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        card.add(body, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        cancelAction = null;
        if (choices != null) {
            for (Choice choice : choices) {
                if (choice == null) continue;
                if ("Cancel".equalsIgnoreCase(choice.label())) cancelAction = choice.action();
                JButton button = new JButton(choice.label());
                button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
                button.setForeground(new Color(230, 244, 250));
                button.setBackground(new Color(35, 68, 94));
                button.setFocusPainted(false);
                button.addActionListener(event -> {
                    hideDialog();
                    if (choice.action() != null) choice.action().run();
                });
                actions.add(button);
                if ("Cancel".equalsIgnoreCase(choice.label())) {
                    getRootPaneDefaultAction(button);
                }
            }
        }
        card.add(actions, BorderLayout.SOUTH);

        removeAll();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(16, 16, 16, 16);
        gbc.fill = GridBagConstraints.NONE;
        add(card, gbc);
        setVisible(true);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(() -> {
            if (!isVisible()) return;
            if (content instanceof JTextField field) field.requestFocusInWindow();
            else requestFocusInWindow();
        });
    }

    private static void getRootPaneDefaultAction(JButton button) {
        // Keep cancellation an explicit, keyboard-accessible button.
        button.setMnemonic('C');
    }

    void cancel() {
        if (!isOpen() || busy) return;
        Runnable callback = cancelAction;
        hideDialog();
        if (callback != null) callback.run();
    }

    void hideDialog() {
        if (!isVisible()) return;
        setVisible(false);
        cancelAction = null;
        busy = false;
        removeAll();
        if (restoreFocus != null) SwingUtilities.invokeLater(() -> {
            // A button may immediately open another campaign dialog.
            // Never steal focus from its text editor or snapshot selector.
            if (!isVisible()) restoreFocus.run();
        });
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(new Color(1, 8, 15, 214));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
        super.paintComponent(graphics);
    }
}
