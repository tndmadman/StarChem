package com.tndmadman.rts;

final class AWorkAnchor {
    private AWorkAnchor() { }

    static void apply(World world, Unit unit, int resourceId) {
        ResourceNode node = world.findResource(resourceId);
        if (node == null) return;
        unit.setMiningAnchor(node.x, node.y);
    }
}
