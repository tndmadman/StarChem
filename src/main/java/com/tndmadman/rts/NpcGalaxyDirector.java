package com.tndmadman.rts;

/**
 * Coordinates organized-faction strategy, persistent galaxy expeditions, and
 * local squad combat. Strategic review and expedition timers advance only from
 * the faction home. Expedition travel orders are reasserted before squad combat
 * so transit remains authoritative while establishing and defending fleets can
 * immediately use coordinated combat behavior.
 */
final class NpcGalaxyDirector {
    void update(World world, double dt) {
        if (world == null || dt <= 0) return;
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;
            NpcStrategicState strategy = NpcStrategicDirector.update(world, faction, dt);
            NpcExpeditionSystem.update(world, faction, strategy, dt);
            NpcSquadCombatSystem.update(world, faction, strategy, dt);
        }
    }
}
