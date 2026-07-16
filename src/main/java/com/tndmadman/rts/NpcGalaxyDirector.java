package com.tndmadman.rts;

/**
 * Coordinates organized-faction strategy and persistent galaxy expeditions.
 * Strategic review and expedition timers advance only from the faction home,
 * while NpcExpeditionSystem reasserts local travel orders in every simulated
 * system after ordinary tactical AI.
 */
final class NpcGalaxyDirector {
    void update(World world, double dt) {
        if (world == null || dt <= 0) return;
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;
            NpcStrategicState strategy = NpcStrategicDirector.update(world, faction, dt);
            NpcExpeditionSystem.update(world, faction, strategy, dt);
        }
    }
}
