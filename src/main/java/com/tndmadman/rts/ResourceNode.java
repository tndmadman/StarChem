package com.tndmadman.rts;

import java.awt.Graphics2D;
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

    void draw(Graphics2D g2, boolean selected) { ResourceVisualRenderer.draw(g2, this, selected); }
}
