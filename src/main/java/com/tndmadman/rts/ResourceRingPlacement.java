package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The system template defines a bounded radial belt and an orbiting angular arc.
 * The very same geometry is used for initial creation and all natural respawns.
 * Event resources and fractured planetary deposits are intentionally excluded.
 */
final class ResourceRingPlacement {
    private static final double TAU = Math.PI * 2.0;
    private static final int RESPAWN_ATTEMPTS = 48;
    private static final double MIN_NODE_SEPARATION = 140.0;
    private static final double MIN_STATION_SEPARATION = 180.0;

    private ResourceRingPlacement() { }

    static double radius(ResourceBelt belt, Random random) {
        // Reflect a normal sample at the ring boundaries instead of clipping
        // it there or drawing a second sample. This retains one Gaussian draw
        // per node (important for legacy seeded worlds), preserves in-band
        // positions exactly, and has no pile-up at either ring edge.
        double shifted = (random.nextGaussian() + 1.0) % 4.0;
        if (shifted < 0) shifted += 4.0;
        double reflected = (shifted <= 2.0 ? shifted : 4.0 - shifted) - 1.0;
        return belt.orbit + reflected * belt.width;
    }

    static double angle(ResourceBelt belt, double center, Random random) {
        return center + (random.nextDouble() - 0.5) * belt.arc;
    }

    static ResourceBelt resolve(ResourceNode node, StarSystemDefinition definition) {
        if (node == null || definition == null || isCelestial(node)) return null;
        if (node.resourceBeltId != null && !node.resourceBeltId.isBlank()) {
            for (ResourceBelt belt : definition.resourceBelts()) {
                if (belt.id.equals(node.resourceBeltId)) return belt;
            }
            return null;
        }
        // Legacy archives have no explicit source. Only the original deterministic
        // "Belt Name MATERIAL nodeIndex" naming format is safe to infer from;
        // do not capture event resources simply because they share a material.
        for (ResourceBelt belt : definition.resourceBelts()) {
            if (belt.kind != node.kind || !belt.materials.contains(node.material)) continue;
            String prefix = belt.name + " " + node.material.name() + " ";
            if (!node.name.startsWith(prefix)) continue;
            String ordinal = node.name.substring(prefix.length());
            if (!ordinal.isEmpty() && ordinal.chars().allMatch(Character::isDigit)) return belt;
        }
        return null;
    }

    static void relocate(ResourceNode node, List<ResourceNode> resources, Collection<Base> bases,
                         CelestialSystem celestials, Random random, ResourceBelt belt, double systemTime) {
        node.resourceBeltId = belt.id;
        if (!Double.isFinite(node.resourceBeltCenterAngle)) {
            node.resourceBeltCenterAngle = node.orbitAngle - ResourceSpawner.speedFor(belt.orbit) * systemTime;
        }
        double phase = node.resourceBeltCenterAngle + ResourceSpawner.speedFor(belt.orbit) * systemTime;
        double x = celestials.sunX(), y = celestials.sunY();
        double bestRadius = belt.orbit, bestAngle = phase, bestClearance = -Double.MAX_VALUE;
        for (int attempt = 0; attempt < RESPAWN_ATTEMPTS; attempt++) {
            double r = radius(belt, random);
            double a = angle(belt, phase, random);
            double px = x + Math.cos(a) * r;
            double py = y + Math.sin(a) * r;
            double clearance = Double.POSITIVE_INFINITY;
            for (Base base : bases) {
                if (base.hp <= 0) continue;
                clearance = Math.min(clearance, Math.hypot(px - base.x, py - base.y)
                        - MIN_STATION_SEPARATION);
            }
            for (ResourceNode other : resources) {
                if (other == null || other == node || !other.active) continue;
                clearance = Math.min(clearance, Math.hypot(px - other.x, py - other.y)
                        - MIN_NODE_SEPARATION);
            }
            if (clearance > bestClearance) {
                bestClearance = clearance;
                bestRadius = r;
                bestAngle = a;
            }
            if (clearance >= 0) break;
        }
        node.amount = node.maxAmount;
        node.active = true;
        node.respawnTimer = 0;
        node.orbit(x, y, bestRadius, bestAngle, ResourceSpawner.speedFor(belt.orbit));
    }

    static void migrateLoaded(List<ResourceNode> nodes, StarSystemDefinition definition, double systemTime) {
        if (nodes == null || definition == null) return;
        Map<String, List<ResourceNode>> byBelt = new LinkedHashMap<>();
        for (ResourceNode node : nodes) {
            ResourceBelt belt = resolve(node, definition);
            if (belt == null) continue;
            node.resourceBeltId = belt.id;
            byBelt.computeIfAbsent(belt.id, ignored -> new ArrayList<>()).add(node);
        }
        for (ResourceBelt belt : definition.resourceBelts()) {
            List<ResourceNode> members = byBelt.get(belt.id);
            if (members == null || members.isEmpty()) continue;
            double originalCenter = Double.NaN;
            for (ResourceNode node : members) {
                if (Double.isFinite(node.resourceBeltCenterAngle)) {
                    originalCenter = node.resourceBeltCenterAngle;
                    break;
                }
            }
            boolean legacyGeometry = !Double.isFinite(originalCenter);
            double commonSpeed = ResourceSpawner.speedFor(belt.orbit);
            if (legacyGeometry) {
                // Legacy rocks once had independent speed jitter, so unwinding by their
                // differing speeds reconstructs an inconsistent arc. Use their currently
                // occupied direction instead and preserve in-arc positions on migration.
                double sin = 0, cos = 0;
                for (ResourceNode node : members) {
                    if (!Double.isFinite(node.orbitAngle)) continue;
                    sin += Math.sin(node.orbitAngle);
                    cos += Math.cos(node.orbitAngle);
                }
                double currentCenter = Math.atan2(sin, cos);
                originalCenter = currentCenter - commonSpeed * systemTime;
            }
            double phaseNow = originalCenter + commonSpeed * systemTime;
            for (ResourceNode node : members) {
                // Belt members now share exactly one angular velocity. Legacy outliers
                // are moved only if they escaped the original partial orbital arc.
                node.orbitSpeed = commonSpeed;
                if (legacyGeometry && node.orbiting && Double.isFinite(node.orbitAngle)) {
                    double offset = Math.atan2(Math.sin(node.orbitAngle - phaseNow),
                            Math.cos(node.orbitAngle - phaseNow));
                    if (Math.abs(offset) > belt.arc * 0.5) {
                        // Stable per-node fractional offset prevents a pile-up at the edge.
                        double fraction = ((node.id * 1103515245L + 12345L) & 0x7fffffffL)
                                / (double)0x80000000L;
                        offset = (fraction - 0.5) * belt.arc * 0.94;
                        node.orbitAngle = phaseNow + offset;
                        node.orbitCenterX = definition.width() * 0.5;
                        node.orbitCenterY = definition.height() * 0.5;
                        node.x = node.orbitCenterX + Math.cos(node.orbitAngle) * node.orbitRadius;
                        node.y = node.orbitCenterY + Math.sin(node.orbitAngle) * node.orbitRadius;
                    }
                }
                if (!Double.isFinite(node.resourceBeltCenterAngle))
                    node.resourceBeltCenterAngle = originalCenter;
                double min = belt.orbit - belt.width;
                double max = belt.orbit + belt.width;
                if (!Double.isFinite(node.orbitRadius) || node.orbitRadius < min || node.orbitRadius > max) {
                    // Repair escaped natural rocks in existing saves without changing their
                    // material, quantity, ID, depletion timer, or angular position.
                    double r = Double.isFinite(node.orbitRadius) ? Math.max(min, Math.min(max, node.orbitRadius)) : belt.orbit;
                    node.orbitRadius = r;
                    node.orbitCenterX = definition.width() * 0.5;
                    node.orbitCenterY = definition.height() * 0.5;
                    node.x = node.orbitCenterX + Math.cos(node.orbitAngle) * r;
                    node.y = node.orbitCenterY + Math.sin(node.orbitAngle) * r;
                }
            }
        }
    }

    static boolean withinRadius(ResourceNode node, ResourceBelt belt) {
        return node.orbitRadius >= belt.orbit - belt.width - 1e-7
                && node.orbitRadius <= belt.orbit + belt.width + 1e-7;
    }

    static boolean withinArc(double angle, double center, double width) {
        return Math.abs(Math.atan2(Math.sin(angle - center), Math.cos(angle - center))) <= width * 0.5 + 1e-7;
    }

    private static boolean isCelestial(ResourceNode node) {
        return node.celestialAnchorBodyId != null && !node.celestialAnchorBodyId.isBlank();
    }
}
