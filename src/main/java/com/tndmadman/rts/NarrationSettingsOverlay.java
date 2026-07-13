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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NarrationSettingsOverlay extends JComponent {
    private static final int PANEL_W = 560;
    private static final int PANEL_H = 330;
    private final List<Button> buttons = new ArrayList<>();

    NarrationSettingsOverlay() {
        setOpaque(false);
        setFocusable(true);
        setVisible(false);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { click(e.getX(), e.getY()); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-narration");
        getActionMap().put("close-narration", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { close(); }
        });
    }

    void toggle() {
        setVisible(!isVisible());
        if (isVisible()) {
            NarrationService.voices();
            requestFocusInWindow();
        }
        repaint();
    }

    void close() {
        setVisible(false);
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        if (!isVisible()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0, 0, 0, 145));
        g2.fillRect(0, 0, getWidth(), getHeight());

        int x = (getWidth() - PANEL_W) / 2;
        int y = (getHeight() - PANEL_H) / 2;
        g2.setColor(new Color(16, 24, 34, 245));
        g2.fillRoundRect(x, y, PANEL_W, PANEL_H, 20, 20);
        g2.setColor(new Color(90, 190, 255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x, y, PANEL_W, PANEL_H, 20, 20);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 22f));
        g2.setColor(Color.WHITE);
        g2.drawString("Narration settings", x + 28, y + 38);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(175, 205, 225));
        g2.drawString("F8 opens this panel. The server decides what happened; this client only speaks the notice.", x + 28, y + 60);
        g2.drawString("Backend: " + NarrationService.backendLabel(), x + 28, y + 80);

        buttons.clear();
        int rowY = y + 104;
        drawLabel(g2, "Narration", x + 30, rowY + 23);
        drawButton(g2, new Rectangle(x + 210, rowY, 170, 34), NarrationService.enabled() ? "Enabled" : "Disabled", "toggle");

        rowY += 48;
        drawLabel(g2, "Voice", x + 30, rowY + 23);
        drawButton(g2, new Rectangle(x + 210, rowY, 42, 34), "<", "voice-prev");
        String voice = NarrationService.voice();
        if (voice.length() > 29) voice = voice.substring(0, 26) + "...";
        drawButton(g2, new Rectangle(x + 260, rowY, 232, 34), voice, "voice-next");
        drawButton(g2, new Rectangle(x + 500, rowY, 32, 34), ">", "voice-next");

        rowY += 48;
        drawLabel(g2, "Volume", x + 30, rowY + 23);
        drawButton(g2, new Rectangle(x + 210, rowY, 42, 34), "-", "volume-down");
        drawValue(g2, NarrationService.volume() + "%", x + 268, rowY, 106, 34);
        drawButton(g2, new Rectangle(x + 390, rowY, 42, 34), "+", "volume-up");

        rowY += 48;
        drawLabel(g2, "Speed", x + 30, rowY + 23);
        drawButton(g2, new Rectangle(x + 210, rowY, 42, 34), "-", "speed-down");
        drawValue(g2, String.format(Locale.ROOT, "%.1fx", NarrationService.speed()), x + 268, rowY, 106, 34);
        drawButton(g2, new Rectangle(x + 390, rowY, 42, 34), "+", "speed-up");

        rowY += 54;
        drawButton(g2, new Rectangle(x + 210, rowY, 145, 36), "Test voice", "test");
        drawButton(g2, new Rectangle(x + 370, rowY, 122, 36), "Close", "close");
        g2.dispose();
    }

    private void drawLabel(Graphics2D g2, String text, int x, int y) {
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
        g2.setColor(new Color(220, 235, 245));
        g2.drawString(text, x, y);
    }

    private void drawValue(Graphics2D g2, String text, int x, int y, int w, int h) {
        g2.setColor(new Color(8, 13, 20, 220));
        g2.fillRoundRect(x, y, w, h, 10, 10);
        g2.setColor(new Color(175, 220, 250));
        int tw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, x + (w - tw) / 2, y + 22);
    }

    private void drawButton(Graphics2D g2, Rectangle bounds, String text, String action) {
        buttons.add(new Button(bounds, action));
        boolean primary = "toggle".equals(action) && NarrationService.enabled();
        g2.setColor(primary ? new Color(34, 118, 86) : new Color(35, 58, 78));
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
        g2.setColor(primary ? new Color(125, 255, 195) : new Color(105, 195, 255));
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
        g2.setColor(Color.WHITE);
        int tw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, bounds.x + Math.max(7, (bounds.width - tw) / 2), bounds.y + 22);
    }

    private void click(int x, int y) {
        for (Button button : buttons) {
            if (!button.bounds.contains(x, y)) continue;
            switch (button.action) {
                case "toggle" -> NarrationService.toggle();
                case "voice-prev" -> NarrationService.previousVoice();
                case "voice-next" -> NarrationService.nextVoice();
                case "volume-down" -> NarrationService.setVolume(NarrationService.volume() - 10);
                case "volume-up" -> NarrationService.setVolume(NarrationService.volume() + 10);
                case "speed-down" -> NarrationService.setSpeed(NarrationService.speed() - 0.1);
                case "speed-up" -> NarrationService.setSpeed(NarrationService.speed() + 0.1);
                case "test" -> NarrationService.testVoice();
                case "close" -> close();
                default -> { }
            }
            repaint();
            return;
        }
    }

    private record Button(Rectangle bounds, String action) { }
}
