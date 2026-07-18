package com.tndmadman.rts;

import java.util.LinkedHashSet;
import java.util.Set;

/** Verifies that a completed expedition aborts cleanly after a required defender is lost. */
public final class NpcExpeditionDefenseValidator {
    private static final long FIXED_SEED = 41_137L;

    private NpcExpeditionDefenseValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem expedition defense validation passed.");
    }

    static void validateOrThrow() {
        Fixture fixture = fixture();
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        clearTarget(fixture, reserved.targetSystemId());
        assembleAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "defense expedition did not launch");
        moveRosterAlongRoute(fixture, launching);

        NpcExpeditionSnapshot establishing = NpcExpeditionSystem.snapshot(
                fixture.world, fixture.faction);
        require(establishing.state() == NpcExpeditionState.ESTABLISHING,
                "defense expedition did not reach establishment");
        updateAtHome(fixture, 1.0);
        fixture.world.activateSystem(establishing.targetSystemId());
        int guard = 0;
        while (NpcStationConstructionSystem.snapshot(fixture.world, fixture.faction).active()
                && guard++ < 240) {
            fixture.world.updateCurrentSystem(1.0);
        }
        require(guard < 240, "defense foothold construction timed out");

        NpcExpeditionSnapshot defending = updateAtHome(fixture, 1.0);
        require(defending.state() == NpcExpeditionState.DEFENDING,
                "defense fixture did not reach DEFENDING");
        fixture.world.activateSystem(defending.targetSystemId());
        Unit worker = fixture.world.units.get(defending.workerKey());
        require(worker != null, "defense fixture lost its worker before the test casualty");
        worker.hp = 0;
        fixture.world.saveActiveSystem();

        NpcExpeditionSnapshot aborting = updateAtHome(fixture, 1.0);
        require(aborting.state() == NpcExpeditionState.ABORTING
                        && aborting.reason().contains("defense force"),
                "required-unit loss did not abort foothold defense deterministically: " + aborting);
    }

    private static Fixture fixture() {
        PlayerRegistry.reset("WAIT", "NPC Expedition Defense Validator", 0x50BEFF);
        World world = new World("NPC Expedition Defense Validator",
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID, false);
        world.useSystemSeed(FIXED_SEED);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();
        NpcStationConstructionSystem.clear(world);
        NpcExpeditionSystem.clear(world);
        NpcStrategicDirector.clear(world);

        NpcFaction faction = corsairs();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        addBase(world, faction, 1, "outpost", x, y);
        addBase(world, faction, 2, "shipyard", x - 720, y);
        addBase(world, faction, 3, "laboratory", x + 720, y);
        addBase(world, faction, 4, "manufacturing", x, y + 720);
        for (Base base : world.bases.values()) {
            for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
        }
        addUnit(world, faction, 96_101, "station_builder", x + 120, y);
        addUnit(world, faction, 96_102, "prospector", x - 120, y);
        addUnit(world, faction, 96_103, "prospector", x - 170, y + 80);
        addUnit(world, faction, 96_104, "prospector", x - 220, y + 140);
        addUnit(world, faction, 96_105, "frigate", x, y + 120);
        addUnit(world, faction, 96_106, "frigate", x, y - 120);
        addUnit(world, faction, 96_107, "destroyer", x + 180, y + 110);
        addUnit(world, faction, 96_108, "destroyer", x - 180, y - 110);
        addUnit(world, faction, 96_109, "frigate", x + 240, y - 160);
        addUnit(world, faction, 96_110, "frigate", x - 240, y + 160);
        addUnit(world, faction, 96_111, "hauler", x + 260, y + 210);
        addUnit(world, faction, 96_112, "salvager", x + 310, y + 230);
        addUnit(world, faction, 96_113, "deep_miner", x - 300, y + 220);
        for (String topic : faction.researchTopicIds()) world.completeResearch(faction.id(), topic);
        world.saveActiveSystem();
        return new Fixture(world, faction, new NpcGalaxyDirector());
    }

    private static NpcExpeditionSnapshot startReservedPlan(Fixture fixture) {
        for (int i = 0; i < 120; i++) {
            NpcExpeditionSnapshot snapshot = updateAtHome(fixture, 1.0);
            if (snapshot.active() && snapshot.state() == NpcExpeditionState.ASSEMBLING) return snapshot;
        }
        throw new IllegalStateException("defense fixture did not reserve an expedition");
    }

    private static NpcExpeditionSnapshot updateAtHome(Fixture fixture, double dt) {
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        fixture.director.update(fixture.world, dt);
        fixture.world.saveActiveSystem();
        return NpcExpeditionSystem.snapshot(fixture.world, fixture.faction);
    }

    private static void clearTarget(Fixture fixture, String systemId) {
        fixture.world.activateSystem(systemId);
        fixture.world.units.values().removeIf(unit -> !fixture.faction.id().equals(unit.playerId));
        fixture.world.bases.values().removeIf(base -> !fixture.faction.id().equals(base.playerId));
        fixture.world.shots.removeIf(shot -> !fixture.faction.id().equals(shot.ownerId));
        fixture.world.saveActiveSystem();
    }

    private static void assembleAtIssuedPositions(Fixture fixture, NpcExpeditionSnapshot snapshot) {
        fixture.world.activateSystem(snapshot.sourceSystemId());
        for (String key : roster(snapshot)) {
            Unit unit = fixture.world.units.get(key);
            require(unit != null, "reserved unit disappeared before defense assembly: " + key);
            unit.x = unit.targetX;
            unit.y = unit.targetY;
            unit.task = UnitTask.IDLE;
        }
        fixture.world.saveActiveSystem();
    }

    private static void moveRosterAlongRoute(Fixture fixture, NpcExpeditionSnapshot snapshot) {
        Set<String> keys = roster(snapshot);
        for (int i = 0; i < snapshot.route().size() - 1; i++) {
            moveSelected(fixture.world, snapshot.route().get(i), snapshot.route().get(i + 1),
                    keys, fixture.faction.id());
            snapshot = updateAtHome(fixture, 1.0);
            require(snapshot.active(), "defense plan disappeared during route travel");
        }
    }

    private static void moveSelected(World world, String from, String to,
                                     Set<String> keys, String factionId) {
        world.activateSystem(from);
        WormholeGate gate = gateTo(world, to);
        require(gate != null, "defense wormhole disappeared: " + from + " -> " + to);
        int present = 0;
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            unit.x = gate.x;
            unit.y = gate.y;
            unit.targetX = gate.x;
            unit.targetY = gate.y;
            unit.wormholeCooldown = 0;
            present++;
        }
        require(present > 0 && world.transferTouchingShips(factionId),
                "defense ships did not transfer through the wormhole");
        world.saveActiveSystem();
    }

    private static WormholeGate gateTo(World world, String target) {
        for (WormholeGate gate : world.wormholes) if (target.equals(gate.toSystemId)) return gate;
        return null;
    }

    private static Set<String> roster(NpcExpeditionSnapshot snapshot) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (!snapshot.builderKey().isBlank()) keys.add(snapshot.builderKey());
        if (!snapshot.workerKey().isBlank()) keys.add(snapshot.workerKey());
        keys.addAll(snapshot.combatKeys());
        keys.addAll(snapshot.supportKeys());
        return keys;
    }

    private static Base addBase(World world, NpcFaction faction, int id,
                                String type, double x, double y) {
        String key = faction.id() + ":B" + id;
        Base base = new Base(key, faction.id(), type, x, y);
        world.bases.put(key, base);
        return base;
    }

    private static Unit addUnit(World world, NpcFaction faction, int id,
                                String type, double x, double y) {
        Unit unit = new Unit(faction.id(), id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
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

    private record Fixture(World world, NpcFaction faction, NpcGalaxyDirector director) { }
}
