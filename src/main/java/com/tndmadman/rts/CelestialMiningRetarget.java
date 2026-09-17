package com.tndmadman.rts;

/** Fast, allocation-light retargeting inside one exposed planet/moon extraction field. */
final class CelestialMiningRetarget {
    private CelestialMiningRetarget() { }

    static boolean isCelestial(ResourceNode node) {
        return node != null
                && node.celestialAnchorBodyId != null
                && !node.celestialAnchorBodyId.isBlank();
    }

    static boolean retargetWithinField(World world, Unit miner, ResourceNode depleted) {
        if (world == null || miner == null || !isCelestial(depleted) || miner.freeCargo() <= 0.05) return false;
        String bodyId = depleted.celestialAnchorBodyId;
        ResourceNode best = null;
        int bestAssigned = Integer.MAX_VALUE;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;

        for (ResourceNode candidate : world.resources) {
            if (candidate == null || candidate == depleted || !candidate.active
                    || !bodyId.equals(candidate.celestialAnchorBodyId)
                    || !miner.type().harvestKinds.contains(candidate.kind)) continue;

            int assigned = assignedCount(world, miner.playerId, candidate.id);
            double dx = miner.x - candidate.x;
            double dy = miner.y - candidate.y;
            double distanceSquared = dx * dx + dy * dy;
            if (best == null || assigned < bestAssigned
                    || (assigned == bestAssigned && distanceSquared < bestDistanceSquared)
                    || (assigned == bestAssigned && Math.abs(distanceSquared - bestDistanceSquared) <= 0.001
                    && candidate.id < best.id)) {
                best = candidate;
                bestAssigned = assigned;
                bestDistanceSquared = distanceSquared;
            }
        }

        if (best == null) return false;
        miner.startAutoHarvest(best.id);
        world.status = miner.type().name + " redirected to the next exposed " + best.name + ".";
        return true;
    }

    private static int assignedCount(World world, String playerId, int resourceId) {
        int count = 0;
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || !playerId.equals(unit.playerId)) continue;
            if (unit.automationResourceId != resourceId) continue;
            if (unit.task == UnitTask.AUTO_HARVEST || unit.task == UnitTask.RETURN_TO_STATION) count++;
        }
        return count;
    }
}
