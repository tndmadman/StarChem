package com.tndmadman.rts;

import java.awt.*;

final class HudWindow {
    private static final int HEADER = 28;
    private static final int BODY_PAD = 8;
    private static final int SCROLLBAR_W = 7;
    private static final int WHEEL_STEP = 42;

    int x, y;
    final int w;
    boolean collapsed;
    private boolean dragging;
    private int dx, dy;
    private int scrollOffset;

    HudWindow(int x, int y, int w) { this.x = x; this.y = y; this.w = w; }

    int height(int bodyHeight) { return collapsed ? HEADER : HEADER + bodyHeight; }

    int height(int bodyHeight, int screenH) {
        return collapsed ? HEADER : HEADER + viewportBodyHeight(bodyHeight, screenH);
    }

    int bodyY() { return y + HEADER + BODY_PAD; }

    int contentY(int screenY) { return screenY - bodyY() + scrollOffset; }

    void draw(Graphics2D g2, String title, int bodyHeight, Color border) {
        Rectangle clip = g2.getClipBounds();
        draw(g2, title, bodyHeight, border, clip == null ? Integer.MAX_VALUE : clip.height);
    }

    void draw(Graphics2D g2, String title, int bodyHeight, Color border, int screenH) {
        clampScroll(bodyHeight, screenH);
        int h = height(bodyHeight, screenH);
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRoundRect(x, y, w, h, 14, 14);
        g2.setColor(border);
        g2.drawRoundRect(x, y, w, h, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.drawString((collapsed ? "+ " : "- ") + title, x + 12, y + 19);
        if (!collapsed && maxScroll(bodyHeight, screenH) > 0) {
            String hint = "SCROLL ↕";
            int hintWidth = g2.getFontMetrics().stringWidth(hint);
            g2.setColor(new Color(160, 225, 255));
            g2.drawString(hint, x + w - hintWidth - 38, y + 19);
            drawScrollbar(g2, bodyHeight, screenH);
        }
        g2.setColor(Color.WHITE);
        g2.drawString(collapsed ? "+" : "_", x + w - 24, y + 19);
    }

    Graphics2D bodyGraphics(Graphics2D g2, int bodyHeight, int screenH) {
        clampScroll(bodyHeight, screenH);
        Graphics2D body = (Graphics2D)g2.create();
        int viewportHeight = viewportBodyHeight(bodyHeight, screenH);
        body.clipRect(x + 1, y + HEADER, Math.max(0, w - 2), Math.max(0, viewportHeight));
        body.translate(0, -scrollOffset);
        return body;
    }

    boolean press(int sx, int sy, int bodyHeight) {
        return press(sx, sy, bodyHeight, Integer.MAX_VALUE);
    }

    boolean press(int sx, int sy, int bodyHeight, int screenH) {
        if (!contains(sx, sy, bodyHeight, screenH)) return false;
        if (sy <= y + HEADER) {
            if (sx >= x + w - 34) collapsed = !collapsed;
            else { dragging = true; dx = sx - x; dy = sy - y; }
        }
        return true;
    }

    boolean scroll(int sx, int sy, int wheelRotation, int bodyHeight, int screenH) {
        if (!contains(sx, sy, bodyHeight, screenH)) return false;
        if (collapsed || wheelRotation == 0) return true;
        int maximum = maxScroll(bodyHeight, screenH);
        if (maximum <= 0) return true;
        scrollOffset = clamp(scrollOffset + wheelRotation * WHEEL_STEP, 0, maximum);
        return true;
    }

    void drag(int sx, int sy, int screenW, int screenH) {
        if (!dragging) return;
        x = (int)Calc.clamp(sx - dx, 0, Math.max(0, screenW - w));
        y = (int)Calc.clamp(sy - dy, 0, Math.max(0, screenH - HEADER));
    }

    void release() { dragging = false; }

    boolean contains(int sx, int sy, int bodyHeight) {
        return contains(sx, sy, bodyHeight, Integer.MAX_VALUE);
    }

    boolean contains(int sx, int sy, int bodyHeight, int screenH) {
        return sx >= x && sx <= x + w && sy >= y && sy <= y + height(bodyHeight, screenH);
    }

    int scrollOffsetForTest() { return scrollOffset; }

    private int viewportBodyHeight(int bodyHeight, int screenH) {
        if (screenH == Integer.MAX_VALUE) return Math.max(0, bodyHeight);
        int available = Math.max(0, screenH - y - HEADER);
        return Math.min(Math.max(0, bodyHeight), available);
    }

    private int maxScroll(int bodyHeight, int screenH) {
        return Math.max(0, bodyHeight - viewportBodyHeight(bodyHeight, screenH));
    }

    private void clampScroll(int bodyHeight, int screenH) {
        scrollOffset = clamp(scrollOffset, 0, maxScroll(bodyHeight, screenH));
    }

    private void drawScrollbar(Graphics2D g2, int bodyHeight, int screenH) {
        int viewportHeight = viewportBodyHeight(bodyHeight, screenH);
        int trackHeight = Math.max(20, viewportHeight - 10);
        int trackX = x + w - SCROLLBAR_W - 4;
        int trackY = y + HEADER + 5;
        g2.setColor(new Color(30, 70, 90, 210));
        g2.fillRoundRect(trackX, trackY, SCROLLBAR_W, trackHeight, SCROLLBAR_W, SCROLLBAR_W);

        int thumbHeight = Math.max(28, (int)Math.round(trackHeight * viewportHeight / (double)Math.max(1, bodyHeight)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int travel = Math.max(0, trackHeight - thumbHeight);
        int maximum = Math.max(1, maxScroll(bodyHeight, screenH));
        int thumbY = trackY + (int)Math.round(travel * scrollOffset / (double)maximum);
        g2.setColor(new Color(130, 225, 255, 230));
        g2.fillRoundRect(trackX, thumbY, SCROLLBAR_W, thumbHeight, SCROLLBAR_W, SCROLLBAR_W);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
