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
    private final Rectangle confirmCancelButton = new Rectangle();
    private final Rectangle confirmActionButton = new Rectangle();

    private Selection hovered = Selection.NONE;
    private Selection pendingConfirmation = Selection.NONE;
    private boolean showingConfirmation = false;
    private int confirmationHovered = 0; // 0 = none, 1 = cancel, 2 = confirm

    public void updateHover(int mx, int my) {
        if (showingConfirmation) {
            confirmationHovered = 0;

            if (confirmCancelButton.contains(mx, my)) {
                confirmationHovered = 1;
            } else if (confirmActionButton.contains(mx, my)) {
                confirmationHovered = 2;
            }

            return;
        }

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
                return;
            }
        }
    }

    public Selection click(int mx, int my) {
        updateHover(mx, my);

        if (showingConfirmation) {
            if (confirmCancelButton.contains(mx, my)) {
                showingConfirmation = false;
                pendingConfirmation = Selection.NONE;
                confirmationHovered = 0;
                return Selection.NONE;
            }

            if (confirmActionButton.contains(mx, my)) {
                Selection result = pendingConfirmation;
                showingConfirmation = false;
                pendingConfirmation = Selection.NONE;
                confirmationHovered = 0;
                return result;
            }

            return Selection.NONE;
        }

        switch (hovered) {
            case MAIN_MENU:
            case QUIT:
                pendingConfirmation = hovered;
                showingConfirmation = true;
                confirmationHovered = 0;
                return Selection.NONE;

            case RETURN:
            case OPTIONS:
                return hovered;

            default:
                return Selection.NONE;
        }
    }

    public void draw(Graphics2D g, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Composite oldComposite = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.58f));
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, width, height);
        g2.setComposite(oldComposite);

        int panelW = 560;
        int panelH = 430;

        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        g2.setColor(new Color(0, 0, 0, 130));
        g2.fillRoundRect(x + 6, y + 7, panelW, panelH, 14, 14);

        g2.setColor(new Color(12, 18, 30, 245));
        g2.fillRoundRect(x, y, panelW, panelH, 14, 14);

        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(95, 165, 235));
        g2.drawRoundRect(x, y, panelW, panelH, 14, 14);

        g2.setColor(new Color(38, 57, 82));
        g2.drawRoundRect(x + 4, y + 4, panelW - 8, panelH - 8, 11, 11);

        g2.setColor(new Color(120, 190, 255));
        int corner = 18;
        g2.drawLine(x + 8, y + 18, x + 8, y + 8 + corner);
        g2.drawLine(x + 8, y + 8, x + 8 + corner, y + 8);

        g2.drawLine(x + panelW - 8, y + 18, x + panelW - 8, y + 8 + corner);
        g2.drawLine(x + panelW - 8 - corner, y + 8, x + panelW - 8, y + 8);

        g2.drawLine(x + 8, y + panelH - 18, x + 8, y + panelH - 8 - corner);
        g2.drawLine(x + 8, y + panelH - 8, x + 8 + corner, y + panelH - 8);

        g2.drawLine(x + panelW - 8, y + panelH - 18, x + panelW - 8, y + panelH - 8 - corner);
        g2.drawLine(x + panelW - 8 - corner, y + panelH - 8, x + panelW - 8, y + panelH - 8);

        g2.setColor(new Color(18, 28, 44));
        g2.fillRoundRect(x + 8, y + 8, panelW - 16, 64, 12, 12);
        g2.fillRect(x + 8, y + 34, panelW - 16, 38);

        g2.setColor(new Color(90, 170, 240));
        g2.drawLine(x + 20, y + 68, x + panelW - 20, y + 68);

        g2.setFont(new Font("SansSerif", Font.BOLD, 30));
        FontMetrics titleFM = g2.getFontMetrics();
        String title = "GAME MENU";

        g2.setColor(new Color(240, 246, 255));
        g2.drawString(title, x + (panelW - titleFM.stringWidth(title)) / 2, y + 44);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g2.setColor(new Color(170, 205, 235));
        String subtitle = "ESC menu";
        FontMetrics subFM = g2.getFontMetrics();
        g2.drawString(subtitle, x + (panelW - subFM.stringWidth(subtitle)) / 2, y + 60);

        g2.setFont(new Font("SansSerif", Font.BOLD, 18));

        int buttonW = 380;
        int buttonH = 52;
        int spacing = 14;

        int bx = x + (panelW - buttonW) / 2;
        int by = y + 96;

        drawButton(g2, 0, bx, by, buttonW, buttonH, "Resume");
        by += buttonH + spacing;
        drawButton(g2, 1, bx, by, buttonW, buttonH, "Options");
        by += buttonH + spacing;
        drawButton(g2, 2, bx, by, buttonW, buttonH, "Return to Main Menu");
        by += buttonH + spacing;
        drawButton(g2, 3, bx, by, buttonW, buttonH, "Quit");

        g2.setColor(new Color(90, 170, 240));
        g2.drawLine(x + 20, y + panelH - 18, x + panelW - 20, y + panelH - 18);

        if (showingConfirmation) {
            drawConfirmationDialog(g2, width, height);
        }

        g2.dispose();
    }

    private void drawConfirmationDialog(Graphics2D g2, int width, int height) {
        int panelW = 420;
        int panelH = 220;

        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, width, height);

        g2.setColor(new Color(14, 20, 32, 248));
        g2.fillRoundRect(x, y, panelW, panelH, 14, 14);

        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(95, 165, 235));
        g2.drawRoundRect(x, y, panelW, panelH, 14, 14);

        g2.setColor(new Color(38, 57, 82));
        g2.drawRoundRect(x + 4, y + 4, panelW - 8, panelH - 8, 11, 11);

        g2.setColor(new Color(18, 28, 44));
        g2.fillRoundRect(x + 8, y + 8, panelW - 16, 52, 12, 12);

        g2.setColor(new Color(90, 170, 240));
        g2.drawLine(x + 16, y + 61, x + panelW - 16, y + 61);

        g2.setFont(new Font("SansSerif", Font.BOLD, 24));
        g2.setColor(new Color(240, 246, 255));

        String confirmTitle = "ARE YOU SURE?";
        FontMetrics titleFM = g2.getFontMetrics();
        g2.drawString(confirmTitle, x + (panelW - titleFM.stringWidth(confirmTitle)) / 2, y + 40);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g2.setColor(new Color(205, 220, 235));

        String line1;
        String line2 = "Unsaved progress will be lost.";

        if (pendingConfirmation == Selection.MAIN_MENU) {
            line1 = "Return to the main menu?";
        } else {
            line1 = "Quit StarChem?";
        }

        FontMetrics msgFM = g2.getFontMetrics();
        g2.drawString(line1, x + (panelW - msgFM.stringWidth(line1)) / 2, y + 98);
        g2.drawString(line2, x + (panelW - msgFM.stringWidth(line2)) / 2, y + 120);

        int buttonW = 140;
        int buttonH = 44;
        int gap = 18;
        int bx1 = x + (panelW - (buttonW * 2 + gap)) / 2;
        int bx2 = bx1 + buttonW + gap;
        int by = y + 150;

        confirmCancelButton.setBounds(bx1, by, buttonW, buttonH);
        confirmActionButton.setBounds(bx2, by, buttonW, buttonH);

        drawConfirmButton(g2, confirmCancelButton, "Cancel", confirmationHovered == 1, false);

        String actionLabel = pendingConfirmation == Selection.MAIN_MENU ? "Main Menu" : "Quit";
        drawConfirmButton(g2, confirmActionButton, actionLabel, confirmationHovered == 2, true);
    }

    private void drawConfirmButton(Graphics2D g2, Rectangle bounds, String text, boolean active, boolean danger) {
        g2.setColor(new Color(0, 0, 0, 75));
        g2.fillRoundRect(bounds.x + 3, bounds.y + 3, bounds.width, bounds.height, 10, 10);

        if (active) {
            g2.setColor(danger ? new Color(155, 85, 95) : new Color(48, 92, 150));
        } else {
            g2.setColor(danger ? new Color(70, 44, 52) : new Color(25, 38, 56));
        }

        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);

        g2.setColor(active
                ? new Color(145, 205, 255)
                : new Color(78, 108, 142));
        g2.drawLine(bounds.x + 2, bounds.y + 2, bounds.x + bounds.width - 3, bounds.y + 2);

        g2.setStroke(new BasicStroke(active ? 2.2f : 1.6f));
        g2.setColor(active
                ? new Color(145, 205, 255)
                : new Color(92, 140, 190));
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);

        g2.setColor(active
                ? new Color(120, 185, 255, 60)
                : new Color(120, 185, 255, 25));
        g2.drawRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 8, 8);

        g2.setFont(new Font("SansSerif", Font.BOLD, 16));
        FontMetrics fm = g2.getFontMetrics();

        g2.setColor(active ? Color.WHITE : new Color(228, 233, 240));
        g2.drawString(
                text,
                bounds.x + (bounds.width - fm.stringWidth(text)) / 2,
                bounds.y + (bounds.height + fm.getAscent()) / 2 - 4
        );
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

        g.setColor(new Color(0, 0, 0, 80));
        g.fillRoundRect(x + 3, y + 3, w, h, 10, 10);

        if (active) {
            g.setColor(new Color(48, 92, 150));
        } else {
            g.setColor(new Color(25, 38, 56));
        }
        g.fillRoundRect(x, y, w, h, 10, 10);

        g.setColor(active ? new Color(145, 205, 255) : new Color(78, 108, 142));
        g.drawLine(x + 2, y + 2, x + w - 3, y + 2);

        g.setStroke(new BasicStroke(active ? 2.2f : 1.6f));
        g.setColor(active ? new Color(145, 205, 255) : new Color(92, 140, 190));
        g.drawRoundRect(x, y, w, h, 10, 10);

        g.setColor(active ? new Color(120, 185, 255, 60) : new Color(120, 185, 255, 25));
        g.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 8, 8);

        FontMetrics fm = g.getFontMetrics();
        g.setColor(active ? Color.WHITE : new Color(228, 233, 240));
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (h + fm.getAscent()) / 2 - 4);
    }
}
