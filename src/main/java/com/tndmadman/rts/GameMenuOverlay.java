package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

final class GameMenuOverlay {

    enum Action {
        NONE,
        RESUME,
        SETTINGS,
        RETURN_TO_MAIN_MENU,
        QUIT
    }

    private static final int PANEL_W = 460;
    private static final int PANEL_H = 470;
    private static final int BUTTON_W = 300;
    private static final int BUTTON_H = 48;
    private static final int BUTTON_GAP = 18;

    private final List<MenuButton> buttons = new ArrayList<>();

    GameMenuOverlay() {
        buttons.add(new MenuButton(Action.RESUME, "Return to Game"));
        buttons.add(new MenuButton(Action.SETTINGS, "Settings"));
        buttons.add(new MenuButton(Action.RETURN_TO_MAIN_MENU, "Return to Main Menu"));
        buttons.add(new MenuButton(Action.QUIT, "Quit Game"));
    }

    void draw(Graphics2D g2, int width, int height) {
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(2, 5, 10, 224));
        g.fillRect(0, 0, width, height);

        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        g.setColor(new Color(10, 18, 30, 240));
        g.fillRoundRect(px, py, PANEL_W, PANEL_H, 24, 24);

        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(92, 137, 180, 150));
        g.drawRoundRect(px, py, PANEL_W, PANEL_H, 24, 24);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 26f));
        g.setColor(new Color(235, 246, 255));
        drawCentered(g, "STARCHEM", width, py + 52);

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
        g.setColor(new Color(188, 214, 235));
        drawCentered(g, "Game Menu", width, py + 76);

        int y = py + 118;
        for (MenuButton b : buttons) {
            b.x = px + (PANEL_W - BUTTON_W) / 2;
            b.y = y;
            b.w = BUTTON_W;
            b.h = BUTTON_H;
            y += BUTTON_H + BUTTON_GAP;

            Shape box = new RoundRectangle2D.Float(b.x, b.y, b.w, b.h, 18, 18);
            g.setColor(b.hover ? new Color(56, 93, 128) : new Color(27, 45, 64));
            g.fill(box);

            g.setStroke(new BasicStroke(2f));
            g.setColor(b.hover ? new Color(120, 195, 255) : new Color(82, 135, 182));
            g.draw(box);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
            g.setColor(Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(b.label, b.x + (b.w - fm.stringWidth(b.label)) / 2, b.y + 31);
        }

        g.dispose();
    }

    void updateHover(int mx, int my) {
        for (MenuButton b : buttons) {
            b.hover = mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h;
        }
    }

    Action click(int mx, int my) {
        for (MenuButton b : buttons) {
            if (mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h) {
                return b.action;
            }
        }
        return Action.NONE;
    }

    private void drawCentered(Graphics2D g, String s, int w, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (w - fm.stringWidth(s)) / 2, y);
    }

    private static final class MenuButton {
        final Action action;
        final String label;
        int x, y, w, h;
        boolean hover;

        MenuButton(Action action, String label) {
            this.action = action;
            this.label = label;
        }
    }
}
