package com.tndmadman.rts;

import java.util.Iterator;

/**
 * Handles local NPC collapse after a system loses every living station.
 *
 * This system must never relocate faction assets between galaxy systems. An
 * organized faction can legitimately own expeditions and footholds outside its
 * home system; cross-system retreat belongs to an explicit strategic order.
 */
final class NpcCollapseSystem {
    private NpcCollapseSystem() { }

    static void removeShipsWithoutStations(World world) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled()) continue;
            if (hasLivingStation(world, faction.id())) continue;
            clearShips(world, faction);
        }
    }

    private static boolean hasLivingStation(World world, String playerId) {
        for (Base base : world.bases.values()) {
            if (base.playerId.equals(playerId) && base.hp > 0) return true;
        }
        return false;
    }

    private static void clearShips(World world, NpcFaction faction) {
        int removed = 0;
        Iterator<Unit> it = world.units.values().iterator();
        while (it.hasNext()) {
            Unit unit = it.next();
            if (!unit.playerId.equals(faction.id()) || unit.hp <= 0) continue;
            world.explodeUnit(unit);
            it.remove();
            removed++;
        }
        if (removed > 0) {
            world.status = faction.name() + " lost all stations. Remaining ships were removed.";
            AiDevLog.add(world, faction, "all stations lost; removed " + removed + " remaining ship(s)");
        }
    }
}
