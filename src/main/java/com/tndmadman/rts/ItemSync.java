package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.List;

final class ItemSync {
    private ItemSync() { }

    static void apply(World world, List<ItemState> states) {
        world.items.clear();
        world.nextWorldItemId = 1;
        for (ItemState state : states) {
            EnumMap<Material, Double> cargo = new EnumMap<>(Material.class);
            CargoCodec.readInto(state.cargo(), cargo);
            WorldItem item = new WorldItem(state.id(), state.x(), state.y(), cargo);
            if (item.empty()) continue;
            world.items.add(item);
            world.nextWorldItemId = Math.max(world.nextWorldItemId, item.id + 1);
        }
    }
}
