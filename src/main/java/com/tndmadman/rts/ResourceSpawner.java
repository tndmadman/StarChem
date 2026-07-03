package com.tndmadman.rts;

import java.util.Collection;
import java.util.List;
import java.util.Random;

final class ResourceSpawner {
    private ResourceSpawner() { }

    static void seed(List<ResourceNode> resources, CelestialSystem celestials, Random random) {
        int id = 1;
        id = belt(resources, id, random, celestials, "Inner Iron Belt", NodeKind.SILICATE_ROCK, new Material[]{Material.IRON}, 1900, 260, 1.0, 130, 22, 7.5, 2.8);
        id = belt(resources, id, random, celestials, "Copper Arc", NodeKind.SILICATE_ROCK, new Material[]{Material.COPPER}, 2650, 300, 0.8, 110, 18, 6.5, 2.6);
        id = belt(resources, id, random, celestials, "Silicate Belt", NodeKind.SILICATE_ROCK, new Material[]{Material.SILICATES}, 3500, 360, 1.2, 140, 24, 8.0, 3.0);
        id = belt(resources, id, random, celestials, "Ice Ring", NodeKind.SILICATE_ROCK, new Material[]{Material.ICE}, 4650, 420, 0.9, 115, 20, 7.0, 2.8);
        id = belt(resources, id, random, celestials, "Hydrogen Drift", NodeKind.GAS_CLOUD, new Material[]{Material.HYDROGEN}, 5450, 520, 1.1, 120, 26, 9.0, 4.8);
        belt(resources, id, random, celestials, "Outer Gas Band", NodeKind.GAS_CLOUD, new Material[]{Material.HELIUM, Material.METHANE, Material.AMMONIA, Material.HYDROGEN}, 6650, 620, 1.4, 160, 22, 7.5, 4.5);
    }

    static void update(List<ResourceNode> resources, CelestialSystem celestials, double dt) {
        for (ResourceNode node : resources) node.updateOrbit(celestials.sunX(), celestials.sunY(), dt);
    }

    static void relocate(ResourceNode node, List<ResourceNode> resources, Collection<Base> bases, CelestialSystem celestials, Random random) {
        ResourceNode anchor = anchorFor(node, resources, random);
        double orbitRadius = anchor == null ? 2200 + random.nextDouble() * 4400 : anchor.orbitRadius + random.nextGaussian() * 90;
        double orbitAngle = anchor == null ? random.nextDouble() * Math.PI * 2 : anchor.orbitAngle + random.nextGaussian() * 0.18;
        double orbitSpeed = anchor == null ? speedFor(orbitRadius) : anchor.orbitSpeed * (0.94 + random.nextDouble() * 0.12);
        activate(node, celestials, orbitRadius, orbitAngle, orbitSpeed);
    }

    private static int belt(List<ResourceNode> out, int id, Random random, CelestialSystem celestials, String name,
                            NodeKind kind, Material[] materials, double orbit, double width, double arc,
                            int count, double amount, double harvestRate, double radius) {
        double center = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double orbitRadius = orbit + random.nextGaussian() * width;
            double orbitAngle = center + (random.nextDouble() - 0.5) * arc;
            double orbitSpeed = speedFor(orbitRadius) * (0.9 + random.nextDouble() * 0.2);
            Material material = materials[i % materials.length];
            double nodeAmount = amount * (0.65 + random.nextDouble() * 0.7);
            double nodeRadius = radius * (0.7 + random.nextDouble() * 0.6);
            ResourceNode node = new ResourceNode(id++, name + " " + material.name() + " " + i, kind, material, 0, 0, nodeAmount, harvestRate, nodeRadius);
            node.orbit(celestials.sunX(), celestials.sunY(), orbitRadius, orbitAngle, orbitSpeed);
            out.add(node);
        }
        return id;
    }

    private static ResourceNode anchorFor(ResourceNode source, List<ResourceNode> resources, Random random) {
        ResourceNode picked = null;
        int seen = 0;
        for (ResourceNode node : resources) {
            if (node.id == source.id || !node.active || node.material != source.material || node.kind != source.kind) continue;
            if (random.nextInt(++seen) == 0) picked = node;
        }
        return picked;
    }

    private static void activate(ResourceNode node, CelestialSystem celestials, double orbitRadius, double orbitAngle, double orbitSpeed) {
        node.amount = node.maxAmount;
        node.active = true;
        node.respawnTimer = 0;
        node.orbit(celestials.sunX(), celestials.sunY(), orbitRadius, orbitAngle, orbitSpeed);
    }

    private static double speedFor(double orbitRadius) {
        return 0.028 * Math.pow(2000.0 / Math.max(900.0, orbitRadius), 0.65);
    }
}
