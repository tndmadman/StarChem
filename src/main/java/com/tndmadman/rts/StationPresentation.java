package com.tndmadman.rts;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * View-only station presentation policy for issue #398.
 *
 * Ordinary stations stay visually quiet. Hover resolves to one nearest visible station and is
 * drawn in screen space after the world pass, while focus exposes tactical range/health and a
 * persistent inspector. Context hit-testing obeys fog-of-war and avoids primary HUD surfaces.
 * No simulation, networking, persistence, or station authority lives here.
 */
final class StationPresentation {
    private static final Color PANEL = new Color(5, 11, 16, 232);
    private static final Color PANEL_INNER = new Color(15, 24, 31, 222);
    private static final Color TEXT = new Color(228, 240, 248);
    private static final Color MUTED = new Color(145, 169, 184);
    private static final Color HP = new Color(103, 224, 119);
    private static final Color SHIELD = new Color(98, 186, 255);
    private static final Color FUEL = new Color(255, 210, 110);
    private static final Color WARNING = new Color(255, 102, 88);
    private static final Color PRODUCTION = new Color(255, 198, 96);
    private static final int CLICK_DRAG_TOLERANCE_PX = 8;
    private static final long HIT_STALE_NANOS = 500_000_000L;
    private static final long FOCUS_STALE_NANOS = 1_000_000_000L;
    private static final Rectangle PRIMARY_HUD = new Rectangle(12, 12, 1120, 132);
    private static final MinimapHud MINIMAP_HIT_TEST = new MinimapHud();

    private static final Map<FocusKey, ScreenHit> SCREEN_HITS = new LinkedHashMap<>();
    private static GamePanel surface;
    private static Point pointer = offscreenPoint();
    private static Point pressPoint;
    private static String pressSystemId = "";
    private static FocusKey hoveredBase;
    private static FocusKey focusedBase;
    private static Base focusedBaseView;
    private static long focusedBaseSeenNanos;
    private static PresentationOverlay presentationOverlay;

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            AWTEventListener listener = event -> {
                if (event instanceof MouseEvent mouse && mouse.getSource() instanceof GamePanel panel) {
                    handleMouse(panel, mouse);
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(listener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }
    }

    private StationPresentation() { }

    static void draw(Graphics2D g2, Base base, BaseType def, double radius, Color playerColor) {
        if (g2 == null || base == null || def == null || playerColor == null) return;
        syncOverlayBounds();
        ScreenHit current = registerScreenHit(g2, base, radius);

        drawOwnershipCue(g2, base, radius, playerColor);
        String warning = criticalWarning(base, def);
        if (warning != null) drawWarningBadge(g2, base, radius, warning);

        if (current != null && current.key().equals(focusedBase)) {
            drawRange(g2, base, def, playerColor);
            drawFocusedBars(g2, base, def, radius, playerColor);
            focusedBaseView = base;
            focusedBaseSeenNanos = System.nanoTime();
            repaintOverlay();
        }
    }

    static String criticalWarning(Base base, BaseType def) {
        if (base == null || def == null) return null;
        if (base.hp <= 0) return "OFFLINE";
        StationFuelRequirement req = StationFuelRules.requirement(base.typeId);
        if (req != null && !StationFuelRules.isOperational(base)) return "NO FUEL";
        boolean recentlyHit = base.shieldDelayTimer > 0.05
                && (base.hp < def.maxHp * 0.999 || base.shield < def.maxShield * 0.999);
        if (recentlyHit) return "UNDER ATTACK";
        if (def.maxHp > 0 && base.hp / def.maxHp <= 0.35) return "CRITICAL DAMAGE";
        return null;
    }

    static String conciseStatus(Base base, BaseType def) {
        String warning = criticalWarning(base, def);
        if (warning != null) return warning;

        // Moving information out of permanent world labels must not create an intelligence
        // side-channel. Foreign stations expose only public tactical state.
        if (!PlayerRegistry.isLocal(base.playerId)) return "Operational";

        ProductionJob job = ProductionQueueScheduler.active(base);
        if (job != null && job.blockedReason != null && !job.blockedReason.isBlank()) {
            return clip("Production blocked: " + job.blockedReason, 42);
        }
        if (job != null) return clip("Producing " + ProductionSystem.displayName(job), 42);
        if (base.logisticsStatus != null && !base.logisticsStatus.isBlank()) return clip(base.logisticsStatus, 42);
        String intel = intelStatus(base);
        if (!intel.isBlank()) return intel;
        StationFuelRequirement req = StationFuelRules.requirement(base.typeId);
        if (req != null) return "Operational | Fuel " + Calc.round(base.inventory.getOrDefault(req.material(), 0.0));
        return "Operational";
    }

    static String resolveNearestHoverIdForTest(Graphics2D g2, Point point, Base... bases) {
        SCREEN_HITS.clear();
        if (g2 == null || bases == null) return "";
        long now = System.nanoTime();
        for (Base base : bases) {
            if (base == null) continue;
            FocusKey key = FocusKey.of(base);
            SCREEN_HITS.put(key, new ScreenHit(key, base, base.x, base.y, 64, now));
        }
        FocusKey key = hitAt(point);
        return key == null ? "" : key.id();
    }

    private static void handleMouse(GamePanel panel, MouseEvent mouse) {
        if (surface != panel) {
            detachPresentationOverlay();
            surface = panel;
            SCREEN_HITS.clear();
            hoveredBase = null;
            focusedBase = null;
            focusedBaseView = null;
            installPresentationOverlay(panel);
        }
        syncOverlayBounds();
        boolean eligible = pointerEligible(panel, mouse.getPoint());
        switch (mouse.getID()) {
            case MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_ENTERED -> {
                pointer = eligible ? mouse.getPoint() : offscreenPoint();
                hoveredBase = eligible ? resolveHover() : null;
                repaintOverlay();
            }
            case MouseEvent.MOUSE_EXITED -> {
                pointer = offscreenPoint();
                hoveredBase = null;
                repaintOverlay();
            }
            case MouseEvent.MOUSE_PRESSED -> {
                pointer = eligible ? mouse.getPoint() : offscreenPoint();
                hoveredBase = eligible ? resolveHover() : null;
                if (mouse.getButton() == MouseEvent.BUTTON1 && eligible) {
                    pressPoint = mouse.getPoint();
                    pressSystemId = currentSystemId();
                } else {
                    pressPoint = null;
                    pressSystemId = "";
                }
            }
            case MouseEvent.MOUSE_RELEASED -> {
                pointer = eligible ? mouse.getPoint() : offscreenPoint();
                hoveredBase = eligible ? resolveHover() : null;
                if (mouse.getButton() == MouseEvent.BUTTON1 && pressPoint != null) {
                    boolean sameSystem = pressSystemId.equals(currentSystemId());
                    if (eligible && sameSystem && pressPoint.distance(mouse.getPoint()) <= CLICK_DRAG_TOLERANCE_PX) {
                        focusedBase = hitAt(mouse.getPoint());
                        ScreenHit selected = focusedBase == null ? null : SCREEN_HITS.get(focusedBase);
                        focusedBaseView = selected == null ? null : selected.base();
                        if (selected != null) focusedBaseSeenNanos = System.nanoTime();
                    } else if (eligible && sameSystem
                            && pressPoint.distance(mouse.getPoint()) > CLICK_DRAG_TOLERANCE_PX) {
                        // A real world-space drag selection supersedes station focus. HUD clicks do not.
                        focusedBase = null;
                        focusedBaseView = null;
                    }
                    pressPoint = null;
                    pressSystemId = "";
                }
                repaintOverlay();
            }
            default -> { }
        }
    }

    private static boolean pointerEligible(GamePanel panel, Point point) {
        if (panel == null || point == null || galaxyMapAppearsOpen()) return false;
        if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) return false;
        if (PRIMARY_HUD.contains(point)) return false;
        World world = PlayerRegistry.activeWorld();
        if (world != null && panel.getWidth() > 0 && panel.getHeight() > 0
                && MINIMAP_HIT_TEST.bounds(world, panel.getWidth(), panel.getHeight()).contains(point)) return false;
        return true;
    }

    private static boolean galaxyMapAppearsOpen() {
        World world = PlayerRegistry.activeWorld();
        if (world == null || world.status == null) return false;
        return world.status.startsWith("Galaxy map open.");
    }

    private static boolean contextVisible(Base base) {
        if (base == null) return false;
        World world = PlayerRegistry.activeWorld();
        if (world == null) return true;
        return PlayerRegistry.isLocal(base.playerId)
                || FogOfWarView.currentlyVisible(world, base.x, base.y);
    }

    private static String currentSystemId() {
        World world = PlayerRegistry.activeWorld();
        String id = world == null ? "" : world.activeSystemId();
        return id == null ? "" : id;
    }

    private static void installPresentationOverlay(GamePanel panel) {
        if (panel == null) return;
        JRootPane root = SwingUtilities.getRootPane(panel);
        if (root == null) return;
        JLayeredPane layeredPane = root.getLayeredPane();
        if (layeredPane == null) return;
        presentationOverlay = new PresentationOverlay();
        layeredPane.add(presentationOverlay, JLayeredPane.PALETTE_LAYER);
        syncOverlayBounds();
    }

    private static void detachPresentationOverlay() {
        if (presentationOverlay == null) return;
        Container parent = presentationOverlay.getParent();
        if (parent != null) {
            parent.remove(presentationOverlay);
            parent.repaint();
        }
        presentationOverlay = null;
    }

    private static void repaintOverlay() {
        if (presentationOverlay != null) presentationOverlay.repaint();
    }

    private static void syncOverlayBounds() {
        GamePanel panel = surface;
        PresentationOverlay overlay = presentationOverlay;
        if (panel == null || overlay == null || overlay.getParent() == null) return;
        Container parent = overlay.getParent();
        Point origin = SwingUtilities.convertPoint(panel, 0, 0, parent);
        if (overlay.getX() != origin.x || overlay.getY() != origin.y
                || overlay.getWidth() != panel.getWidth() || overlay.getHeight() != panel.getHeight()) {
            overlay.setBounds(origin.x, origin.y, panel.getWidth(), panel.getHeight());
        }
    }

    private static ScreenHit registerScreenHit(Graphics2D g2, Base base, double radius) {
        if (!contextVisible(base)) {
            SCREEN_HITS.remove(FocusKey.of(base));
            return null;
        }
        Point2D center;
        World world = PlayerRegistry.activeWorld();
        GameCamera camera = GameCamera.forWorld(world);
        if (camera != null) {
            center = camera.worldToScreen(base.x, base.y);
        } else {
            AffineTransform tx = componentTransform(g2);
            center = tx.transform(new Point2D.Double(base.x, base.y), null);
        }
        double screenRadius;
        if (camera != null) {
            Point2D edge = camera.worldToScreen(base.x + radius, base.y);
            screenRadius = Math.max(18.0, Math.abs(edge.getX() - center.getX()));
        } else {
            AffineTransform tx = componentTransform(g2);
            double scaleX = Math.hypot(tx.getScaleX(), tx.getShearY());
            double scaleY = Math.hypot(tx.getShearX(), tx.getScaleY());
            screenRadius = Math.max(18.0, radius * Math.max(0.01, Math.max(scaleX, scaleY)));
        }
        FocusKey key = FocusKey.of(base);
        ScreenHit hit = new ScreenHit(key, base, center.getX(), center.getY(), screenRadius, System.nanoTime());
        SCREEN_HITS.put(key, hit);
        return hit;
    }

    private static void pruneHits() {
        long now = System.nanoTime();
        String systemId = currentSystemId();
        SCREEN_HITS.entrySet().removeIf(entry -> now - entry.getValue().seenNanos() > HIT_STALE_NANOS
                || !entry.getKey().systemId().equals(systemId)
                || !contextVisible(entry.getValue().base()));
    }

    private static FocusKey resolveHover() {
        pruneHits();
        return isOffscreen(pointer) ? null : nearestAt(pointer);
    }

    private static FocusKey hitAt(Point point) {
        if (point == null || isOffscreen(point)) return null;
        pruneHits();
        return nearestAt(point);
    }

    private static FocusKey nearestAt(Point point) {
        ScreenHit best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        for (ScreenHit hit : SCREEN_HITS.values()) {
            double dx = point.x - hit.centerX();
            double dy = point.y - hit.centerY();
            double distanceSq = dx * dx + dy * dy;
            if (distanceSq > hit.radius() * hit.radius() || distanceSq >= bestDistanceSq) continue;
            best = hit;
            bestDistanceSq = distanceSq;
        }
        return best == null ? null : best.key();
    }

    private static AffineTransform componentTransform(Graphics2D g2) {
        AffineTransform tx = new AffineTransform(g2.getTransform());
        GamePanel target = surface;
        if (target == null || target.getGraphicsConfiguration() == null) return tx;
        try {
            AffineTransform deviceInverse = target.getGraphicsConfiguration().getDefaultTransform().createInverse();
            tx.preConcatenate(deviceInverse);
        } catch (NoninvertibleTransformException ignored) {
            // Fall back to the graphics transform on an unusual display configuration.
        }
        return tx;
    }

    private static void drawRange(Graphics2D g2, Base base, BaseType def, Color playerColor) {
        if (def.unloadRange <= 0) return;
        double diameter = def.unloadRange * 2.0;
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 22));
        g2.fill(new Ellipse2D.Double(base.x - def.unloadRange, base.y - def.unloadRange, diameter, diameter));
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 118));
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{10f, 8f}, 0));
        g2.draw(new Ellipse2D.Double(base.x - def.unloadRange, base.y - def.unloadRange, diameter, diameter));
    }

    private static void drawOwnershipCue(Graphics2D g2, Base base, double radius, Color playerColor) {
        double markerY = base.y + radius + 7;
        g2.setColor(new Color(0, 0, 0, 165));
        g2.fill(new Ellipse2D.Double(base.x - 5, markerY - 5, 10, 10));
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 225));
        g2.fill(new Ellipse2D.Double(base.x - 3, markerY - 3, 6, 6));
    }

    private static void drawWarningBadge(Graphics2D g2, Base base, double radius, String warning) {
        Font oldFont = g2.getFont();
        g2.setFont(oldFont.deriveFont(Font.BOLD, 11f));
        int width = g2.getFontMetrics().stringWidth(warning) + 16;
        int x = (int)Math.round(base.x - width / 2.0);
        int y = (int)Math.round(base.y - radius - 26);
        g2.setColor(new Color(25, 4, 4, 210));
        g2.fillRoundRect(x, y, width, 18, 8, 8);
        g2.setColor(WARNING);
        g2.drawRoundRect(x, y, width, 18, 8, 8);
        g2.setColor(new Color(255, 222, 215));
        g2.drawString(warning, x + 8, y + 13);
        g2.setFont(oldFont);
    }

    private static void drawHoverCard(Graphics2D g2, ScreenHit hit, int screenWidth, int screenHeight) {
        if (hit == null || !contextVisible(hit.base())
                || !hit.key().systemId().equals(currentSystemId())
                || System.nanoTime() - hit.seenNanos() > HIT_STALE_NANOS) return;
        Base base = hit.base();
        BaseType def = base.type();
        Color playerColor = PlayerRegistry.color(base.playerId);
        String title = IntelWarfareSystem.CONTACT_STATION.equals(base.typeId)
                ? def.name : def.name + " - " + PlayerRegistry.name(base.playerId);
        String summary = conciseStatus(base, def);

        Font oldFont = g2.getFont();
        g2.setFont(oldFont.deriveFont(Font.BOLD, 12f));
        int width = Math.max(g2.getFontMetrics().stringWidth(title),
                g2.getFontMetrics().stringWidth(summary)) + 18;
        width = Math.min(width, Math.max(120, screenWidth - 16));
        int x = (int)Math.round(hit.centerX() - width / 2.0);
        int y = (int)Math.round(hit.centerY() + hit.radius() + 12);
        x = Math.max(8, Math.min(x, screenWidth - width - 8));
        y = Math.max(8, Math.min(y, screenHeight - 44));

        g2.setColor(new Color(0, 0, 0, 205));
        g2.fillRoundRect(x, y, width, 36, 9, 9);
        String warning = criticalWarning(base, def);
        Color border = warning == null ? playerColor : WARNING;
        g2.setColor(new Color(border.getRed(), border.getGreen(), border.getBlue(), 225));
        g2.drawRoundRect(x, y, width, 36, 9, 9);
        g2.setColor(playerColor);
        g2.drawString(clipToWidth(g2, title, width - 18), x + 9, y + 14);
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
        g2.setColor(warning == null ? TEXT : WARNING);
        g2.drawString(clipToWidth(g2, summary, width - 18), x + 9, y + 29);
        g2.setFont(oldFont);
    }

    private static void drawFocusedBars(Graphics2D g2, Base base, BaseType def, double radius, Color playerColor) {
        int width = 72;
        int x = (int)Math.round(base.x - width / 2.0);
        int y = (int)Math.round(base.y - radius - 47);
        if (def.maxShield > 0) {
            drawWorldBar(g2, x, y, width, 6, base.shield, def.maxShield, SHIELD);
            y += 9;
        }
        drawWorldBar(g2, x, y, width, 7, base.hp, def.maxHp, HP);
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 205));
        g2.setStroke(new BasicStroke(1.6f));
        g2.draw(new Ellipse2D.Double(base.x - radius - 5, base.y - radius - 5,
                radius * 2 + 10, radius * 2 + 10));
    }

    private static void drawWorldBar(Graphics2D g2, int x, int y, int width, int height,
                                     double value, double max, Color fill) {
        g2.setColor(new Color(8, 12, 16, 220));
        g2.fillRect(x, y, width, height);
        double ratio = max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
        g2.setColor(fill);
        g2.fillRect(x, y, (int)Math.round(width * ratio), height);
    }

    private static void drawInspector(Graphics2D g2, Base base, BaseType def,
                                      Color playerColor, String warning, int screenWidth, int screenHeight) {
        if (screenWidth < 480 || screenHeight < 340 || !contextVisible(base)) return;
        boolean local = PlayerRegistry.isLocal(base.playerId);
        List<String> inventoryRows = local ? ResourceText.lines(base.inventory) : List.of();
        int queueRows = local ? Math.min(4, base.productionQueue.size()) : 0;
        int inventoryRowsShown = local ? Math.min(10, inventoryRows.size()) : 0;
        int warningHeight = warning == null ? 0 : 31;
        int desiredHeight = (local ? 250 + queueRows * 15 + inventoryRowsShown * 15 : 174) + warningHeight;

        int x = 14;
        int y = 156;
        int panelWidth = Math.min(local && inventoryRows.size() > 8 ? 430 : 360, screenWidth - 28);
        int panelHeight = Math.min(desiredHeight, screenHeight - y - 14);
        if (panelHeight < 150) return;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(PANEL);
        g2.fillRoundRect(x, y, panelWidth, panelHeight, 14, 14);
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 190));
        g2.drawRoundRect(x, y, panelWidth - 1, panelHeight - 1, 14, 14);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
        g2.setColor(TEXT);
        g2.drawString(def.name.toUpperCase(Locale.ROOT), x + 14, y + 22);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
        g2.setColor(MUTED);
        g2.drawString(base.id + " | " + PlayerRegistry.name(base.playerId), x + 14, y + 38);

        int cursor = y + 51;
        if (warning != null) {
            g2.setColor(new Color(65, 18, 16, 220));
            g2.fillRoundRect(x + 10, cursor, panelWidth - 20, 24, 8, 8);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.setColor(WARNING);
            g2.drawString(warning, x + 18, cursor + 16);
            cursor += 31;
        }

        g2.setColor(PANEL_INNER);
        g2.fillRoundRect(x + 10, cursor, panelWidth - 20, 50, 9, 9);
        drawPanelBar(g2, x + 18, cursor + 13, panelWidth - 36, "HP", base.hp, def.maxHp, HP);
        if (def.maxShield > 0) {
            drawPanelBar(g2, x + 18, cursor + 35, panelWidth - 36, "SHIELD", base.shield, def.maxShield, SHIELD);
        }
        cursor += 61;

        if (!local) {
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.setColor(TEXT);
            g2.drawString("STATUS", x + 14, cursor);
            cursor += 17;
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            g2.setColor(MUTED);
            g2.drawString(warning == null ? "Operational" : warning, x + 16, cursor);
            g2.drawString(clipToWidth(g2, "Role: " + StationControls.role(base.typeId), panelWidth - 32),
                    x + 16, cursor + 15);
            return;
        }

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        g2.setColor(TEXT);
        g2.drawString("OPERATIONS", x + 14, cursor);
        cursor += 16;
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
        drawOperationLines(g2, base, x + 16, cursor, panelWidth - 32);
        cursor += 58;

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        g2.setColor(TEXT);
        g2.drawString("PRODUCTION QUEUE", x + 14, cursor);
        cursor += 15;
        cursor = drawQueue(g2, base, x + 16, cursor, panelWidth - 32, y + panelHeight - 34);

        if (cursor < y + panelHeight - 28) {
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.setColor(TEXT);
            g2.drawString("HANGAR", x + 14, cursor);
            cursor += 15;
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            drawInventory(g2, inventoryRows, x + 16, cursor, panelWidth - 32,
                    Math.max(0, y + panelHeight - cursor - 10));
        }
    }

    private static int drawQueue(Graphics2D g2, Base base, int x, int y, int width, int bottom) {
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
        if (base.productionQueue.isEmpty()) {
            g2.setColor(MUTED);
            g2.drawString("Idle", x, y);
            return y + 15;
        }

        int rows = Math.min(4, base.productionQueue.size());
        int cursor = y;
        for (int i = 0; i < rows && cursor < bottom; i++) {
            ProductionJob queued = base.productionQueue.get(i);
            String prefix = i == 0 ? "ACTIVE  " : (i + 1) + ".  ";
            String row = prefix + ProductionSystem.displayName(queued);
            if (i == 0) row += " | " + ProductionQueueScheduler.detail(base, queued);
            g2.setColor(i == 0 ? PRODUCTION : MUTED);
            g2.drawString(clipToWidth(g2, row, width), x, cursor);
            cursor += 15;
        }
        int hidden = base.productionQueue.size() - rows;
        if (hidden > 0 && cursor < bottom) {
            g2.setColor(MUTED);
            g2.drawString("+" + hidden + " queued", x, cursor);
            cursor += 15;
        }
        return cursor;
    }

    private static void drawPanelBar(Graphics2D g2, int x, int y, int width,
                                     String label, double value, double max, Color fill) {
        int labelWidth = 52;
        int barX = x + labelWidth;
        int barWidth = Math.max(40, width - labelWidth - 70);
        double ratio = max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 9f));
        g2.setColor(MUTED);
        g2.drawString(label, x, y + 8);
        g2.setColor(new Color(28, 37, 44));
        g2.fillRoundRect(barX, y, barWidth, 9, 5, 5);
        g2.setColor(fill);
        g2.fillRoundRect(barX, y, (int)Math.round(barWidth * ratio), 9, 5, 5);
        g2.setColor(TEXT);
        g2.drawString(shortNumber(value) + " / " + shortNumber(max), barX + barWidth + 8, y + 8);
    }

    private static void drawOperationLines(Graphics2D g2, Base base, int x, int y, int maxWidth) {
        StationFuelRequirement req = StationFuelRules.requirement(base.typeId);
        String fuel;
        if (req == null) {
            fuel = "Fuel: not required";
        } else {
            double amount = base.inventory.getOrDefault(req.material(), 0.0);
            fuel = "Fuel: " + req.material().label + " " + Calc.round(amount)
                    + " | burn " + Calc.round(req.perSecond()) + "/s"
                    + (StationFuelRules.isOperational(base) ? " | online" : " | offline");
        }
        g2.setColor(req != null && !StationFuelRules.isOperational(base) ? WARNING : FUEL);
        g2.drawString(clipToWidth(g2, fuel, maxWidth), x, y);

        ProductionJob active = ProductionQueueScheduler.active(base);
        String production = active == null ? "Production: idle"
                : "Production: " + ProductionSystem.displayName(active) + " | " + ProductionQueueScheduler.detail(base, active);
        g2.setColor(active != null && active.blockedReason != null && !active.blockedReason.isBlank() ? WARNING : PRODUCTION);
        g2.drawString(clipToWidth(g2, production, maxWidth), x, y + 15);

        String logistics = base.logisticsStatus == null || base.logisticsStatus.isBlank()
                ? "Logistics: no current station status" : "Logistics: " + base.logisticsStatus;
        g2.setColor(MUTED);
        g2.drawString(clipToWidth(g2, logistics, maxWidth), x, y + 30);

        String role = "Role: " + StationControls.role(base.typeId)
                + (StationControls.nonProduction(base.typeId) ? " | non-production" : " | production");
        String intel = intelStatus(base);
        if (!intel.isBlank()) role += " | " + intel;
        g2.drawString(clipToWidth(g2, role, maxWidth), x, y + 45);
    }

    private static String intelStatus(Base base) {
        if (base == null || !PlayerRegistry.isLocal(base.playerId)) return "";
        if (IntelWarfareSystem.isRadar(base.typeId)) {
            IntelWarfareSystem.RadarMode mode = IntelWarfareSystem.radarMode(PlayerRegistry.activeWorld(), base);
            return "Radar " + mode.name() + " | adaptive";
        }
        if (IntelWarfareSystem.isJammer(base.typeId)) return "ECM active";
        if (IntelWarfareSystem.isDecoy(base.typeId)) return "False signature active";
        return "";
    }

    private static void drawInventory(Graphics2D g2, List<String> rows, int x, int y, int width, int availableHeight) {
        if (rows.isEmpty()) {
            g2.setColor(MUTED);
            g2.drawString("empty", x, y);
            return;
        }
        int maxRowsPerColumn = Math.max(1, availableHeight / 15);
        int columns = rows.size() > maxRowsPerColumn && width >= 300 ? 2 : 1;
        int columnWidth = width / columns;
        int capacity = maxRowsPerColumn * columns;
        int shown = Math.min(rows.size(), capacity);
        for (int i = 0; i < shown; i++) {
            int column = i / maxRowsPerColumn;
            int row = i % maxRowsPerColumn;
            g2.setColor(MUTED);
            g2.drawString(clipToWidth(g2, rows.get(i), columnWidth - 8),
                    x + column * columnWidth, y + row * 15);
        }
        if (rows.size() > shown && shown > 0) {
            int column = Math.min(columns - 1, (shown - 1) / maxRowsPerColumn);
            int row = Math.min(maxRowsPerColumn - 1, shown % maxRowsPerColumn);
            g2.setColor(TEXT);
            g2.drawString("+" + (rows.size() - shown) + " more", x + column * columnWidth, y + row * 15);
        }
    }

    private static String clipToWidth(Graphics2D g2, String text, int maxWidth) {
        if (text == null || text.isBlank()) return "--";
        if (g2.getFontMetrics().stringWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        int limit = Math.max(0, maxWidth - g2.getFontMetrics().stringWidth(ellipsis));
        int end = text.length();
        while (end > 0 && g2.getFontMetrics().stringWidth(text.substring(0, end)) > limit) end--;
        return end <= 0 ? ellipsis : text.substring(0, end).stripTrailing() + ellipsis;
    }

    private static String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value == null ? "" : value;
        return value.substring(0, Math.max(0, maxChars - 1)).stripTrailing() + "…";
    }

    private static String shortNumber(double value) {
        if (!Double.isFinite(value)) return "--";
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000.0);
        if (abs >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        if (abs >= 100 || Math.abs(value - Math.rint(value)) < 0.05) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Point offscreenPoint() {
        return new Point(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4);
    }

    private static boolean isOffscreen(Point point) {
        return point == null || point.x <= Integer.MIN_VALUE / 8 || point.y <= Integer.MIN_VALUE / 8;
    }

    private static final class PresentationOverlay extends JComponent {
        private PresentationOverlay() {
            setOpaque(false);
            setFocusable(false);
        }

        @Override public boolean contains(int x, int y) {
            return false;
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (galaxyMapAppearsOpen()) return;
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                hoveredBase = resolveHover();
                ScreenHit hovered = hoveredBase == null ? null : SCREEN_HITS.get(hoveredBase);
                if (hovered != null && !hovered.key().equals(focusedBase)) {
                    drawHoverCard(g2, hovered, getWidth(), getHeight());
                }

                Base base = focusedBaseView;
                if (base != null && focusedBase != null
                        && focusedBase.systemId().equals(currentSystemId())
                        && contextVisible(base)
                        && System.nanoTime() - focusedBaseSeenNanos <= FOCUS_STALE_NANOS) {
                    BaseType def = base.type();
                    drawInspector(g2, base, def, PlayerRegistry.color(base.playerId),
                            criticalWarning(base, def), getWidth(), getHeight());
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private record ScreenHit(FocusKey key, Base base, double centerX, double centerY,
                             double radius, long seenNanos) { }

    private record FocusKey(String systemId, String id, String playerId, String typeId) {
        static FocusKey of(Base base) {
            return new FocusKey(currentSystemId(), base.id, base.playerId, base.typeId);
        }
    }
}
