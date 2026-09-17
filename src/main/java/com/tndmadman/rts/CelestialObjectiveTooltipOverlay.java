package com.tndmadman.rts;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Game-styled hover help for the SCAN / CLAIM / HOLD / EXTRACT rows in the celestial HUD.
 *
 * <p>The main intel panel intentionally remains a custom painted game HUD rather than Swing form
 * controls. This companion listener therefore paints its own tooltip card in the same layered pane
 * instead of falling back to the platform/default Swing tooltip.</p>
 */
final class CelestialObjectiveTooltipOverlay {
    private static final int HEADER_HEIGHT = 58;
    private static final int TOOLTIP_WIDTH = 332;
    private static final int TOOLTIP_HEIGHT = 82;
    private static final Color PANEL = new Color(8, 15, 23, 248);
    private static final Color BORDER = new Color(79, 172, 214, 220);
    private static final Color CYAN = new Color(112, 214, 255);
    private static final Color TEXT = new Color(232, 243, 250);
    private static final Color MUTED = new Color(158, 184, 199);

    private static volatile boolean installed;
    private static TooltipCard card;
    private static Field worldRefField;
    private static Field scrollOffsetField;

    private CelestialObjectiveTooltipOverlay() { }

    static void ensureInstalled() {
        if (GraphicsEnvironment.isHeadless() || installed) return;
        synchronized (CelestialObjectiveTooltipOverlay.class) {
            if (installed) return;
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    CelestialObjectiveTooltipOverlay::handleEvent,
                    AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
            installed = true;
        }
    }

    private static void handleEvent(AWTEvent raw) {
        if (!(raw instanceof MouseEvent event)) return;
        if (event.getID() == MouseEvent.MOUSE_EXITED || event.getID() == MouseEvent.MOUSE_PRESSED) {
            hide();
            return;
        }
        if (event.getID() != MouseEvent.MOUSE_MOVED) return;
        if (!(event.getSource() instanceof JComponent source)
                || !source.getClass().getName().endsWith("CelestialGameplayOverlay$IntelPanel")) {
            hide();
            return;
        }

        World world = worldFromPanel(source);
        if (world == null) {
            hide();
            return;
        }
        WorldSystemState state = activeState(world);
        String bodyId = CelestialGameplayOverlay.selectedBodyId(world);
        CelestialBodyState bodyState = CelestialGameplaySystem.bodyState(state, bodyId);
        if (state == null || bodyState == null) {
            hide();
            return;
        }

        int objectiveIndex = objectiveIndexAt(source, state, bodyId, event.getY());
        CelestialObjectiveType[] objectives = CelestialObjectiveType.values();
        if (objectiveIndex < 0 || objectiveIndex >= objectives.length) {
            hide();
            return;
        }

        CelestialObjectiveType objective = objectives[objectiveIndex];
        String text = CelestialObjectiveHelp.requirement(state, bodyId, objective);
        show(source, event.getPoint(), objective.name() + " REQUIREMENTS", text);
    }

    private static int objectiveIndexAt(JComponent panel, WorldSystemState state, String bodyId, int mouseY) {
        CelestialBodyState body = CelestialGameplaySystem.bodyState(state, bodyId);
        if (body == null) return -1;
        String playerId = PlayerRegistry.localId();
        if (playerId == null || playerId.isBlank()) playerId = "SOLO";
        CelestialIntelLevel intel = CelestialGameplaySystem.intel(state, bodyId, playerId);

        int y = 14;
        y += 128; // body card
        y += 10;
        y += 19 + 42; // installation section title + slots
        y += 10;
        y += 19 + (intel == CelestialIntelLevel.VISIBLE ? 52 : 76); // survey
        y += 10;
        boolean fullBonuses = intel == CelestialIntelLevel.ANALYZED && !body.profile.bonuses().isEmpty();
        y += 19 + (fullBonuses ? 46 : 42); // strategic bonuses
        y += 10;
        y += 19; // objectives section title
        int firstRowY = HEADER_HEIGHT + y + 10 - scrollOffset(panel);

        for (int i = 0; i < CelestialObjectiveType.values().length; i++) {
            int top = firstRowY + i * 25 - 5;
            if (mouseY >= top && mouseY < top + 24) return i;
        }
        return -1;
    }

    private static World worldFromPanel(JComponent panel) {
        try {
            Field field = worldRefField;
            if (field == null || field.getDeclaringClass() != panel.getClass()) {
                field = panel.getClass().getDeclaredField("worldRef");
                field.setAccessible(true);
                worldRefField = field;
            }
            Object value = field.get(panel);
            if (value instanceof WeakReference<?> reference && reference.get() instanceof World world) return world;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The tooltip is optional presentation; a layout refactor must never break gameplay.
        }
        return null;
    }

    private static int scrollOffset(JComponent panel) {
        try {
            Field field = scrollOffsetField;
            if (field == null || field.getDeclaringClass() != panel.getClass()) {
                field = panel.getClass().getDeclaredField("scrollOffset");
                field.setAccessible(true);
                scrollOffsetField = field;
            }
            return Math.max(0, field.getInt(panel));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static WorldSystemState activeState(World world) {
        if (world == null) return null;
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id.equals(active)) return state;
        }
        return null;
    }

    private static void show(JComponent source, Point mouse, String title, String text) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            hide();
            return;
        }
        JLayeredPane layered = root.getLayeredPane();
        if (layered == null) {
            hide();
            return;
        }

        TooltipCard next = card;
        if (next == null || next.getParent() != layered) {
            hide();
            next = new TooltipCard();
            layered.add(next, JLayeredPane.DRAG_LAYER);
            card = next;
        }
        next.setContent(title, text);

        Point p = SwingUtilities.convertPoint(source, mouse.x + 14, mouse.y + 16, layered);
        int x = p.x;
        int y = p.y;
        if (x + TOOLTIP_WIDTH > layered.getWidth() - 8) x = p.x - TOOLTIP_WIDTH - 26;
        if (x < 8) x = 8;
        if (y + TOOLTIP_HEIGHT > layered.getHeight() - 8) y = layered.getHeight() - TOOLTIP_HEIGHT - 8;
        if (y < 8) y = 8;
        next.setBounds(x, y, TOOLTIP_WIDTH, TOOLTIP_HEIGHT);
        next.setVisible(true);
        next.repaint();
    }

    private static void hide() {
        TooltipCard current = card;
        if (current == null) return;
        if (current.getParent() != null) {
            current.getParent().remove(current);
            current.getParent().repaint();
        }
        card = null;
    }

    private static final class TooltipCard extends JComponent {
        private String title = "";
        private String text = "";

        TooltipCard() {
            setOpaque(false);
            setFocusable(false);
        }

        void setContent(String title, String text) {
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            g.setColor(PANEL);
            g.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g.setStroke(new BasicStroke(1.1f));
            g.setColor(BORDER);
            g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g.setColor(CYAN);
            g.fillRoundRect(1, 1, 4, h - 2, 10, 10);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(CYAN);
            g.drawString(title.toUpperCase(Locale.ROOT), 14, 19);

            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(TEXT);
            List<String> lines = wrap(g.getFontMetrics(), text, w - 28, 3);
            int baseline = 39;
            for (String line : lines) {
                g.drawString(line, 14, baseline);
                baseline += 14;
            }
            if (lines.isEmpty()) {
                g.setColor(MUTED);
                g.drawString("No additional requirement data.", 14, 39);
            }
            g.dispose();
        }

        private static List<String> wrap(FontMetrics fm, String text, int width, int maxLines) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isBlank() || width <= 0 || maxLines <= 0) return out;
            String[] words = text.trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(candidate) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) out.add(line.toString());
                line.setLength(0);
                line.append(word);
                if (out.size() >= maxLines - 1) break;
            }
            if (out.size() < maxLines && !line.isEmpty()) out.add(line.toString());
            if (out.size() == maxLines) {
                String last = out.get(maxLines - 1);
                if (!text.endsWith(last)) {
                    while (!last.isEmpty() && fm.stringWidth(last + "…") > width) {
                        last = last.substring(0, last.length() - 1).stripTrailing();
                    }
                    out.set(maxLines - 1, last + "…");
                }
            }
            return out;
        }
    }
}
