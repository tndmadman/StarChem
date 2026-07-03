package com.tndmadman.rts;

import java.util.EnumMap;

final class ResourceText {
    private ResourceText() { }

    static String shortLine(EnumMap<Material, Double> store) {
        if (store == null || store.isEmpty()) return "empty";
        StringBuilder out = new StringBuilder();
        for (Material material : Material.values()) {
            double amount = store.getOrDefault(material, 0.0);
            if (amount <= 0.05) continue;
            if (!out.isEmpty()) out.append("  ");
            out.append(displayName(material)).append(':').append((int)Math.floor(amount));
        }
        return out.isEmpty() ? "empty" : out.toString();
    }

    static String displayName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
