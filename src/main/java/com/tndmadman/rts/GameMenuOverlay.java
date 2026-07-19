package com.tndmadman.rts;

import java.awt.*;

public class GameMenuOverlay {

    public enum Selection {
        NONE,
        RETURN,
        OPTIONS,
        MAIN_MENU,
        QUIT
    }

    private final Rectangle[] buttons = new Rectangle[4];
    private Selection hovered = Selection.NONE;

    public void updateHover(int mx, int my) {

        hovered = Selection.NONE;

        for (int i = 0; i < buttons.length; i++) {

            if (buttons[i] != null && buttons[i].contains(mx, my)) {

                hovered = switch (i) {
                    case 0 -> Selection.RETURN;
                    case 1 -> Selection.OPTIONS;
                    case 2 -> Selection.MAIN_MENU;
                    case 3 -> Selection.QUIT;
                    default -> Selection.NONE;
                };
            }
        }
    }

    public Selection click(int mx, int my) {
        updateHover(mx, my);
        return hovered;
    }

public void draw(Graphics2D g, int width, int height) {

    // Darken the game behind the menu
    Composite oldComposite = g.getComposite();
    g.setComposite(AlphaComposite.getInstance(
            AlphaComposite.SRC_OVER, 0.55f));
    g.setColor(Color.BLACK);
    g.fillRect(0, 0, width, height);
    g.setComposite(oldComposite);

    int panelW = 470;
    int panelH = 405;

    int x = (width - panelW) / 2;
    int y = (height - panelH) / 2;

    // Main panel
g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON);

g.setColor(new Color(10, 18, 30, 240));
g.fillRoundRect(x, y, panelW, panelH, 24, 24);

g.setStroke(new BasicStroke(2f));
g.setColor(new Color(92, 137, 180, 150));
g.drawRoundRect(x, y, panelW, panelH, 24, 24);

    g.setFont(new Font("SansSerif", Font.BOLD, 22));
g.setColor(new Color(230, 244, 255));
g.drawString("GAME MENU", x + 28, y + 38);

g.setFont(new Font("SansSerif", Font.PLAIN, 12));
g.setColor(new Color(185, 211, 235));
g.drawString("Press Esc to Close the Menu.", x + 28, y + 58);

    // Button font
    g.setFont(new Font("SansSerif", Font.BOLD, 22));
        int buttonW = 330;
    int buttonH = 52;
    int spacing = 18;

    int bx = x + (panelW - buttonW) / 2;
    int by = y + 100;

    drawButton(
            g,
            0,
            bx,
            by,
            buttonW,
            buttonH,
            "Return");

    by += buttonH + spacing;

    drawButton(
            g,
            1,
            bx,
            by,
            buttonW,
            buttonH,
            "Options");

    by += buttonH + spacing;

    drawButton(
            g,
            2,
            bx,
            by,
            buttonW,
            buttonH,
            "Return to Main Menu");

    by += buttonH + spacing;

    drawButton(
            g,
            3,
            bx,
            by,
            buttonW,
            buttonH,
            "Quit");

}


private void drawButton(Graphics2D g,
                        int index,
                        int x,
                        int y,
                        int w,
                        int h,
                        String text) {

    buttons[index] = new Rectangle(x, y, w, h);

    boolean active = hovered.ordinal() == index + 1;

    
            
    // Background
    g.setColor(active
        ? new Color(48, 112, 96, 245)
        : new Color(34, 58, 82, 235));

    g.fillRoundRect(
            x,
            y,
            w,
            h,
            16,
            16);


    // Border
    g.setStroke(new BasicStroke(active ? 2.5f : 2f));

g.setColor(active
        ? new Color(110, 190, 255)
        : new Color(92, 137, 180, 150));

    g.drawRoundRect(
            x,
            y,
            w,
            h,
            16,
            16);

    // Text
    FontMetrics fm = g.getFontMetrics();

    g.setColor(active
            ? Color.WHITE
            : new Color(225, 230, 240));

    g.drawString(
            text,
            x + (w - fm.stringWidth(text)) / 2,
            y + (h + fm.getAscent()) / 2 - 4);
}

}