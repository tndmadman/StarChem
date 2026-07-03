package com.tndmadman.rts;

import java.awt.*;

final class HudWindow {
    private static final int HEADER = 28;
    int x, y;
    final int w;
    boolean collapsed;
    private boolean dragging;
    private int dx, dy;

    HudWindow(int x, int y, int w) { this.x = x; this.y = y; this.w = w; }

    int height(int bodyHeight) { return collapsed ? HEADER : HEADER + bodyHeight; }
    int bodyY() { return y + HEADER + 8; }

    void draw(Graphics2D g2, String title, int bodyHeight, Color border) {
        int h = height(bodyHeight);
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRoundRect(x, y, w, h, 14, 14);
        g2.setColor(border);
        g2.drawRoundRect(x, y, w, h, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString((collapsed ? "+ " : "- ") + title, x + 12, y + 19);
    }

    boolean press(int sx, int sy, int bodyHeight) {
        if (!contains(sx, sy, bodyHeight)) return false;
        if (sy <= y + HEADER) {
            if (sx >= x + w - 34) collapsed = !collapsed;
            else { dragging = true; dx = sx - x; dy = sy - y; }
        }
        return true;
    }

    void drag(int sx, int sy, int screenW, int screenH) {
        if (!dragging) return;
        x = Calc.clamp(sx - dx, 0, Math.max(0, screenW - w));
        y = Calc.clamp(sy - dy, 0, Math.max(0, screenH - HEADER));
    }

    void release() { dragging = false; }
    boolean contains(int sx, int sy, int bodyHeight) { return sx >= x && sx <= x + w && sy >= y && sy <= y + height(bodyHeight); }
}
