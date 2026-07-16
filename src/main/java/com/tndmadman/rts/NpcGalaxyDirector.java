package com.tndmadman.rts;

/**
 * Coordinates organized-faction strategy, persistent galaxy expeditions, local
 * squad combat, and authoritative recovery. Strategic review and expedition
 * timers advance only from the faction home. Expedition travel orders are
 * reasserted before squad combat, then recovery runs last so repair and
 * evacuation orders remain authoritative for the following movement step.
 */
final class NpcGalaxyDirector {
    void update(World world, double dt) {
        if (world == null || dt <= 0) return;
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;
            NpcStrategicState strategy = NpcStrategicDirector.update(world, faction, dt);
            NpcExpeditionSystem.update(world, faction, strategy, dt);
            NpcSquadCombatSystem.update(world, faction, strategy, dt);
            boolean hasLocalStation = world.bases.values().stream()
                    .anyMatch(base -> faction.id().equals(base.playerId) && base.hp > 0);
            if (hasLocalStation
                    || !NpcExpeditionSystem.protectsStationlessCurrentSystem(world, faction)) {
                NpcRecoverySystem.update(world, faction);
            }
        }
    }
}
