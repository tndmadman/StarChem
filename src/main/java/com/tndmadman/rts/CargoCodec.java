package com.tndmadman.rts;

import java.util.EnumMap;

final class CargoCodec {
    private CargoCodec() { }

    static String write(EnumMap<Material, Double> cargo) {
        if (cargo == null || cargo.isEmpty()) return "-";
        StringBuilder out = new StringBuilder();
        for (Material material : Material.values()) {
            double amount = cargo.getOrDefault(material, 0.0);
            if (amount > 0.05) {
                if (!out.isEmpty()) out.append('~');
                out.append(material.name()).append(':').append(Calc.round(amount));
            }
        }
        return out.isEmpty() ? "-" : out.toString();
    }

    static void readInto(String text, EnumMap<Material, Double> cargo) {
        cargo.clear();
        if (text == null || text.isBlank() || text.equals("-")) return;
        for (String part : text.split("~")) {
            String[] pair = part.split(":");
            if (pair.length != 2) continue;
            try {
                Material material = Material.valueOf(pair[0]);
                double amount = Double.parseDouble(pair[1]);
                if (amount > 0.05) cargo.put(material, amount);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    static String safe(String value) { return value == null || value.isBlank() ? "-" : value; }
    static String unsafed(String value) { return value == null || value.equals("-") ? "" : value; }
}
