package com.tndmadman.rts;

import java.util.Iterator;

/**
 * Handles local NPC collapse after a system loses every living station.
 *
 * Organized factions use NpcRecoverySystem so expeditions can repair, rebuild,
 * evacuate, or remain alive while recovery is possible. Ships assigned to an
 * active Phase 8 transit or foothold plan are protected from stationless
 * recovery until that plan succeeds, aborts, or fails. Ordinary local NPC
 * factions retain their original immediate collapse behavior.
 */
final class NpcCollapseSystem {
    private NpcCollapseSystem() { }

    static void removeShipsWithoutStations(World world) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled()) continue;

            if (faction.behavior() == NpcBehavior.FACTION) {
                boolean hasStation = hasLivingStation(world, faction.id());
                if (hasStation || !NpcExpeditionSystem.protectsStationlessCurrentSystem(world, faction)) {
                    NpcRecoverySystem.update(world, faction);
                }
                if (hasStation) NpcWorkerProductionSystem.update(world, faction);
                continue;
            }

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
