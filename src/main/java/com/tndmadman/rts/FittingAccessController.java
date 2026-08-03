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
import java.awt.FontMetrics;
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
import java.util.Locale;
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
            if (event.getID() != KeyEvent.KEY_PRESSED) return false;
            if (ShipFitStudioWindow.active()) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    STUDIO.close();
                    event.consume();
                    return true;
                }
                if (!(event.getSource() instanceof JTextComponent) && blockedGameHotkey(event.getKeyCode())) {
                    event.consume();
                    return true;
                }
                return false;
            }
            if (event.getKeyCode() != KeyEvent.VK_L
                    || event.isControlDown() || event.isAltDown() || event.isMetaDown()
                    || event.getSource() instanceof JTextComponent) return false;
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

    private static boolean blockedGameHotkey(int keyCode) {
        return keyCode == KeyEvent.VK_I || keyCode == KeyEvent.VK_L
                || keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12;
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
        World world = world(panel);
        button.updateSelection(selectedContext(world));
        Rectangle bounds = fittingBounds(panel);
        Point position = SwingUtilities.convertPoint(panel, bounds.x, bounds.y, layered);
        button.setBounds(position.x, position.y, bounds.width, bounds.height);
        button.setVisible(panel.isShowing() && !ShipFitStudioWindow.active());
        layered.repaint(button.getBounds());
    }

    private static void open(GamePanel panel) {
        if (panel == null || !panel.isShowing()) return;
        World world = world(panel);
        if (world == null) return;
        try {
            PeerNetwork network = (PeerNetwork)NETWORK.get(panel);
            ShipFittingWindow.closeActive();
            STUDIO.show(panel, world, network, selectedContext(world));
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Could not open the fitting studio.", ex);
        }
    }

    private static World world(GamePanel panel) {
        if (panel == null) return null;
        try {
            return (World)WORLD.get(panel);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static Unit selectedContext(World world) {
        if (world == null) return null;
        List<Unit> local = world.selectedUnits().stream()
                .filter(unit -> unit.hp > 0 && PlayerRegistry.isLocal(unit.playerId))
                .toList();
        return local.size() == 1 ? local.get(0) : null;
    }

    private static String currentFitName(Unit unit) {
        if (unit == null) return "";
        ShipLoadoutDefinition definition = WeaponRules.findLoadout(unit.loadoutId);
        if (definition != null && unit.shipTypeId.equals(definition.hullId())
                && definition.displayName() != null && !definition.displayName().isBlank()) {
            return definition.displayName();
        }
        String id = unit.loadoutId == null ? "" : unit.loadoutId.trim();
        if (id.isBlank()) {
            ShipLoadoutDefinition fallback = WeaponRules.defaultLoadout(unit.shipTypeId);
            return fallback == null ? "Default Fit" : fallback.displayName();
        }
        if (id.startsWith("custom_")) return "Custom Fit";
        return readableId(id);
    }

    private static String readableId(String id) {
        String[] words = id.split("[_-]");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? "Unknown Fit" : out.toString();
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
        private String currentFit = "";
        private String selectedShip = "";

        StudioButton() {
            setFocusable(false);
            setForeground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(105, 221, 255), 1));
            setOpaque(false);
            setContentAreaFilled(false);
            updateSelection(null);
        }

        void updateSelection(Unit unit) {
            String nextFit = currentFitName(unit);
            String nextShip = unit == null ? "" : unit.type().name + " #" + unit.unitId;
            if (nextFit.equals(currentFit) && nextShip.equals(selectedShip)) return;
            currentFit = nextFit;
            selectedShip = nextShip;
            if (unit == null) {
                setToolTipText("Open the fitting studio. Select one friendly ship to show its current fit here.");
                getAccessibleContext().setAccessibleName("Fit Studio");
            } else {
                setToolTipText(selectedShip + " — Current fit: " + currentFit);
                getAccessibleContext().setAccessibleName("Fit Studio. " + selectedShip + " current fit " + currentFit);
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color top = getModel().isRollover() || getModel().isPressed()
                    ? new Color(71, 56, 132) : new Color(18, 79, 112);
            Color bottom = getModel().isRollover() || getModel().isPressed()
                    ? new Color(22, 99, 128) : new Color(22, 35, 67);
            g.setPaint(new GradientPaint(0, 0, top, getWidth(), getHeight(), bottom));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setColor(new Color(196, 111, 255, 170));
            g.drawLine(7, getHeight() - 3, getWidth() - 8, getHeight() - 3);

            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 9.2f));
            drawCentered(g, "FIT STUDIO [L]", 10);
            g.setColor(currentFit.isBlank() ? new Color(163, 197, 215) : new Color(137, 232, 185));
            g.setFont(getFont().deriveFont(Font.BOLD, 7.8f));
            String detail = currentFit.isBlank() ? "NO SHIP LINKED" : "FIT // " + currentFit.toUpperCase(Locale.ROOT);
            drawCentered(g, detail, 21);
            g.dispose();
        }

        private void drawCentered(Graphics2D g, String text, int baseline) {
            FontMetrics metrics = g.getFontMetrics();
            String shown = clipped(metrics, text, Math.max(12, getWidth() - 12));
            int x = Math.max(5, (getWidth() - metrics.stringWidth(shown)) / 2);
            g.drawString(shown, x, baseline);
        }

        private static String clipped(FontMetrics metrics, String text, int available) {
            if (metrics.stringWidth(text) <= available) return text;
            String ellipsis = "…";
            int length = text.length();
            while (length > 1 && metrics.stringWidth(text.substring(0, length) + ellipsis) > available) length--;
            return text.substring(0, Math.max(1, length)) + ellipsis;
        }
    }
}
