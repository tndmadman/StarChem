package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.Map;

final class ResourceNode {
    final int id;
    final String name;
    final NodeKind kind;
    final Material material;
    final double maxAmount;
    final double harvestRate;
    final double radius;
    double x, y, amount, respawnTimer;
    double orbitCenterX, orbitCenterY, orbitRadius, orbitAngle, orbitSpeed;
    boolean active = true;
    boolean orbiting;

    ResourceNode(int id, String name, NodeKind kind, Material material, double x, double y, double maxAmount, double harvestRate, double radius) {
        this.id = id; this.name = name; this.kind = kind; this.material = material; this.x = x; this.y = y;
        this.maxAmount = maxAmount; this.harvestRate = harvestRate; this.radius = radius; this.amount = maxAmount;
    }

    /** Convenience shape used by generic JSON-authored event resource spawns. */
    ResourceNode(int id, NodeKind kind, double x, double y, double radius, double initialAmount,
                 Map<Material,Double> baseYield, double harvestRate) {
        this(id, eventMaterial(baseYield).label, kind, eventMaterial(baseYield), x, y,
                initialAmount, harvestRate, radius);
    }

    private static Material eventMaterial(Map<Material,Double> baseYield) {
        if (baseYield == null || baseYield.isEmpty()) return Material.RARE_EARTHS;
        Material best = null;
        double bestWeight = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Material,Double> entry : baseYield.entrySet()) {
            if (entry.getKey() == null) continue;
            double weight = entry.getValue() == null || !Double.isFinite(entry.getValue()) ? 0 : entry.getValue();
            if (best == null || weight > bestWeight) {
                best = entry.getKey();
                bestWeight = weight;
            }
        }
        return best == null ? Material.RARE_EARTHS : best;
    }

    void orbit(double centerX, double centerY, double orbitRadius, double orbitAngle, double orbitSpeed) {
        this.orbitCenterX = Double.isFinite(centerX) ? centerX : x;
        this.orbitCenterY = Double.isFinite(centerY) ? centerY : y;
        double normalizedRadius = Double.isFinite(orbitRadius) ? orbitRadius : 0;
        double normalizedAngle = Double.isFinite(orbitAngle) ? orbitAngle : 0;
        if (normalizedRadius < 0) {
            normalizedRadius = -normalizedRadius;
            normalizedAngle += Math.PI;
        }
        this.orbitRadius = normalizedRadius;
        this.orbitAngle = normalizedAngle;
        this.orbitSpeed = Double.isFinite(orbitSpeed) ? orbitSpeed : 0;
        this.orbiting = true;
        updateOrbit(0);
    }

    void updateOrbit(double centerX, double centerY, double dt) {
        orbitCenterX = centerX;
        orbitCenterY = centerY;
        updateOrbit(dt);
    }

    void updateOrbit(double dt) {
        if (!active || !orbiting) return;
        orbitAngle += orbitSpeed * dt;
        x = orbitCenterX + Math.cos(orbitAngle) * orbitRadius;
        y = orbitCenterY + Math.sin(orbitAngle) * orbitRadius;
    }

    void deplete() { active = false; amount = 0; respawnTimer = Rules.RESOURCE_RESPAWN.respawnDelaySeconds; }

    void updateRespawn(double dt, World world) {
        if (active) return;
        respawnTimer -= dt * SystemModifierRules.resourceRespawn(world);
        if (respawnTimer <= 0) world.relocateResource(this);
    }

    void draw(Graphics2D g2, boolean selected) {
        if (!active || g2 == null) return;
        double vr = visualRadius();
        if (!RenderCulling.visible(g2, x, y, vr + (selected ? 18 : 10))) return;
        Graphics2D r = (Graphics2D)g2.create();
        r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (kind == NodeKind.GAS_CLOUD) drawGas(r, selected); else drawRock(r, selected);
        drawAmountBar(r);
        r.dispose();
    }

    private void drawRock(Graphics2D g2, boolean selected) {
        double visualRadius = visualRadius();
        Polygon poly = new Polygon();
        for (int i = 0; i < 9; i++) {
            double a = -Math.PI / 2 + i * Math.PI * 2 / 9;
            double wobble = 0.78 + (Math.floorMod(id * 31 + i * 17, 30) / 100.0);
            poly.addPoint((int)Math.round(x + Math.cos(a) * visualRadius * wobble),
                    (int)Math.round(y + Math.sin(a) * visualRadius * wobble));
        }
        g2.setColor(new Color(70, 68, 63)); g2.fillPolygon(poly);
        g2.setColor(material.color); g2.setStroke(new BasicStroke(1f)); g2.drawPolygon(poly);
        g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 85));
        g2.fillOval((int)(x - visualRadius * .45), (int)(y - visualRadius * .45),
                (int)visualRadius, (int)visualRadius);
        if (selected) drawSelected(g2, visualRadius);
    }

    private void drawGas(Graphics2D g2, boolean selected) {
        double visualRadius = visualRadius();
        for (int i = 0; i < 7; i++) {
            double a = i * Math.PI * 2 / 7.0;
            double ox = Math.cos(a) * visualRadius * .25, oy = Math.sin(a) * visualRadius * .22;
            g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 42 + i * 8));
            g2.fillOval((int)(x + ox - visualRadius * .55), (int)(y + oy - visualRadius * .42),
                    (int)(visualRadius * 1.1), (int)(visualRadius * .84));
        }
        g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 150));
        g2.setStroke(new BasicStroke(1f));
        g2.drawOval((int)(x - visualRadius * .8), (int)(y - visualRadius * .62),
                (int)(visualRadius * 1.6), (int)(visualRadius * 1.24));
        if (selected) drawSelected(g2, visualRadius);
    }

    private void drawSelected(Graphics2D g2, double visualRadius) {
        g2.setColor(new Color(255, 245, 140, 210));
        g2.setStroke(new BasicStroke(1.8f));
        g2.drawOval((int)(x - visualRadius - 8), (int)(y - visualRadius - 8),
                (int)(visualRadius * 2 + 16), (int)(visualRadius * 2 + 16));
    }

    private void drawAmountBar(Graphics2D g2) {
        double visualRadius = visualRadius();
        int w = Math.max(12, (int)Math.round(visualRadius * 6.0)), h = 3;
        int bx = (int)(x - w / 2.0), by = (int)(y + visualRadius + 4);
        double pct = amountPercent();
        g2.setColor(new Color(0, 0, 0, 130)); g2.fillRoundRect(bx, by, w, h, 3, 3);
        g2.setColor(material.color); g2.fillRoundRect(bx, by, (int)Math.round(w * pct), h, 3, 3);
    }

    private double visualRadius() {
        return radius * (0.38 + 0.62 * Math.sqrt(amountPercent()));
    }

    private double amountPercent() {
        if (maxAmount <= 0) return 0;
        return Math.max(0, Math.min(1, amount / maxAmount));
    }
}
