package com.tndmadman.rts;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public class GameMenuOverlay {

    public enum Selection {
        NONE,
        RETURN,
        OPTIONS,
        MAIN_MENU,
        QUIT
    }

    private enum Screen {
        MAIN,
        OPTIONS,
        DISPLAY,
        CONTROLS
    }

    private static final int MAIN_PANEL_W = 560;
    private static final int MAIN_PANEL_H = 430;

    private static final int OPTIONS_PANEL_W = 420;
    private static final int OPTIONS_PANEL_H = 300;

    private static final int DISPLAY_PANEL_W = 520;
    private static final int DISPLAY_PANEL_H = 360;

    private static final int CONTROLS_PANEL_W = 760;
    private static final int CONTROLS_PANEL_H = 640;

    private static final int CONFIRM_PANEL_W = 420;
    private static final int CONFIRM_PANEL_H = 220;

    private static final int BUTTON_W = 380;
    private static final int BUTTON_H = 52;
    private static final int BUTTON_GAP = 14;

    private static final int SMALL_BUTTON_W = 320;
    private static final int SMALL_BUTTON_H = 48;

    private static final int TOGGLE_BUTTON_W = 390;
    private static final int TOGGLE_BUTTON_H = 44;
    private static final int TOGGLE_BUTTON_GAP = 12;

    private static final int CONTROL_ROW_H = 28;
    private static final int CONTROL_ROW_GAP = 4;
    private static final int CONTROL_GROUP_GAP = 8;
    private static final int CONTROL_SCROLL_STEP = 32;

    private static final String[] UI_SCALE_LABELS = {"100%", "125%", "150%"};

    private final Rectangle[] mainButtons = new Rectangle[4];
    private final Rectangle[] optionsButtons = new Rectangle[3];
    private final Rectangle[] displayButtons = new Rectangle[4];
    private final Rectangle controlsBackButton = new Rectangle();
    private final Rectangle confirmCancelButton = new Rectangle();
    private final Rectangle confirmActionButton = new Rectangle();

    private final List<KeyBinding> bindings = new ArrayList<>();

    private Screen screen = Screen.MAIN;

    private boolean showingConfirmation = false;
    private Selection pendingConfirmation = Selection.NONE;

    private int hoveredMain = -1;
    private int hoveredOptions = -1;
    private int hoveredDisplay = -1;
    private int hoveredBinding = -1;
    private boolean hoverControlsBack = false;
    private int confirmationHovered = 0;

    private int editingBindingIndex = -1;

    private boolean fullscreenEnabled = false;
    private boolean showFpsEnabled = false;
    private int uiScaleIndex = 0;

    private int controlsScrollOffset = 0;

    public GameMenuOverlay() {
        seedBindings();
    }

    public boolean isFullscreenEnabled() {
        return fullscreenEnabled;
    }

    public boolean isShowFpsEnabled() {
        return showFpsEnabled;
    }

    public String getUiScaleLabel() {
        return UI_SCALE_LABELS[uiScaleIndex];
    }

    public boolean isEditingBinding() {
        return editingBindingIndex >= 0;
    }

    public boolean matchesAction(String actionId, KeyEvent e) {
        for (KeyBinding binding : bindings) {
            if (binding.actionId.equals(actionId) && binding.matches(e)) {
                return true;
            }
        }
        return false;
    }

    public boolean handleKeyPressed(KeyEvent e) {
    if (editingBindingIndex < 0) {
        return false;
    }

    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
        editingBindingIndex = -1;
        return true;
    }

    if (isModifierOnly(e.getKeyCode())) {
        return true;
    }

    KeyBinding edited = bindings.get(editingBindingIndex);

    int oldKey = edited.keyCode;
    boolean oldCtrl = edited.ctrlRequired;

    int newKey = e.getKeyCode();
    boolean newCtrl = e.isControlDown();

    // Swap with any existing editable binding using this key
    for (KeyBinding binding : bindings) {
        if (binding == edited || !binding.editable) {
            continue;
        }

        if (binding.keyCode == newKey && binding.ctrlRequired == newCtrl) {
            binding.keyCode = oldKey;
            binding.ctrlRequired = oldCtrl;
            break;
        }
    }

    edited.keyCode = newKey;
    edited.ctrlRequired = newCtrl;

    editingBindingIndex = -1;
    return true;
}

    public boolean handleEscapePressed() {
        if (showingConfirmation) {
            showingConfirmation = false;
            pendingConfirmation = Selection.NONE;
            confirmationHovered = 0;
            return true;
        }

        if (editingBindingIndex >= 0) {
            editingBindingIndex = -1;
            return true;
        }

        switch (screen) {
            case DISPLAY, CONTROLS -> {
                screen = Screen.OPTIONS;
                editingBindingIndex = -1;
                return true;
            }
            case OPTIONS -> {
                screen = Screen.MAIN;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean handleMouseWheel(int wheelRotation) {
        if (showingConfirmation || screen != Screen.CONTROLS) {
            return false;
        }

        int maxScroll = Math.max(0, measureControlsContentHeight() - getControlsViewHeight());
        controlsScrollOffset = clamp(controlsScrollOffset + (wheelRotation * CONTROL_SCROLL_STEP), 0, maxScroll);
        return true;
    }

    public void updateHover(int mx, int my) {
        hoveredMain = -1;
        hoveredOptions = -1;
        hoveredDisplay = -1;
        hoveredBinding = -1;
        hoverControlsBack = false;
        confirmationHovered = 0;

        if (showingConfirmation) {
            if (confirmCancelButton.contains(mx, my)) {
                confirmationHovered = 1;
            } else if (confirmActionButton.contains(mx, my)) {
                confirmationHovered = 2;
            }
            return;
        }

        switch (screen) {
            case MAIN -> {
                for (int i = 0; i < mainButtons.length; i++) {
                    if (mainButtons[i] != null && mainButtons[i].contains(mx, my)) {
                        hoveredMain = i;
                        break;
                    }
                }
            }
            case OPTIONS -> {
                for (int i = 0; i < optionsButtons.length; i++) {
                    if (optionsButtons[i] != null && optionsButtons[i].contains(mx, my)) {
                        hoveredOptions = i;
                        break;
                    }
                }
            }
            case DISPLAY -> {
                for (int i = 0; i < displayButtons.length; i++) {
                    if (displayButtons[i] != null && displayButtons[i].contains(mx, my)) {
                        hoveredDisplay = i;
                        break;
                    }
                }
            }
            case CONTROLS -> {
                for (int i = 0; i < bindings.size(); i++) {
                    Rectangle r = bindings.get(i).bounds;
                    if (r.contains(mx, my)) {
                        hoveredBinding = i;
                        break;
                    }
                }
                hoverControlsBack = controlsBackButton.contains(mx, my);
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

        switch (screen) {
            case MAIN -> {
                switch (hoveredMain) {
                    case 0 -> {
                        return Selection.RETURN;
                    }
                    case 1 -> {
                        screen = Screen.OPTIONS;
                        return Selection.NONE;
                    }
                    case 2 -> {
                        pendingConfirmation = Selection.MAIN_MENU;
                        showingConfirmation = true;
                        return Selection.NONE;
                    }
                    case 3 -> {
                        pendingConfirmation = Selection.QUIT;
                        showingConfirmation = true;
                        return Selection.NONE;
                    }
                    default -> {
                        return Selection.NONE;
                    }
                }
            }
            case OPTIONS -> {
                switch (hoveredOptions) {
                    case 0 -> screen = Screen.DISPLAY;
                    case 1 -> screen = Screen.CONTROLS;
                    case 2 -> screen = Screen.MAIN;
                }
                return Selection.NONE;
            }
            case DISPLAY -> {
                switch (hoveredDisplay) {
                    case 0 -> fullscreenEnabled = !fullscreenEnabled;
                    case 1 -> showFpsEnabled = !showFpsEnabled;
                    case 2 -> uiScaleIndex = (uiScaleIndex + 1) % UI_SCALE_LABELS.length;
                    case 3 -> screen = Screen.OPTIONS;
                }
                return Selection.NONE;
            }
            case CONTROLS -> {
                if (controlsBackButton.contains(mx, my)) {
                    screen = Screen.OPTIONS;
                    editingBindingIndex = -1;
                    return Selection.NONE;
                }

                if (hoveredBinding >= 0 && hoveredBinding < bindings.size()) {
                    KeyBinding binding = bindings.get(hoveredBinding);
                    if (binding.editable) {
                        editingBindingIndex = hoveredBinding;
                    }
                }

                return Selection.NONE;
            }
        }

        return Selection.NONE;
    }

    public void draw(Graphics2D g, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Composite oldComposite = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.58f));
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, width, height);
        g2.setComposite(oldComposite);

        switch (screen) {
            case MAIN -> drawMainScreen(g2, width, height);
            case OPTIONS -> drawOptionsScreen(g2, width, height);
            case DISPLAY -> drawDisplayScreen(g2, width, height);
            case CONTROLS -> drawControlsScreen(g2, width, height);
        }

        if (showingConfirmation) {
            drawConfirmationDialog(g2, width, height);
        }

        g2.dispose();
    }

    private void drawMainScreen(Graphics2D g2, int width, int height) {
        int panelW = MAIN_PANEL_W;
        int panelH = MAIN_PANEL_H;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        drawFrame(g2, x, y, panelW, panelH, "GAME MENU", "ESC menu");

        int bx = x + (panelW - BUTTON_W) / 2;
        int by = y + 96;

        drawButton(g2, mainButtons, 0, bx, by, BUTTON_W, BUTTON_H, "Resume", hoveredMain == 0);
        by += BUTTON_H + BUTTON_GAP;

        drawButton(g2, mainButtons, 1, bx, by, BUTTON_W, BUTTON_H, "Options", hoveredMain == 1);
        by += BUTTON_H + BUTTON_GAP;

        drawButton(g2, mainButtons, 2, bx, by, BUTTON_W, BUTTON_H, "Return to Main Menu", hoveredMain == 2);
        by += BUTTON_H + BUTTON_GAP;

        drawButton(g2, mainButtons, 3, bx, by, BUTTON_W, BUTTON_H, "Quit", hoveredMain == 3);
    }

    private void drawOptionsScreen(Graphics2D g2, int width, int height) {
        int panelW = OPTIONS_PANEL_W;
        int panelH = OPTIONS_PANEL_H;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        drawFrame(g2, x, y, panelW, panelH, "OPTIONS", "Display settings and controls");

        int bx = x + (panelW - SMALL_BUTTON_W) / 2;
        int by = y + 96;

        drawButton(g2, optionsButtons, 0, bx, by, SMALL_BUTTON_W, SMALL_BUTTON_H, "Display Settings", hoveredOptions == 0);
        by += SMALL_BUTTON_H + BUTTON_GAP;

        drawButton(g2, optionsButtons, 1, bx, by, SMALL_BUTTON_W, SMALL_BUTTON_H, "Controls", hoveredOptions == 1);
        by += SMALL_BUTTON_H + BUTTON_GAP;

        drawButton(g2, optionsButtons, 2, bx, by, SMALL_BUTTON_W, SMALL_BUTTON_H, "Back", hoveredOptions == 2);
    }

    private void drawDisplayScreen(Graphics2D g2, int width, int height) {
        int panelW = DISPLAY_PANEL_W;
        int panelH = DISPLAY_PANEL_H;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        drawFrame(g2, x, y, panelW, panelH, "DISPLAY SETTINGS", "Visual preferences");

        int bx = x + (panelW - TOGGLE_BUTTON_W) / 2;
        int by = y + 98;

        drawToggleRow(g2, displayButtons, 0, bx, by, TOGGLE_BUTTON_W, TOGGLE_BUTTON_H, "Fullscreen", fullscreenEnabled ? "ON" : "OFF", hoveredDisplay == 0);
        by += TOGGLE_BUTTON_H + TOGGLE_BUTTON_GAP;

        drawToggleRow(g2, displayButtons, 1, bx, by, TOGGLE_BUTTON_W, TOGGLE_BUTTON_H, "Show FPS", showFpsEnabled ? "ON" : "OFF", hoveredDisplay == 1);
        by += TOGGLE_BUTTON_H + TOGGLE_BUTTON_GAP;

        drawToggleRow(g2, displayButtons, 2, bx, by, TOGGLE_BUTTON_W, TOGGLE_BUTTON_H, "UI Scale", UI_SCALE_LABELS[uiScaleIndex], hoveredDisplay == 2);
        by += TOGGLE_BUTTON_H + TOGGLE_BUTTON_GAP;

        drawButton(g2, displayButtons, 3, bx, by, TOGGLE_BUTTON_W, TOGGLE_BUTTON_H, "Back", hoveredDisplay == 3);
    }

    private void drawControlsScreen(Graphics2D g2, int width, int height) {
        int panelW = CONTROLS_PANEL_W;
        int panelH = CONTROLS_PANEL_H;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        drawFrame(
                g2,
                x,
                y,
                panelW,
                panelH,
                "CONTROLS",
                editingBindingIndex >= 0 ? "Press a new key to rebind" : "Click a key to edit it"
        );

        int listLeft = x + 24;
        int listWidth = panelW - 48;
        int listTop = y + 92;
        int listHeight = panelH - 182;

        int contentHeight = measureControlsContentHeight();
        int maxScroll = Math.max(0, contentHeight - listHeight);
        controlsScrollOffset = clamp(controlsScrollOffset, 0, maxScroll);

        Shape oldClip = g2.getClip();
        g2.setClip(x + 20, listTop, panelW - 40, listHeight);

        layoutControlRows(g2, listLeft, listTop, listWidth, true);

        g2.setClip(oldClip);

        if (maxScroll > 0) {
            int trackX = x + panelW - 15;
            int trackY = listTop;
            int trackH = listHeight;

            g2.setColor(new Color(0, 0, 0, 80));
            g2.fillRoundRect(trackX, trackY, 6, trackH, 6, 6);

            int thumbH = Math.max(36, (int) Math.round((double) trackH * trackH / (contentHeight + trackH)));
            int thumbRange = Math.max(0, trackH - thumbH);
            int thumbY = trackY + (maxScroll == 0 ? 0 : (int) Math.round((double) controlsScrollOffset / maxScroll * thumbRange));

            g2.setColor(new Color(90, 170, 240, 180));
            g2.fillRoundRect(trackX - 1, thumbY, 8, thumbH, 8, 8);
        }

        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g2.setColor(new Color(170, 205, 235));
        g2.drawString(
                "Mouse: Left click select / interact | Left drag box select | Right click move / order | Wheel zoom",
                listLeft,
                y + panelH - 104
        );

        int backW = 140;
        int backH = 42;
        int backX = x + (panelW - backW) / 2;
        int backY = y + panelH - 62;

        controlsBackButton.setBounds(backX, backY, backW, backH);
        drawButton(g2, controlsBackButton, backX, backY, backW, backH, "Back", hoverControlsBack);
    }

    private void drawConfirmationDialog(Graphics2D g2, int width, int height) {
        int panelW = CONFIRM_PANEL_W;
        int panelH = CONFIRM_PANEL_H;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, width, height);

        drawFrame(
                g2,
                x,
                y,
                panelW,
                panelH,
                "ARE YOU SURE?",
                pendingConfirmation == Selection.MAIN_MENU ? "Return to the main menu?" : "Quit StarChem?"
        );

        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g2.setColor(new Color(205, 220, 235));
        String warn = "Unsaved progress will be lost.";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(warn, x + (panelW - fm.stringWidth(warn)) / 2, y + 112);

        int buttonW = 140;
        int buttonH = 42;
        int gap = 18;
        int bx1 = x + (panelW - (buttonW * 2 + gap)) / 2;
        int bx2 = bx1 + buttonW + gap;
        int by = y + 148;

        confirmCancelButton.setBounds(bx1, by, buttonW, buttonH);
        confirmActionButton.setBounds(bx2, by, buttonW, buttonH);

        drawButton(g2, confirmCancelButton, bx1, by, buttonW, buttonH, "Cancel", confirmationHovered == 1);
        drawButton(
                g2,
                confirmActionButton,
                bx2,
                by,
                buttonW,
                buttonH,
                pendingConfirmation == Selection.MAIN_MENU ? "Main Menu" : "Quit",
                confirmationHovered == 2
        );
    }

    private void drawFrame(Graphics2D g2, int x, int y, int w, int h, String title, String subtitle) {
        g2.setColor(new Color(0, 0, 0, 130));
        g2.fillRoundRect(x + 6, y + 7, w, h, 14, 14);

        g2.setColor(new Color(12, 18, 30, 245));
        g2.fillRoundRect(x, y, w, h, 14, 14);

        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(95, 165, 235));
        g2.drawRoundRect(x, y, w, h, 14, 14);

        g2.setColor(new Color(38, 57, 82));
        g2.drawRoundRect(x + 4, y + 4, w - 8, h - 8, 11, 11);

        g2.setColor(new Color(18, 28, 44));
        g2.fillRoundRect(x + 8, y + 8, w - 16, 64, 12, 12);
        g2.fillRect(x + 8, y + 34, w - 16, 38);

        g2.setColor(new Color(90, 170, 240));
        g2.drawLine(x + 20, y + 68, x + w - 20, y + 68);

        g2.setFont(new Font("SansSerif", Font.BOLD, 30));
        FontMetrics titleFM = g2.getFontMetrics();
        g2.setColor(new Color(240, 246, 255));
        g2.drawString(title, x + (w - titleFM.stringWidth(title)) / 2, y + 44);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        FontMetrics subFM = g2.getFontMetrics();
        g2.setColor(new Color(170, 205, 235));
        g2.drawString(subtitle, x + (w - subFM.stringWidth(subtitle)) / 2, y + 60);

        g2.setColor(new Color(90, 170, 240));
        g2.drawLine(x + 20, y + h - 18, x + w - 20, y + h - 18);
    }

    private void drawButton(Graphics2D g2,
                            Rectangle[] slots,
                            int index,
                            int x,
                            int y,
                            int w,
                            int h,
                            String text,
                            boolean active) {
        if (slots != null && index >= 0 && index < slots.length) {
            if (slots[index] == null) {
                slots[index] = new Rectangle();
            }
            slots[index].setBounds(x, y, w, h);
        }

        g2.setColor(new Color(0, 0, 0, 80));
        g2.fillRoundRect(x + 3, y + 3, w, h, 10, 10);

        g2.setColor(active ? new Color(48, 92, 150) : new Color(25, 38, 56));
        g2.fillRoundRect(x, y, w, h, 10, 10);

        g2.setColor(active ? new Color(145, 205, 255) : new Color(78, 108, 142));
        g2.drawLine(x + 2, y + 2, x + w - 3, y + 2);

        g2.setStroke(new BasicStroke(active ? 2.2f : 1.6f));
        g2.setColor(active ? new Color(145, 205, 255) : new Color(92, 140, 190));
        g2.drawRoundRect(x, y, w, h, 10, 10);

        g2.setColor(active ? new Color(120, 185, 255, 60) : new Color(120, 185, 255, 25));
        g2.drawRoundRect(x + 2, y + 2, w - 4, h - 4, 8, 8);

        g2.setFont(new Font("SansSerif", Font.BOLD, 18));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(active ? Color.WHITE : new Color(228, 233, 240));
        g2.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (h + fm.getAscent()) / 2 - 4);
    }

    private void drawButton(Graphics2D g2,
                            Rectangle slot,
                            int x,
                            int y,
                            int w,
                            int h,
                            String text,
                            boolean active) {
        if (slot != null) {
            slot.setBounds(x, y, w, h);
        }
        drawButton(g2, (Rectangle[]) null, -1, x, y, w, h, text, active);
    }

    private void drawToggleRow(Graphics2D g2,
                               Rectangle[] slots,
                               int index,
                               int x,
                               int y,
                               int w,
                               int h,
                               String label,
                               String value,
                               boolean active) {
        if (slots != null && index >= 0 && index < slots.length) {
            if (slots[index] == null) {
                slots[index] = new Rectangle();
            }
            slots[index].setBounds(x, y, w, h);
        }

        g2.setColor(new Color(0, 0, 0, 80));
        g2.fillRoundRect(x + 3, y + 3, w, h, 10, 10);

        g2.setColor(active ? new Color(48, 92, 150) : new Color(25, 38, 56));
        g2.fillRoundRect(x, y, w, h, 10, 10);

        g2.setColor(active ? new Color(145, 205, 255) : new Color(78, 108, 142));
        g2.drawLine(x + 2, y + 2, x + w - 3, y + 2);

        g2.setStroke(new BasicStroke(active ? 2.2f : 1.6f));
        g2.setColor(active ? new Color(145, 205, 255) : new Color(92, 140, 190));
        g2.drawRoundRect(x, y, w, h, 10, 10);

        g2.setFont(new Font("SansSerif", Font.BOLD, 17));
        g2.setColor(active ? Color.WHITE : new Color(228, 233, 240));
        FontMetrics fm = g2.getFontMetrics();

        g2.drawString(label, x + 16, y + (h + fm.getAscent()) / 2 - 4);
        g2.setColor(new Color(180, 210, 235));
        g2.drawString(value, x + w - 16 - fm.stringWidth(value), y + (h + fm.getAscent()) / 2 - 4);
    }

    private void layoutControlRows(Graphics2D g2, int left, int top, int rowW, boolean draw) {
        int yCursor = top - controlsScrollOffset;
        String currentGroup = null;

        g2.setFont(new Font("SansSerif", Font.BOLD, 15));

        for (int i = 0; i < bindings.size(); i++) {
            KeyBinding binding = bindings.get(i);

            if (!binding.group.equals(currentGroup)) {
                if (currentGroup != null) {
                    yCursor += CONTROL_GROUP_GAP;
                }

                if (draw) {
                    g2.setColor(new Color(180, 210, 235));
                    g2.drawString(binding.group.toUpperCase(), left, yCursor);
                }

                yCursor += 18;
                currentGroup = binding.group;
            }

            binding.bounds.setBounds(left, yCursor, rowW, CONTROL_ROW_H);

            if (draw) {
                boolean active = (i == hoveredBinding) || (i == editingBindingIndex);
                drawBindingRow(g2, binding, active);
            }

            yCursor += CONTROL_ROW_H + CONTROL_ROW_GAP;
        }
    }

    private void drawBindingRow(Graphics2D g2, KeyBinding binding, boolean active) {
        g2.setColor(new Color(0, 0, 0, 64));
        g2.fillRoundRect(binding.bounds.x + 3, binding.bounds.y + 3, binding.bounds.width, binding.bounds.height, 8, 8);

        boolean editable = binding.editable;
        Color base = editable ? (active ? new Color(48, 92, 150) : new Color(25, 38, 56)) : new Color(20, 28, 38);
        Color border = editable ? (active ? new Color(145, 205, 255) : new Color(92, 140, 190)) : new Color(64, 86, 110);

        g2.setColor(base);
        g2.fillRoundRect(binding.bounds.x, binding.bounds.y, binding.bounds.width, binding.bounds.height, 8, 8);

        g2.setColor(editable ? (active ? new Color(145, 205, 255) : new Color(78, 108, 142)) : new Color(64, 86, 110));
        g2.drawLine(binding.bounds.x + 2, binding.bounds.y + 2, binding.bounds.x + binding.bounds.width - 3, binding.bounds.y + 2);

        g2.setStroke(new BasicStroke(active ? 2.0f : 1.4f));
        g2.setColor(border);
        g2.drawRoundRect(binding.bounds.x, binding.bounds.y, binding.bounds.width, binding.bounds.height, 8, 8);

        g2.setFont(new Font("SansSerif", Font.BOLD, 14));
        FontMetrics fm = g2.getFontMetrics();

        g2.setColor(editable ? (active ? Color.WHITE : new Color(228, 233, 240)) : new Color(155, 170, 185));
        g2.drawString(binding.label, binding.bounds.x + 12, binding.bounds.y + 17);

        String keyText = binding.displayKeyText();
        if (editingBindingIndex >= 0 && bindings.get(editingBindingIndex) == binding) {
            keyText = "Press a new key...";
        }

        g2.setColor(editable ? new Color(180, 210, 235) : new Color(140, 155, 170));
        g2.drawString(keyText, binding.bounds.x + binding.bounds.width - 12 - fm.stringWidth(keyText), binding.bounds.y + 17);
    }

    private void seedBindings() {
        addBinding("Camera", "Camera Left (WASD)", "camera_left_wasd", KeyEvent.VK_A, false, true);
        addBinding("Camera", "Camera Left (Arrows)", "camera_left_arrow", KeyEvent.VK_LEFT, false, true);
        addBinding("Camera", "Camera Right (WASD)", "camera_right_wasd", KeyEvent.VK_D, false, true);
        addBinding("Camera", "Camera Right (Arrows)", "camera_right_arrow", KeyEvent.VK_RIGHT, false, true);
        addBinding("Camera", "Camera Up (WASD)", "camera_up_wasd", KeyEvent.VK_W, false, true);
        addBinding("Camera", "Camera Up (Arrows)", "camera_up_arrow", KeyEvent.VK_UP, false, true);
        addBinding("Camera", "Camera Down (WASD)", "camera_down_wasd", KeyEvent.VK_S, false, true);
        addBinding("Camera", "Camera Down (Arrows)", "camera_down_arrow", KeyEvent.VK_DOWN, false, true);

        addBinding("Interface", "Galaxy Map", "galaxy_map", KeyEvent.VK_M, false, true);
        addBinding("Interface", "Mute Audio", "mute_audio", KeyEvent.VK_M, true, true);
        addBinding("Interface", "Fleet Formation", "fleet_formation", KeyEvent.VK_F, false, true);
        addBinding("Interface", "Miner Range Overlay", "miner_range_overlay", KeyEvent.VK_R, false, true);

        addBinding("Orders", "Attack Move", "attack_move", KeyEvent.VK_X, false, true);
        addBinding("Orders", "Patrol", "patrol", KeyEvent.VK_P, false, true);
        addBinding("Orders", "Guard", "guard", KeyEvent.VK_G, false, true);
        addBinding("Orders", "Escort", "escort", KeyEvent.VK_E, false, true);
        addBinding("Orders", "Hold Position", "hold", KeyEvent.VK_H, false, true);

        addBinding("Debug", "AI Debug Overlay", "ai_debug_overlay", KeyEvent.VK_F3, false, true);
        addBinding("Debug", "Performance Overlay", "performance_overlay", KeyEvent.VK_F4, false, true);

        addBinding("Menu", "Game Menu", "game_menu", KeyEvent.VK_ESCAPE, false, false);
    }

    private void addBinding(String group, String label, String actionId, int keyCode, boolean ctrlRequired, boolean editable) {
        bindings.add(new KeyBinding(group, label, actionId, keyCode, ctrlRequired, editable));
    }

    private void addBinding(String group, String label, int keyCode, boolean ctrlRequired, boolean editable) {
        bindings.add(new KeyBinding(group, label, label.toLowerCase().replace(' ', '_'), keyCode, ctrlRequired, editable));
    }

    private int measureControlsContentHeight() {
        int height = 0;
        String currentGroup = null;

        for (KeyBinding binding : bindings) {
            if (!binding.group.equals(currentGroup)) {
                if (currentGroup != null) {
                    height += CONTROL_GROUP_GAP;
                }
                height += 18;
                currentGroup = binding.group;
            }
            height += CONTROL_ROW_H + CONTROL_ROW_GAP;
        }

        return height;
    }

    private int getControlsViewHeight() {
        return CONTROLS_PANEL_H - 182;
    }

    private void drawConfirmationDialog(Graphics2D g2, int width, int height, String title, String subtitle) {
        int panelW = CONFIRM_PANEL_W;
        int panelH = CONFIRM_PANEL_H;
        int x = (width - panelW) / 2;
        int y = (height - panelH) / 2;

        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, width, height);

        drawFrame(g2, x, y, panelW, panelH, title, subtitle);
    }

    private boolean isModifierOnly(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT
                || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class KeyBinding {
        final String group;
        final String label;
        final String actionId;
        int keyCode;
        boolean ctrlRequired;
        final boolean editable;
        final Rectangle bounds = new Rectangle();

        KeyBinding(String group, String label, String actionId, int keyCode, boolean ctrlRequired, boolean editable) {
            this.group = group;
            this.label = label;
            this.actionId = actionId;
            this.keyCode = keyCode;
            this.ctrlRequired = ctrlRequired;
            this.editable = editable;
        }

        String displayKeyText() {
            return (ctrlRequired ? "Ctrl+" : "") + KeyEvent.getKeyText(keyCode);
        }

        boolean matches(KeyEvent e) {
            return keyCode == e.getKeyCode() && ctrlRequired == e.isControlDown();
        }
    }
}
