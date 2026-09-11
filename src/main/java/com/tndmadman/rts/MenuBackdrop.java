package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class MenuBackdrop extends JPanel {
    MenuBackdrop() { setBackground(UiPalette.BACKDROP); }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(UiPalette.BACKDROP);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(UiPalette.BACKDROP_GRID);
        for (int x = 0; x < getWidth(); x += 80) g2.drawLine(x, 0, x, getHeight());
        for (int y = 0; y < getHeight(); y += 80) g2.drawLine(0, y, getWidth(), y);
        g2.setColor(UiPalette.BACKDROP_GLOW);
        g2.fillOval(getWidth() - 420, -180, 560, 560);
        g2.dispose();
    }
}
