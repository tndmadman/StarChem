package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class MenuCardPanel extends JPanel {
    MenuCardPanel(LayoutManager layout) { super(layout); setOpaque(false); }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(UiPalette.PANEL_SOFT);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
        g2.setColor(UiPalette.BORDER);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
        g2.dispose();
        super.paintComponent(g);
    }
}

final class MenuButton extends JButton {
    MenuButton(String text) {
        super(text);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setForeground(UiPalette.TEXT);
        setFont(getFont().deriveFont(Font.BOLD, 15f));
        setPreferredSize(new Dimension(120, 44));
    }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ButtonModel m = getModel();
        Color top = m.isPressed() ? UiPalette.CONTROL_ACTIVE
                : m.isRollover() ? UiPalette.CONTROL_HOVER : UiPalette.CONTROL;
        Color bottom = m.isPressed() ? new Color(55, 48, 38)
                : m.isRollover() ? new Color(46, 51, 50) : new Color(31, 35, 36);
        g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g2.setColor(m.isPressed() || m.isRollover() ? UiPalette.BORDER_STRONG : UiPalette.BORDER);
        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
        g2.dispose();
        super.paintComponent(g);
    }
}
