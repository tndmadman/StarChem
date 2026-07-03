package com.tndmadman.rts;

final class BuildSystem {
    boolean buildShip(World world, String baseId, String shipTypeId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        ShipType shipType = Rules.ship(shipTypeId);
        if (!base.type().buildableShips.contains(shipTypeId)) {
            world.status = base.type().name + " cannot build " + shipType.name + ".";
            return false;
        }
        if (!HangarStore.canAfford(base.inventory, shipType.buildCost)) {
            world.status = "Need " + Rules.formatCost(shipType.buildCost) + " in " + base.type().name + " hangar.";
            return false;
        }
        HangarStore.spend(base.inventory, shipType.buildCost);
        int n = countUnits(world, base.playerId) + 1;
        double a = n * 1.35;
        spawnShipFor(world, base.playerId, shipTypeId, base.x + Math.cos(a) * (base.type().buildRadius + 40), base.y + Math.sin(a) * (base.type().buildRadius + 40));
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
        if (!HangarStore.canAfford(base.inventory, pkg.buildCost)) {
            world.status = "Need " + Rules.formatCost(pkg.buildCost) + " in " + base.type().name + " hangar.";
            return false;
        }
        Unit carrier = nearestEmptyBuilder(world, base);
        if (carrier == null) {
            world.status = "Move an empty Deployer into base range first.";
            return false;
        }
        HangarStore.spend(base.inventory, pkg.buildCost);
        carrier.basePackageType = packageType;
        world.status = "Loaded " + pkg.name + " package into Deployer.";
        return true;
    }

    boolean placePackage(World world, Unit carrier) {
        if (carrier == null || carrier.basePackageType.isBlank()) {
            world.status = "Select a loaded Deployer first.";
            return false;
        }
        String baseId = nextBaseId(world, carrier.playerId);
        world.bases.put(baseId, new Base(baseId, carrier.playerId, carrier.basePackageType, carrier.x, carrier.y));
        world.units.remove(carrier.key());
        world.status = "Placed Shipyard. Deployer consumed.";
        return true;
    }

    private Unit nearestEmptyBuilder(World world, Base base) {
        Unit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(base.playerId)) continue;
            if (!unit.type().baseBuilder || !unit.basePackageType.isBlank()) continue;
            double d = Calc.distance(unit.x, unit.y, base.x, base.y);
            if (d <= base.type().unloadRange && d < bestDist) {
                best = unit;
                bestDist = d;
            }
        }
        return best;
    }

    private void spawnShipFor(World world, String playerId, String type, double x, double y) {
        int next = countUnits(world, playerId) + 1;
        Unit unit = new Unit(playerId, next, type, x, y);
        world.units.put(unit.key(), unit);
    }

    private int countUnits(World world, String playerId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) count++;
        return count;
    }

    private String nextBaseId(World world, String playerId) {
        int max = 0;
        String prefix = playerId + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return prefix + (max + 1);
    }
}
