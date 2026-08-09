package com.tndmadman.rts;

final class AUnitWork {
    private AUnitWork() { }

    static boolean apply(World world, HarvestCommand command) {
        if (world == null || command == null) return false;
        Unit unit = world.units.get(Unit.key(command.playerId(), command.unitId()));
        ResourceNode node = world.findResource(command.resourceId());
        ResourceNetDebug.hostWorkOrder(world, command, unit, node);
        if (!valid(world, unit, node, command)) return false;
        LogisticsRouteSystem.releaseForManualCommand(world, unit.key());
        UnitCommandQueueSystem.legacyReplace(world, unit);
        unit.setMiningAnchor(node.x, node.y);
        unit.startAutoHarvest(node.id);
        return true;
    }

    private static boolean valid(World world, Unit unit, ResourceNode node, HarvestCommand command) {
        return unit != null
                && !ProductionSystem.refitReserved(world, unit.key())
                && unit.playerId.equals(command.playerId())
                && node != null
                && node.active
                && node.amount > 0.05
                && unit.type().harvestKinds.contains(node.kind)
                && VisibilityRules.resourceStage(world, command.playerId(), node)
                .atLeast(IntelWarfareSystem.DetectionStage.IDENTIFIED);
    }
}
