package com.tndmadman.rts;

import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
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
import java.awt.event.MouseMotionAdapter;
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

/** Styled in-game celestial intelligence and extraction HUD. */
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
        IntelView view = buildView(system, selectedBodyId(world), localPlayerId(world));
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
        world.status = oneLine(system, body.id(), localPlayerId(world));
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
        if (panel != null && panel.getParent() != null) panel.getParent().remove(panel);
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
            } else if (status.complete()) detail = "Complete";
            else if (objective == CelestialObjectiveType.HOLD) detail = Math.round(progress) + " / " + Math.round(target) + " sec";
            else if (objective == CelestialObjectiveType.EXTRACT) detail = Math.round(progress) + " / " + Math.round(target);
            else detail = "Pending";
            objectives.add(new ObjectiveView(
                    title(objective.name()),
                    progress / target,
                    detail,
                    status.complete(),
                    CelestialObjectiveHelp.requirement(system, bodyId, objective)));
        }

        String masterId = CelestialMoonInheritance.masterPlanetId(system, bodyId);
        CelestialSystem.BodyView master = masterId.isBlank() ? null : system.celestials.bodyView(masterId);
        String masterName = master == null ? state.profile.bodyName() : master.name();
        boolean released = CelestialExtractionSystem.released(system, bodyId);
        boolean chargeInFlight = CelestialExtractionSystem.chargeInFlight(system, bodyId);
        boolean extractorReady = CelestialExtractionSystem.extractorReady(system, bodyId, playerId);

        return new IntelView(
                state.profile.bodyName(), bodyId,
                body.moon() ? "MOON" : "PLANET", state.profile.bodyType(), Math.round(body.radius()),
                intel, scanSeconds, controlText, controlColor, state.contested,
                state.profile.installationSlots(), installations, deposits,
                List.copyOf(state.profile.traits()), List.copyOf(state.profile.hazards()), bonuses, objectives,
                released, chargeInFlight, extractorReady,
                CelestialExtractionSystem.chargeProgress(system, bodyId), masterName);
    }

    private static String oneLine(WorldSystemState system, String bodyId, String playerId) {
        CelestialBodyState state = CelestialGameplaySystem.bodyState(system, bodyId);
        if (state == null) return "Celestial body selected.";
        CelestialIntelLevel intel = CelestialGameplaySystem.intel(system, bodyId, playerId);
        String claim = state.contested ? "contested"
                : state.claimantId == null || state.claimantId.isBlank() ? "unclaimed"
                : "claimed by " + PlayerRegistry.name(state.claimantId);
        String extraction = "";
        if (intel.ordinal() >= CelestialIntelLevel.SCANNED.ordinal()) {
            if (CelestialExtractionSystem.released(system, bodyId)) {
                extraction = " | exposed deposits: " + state.resourceNodeIds.size() + " (use LOCATE)";
            } else if (CelestialExtractionSystem.chargeInFlight(system, bodyId)) {
                extraction = " | fracture charge in flight";
            } else if (CelestialExtractionSystem.extractorReady(system, bodyId, playerId)) {
                extraction = " | deposits surveyed — fire fracture charge";
            } else {
                extraction = " | deposits surveyed — Planetary Extractor required";
            }
        }
        return "Selected " + state.profile.bodyName() + " | " + state.profile.bodyType()
                + " | " + claim + " | intel: " + intel.name().toLowerCase(Locale.ROOT) + extraction;
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

    private static ResourceNode resourceById(WorldSystemState state, int id) {
        if (state == null) return null;
        for (ResourceNode node : state.resources) if (node != null && node.id == id) return node;
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
    private record ObjectiveView(String name, double fraction, String detail, boolean complete, String help) { }
    private record ObjectiveHitbox(Rectangle bounds, ObjectiveView objective) { }
    private record IntelView(
            String name, String bodyId, String kind, String type, long radius,
            CelestialIntelLevel intel, double scanSeconds,
            String control, Color controlColor, boolean contested,
            int slots, List<String> installations, List<String> deposits,
            List<String> traits, List<String> hazards, List<BonusView> bonuses, List<ObjectiveView> objectives,
            boolean released, boolean chargeInFlight, boolean extractorReady,
            double chargeProgress, String masterName) { }

    private static final class IntelPanel extends javax.swing.JComponent {
        private final WeakReference<World> worldRef;
        private final WeakReference<GamePanel> hostRef;
        private IntelView view;
        private int scrollOffset;
        private int maxScroll;
        private int depositCycle;
        private boolean depositHover;
        private String hoveredObjectiveName = "";
        private final Rectangle closeBounds = new Rectangle();
        private final Rectangle depositBounds = new Rectangle();
        private final List<ObjectiveHitbox> objectiveHitboxes = new ArrayList<>();

        IntelPanel(World world, GamePanel host) {
            worldRef = new WeakReference<>(world);
            hostRef = new WeakReference<>(host);
            setOpaque(false);
            setFocusable(false);
            setPreferredSize(new Dimension(PANEL_WIDTH, 600));
            setToolTipText(null);
            javax.swing.ToolTipManager.sharedInstance().unregisterComponent(this);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (closeBounds.contains(event.getPoint())) {
                        setVisible(false);
                        event.consume();
                        return;
                    }
                    if (depositBounds.contains(event.getPoint())) {
                        activateDeposits();
                        event.consume();
                    }
                }

                @Override public void mouseExited(MouseEvent event) {
                    if (depositHover || !hoveredObjectiveName.isBlank()) {
                        depositHover = false;
                        hoveredObjectiveName = "";
                        setCursor(Cursor.getDefaultCursor());
                        repaint();
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent event) { updateHover(event.getPoint()); }
            });
            addMouseWheelListener(this::scroll);
        }

        @Override public String getToolTipText(MouseEvent event) { return null; }
        GamePanel host() { return hostRef.get(); }

        void setView(IntelView next) {
            boolean changedBody = view == null || next == null || !view.bodyId().equals(next.bodyId());
            view = next;
            if (changedBody) {
                scrollOffset = 0;
                depositCycle = 0;
                depositHover = false;
                hoveredObjectiveName = "";
            }
            repaint();
        }

        private void scroll(MouseWheelEvent event) {
            if (maxScroll <= 0) return;
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + event.getWheelRotation() * 34));
            hoveredObjectiveName = "";
            depositHover = false;
            repaint();
            event.consume();
        }

        private void updateHover(Point point) {
            boolean nextDepositHover = depositBounds.contains(point);
            String nextObjective = "";
            for (ObjectiveHitbox hitbox : objectiveHitboxes) {
                if (hitbox.bounds().contains(point)) {
                    nextObjective = hitbox.objective().name();
                    break;
                }
            }
            if (nextDepositHover == depositHover && nextObjective.equals(hoveredObjectiveName)) return;
            depositHover = nextDepositHover;
            hoveredObjectiveName = nextObjective;
            setCursor(nextDepositHover ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            repaint();
        }

        private void activateDeposits() {
            World world = worldRef.get();
            GamePanel host = hostRef.get();
            IntelView model = view;
            if (world == null || host == null || model == null) return;
            if (model.intel().ordinal() < CelestialIntelLevel.SCANNED.ordinal()) {
                world.status = "Scan " + model.name() + " before attempting extraction.";
                return;
            }
            WorldSystemState system = activeState(world);
            String bodyId = selectedBodyId(world);
            if (system == null || bodyId.isBlank()) return;

            if (!CelestialExtractionSystem.released(system, bodyId)) {
                CelestialExtractionSystem.FireResult result = CelestialExtractionSystem.fireCharge(
                        system, bodyId, localPlayerId(world));
                world.status = result.message();
                refresh(world);
                host.repaint();
                repaint();
                return;
            }
            locateNextDeposit(world, host, system, bodyId, model);
        }

        private void locateNextDeposit(World world, GamePanel host, WorldSystemState system,
                                       String bodyId, IntelView model) {
            CelestialBodyState body = CelestialGameplaySystem.bodyState(system, bodyId);
            if (body == null || body.resourceNodeIds.isEmpty()) {
                world.status = "Fracture complete, but no physical deposits are currently available for " + model.name() + ".";
                return;
            }
            List<ResourceNode> deposits = new ArrayList<>();
            for (int id : body.resourceNodeIds) {
                ResourceNode node = resourceById(system, id);
                if (node != null && node.active) deposits.add(node);
            }
            if (deposits.isEmpty()) {
                for (int id : body.resourceNodeIds) {
                    ResourceNode node = resourceById(system, id);
                    if (node != null) deposits.add(node);
                }
            }
            if (deposits.isEmpty()) {
                world.status = "Fracture complete, but no physical deposits are currently available for " + model.name() + ".";
                return;
            }
            int index = Math.floorMod(depositCycle, deposits.size());
            ResourceNode node = deposits.get(index);
            depositCycle = (index + 1) % deposits.size();
            world.selectedResourceId = node.id;
            GameCamera camera = GameCamera.forWorld(world);
            if (camera != null) camera.centerAt(node.x, node.y, world, host.getWidth(), host.getHeight());
            long amount = Math.round(Math.max(0.0, node.amount));
            world.status = "Located " + node.material.label + " deposit " + (index + 1) + "/" + deposits.size()
                    + " orbiting " + model.name() + " | " + amount + " units remaining"
                    + " | Select a mining ship and right-click this deposit to harvest.";
            host.requestFocusInWindow();
            host.repaint();
            repaint();
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
            objectiveHitboxes.clear();
            depositBounds.setBounds(0, 0, 0, 0);

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
            drawObjectiveTooltip(g, w, h);
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
            y = drawBodyCard(g, model, accent, x, y, innerW) + 10;
            y = drawInstallations(g, model, accent, x, y, innerW) + 10;
            y = drawSurvey(g, model, accent, x, y, innerW) + 10;
            y = drawBonuses(g, model, accent, x, y, innerW) + 10;
            y = drawObjectives(g, model, accent, x, y, innerW) + 10;
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
                drawCenteredEllipsis(g,
                        filled ? compactInstallation(model.installations().get(i)) : "EMPTY",
                        sx + 5, y + 25, slotW - 10);
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
            depositBounds.setBounds(x, HEADER_HEIGHT + y - scrollOffset, colW, 84);
            drawDepositColumn(g, model, x, y, colW, accent);
            drawSurveyColumn(g, "TRAITS", model.traits(), x + colW + gap, y, colW, CYAN);
            drawSurveyColumn(g, "HAZARDS", model.hazards(), x + (colW + gap) * 2, y, colW,
                    model.hazards().isEmpty() ? GOOD : WARN);
            return y + 84;
        }

        private int drawBonuses(Graphics2D g, IntelView model, Color accent, int x, int y, int w) {
            sectionTitle(g, "STRATEGIC BONUSES",
                    model.intel() == CelestialIntelLevel.ANALYZED ? "ANALYZED" : "FULL ANALYSIS REQUIRED", x, y, w);
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
                Rectangle hit = new Rectangle(x + 6, HEADER_HEIGHT + rowY - scrollOffset - 4, w - 12, 24);
                objectiveHitboxes.add(new ObjectiveHitbox(hit, objective));
                boolean hovered = objective.name().equals(hoveredObjectiveName);
                if (hovered) {
                    g.setColor(new Color(112, 214, 255, 17));
                    g.fillRoundRect(x + 6, rowY - 5, w - 12, 23, 6, 6);
                }
                g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
                g.setColor(objective.complete() ? GOOD : hovered ? CYAN : TEXT);
                g.drawString((objective.complete() ? "✓" : "•") + " " + objective.name().toUpperCase(Locale.ROOT),
                        x + 12, rowY + 9);
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
            drawEllipsis(g, "Claim the planet, deploy specialized orbital stations, then exploit its surveyed resources.",
                    x + 13, y + 35, w - 26);
            return y + h;
        }

        private void drawDepositColumn(Graphics2D g, IntelView model, int x, int y, int w, Color accent) {
            Color fill = depositHover ? alpha(accent, 31) : CARD_ALT;
            card(g, x, y, w, 84, fill);
            if (depositHover) {
                g.setColor(alpha(accent, 205));
                g.drawRoundRect(x, y, w, 84, 10, 10);
            }
            String action;
            Color actionColor;
            if (model.released()) {
                action = "LOCATE ↗";
                actionColor = GOOD;
            } else if (model.chargeInFlight()) {
                action = "IN FLIGHT";
                actionColor = WARN;
            } else if (model.extractorReady()) {
                action = "FIRE CHARGE";
                actionColor = CYAN;
            } else {
                action = "NEED EXTRACTOR";
                actionColor = WARN;
            }
            g.setFont(g.getFont().deriveFont(Font.BOLD, 8f));
            g.setColor(accent);
            g.drawString("DEPOSITS", x + 9, y + 15);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 7f));
            g.setColor(actionColor);
            int actionW = g.getFontMetrics().stringWidth(action);
            g.drawString(action, x + w - actionW - 8, y + 15);

            List<String> safe = model.deposits() == null ? List.of() : model.deposits();
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
            g.setColor(TEXT);
            int lineY = y + 34;
            int shown = Math.min(2, safe.size());
            for (int i = 0; i < shown; i++) {
                drawEllipsis(g, "◆ " + safe.get(i), x + 9, lineY, w - 18);
                lineY += 14;
            }
            if (safe.isEmpty()) {
                g.setColor(MUTED);
                g.drawString("None detected", x + 9, y + 36);
            }

            g.setFont(g.getFont().deriveFont(Font.PLAIN, 8f));
            if (model.released()) {
                g.setColor(depositHover ? GOOD : MUTED);
                g.drawString("Click to locate exposed rock", x + 9, y + 76);
            } else if (model.chargeInFlight()) {
                g.setColor(WARN);
                g.drawString("Fracturing " + Math.round(model.chargeProgress() * 100) + "%", x + 9, y + 69);
                progress(g, x + 9, y + 74, w - 18, 4, model.chargeProgress(), WARN);
            } else if (model.extractorReady()) {
                g.setColor(depositHover ? CYAN : MUTED);
                g.drawString("Click to fire fracture charge", x + 9, y + 76);
            } else {
                g.setColor(WARN);
                drawEllipsis(g, "Deploy Extractor to " + model.masterName(), x + 9, y + 76, w - 18);
            }
        }

        private void drawSurveyColumn(Graphics2D g, String heading, List<String> values,
                                      int x, int y, int w, Color accent) {
            card(g, x, y, w, 84, CARD_ALT);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 8f));
            g.setColor(accent);
            g.drawString(heading, x + 9, y + 15);
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
            int shown = Math.min(3, safe.size());
            for (int i = 0; i < shown; i++) {
                drawEllipsis(g, "• " + safe.get(i), x + 9, lineY, w - 18);
                lineY += 15;
            }
            if (safe.size() > shown) {
                g.setColor(MUTED);
                g.drawString("+" + (safe.size() - shown) + " more", x + 9, y + 78);
            }
        }

        private void drawObjectiveTooltip(Graphics2D g, int w, int h) {
            if (hoveredObjectiveName.isBlank()) return;
            ObjectiveView objective = null;
            if (view != null) {
                for (ObjectiveView candidate : view.objectives()) {
                    if (candidate.name().equals(hoveredObjectiveName)) {
                        objective = candidate;
                        break;
                    }
                }
            }
            if (objective == null || objective.help() == null || objective.help().isBlank()) return;
            int boxW = Math.max(220, w - 32);
            int x = 16;
            List<String> lines = wrap(g, objective.help(), boxW - 24, 10f);
            int boxH = 31 + lines.size() * 14;
            int y = Math.max(HEADER_HEIGHT + 8, h - boxH - 14);
            g.setColor(new Color(3, 9, 15, 245));
            g.fillRoundRect(x, y, boxW, boxH, 10, 10);
            g.setColor(alpha(CYAN, 190));
            g.drawRoundRect(x, y, boxW, boxH, 10, 10);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
            g.setColor(CYAN);
            g.drawString(objective.name().toUpperCase(Locale.ROOT) + " REQUIREMENTS", x + 12, y + 17);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
            g.setColor(TEXT);
            int baseline = y + 34;
            for (String line : lines) {
                g.drawString(line, x + 12, baseline);
                baseline += 14;
            }
        }

        private List<String> wrap(Graphics2D g, String text, int width, float fontSize) {
            Font old = g.getFont();
            g.setFont(old.deriveFont(Font.PLAIN, fontSize));
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && fm.stringWidth(candidate) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    if (!line.isEmpty()) line.append(' ');
                    line.append(word);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
            g.setFont(old);
            return lines.isEmpty() ? List.of("") : lines;
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

        private void sectionTitle(Graphics2D g, String heading, String right, int x, int y, int w) {
            g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
            g.setColor(CYAN);
            g.drawString(heading, x, y + 10);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
            g.setColor(MUTED);
            int rw = g.getFontMetrics().stringWidth(right);
            g.drawString(right, x + w - rw, y + 10);
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
                case "Extractor" -> "EXTRACTOR";
                default -> value.toUpperCase(Locale.ROOT);
            };
        }
    }
}
