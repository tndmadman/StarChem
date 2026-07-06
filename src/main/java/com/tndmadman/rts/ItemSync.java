package com.tndmadman.rts;

import java.util.List;

final class ItemSync {
    private ItemSync() { }

    static void apply(World world, List<ItemState> states) {
        world.items.clear();
        world.nextWorldItemId = 1;
        for (ItemState state : states) {
            Material material = material(state.material());
            WorldItem item = new WorldItem(state.id(), material, state.amount(), state.x(), state.y(), state.vx(), state.vy(), state.angle(), state.spin());
            if (item.empty()) continue;
            world.items.add(item);
            world.nextWorldItemId = Math.max(world.nextWorldItemId, item.id + 1);
        }
    }

    private static Material material(String value) {
        try { return Material.valueOf(value); }
        catch (Exception ignored) { return Material.SCRAP_METAL; }
    }
}
