package com.tndmadman.rts;

import java.awt.*;

final class ResourceNode {
    final int id;
    final String name;
    final NodeKind kind;
    final Material material;
    final double maxAmount;
    final double harvestRate;
    final double radius;
    double x, y, amount, respawnTimer;
    double orbitRadius, orbitAngle, orbitSpeed;
    boolean active = true;
    boolean orbiting;

    ResourceNode(int id, String name, NodeKind kind, Material material, double x, double y, double maxAmount, double harvestRate, double radius) {
        this.id = id; this.name = name; this.kind = kind; this.material = material; this.x = x; this.y = y;
        this.maxAmount = maxAmount; this.harvestRate = harvestRate; this.radius = radius; this.amount = maxAmount;
    }

    void orbit(double centerX, double centerY, double orbitRadius, double orbitAngle, double orbitSpeed) {
        this.orbitRadius = orbitRadius;
        this.orbitAngle = orbitAngle;
        this.orbitSpeed = orbitSpeed;
        this.orbiting = true;
        updateOrbit(centerX, centerY, 0);
    }

    void updateOrbit(double centerX, double centerY, double dt) {
        if (!active || !orbiting) return;
        orbitAngle += orbitSpeed * dt;
        x = centerX + Math.cos(orbitAngle) * orbitRadius;
        y = centerY + Math.sin(orbitAngle) * orbitRadius;
    }

    void deplete() { active = false; amount = 0; respawnTimer = Rules.RESOURCE_RESPAWN.respawnDelaySeconds; }

    void updateRespawn(double dt, World world) {
        if (active) return;
        respawnTimer -= dt;
        if (respawnTimer <= 0) world.relocateResource(this);
    }

    void draw(Graphics2D g2, boolean selected) {
        if (!active) return;
        Graphics2D r = (Graphics2D) g2.create();
        r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (kind == NodeKind.GAS_CLOUD) drawGas(r, selected); else drawRock(r, selected);
        drawAmountBar(r);
        r.dispose();
    }

    private void drawRock(Graphics2D g2, boolean selected) {
        Polygon poly = new Polygon();
        for (int i = 0; i < 9; i++) {
            double a = -Math.PI / 2 + i * Math.PI * 2 / 9;
            double wobble = 0.78 + (Math.floorMod(id * 31 + i * 17, 30) / 100.0);
            poly.addPoint((int)Math.round(x + Math.cos(a) * radius * wobble), (int)Math.round(y + Math.sin(a) * radius * wobble));
        }
        g2.setColor(new Color(70, 68, 63)); g2.fillPolygon(poly);
        g2.setColor(material.color); g2.setStroke(new BasicStroke(1f)); g2.drawPolygon(poly);
        g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 85));
        g2.fillOval((int)(x - radius * .45), (int)(y - radius * .45), (int)radius, (int)radius);
        if (selected) drawSelected(g2);
    }

    private void drawGas(Graphics2D g2, boolean selected) {
        for (int i = 0; i < 7; i++) {
            double a = i * Math.PI * 2 / 7.0;
            double ox = Math.cos(a) * radius * .25, oy = Math.sin(a) * radius * .22;
            g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 42 + i * 8));
            g2.fillOval((int)(x + ox - radius * .55), (int)(y + oy - radius * .42), (int)(radius * 1.1), (int)(radius * .84));
        }
        g2.setColor(new Color(material.color.getRed(), material.color.getGreen(), material.color.getBlue(), 150));
        g2.setStroke(new BasicStroke(1f));
        g2.drawOval((int)(x - radius * .8), (int)(y - radius * .62), (int)(radius * 1.6), (int)(radius * 1.24));
        if (selected) drawSelected(g2);
    }

    private void drawSelected(Graphics2D g2) {
        g2.setColor(new Color(255,245,140,210));
        g2.setStroke(new BasicStroke(1.8f));
        g2.drawOval((int)(x - radius - 8), (int)(y - radius - 8), (int)(radius * 2 + 16), (int)(radius * 2 + 16));
    }

    private void drawAmountBar(Graphics2D g2) {
        int w = 18, h = 3, bx = (int)(x - w / 2.0), by = (int)(y + radius + 4);
        double pct = maxAmount <= 0 ? 0 : amount / maxAmount;
        g2.setColor(new Color(0,0,0,130)); g2.fillRoundRect(bx, by, w, h, 3, 3);
        g2.setColor(material.color); g2.fillRoundRect(bx, by, (int)Math.round(w * pct), h, 3, 3);
    }
}
