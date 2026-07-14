package com.tndmadman.rts;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Vector artwork used by the resource and system catalog. */
final class CatalogVisuals {
    private static final Color TEXT = new Color(229, 243, 252);
    private static final Color MUTED = new Color(166, 197, 220);
    private static final Color PANEL = new Color(11, 22, 36);
    private static final Color PANEL_2 = new Color(6, 13, 23);
    private static final Color BORDER = new Color(63, 112, 148);

    private CatalogVisuals() { }

    static Icon materialIcon(Material material, int size) {
        return new VectorIcon(size, size) {
            @Override protected void paint(Graphics2D g, int x, int y, int width, int height) {
                drawMaterialBadge(g, material, x, y, Math.min(width, height));
            }
        };
    }

    static Icon systemIcon(ResourceSystemCatalog.SystemEntry system, int size) {
        return new VectorIcon(size, size) {
            @Override protected void paint(Graphics2D g, int x, int y, int width, int height) {
                int s = Math.min(width, height);
                int cx = x + s / 2;
                int cy = y + s / 2;
                g.setColor(new Color(12, 25, 42));
                g.fillOval(x, y, s, s);
                g.setColor(new Color(80, 135, 176));
                g.drawOval(x, y, s - 1, s - 1);
                g.setColor(new Color(112, 157, 190, 150));
                g.drawOval(cx - s / 3, cy - s / 3, s * 2 / 3, s * 2 / 3);
                g.drawOval(cx - s / 5, cy - s / 5, s * 2 / 5, s * 2 / 5);
                drawStar(g, cx, cy, Math.max(4, s / 9));
                if (system != null && !system.spawns().isEmpty()) {
                    Color color = firstMaterialColor(system.spawns().get(0));
                    g.setColor(withAlpha(color, 220));
                    g.setStroke(new BasicStroke(Math.max(2f, s / 16f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawArc(cx - s / 3, cy - s / 3, s * 2 / 3, s * 2 / 3, 205, 115);
                }
            }
        };
    }

    static final class ResourcePreview extends JComponent {
        private ResourceSystemCatalog.Entry entry;

        ResourcePreview() {
            setPreferredSize(new Dimension(560, 218));
            setMinimumSize(new Dimension(360, 190));
            setOpaque(false);
        }

        void setEntry(ResourceSystemCatalog.Entry entry) {
            this.entry = entry;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics2D(graphics);
            int w = getWidth();
            int h = getHeight();
            paintCard(g, 0, 0, w, h);
            if (entry == null) {
                drawCentered(g, "Select a resource or item", w / 2, h / 2, 15f, MUTED);
                g.dispose();
                return;
            }

            Material material = entry.material();
            int badge = Math.min(112, h - 74);
            drawMaterialBadge(g, material, 22, 26, badge);

            int textX = 22 + badge + 22;
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
            g.drawString(material.label, textX, 53);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            g.setColor(MUTED);
            g.drawString(material.name(), textX, 72);
            int chipX = textX;
            chipX += drawChip(g, title(material.family.name()), chipX, 86, material.color) + 7;
            chipX += drawChip(g, title(material.tier.name()), chipX, 86, new Color(104, 161, 205)) + 7;
            drawChip(g, entry.sourceLabel(), chipX, 86, new Color(83, 137, 106));

            List<Usage> usages = usageFor(material);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(new Color(203, 226, 244));
            g.drawString("USED BY SHIPS & STATIONS", textX, 126);
            drawUsageCards(g, usages, textX, 137, Math.max(80, w - textX - 18), h - 148);
            g.dispose();
        }
    }

    static final class SystemPreview extends JComponent {
        private ResourceSystemCatalog.SystemEntry system;

        SystemPreview() {
            setPreferredSize(new Dimension(560, 286));
            setMinimumSize(new Dimension(360, 240));
            setOpaque(false);
        }

        void setSystem(ResourceSystemCatalog.SystemEntry system) {
            this.system = system;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics2D(graphics);
            int w = getWidth();
            int h = getHeight();
            paintCard(g, 0, 0, w, h);
            if (system == null) {
                drawCentered(g, "Select a system", w / 2, h / 2, 15f, MUTED);
                g.dispose();
                return;
            }

            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            g.drawString(system.name(), 18, 30);
            drawChip(g, title(system.role()), Math.max(18, w - 145), 12, roleColor(system.role()));

            int legendWidth = Math.max(190, Math.min(260, w / 3));
            int diagramX = 12;
            int diagramY = 42;
            int diagramW = Math.max(170, w - legendWidth - 28);
            int diagramH = h - 54;
            drawOrbitMap(g, system, diagramX, diagramY, diagramW, diagramH);
            drawSpawnLegend(g, system, w - legendWidth, 50, legendWidth - 12, h - 62);
            g.dispose();
        }
    }

    private static void drawOrbitMap(Graphics2D g, ResourceSystemCatalog.SystemEntry system,
                                     int x, int y, int width, int height) {
        int size = Math.max(130, Math.min(width, height) - 12);
        int cx = x + width / 2;
        int cy = y + height / 2;
        double maxOrbit = 1;
        for (ResourceSystemCatalog.SpawnBand spawn : system.spawns()) {
            maxOrbit = Math.max(maxOrbit, spawn.orbit() + Math.abs(spawn.width()));
        }
        for (ResourceSystemCatalog.CelestialOrbit body : system.bodies()) {
            maxOrbit = Math.max(maxOrbit, body.orbitRadius());
        }
        double scale = (size * 0.47) / maxOrbit;

        g.setColor(new Color(4, 10, 18, 210));
        g.fill(new Ellipse2D.Double(cx - size / 2.0, cy - size / 2.0, size, size));
        g.setColor(new Color(44, 79, 108));
        g.draw(new Ellipse2D.Double(cx - size / 2.0, cy - size / 2.0, size, size));

        Stroke old = g.getStroke();
        for (ResourceSystemCatalog.CelestialOrbit body : system.bodies()) {
            if (body.orbitRadius() <= 0) continue;
            int radius = Math.max(7, (int)Math.round(body.orbitRadius() * scale));
            g.setColor(new Color(103, 145, 176, 58));
            g.setStroke(new BasicStroke(1f));
            g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }

        for (ResourceSystemCatalog.SpawnBand spawn : system.spawns()) {
            int radius = Math.max(8, (int)Math.round(spawn.orbit() * scale));
            double degrees = Math.max(12, Math.min(360, Math.toDegrees(Math.abs(spawn.arc()))));
            double start = Math.floorMod(spawn.name().hashCode(), 360);
            Color belt = firstMaterialColor(spawn);
            float stroke = (float)Math.max(2.5, Math.min(10, 2.5 + Math.abs(spawn.width()) * scale * 0.12));
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(withAlpha(belt, 180));
            g.draw(new Arc2D.Double(cx - radius, cy - radius, radius * 2.0, radius * 2.0,
                    start, degrees, Arc2D.OPEN));
        }

        for (ResourceSystemCatalog.CelestialOrbit body : system.bodies()) {
            if (body.orbitRadius() <= 0) continue;
            int radius = Math.max(7, (int)Math.round(body.orbitRadius() * scale));
            double angle = Math.toRadians(Math.floorMod(body.id().hashCode(), 360));
            int px = (int)Math.round(cx + Math.cos(angle) * radius);
            int py = (int)Math.round(cy - Math.sin(angle) * radius);
            int bodySize = Math.max(5, Math.min(14, (int)Math.round(4 + Math.sqrt(Math.max(0, body.radius())) * 0.42)));
            Color bodyColor = colorFromHash(body.id());
            g.setColor(withAlpha(bodyColor, 70));
            g.fillOval(px - bodySize, py - bodySize, bodySize * 2, bodySize * 2);
            g.setColor(bodyColor);
            g.fillOval(px - bodySize / 2, py - bodySize / 2, bodySize, bodySize);
        }
        g.setStroke(old);
        drawStar(g, cx, cy, Math.max(10, size / 28));

        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        g.setColor(MUTED);
        g.drawString("MAX ORBIT " + number(maxOrbit) + "u", x + 7, y + height - 7);
    }

    private static void drawSpawnLegend(Graphics2D g, ResourceSystemCatalog.SystemEntry system,
                                        int x, int y, int width, int height) {
        g.setColor(new Color(7, 15, 25, 225));
        g.fillRoundRect(x, y, width, height, 12, 12);
        g.setColor(BORDER);
        g.drawRoundRect(x, y, width, height, 12, 12);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(new Color(203, 226, 244));
        g.drawString("SPAWN ORBITS", x + 12, y + 19);

        int rowY = y + 28;
        int rowHeight = 41;
        int limit = Math.max(1, (height - 36) / rowHeight);
        int shown = Math.min(limit, system.spawns().size());
        for (int i = 0; i < shown; i++) {
            ResourceSystemCatalog.SpawnBand spawn = system.spawns().get(i);
            Material primary = spawn.materials().isEmpty() ? Material.IRON : spawn.materials().get(0);
            drawMaterialBadge(g, primary, x + 10, rowY + 4, 28);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(TEXT);
            g.drawString(trim(spawn.name(), 24), x + 45, rowY + 15);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            g.setColor(MUTED);
            g.drawString(number(spawn.orbit()) + "u  +/-" + number(spawn.width()) + "  "
                    + title(spawn.kind().name()), x + 45, rowY + 29);
            rowY += rowHeight;
        }
        if (system.spawns().isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(MUTED);
            g.drawString("No natural spawn bands", x + 12, rowY + 16);
        } else if (system.spawns().size() > shown) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.setColor(MUTED);
            g.drawString("+ " + (system.spawns().size() - shown) + " more belts below", x + 12, y + height - 10);
        }
    }

    private static void drawUsageCards(Graphics2D g, List<Usage> usages, int x, int y, int width, int height) {
        if (usages.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(MUTED);
            g.drawString("No configured ship or station build costs use this material.", x, y + 23);
            return;
        }
        int maxCards = Math.min(5, usages.size());
        int gap = 7;
        int cardWidth = Math.max(78, (width - gap * (maxCards - 1)) / maxCards);
        int cardHeight = Math.max(52, height);
        for (int i = 0; i < maxCards; i++) {
            Usage usage = usages.get(i);
            int cardX = x + i * (cardWidth + gap);
            g.setColor(new Color(15, 31, 48, 230));
            g.fillRoundRect(cardX, y, cardWidth, cardHeight, 9, 9);
            g.setColor(new Color(54, 94, 125));
            g.drawRoundRect(cardX, y, cardWidth, cardHeight, 9, 9);
            if (usage.ship()) drawShip(g, cardX + 9, y + 10, 30, usage.color());
            else drawStation(g, cardX + 9, y + 9, 30, usage.color());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            g.setColor(TEXT);
            g.drawString(trim(usage.name(), 13), cardX + 43, y + 18);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
            g.setColor(MUTED);
            g.drawString((usage.ship() ? "SHIP" : "STATION") + "  x" + number(usage.amount()), cardX + 43, y + 34);
        }
    }

    private static List<Usage> usageFor(Material material) {
        List<Usage> out = new ArrayList<>();
        for (ShipType ship : Rules.SHIPS.values()) {
            double amount = costAmount(ship.buildCost, material);
            if (amount > 0) out.add(new Usage(ship.name, true, amount, colorFromHash(ship.id)));
        }
        for (BaseType base : Rules.BASES.values()) {
            double amount = costAmount(base.buildCost, material);
            if (amount > 0) out.add(new Usage(base.name, false, amount, colorFromHash(base.id)));
        }
        out.sort(Comparator.comparingDouble(Usage::amount).reversed());
        return out;
    }

    private static double costAmount(List<Cost> costs, Material material) {
        if (costs == null) return 0;
        for (Cost cost : costs) if (cost.material() == material) return cost.amount();
        return 0;
    }

    private static void drawMaterialBadge(Graphics2D g, Material material, int x, int y, int size) {
        Material safe = material == null ? Material.IRON : material;
        Color color = safe.color;
        g.setPaint(new GradientPaint(x, y, brighten(color, 1.25), x + size, y + size, darken(color, 0.55)));
        g.fillRoundRect(x, y, size, size, Math.max(8, size / 4), Math.max(8, size / 4));
        g.setColor(withAlpha(Color.WHITE, 145));
        g.drawRoundRect(x, y, size - 1, size - 1, Math.max(8, size / 4), Math.max(8, size / 4));
        drawMaterialGlyph(g, safe, x, y, size);
    }

    private static void drawMaterialGlyph(Graphics2D g, Material material, int x, int y, int size) {
        Color ink = contrast(material.color);
        g.setColor(withAlpha(ink, 225));
        int p = Math.max(3, size / 7);
        int innerX = x + p;
        int innerY = y + p;
        int inner = size - p * 2;
        switch (material.family) {
            case GAS, VOLATILE -> {
                g.fillOval(innerX, innerY + inner / 3, inner / 2, inner / 2);
                g.fillOval(innerX + inner / 3, innerY + inner / 6, inner / 2, inner / 2);
                g.fillOval(innerX + inner / 2, innerY + inner / 3, inner / 2, inner / 2);
            }
            case ELECTRONIC -> {
                g.fillRoundRect(innerX + inner / 5, innerY + inner / 5, inner * 3 / 5, inner * 3 / 5, 3, 3);
                g.setStroke(new BasicStroke(Math.max(1.5f, size / 18f)));
                for (int i = 1; i <= 3; i++) {
                    int py = innerY + i * inner / 4;
                    g.drawLine(innerX, py, innerX + inner / 5, py);
                    g.drawLine(innerX + inner * 4 / 5, py, innerX + inner, py);
                }
            }
            case POWER -> {
                Polygon bolt = new Polygon();
                bolt.addPoint(innerX + inner * 3 / 5, innerY);
                bolt.addPoint(innerX + inner / 4, innerY + inner * 3 / 5);
                bolt.addPoint(innerX + inner / 2, innerY + inner * 3 / 5);
                bolt.addPoint(innerX + inner * 2 / 5, innerY + inner);
                bolt.addPoint(innerX + inner * 4 / 5, innerY + inner * 2 / 5);
                bolt.addPoint(innerX + inner * 3 / 5, innerY + inner * 2 / 5);
                g.fillPolygon(bolt);
            }
            case CHEMICAL -> {
                Polygon drop = new Polygon();
                drop.addPoint(innerX + inner / 2, innerY);
                drop.addPoint(innerX + inner / 6, innerY + inner * 3 / 5);
                drop.addPoint(innerX + inner / 3, innerY + inner);
                drop.addPoint(innerX + inner * 2 / 3, innerY + inner);
                drop.addPoint(innerX + inner * 5 / 6, innerY + inner * 3 / 5);
                g.fillPolygon(drop);
            }
            case WEAPON -> {
                Polygon missile = new Polygon();
                missile.addPoint(innerX + inner, innerY + inner / 2);
                missile.addPoint(innerX + inner / 3, innerY + inner / 4);
                missile.addPoint(innerX + inner / 3, innerY + inner * 3 / 4);
                g.fillPolygon(missile);
                g.fillRect(innerX, innerY + inner * 2 / 5, inner / 2, inner / 5);
            }
            case REFINED, ALLOY, COMPOSITE, INDUSTRIAL, CAPITAL -> {
                Polygon ingot = new Polygon();
                ingot.addPoint(innerX + inner / 5, innerY + inner / 4);
                ingot.addPoint(innerX + inner * 4 / 5, innerY + inner / 4);
                ingot.addPoint(innerX + inner, innerY + inner * 3 / 4);
                ingot.addPoint(innerX, innerY + inner * 3 / 4);
                g.fillPolygon(ingot);
                g.setColor(withAlpha(contrast(material.color), 100));
                g.drawLine(innerX + inner / 5, innerY + inner / 4, innerX + inner * 2 / 5, innerY + inner * 3 / 4);
            }
            case SALVAGE -> {
                g.setStroke(new BasicStroke(Math.max(2f, size / 11f)));
                g.drawOval(innerX + inner / 5, innerY + inner / 5, inner * 3 / 5, inner * 3 / 5);
                g.drawLine(innerX, innerY + inner / 2, innerX + inner, innerY + inner / 2);
                g.drawLine(innerX + inner / 2, innerY, innerX + inner / 2, innerY + inner);
            }
            default -> {
                Polygon rock = new Polygon();
                rock.addPoint(innerX + inner / 5, innerY + inner / 8);
                rock.addPoint(innerX + inner * 4 / 5, innerY);
                rock.addPoint(innerX + inner, innerY + inner / 2);
                rock.addPoint(innerX + inner * 3 / 4, innerY + inner);
                rock.addPoint(innerX + inner / 6, innerY + inner * 5 / 6);
                rock.addPoint(innerX, innerY + inner * 2 / 5);
                g.fillPolygon(rock);
                g.setColor(withAlpha(Color.WHITE, 90));
                g.drawLine(innerX + inner / 5, innerY + inner / 8, innerX + inner / 2, innerY + inner / 2);
            }
        }
    }

    private static void drawShip(Graphics2D g, int x, int y, int size, Color color) {
        Polygon ship = new Polygon();
        ship.addPoint(x + size, y + size / 2);
        ship.addPoint(x + size / 4, y + size / 5);
        ship.addPoint(x, y + size / 2);
        ship.addPoint(x + size / 4, y + size * 4 / 5);
        g.setColor(withAlpha(color, 210));
        g.fillPolygon(ship);
        g.setColor(withAlpha(Color.WHITE, 175));
        g.drawPolygon(ship);
        g.fillOval(x + size / 2, y + size * 2 / 5, size / 5, size / 5);
    }

    private static void drawStation(Graphics2D g, int x, int y, int size, Color color) {
        g.setColor(withAlpha(color, 210));
        g.fillRoundRect(x + size / 4, y + size / 4, size / 2, size / 2, 4, 4);
        g.fillRect(x, y + size * 2 / 5, size, size / 5);
        g.fillRect(x + size * 2 / 5, y, size / 5, size);
        g.setColor(withAlpha(Color.WHITE, 175));
        g.drawRoundRect(x + size / 4, y + size / 4, size / 2, size / 2, 4, 4);
    }

    private static void drawStar(Graphics2D g, int cx, int cy, int radius) {
        g.setPaint(new GradientPaint(cx - radius, cy - radius, new Color(255, 246, 178),
                cx + radius, cy + radius, new Color(255, 135, 38)));
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setColor(new Color(255, 224, 122, 65));
        g.fillOval(cx - radius * 2, cy - radius * 2, radius * 4, radius * 4);
        g.setColor(new Color(255, 255, 230, 210));
        g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
    }

    private static void paintCard(Graphics2D g, int x, int y, int width, int height) {
        g.setPaint(new GradientPaint(x, y, PANEL, x, y + height, PANEL_2));
        g.fill(new RoundRectangle2D.Double(x, y, Math.max(1, width - 1), Math.max(1, height - 1), 14, 14));
        g.setColor(BORDER);
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, Math.max(1, width - 2), Math.max(1, height - 2), 14, 14));
    }

    private static int drawChip(Graphics2D g, String text, int x, int y, Color color) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        int width = g.getFontMetrics().stringWidth(text) + 16;
        g.setColor(withAlpha(color, 90));
        g.fillRoundRect(x, y, width, 22, 11, 11);
        g.setColor(withAlpha(brighten(color, 1.35), 210));
        g.drawRoundRect(x, y, width, 22, 11, 11);
        g.setColor(TEXT);
        g.drawString(text, x + 8, y + 15);
        return width;
    }

    private static Graphics2D graphics2D(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static Color firstMaterialColor(ResourceSystemCatalog.SpawnBand spawn) {
        return spawn == null || spawn.materials().isEmpty() ? new Color(116, 166, 198) : spawn.materials().get(0).color;
    }

    private static Color roleColor(String role) {
        return colorFromHash(role == null ? "standard" : role);
    }

    private static Color colorFromHash(String value) {
        int hash = value == null ? 0 : value.hashCode();
        int r = 80 + Math.floorMod(hash, 130);
        int g = 95 + Math.floorMod(hash >>> 8, 120);
        int b = 110 + Math.floorMod(hash >>> 16, 110);
        return new Color(Math.min(235, r), Math.min(235, g), Math.min(235, b));
    }

    private static Color contrast(Color color) {
        double luma = color.getRed() * 0.299 + color.getGreen() * 0.587 + color.getBlue() * 0.114;
        return luma > 150 ? new Color(18, 28, 38) : Color.WHITE;
    }

    private static Color brighten(Color color, double factor) {
        return new Color(clamp((int)Math.round(color.getRed() * factor)),
                clamp((int)Math.round(color.getGreen() * factor)),
                clamp((int)Math.round(color.getBlue() * factor)));
    }

    private static Color darken(Color color, double factor) {
        return new Color(clamp((int)Math.round(color.getRed() * factor)),
                clamp((int)Math.round(color.getGreen() * factor)),
                clamp((int)Math.round(color.getBlue() * factor)));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static void drawCentered(Graphics2D g, String text, int cx, int cy, float size, Color color) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size)));
        g.setColor(color);
        g.drawString(text, cx - g.getFontMetrics().stringWidth(text) / 2, cy);
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "Standard";
        String[] parts = value.toLowerCase(Locale.ROOT).split("[_\\s-]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.0001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record Usage(String name, boolean ship, double amount, Color color) { }

    private abstract static class VectorIcon implements Icon {
        private final int width;
        private final int height;

        private VectorIcon(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override public int getIconWidth() { return width; }
        @Override public int getIconHeight() { return height; }

        @Override public final void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g = graphics2D(graphics);
            paint(g, x, y, width, height);
            g.dispose();
        }

        protected abstract void paint(Graphics2D g, int x, int y, int width, int height);
    }
}
