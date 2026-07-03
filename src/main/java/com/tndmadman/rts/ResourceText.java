package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

final class ResourceText {
    private ResourceText() { }

    static String shortLine(EnumMap<Material, Double> store) {
        List<String> rows = lines(store);
        return rows.isEmpty() ? "empty" : String.join("  ", rows);
    }

    static List<String> lines(EnumMap<Material, Double> store) {
        List<String> out = new ArrayList<>();
        if (store == null || store.isEmpty()) return out;
        for (Material material : Material.values()) {
            double amount = store.getOrDefault(material, 0.0);
            if (amount <= 0.05) continue;
            out.add(displayName(material) + ": " + (int)Math.floor(amount));
        }
        return out;
    }

    static String displayName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
