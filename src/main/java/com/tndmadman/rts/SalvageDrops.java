package com.tndmadman.rts;

import java.util.EnumMap;

final class SalvageDrops {
    private SalvageDrops() { }

    static EnumMap<Material, Double> fromUnit(Unit unit) {
        EnumMap<Material, Double> out = new EnumMap<>(Material.class);
        out.putAll(unit.inventory);
        double scale = Math.max(0.65, unit.type().size.scale);
        add(out, Material.SCRAP_METAL, 10 * scale);
        add(out, Material.HULL_PLATING, 6 * scale);
        add(out, Material.CIRCUIT_FRAGMENTS, (unit.type().maxShield > 0 ? 4 : 2) * scale);
        return out;
    }

    static EnumMap<Material, Double> fromBase(Base base) {
        EnumMap<Material, Double> out = new EnumMap<>(Material.class);
        out.putAll(base.inventory);
        double scale = Math.max(2.0, base.type().maxHp / 600.0);
        add(out, Material.SCRAP_METAL, 35 * scale);
        add(out, Material.HULL_PLATING, 24 * scale);
        add(out, Material.CIRCUIT_FRAGMENTS, 14 * scale);
        return out;
    }

    private static void add(EnumMap<Material, Double> cargo, Material material, double amount) {
        if (amount <= 0.05) return;
        cargo.put(material, cargo.getOrDefault(material, 0.0) + amount);
    }
}
