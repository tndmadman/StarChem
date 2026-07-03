package com.tndmadman.rts;

final class MobileDepot {
    private static final double RANGE = 125;
    private static final double RATE = 120;

    private MobileDepot() { }

    static boolean isDepot(Unit unit) { return unit != null && "freighter".equals(unit.shipTypeId); }
    static double range(Unit unit) { return RANGE + (unit == null ? 0 : unit.type().size.scale * 18); }

    static Unit preferredFor(World world, Unit miner, Base base) {
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
        if (miner.cargoUsed() <= 0.05 || depot == null || depot.freeCargo() <= 0.05) return false;
        if (Calc.distance(miner.x, miner.y, depot.x, depot.y) > range(depot)) return false;
        double remaining = Math.min(RATE * dt, Math.min(miner.cargoUsed(), depot.freeCargo()));
        for (Material material : Material.values()) {
            if (remaining <= 0.001) break;
            double held = miner.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            miner.inventory.put(material, held - take);
            if (miner.inventory.getOrDefault(material, 0.0) <= 0.05) miner.inventory.remove(material);
            depot.addCargo(material, take);
            remaining -= take;
        }
        miner.unloadingThisFrame = true;
        return true;
    }
}
