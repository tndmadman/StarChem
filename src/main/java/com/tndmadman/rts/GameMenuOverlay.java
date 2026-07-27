package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/** Exact feature/game-menu visual design, with one added tutorial action. */
final class GameMenuOverlay {
    enum Action { NONE, RESUME, SETTINGS, TUTORIAL, RETURN_TO_MAIN_MENU, QUIT }

    private static final int PANEL_W = 460;
    private static final int PANEL_H = 536;
    private static final int BUTTON_W = 300;
    private static final int BUTTON_H = 48;
    private static final int BUTTON_GAP = 18;

    private final List<MenuButton> buttons = new ArrayList<>();

    GameMenuOverlay(boolean tutorialAvailable) {
        buttons.add(new MenuButton(Action.RESUME, "Return to Game", true));
        buttons.add(new MenuButton(Action.SETTINGS, "Settings", true));
        buttons.add(new MenuButton(Action.TUTORIAL,
                tutorialAvailable ? "Start / Resume Tutorial" : "Tutorial - Solo Only",
                tutorialAvailable));
        buttons.add(new MenuButton(Action.RETURN_TO_MAIN_MENU, "Return to Main Menu", true));
        buttons.add(new MenuButton(Action.QUIT, "Quit Game", true));
    }

    void draw(Graphics2D graphics, int width, int height) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(2, 5, 10, 224));
        g2.fillRect(0, 0, width, height);

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        g2.setColor(new Color(10, 18, 30, 240));
        g2.fillRoundRect(panelX, panelY, PANEL_W, PANEL_H, 24, 24);
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(92, 137, 180, 150));
        g2.drawRoundRect(panelX, panelY, PANEL_W, PANEL_H, 24, 24);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 26f));
        g2.setColor(new Color(235, 246, 255));
        drawCentered(g2, "STARCHEM", width, panelY + 52);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
        g2.setColor(new Color(188, 214, 235));
        drawCentered(g2, "Game Menu", width, panelY + 76);

        int y = panelY + 118;
        for (MenuButton button : buttons) {
            button.x = panelX + (PANEL_W - BUTTON_W) / 2;
            button.y = y;
            button.w = BUTTON_W;
            button.h = BUTTON_H;
            y += BUTTON_H + BUTTON_GAP;

            Shape box = new RoundRectangle2D.Float(
                    button.x, button.y, button.w, button.h, 18, 18);
            if (!button.enabled) {
                g2.setColor(new Color(20, 30, 42));
            } else {
                g2.setColor(button.hover ? new Color(56, 93, 128) : new Color(27, 45, 64));
            }
            g2.fill(box);
            g2.setStroke(new BasicStroke(2f));
            if (!button.enabled) {
                g2.setColor(new Color(62, 83, 101));
            } else {
                g2.setColor(button.hover ? new Color(120, 195, 255) : new Color(82, 135, 182));
            }
            g2.draw(box);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 16f));
            g2.setColor(button.enabled ? Color.WHITE : new Color(125, 140, 153));
            FontMetrics metrics = g2.getFontMetrics();
            g2.drawString(button.label,
                    button.x + (button.w - metrics.stringWidth(button.label)) / 2,
                    button.y + 31);
        }
        g2.dispose();
    }

    void updateHover(int mouseX, int mouseY) {
        for (MenuButton button : buttons) {
            button.hover = button.enabled && mouseX >= button.x && mouseX <= button.x + button.w
                    && mouseY >= button.y && mouseY <= button.y + button.h;
        }
    }

    Action click(int mouseX, int mouseY) {
        for (MenuButton button : buttons) {
            if (button.enabled && mouseX >= button.x && mouseX <= button.x + button.w
                    && mouseY >= button.y && mouseY <= button.y + button.h) {
                return button.action;
            }
        }
        return Action.NONE;
    }

    List<String> labelsForTest() {
        List<String> labels = new ArrayList<>();
        for (MenuButton button : buttons) labels.add(button.label);
        return List.copyOf(labels);
    }

    static int panelWidthForTest() { return PANEL_W; }
    static int buttonWidthForTest() { return BUTTON_W; }
    static int buttonHeightForTest() { return BUTTON_H; }

    private static void drawCentered(Graphics2D g2, String text, int width, int y) {
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(text, (width - metrics.stringWidth(text)) / 2, y);
    }

    private static final class MenuButton {
        private final Action action;
        private final String label;
        private final boolean enabled;
        private int x;
        private int y;
        private int w;
        private int h;
        private boolean hover;

        private MenuButton(Action action, String label, boolean enabled) {
            this.action = action;
            this.label = label;
            this.enabled = enabled;
        }
    }
}
