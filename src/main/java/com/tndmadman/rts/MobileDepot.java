package com.tndmadman.rts;

final class MobileDepot {
    static final String ROUTE_PREFIX = "MOBILE_DEPOT";
    private static final double RANGE = 125;
    private static final double RATE = 120;

    private MobileDepot() { }

    static boolean isDepot(Unit unit) { return unit != null && "freighter".equals(unit.shipTypeId); }
    static boolean isHauler(Unit unit) { return unit != null && "hauler".equals(unit.shipTypeId); }
    static boolean haulerCanDrain(Unit unit) { return isDepot(unit) || isSalvager(unit); }
    static double range(Unit unit) { return RANGE + (unit == null ? 0 : unit.type().size.scale * 18); }
    static boolean automatedRoute(Unit unit) {
        return isHauler(unit) && unit.logisticsRequestId != null
                && unit.logisticsRequestId.startsWith(ROUTE_PREFIX);
    }

    private static boolean isSalvager(Unit unit) { return unit != null && "salvager".equals(unit.shipTypeId); }

    static Unit preferredFor(World world, Unit miner, Base base) {
        if (world == null || miner == null || isHauler(miner)) return null;
        Unit best = null;
        double baseDist = base == null ? Double.MAX_VALUE : Calc.distance(miner.x, miner.y, base.x, base.y);
        double bestDist = baseDist;
        for (Unit unit : world.units.values()) {
            if (unit == miner || !isDepot(unit)) continue;
            if (!unit.playerId.equals(miner.playerId) || unit.freeCargo() <= 1) continue;
            if (NpcExpeditionSystem.ownsUnit(world, unit.key())
                    || NpcRecoverySystem.ownsUnit(world, unit)
                    || NpcRepairEvacuationSystem.ownsUnit(world, unit)) continue;
            double d = Calc.distance(miner.x, miner.y, unit.x, unit.y);
            if (d < bestDist) { best = unit; bestDist = d; }
        }
        return best;
    }

    static boolean transfer(Unit miner, Unit depot, double dt) {
        if (miner == null) return false;
        // Hauler cargo on an automated depot route is owned by HaulerSystem.
        // Returning true tells the generic World auto-unload pass not to dump
        // that cargo into whichever station happens to be closest.
        if (isHauler(miner)) return automatedRoute(miner);
        if (!isDepot(depot)) return false;
        if (miner.cargoUsed() <= 0.05 || depot.freeCargo() <= 0.05) return false;
        if (Calc.distance(miner.x, miner.y, depot.x, depot.y) > range(depot)) return false;
        return moveCargo(miner, depot, Math.min(RATE * Math.max(0, dt),
                Math.min(miner.cargoUsed(), depot.freeCargo())));
    }

    static boolean drainTo(Unit hauler, Unit depot, double dt) {
        if (!isHauler(hauler) || depot == null || depot.cargoUsed() <= 0.05
                || hauler.freeCargo() <= 0.05) return false;
        if (!haulerCanDrain(depot)) return false;
        if (Calc.distance(hauler.x, hauler.y, depot.x, depot.y) > range(depot)) return false;
        return moveCargo(depot, hauler, Math.min(RATE * Math.max(0, dt),
                Math.min(depot.cargoUsed(), hauler.freeCargo())));
    }

    static boolean unloadToBase(Unit hauler, Base base, double dt) {
        if (!isHauler(hauler) || base == null || hauler.cargoUsed() <= 0.05) return false;
        double range = Math.max(42.0, base.type().unloadRange * 0.72);
        if (Calc.distance(hauler.x, hauler.y, base.x, base.y) > range) return false;
        double amount = Math.min(Math.max(1.0, base.type().unloadRate) * Math.max(0, dt),
                hauler.cargoUsed());
        if (amount <= 0.001) return false;
        double remaining = amount;
        for (Material material : Material.values()) {
            if (remaining <= 0.001) break;
            double held = hauler.inventory.getOrDefault(material, 0.0);
            if (held <= 0.001) continue;
            double take = Math.min(held, remaining);
            hauler.inventory.put(material, held - take);
            if (hauler.inventory.getOrDefault(material, 0.0) <= 0.05) {
                hauler.inventory.remove(material);
            }
            HangarStore.add(base.inventory, material, take);
            remaining -= take;
        }
        hauler.unloadingThisFrame = true;
        return amount - remaining > 0.001;
    }

    private static boolean moveCargo(Unit from, Unit to, double amount) {
        if (from == null || to == null || amount <= 0.001) return false;
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
