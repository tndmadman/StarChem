package com.tndmadman.rts;

import java.awt.*;

final class EliminationOverlay {
    private Rectangle respawnButton = new Rectangle();
    private Rectangle disconnectButton = new Rectangle();

    void draw(Graphics2D g2, int screenW, int screenH) {
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRect(0, 0, screenW, screenH);
        int w = 430;
        int h = 190;
        int x = (screenW - w) / 2;
        int y = (screenH - h) / 2;
        g2.setColor(new Color(8, 18, 30, 235));
        g2.fillRoundRect(x, y, w, h, 18, 18);
        g2.setColor(new Color(255, 95, 85, 190));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x, y, w, h, 18, 18);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 24f));
        g2.setColor(Color.WHITE);
        drawCentered(g2, "FLEET DESTROYED", x, y + 42, w);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
        g2.setColor(new Color(220, 238, 250));
        drawCentered(g2, "You are off the leaderboard until you respawn.", x, y + 72, w);
        respawnButton = new Rectangle(x + 42, y + 112, 160, 40);
        disconnectButton = new Rectangle(x + w - 202, y + 112, 160, 40);
        button(g2, respawnButton, "RESPAWN");
        button(g2, disconnectButton, "DISCONNECT");
    }

    boolean respawnClicked(int sx, int sy) { return respawnButton.contains(sx, sy); }
    boolean disconnectClicked(int sx, int sy) { return disconnectButton.contains(sx, sy); }

    private void button(Graphics2D g2, Rectangle r, String text) {
        g2.setColor(new Color(18, 54, 82, 235));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2.setColor(new Color(120, 220, 255, 170));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        g2.setColor(Color.WHITE);
        drawCentered(g2, text, r.x, r.y + 25, r.width);
    }

    private void drawCentered(Graphics2D g2, String text, int x, int y, int w) {
        g2.drawString(text, x + w / 2 - g2.getFontMetrics().stringWidth(text) / 2, y);
    }
}
