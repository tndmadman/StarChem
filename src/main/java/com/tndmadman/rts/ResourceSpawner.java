package com.tndmadman.rts;

import java.util.Collection;
import java.util.List;
import java.util.Random;

final class ResourceSpawner {
    private static final int SEED = 1977;
    private static final double RESOURCE_SPACING = 18;
    private static final double BASE_SPACING = 180;

    private ResourceSpawner() { }

    static void seed(List<ResourceNode> resources, int worldW, int worldH) {
        Random random = new Random(SEED);
        int id = 1;
        id = belt(resources, id, random, "Inner Iron Belt", NodeKind.SILICATE_ROCK, new Material[]{Material.IRON}, 1700, 860, 1500, 190, -0.18, 44, 60, 7.5, 3.4, worldW, worldH);
        id = belt(resources, id, random, "Copper Shard Belt", NodeKind.SILICATE_ROCK, new Material[]{Material.COPPER}, 3250, 1380, 1320, 170, 0.28, 36, 54, 6.5, 3.0, worldW, worldH);
        id = belt(resources, id, random, "Silicate Ridge", NodeKind.SILICATE_ROCK, new Material[]{Material.SILICATES}, 6350, 3650, 1600, 220, -0.38, 38, 70, 8.0, 3.8, worldW, worldH);
        id = belt(resources, id, random, "Ice Fragment Field", NodeKind.SILICATE_ROCK, new Material[]{Material.ICE}, 5750, 980, 1500, 240, 0.10, 34, 62, 7.0, 3.2, worldW, worldH);
        id = belt(resources, id, random, "Hydrogen Nebula", NodeKind.GAS_CLOUD, new Material[]{Material.HYDROGEN}, 2300, 3920, 1450, 360, -0.08, 40, 78, 9.0, 6.2, worldW, worldH);
        belt(resources, id, random, "Outer Gas Pocket", NodeKind.GAS_CLOUD, new Material[]{Material.HELIUM, Material.METHANE, Material.AMMONIA, Material.HYDROGEN}, 5450, 3180, 1700, 420, 0.32, 48, 64, 7.5, 5.8, worldW, worldH);
    }

    static void relocate(ResourceNode node, List<ResourceNode> resources, Collection<Base> bases, int worldW, int worldH, Random random) {
        for (int attempt = 0; attempt < 80; attempt++) {
            ResourceNode anchor = anchorFor(node, resources, random);
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 35 + random.nextDouble() * 260;
            double x = anchor == null ? randomX(worldW, random) : anchor.x + Math.cos(angle) * distance;
            double y = anchor == null ? randomY(worldH, random) : anchor.y + Math.sin(angle) * distance;
            x = clamp(x, 120, worldW - 120);
            y = clamp(y, 120, worldH - 120);
            if (valid(node.id, x, y, resources, bases)) {
                activate(node, x, y);
                return;
            }
        }
        activate(node, randomX(worldW, random), randomY(worldH, random));
    }

    private static int belt(List<ResourceNode> out, int id, Random random, String name, NodeKind kind, Material[] materials,
                            double cx, double cy, double length, double width, double angle, int count,
                            double amount, double harvestRate, double radius, int worldW, int worldH) {
        double ca = Math.cos(angle);
        double sa = Math.sin(angle);
        for (int i = 0; i < count; i++) {
            double along = ((i / (double)Math.max(1, count - 1)) - 0.5) * length;
            double across = (random.nextDouble() - 0.5) * width;
            double x = clamp(cx + ca * along - sa * across, 120, worldW - 120);
            double y = clamp(cy + sa * along + ca * across, 120, worldH - 120);
            Material material = materials[i % materials.length];
            double nodeAmount = amount * (0.75 + random.nextDouble() * 0.5);
            double nodeRadius = radius * (0.75 + random.nextDouble() * 0.5);
            out.add(new ResourceNode(id++, name + " " + material.name() + " " + i, kind, material, x, y, nodeAmount, harvestRate, nodeRadius));
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

    private static boolean valid(int nodeId, double x, double y, List<ResourceNode> resources, Collection<Base> bases) {
        for (Base base : bases) if (Calc.distance(x, y, base.x, base.y) < BASE_SPACING) return false;
        for (ResourceNode node : resources) if (node.id != nodeId && node.active && Calc.distance(x, y, node.x, node.y) < RESOURCE_SPACING) return false;
        return true;
    }

    private static void activate(ResourceNode node, double x, double y) {
        node.x = x;
        node.y = y;
        node.amount = node.maxAmount;
        node.active = true;
        node.respawnTimer = 0;
    }

    private static double randomX(int worldW, Random random) { return 120 + random.nextDouble() * (worldW - 240); }
    private static double randomY(int worldH, Random random) { return 120 + random.nextDouble() * (worldH - 240); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
