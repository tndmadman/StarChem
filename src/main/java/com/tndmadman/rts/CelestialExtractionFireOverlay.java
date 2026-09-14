package com.tndmadman.rts;

import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Dedicated player-facing FIRE FRACTURE CHARGE control for the celestial intel HUD. */
final class CelestialExtractionFireOverlay {
    private static final int WIDTH = 408;
    private static final int HEIGHT = 42;
    private static final int INTEL_TOP = 156;
    private static final int MARGIN = 18;

    private static final Color PANEL = new Color(7, 13, 20, 246);
    private static final Color TEXT = new Color(234, 246, 252);
    private static final Color MUTED = new Color(145, 171, 188);
    private static final Color CYAN = new Color(112, 214, 255);
    private static final Color WARN = new Color(255, 193, 91);

    private static final Map<World, FireButton> BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean listenerInstalled;
    private static Field gamePanelWorldField;

    private CelestialExtractionFireOverlay() { }

    static void ensureInstalled(World world) {
        if (world == null || GraphicsEnvironment.isHeadless() || listenerInstalled) return;
        synchronized (CelestialExtractionFireOverlay.class) {
            if (listenerInstalled) return;
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    CelestialExtractionFireOverlay::handleEvent,
                    AWTEvent.MOUSE_EVENT_MASK);
            listenerInstalled = true;
        }
    }

    static void refresh(World world) {
        if (world == null || GraphicsEnvironment.isHeadless()) return;
        FireButton button = BUTTONS.get(world);
        if (button == null || !button.isDisplayable()) return;
        GamePanel host = button.host();
        if (host == null || !host.isDisplayable()) {
            detach(world, button);
            return;
        }

        WorldSystemState state = activeState(world);
        String bodyId = CelestialGameplayOverlay.selectedBodyId(world);
        CelestialBodyState body = state == null ? null : CelestialGameplaySystem.bodyState(state, bodyId);
        if (state == null || body == null || bodyId.isBlank()
                || CelestialGameplaySystem.intel(state, bodyId, localPlayerId(world)).ordinal()
                < CelestialIntelLevel.SCANNED.ordinal()
                || CelestialExtractionSystem.released(state, bodyId)) {
            button.setVisible(false);
            return;
        }

        boolean inFlight = CelestialExtractionSystem.chargeInFlight(state, bodyId);
        boolean ready = CelestialExtractionSystem.extractorReady(state, bodyId, localPlayerId(world));
        double progress = CelestialExtractionSystem.chargeProgress(state, bodyId);
        button.setModel(body.profile.bodyName(), inFlight, ready, progress);
        button.setVisible(true);
        position(host, button);
        button.repaint();
    }

    private static void handleEvent(AWTEvent raw) {
        if (!(raw instanceof MouseEvent event)) return;

        if (event.getSource() instanceof GamePanel panel) {
            if (event.getID() != MouseEvent.MOUSE_PRESSED && event.getID() != MouseEvent.MOUSE_CLICKED) return;
            World world = worldFrom(panel);
            if (world == null) return;
            SwingUtilities.invokeLater(() -> bindAndRefresh(world, panel));
            return;
        }

        // Keep this companion control synchronized with the intel panel's close affordance without
        // changing the existing large HUD class merely to expose its private close rectangle.
        if (event.getID() == MouseEvent.MOUSE_PRESSED
                && event.getSource() instanceof javax.swing.JComponent component
                && component.getClass().getName().endsWith("CelestialGameplayOverlay$IntelPanel")
                && event.getY() <= 58 && event.getX() >= component.getWidth() - 60) {
            for (FireButton button : BUTTONS.values()) {
                if (button != null) button.setVisible(false);
            }
        }
    }

    private static void bindAndRefresh(World world, GamePanel host) {
        if (world == null || host == null || !host.isDisplayable()) return;
        FireButton button = BUTTONS.get(world);
        if (button == null || button.host() != host || !button.isDisplayable()) {
            if (button != null) detach(world, button);
            JRootPane root = SwingUtilities.getRootPane(host);
            if (root == null) return;
            button = new FireButton(world, host);
            root.getLayeredPane().add(button, JLayeredPane.POPUP_LAYER);
            BUTTONS.put(world, button);
        }
        refresh(world);
    }

    private static void position(GamePanel host, FireButton button) {
        JRootPane root = SwingUtilities.getRootPane(host);
        if (root == null) return;
        JLayeredPane layered = root.getLayeredPane();
        int width = Math.min(WIDTH, Math.max(320, host.getWidth() - MARGIN * 2));
        int localX = Math.max(MARGIN, host.getWidth() - width - MARGIN);
        int intelY = Math.min(INTEL_TOP, Math.max(MARGIN, host.getHeight() / 5));
        int localY = Math.max(MARGIN, intelY - HEIGHT - 7);
        Point p = SwingUtilities.convertPoint(host, localX, localY, layered);
        button.setBounds(p.x, p.y, width, HEIGHT);
    }

    private static void detach(World world, FireButton button) {
        BUTTONS.remove(world);
        if (button != null && button.getParent() != null) button.getParent().remove(button);
    }

    private static WorldSystemState activeState(World world) {
        if (world == null) return null;
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id.equals(active)) return state;
        }
        return null;
    }

    private static String localPlayerId(World world) {
        String id = PlayerRegistry.localId();
        return id == null || id.isBlank() ? world.localPlayerId : id;
    }

    private static World worldFrom(GamePanel panel) {
        try {
            Field field = gamePanelWorldField;
            if (field == null) {
                field = GamePanel.class.getDeclaredField("world");
                field.setAccessible(true);
                gamePanelWorldField = field;
            }
            Object value = field.get(panel);
            return value instanceof World world ? world : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static final class FireButton extends javax.swing.JComponent {
        private final WeakReference<World> worldRef;
        private final WeakReference<GamePanel> hostRef;
        private String bodyName = "";
        private boolean inFlight;
        private boolean ready;
        private double progress;
        private boolean hover;

        FireButton(World world, GamePanel host) {
            worldRef = new WeakReference<>(world);
            hostRef = new WeakReference<>(host);
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }

                @Override public void mousePressed(MouseEvent event) {
                    fire();
                    event.consume();
                }
            });
        }

        GamePanel host() { return hostRef.get(); }

        void setModel(String nextBodyName, boolean nextInFlight, boolean nextReady, double nextProgress) {
            bodyName = nextBodyName == null ? "" : nextBodyName;
            inFlight = nextInFlight;
            ready = nextReady;
            progress = Math.max(0.0, Math.min(1.0, nextProgress));
            setCursor(!inFlight && ready
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
        }

        private void fire() {
            if (inFlight || !ready) return;
            World world = worldRef.get();
            GamePanel host = hostRef.get();
            if (world == null || host == null) return;
            WorldSystemState state = activeState(world);
            String bodyId = CelestialGameplayOverlay.selectedBodyId(world);
            if (state == null || bodyId.isBlank()) return;
            CelestialExtractionSystem.FireResult result = CelestialExtractionSystem.fireChargeFromButton(
                    state, bodyId, localPlayerId(world));
            world.status = result.message();
            refresh(world);
            host.repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color accent = inFlight ? WARN : ready ? CYAN : MUTED;

            g.setColor(PANEL);
            g.fillRoundRect(0, 0, w - 1, h - 1, 11, 11);
            g.setStroke(new BasicStroke(hover && ready && !inFlight ? 1.8f : 1.2f));
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                    hover && ready && !inFlight ? 230 : 150));
            g.drawRoundRect(0, 0, w - 1, h - 1, 11, 11);

            int iconX = 18;
            int cy = h / 2;
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45));
            g.fillOval(iconX - 8, cy - 8, 16, 16);
            g.setColor(accent);
            g.drawOval(iconX - 8, cy - 8, 16, 16);
            g.fillOval(iconX - 2, cy - 2, 4, 4);

            String heading;
            String detail;
            if (inFlight) {
                heading = "FRACTURE CHARGE IN FLIGHT  " + Math.round(progress * 100) + "%";
                detail = "Target: " + bodyName;
            } else if (ready) {
                heading = "FIRE FRACTURE CHARGE";
                detail = "Expose a fresh extraction field on " + bodyName;
            } else {
                heading = "FIRE FRACTURE CHARGE";
                detail = "Planetary Extractor required on the master planet";
            }

            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            g.setColor(ready || inFlight ? TEXT : MUTED);
            g.drawString(heading, 37, 17);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
            g.setColor(MUTED);
            g.drawString(detail, 37, 31);

            if (inFlight) {
                int barX = w - 92;
                int barW = 72;
                g.setColor(new Color(255, 255, 255, 18));
                g.fillRoundRect(barX, h - 8, barW, 3, 3, 3);
                g.setColor(WARN);
                g.fillRoundRect(barX, h - 8, (int)Math.round(barW * progress), 3, 3, 3);
            } else if (ready) {
                g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
                g.setColor(CYAN);
                String action = "FIRE  ›";
                int tw = g.getFontMetrics().stringWidth(action);
                g.drawString(action, w - tw - 16, 25);
            }
            g.dispose();
        }
    }
}
