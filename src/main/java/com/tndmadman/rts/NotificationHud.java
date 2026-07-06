package com.tndmadman.rts;

import java.awt.*;

final class NotificationHud {
    void update(World world, double dt) { AlertCenter.update(world, dt); }

    void draw(Graphics2D g2, World world, int height) {
        int x = 18;
        int y = height - 28;
        java.util.List<GameNotification> notes = AlertCenter.list(world);
        for (int i = notes.size() - 1; i >= 0; i--) {
            GameNotification note = notes.get(i);
            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, note.alpha()));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
            int w = Math.min(520, g2.getFontMetrics().stringWidth(note.text) + 24);
            g2.setColor(new Color(0, 0, 0, 185));
            g2.fillRoundRect(x, y - 24, w, 30, 10, 10);
            g2.setColor(new Color(120, 220, 255, 150));
            g2.drawRoundRect(x, y - 24, w, 30, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(fit(g2, note.text, w - 18), x + 12, y - 5);
            g2.setComposite(old);
            y -= 36;
        }
    }

    private String fit(Graphics2D g2, String text, int maxW) {
        if (g2.getFontMetrics().stringWidth(text) <= maxW) return text;
        String s = text;
        while (s.length() > 3 && g2.getFontMetrics().stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
