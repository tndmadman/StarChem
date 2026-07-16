package com.tndmadman.rts;

import java.util.Set;

public final class NpcStrategicStabilityValidator {
    private NpcStrategicStabilityValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC strategic stability validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("WAIT", "NPC Strategic Stability Validator", 0x50BEFF);
        World world = new World("NPC Strategic Stability Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        NpcFaction faction = corsairs();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();

        for (int i = 1; i <= faction.maxStations(); i++) {
            String id = faction.id() + ":STABILITY_B" + i;
            Base base = new Base(id, faction.id(), i == 1 ? "outpost" : "shipyard",
                    2800 + i * 420, 3600 + (i % 2) * 520);
            if (i == 1) base.inventory.put(Material.FUEL, 500.0);
            world.bases.put(id, base);
        }

        int nextUnit = 90_000;
        for (int i = 0; i < faction.maxWorkers(); i++) {
            addUnit(world, faction, nextUnit++, "prospector", 3300 + i * 80, 4200);
        }
        for (int i = 0; i < faction.targetFleetSize(); i++) {
            addUnit(world, faction, nextUnit++, "frigate", 3600 + i * 70, 4500);
        }
        addUnit(world, faction, nextUnit++, "hauler", 3500, 4000);
        addUnit(world, faction, nextUnit++, "freighter", 3600, 4000);
        addUnit(world, faction, nextUnit, "deep_miner", 3700, 4000);

        for (String topicId : faction.researchTopicIds()) {
            world.completeResearch(faction.id(), topicId);
        }
        world.saveActiveSystem();
        NpcStrategicDirector.onSpawned(world, faction);

        NpcFactionCapacitySnapshot capacity =
                NpcFactionCapacitySystem.snapshot(world, faction);
        require(capacity.stationCommitments() >= faction.maxStations(),
                "fixture did not reach the organized-faction station cap");

        for (int i = 0; i < 40; i++) {
            NpcStrategicState state = NpcStrategicDirector.update(world, faction, 3.0);
            require(state != NpcStrategicState.EXPAND,
                    "strategy entered EXPAND after the station cap was exhausted");
            require(!NpcExpeditionSystem.snapshot(world, faction).active(),
                    "expedition started after the station cap was exhausted");
        }
    }

    private static void addUnit(World world, NpcFaction faction, int unitId,
                                String typeId, double x, double y) {
        Unit unit = new Unit(faction.id(), unitId, typeId, x, y);
        world.units.put(unit.key(), unit);
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
