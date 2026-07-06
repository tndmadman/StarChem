package com.tndmadman.rts;

final class WorkSystem {
    void update(World world, Unit unit, double dt) {
        if (unit.task != UnitTask.AUTO_HARVEST) return;
        ResourceNode node = world.findResource(unit.automationResourceId);
        if (node == null || !node.active) {
            if (unit.freeCargo() > 0.05 && world.scoutRetarget(unit, node)) return;
            if (unit.cargoUsed() > 0.05) world.sendToNearestBase(unit);
            else unit.task = UnitTask.IDLE;
            return;
        }
        ShipType type = unit.type();
        if (!type.harvestKinds.contains(node.kind)) {
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
            world.moveTowardOrbit(unit, node.x, node.y, node.radius + type.orbitRadius);
            return;
        }
        double gain = Math.min(node.harvestRate * dt, Math.min(node.amount, unit.freeCargo()));
        if (gain > 0) {
            node.amount -= gain;
            ResourceSync.mark(world, node);
            unit.addCargo(node.material, gain);
        }
        if (node.amount <= 0.05) {
            node.deplete();
            ResourceSync.mark(world, node);
            ProceduralAudio.playResourceDepleted(node.material);
            if (unit.freeCargo() > 0.05 && world.scoutRetarget(unit, node)) return;
            world.status = node.name + " depleted. Returning cargo and relocating deposit.";
            if (unit.cargoUsed() > 0.05) world.sendToNearestBase(unit);
            else unit.task = UnitTask.IDLE;
            return;
        }
        world.orbitAround(unit, node.x, node.y, node.radius + type.orbitRadius, dt, 0.7);
    }
}
