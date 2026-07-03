package com.tndmadman.rts;

import java.util.List;

final class BuildSystem {
    boolean buildShip(World world, String baseId, String shipTypeId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        ShipType shipType = Rules.ship(shipTypeId);
        if (!base.type().buildableShips.contains(shipTypeId)) {
            world.status = base.type().name + " cannot build " + shipType.name + ".";
            return false;
        }
        if (!world.canAfford(shipType.buildCost)) {
            world.status = "Need " + Rules.formatCost(shipType.buildCost) + " for " + shipType.name + ".";
            return false;
        }
        world.spend(shipType.buildCost);
        int n = world.units.size() + 1;
        double a = n * 1.35;
        world.spawnShip(shipTypeId, base.x + Math.cos(a) * (base.type().buildRadius + 40), base.y + Math.sin(a) * (base.type().buildRadius + 40));
        world.status = "Built " + shipType.name + ".";
        return true;
    }

    boolean loadBasePackage(World world, String baseId, String packageType) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        if (!base.type().basePackages.contains(packageType)) {
            world.status = base.type().name + " cannot craft that package.";
            return false;
        }
        BaseType pkg = Rules.base(packageType);
        if (!world.canAfford(pkg.buildCost)) {
            world.status = "Need " + Rules.formatCost(pkg.buildCost) + " for " + pkg.name + " package.";
            return false;
        }
        Unit carrier = nearestEmptyBuilder(world, base);
        if (carrier == null) {
            world.status = "Move an empty Deployer into base range first.";
            return false;
        }
        world.spend(pkg.buildCost);
        carrier.basePackageType = packageType;
        world.status = "Loaded " + pkg.name + " package into Deployer.";
        return true;
    }

    boolean placePackage(World world, Unit carrier) {
        if (carrier == null || carrier.basePackageType.isBlank()) {
            world.status = "Select a loaded Deployer first.";
            return false;
        }
        world.addBase(carrier.basePackageType, carrier.x, carrier.y);
        world.units.remove(carrier.key());
        world.status = "Placed Shipyard. Deployer consumed.";
        return true;
    }

    private Unit nearestEmptyBuilder(World world, Base base) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!unit.type().baseBuilder || !unit.basePackageType.isBlank()) continue;
            double d = Calc.distance(unit.x, unit.y, base.x, base.y);
            if (d <= base.type().unloadRange && d < bestDist) {
                best = unit;
                bestDist = d;
            }
        }
        return best;
    }
}
