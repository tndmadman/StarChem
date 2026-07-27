package com.tndmadman.rts;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

final class InGameMenuOverlay extends JComponent {
    private static final int PANEL_W = 620;
    private static final int PANEL_H = 590;
    private static final int SLIDER_W = 280;

    private final boolean soloMode;
    private final boolean tutorialAvailable;
    private final Runnable resumeAction;
    private final Runnable tutorialAction;
    private final Runnable restartTutorialAction;
    private final Runnable narrationAction;
    private final Runnable lobbyAction;
    private final List<Button> buttons = new ArrayList<>();
    private final Rectangle volumeSlider = new Rectangle();
    private boolean draggingVolume;

    InGameMenuOverlay(boolean soloMode,
                      boolean tutorialAvailable,
                      Runnable resumeAction,
                      Runnable tutorialAction,
                      Runnable restartTutorialAction,
                      Runnable narrationAction,
                      Runnable lobbyAction) {
        this.soloMode = soloMode;
        this.tutorialAvailable = tutorialAvailable;
        this.resumeAction = resumeAction;
        this.tutorialAction = tutorialAction;
        this.restartTutorialAction = restartTutorialAction;
        this.narrationAction = narrationAction;
        this.lobbyAction = lobbyAction;
        setOpaque(false);
        setFocusable(true);
        setVisible(false);

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (volumeSlider.contains(event.getPoint())) {
                    draggingVolume = true;
                    updateVolumeFromMouse(event.getX());
                    return;
                }
                click(event.getX(), event.getY());
            }

            @Override public void mouseReleased(MouseEvent event) {
                draggingVolume = false;
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent event) {
                if (draggingVolume) updateVolumeFromMouse(event.getX());
            }
        });

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-game-menu");
        getActionMap().put("close-game-menu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (isVisible() && resumeAction != null) resumeAction.run();
            }
        });
    }

    void open() {
        setVisible(true);
        requestFocusInWindow();
        repaint();
    }

    void close() {
        draggingVolume = false;
        setVisible(false);
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (!isVisible()) return;
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRect(0, 0, getWidth(), getHeight());

        int x = (getWidth() - PANEL_W) / 2;
        int y = (getHeight() - PANEL_H) / 2;
        g2.setColor(new Color(13, 21, 31, 250));
        g2.fillRoundRect(x, y, PANEL_W, PANEL_H, 22, 22);
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(88, 188, 255));
        g2.drawRoundRect(x, y, PANEL_W, PANEL_H, 22, 22);

        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 28f));
        g2.drawString("GAME MENU", x + 34, y + 48);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(170, 202, 224));
        g2.drawString(soloMode
                ? "Solo simulation is paused while this menu is open."
                : "Online simulation continues while this menu is open.", x + 34, y + 72);

        buttons.clear();
        int rowY = y + 98;
        drawWideButton(g2, new Rectangle(x + 34, rowY, PANEL_W - 68, 42), "Resume Game", "resume", true);

        rowY += 56;
        drawSectionLabel(g2, "TUTORIAL", x + 34, rowY + 15);
        rowY += 28;
        drawHalfButton(g2, new Rectangle(x + 34, rowY, 262, 42),
                tutorialAvailable ? "Start / Resume Tutorial" : "Tutorial: Solo Only", "tutorial", tutorialAvailable);
        drawHalfButton(g2, new Rectangle(x + 324, rowY, 262, 42),
                "Restart Tutorial", "restart-tutorial", tutorialAvailable);

        rowY += 68;
        drawSectionLabel(g2, "AUDIO", x + 34, rowY + 15);
        rowY += 30;
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
        g2.setColor(new Color(220, 235, 245));
        g2.drawString("Effects volume", x + 34, rowY + 24);

        volumeSlider.setBounds(x + 184, rowY + 6, SLIDER_W, 28);
        drawVolumeSlider(g2);
        drawSmallButton(g2, new Rectangle(x + 480, rowY, 48, 40), "-", "volume-down", true);
        drawSmallButton(g2, new Rectangle(x + 538, rowY, 48, 40), "+", "volume-up", true);

        rowY += 54;
        String muteText = ProceduralAudio.muted() ? "Unmute Effects" : "Mute Effects";
        drawHalfButton(g2, new Rectangle(x + 34, rowY, 262, 42), muteText, "mute", true);
        drawHalfButton(g2, new Rectangle(x + 324, rowY, 262, 42),
                "Narration Settings", "narration", true);

        rowY += 72;
        drawSectionLabel(g2, "SESSION", x + 34, rowY + 15);
        rowY += 30;
        drawWideButton(g2, new Rectangle(x + 34, rowY, PANEL_W - 68, 42),
                "Return to Main Menu", "lobby", false);

        rowY += 58;
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(135, 175, 200));
        g2.drawString("Press ESC at any time to close this menu.", x + 34, rowY + 10);
        g2.dispose();
    }

    private void drawVolumeSlider(Graphics2D g2) {
        int value = ProceduralAudio.volumePercent();
        g2.setColor(new Color(7, 13, 20));
        g2.fillRoundRect(volumeSlider.x, volumeSlider.y, volumeSlider.width, volumeSlider.height, 12, 12);
        g2.setColor(new Color(46, 78, 102));
        g2.fillRoundRect(volumeSlider.x + 6, volumeSlider.y + 10, volumeSlider.width - 12, 8, 8, 8);
        int usable = volumeSlider.width - 12;
        int fill = (int)Math.round(usable * value / 100.0);
        g2.setColor(new Color(86, 195, 255));
        g2.fillRoundRect(volumeSlider.x + 6, volumeSlider.y + 10, Math.max(6, fill), 8, 8, 8);
        int knobX = volumeSlider.x + 6 + fill;
        g2.setColor(Color.WHITE);
        g2.fillOval(knobX - 7, volumeSlider.y + 7, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(new Color(220, 240, 252));
        String label = value + "%";
        int tw = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, volumeSlider.x + (volumeSlider.width - tw) / 2, volumeSlider.y + 22);
    }

    private void drawSectionLabel(Graphics2D g2, String text, int x, int y) {
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(new Color(105, 195, 255));
        g2.drawString(text, x, y);
    }

    private void drawWideButton(Graphics2D g2, Rectangle bounds, String text, String action, boolean primary) {
        drawButton(g2, bounds, text, action, true, primary);
    }

    private void drawHalfButton(Graphics2D g2, Rectangle bounds, String text, String action, boolean enabled) {
        drawButton(g2, bounds, text, action, enabled, false);
    }

    private void drawSmallButton(Graphics2D g2, Rectangle bounds, String text, String action, boolean enabled) {
        drawButton(g2, bounds, text, action, enabled, false);
    }

    private void drawButton(Graphics2D g2, Rectangle bounds, String text, String action,
                            boolean enabled, boolean primary) {
        buttons.add(new Button(new Rectangle(bounds), action, enabled));
        if (!enabled) {
            g2.setColor(new Color(28, 37, 47));
            g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
            g2.setColor(new Color(78, 94, 108));
        } else if (primary) {
            g2.setColor(new Color(30, 105, 145));
            g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
            g2.setColor(new Color(125, 225, 255));
        } else {
            g2.setColor(new Color(32, 52, 70));
            g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
            g2.setColor(new Color(90, 180, 240));
        }
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        g2.setColor(enabled ? Color.WHITE : new Color(120, 132, 142));
        int tw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, bounds.x + Math.max(8, (bounds.width - tw) / 2), bounds.y + 26);
    }

    private void click(int x, int y) {
        for (Button button : buttons) {
            if (!button.enabled || !button.bounds.contains(x, y)) continue;
            switch (button.action) {
                case "resume" -> run(resumeAction);
                case "tutorial" -> run(tutorialAction);
                case "restart-tutorial" -> run(restartTutorialAction);
                case "mute" -> {
                    ProceduralAudio.toggleMute();
                    repaint();
                }
                case "volume-down" -> changeVolume(-10);
                case "volume-up" -> changeVolume(10);
                case "narration" -> run(narrationAction);
                case "lobby" -> run(lobbyAction);
                default -> { }
            }
            return;
        }
    }

    private void changeVolume(int delta) {
        ProceduralAudio.setVolumePercent(ProceduralAudio.volumePercent() + delta);
        ProceduralAudio.play(SoundCue.SELECT);
        repaint();
    }

    private void updateVolumeFromMouse(int mouseX) {
        int value = volumePercentAt(mouseX, volumeSlider.x, volumeSlider.width);
        ProceduralAudio.setVolumePercent(value);
        repaint();
    }

    private static void run(Runnable action) {
        if (action != null) action.run();
    }

    static int volumePercentAt(int mouseX, int sliderX, int sliderWidth) {
        if (sliderWidth <= 12) return 0;
        double normalized = (mouseX - (sliderX + 6)) / (double)(sliderWidth - 12);
        return (int)Math.round(Math.max(0.0, Math.min(1.0, normalized)) * 100.0);
    }

    static boolean pausesSimulation(boolean soloMode) {
        return soloMode;
    }

    private record Button(Rectangle bounds, String action, boolean enabled) { }
}
