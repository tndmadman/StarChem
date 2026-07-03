package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class MenuCardPanel extends JPanel {
    MenuCardPanel(LayoutManager layout) { super(layout); setOpaque(false); }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(6, 12, 22, 218));
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
        g2.setColor(new Color(80, 170, 225, 140));
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
        setForeground(Color.WHITE);
        setFont(getFont().deriveFont(Font.BOLD, 15f));
        setPreferredSize(new Dimension(120, 44));
    }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ButtonModel m = getModel();
        Color top = m.isPressed() ? new Color(25,90,130) : m.isRollover() ? new Color(34,128,180) : new Color(18,64,100);
        Color bottom = m.isPressed() ? new Color(16,52,82) : m.isRollover() ? new Color(18,86,132) : new Color(9,34,62);
        g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g2.setColor(new Color(126,220,255));
        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
        g2.dispose();
        super.paintComponent(g);
    }
}
