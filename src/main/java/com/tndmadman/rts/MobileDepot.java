package com.tndmadman.rts;

final class MobileDepot {
    private static final double RANGE = 125;
    private static final double RATE = 120;

    private MobileDepot() { }

    static boolean isDepot(Unit unit) { return unit != null && "freighter".equals(unit.shipTypeId); }
    static boolean isHauler(Unit unit) { return unit != null && "hauler".equals(unit.shipTypeId); }
    static boolean haulerCanDrain(Unit unit) { return isDepot(unit) || isSalvager(unit); }
    static double range(Unit unit) { return RANGE + (unit == null ? 0 : unit.type().size.scale * 18); }

    private static boolean isSalvager(Unit unit) { return unit != null && "salvager".equals(unit.shipTypeId); }

    static Unit preferredFor(World world, Unit miner, Base base) {
        if (isHauler(miner)) return null;
        Unit best = null;
        double baseDist = base == null ? Double.MAX_VALUE : Calc.distance(miner.x, miner.y, base.x, base.y);
        double bestDist = baseDist;
        for (Unit unit : world.units.values()) {
            if (unit == miner || !isDepot(unit)) continue;
            if (!unit.playerId.equals(miner.playerId) || unit.freeCargo() <= 1) continue;
            double d = Calc.distance(miner.x, miner.y, unit.x, unit.y);
            if (d < bestDist) { best = unit; bestDist = d; }
        }
        return best;
    }

    static boolean transfer(Unit miner, Unit depot, double dt) {
        if (isHauler(miner)) return false;
        if (miner.cargoUsed() <= 0.05 || depot == null || depot.freeCargo() <= 0.05) return false;
        if (Calc.distance(miner.x, miner.y, depot.x, depot.y) > range(depot)) return false;
        return moveCargo(miner, depot, Math.min(RATE * dt, Math.min(miner.cargoUsed(), depot.freeCargo())));
    }

    static boolean drainTo(Unit hauler, Unit depot, double dt) {
        if (!isHauler(hauler) || depot == null || depot.cargoUsed() <= 0.05 || hauler.freeCargo() <= 0.05) return false;
        if (!haulerCanDrain(depot)) return false;
        if (Calc.distance(hauler.x, hauler.y, depot.x, depot.y) > range(depot)) return false;
        return moveCargo(depot, hauler, Math.min(RATE * dt, Math.min(depot.cargoUsed(), hauler.freeCargo())));
    }

    private static boolean moveCargo(Unit from, Unit to, double amount) {
        double remaining = amount;
        for (Material material : Material.values()) {
            if (remaining <= 0.001) break;
            double held = from.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            from.inventory.put(material, held - take);
            if (from.inventory.getOrDefault(material, 0.0) <= 0.05) from.inventory.remove(material);
            to.addCargo(material, take);
            remaining -= take;
        }
        from.unloadingThisFrame = true;
        to.unloadingThisFrame = true;
        return amount - remaining > 0.001;
    }
}
