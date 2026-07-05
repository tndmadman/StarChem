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
        if (!StationFuelRules.isOperational(base)) {
            world.status = base.type().name + " needs " + StationFuelRules.requirement(base.typeId).material().label + " to run.";
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !HangarStore.canAfford(base.inventory, shipType.buildCost)) {
            if (world.logisticsSystem.queueBuildShip(world, base, shipType)) return true;
            world.status = "Need " + Rules.formatCost(shipType.buildCost) + " in " + base.type().name + " hangar.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, shipType.buildCost);
        int n = nextUnitId(world, base.playerId);
        double a = n * 1.35;
        spawnShipFor(world, base.playerId, n, shipTypeId, base.x + Math.cos(a) * (base.type().buildRadius + 40), base.y + Math.sin(a) * (base.type().buildRadius + 40));
        world.status = free ? "Dev built " + shipType.name + " for free." : "Built " + shipType.name + ".";
        return true;
    }

    boolean loadBasePackage(World world, String baseId, String packageType) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        if (!base.type().basePackages.contains(packageType)) {
            world.status = base.type().name + " cannot craft that package.";
            return false;
        }
        if (!StationFuelRules.isOperational(base)) {
            world.status = base.type().name + " needs " + StationFuelRules.requirement(base.typeId).material().label + " to run.";
            return false;
        }
        BaseType pkg = Rules.base(packageType);
        boolean free = freeBuild(world, base);
        if (!free && !HangarStore.canAfford(base.inventory, pkg.buildCost)) {
            if (world.logisticsSystem.queueBasePackage(world, base, pkg)) return true;
            world.status = "Need " + Rules.formatCost(pkg.buildCost) + " in " + base.type().name + " hangar.";
            return false;
        }
        Unit carrier = nearestEmptyBuilder(world, base);
        if (carrier == null) {
            world.status = "Move an empty Deployer into base range first.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, pkg.buildCost);
        carrier.basePackageType = packageType;
        world.status = free ? "Dev loaded " + pkg.name + " package for free." : "Loaded " + pkg.name + " package into Deployer.";
        return true;
    }

    boolean placePackage(World world, Unit carrier) {
        if (carrier == null || carrier.basePackageType.isBlank()) {
            world.status = "Select a loaded Deployer first.";
            return false;
        }
        String baseId = nextBaseId(world, carrier.playerId);
        BaseType placed = Rules.base(carrier.basePackageType);
        world.bases.put(baseId, new Base(baseId, carrier.playerId, carrier.basePackageType, carrier.x, carrier.y));
        world.units.remove(carrier.key());
        world.status = "Placed " + placed.name + ". Deployer consumed.";
        return true;
    }

    boolean craftItem(World world, String baseId, String craftableId) {
        Base base = world.bases.get(baseId);
        if (base == null) return false;
        CraftableItem item = CraftingRules.item(craftableId);
        if (item == null) {
            world.status = "Unknown craftable item: " + craftableId + ".";
            return false;
        }
        if (!item.canCraftAt(base.typeId)) {
            world.status = base.type().name + " cannot manufacture " + item.name + ".";
            return false;
        }
        if (!StationFuelRules.isOperational(base)) {
            world.status = base.type().name + " needs " + StationFuelRules.requirement(base.typeId).material().label + " to run.";
            return false;
        }
        boolean free = freeBuild(world, base);
        if (!free && !HangarStore.canAfford(base.inventory, item.requiredResources)) {
            if (world.logisticsSystem.queueCraftable(world, base, item)) return true;
            world.status = "Need " + Rules.formatCost(item.requiredResources) + " in " + base.type().name + " hangar.";
            return false;
        }
        if (!free) HangarStore.spend(base.inventory, item.requiredResources);
        HangarStore.add(base.inventory, item.outputMaterial, item.outputAmount);
        world.status = free ? "Dev manufactured " + item.outputLabel() + " for free." : "Manufactured " + item.outputLabel() + ".";
        return true;
    }

    private boolean freeBuild(World world, Base base) {
        return world.devFreeBuildFor(base.playerId);
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

    private void spawnShipFor(World world, String playerId, int unitId, String type, double x, double y) {
        Unit unit = new Unit(playerId, unitId, type, x, y);
        world.units.put(unit.key(), unit);
    }

    private int nextUnitId(World world, String playerId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
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
