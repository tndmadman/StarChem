package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Renderer and input model ported from feature/game-menu. */
public final class SettingsPanel {
    public enum Source { LOBBY, IN_GAME }
    public enum Result { NONE, BACK }
    private enum Tab { CONTROLS, AUDIO, DISPLAY }

    private static final int PANEL_W = 980;
    private static final int PANEL_H = 680;
    private static final int TITLE_H = 72;
    private static final int TAB_H = 42;

    private final GameSettings settings;
    private final Runnable displayChangeListener;
    private final Rectangle panelRect = new Rectangle();
    private final Rectangle[] tabRects = new Rectangle[3];
    private final Rectangle backRect = new Rectangle();
    private final Rectangle defaultsRect = new Rectangle();
    private final Rectangle audioMasterRect = new Rectangle();
    private final Rectangle audioMusicRect = new Rectangle();
    private final Rectangle audioEffectsRect = new Rectangle();
    private final Rectangle displayFullscreenRect = new Rectangle();
    private final Rectangle displayFpsRect = new Rectangle();
    private final List<RowHit> visibleControlRows = new ArrayList<>();

    private Source source = Source.LOBBY;
    private Tab tab = Tab.CONTROLS;
    private boolean open;
    private String editingActionId;
    private String statusLine = "";
    private int controlsScroll;
    private int displayScroll;
    private int lastWidth = 1280;
    private int lastHeight = 720;

    public SettingsPanel(GameSettings settings) { this(settings, null); }

    public SettingsPanel(GameSettings settings, Runnable displayChangeListener) {
        this.settings = settings;
        this.displayChangeListener = displayChangeListener;
    }

    public void open(Source source) {
        this.source = source == null ? Source.LOBBY : source;
        open = true;
        editingActionId = null;
        statusLine = "";
    }

    public boolean isOpen() { return open; }
    public Source getSource() { return source; }
    public String getStatusLine() { return statusLine; }
    public boolean isEditingBinding() { return editingActionId != null; }

    public Result handleEscapePressed() {
        if (!open) return Result.NONE;
        if (editingActionId != null) {
            editingActionId = null;
            statusLine = "Rebind cancelled.";
            return Result.NONE;
        }
        open = false;
        return Result.BACK;
    }

    public boolean handleKeyPressed(KeyEvent event) {
        if (!open || editingActionId == null || event == null) return false;
        if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            editingActionId = null;
            statusLine = "Rebind cancelled.";
            return true;
        }
        if (isModifierOnly(event.getKeyCode())) return true;
        GameSettings.Binding binding = settings.binding(editingActionId);
        String label = binding == null ? editingActionId : binding.label();
        GameSettings.RebindResult result = settings.rebindSwap(
                editingActionId, event.getKeyCode(), event.isControlDown());
        statusLine = switch (result) {
            case APPLIED -> label + " bound to " + settings.bindingText(editingActionId) + ".";
            case SWAPPED -> label + " binding swapped.";
            case BLOCKED -> "That key is already assigned to another action.";
        };
        editingActionId = null;
        return true;
    }

    public boolean handleMouseWheel(int rotation) {
        if (!open) return false;
        if (tab == Tab.CONTROLS) {
            int maxScroll = Math.max(0, measureControlsContentHeight() - controlsViewHeight());
            controlsScroll = clamp(controlsScroll + rotation * 30, 0, maxScroll);
            return true;
        }
        if (tab == Tab.DISPLAY) {
            int maxScroll = Math.max(0, measureDisplayContentHeight() - displayViewHeight());
            displayScroll = clamp(displayScroll + rotation * 30, 0, maxScroll);
            return true;
        }
        return false;
    }

    public void updateHover(int mouseX, int mouseY) {
        if (open) updateLayout(lastWidth, lastHeight);
    }

    public boolean drag(int mouseX, int mouseY) {
        if (!open || tab != Tab.AUDIO) return false;
        updateLayout(lastWidth, lastHeight);
        return updateAudioAt(mouseX, mouseY);
    }

    public Result click(int mouseX, int mouseY) {
        if (!open) return Result.NONE;
        updateLayout(lastWidth, lastHeight);
        for (int i = 0; i < tabRects.length; i++) {
            if (tabRects[i].contains(mouseX, mouseY)) {
                tab = Tab.values()[i];
                editingActionId = null;
                statusLine = "";
                return Result.NONE;
            }
        }
        if (backRect.contains(mouseX, mouseY)) {
            open = false;
            editingActionId = null;
            return Result.BACK;
        }
        if (defaultsRect.contains(mouseX, mouseY)) {
            settings.resetDefaults();
            notifyDisplayChanged();
            statusLine = "Defaults restored.";
            editingActionId = null;
            return Result.NONE;
        }
        switch (tab) {
            case CONTROLS -> {
                for (RowHit hit : visibleControlRows) {
                    if (!hit.bounds.contains(mouseX, mouseY)) continue;
                    GameSettings.Binding binding = settings.binding(hit.actionId);
                    if (binding == null || !binding.editable()) return Result.NONE;
                    editingActionId = hit.actionId;
                    statusLine = "Press a key for " + hit.label + ".";
                    return Result.NONE;
                }
            }
            case AUDIO -> updateAudioAt(mouseX, mouseY);
            case DISPLAY -> {
                if (displayFullscreenRect.contains(mouseX, mouseY)) {
                    settings.setFullscreen(!settings.isFullscreen());
                    notifyDisplayChanged();
                    statusLine = "Fullscreen toggled.";
                } else if (displayFpsRect.contains(mouseX, mouseY)) {
                    settings.setShowFps(!settings.isShowFps());
                    statusLine = "FPS overlay toggled.";
                } else {
                    String[] labels = settings.resolutionLabels();
                    for (int i = 0; i < labels.length; i++) {
                        if (!displayResolutionRect(i).contains(mouseX, mouseY)) continue;
                        settings.setResolutionIndex(i);
                        notifyDisplayChanged();
                        statusLine = "Resolution set to " + settings.resolutionLabel() + ".";
                        break;
                    }
                }
            }
        }
        return Result.NONE;
    }

    private boolean updateAudioAt(int mouseX, int mouseY) {
        if (audioMasterRect.contains(mouseX, mouseY)) {
            settings.setMasterVolume(sliderValue(mouseX, audioMasterRect));
            statusLine = "Master volume set.";
            return true;
        }
        if (audioMusicRect.contains(mouseX, mouseY)) {
            settings.setMusicVolume(sliderValue(mouseX, audioMusicRect));
            statusLine = "Music volume set. Music is not implemented yet.";
            return true;
        }
        if (audioEffectsRect.contains(mouseX, mouseY)) {
            settings.setEffectsVolume(sliderValue(mouseX, audioEffectsRect));
            statusLine = "Effects volume set.";
            return true;
        }
        return false;
    }

    public void draw(Graphics2D graphics, int width, int height) {
        lastWidth = width;
        lastHeight = height;
        updateLayout(width, height);
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(10, 18, 30, 245));
        g2.fillRoundRect(panelRect.x, panelRect.y, panelRect.width, panelRect.height, 24, 24);
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(92, 137, 180, 170));
        g2.drawRoundRect(panelRect.x, panelRect.y, panelRect.width, panelRect.height, 24, 24);
        g2.setFont(new Font("SansSerif", Font.BOLD, 28));
        g2.setColor(new Color(235, 246, 255));
        drawCentered(g2, "SETTINGS", width, panelRect.y + 46);
        drawTabs(g2);
        drawFooterButtons(g2);
        drawStatusLine(g2);
        switch (tab) {
            case CONTROLS -> drawControlsTab(g2);
            case AUDIO -> drawAudioTab(g2);
            case DISPLAY -> drawDisplayTab(g2);
        }
        g2.dispose();
    }

    private void drawTabs(Graphics2D g2) {
        String[] labels = {"Controls", "Audio", "Display"};
        for (int i = 0; i < tabRects.length; i++) {
            Rectangle rectangle = tabRects[i];
            boolean selected = tab == Tab.values()[i];
            g2.setColor(selected ? new Color(40, 72, 110) : new Color(25, 38, 56));
            g2.fillRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 16, 16);
            g2.setColor(selected ? new Color(145, 205, 255) : new Color(82, 135, 182));
            g2.drawRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 16, 16);
            g2.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2.setColor(selected ? Color.WHITE : new Color(228, 233, 240));
            FontMetrics metrics = g2.getFontMetrics();
            g2.drawString(labels[i], rectangle.x + (rectangle.width - metrics.stringWidth(labels[i])) / 2,
                    rectangle.y + 29);
        }
    }

    private void drawFooterButtons(Graphics2D g2) {
        drawButton(g2, backRect, "Back", false);
        drawButton(g2, defaultsRect, "Reset Defaults", false);
    }

    private void drawStatusLine(Graphics2D g2) {
        if (statusLine == null || statusLine.isBlank()) return;
        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g2.setColor(new Color(170, 205, 235));
        g2.drawString(statusLine, panelRect.x + 24, panelRect.y + panelRect.height - 66);
    }

    private void drawControlsTab(Graphics2D g2) {
        int left = panelRect.x + 24;
        int top = panelRect.y + TITLE_H + TAB_H + 24;
        int width = panelRect.width - 48;
        int listHeight = controlsViewHeight();
        Rectangle clip = new Rectangle(left, top, width, listHeight);
        Shape oldClip = g2.getClip();
        g2.setClip(clip);
        visibleControlRows.clear();
        int y = top - controlsScroll;
        String currentGroup = null;
        g2.setFont(new Font("SansSerif", Font.BOLD, 14));
        for (GameSettings.Binding binding : settings.bindings()) {
            if (!binding.group().equals(currentGroup)) {
                if (currentGroup != null) y += 10;
                g2.setColor(new Color(180, 210, 235));
                g2.drawString(binding.group().toUpperCase(), left, y + 14);
                y += 20;
                currentGroup = binding.group();
            }
            Rectangle row = new Rectangle(left, y, width, 28);
            if (row.y + row.height >= top && row.y <= top + listHeight) {
                visibleControlRows.add(new RowHit(binding.actionId(), binding.label(), row));
            }
            boolean editing = binding.actionId().equals(editingActionId);
            drawRow(g2, row, binding.label(), editing ? "Press a key..." : binding.displayText(),
                    editing, binding.editable());
            y += 34;
        }
        g2.setClip(oldClip);
        drawPanelHint(g2, "Click a key to edit it. Duplicate keybinds are not allowed.",
                panelRect.x + 24, panelRect.y + panelRect.height - 84);
        drawScrollbar(g2, top, listHeight, measureControlsContentHeight(), controlsScroll);
    }

    private void drawAudioTab(Graphics2D g2) {
        int left = panelRect.x + 24;
        int y = panelRect.y + TITLE_H + TAB_H + 34;
        int rowWidth = panelRect.width - 48;
        drawSliderRow(g2, audioMasterRect, left, y, rowWidth, "Master Volume", settings.masterVolume());
        y += 78;
        drawSliderRow(g2, audioMusicRect, left, y, rowWidth, "Music Volume", settings.musicVolume());
        y += 78;
        drawSliderRow(g2, audioEffectsRect, left, y, rowWidth, "Effects Volume", settings.effectsVolume());
        drawPanelHint(g2, "Audio changes save and apply immediately.", left,
                panelRect.y + panelRect.height - 84);
    }

    private void drawDisplayTab(Graphics2D g2) {
        int left = panelRect.x + 24;
        int top = panelRect.y + TITLE_H + TAB_H + 24;
        int width = panelRect.width - 48;
        int viewHeight = displayViewHeight();
        Shape oldClip = g2.getClip();
        g2.setClip(new Rectangle(left, top, width, viewHeight));
        int y = top - displayScroll;
        drawToggleRow(g2, displayFullscreenRect, left, y, width, "Fullscreen", settings.isFullscreen());
        y += 58;
        drawToggleRow(g2, displayFpsRect, left, y, width, "Show FPS", settings.isShowFps());
        y += 64;
        g2.setFont(new Font("SansSerif", Font.BOLD, 16));
        g2.setColor(new Color(235, 246, 255));
        g2.drawString("Resolution", left, y);
        y += 18;
        String[] labels = settings.resolutionLabels();
        for (int i = 0; i < labels.length; i++) {
            drawResolutionRow(g2, displayResolutionRect(i), left, y, width,
                    labels[i], i == settings.resolutionIndex());
            y += 40;
        }
        drawPanelHint(g2, "Display changes are applied immediately.", left,
                panelRect.y + panelRect.height - 84);
        drawScrollbar(g2, top, viewHeight, measureDisplayContentHeight(), displayScroll);
        g2.setClip(oldClip);
    }

    private void drawScrollbar(Graphics2D g2, int trackY, int trackHeight,
                               int contentHeight, int scroll) {
        int maxScroll = Math.max(0, contentHeight - trackHeight);
        if (maxScroll <= 0) return;
        int trackX = panelRect.x + panelRect.width - 14;
        g2.setColor(new Color(0, 0, 0, 80));
        g2.fillRoundRect(trackX, trackY, 6, trackHeight, 6, 6);
        int thumbHeight = Math.max(36,
                (int)Math.round((double)trackHeight * trackHeight / (contentHeight + trackHeight)));
        int thumbRange = Math.max(0, trackHeight - thumbHeight);
        int thumbY = trackY + (int)Math.round((double)scroll / maxScroll * thumbRange);
        g2.setColor(new Color(90, 170, 240, 180));
        g2.fillRoundRect(trackX - 1, thumbY, 8, thumbHeight, 8, 8);
    }

    private Rectangle displayResolutionRect(int index) {
        int left = panelRect.x + 24;
        int rowWidth = panelRect.width - 48;
        int y = panelRect.y + TITLE_H + TAB_H + 24 - displayScroll + 58 + 64 + 18 + index * 40;
        return new Rectangle(left, y, rowWidth, 30);
    }

    private void drawButton(Graphics2D g2, Rectangle rectangle, String label, boolean active) {
        g2.setColor(active ? new Color(48, 92, 150) : new Color(25, 38, 56));
        g2.fillRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 14, 14);
        g2.setColor(active ? new Color(145, 205, 255) : new Color(82, 135, 182));
        g2.drawRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 14, 14);
        g2.setFont(new Font("SansSerif", Font.BOLD, 16));
        g2.setColor(active ? Color.WHITE : new Color(228, 233, 240));
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(label, rectangle.x + (rectangle.width - metrics.stringWidth(label)) / 2,
                rectangle.y + 26);
    }

    private void drawRow(Graphics2D g2, Rectangle rectangle, String label, String value,
                         boolean active, boolean editable) {
        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRoundRect(rectangle.x + 2, rectangle.y + 2, rectangle.width, rectangle.height, 10, 10);
        g2.setColor(active ? new Color(48, 92, 150)
                : editable ? new Color(25, 38, 56) : new Color(20, 28, 38));
        g2.fillRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 10, 10);
        g2.setColor(active ? new Color(145, 205, 255) : new Color(82, 135, 182));
        g2.drawRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 10, 10);
        g2.setFont(new Font("SansSerif", Font.BOLD, 14));
        g2.setColor(editable ? Color.WHITE : new Color(155, 170, 185));
        g2.drawString(label, rectangle.x + 12, rectangle.y + 19);
        g2.setColor(new Color(180, 210, 235));
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(value, rectangle.x + rectangle.width - 12 - metrics.stringWidth(value),
                rectangle.y + 19);
    }

    private void drawToggleRow(Graphics2D g2, Rectangle rectangle, int x, int y, int width,
                               String label, boolean on) {
        rectangle.setBounds(x, y, width, 42);
        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillRoundRect(x + 2, y + 2, width, 42, 10, 10);
        g2.setColor(on ? new Color(48, 92, 150) : new Color(25, 38, 56));
        g2.fillRoundRect(x, y, width, 42, 10, 10);
        g2.setColor(on ? new Color(145, 205, 255) : new Color(82, 135, 182));
        g2.drawRoundRect(x, y, width, 42, 10, 10);
        g2.setFont(new Font("SansSerif", Font.BOLD, 15));
        g2.setColor(Color.WHITE);
        g2.drawString(label, x + 14, y + 26);
        g2.setColor(new Color(180, 210, 235));
        g2.drawString(on ? "ON" : "OFF", x + width - 40, y + 26);
    }

    private void drawResolutionRow(Graphics2D g2, Rectangle rectangle, int x, int y, int width,
                                   String label, boolean selected) {
        rectangle.setBounds(x, y, width, 30);
        g2.setColor(new Color(0, 0, 0, 60));
        g2.fillRoundRect(x + 2, y + 2, width, 30, 10, 10);
        g2.setColor(selected ? new Color(48, 92, 150) : new Color(25, 38, 56));
        g2.fillRoundRect(x, y, width, 30, 10, 10);
        g2.setColor(selected ? new Color(145, 205, 255) : new Color(82, 135, 182));
        g2.drawRoundRect(x, y, width, 30, 10, 10);
        g2.setFont(new Font("SansSerif", Font.BOLD, 14));
        g2.setColor(selected ? Color.WHITE : new Color(228, 233, 240));
        g2.drawString((selected ? "✓ " : "  ") + label, x + 10, y + 20);
    }

    private void drawSliderRow(Graphics2D g2, Rectangle rectangle, int x, int y, int width,
                               String label, int value) {
        rectangle.setBounds(x, y, width, 50);
        g2.setFont(new Font("SansSerif", Font.BOLD, 15));
        g2.setColor(new Color(235, 246, 255));
        g2.drawString(label, x, y + 16);
        int trackY = y + 28;
        g2.setColor(new Color(25, 38, 56));
        g2.fillRoundRect(x, trackY, width, 12, 12, 12);
        int fillWidth = (int)Math.round((width - 12) * (value / 100.0));
        g2.setColor(new Color(90, 170, 240));
        g2.fillRoundRect(x, trackY, Math.max(0, fillWidth), 12, 12, 12);
        g2.setColor(new Color(145, 205, 255));
        g2.drawRoundRect(x, trackY, width, 12, 12, 12);
        g2.setColor(new Color(180, 210, 235));
        g2.drawString(value + "%", x + width - 42, y + 16);
    }

    private void drawPanelHint(Graphics2D g2, String text, int x, int y) {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g2.setColor(new Color(170, 205, 235));
        g2.drawString(text, x, y);
    }

    private void updateLayout(int width, int height) {
        panelRect.setBounds((width - PANEL_W) / 2, (height - PANEL_H) / 2, PANEL_W, PANEL_H);
        int tabY = panelRect.y + TITLE_H + 6;
        int tabWidth = 160;
        int tabGap = 12;
        int tabX = panelRect.x + 24;
        for (int i = 0; i < tabRects.length; i++) {
            if (tabRects[i] == null) tabRects[i] = new Rectangle();
            tabRects[i].setBounds(tabX + i * (tabWidth + tabGap), tabY, tabWidth, TAB_H);
        }
        backRect.setBounds(panelRect.x + panelRect.width - 146,
                panelRect.y + panelRect.height - 56, 120, 36);
        defaultsRect.setBounds(panelRect.x + 24, panelRect.y + panelRect.height - 56, 160, 36);
        int left = panelRect.x + 24;
        int rowWidth = panelRect.width - 48;
        int audioY = panelRect.y + TITLE_H + TAB_H + 34;
        audioMasterRect.setBounds(left, audioY, rowWidth, 50);
        audioMusicRect.setBounds(left, audioY + 78, rowWidth, 50);
        audioEffectsRect.setBounds(left, audioY + 156, rowWidth, 50);
        displayFullscreenRect.setBounds(left, panelRect.y + TITLE_H + TAB_H + 28, rowWidth, 42);
        displayFpsRect.setBounds(left, panelRect.y + TITLE_H + TAB_H + 86, rowWidth, 42);
    }

    private int controlsViewHeight() { return PANEL_H - TITLE_H - TAB_H - 150; }
    private int displayViewHeight() { return PANEL_H - TITLE_H - TAB_H - 150; }

    private int measureControlsContentHeight() {
        int height = 0;
        String group = null;
        for (GameSettings.Binding binding : settings.bindings()) {
            if (!binding.group().equals(group)) {
                if (group != null) height += 10;
                height += 20;
                group = binding.group();
            }
            height += 34;
        }
        return height;
    }

    private int measureDisplayContentHeight() {
        return 130 + settings.resolutionLabels().length * 40;
    }

    static int sliderValue(int mouseX, Rectangle rectangle) {
        int x = clamp(mouseX - rectangle.x, 0, rectangle.width);
        return clamp((int)Math.round(x * 100.0 / Math.max(1, rectangle.width)), 0, 100);
    }

    private void notifyDisplayChanged() {
        if (displayChangeListener != null) displayChangeListener.run();
    }

    private static boolean isModifierOnly(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawCentered(Graphics2D g2, String text, int width, int y) {
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(text, (width - metrics.stringWidth(text)) / 2, y);
    }

    static int panelWidthForTest() { return PANEL_W; }
    static int panelHeightForTest() { return PANEL_H; }

    private record RowHit(String actionId, String label, Rectangle bounds) { }
}
