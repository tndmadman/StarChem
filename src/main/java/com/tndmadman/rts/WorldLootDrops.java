package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.Random;

final class WorldLootDrops {
    private WorldLootDrops() { }

    static int scatter(World world, EnumMap<Material, Double> cargo, double x, double y, double power, long seed) {
        if (cargo == null || cargo.isEmpty()) return 0;
        Random random = new Random(seed);
        int count = 0;
        for (Material material : Material.values()) {
            double amount = cargo.getOrDefault(material, 0.0);
            if (amount <= 0.05) continue;
            int pieces = pieces(amount, power);
            for (int i = 0; i < pieces; i++) {
                double share = amount / pieces;
                if (pieces > 1) share *= 0.75 + random.nextDouble() * 0.5;
                spawn(world, material, Math.max(0.05, share), x, y, power, random);
                count++;
            }
        }
        return count;
    }

    private static int pieces(double amount, double power) {
        int byAmount = (int)Math.ceil(amount / 80.0);
        int byPower = (int)Math.round(power * 1.4);
        return Math.max(1, Math.min(10, Math.max(byAmount, byPower)));
    }

    private static void spawn(World world, Material material, double amount, double x, double y, double power, Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double speed = (70 + random.nextDouble() * 210) * power;
        double offset = 8 + random.nextDouble() * 20 * power;
        world.addWorldItem(
                material,
                amount,
                x + Math.cos(angle) * offset,
                y + Math.sin(angle) * offset,
                Math.cos(angle) * speed,
                Math.sin(angle) * speed,
                random.nextDouble() * Math.PI * 2,
                (random.nextDouble() - 0.5) * 8.5 * power);
    }
}
