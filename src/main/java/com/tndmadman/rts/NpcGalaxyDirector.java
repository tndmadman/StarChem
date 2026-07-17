package com.tndmadman.rts;

/** Coordinates organized-faction strategy, construction, expeditions, combat, logistics, and recovery. */
final class NpcGalaxyDirector {
    void update(World world, double dt) {
        if (world == null || dt <= 0) return;
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;

            NpcStrategicState strategy = NpcStrategicDirector.update(world, faction, dt);
            NpcStationDeployerRecoverySystem.update(world, faction, strategy);
            NpcStationConstructionSystem.update(world, faction, dt);
            if (NpcFactionRuntime.homeSystemIdFor(faction).equals(world.activeSystemId())) {
                NpcWorkerProductionSystem.update(world, faction);
            }
            NpcExpeditionSystem.update(world, faction, strategy, dt);
            NpcSquadCombatSystem.update(world, faction, strategy, dt);
            NpcMobileDepotSystem.update(world, faction);

            boolean hasLocalStation = world.bases.values().stream()
                    .anyMatch(base -> faction.id().equals(base.playerId) && base.hp > 0);
            if (hasLocalStation
                    || !NpcExpeditionSystem.protectsStationlessCurrentSystem(world, faction)) {
                NpcRecoverySystem.update(world, faction);
            }
            NpcRepairEvacuationSystem.update(world, faction, dt);
        }
        AiBrainLog.observe(world);
    }
}
