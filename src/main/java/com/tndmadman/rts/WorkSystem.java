package com.tndmadman.rts;

final class WorkSystem {
    void update(World world, Unit unit, double dt) {
        if (unit.task != UnitTask.AUTO_HARVEST) return;
        ResourceNode node = world.findResource(unit.automationResourceId);
        if (node == null || !node.active) {
            ResourceNetDebug.workState(world, unit, node, node == null ? "target-missing" : "target-inactive");
            if (unit.freeCargo() <= 0.05) {
                unit.automationResourceId = -1;
                world.sendToNearestBase(unit);
                return;
            }
            if (world.scoutRetarget(unit, node)) return;
            abandonTarget(world, unit);
            return;
        }
        ShipType type = unit.type();
        if (!type.harvestKinds.contains(node.kind)) {
            unit.automationResourceId = -1;
            unit.task = UnitTask.IDLE;
            return;
        }
        if (type.scoutRange > 0 && !type.harvestKinds.isEmpty() && !unit.miningAnchorSet) unit.setMiningAnchor(node.x, node.y);
        if (unit.freeCargo() <= 0.05) {
            world.sendToNearestBase(unit);
            return;
        }
        double range = type.harvestRange + node.radius;
        if (Calc.distance(unit.x, unit.y, node.x, node.y) > range) {
            ResourceNetDebug.workState(world, unit, node, "moving-to-resource");
            world.moveTowardOrbit(unit, node.x, node.y, node.radius + type.orbitRadius);
            return;
        }
        ResourceNetDebug.workState(world, unit, node, "mining");
        double gain = Math.min(node.harvestRate * SystemModifierRules.miningYield(world) * SystemControlBonuses.miningYield(world, unit.playerId) * dt, Math.min(node.amount, unit.freeCargo()));
        if (gain > 0) {
            node.amount -= gain;
            ResourceSync.mark(world, node);
            unit.addCargo(node.material, gain);
        }
        if (node.amount <= 0.05) {
            node.deplete();
            ResourceSync.mark(world, node);
            SystemAudio.playResourceDepleted(world, node.material);
            if (unit.freeCargo() <= 0.05) {
                unit.automationResourceId = -1;
                world.status = node.name + " depleted. Cargo full, returning to unload.";
                world.sendToNearestBase(unit);
                return;
            }
            if (world.scoutRetarget(unit, node)) return;
            world.status = node.name + " depleted. Waiting at assigned mining area for another deposit.";
            abandonTarget(world, unit);
            return;
        }
        world.orbitAround(unit, node.x, node.y, node.radius + type.orbitRadius, dt, 0.7);
    }

    private void abandonTarget(World world, Unit unit) {
        unit.automationResourceId = -1;
        if (!world.returnToMiningAnchor(unit)) unit.task = UnitTask.IDLE;
    }
}
