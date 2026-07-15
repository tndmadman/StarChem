package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NpcStrategicDirectorValidator {
    private NpcStrategicDirectorValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC strategic director validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("WAIT", "NPC Strategic Validator", 0x50BEFF);
        World world = new World("NPC Strategic Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        NpcFaction faction = corsairs();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.DEFEATED,
                "empty faction did not begin defeated");

        AiDevCommands.spawnCorsairs(world);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.STABILIZE_ECONOMY,
                "new faction did not prioritize its weak economy");

        Base home = firstFactionBase(world, faction.id());
        HangarStore.add(home.inventory, Material.FUEL, faction.fuelReserve() + 20.0);
        addUnit(world, faction.id(), "prospector", home.x + 180, home.y);
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.ESTABLISH,
                "stable starting economy did not establish a second station");

        addBase(world, faction.id(), "laboratory", home.x + 500, home.y + 120);
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.RESEARCH,
                "research-capable faction did not prioritize missing doctrine");

        for (String topicId : faction.researchTopicIds()) world.completeResearch(faction.id(), topicId);
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.FORTIFY,
                "researched faction did not finish its local infrastructure");

        addBase(world, faction.id(), "shipyard", home.x - 500, home.y + 150);
        addBase(world, faction.id(), "manufacturing", home.x, home.y - 520);
        addUnit(world, faction.id(), "hauler", home.x + 210, home.y + 110);
        addUnit(world, faction.id(), "salvager", home.x + 240, home.y + 140);
        addUnit(world, faction.id(), "deep_miner", home.x - 210, home.y + 110);
        while (armedCount(world, faction.id()) < faction.targetFleetSize()) {
            addUnit(world, faction.id(), "frigate", home.x - 220, home.y - 120);
        }

        advance(world, faction, 7);
        requireState(world, faction, NpcStrategicState.PREPARE_RAID,
                "mature faction did not assemble for a raid");
        advance(world, faction, 7);
        requireState(world, faction, NpcStrategicState.RAID,
                "prepared faction did not enter its raid window");
        advance(world, faction, 10);
        requireState(world, faction, NpcStrategicState.EXPAND,
                "completed raid cycle did not request expansion");

        Unit threat = new Unit("STRATEGY_ENEMY", 99_001, "frigate", home.x + 120, home.y + 80);
        world.units.put(threat.key(), threat);
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.FORTIFY,
                "nearby armed threat did not override expansion");
        world.units.remove(threat.key());

        List<Unit> combat = factionCombat(world, faction.id());
        for (int i = 0; i < Math.min(3, combat.size()); i++) {
            Unit unit = combat.get(i);
            unit.hp = unit.type().maxHp * 0.20;
        }
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.RETREAT,
                "major fleet damage did not trigger retreat");
        for (Unit unit : combat) unit.hp = unit.type().maxHp;

        world.bases.values().removeIf(base -> faction.id().equals(base.playerId));
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.RECOVER,
                "stationless survivors did not enter recovery");

        world.units.values().removeIf(unit -> faction.id().equals(unit.playerId));
        refresh(world, faction);
        requireState(world, faction, NpcStrategicState.DEFEATED,
                "total asset loss did not enter defeated state");

        int transitions = NpcStrategicDirector.transitionCount(world, faction);
        advance(world, faction, 12);
        require(NpcStrategicDirector.transitionCount(world, faction) == transitions,
                "stable defeated state emitted duplicate transitions");
    }

    private static void refresh(World world, NpcFaction faction) {
        advance(world, faction, Math.max(4, (int)Math.ceil(faction.orderSeconds()) + 1));
    }

    private static void advance(World world, NpcFaction faction, int seconds) {
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        for (int i = 0; i < seconds; i++) NpcStrategicDirector.update(world, faction, 1.0);
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Base firstFactionBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        throw new IllegalStateException("Corsair home station is missing");
    }

    private static void addBase(World world, String factionId, String type, double x, double y) {
        String id = factionId + ":STRATEGY_B" + (world.bases.size() + 1);
        world.bases.put(id, new Base(id, factionId, type, x, y));
    }

    private static void addUnit(World world, String factionId, String type, double x, double y) {
        int id = 10_000;
        while (world.units.containsKey(Unit.key(factionId, id))) id++;
        Unit unit = new Unit(factionId, id, type, x + id % 7, y + id % 11);
        world.units.put(unit.key(), unit);
    }

    private static int armedCount(World world, String factionId) {
        int count = 0;
        for (Unit unit : world.units.values()) {
            if (factionId.equals(unit.playerId) && unit.hp > 0 && WeaponRules.armed(unit.type())) count++;
        }
        return count;
    }

    private static List<Unit> factionCombat(World world, String factionId) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (factionId.equals(unit.playerId) && unit.hp > 0 && WeaponRules.armed(unit.type())) out.add(unit);
        }
        return out;
    }

    private static void requireState(World world, NpcFaction faction, NpcStrategicState expected,
                                     String message) {
        NpcStrategicState actual = NpcStrategicDirector.state(world, faction);
        require(actual == expected, message + ": expected " + expected + ", found " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
