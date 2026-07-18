package com.tndmadman.rts;

import java.util.Set;

public final class NpcResourceBudgetCapabilityValidator {
    private static final double EPSILON = 0.001;

    private NpcResourceBudgetCapabilityValidator() { }

    static void validateOrThrow() {
        PlayerRegistry.reset("WAIT", "NPC Budget Capability Validator", 0x50BEFF);
        World world = new World("NPC Budget Capability Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(world);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        NpcFaction faction = corsairs();
        Base home = firstBase(world, faction.id());
        home.inventory.clear();
        CraftableItem fuel = CraftingRules.preferredForOutput(Material.FUEL);
        require(fuel != null, "fuel recipe is missing");

        NpcBudgetPlan unavailable = NpcResourceBudget.plan(
                world, faction, NpcStrategicState.FORTIFY);
        for (Cost input : fuel.requiredResources) {
            require(unavailable.desired(NpcBudgetCategory.EMERGENCY_FUEL, input.material()) <= EPSILON,
                    "unavailable fuel recipe reserved " + input.material());
        }

        String id = faction.id() + ":BUDGET_MANUFACTURING";
        world.bases.put(id, new Base(id, faction.id(), "manufacturing",
                home.x + 520, home.y));
        NpcBudgetPlan available = NpcResourceBudget.plan(
                world, faction, NpcStrategicState.FORTIFY);
        boolean protectedInput = false;
        for (Cost input : fuel.requiredResources) {
            if (available.desired(NpcBudgetCategory.EMERGENCY_FUEL, input.material()) > EPSILON) {
                protectedInput = true;
                break;
            }
        }
        require(protectedInput,
                "operational manufacturing did not activate fuel-feedstock reservations");
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Base firstBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        throw new IllegalStateException("Corsair home station is missing");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}