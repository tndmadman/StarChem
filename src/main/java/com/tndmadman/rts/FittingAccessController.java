package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the always-available fitting-studio shortcut and HUD launcher. */
final class FittingAccessController {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Field WORLD = field(GamePanel.class, "world");
    private static final Field NETWORK = field(GamePanel.class, "network");
    private static final Method FITTING_BOUNDS = method(GamePanel.class, "fittingButtonBounds");
    private static final Map<GamePanel,StudioButton> BUTTONS = new WeakHashMap<>();
    private static final ShipFitStudioWindow STUDIO = new ShipFitStudioWindow();
    private static Timer scanTimer;

    private FittingAccessController() { }

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED
                    || event.getKeyCode() != KeyEvent.VK_L
                    || event.isControlDown() || event.isAltDown() || event.isMetaDown()
                    || event.getSource() instanceof JTextComponent
                    || ShipFitStudioWindow.active()) return false;
            GamePanel panel = gamePanelFor(event.getSource());
            if (panel == null || !panel.isShowing() || foreignGlassVisible(panel)) return false;
            event.consume();
            open(panel);
            return true;
        });
        scanTimer = new Timer(350, event -> refreshLaunchers());
        scanTimer.setCoalesce(true);
        scanTimer.start();
    }

    private static void refreshLaunchers() {
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            installLaunchers(window);
        }
        BUTTONS.entrySet().removeIf(entry -> {
            GamePanel panel = entry.getKey();
            StudioButton button = entry.getValue();
            boolean stale = panel == null || !panel.isDisplayable() || panel.getParent() == null;
            if (stale && button != null && button.getParent() != null) button.getParent().remove(button);
            return stale;
        });
    }

    private static void installLaunchers(Component component) {
        if (component instanceof GamePanel panel) ensureLauncher(panel);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) installLaunchers(child);
        }
    }

    private static void ensureLauncher(GamePanel panel) {
        if (!(panel.getParent() instanceof JLayeredPane layered)) return;
        StudioButton button = BUTTONS.get(panel);
        if (button == null || button.getParent() != layered) {
            if (button != null && button.getParent() != null) button.getParent().remove(button);
            button = new StudioButton();
            button.addActionListener(event -> open(panel));
            layered.add(button, Integer.valueOf(50));
            BUTTONS.put(panel, button);
        }
        Rectangle bounds = fittingBounds(panel);
        Point position = SwingUtilities.convertPoint(panel, bounds.x, bounds.y, layered);
        button.setBounds(position.x, position.y, bounds.width, bounds.height);
        button.setVisible(panel.isShowing() && !ShipFitStudioWindow.active());
        layered.repaint(button.getBounds());
    }

    private static void open(GamePanel panel) {
        if (panel == null || !panel.isShowing()) return;
        try {
            World world = (World)WORLD.get(panel);
            PeerNetwork network = (PeerNetwork)NETWORK.get(panel);
            if (world == null) return;
            ShipFittingWindow.closeActive();
            STUDIO.show(panel, world, network, selectedContext(world));
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not open the fitting studio.", ex);
        }
    }

    private static Unit selectedContext(World world) {
        if (world == null) return null;
        List<Unit> local = world.selectedUnits().stream()
                .filter(unit -> unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId))
                .toList();
        return local.size() == 1 ? local.get(0) : null;
    }

    private static GamePanel gamePanelFor(Object source) {
        if (source instanceof GamePanel panel) return panel;
        if (source instanceof Component component) {
            Component current = component;
            while (current != null) {
                if (current instanceof GamePanel panel) return panel;
                current = current.getParent();
            }
        }
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        return findGamePanel(active);
    }

    private static GamePanel findGamePanel(Component component) {
        if (component == null) return null;
        if (component instanceof GamePanel panel) return panel;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                GamePanel found = findGamePanel(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean foreignGlassVisible(GamePanel panel) {
        JRootPane root = SwingUtilities.getRootPane(panel);
        return root != null && root.getGlassPane() != null && root.getGlassPane().isVisible()
                && !ShipFitStudioWindow.active();
    }

    private static Rectangle fittingBounds(GamePanel panel) {
        try {
            Object value = FITTING_BOUNDS.invoke(panel);
            return value instanceof Rectangle rectangle ? rectangle : new Rectangle(958, 106, 156, 28);
        } catch (ReflectiveOperationException ex) {
            return new Rectangle(958, 106, 156, 28);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final class StudioButton extends JButton {
        StudioButton() {
            super("FIT STUDIO [L]");
            setFocusable(false);
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(Font.BOLD, 10.5f));
            setBorder(BorderFactory.createLineBorder(new Color(105, 221, 255), 1));
            setOpaque(false);
            setContentAreaFilled(false);
            setToolTipText("Open the fitting studio. A selected ship is optional.");
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color top = getModel().isRollover() || getModel().isPressed()
                    ? new Color(71, 56, 132) : new Color(18, 79, 112);
            Color bottom = getModel().isRollover() || getModel().isPressed()
                    ? new Color(22, 99, 128) : new Color(22, 35, 67);
            g.setPaint(new GradientPaint(0, 0, top, getWidth(), getHeight(), bottom));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setColor(new Color(196, 111, 255, 170));
            g.drawLine(7, getHeight() - 3, getWidth() - 8, getHeight() - 3);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
