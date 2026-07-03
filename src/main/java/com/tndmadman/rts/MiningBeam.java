package com.tndmadman.rts;

final class MiningBeam {
    private MiningBeam() { }

    static boolean visible(Unit unit, ResourceNode node) {
        if (unit == null || node == null || !node.active) return false;
        if (unit.task != UnitTask.AUTO_HARVEST) return false;
        if (!unit.type().harvestKinds.contains(node.kind)) return false;
        double range = unit.type().harvestRange + node.radius;
        return Calc.distance(unit.x, unit.y, node.x, node.y) <= range;
    }
}
