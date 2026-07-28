package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

final class Base {
    final String id;
    final String playerId;
    final String typeId;
    final double x, y;
    final EnumMap<Material, Double> inventory = new EnumMap<>(Material.class);
    final List<ProductionJob> productionQueue = new ArrayList<>();
    long nextProductionJobId = 1;
    String logisticsStatus = "";
    double hp, shield, shieldDelayTimer;

    Base(String id, String playerId, String typeId, double x, double y) {
        this.id = id; this.playerId = playerId; this.typeId = typeId; this.x = x; this.y = y;
        this.hp = type().maxHp;
        this.shield = type().maxShield;
    }

    BaseType type() { return Rules.base(typeId); }

    boolean contains(double wx, double wy) {
        return Calc.distance(wx, wy, x, y) <= radius();
    }

    void draw(Graphics2D g2, Color ignoredColor, EnumMap<Material, Double> ignoredStockpile, boolean ignoredLocal) {
        Color playerColor = PlayerRegistry.color(playerId);
        boolean local = PlayerRegistry.isLocal(playerId);
        Graphics2D s = (Graphics2D) g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BaseType def = type();
        double radius = radius();
        s.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), local ? 42 : 22));
        s.fillOval((int)(x - def.unloadRange), (int)(y - def.unloadRange), (int)(def.unloadRange * 2), (int)(def.unloadRange * 2));
        s.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), local ? 120 : 72));
        s.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{10f,8f}, 0));
        s.drawOval((int)(x - def.unloadRange), (int)(y - def.unloadRange), (int)(def.unloadRange * 2), (int)(def.unloadRange * 2));
        Polygon hull = new Polygon();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 6 + i * Math.PI * 2 / 6.0;
            hull.addPoint((int)Math.round(x + Math.cos(a) * radius), (int)Math.round(y + Math.sin(a) * radius));
        }
        s.setColor(new Color(20,29,42)); s.fillPolygon(hull);
        s.setColor(playerColor); s.setStroke(new BasicStroke(3f)); s.drawPolygon(hull);
        drawCore(s, playerColor);
        s.setFont(s.getFont().deriveFont(Font.BOLD, 12f));
        drawBars(s, def, radius);
        drawLabel(s, def, radius, playerColor);
        drawFuelState(s, radius);
        drawLogistics(s, radius);
        drawProduction(s, radius);
        if (local) drawHangar(s, radius);
        s.dispose();
    }

    private double radius() {
        return switch (typeId) {
            case "shipyard" -> 82;
            case "laboratory", "manufacturing" -> 74;
            case RadarTowerRules.TIER_ONE -> 54;
            case RadarTowerRules.TIER_TWO -> 66;
            case RadarTowerRules.TIER_THREE -> 78;
            default -> 64;
        };
    }

    private void drawCore(Graphics2D s, Color playerColor) {
        int radarTier = RadarTowerRules.tierNumber(typeId);
        if (radarTier > 0) {
            drawRadarCore(s, playerColor, radarTier);
            return;
        }
        if ("laboratory".equals(typeId)) {
            s.setColor(new Color(80, 230, 255, 80));
            s.fillOval((int)(x - 30), (int)(y - 30), 60, 60);
            s.setColor(new Color(240, 255, 255, 120));
            s.drawLine((int)(x - 22), (int)y, (int)(x + 22), (int)y);
            s.drawLine((int)x, (int)(y - 22), (int)x, (int)(y + 22));
            return;
        }
        if ("manufacturing".equals(typeId)) {
            s.setColor(new Color(255, 185, 90, 90));
            s.fillRect((int)(x - 28), (int)(y - 22), 56, 44);
            s.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 150));
            s.drawRect((int)(x - 28), (int)(y - 22), 56, 44);
            return;
        }
        s.setColor(new Color(125,205,255,90));
        s.fillOval((int)(x - 26), (int)(y - 26), 52, 52);
    }

    private void drawRadarCore(Graphics2D s, Color playerColor, int tier) {
        double time = System.nanoTime() / 1_000_000_000.0;
        double spin = time * (0.62 + tier * 0.18);
        double pulse = (time * (0.42 + tier * 0.05)) % 1.0;
        double platformRadius = 20 + tier * 5;
        double sweepRadius = 38 + tier * 10;

        for (int ring = 0; ring < tier + 1; ring++) {
            double phase = (pulse + ring / (double)(tier + 1)) % 1.0;
            double r = platformRadius + 8 + phase * (18 + tier * 5);
            int alpha = (int)Math.round(85 * (1.0 - phase));
            s.setColor(new Color(75, 225, 255, Math.max(0, alpha)));
            s.setStroke(new BasicStroke((float)Math.max(0.8, 2.1 - phase)));
            s.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }

        s.setColor(new Color(5, 18, 28, 235));
        s.fill(new Ellipse2D.Double(x - platformRadius, y - platformRadius * 0.72,
                platformRadius * 2, platformRadius * 1.44));
        s.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 210));
        s.setStroke(new BasicStroke(2.2f));
        s.draw(new Ellipse2D.Double(x - platformRadius, y - platformRadius * 0.72,
                platformRadius * 2, platformRadius * 1.44));

        s.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        s.setColor(new Color(72, 92, 112));
        s.drawLine((int)Math.round(x), (int)Math.round(y + 21), (int)Math.round(x), (int)Math.round(y - 18 - tier * 3));
        s.setStroke(new BasicStroke(2f));
        s.setColor(new Color(150, 235, 255));
        s.drawLine((int)Math.round(x), (int)Math.round(y + 17), (int)Math.round(x), (int)Math.round(y - 20 - tier * 3));

        Graphics2D radar = (Graphics2D)s.create();
        radar.translate(x, y - 18 - tier * 3);
        radar.rotate(spin);
        radar.setColor(new Color(70, 230, 255, 24 + tier * 8));
        radar.fill(new Arc2D.Double(-sweepRadius, -sweepRadius, sweepRadius * 2, sweepRadius * 2,
                -24, 48, Arc2D.PIE));
        radar.setColor(new Color(115, 240, 255, 190));
        radar.setStroke(new BasicStroke(1.8f));
        radar.drawLine(0, 0, (int)Math.round(sweepRadius), 0);

        int arms = 2 + tier;
        for (int i = 0; i < arms; i++) {
            double angle = i * Math.PI * 2.0 / arms;
            double armLength = 19 + tier * 5 + (i % 2) * 5;
            double ax = Math.cos(angle) * armLength;
            double ay = Math.sin(angle) * armLength;
            radar.setColor(new Color(145, 175, 195, 225));
            radar.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            radar.drawLine(0, 0, (int)Math.round(ax), (int)Math.round(ay));
            radar.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 230));
            double node = 4.0 + tier * 0.8;
            radar.fill(new Ellipse2D.Double(ax - node, ay - node, node * 2, node * 2));
            radar.setColor(new Color(210, 250, 255, 230));
            radar.draw(new Ellipse2D.Double(ax - node, ay - node, node * 2, node * 2));
        }

        double dishWidth = 30 + tier * 8;
        double dishHeight = 16 + tier * 3;
        Path2D dish = new Path2D.Double();
        dish.moveTo(-dishWidth * 0.5, -2);
        dish.quadTo(0, dishHeight, dishWidth * 0.5, -2);
        dish.quadTo(0, dishHeight * 0.45, -dishWidth * 0.5, -2);
        dish.closePath();
        radar.setColor(new Color(72, 96, 118, 245));
        radar.fill(dish);
        radar.setColor(new Color(175, 238, 255, 235));
        radar.setStroke(new BasicStroke(1.7f));
        radar.draw(dish);
        radar.setColor(new Color(235, 255, 255));
        radar.fill(new Ellipse2D.Double(-3.2, -3.2, 6.4, 6.4));
        radar.dispose();

        if (tier >= 2) {
            Graphics2D orbit = (Graphics2D)s.create();
            orbit.translate(x, y);
            orbit.rotate(-spin * 0.42);
            double orbitRadius = 33 + tier * 7;
            orbit.setColor(new Color(90, 220, 255, 90));
            orbit.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[]{5f, 5f}, 0));
            orbit.draw(new Ellipse2D.Double(-orbitRadius, -orbitRadius * 0.52,
                    orbitRadius * 2, orbitRadius * 1.04));
            for (int i = 0; i < tier; i++) {
                double angle = i * Math.PI * 2.0 / tier;
                double ox = Math.cos(angle) * orbitRadius;
                double oy = Math.sin(angle) * orbitRadius * 0.52;
                orbit.setColor(new Color(100, 240, 255, 220));
                orbit.fill(new Ellipse2D.Double(ox - 3.5, oy - 3.5, 7, 7));
            }
            orbit.dispose();
        }

        double coreGlow = 8 + Math.sin(time * 3.0) * 1.5;
        s.setColor(new Color(90, 235, 255, 70));
        s.fill(new Ellipse2D.Double(x - coreGlow, y - coreGlow, coreGlow * 2, coreGlow * 2));
        s.setColor(new Color(225, 255, 255, 235));
        s.fill(new Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7));
    }

    private void drawBars(Graphics2D s, BaseType def, double radius) {
        int w = 58;
        int px = (int)(x - w / 2.0);
        int py = (int)(y - radius - 49);
        if (def.maxShield > 0) {
            s.setColor(new Color(20,20,20));
            s.fillRect(px, py, w, 5);
            s.setColor(new Color(80,180,255));
            s.fillRect(px, py, (int)(w * Math.max(0, shield) / Math.max(1, def.maxShield)), 5);
            py += 7;
        }
        s.setColor(new Color(20,20,20));
        s.fillRect(px, py, w, 6);
        s.setColor(new Color(80,230,90));
        s.fillRect(px, py, (int)(w * Math.max(0, hp) / Math.max(1, def.maxHp)), 6);
    }

    private void drawLabel(Graphics2D s, BaseType def, double radius, Color playerColor) {
        String label = def.name + " - " + PlayerRegistry.name(playerId);
        int tw = s.getFontMetrics().stringWidth(label);
        s.setColor(new Color(0,0,0,160));
        s.fillRoundRect((int)(x - tw / 2.0 - 6), (int)(y - radius - 32), tw + 12, 18, 8, 8);
        s.setColor(playerColor);
        s.drawString(label, (int)(x - tw / 2.0), (int)(y - radius - 18));
    }

    private void drawFuelState(Graphics2D s, double radius) {
        StationFuelRequirement req = StationFuelRules.requirement(typeId);
        if (req == null) return;
        double fuel = inventory.getOrDefault(req.material(), 0.0);
        boolean powered = StationFuelRules.isOperational(this);
        String label = powered ? "Fuel " + Calc.round(fuel) + " | " + Calc.round(req.perSecond()) + "/s" : "NO FUEL";
        int tw = s.getFontMetrics().stringWidth(label);
        int px = (int)(x - tw / 2.0 - 6);
        int py = (int)(y - radius - 72);
        s.setColor(new Color(0,0,0,165));
        s.fillRoundRect(px, py, tw + 12, 18, 8, 8);
        s.setColor(powered ? new Color(255, 210, 110) : new Color(255, 95, 80));
        s.drawString(label, px + 6, py + 13);
    }

    private void drawLogistics(Graphics2D s, double radius) {
        if (logisticsStatus == null || logisticsStatus.isBlank()) return;
        String label = logisticsStatus.length() > 44 ? logisticsStatus.substring(0, 41) + "..." : logisticsStatus;
        int tw = s.getFontMetrics().stringWidth(label);
        int px = (int)(x - tw / 2.0 - 6);
        int py = (int)(y - radius - 94);
        s.setColor(new Color(0,0,0,170));
        s.fillRoundRect(px, py, tw + 12, 18, 8, 8);
        s.setColor(new Color(140, 225, 255));
        s.drawString(label, px + 6, py + 13);
    }

    private void drawProduction(Graphics2D s, double radius) {
        ProductionJob job = ProductionQueueScheduler.active(this);
        if (job == null) return;
        String label = ProductionSystem.displayName(job) + " | " + ProductionQueueScheduler.detail(this, job);
        if (productionQueue.size() > 1) label += " | +" + (productionQueue.size() - 1);
        if (label.length() > 48) label = label.substring(0, 45) + "...";
        int tw = s.getFontMetrics().stringWidth(label);
        int w = Math.max(94, tw + 12);
        int px = (int)(x - w / 2.0);
        int py = (int)(y - radius - 116);
        s.setColor(new Color(0,0,0,178));
        s.fillRoundRect(px, py, w, 24, 8, 8);
        s.setColor(new Color(255, 205, 105));
        s.drawString(label, px + 6, py + 13);
        s.setColor(new Color(30, 35, 42));
        s.fillRect(px + 5, py + 17, w - 10, 4);
        s.setColor(job.blockedReason == null || job.blockedReason.isBlank() ? new Color(110, 230, 150) : new Color(255, 165, 75));
        s.fillRect(px + 5, py + 17, (int)Math.round((w - 10) * Math.max(0, Math.min(1, job.progress()))), 4);
    }

    private void drawHangar(Graphics2D s, double radius) {
        List<String> rows = ResourceText.lines(inventory);
        int rowCount = Math.max(1, Math.min(6, rows.size()));
        int w = 150;
        int h = 22 + rowCount * 15;
        int px = (int)(x - w / 2.0);
        int py = (int)(y + radius + 12);
        s.setColor(new Color(0,0,0,170));
        s.fillRoundRect(px, py, w, h, 8, 8);
        s.setColor(new Color(220, 238, 250));
        s.drawString("Hangar", px + 8, py + 14);
        if (rows.isEmpty()) {
            s.drawString("empty", px + 16, py + 30);
            return;
        }
        int line = py + 30;
        for (int i = 0; i < rowCount; i++) {
            s.drawString(rows.get(i), px + 16, line);
            line += 15;
        }
    }
}
