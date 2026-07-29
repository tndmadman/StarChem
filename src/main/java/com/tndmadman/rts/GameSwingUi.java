package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicToolTipUI;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.Objects;

/** Installs StarChem-styled Swing chrome and deterministic hover tooltips. */
public final class GameSwingUi {
    private static final Color TRACK = new Color(4, 15, 24);
    private static final Color TRACK_INNER = new Color(7, 24, 37);
    private static final Color TRACK_EDGE = new Color(27, 70, 96);
    private static final Color THUMB = new Color(40, 126, 164);
    private static final Color THUMB_HOVER = new Color(70, 190, 230);
    private static final Color ACCENT = new Color(120, 220, 255);
    private static final Color TOOLTIP_BG = new Color(5, 18, 29);
    private static final Color TOOLTIP_EDGE = new Color(86, 192, 240);
    private static final Color TOOLTIP_TEXT = new Color(225, 242, 250);
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private static boolean installed;
    private static TooltipController tooltipController;

    private GameSwingUi() { }

    public static synchronized void install() {
        if (installed) return;
        installed = true;

        UIManager.put("ScrollBarUI", GameScrollBarUI.class.getName());
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("ScrollBar.minimumThumbSize", new Dimension(10, 28));
        UIManager.put("ScrollBar.background", TRACK);
        UIManager.put("ScrollPane.background", TRACK);
        UIManager.put("Viewport.background", TRACK);
        UIManager.put("ToolTipUI", GameToolTipUI.class.getName());
        UIManager.put("ToolTip.background", TOOLTIP_BG);
        UIManager.put("ToolTip.foreground", TOOLTIP_TEXT);
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(9, 12, 10, 12));

        ToolTipManager manager = ToolTipManager.sharedInstance();
        manager.setInitialDelay(0);
        manager.setReshowDelay(0);
        manager.setDismissDelay(60_000);
        manager.setEnabled(false);

        if (!GraphicsEnvironment.isHeadless()) {
            tooltipController = new TooltipController();
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    tooltipController,
                    AWTEvent.MOUSE_EVENT_MASK
                            | AWTEvent.MOUSE_MOTION_EVENT_MASK
                            | AWTEvent.WINDOW_EVENT_MASK);
        }
    }

    static JComponent tooltipOwner(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JComponent swing) {
                String text = swing.getToolTipText();
                if (text != null && !text.isBlank()) return swing;
            }
            current = current.getParent();
        }
        return null;
    }

    static boolean customTooltipRoutingInstalledForTest() {
        return GraphicsEnvironment.isHeadless() || tooltipController != null;
    }

    static JComponent tooltipPanelForTest(String text) {
        return new GameTooltipPanel(text, true);
    }

    static Color tooltipFallbackBackgroundForTest() {
        return TOOLTIP_BG;
    }

    public static final class GameScrollBarUI extends BasicScrollBarUI {
        public static ComponentUI createUI(JComponent component) {
            return new GameScrollBarUI();
        }

        @Override protected void configureScrollBarColors() {
            trackColor = TRACK;
            thumbColor = THUMB;
            thumbHighlightColor = ACCENT;
            thumbDarkShadowColor = TRACK_EDGE;
            thumbLightShadowColor = ACCENT;
        }

        @Override public void installUI(JComponent component) {
            super.installUI(component);
            component.setOpaque(true);
            component.setBackground(TRACK);
            component.setForeground(ACCENT);
            component.setBorder(BorderFactory.createEmptyBorder());
            if (component instanceof JScrollBar bar) {
                bar.setFocusable(false);
                bar.setUnitIncrement(Math.max(24, bar.getUnitIncrement()));
            }
        }

        @Override protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        @Override protected Dimension getMinimumThumbSize() {
            return scrollbar.getOrientation() == Adjustable.VERTICAL
                    ? new Dimension(10, 28)
                    : new Dimension(28, 10);
        }

        @Override protected void paintTrack(Graphics graphics, JComponent component, Rectangle trackBounds) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Paint the complete component first. Leaving these outside pixels transparent
            // exposed the operating-system scrollbar background as white framing.
            g.setColor(TRACK);
            g.fillRect(0, 0, component.getWidth(), component.getHeight());

            int insetX = scrollbar.getOrientation() == Adjustable.VERTICAL ? 2 : 1;
            int insetY = scrollbar.getOrientation() == Adjustable.VERTICAL ? 1 : 2;
            int x = trackBounds.x + insetX;
            int y = trackBounds.y + insetY;
            int width = Math.max(1, trackBounds.width - insetX * 2);
            int height = Math.max(1, trackBounds.height - insetY * 2);

            g.setColor(TRACK_INNER);
            g.fillRoundRect(x, y, width, height, 8, 8);
            g.setColor(TRACK_EDGE);
            g.drawRoundRect(x, y, Math.max(0, width - 1), Math.max(0, height - 1), 8, 8);
            g.dispose();
        }

        @Override protected void paintThumb(Graphics graphics, JComponent component, Rectangle thumbBounds) {
            if (!component.isEnabled() || thumbBounds.isEmpty()) return;
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x = thumbBounds.x + 2;
            int y = thumbBounds.y + 2;
            int width = Math.max(1, thumbBounds.width - 4);
            int height = Math.max(1, thumbBounds.height - 4);
            Color fill = isThumbRollover() ? THUMB_HOVER : THUMB;

            g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 150));
            g.fillRoundRect(x, y, width, height, 8, 8);
            g.setColor(isThumbRollover() ? new Color(220, 250, 255) : ACCENT);
            g.drawRoundRect(x, y, Math.max(0, width - 1), Math.max(0, height - 1), 8, 8);

            g.setColor(new Color(205, 240, 252, 185));
            if (scrollbar.getOrientation() == Adjustable.VERTICAL) {
                int centerY = y + height / 2;
                for (int offset = -4; offset <= 4; offset += 4) {
                    g.drawLine(x + 3, centerY + offset, x + width - 4, centerY + offset);
                }
            } else {
                int centerX = x + width / 2;
                for (int offset = -4; offset <= 4; offset += 4) {
                    g.drawLine(centerX + offset, y + 3, centerX + offset, y + height - 4);
                }
            }
            g.dispose();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setFocusable(false);
            button.setBorder(BorderFactory.createEmptyBorder());
            button.setBackground(TRACK);
            button.setOpaque(true);
            return button;
        }
    }

    /**
     * Retained for any directly-created JToolTip instances. Normal in-game hover details
     * are displayed by the dedicated transparent GameTooltipPanel/JWindow path below.
     */
    public static final class GameToolTipUI extends BasicToolTipUI {
        public static ComponentUI createUI(JComponent component) {
            return new GameToolTipUI();
        }

        @Override public void installUI(JComponent component) {
            super.installUI(component);
            component.setOpaque(false);
            component.setForeground(TOOLTIP_TEXT);
            component.setBackground(TOOLTIP_BG);
            component.setBorder(BorderFactory.createEmptyBorder(9, 12, 10, 12));
            Font font = UIManager.getFont("Label.font");
            if (font != null) component.setFont(font.deriveFont(Font.PLAIN, 12f));
        }

        @Override public void update(Graphics graphics, JComponent component) {
            // Do not let BasicToolTipUI pre-fill a native rectangular background.
            paint(graphics, component);
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            paintTooltipShell((Graphics2D)graphics, component.getWidth(), component.getHeight());
            super.paint(graphics, component);
        }
    }

    private static final class GameTooltipPanel extends JPanel {
        private GameTooltipPanel(String text, boolean transparentHost) {
            super(new BorderLayout());
            setOpaque(!transparentHost);
            setBackground(TOOLTIP_BG);
            setBorder(BorderFactory.createEmptyBorder(9, 12, 10, 12));

            JLabel label = new JLabel(text);
            label.setOpaque(false);
            label.setForeground(TOOLTIP_TEXT);
            Font font = UIManager.getFont("Label.font");
            if (font != null) label.setFont(font.deriveFont(Font.PLAIN, 12f));
            add(label, BorderLayout.CENTER);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            paintTooltipShell(g, getWidth(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static void paintTooltipShell(Graphics2D source, int width, int height) {
        Graphics2D g = (Graphics2D)source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(0, 0, 0, 135));
        g.fillRoundRect(3, 4, Math.max(1, width - 4), Math.max(1, height - 5), 11, 11);
        g.setColor(TOOLTIP_BG);
        g.fillRoundRect(0, 0, Math.max(1, width - 3), Math.max(1, height - 3), 10, 10);
        g.setColor(TOOLTIP_EDGE);
        g.drawRoundRect(0, 0, Math.max(0, width - 4), Math.max(0, height - 4), 10, 10);
        g.setColor(new Color(120, 220, 255, 145));
        g.drawLine(8, 3, Math.max(8, width - 12), 3);
        g.dispose();
    }

    private static final class TooltipController implements AWTEventListener {
        private JComponent owner;
        private String text;
        private JWindow tooltipWindow;

        @Override public void eventDispatched(AWTEvent event) {
            if (event instanceof WindowEvent windowEvent) {
                if (windowEvent.getID() == WindowEvent.WINDOW_DEACTIVATED
                        || windowEvent.getID() == WindowEvent.WINDOW_CLOSED
                        || windowEvent.getID() == WindowEvent.WINDOW_CLOSING) {
                    hide();
                }
                return;
            }
            if (!(event instanceof MouseEvent mouse)) return;

            Component source = mouse.getSource() instanceof Component component ? component : null;
            if (tooltipWindow != null && source != null
                    && SwingUtilities.getWindowAncestor(source) == tooltipWindow) {
                return;
            }

            switch (mouse.getID()) {
                case MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED -> update(mouse);
                case MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED -> hide();
                default -> { }
            }
        }

        private void update(MouseEvent event) {
            Point screen;
            try {
                screen = event.getLocationOnScreen();
            } catch (IllegalComponentStateException ignored) {
                hide();
                return;
            }

            JComponent nextOwner = ownerAt(event, screen);
            String nextText = nextOwner == null ? null : nextOwner.getToolTipText();
            if (nextOwner == owner && Objects.equals(nextText, text)) return;

            hide();
            if (nextOwner == null || nextText == null || nextText.isBlank()) return;
            show(nextOwner, nextText, screen);
        }

        private JComponent ownerAt(MouseEvent event, Point screen) {
            Component source = event.getSource() instanceof Component component ? component : null;
            Window window = source instanceof Window sourceWindow
                    ? sourceWindow
                    : source == null ? null : SwingUtilities.getWindowAncestor(source);
            if (window == null || !window.isShowing()) return null;

            Point inWindow = new Point(screen);
            SwingUtilities.convertPointFromScreen(inWindow, window);
            Component deepest = SwingUtilities.getDeepestComponentAt(window, inWindow.x, inWindow.y);
            return tooltipOwner(deepest);
        }

        private void show(JComponent nextOwner, String nextText, Point pointer) {
            Window host = SwingUtilities.getWindowAncestor(nextOwner);
            JWindow window = host == null ? new JWindow() : new JWindow(host);
            window.setFocusableWindowState(false);
            window.setType(Window.Type.POPUP);

            boolean transparentHost = supportsPerPixelTransparency(nextOwner.getGraphicsConfiguration());
            configureWindowBackground(window, transparentHost);

            GameTooltipPanel panel = new GameTooltipPanel(nextText, transparentHost);
            window.setContentPane(panel);
            window.pack();
            Dimension size = window.getSize();

            GraphicsConfiguration configuration = nextOwner.getGraphicsConfiguration();
            Rectangle bounds = configuration == null
                    ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
                    : configuration.getBounds();

            int popupX = pointer.x + 14;
            int popupY = pointer.y + 18;
            if (popupX + size.width > bounds.x + bounds.width - 6) {
                popupX = Math.max(bounds.x + 6, pointer.x - size.width - 14);
            }
            if (popupY + size.height > bounds.y + bounds.height - 6) {
                popupY = Math.max(bounds.y + 6, pointer.y - size.height - 16);
            }

            owner = nextOwner;
            text = nextText;
            tooltipWindow = window;
            window.setLocation(popupX, popupY);
            window.setVisible(true);
        }

        private void hide() {
            if (tooltipWindow != null) {
                tooltipWindow.setVisible(false);
                tooltipWindow.dispose();
            }
            tooltipWindow = null;
            owner = null;
            text = null;
        }
    }

    private static boolean supportsPerPixelTransparency(GraphicsConfiguration configuration) {
        if (GraphicsEnvironment.isHeadless()) return false;
        GraphicsDevice device = configuration == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                : configuration.getDevice();
        return device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
    }

    private static void configureWindowBackground(JWindow window, boolean transparentHost) {
        Color background = transparentHost ? TRANSPARENT : TOOLTIP_BG;
        window.setBackground(background);
        window.getRootPane().setOpaque(!transparentHost);
        window.getRootPane().setBackground(background);
        window.getLayeredPane().setOpaque(!transparentHost);
        window.getLayeredPane().setBackground(background);
        Container content = window.getContentPane();
        content.setBackground(background);
        if (content instanceof JComponent swingContent) swingContent.setOpaque(!transparentHost);
    }
}
