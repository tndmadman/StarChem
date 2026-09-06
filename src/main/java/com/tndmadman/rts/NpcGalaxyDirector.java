package com.tndmadman.rts;

/** Coordinates organized-faction strategy, construction, expeditions, combat, logistics, and recovery. */
final class NpcGalaxyDirector {
    private static final double DECISION_INTERVAL_SECONDS = 0.20;

    void update(World world, double dt) {
        if (world == null || dt <= 0) return;
        double decisionDt = SimulationCadence.consume(world, "npc-galaxy-director", dt, DECISION_INTERVAL_SECONDS);
        if (decisionDt <= 0) return;

        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;

            NpcStrategicState strategy = NpcStrategicDirector.update(world, faction, decisionDt);
            NpcExpeditionReadinessSystem.ensureInfrastructureBuilder(world, faction, strategy);
            NpcStationDeployerRecoverySystem.update(world, faction, strategy);
            NpcStationConstructionSystem.update(world, faction, decisionDt);
            if (NpcFactionRuntime.homeSystemIdFor(faction).equals(world.activeSystemId())) {
                NpcWorkerProductionSystem.update(world, faction);
            }
            updateExpedition(world, faction, strategy, decisionDt);
            NpcSquadCombatSystem.update(world, faction, strategy, decisionDt);
            NpcMobileDepotSystem.update(world, faction);

            boolean hasLocalStation = false;
            for (Base base : world.bases.values()) {
                if (faction.id().equals(base.playerId) && base.hp > 0) {
                    hasLocalStation = true;
                    break;
                }
            }
            if (hasLocalStation
                    || !NpcExpeditionSystem.protectsStationlessCurrentSystem(world, faction)) {
                NpcRecoverySystem.update(world, faction);
            }
            NpcRepairEvacuationSystem.update(world, faction, decisionDt);
        }
        AiBrainLog.observe(world);
    }

    private void updateExpedition(World world, NpcFaction faction,
                                  NpcStrategicState strategy, double dt) {
        double step = dt;
        for (int pass = 0; pass < 3; pass++) {
            if (!NpcExpeditionReadinessSystem.allowProgress(
                    world, faction, strategy, step)) return;

            NpcExpeditionSnapshot before = NpcExpeditionSystem.snapshot(world, faction);
            NpcExpeditionSystem.update(world, faction, strategy, step);
            NpcExpeditionSnapshot after = NpcExpeditionSystem.snapshot(world, faction);
            step = 0;

            if (!after.active()) return;
            boolean bootstrapState = after.state() == NpcExpeditionState.PLANNING
                    || after.state() == NpcExpeditionState.RESERVING;
            boolean advanced = !before.active() || before.state() != after.state();
            if (!bootstrapState || !advanced) return;
        }
    }
}
