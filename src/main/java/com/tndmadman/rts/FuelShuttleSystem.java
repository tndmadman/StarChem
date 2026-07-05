package com.tndmadman.rts;

import java.util.*;

final class FuelShuttleSystem {
    static final String SHUTTLE_TYPE = "fuel_hauler_shuttle";
    private static final String MANUFACTURING = "manufacturing";
    private static final String LABORATORY = "laboratory";
    private static final double LAB_FUEL_TARGET = 200.0;
    private static final double LAB_FUEL_LOW_WATER = 150.0;
    private static final double MAX_DELIVERY_RANGE = 2400.0;
    private static final double LAUNCH_COOLDOWN_SECONDS = 3.5;
    private static final Map<String, Double> launchCooldowns = new HashMap<>();

    private FuelShuttleSystem() { }

    static void update(World world, double dt) {
        deliverActiveShuttles(world);
        launchFromManufacturing(world, dt);
        launchCooldowns.keySet().removeIf(id -> !world.bases.containsKey(id));
    }

    private static void deliverActiveShuttles(World world) {
        Iterator<Unit> it = world.units.values().iterator();
        while (it.hasNext()) {
            Unit shuttle = it.next();
            if (!SHUTTLE_TYPE.equals(shuttle.shipTypeId)) continue;
            Base lab = nearestLab(world, shuttle.playerId, shuttle.x, shuttle.y, Double.MAX_VALUE);
            if (lab == null || shuttle.inventory.getOrDefault(Material.FUEL, 0.0) <= 0.05) {
                it.remove();
                continue;
            }
            moveToward(shuttle, lab);
            if (Calc.distance(shuttle.x, shuttle.y, lab.x, lab.y) <= Math.max(42, lab.type().unloadRange * 0.42)) {
                double fuel = shuttle.inventory.getOrDefault(Material.FUEL, 0.0);
                HangarStore.add(lab.inventory, Material.FUEL, fuel);
                it.remove();
                if (PlayerRegistry.isLocal(lab.playerId)) world.status = "Fuel shuttle docked at " + lab.type().name + " with " + Calc.round(fuel) + " Fuel.";
            }
        }
    }

    private static void launchFromManufacturing(World world, double dt) {
        for (Base plant : new ArrayList<>(world.bases.values())) {
            if (!MANUFACTURING.equals(plant.typeId)) continue;
            double cooldown = launchCooldowns.getOrDefault(plant.id, 0.0) - dt;
            if (cooldown > 0) {
                launchCooldowns.put(plant.id, cooldown);
                continue;
            }
            Base lab = nearestFuelHungryLab(world, plant.playerId, plant.x, plant.y, MAX_DELIVERY_RANGE);
            if (lab == null) {
                launchCooldowns.put(plant.id, 0.4);
                continue;
            }
            if (!Rules.SHIPS.containsKey(SHUTTLE_TYPE)) {
                launchCooldowns.put(plant.id, 0.4);
                continue;
            }
            double available = plant.inventory.getOrDefault(Material.FUEL, 0.0);
            double shuttleCapacity = Rules.ship(SHUTTLE_TYPE).cargoCapacity;
            double minimumLoad = Math.min(shuttleCapacity, LAB_FUEL_TARGET - LAB_FUEL_LOW_WATER);
            double labFuel = lab.inventory.getOrDefault(Material.FUEL, 0.0);
            double missing = Math.max(0, LAB_FUEL_TARGET - labFuel - inboundFuel(world, lab));
            if (available < minimumLoad || missing < minimumLoad) {
                launchCooldowns.put(plant.id, 0.6);
                continue;
            }
            double batch = Math.min(available, Math.min(shuttleCapacity, missing));
            spendFuel(plant, batch);
            Unit shuttle = new Unit(plant.playerId, nextUnitId(world, plant.playerId), SHUTTLE_TYPE, undockX(plant, lab), undockY(plant, lab));
            shuttle.addCargo(Material.FUEL, batch);
            moveToward(shuttle, lab);
            world.units.put(shuttle.key(), shuttle);
            launchCooldowns.put(plant.id, LAUNCH_COOLDOWN_SECONDS);
            if (PlayerRegistry.isLocal(plant.playerId)) world.status = "Fuel shuttle launched with " + Calc.round(batch) + " Fuel.";
        }
    }

    private static Base nearestFuelHungryLab(World world, String playerId, double x, double y, double maxRange) {
        Base best = null;
        double bestDist = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!LABORATORY.equals(base.typeId) || !base.playerId.equals(playerId)) continue;
            double stored = base.inventory.getOrDefault(Material.FUEL, 0.0) + inboundFuel(world, base);
            if (stored > LAB_FUEL_LOW_WATER) continue;
            double d = Calc.distance(x, y, base.x, base.y);
            if (d <= maxRange && d < bestDist) {
                best = base;
                bestDist = d;
            }
        }
        return best;
    }

    private static Base nearestLab(World world, String playerId, double x, double y, double maxRange) {
        Base best = null;
        double bestDist = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!LABORATORY.equals(base.typeId) || !base.playerId.equals(playerId)) continue;
            double d = Calc.distance(x, y, base.x, base.y);
            if (d <= maxRange && d < bestDist) {
                best = base;
                bestDist = d;
            }
        }
        return best;
    }

    private static double inboundFuel(World world, Base lab) {
        double total = 0;
        for (Unit unit : world.units.values()) {
            if (!SHUTTLE_TYPE.equals(unit.shipTypeId) || !unit.playerId.equals(lab.playerId)) continue;
            if (Calc.distance(unit.targetX, unit.targetY, lab.x, lab.y) > Math.max(64, lab.type().unloadRange)) continue;
            total += unit.inventory.getOrDefault(Material.FUEL, 0.0);
        }
        return total;
    }

    private static void moveToward(Unit shuttle, Base lab) {
        shuttle.task = UnitTask.MOVE;
        shuttle.attackTarget = "";
        shuttle.automationResourceId = -1;
        shuttle.targetX = lab.x;
        shuttle.targetY = lab.y;
    }

    private static double undockX(Base plant, Base lab) {
        double a = Math.atan2(lab.y - plant.y, lab.x - plant.x);
        return plant.x + Math.cos(a) * Math.max(58, plant.type().buildRadius * 0.8);
    }

    private static double undockY(Base plant, Base lab) {
        double a = Math.atan2(lab.y - plant.y, lab.x - plant.x);
        return plant.y + Math.sin(a) * Math.max(58, plant.type().buildRadius * 0.8);
    }

    private static void spendFuel(Base base, double amount) {
        double next = base.inventory.getOrDefault(Material.FUEL, 0.0) - amount;
        if (next <= 0.05) base.inventory.remove(Material.FUEL);
        else base.inventory.put(Material.FUEL, next);
    }

    private static int nextUnitId(World world, String playerId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
    }
}
