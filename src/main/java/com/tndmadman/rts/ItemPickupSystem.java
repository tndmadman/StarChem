package com.tndmadman.rts;

import java.util.Iterator;

final class ItemPickupSystem {
    private static final double EPS = 0.05;

    void update(World world) {
        Iterator<WorldItem> it = world.items.iterator();
        while (it.hasNext()) {
            WorldItem item = it.next();
            Unit unit = pickupUnit(world, item);
            if (unit != null) transfer(item, unit);
            if (item.empty()) it.remove();
        }
    }

    private Unit pickupUnit(World world, WorldItem item) {
        // For now any cargo-capable ship collects loot automatically.
        // Later this should require the dedicated salvage hauler hull.
        for (Unit unit : world.units.values()) {
            if (unit.freeCargo() <= EPS) continue;
            if (Calc.distance(unit.x, unit.y, item.x, item.y) <= item.pickupRange(unit)) return unit;
        }
        return null;
    }

    private void transfer(WorldItem item, Unit unit) {
        double free = unit.freeCargo();
        if (free <= EPS) return;
        boolean moved = false;
        for (Material material : Material.values()) {
            if (free <= EPS) break;
            double held = item.inventory.getOrDefault(material, 0.0);
            if (held <= EPS) continue;
            double take = Math.min(held, free);
            unit.addCargo(material, take);
            double left = held - take;
            if (left <= EPS) item.inventory.remove(material);
            else item.inventory.put(material, left);
            free -= take;
            moved = true;
        }
        if (moved && PlayerRegistry.isLocal(unit.playerId)) unit.unloadingThisFrame = true;
    }
}
