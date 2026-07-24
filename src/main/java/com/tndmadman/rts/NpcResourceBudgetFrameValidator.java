package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.Set;

/** Regression coverage for one galaxy-wide NPC budget scan per world-update traversal. */
public final class NpcResourceBudgetFrameValidator {
    private NpcResourceBudgetFrameValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC resource budget frame validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("WAIT", "NPC Budget Frame Validator", 0x50BEFF);
        World world = new World("NPC Budget Frame Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(world);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        NpcFaction faction = corsairs();
        String origin = world.activeSystemId();
        Set<String> systems = new LinkedHashSet<>();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map != null && map.systems() != null) {
            for (GalaxyMapSystem system : map.systems()) {
                if (system != null && system.id() != null && !system.id().isBlank()) {
                    systems.add(system.id());
                }
            }
        }
        require(systems.size() >= 2,
                "multi-system budget fixture did not contain at least two systems");

        NpcResourceBudget.invalidate(world, faction);
        long before = NpcResourceBudget.scanCountForTesting(world, faction);
        NpcBudgetPlan first = NpcResourceBudget.plan(
                world, faction, NpcStrategicState.STABILIZE_ECONOMY);
        require(NpcResourceBudget.scanCountForTesting(world, faction) == before + 1,
                "initial budget frame did not perform exactly one galaxy scan");

        int switched = 0;
        for (String systemId : systems) {
            if (systemId.equals(origin)) continue;
            world.activateSystem(systemId);
            NpcBudgetPlan reused = NpcResourceBudget.plan(
                    world, faction, NpcStrategicState.STABILIZE_ECONOMY);
            require(reused == first,
                    "budget snapshot was rebuilt while traversing system " + systemId);
            require(NpcResourceBudget.scanCountForTesting(world, faction) == before + 1,
                    "galaxy scan count grew with active-system traversal");
            switched++;
        }
        require(switched > 0, "multi-system budget fixture never switched systems");

        world.activateSystem(origin);
        NpcBudgetPlan nextFrame = NpcResourceBudget.plan(
                world, faction, NpcStrategicState.STABILIZE_ECONOMY);
        require(nextFrame != first,
                "returning to a visited system did not advance the budget frame");
        require(NpcResourceBudget.scanCountForTesting(world, faction) == before + 2,
                "next budget frame did not perform exactly one refreshed galaxy scan");
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
