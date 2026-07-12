package com.tndmadman.rts;

import java.util.Collection;
import java.util.List;
import java.util.Random;

final class ResourceSpawner {
    private ResourceSpawner() { }

    static void seed(List<ResourceNode> resources, CelestialSystem celestials, Random random) {
        seed(resources, celestials, random, 1, Rules.RESOURCE_BELTS);
    }

    static void seed(List<ResourceNode> resources, CelestialSystem celestials, Random random, List<ResourceBelt> belts) {
        seed(resources, celestials, random, 1, belts);
    }

    static int seed(List<ResourceNode> resources, CelestialSystem celestials, Random random, int startId, List<ResourceBelt> belts) {
        int id = startId;
        for (ResourceBelt belt : belts) id = belt(resources, id, random, celestials, belt);
        return id;
    }

    static void update(List<ResourceNode> resources, CelestialSystem celestials, double dt) {
        for (ResourceNode node : resources) node.updateOrbit(dt);
    }

    static void relocate(ResourceNode node, List<ResourceNode> resources, Collection<Base> bases, CelestialSystem celestials, Random random) {
        ResourceNode anchor = anchorFor(node, resources, random);
        double orbitRadius = anchor == null ? 2200 + random.nextDouble() * 4400 : anchor.orbitRadius + random.nextGaussian() * 90;
        double orbitAngle = anchor == null ? random.nextDouble() * Math.PI * 2 : anchor.orbitAngle + random.nextGaussian() * 0.18;
        double orbitSpeed = anchor == null ? speedFor(orbitRadius) : anchor.orbitSpeed * (0.94 + random.nextDouble() * 0.12);
        activate(node, celestials == null ? node.orbitCenterX : celestials.sunX(), celestials == null ? node.orbitCenterY : celestials.sunY(), orbitRadius, orbitAngle, orbitSpeed);
    }

    private static int belt(List<ResourceNode> out, int id, Random random, CelestialSystem celestials, ResourceBelt belt) {
        double center = random.nextDouble() * Math.PI * 2;
        List<Material> materials = belt.materials.isEmpty() ? List.of(Material.IRON) : belt.materials;
        for (int i = 0; i < belt.count; i++) {
            double orbitRadius = belt.orbit + random.nextGaussian() * belt.width;
            double orbitAngle = center + (random.nextDouble() - 0.5) * belt.arc;
            double orbitSpeed = speedFor(orbitRadius) * (0.9 + random.nextDouble() * 0.2);
            Material material = materials.get(random.nextInt(materials.size()));
            double nodeAmount = belt.amount * (0.35 + random.nextDouble() * 1.5);
            double nodeRadius = radiusForAmount(belt.radius, belt.amount, nodeAmount, random);
            ResourceNode node = new ResourceNode(id++, belt.name + " " + material.name() + " " + i, belt.kind, material, 0, 0, nodeAmount, belt.harvestRate, nodeRadius);
            node.orbit(celestials.sunX(), celestials.sunY(), orbitRadius, orbitAngle, orbitSpeed);
            out.add(node);
        }
        return id;
    }

    private static double radiusForAmount(double baseRadius, double beltAverageAmount, double nodeAmount, Random random) {
        double average = Math.max(1.0, beltAverageAmount);
        double richness = Math.max(0.18, nodeAmount / average);
        double amountScale = Math.pow(richness, 0.85);
        double naturalVariation = 0.82 + random.nextDouble() * 0.36;
        return Math.max(1.3, baseRadius * amountScale * naturalVariation);
    }

    private static ResourceNode anchorFor(ResourceNode source, List<ResourceNode> resources, Random random) {
        ResourceNode picked = null;
        int seen = 0;
        for (ResourceNode node : resources) {
            if (node.id == source.id || !node.active || node.material != source.material || node.kind != source.kind) continue;
            if (Calc.distance(source.orbitCenterX, source.orbitCenterY, node.orbitCenterX, node.orbitCenterY) > 100) continue;
            if (random.nextInt(++seen) == 0) picked = node;
        }
        return picked;
    }

    private static void activate(ResourceNode node, double centerX, double centerY, double orbitRadius, double orbitAngle, double orbitSpeed) {
        node.amount = node.maxAmount;
        node.active = true;
        node.respawnTimer = 0;
        node.orbit(centerX, centerY, orbitRadius, orbitAngle, orbitSpeed);
    }

    private static double speedFor(double orbitRadius) {
        return 0.028 * Math.pow(2000.0 / Math.max(900.0, orbitRadius), 0.65);
    }
}
