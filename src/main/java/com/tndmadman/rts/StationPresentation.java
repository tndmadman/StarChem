package com.tndmadman.rts;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Locale;

/**
 * View-only station presentation policy for issue #398.
 *
 * Keeps ordinary stations visually quiet, promotes warnings when they matter, and exposes
 * detailed state contextually without changing station simulation, networking, or persistence.
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
    private static final long FOCUS_RESOLVE_NANOS = 120_000_000L;

    private static Component surface;
    private static Point pointer = new Point(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4);
    private static Point pressPoint;
    private static Point pendingFocusPoint;
    private static long pendingFocusUntil;
    private static String focusedBaseId;
    private static double focusedDistanceSq = Double.POSITIVE_INFINITY;

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            AWTEventListener listener = event -> {
                if (!(event instanceof MouseEvent mouse) || !(mouse.getSource() instanceof GamePanel panel)) return;
                handleMouse(panel, mouse);
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(listener,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }
    }

    private StationPresentation() { }

    static void draw(Graphics2D g2, Base base, BaseType def, double radius, Color playerColor) {
        if (g2 == null || base == null || def == null || playerColor == null) return;
        Presentation presentation = presentationFor(g2, base, radius);

        if (presentation.focused()) drawRange(g2, base, def, playerColor);
        drawOwnershipCue(g2, base, radius, playerColor);

        String warning = criticalWarning(base, def);
        if (warning != null) drawWarningBadge(g2, base, radius, warning);

        if (presentation.hovered() || presentation.focused()) {
            drawContextLabel(g2, base, def, radius, playerColor, presentation.focused());
            IntelStructureRenderer.drawStatus(g2, base, radius);
        }
        if (presentation.focused()) {
            drawFocusedBars(g2, base, def, radius, playerColor);
            drawInspector(g2, base, def, playerColor, warning);
        }
    }

    static boolean isFocused(String baseId) {
        return baseId != null && baseId.equals(focusedBaseId);
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
        ProductionJob job = ProductionQueueScheduler.active(base);
        if (job != null && job.blockedReason != null && !job.blockedReason.isBlank()) return "PRODUCTION BLOCKED";
        return null;
    }

    private static void handleMouse(GamePanel panel, MouseEvent mouse) {
        surface = panel;
        switch (mouse.getID()) {
            case MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_ENTERED ->
                    pointer = mouse.getPoint();
            case MouseEvent.MOUSE_EXITED ->
                    pointer = new Point(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4);
            case MouseEvent.MOUSE_PRESSED -> {
                pointer = mouse.getPoint();
                if (mouse.getButton() == MouseEvent.BUTTON1) pressPoint = mouse.getPoint();
            }
            case MouseEvent.MOUSE_RELEASED -> {
                pointer = mouse.getPoint();
                if (mouse.getButton() == MouseEvent.BUTTON1 && pressPoint != null) {
                    if (pressPoint.distance(mouse.getPoint()) <= CLICK_DRAG_TOLERANCE_PX) {
                        focusedBaseId = null;
                        focusedDistanceSq = Double.POSITIVE_INFINITY;
                        pendingFocusPoint = mouse.getPoint();
                        pendingFocusUntil = System.nanoTime() + FOCUS_RESOLVE_NANOS;
                    }
                    pressPoint = null;
                }
            }
            default -> { }
        }
    }

    private static Presentation presentationFor(Graphics2D g2, Base base, double radius) {
        AffineTransform tx = g2.getTransform();
        Point2D center = tx.transform(new Point2D.Double(base.x, base.y), null);
        double scaleX = Math.hypot(tx.getScaleX(), tx.getShearY());
        double scaleY = Math.hypot(tx.getShearX(), tx.getScaleY());
        double scale = Math.max(0.01, Math.max(scaleX, scaleY));
        double hitRadius = Math.max(18.0, radius * scale);
        double hoverDistanceSq = center.distanceSq(pointer);
        boolean hovered = hoverDistanceSq <= hitRadius * hitRadius;

        long now = System.nanoTime();
        if (pendingFocusPoint != null && now <= pendingFocusUntil) {
            double clickDistanceSq = center.distanceSq(pendingFocusPoint);
            if (clickDistanceSq <= hitRadius * hitRadius && clickDistanceSq < focusedDistanceSq) {
                focusedBaseId = base.id;
                focusedDistanceSq = clickDistanceSq;
            }
        } else if (pendingFocusPoint != null) {
            pendingFocusPoint = null;
            focusedDistanceSq = Double.POSITIVE_INFINITY;
        }
        return new Presentation(hovered, base.id.equals(focusedBaseId));
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

    private static void drawContextLabel(Graphics2D g2, Base base, BaseType def, double radius,
                                         Color playerColor, boolean focused) {
        Font oldFont = g2.getFont();
        g2.setFont(oldFont.deriveFont(Font.BOLD, 12f));
        String title = IntelWarfareSystem.CONTACT_STATION.equals(base.typeId)
                ? def.name : def.name + " - " + PlayerRegistry.name(base.playerId);
        String summary = conciseStatus(base, def);
        int width = Math.max(g2.getFontMetrics().stringWidth(title), g2.getFontMetrics().stringWidth(summary)) + 18;
        int x = (int)Math.round(base.x - width / 2.0);
        int y = (int)Math.round(base.y + radius + 20);
        g2.setColor(new Color(0, 0, 0, focused ? 205 : 180));
        g2.fillRoundRect(x, y, width, 36, 9, 9);
        g2.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), focused ? 230 : 180));
        g2.drawRoundRect(x, y, width, 36, 9, 9);
        g2.setColor(playerColor);
        g2.drawString(title, x + 9, y + 14);
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
        g2.setColor(new Color(220, 234, 242));
        g2.drawString(summary, x + 9, y + 29);
        g2.setFont(oldFont);
    }

    private static String conciseStatus(Base base, BaseType def) {
        String warning = criticalWarning(base, def);
        if (warning != null) return warning;
        ProductionJob job = ProductionQueueScheduler.active(base);
        if (job != null) return clip("Producing " + ProductionSystem.displayName(job), 42);
        if (base.logisticsStatus != null && !base.logisticsStatus.isBlank()) return clip(base.logisticsStatus, 42);
        StationFuelRequirement req = StationFuelRules.requirement(base.typeId);
        if (req != null) {
            double fuel = base.inventory.getOrDefault(req.material(), 0.0);
            return "Operational | Fuel " + Calc.round(fuel);
        }
        return "Operational";
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

    private static void drawInspector(Graphics2D worldGraphics, Base base, BaseType def,
                                      Color playerColor, String warning) {
        Component target = surface;
        if (target == null || target.getWidth() < 480 || target.getHeight() < 300) return;

        Graphics2D g2 = (Graphics2D) worldGraphics.create();
        try {
            AffineTransform screenTransform = target.getGraphicsConfiguration() == null
                    ? new AffineTransform() : target.getGraphicsConfiguration().getDefaultTransform();
            g2.setTransform(screenTransform);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            List<String> inventoryRows = ResourceText.lines(base.inventory);
            int inventoryColumns = inventoryRows.size() > 8 ? 2 : 1;
            int inventoryPerColumn = inventoryColumns == 1 ? inventoryRows.size() : (inventoryRows.size() + 1) / 2;
            int inventoryLines = Math.min(10, inventoryPerColumn);
            int queueLines = Math.min(4, base.productionQueue.size());
            int panelWidth = inventoryColumns == 2 ? 430 : 360;
            int panelHeight = 252 + inventoryLines * 15 + queueLines * 15;
            panelHeight = Math.min(panelHeight, target.getHeight() - 176);
            int x = 14;
            int y = 156;

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
            if (base.productionQueue.isEmpty()) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
                g2.setColor(MUTED);
                g2.drawString("Idle", x + 16, cursor);
                cursor += 15;
            } else {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
                for (int i = 0; i < queueLines && cursor < y + panelHeight - 46; i++) {
                    ProductionJob queued = base.productionQueue.get(i);
                    String prefix = i == 0 ? "ACTIVE  " : (i + 1) + ".  ";
                    String row = prefix + ProductionSystem.displayName(queued);
                    if (i == 0) row += " | " + ProductionQueueScheduler.detail(base, queued);
                    g2.setColor(i == 0 ? PRODUCTION : MUTED);
                    g2.drawString(clipToWidth(g2, row, panelWidth - 32), x + 16, cursor);
                    cursor += 15;
                }
                if (base.productionQueue.size() > queueLines && cursor < y + panelHeight - 46) {
                    g2.setColor(MUTED);
                    g2.drawString("+" + (base.productionQueue.size() - queueLines) + " queued", x + 16, cursor);
                    cursor += 15;
                }
            }

            if (cursor < y + panelHeight - 28) {
                g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
                g2.setColor(TEXT);
                g2.drawString("HANGAR", x + 14, cursor);
                cursor += 15;
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
                drawInventory(g2, inventoryRows, x + 16, cursor, panelWidth - 32,
                        Math.max(0, y + panelHeight - cursor - 10));
            }
        } finally {
            g2.dispose();
        }
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
        g2.drawString(clipToWidth(g2, role, maxWidth), x, y + 45);
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
        if (rows.size() > shown && maxRowsPerColumn > 0) {
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

    private record Presentation(boolean hovered, boolean focused) { }
}
