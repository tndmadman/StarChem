package com.tndmadman.rts;

import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * In-game celestial intelligence HUD.
 *
 * <p>This deliberately avoids native Swing dialogs. Planet/moon intel is rendered as a game-styled
 * layered HUD card over the tactical view so inspecting a world never pulls the player out of the
 * game or presents raw debug text.</p>
 */
final class CelestialGameplayOverlay {
    private static final int PANEL_WIDTH = 408;
    private static final int HEADER_HEIGHT = 58;
    private static final int PANEL_TOP = 156;
    private static final int PANEL_MARGIN = 18;

    private static final Color PANEL_TOP_COLOR = new Color(14, 23, 34, 248);
    private static final Color PANEL_BOTTOM_COLOR = new Color(7, 12, 19, 248);
    private static final Color CARD = new Color(21, 32, 45, 235);
    private static final Color CARD_ALT = new Color(17, 27, 39, 235);
    private static final Color BORDER = new Color(75, 148, 187, 195);
    private static final Color CYAN = new Color(112, 214, 255);
    private static final Color TEXT = new Color(232, 243, 250);
    private static final Color MUTED = new Color(151, 177, 193);
    private static final Color GOOD = new Color(112, 224, 158);
    private static final Color WARN = new Color(255, 193, 91);
    private static final Color BAD = new Color(255, 111, 104);

    private static final Map<World, String> SELECTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, IntelPanel> PANELS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean listenerInstalled;
    private static Field gamePanelWorldField;

    private CelestialGameplayOverlay() { }

    static void ensureInstalled(World world) {
        if (world == null || GraphicsEnvironment.isHeadless() || listenerInstalled) return;
        synchronized (CelestialGameplayOverlay.class) {
            if (listenerInstalled) return;
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    CelestialGameplayOverlay::handleEvent,
                    AWTEvent.MOUSE_EVENT_MASK);
            listenerInstalled = true;
        }
    }

    static String selectedBodyId(World world) {
        return world == null ? "" : SELECTED.getOrDefault(world, "");
    }

    static void refresh(World world) {
        if (world == null || GraphicsEnvironment.isHeadless()) return;
        IntelPanel panel = PANELS.get(world);
        if (panel == null || !panel.isDisplayable()) return;
        GamePanel host = panel.host();
        if (host == null || !host.isDisplayable()) {
            detach(world, panel);
            return;
        }
        WorldSystemState system = activeState(world);
        String bodyId = selectedBodyId(world);
        IntelView view = buildView(system, bodyId, localPlayerId(world));
        if (view == null) {
            panel.setVisible(false);
            return;
        }
        panel.setView(view);
        position(host, panel);
    }

    private static void handleEvent(AWTEvent raw) {
        if (!(raw instanceof MouseEvent event)
                || event.getID() != MouseEvent.MOUSE_CLICKED
                || event.getButton() != MouseEvent.BUTTON1
                || event.getClickCount() != 1
                || !(event.getSource() instanceof GamePanel panel)) return;
        World world = worldFrom(panel);
        if (world == null) return;
        GameCamera camera = GameCamera.forWorld(world);
        WorldSystemState system = activeState(world);
        if (camera == null || system == null || system.celestials == null) return;
        Point2D point = camera.screenToWorld(event.getPoint());
        CelestialSystem.BodyView body = system.celestials.bodyAt(point.getX(), point.getY(), 28.0);
        if (body == null || body.visualClass() == CelestialVisualClass.STAR) return;

        SELECTED.put(world, body.id());
        String playerId = localPlayerId(world);
        world.status = oneLine(system, body.id(), playerId);
        SwingUtilities.invokeLater(() -> show(world, panel));
    }

    private static void show(World world, GamePanel host) {
        if (world == null || host == null || !host.isDisplayable()) return;
        IntelPanel panel = PANELS.get(world);
        if (panel == null || panel.host() != host || !panel.isDisplayable()) {
            if (panel != null) detach(world, panel);
            JRootPane root = SwingUtilities.getRootPane(host);
            if (root == null) return;
            panel = new IntelPanel(world, host);
            root.getLayeredPane().add(panel, JLayeredPane.POPUP_LAYER);
            PANELS.put(world, panel);
        }
        panel.setVisible(true);
        refresh(world);
        panel.repaint();
    }

    private static void position(GamePanel host, IntelPanel panel) {
        JRootPane root = SwingUtilities.getRootPane(host);
        if (root == null) return;
        JLayeredPane layered = root.getLayeredPane();
        int width = Math.min(PANEL_WIDTH, Math.max(320, host.getWidth() - PANEL_MARGIN * 2));
        int localX = Math.max(PANEL_MARGIN, host.getWidth() - width - PANEL_MARGIN);
        int localY = Math.min(PANEL_TOP, Math.max(PANEL_MARGIN, host.getHeight() / 5));
        int height = Math.min(624, Math.max(350, host.getHeight() - localY - PANEL_MARGIN));
        Point p = SwingUtilities.convertPoint(host, localX, localY, layered);
        panel.setBounds(p.x, p.y, width, height);
        panel.revalidate();
    }

    private static void detach(World world, IntelPanel panel) {
        PANELS.remove(world);
        if (panel == null) return;
        if (panel.getParent() != null) panel.getParent().remove(panel);
    }

    private static IntelView buildView(WorldSystemState system, String bodyId, String playerId) {
        if (system == null || bodyId == null || bodyId.isBlank() || system.celestials == null) return null;
        CelestialBodyState state = CelestialGameplaySystem.bodyState(system, bodyId);
        CelestialSystem.BodyView body = system.celestials.bodyView(bodyId);
        if (state == null || body == null) return null;

        CelestialIntelLevel intel = CelestialGameplaySystem.intel(system, bodyId, playerId);
        double scanSeconds = Math.max(0.0, state.scanSecondsByPlayer.getOrDefault(playerId, 0.0));
        String claimant = state.claimantId == null ? "" : state.claimantId;
        String controlText;
        Color controlColor;
        if (state.contested) {
            controlText = "CONTESTED";
            controlColor = BAD;
        } else if (claimant.isBlank()) {
            controlText = "UNCLAIMED";
            controlColor = MUTED;
        } else {
            controlText = PlayerRegistry.name(claimant);
            Color playerColor = PlayerRegistry.color(claimant);
            controlColor = playerColor == null ? CYAN : playerColor;
        }

        List<String> installations = new ArrayList<>();
        for (CelestialInstallationType type : state.installations) installations.add(title(type.name()));
        List<String> deposits = new ArrayList<>();
        for (Material material : state.profile.deposits()) deposits.add(material.label);
        List<String> traits = List.copyOf(state.profile.traits());
        List<String> hazards = List.copyOf(state.profile.hazards());
        List<BonusView> bonuses = new ArrayList<>();
        state.profile.bonuses().forEach((kind, value) ->
                bonuses.add(new BonusView(title(kind.name()), "+" + Math.round(value * 100) + "%")));

        List<ObjectiveView> objectives = new ArrayList<>();
        for (CelestialObjectiveType objective : CelestialObjectiveType.values()) {
            CelestialObjectiveStatus status = CelestialGameplaySystem.objectiveStatus(system, bodyId, playerId, objective);
            double progress = status.progress();
            double target = Math.max(0.0001, status.target());
            String detail;
            if (objective == CelestialObjectiveType.SCAN && !status.complete()) {
                progress = Math.min(scanSeconds, CelestialGameplaySystem.SCAN_SECONDS);
                target = CelestialGameplaySystem.SCAN_SECONDS;
                detail = oneDecimal(progress) + " / " + oneDecimal(target) + " sec";
            } else if (status.complete()) {
                detail = "Complete";
            } else if (objective == CelestialObjectiveType.HOLD) {
                detail = Math.round(progress) + " / " + Math.round(target) + " sec";
            } else if (objective == CelestialObjectiveType.EXTRACT) {
                detail = Math.round(progress) + " / " + Math.round(target);
            } else {
                detail = "Pending";
            }
            objectives.add(new ObjectiveView(title(objective.name()), progress / target, detail, status.complete()));
        }

        return new IntelView(
                state.profile.bodyName(),
                body.moon() ? "MOON" : "PLANET",
                state.profile.bodyType(),
                Math.round(body.radius()),
                intel,
                scanSeconds,
                controlText,
                controlColor,
                state.contested,
                state.profile.installationSlots(),
                installations,
                deposits,
                traits,
                hazards,
                bonuses,
                objectives);
    }

    private static String oneLine(WorldSystemState system, String bodyId, String playerId) {
        CelestialBodyState state = CelestialGameplaySystem.bodyState(system, bodyId);
        if (state == null) return "Celestial body selected.";
        CelestialIntelLevel intel = CelestialGameplaySystem.intel(system, bodyId, playerId);
        String claim = state.contested ? "contested"
                : state.claimantId == null || state.claimantId.isBlank() ? "unclaimed"
                : "claimed by " + PlayerRegistry.name(state.claimantId);
        return "Selected " + state.profile.bodyName() + " | " + state.profile.bodyType()
                + " | " + claim + " | intel: " + intel.name().toLowerCase(Locale.ROOT);
    }

    private static String localPlayerId(World world) {
        String id = PlayerRegistry.localId();
        return id == null || id.isBlank() ? world.localPlayerId : id;
    }

    private static WorldSystemState activeState(World world) {
        if (world == null) return null;
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id.equals(active)) return state;
        }
        return null;
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

    private static String title(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] words = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Color bodyColor(String type) {
        return switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "TERRESTRIAL" -> new Color(92, 203, 159);
            case "DESERT" -> new Color(228, 178, 91);
            case "ICE" -> new Color(151, 220, 246);
            case "LAVA" -> new Color(255, 119, 67);
            case "GAS_GIANT" -> new Color(218, 170, 124);
            case "ICE_GIANT" -> new Color(104, 168, 235);
            case "TOXIC" -> new Color(172, 220, 91);
            case "INDUSTRIAL" -> new Color(147, 165, 181);
            case "DEAD" -> new Color(125, 130, 137);
            default -> new Color(158, 148, 139);
        };
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private record BonusView(String name, String value) { }
    private record ObjectiveView(String name, double fraction, String detail, boolean complete) { }
    private record IntelView(
            String name,
            String kind,
            String type,
            long radius,
            CelestialIntelLevel intel,
            double scanSeconds,
            String control,
            Color controlColor,
            boolean contested,
            int slots,
            List<String> installations,
            List<String> deposits,
            List<String> traits,
            List<String> hazards,
            List<BonusView> bonuses,
            List<ObjectiveView> objectives) { }

    private static final class IntelPanel extends javax.swing.JComponent {
        private final WeakReference<World> worldRef;
        private final WeakReference<GamePanel> hostRef;
        private IntelView view;
        private int scrollOffset;
        private int maxScroll;
        private final Rectangle closeBounds = new Rectangle();

        IntelPanel(World world, GamePanel host) {
            worldRef = new WeakReference<>(world);
            hostRef = new WeakReference<>(host);
            setOpaque(false);
            setFocusable(false);
            setPreferredSize(new Dimension(PANEL_WIDTH, 600));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (closeBounds.contains(event.getPoint())) {
                        setVisible(false);
                        event.consume();
                    }
                }
            });
            addMouseWheelListener(this::scroll);
        }

        GamePanel host() { return hostRef.get(); }

        void setView(IntelView next) {
            boolean changedBody = view == null || next == null || !view.name().equals(next.name());
            view = next;
            if (changedBody) scrollOffset = 0;
            repaint();
        }

        private void scroll(MouseWheelEvent event) {
            if (maxScroll <= 0) return;
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + event.getWheelRotation() * 34));
            repaint();
            event.consume();
        }

        @Override protected void paintComponent(Graphics graphics) {
            IntelView model = view;
            if (model == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            Color accent = bodyColor(model.type());
            g.setPaint(new GradientPaint(0, 0, PANEL_TOP_COLOR, 0, h, PANEL_BOTTOM_COLOR));
            g.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g.setColor(alpha(BORDER, 215));
            g.setStroke(new BasicStroke(1.2f));
            g.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g.setColor(alpha(accent, 220));
            g.fillRoundRect(1, 1, 5, h - 2, 14, 14);

            drawHeader(g, model, accent, w);

            Graphics2D content = (Graphics2D) g.create(0, HEADER_HEIGHT, w, Math.max(1, h - HEADER_HEIGHT));
            content.translate(0, -scrollOffset);
            int contentHeight = drawContent(content, model, accent, w);
            content.dispose();

            maxScroll = Math.max(0, contentHeight - Math.max(1, h - HEADER_HEIGHT));
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;
            drawScrollbar(g, w, h);
            g.dispose();
        }

        private void drawHeader(Graphics2D g, IntelView model, Color accent, int w) {
            g.setColor(new Color(5, 11, 18, 185));
            g.fillRoundRect(6, 4, w - 12, HEADER_HEIGHT - 3, 12, 12);
            g.setColor(alpha(accent, 48));
            g.fillRect(7, HEADER_HEIGHT - 3, w - 14, 2);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(CYAN);
            g.drawString("CELESTIAL INTELLIGENCE", 20, 20);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
            g.setColor(TEXT);
            drawEllipsis(g, model.name(), 20, 43, w - 88);

            closeBounds.setBounds(w - 42, 14, 24, 24);
            g.setColor(new Color(255, 255, 255, 18));
            g.fillOval(closeBounds.x, closeBounds.y, closeBounds.width, closeBounds.height);
            g.setColor(MUTED);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(closeBounds.x + 7, closeBounds.y + 7, closeBounds.x + 17, closeBounds.y + 17);
            g.drawLine(closeBounds.x + 17, closeBounds.y + 7, closeBounds.x + 7, closeBounds.y + 17);
        }

        private int drawContent(Graphics2D g, IntelView model, Color accent, int w) {
            int x = 18;
            int innerW = w - 36;
            int y = 14;

            y = drawBodyCard(g, model, accent, x, y, innerW);
            y += 10;
            y = drawInstallations(g, model, accent, x, y, innerW);
            y += 10;
            y = drawSurvey(g, model, accent, x, y, innerW);
            y += 10;
            y = drawBonuses(g, model, accent, x, y, innerW);
            y += 10;
            y = drawObjectives(g, model, accent, x, y, innerW);
            y += 10;
            y = drawFooter(g, model, x, y, innerW);
            return y + 18;
        }

        private int drawBodyCard(Graphics2D g, IntelView model, Color accent, int x, int y, int w) {
            int h = 128;
            card(g, x, y, w, h, CARD);
            drawPlanet(g, model, accent, x + 48, y + 46, 31);

            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            pill(g, model.kind(), x + 92, y + 14, accent);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
            g.setColor(TEXT);
            drawEllipsis(g, title(model.type()), x + 92, y + 45, w - 112);

            metric(g, "RADIUS", model.radius() + " u", x + 92, y + 62, 88);
            metric(g, "INTEL", model.intel().name(), x + 180, y + 62, 88);
            metric(g, "CONTROL", model.control(), x + 268, y + 62, Math.max(80, w - 278), model.controlColor());

            double overall = Math.min(1.0, model.scanSeconds() / CelestialGameplaySystem.ANALYZE_SECONDS);
            String phase;
            if (model.intel() == CelestialIntelLevel.ANALYZED) phase = "ANALYSIS COMPLETE";
            else if (model.scanSeconds() >= CelestialGameplaySystem.SCAN_SECONDS) {
                phase = "ANALYZING  " + oneDecimal(model.scanSeconds()) + " / "
                        + oneDecimal(CelestialGameplaySystem.ANALYZE_SECONDS) + "s";
            } else {
                phase = "SCANNING  " + oneDecimal(model.scanSeconds()) + " / "
                        + oneDecimal(CelestialGameplaySystem.SCAN_SECONDS) + "s";
            }
            g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
            g.setColor(MUTED);
            g.drawString(phase, x + 14, y + 101);
            progress(g, x + 14, y + 108, w - 28, 7, overall,
                    model.intel() == CelestialIntelLevel.ANALYZED ? GOOD : accent);
            double marker = CelestialGameplaySystem.SCAN_SECONDS / CelestialGameplaySystem.ANALYZE_SECONDS;
            int markerX = x + 14 + (int)Math.round((w - 28) * marker);
            g.setColor(new Color(220, 236, 244, 120));
            g.drawLine(markerX, y + 106, markerX, y + 117);
            return y + h;
        }

        private int drawInstallations(Graphics2D g, IntelView model, Color accent, int x, int y, int w) {
            sectionTitle(g, "ORBITAL INSTALLATIONS", model.installations().size() + " / " + model.slots(), x, y, w);
            y += 19;
            int slots = Math.max(1, model.slots());
            int gap = 7;
            int slotW = Math.max(52, (w - gap * (slots - 1)) / slots);
            int slotH = 42;
            for (int i = 0; i < slots; i++) {
                int sx = x + i * (slotW + gap);
                boolean filled = i < model.installations().size();
                g.setColor(filled ? alpha(accent, 42) : new Color(255, 255, 255, 9));
                g.fillRoundRect(sx, y, slotW, slotH, 8, 8);
                g.setColor(filled ? alpha(accent, 185) : new Color(122, 148, 163, 80));
                g.drawRoundRect(sx, y, slotW, slotH, 8, 8);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
                g.setColor(filled ? TEXT : MUTED);
                String label = filled ? compactInstallation(model.installations().get(i)) : "EMPTY";
                drawCenteredEllipsis(g, label, sx + 5, y + 25, slotW - 10);
            }
            return y + slotH;
        }

        private int drawSurvey(Graphics2D g, IntelView model, Color accent, int x, int y, int w) {
            sectionTitle(g, "SURVEY DATA", model.intel() == CelestialIntelLevel.VISIBLE ? "SCAN REQUIRED" : "VERIFIED", x, y, w);
            y += 19;
            if (model.intel() == CelestialIntelLevel.VISIBLE) {
                card(g, x, y, w, 52, CARD_ALT);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
                g.setColor(WARN);
                g.drawString("◈  Survey data encrypted", x + 14, y + 21);
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
                g.setColor(MUTED);
                g.drawString("Maintain a nearby ship or station to complete the scan.", x + 14, y + 39);
                return y + 52;
            }

            int gap = 8;
            int colW = (w - gap * 2) / 3;
            drawSurveyColumn(g, "DEPOSITS", model.deposits(), x, y, colW, accent);
            drawSurveyColumn(g, "TRAITS", model.traits(), x + colW + gap, y, colW, CYAN);
            drawSurveyColumn(g, "HAZARDS", model.hazards(), x + (colW + gap) * 2, y, colW,
                    model.hazards().isEmpty() ? GOOD : WARN);
            return y + 76;
        }

        private int drawBonuses(Graphics2D g, IntelView model, Color accent, int x, int y, int w) {
            sectionTitle(g, "STRATEGIC BONUSES", model.intel() == CelestialIntelLevel.ANALYZED ? "ANALYZED" : "FULL ANALYSIS REQUIRED", x, y, w);
            y += 19;
            if (model.intel() != CelestialIntelLevel.ANALYZED) {
                card(g, x, y, w, 42, CARD_ALT);
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
                g.setColor(MUTED);
                g.drawString("Exact modifiers unlock after 12 seconds of local analysis.", x + 14, y + 26);
                return y + 42;
            }
            if (model.bonuses().isEmpty()) {
                card(g, x, y, w, 42, CARD_ALT);
                g.setColor(MUTED);
                g.drawString("No strategic modifiers detected.", x + 14, y + 26);
                return y + 42;
            }
            int count = model.bonuses().size();
            int gap = 7;
            int chipW = Math.max(82, (w - gap * (count - 1)) / count);
            for (int i = 0; i < count; i++) {
                BonusView bonus = model.bonuses().get(i);
                int bx = x + i * (chipW + gap);
                g.setColor(alpha(accent, 38));
                g.fillRoundRect(bx, y, chipW, 46, 8, 8);
                g.setColor(alpha(accent, 150));
                g.drawRoundRect(bx, y, chipW, 46, 8, 8);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
                g.setColor(MUTED);
                drawCenteredEllipsis(g, bonus.name().toUpperCase(Locale.ROOT), bx + 5, y + 16, chipW - 10);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
                g.setColor(GOOD);
                drawCenteredEllipsis(g, bonus.value(), bx + 5, y + 36, chipW - 10);
            }
            return y + 46;
        }

        private int drawObjectives(Graphics2D g, IntelView model, Color accent, int x, int y, int w) {
            sectionTitle(g, "BODY OBJECTIVES", "4 TRACKED", x, y, w);
            y += 19;
            card(g, x, y, w, 116, CARD_ALT);
            int rowY = y + 10;
            for (ObjectiveView objective : model.objectives()) {
                g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
                g.setColor(objective.complete() ? GOOD : TEXT);
                String icon = objective.complete() ? "✓" : "•";
                g.drawString(icon + " " + objective.name().toUpperCase(Locale.ROOT), x + 12, rowY + 9);
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
                g.setColor(objective.complete() ? GOOD : MUTED);
                int detailW = g.getFontMetrics().stringWidth(objective.detail());
                g.drawString(objective.detail(), x + w - detailW - 12, rowY + 9);
                progress(g, x + 12, rowY + 14, w - 24, 4, objective.fraction(), objective.complete() ? GOOD : accent);
                rowY += 25;
            }
            return y + 116;
        }

        private int drawFooter(Graphics2D g, IntelView model, int x, int y, int w) {
            int h = 48;
            Color outline = model.contested() ? BAD : model.controlColor();
            g.setColor(new Color(5, 10, 16, 170));
            g.fillRoundRect(x, y, w, h, 10, 10);
            g.setColor(alpha(outline, 145));
            g.drawRoundRect(x, y, w, h, 10, 10);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(TEXT);
            g.drawString(model.contested() ? "CONTROL CONTESTED" : "CLAIM / DEVELOP", x + 13, y + 18);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
            g.setColor(MUTED);
            drawEllipsis(g, "Deploy a station package in this body's orbit to claim it and activate bonuses.",
                    x + 13, y + 35, w - 26);
            return y + h;
        }

        private void drawPlanet(Graphics2D g, IntelView model, Color accent, int cx, int cy, int radius) {
            Color dark = accent.darker().darker();
            RadialGradientPaint paint = new RadialGradientPaint(
                    new Point2D.Float(cx - radius * 0.28f, cy - radius * 0.30f),
                    radius * 1.45f,
                    new float[]{0f, 0.56f, 1f},
                    new Color[]{accent.brighter(), accent, dark});
            g.setPaint(paint);
            g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2.0, radius * 2.0));
            Shape oldClip = g.getClip();
            g.clip(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2.0, radius * 2.0));
            g.setColor(new Color(255, 255, 255, 24));
            for (int i = -2; i <= 2; i++) {
                int yy = cy + i * 9;
                g.fillOval(cx - radius - 8, yy - 3, radius * 2 + 16, 5);
            }
            g.setClip(oldClip);
            g.setColor(alpha(accent.brighter(), 175));
            g.setStroke(new BasicStroke(1.25f));
            g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            if ("GAS_GIANT".equals(model.type()) || "ICE_GIANT".equals(model.type())) {
                Composite old = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                g.setColor(accent.brighter());
                g.drawOval(cx - radius - 13, cy - 7, (radius + 13) * 2, 14);
                g.setComposite(old);
            }
        }

        private void metric(Graphics2D g, String label, String value, int x, int y, int width) {
            metric(g, label, value, x, y, width, TEXT);
        }

        private void metric(Graphics2D g, String label, String value, int x, int y, int width, Color valueColor) {
            g.setFont(g.getFont().deriveFont(Font.BOLD, 8f));
            g.setColor(MUTED);
            g.drawString(label, x, y + 7);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(valueColor == null ? TEXT : valueColor);
            drawEllipsis(g, value, x, y + 22, width);
        }

        private void pill(Graphics2D g, String text, int x, int y, Color accent) {
            FontMetrics fm = g.getFontMetrics();
            int width = fm.stringWidth(text) + 16;
            g.setColor(alpha(accent, 35));
            g.fillRoundRect(x, y, width, 18, 9, 9);
            g.setColor(alpha(accent, 165));
            g.drawRoundRect(x, y, width, 18, 9, 9);
            g.setColor(accent.brighter());
            g.drawString(text, x + 8, y + 13);
        }

        private void sectionTitle(Graphics2D g, String title, String right, int x, int y, int w) {
            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(CYAN);
            g.drawString(title, x, y + 10);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
            g.setColor(MUTED);
            int rw = g.getFontMetrics().stringWidth(right);
            g.drawString(right, x + w - rw, y + 10);
        }

        private void drawSurveyColumn(Graphics2D g, String title, List<String> values,
                                      int x, int y, int w, Color accent) {
            card(g, x, y, w, 76, CARD_ALT);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 8f));
            g.setColor(accent);
            g.drawString(title, x + 9, y + 15);
            List<String> safe = values == null ? List.of() : values;
            if (safe.isEmpty()) {
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
                g.setColor(MUTED);
                g.drawString("None detected", x + 9, y + 37);
                return;
            }
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
            g.setColor(TEXT);
            int lineY = y + 35;
            int shown = Math.min(2, safe.size());
            for (int i = 0; i < shown; i++) {
                drawEllipsis(g, "• " + safe.get(i), x + 9, lineY, w - 18);
                lineY += 15;
            }
            if (safe.size() > shown) {
                g.setColor(MUTED);
                g.drawString("+" + (safe.size() - shown) + " more", x + 9, y + 67);
            }
        }

        private void card(Graphics2D g, int x, int y, int w, int h, Color fill) {
            g.setColor(fill);
            g.fillRoundRect(x, y, w, h, 10, 10);
            g.setColor(new Color(90, 133, 157, 70));
            g.drawRoundRect(x, y, w, h, 10, 10);
        }

        private void progress(Graphics2D g, int x, int y, int w, int h, double fraction, Color fill) {
            double clamped = Math.max(0.0, Math.min(1.0, Double.isFinite(fraction) ? fraction : 0.0));
            g.setColor(new Color(255, 255, 255, 14));
            g.fillRoundRect(x, y, w, h, h, h);
            int fw = (int)Math.round(w * clamped);
            if (fw > 0) {
                g.setColor(fill == null ? CYAN : fill);
                g.fillRoundRect(x, y, fw, h, h, h);
            }
        }

        private void drawScrollbar(Graphics2D g, int w, int h) {
            if (maxScroll <= 0) return;
            int trackY = HEADER_HEIGHT + 8;
            int trackH = Math.max(24, h - HEADER_HEIGHT - 16);
            double visible = Math.max(1, h - HEADER_HEIGHT);
            double total = visible + maxScroll;
            int thumbH = Math.max(28, (int)Math.round(trackH * visible / total));
            int travel = Math.max(1, trackH - thumbH);
            int thumbY = trackY + (int)Math.round(travel * (scrollOffset / (double)Math.max(1, maxScroll)));
            g.setColor(new Color(255, 255, 255, 16));
            g.fillRoundRect(w - 7, trackY, 3, trackH, 3, 3);
            g.setColor(new Color(112, 214, 255, 125));
            g.fillRoundRect(w - 7, thumbY, 3, thumbH, 3, 3);
        }

        private void drawEllipsis(Graphics2D g, String text, int x, int baseline, int maxWidth) {
            if (text == null || maxWidth <= 0) return;
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) {
                g.drawString(text, x, baseline);
                return;
            }
            String suffix = "…";
            int allowed = Math.max(0, maxWidth - fm.stringWidth(suffix));
            int end = text.length();
            while (end > 0 && fm.stringWidth(text.substring(0, end)) > allowed) end--;
            g.drawString((end <= 0 ? "" : text.substring(0, end).stripTrailing()) + suffix, x, baseline);
        }

        private void drawCenteredEllipsis(Graphics2D g, String text, int x, int baseline, int maxWidth) {
            if (text == null || maxWidth <= 0) return;
            FontMetrics fm = g.getFontMetrics();
            String value = text;
            if (fm.stringWidth(value) > maxWidth) {
                String suffix = "…";
                int allowed = Math.max(0, maxWidth - fm.stringWidth(suffix));
                int end = value.length();
                while (end > 0 && fm.stringWidth(value.substring(0, end)) > allowed) end--;
                value = (end <= 0 ? "" : value.substring(0, end).stripTrailing()) + suffix;
            }
            int width = fm.stringWidth(value);
            g.drawString(value, x + Math.max(0, (maxWidth - width) / 2), baseline);
        }

        private String compactInstallation(String value) {
            if (value == null) return "INSTALLATION";
            return switch (value) {
                case "Research Site" -> "RESEARCH";
                case "Sensor Array" -> "SENSOR";
                case "Logistics Hub" -> "LOGISTICS";
                default -> value.toUpperCase(Locale.ROOT);
            };
        }
    }
}
