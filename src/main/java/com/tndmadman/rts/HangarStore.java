package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.List;

final class HangarStore {
    private HangarStore() { }

    static boolean canAfford(EnumMap<Material, Double> store, List<Cost> cost) {
        for (Cost c : cost) if (store.getOrDefault(c.material(), 0.0) + 0.001 < c.amount()) return false;
        return true;
    }

    static void spend(EnumMap<Material, Double> store, List<Cost> cost) {
        for (Cost c : cost) {
            double next = store.getOrDefault(c.material(), 0.0) - c.amount();
            if (next <= 0.05) store.remove(c.material());
            else store.put(c.material(), next);
        }
    }

    static void add(EnumMap<Material, Double> store, Material material, double amount) {
        if (amount <= 0.001) return;
        store.put(material, store.getOrDefault(material, 0.0) + amount);
    }
}
