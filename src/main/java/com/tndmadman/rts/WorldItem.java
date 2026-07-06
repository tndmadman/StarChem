package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.EnumMap;

final class WorldItem {
    static final double RADIUS = 24;
    final int id;
    final double x, y;
    final EnumMap<Material, Double> inventory = new EnumMap<>(Material.class);

    WorldItem(int id, double x, double y, EnumMap<Material, Double> cargo) {
        this.id = id;
        this.x = x;
        this.y = y;
        if (cargo != null) for (Material material : Material.values()) {
            double amount = cargo.getOrDefault(material, 0.0);
            if (amount > 0.05) inventory.put(material, amount);
        }
    }

    boolean empty() { return amount() <= 0.05; }

    double amount() {
        double total = 0;
        for (double value : inventory.values()) total += value;
        return total;
    }

    double pickupRange(Unit unit) { return RADIUS + 28 * unit.type().size.scale; }

    void draw(Graphics2D g2) {
        if (empty()) return;
        Material material = primaryMaterial();
        Color c = material.color;
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 65));
        g2.fill(new Ellipse2D.Double(x - RADIUS * 1.45, y - RADIUS * 1.45, RADIUS * 2.9, RADIUS * 2.9));
        g2.setColor(new Color(15, 20, 26, 220));
        g2.fill(new Ellipse2D.Double(x - RADIUS, y - RADIUS, RADIUS * 2, RADIUS * 2));
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 210));
        g2.draw(new Ellipse2D.Double(x - RADIUS, y - RADIUS, RADIUS * 2, RADIUS * 2));
        g2.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
        g2.setColor(new Color(235, 245, 255, 215));
        g2.drawString(Calc.round(amount()) + " loot", (float)(x + RADIUS + 5), (float)(y - RADIUS));
    }

    private Material primaryMaterial() {
        Material best = Material.SCRAP_METAL;
        double bestAmount = -1;
        for (Material material : Material.values()) {
            double amount = inventory.getOrDefault(material, 0.0);
            if (amount > bestAmount) {
                best = material;
                bestAmount = amount;
            }
        }
        return best;
    }
}
