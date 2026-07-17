package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class NpcExpeditionValidator {
    private static final double EPSILON = 0.001;

    private NpcExpeditionValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC expedition validation passed.");
    }

    static void validateOrThrow() {
        validateSuccessfulPersistentEstablishment();
        validateFinalCapacityExpeditionEstablishment();
        validatePrelaunchAbortRefund();
        validateTransitFailureAndRecovery();
        validateTargetCaptureInvalidation();
    }

    private static void validateSuccessfulPersistentEstablishment() {
        Fixture fixture = fixture("NPC Expedition Success");
        EnumMap<Material, Double> before = galaxyMaterials(fixture.world(), fixture.faction().id());
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        Set<String> roster = roster(reserved);

        require(reserved.state() == NpcExpeditionState.ASSEMBLING,
                "expedition did not persist through planning and reservation");
        require(reserved.route().size() >= 2
                        && StarSystems.CORSAIR_SYSTEM_ID.equals(reserved.route().get(0))
                        && reserved.targetSystemId().equals(reserved.route().get(reserved.route().size() - 1)),
                "expedition did not own a complete route");
        require(!reserved.builderKey().isBlank() && !reserved.workerKey().isBlank()
                        && reserved.combatKeys().size() >= 2 && !reserved.supplies().isEmpty(),
                "expedition did not reserve its required roster and cargo");
        assertMaterialDelta(before, galaxyMaterials(fixture.world(), fixture.faction().id()),
                totalCommitment(fixture.faction(), reserved), "expedition reservation");
        require(roster.stream().allMatch(key -> NpcExpeditionSystem.ownsUnit(fixture.world(), key)),
                "reserved ships were not owned by one authoritative plan");
        require(!targetHasCorsairStation(fixture.world(), reserved.targetSystemId()),
                "expedition created an instant foothold");

        String backgroundView = unrelatedSystem(fixture.world(), reserved.sourceSystemId());
        fixture.world().activateSystem(backgroundView);
        fixture.world().update(1.0);
        NpcExpeditionSnapshot persisted = NpcExpeditionSystem.snapshot(
                fixture.world(), fixture.faction());
        require(persisted.active() && persisted.targetSystemId().equals(reserved.targetSystemId())
                        && roster(persisted).equals(roster),
                "background simulation lost or replaced the expedition plan");

        assembleAtIssuedPositions(fixture, persisted);
        NpcExpeditionSnapshot launching = updateAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "assembled fleet did not enter LAUNCHING");
        moveRosterAlongRoute(fixture, launching);

        NpcExpeditionSnapshot establishing = NpcExpeditionSystem.snapshot(
                fixture.world(), fixture.faction());
        require(establishing.state() == NpcExpeditionState.ESTABLISHING,
                "physical wormhole travel did not reach ESTABLISHING");
        require(allKeysInSystem(fixture.world(), roster(establishing), establishing.targetSystemId()),
                "expedition roster did not physically arrive in the target");

        updateAtHome(fixture, 1.0);
        fixture.world().activateSystem(establishing.targetSystemId());
        require(NpcStationConstructionSystem.snapshot(fixture.world(), fixture.faction()).active(),
                "arriving deployer did not start the Phase 7 pipeline");
        require(!targetHasCorsairStation(fixture.world(), establishing.targetSystemId()),
                "foothold completed before timed construction");

        int guard = 0;
        while (NpcStationConstructionSystem.snapshot(fixture.world(), fixture.faction()).active()
                && guard++ < 240) {
            fixture.world().updateCurrentSystem(1.0);
        }
        require(guard < 240 && targetHasCorsairStation(fixture.world(), establishing.targetSystemId()),
                "timed expedition foothold construction did not finish");
        require(!fixture.world().units.containsKey(establishing.builderKey()),
                "completed foothold did not consume its deployer");

        NpcExpeditionSnapshot defending = updateAtHome(fixture, 1.0);
        require(defending.state() == NpcExpeditionState.DEFENDING && defending.suppliesDelivered(),
                "completed foothold did not receive cargo and enter DEFENDING");
        Base foothold = firstFactionBase(fixture.world(), defending.targetSystemId(), fixture.faction().id());
        require(foothold != null && !foothold.inventory.isEmpty(),
                "completed foothold received no expedition cargo");

        NpcExpeditionSnapshot terminal = defending;
        for (int i = 0; i < 20 && terminal.state() != NpcExpeditionState.SUCCEEDED; i++) {
            terminal = updateAtHome(fixture, 1.0);
        }
        require(terminal.state() == NpcExpeditionState.SUCCEEDED
                        && terminal.cooldownSeconds() >= 149.0,
                "successful foothold did not complete with one cooldown");
        require(globalLiveKeys(fixture.world(), fixture.faction().id()).size()
                        == fixture.initialUnitKeys().size() - 1,
                "successful expedition duplicated or lost ships beyond its consumed deployer");
    }

    private static void validateFinalCapacityExpeditionEstablishment() {
        Fixture fixture = fixture("NPC Expedition Final Capacity");
        fixture.world().activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Base fifth = addBase(fixture.world(), fixture.faction(), 5, "shipyard",
                fixture.world().width * 0.5 - 1400,
                fixture.world().height * 0.5 + 1400);
        fifth.inventory.put(Material.FUEL, 100.0);
        fixture.world().saveActiveSystem();

        require(globalStationCount(fixture.world(), fixture.faction().id())
                        == fixture.faction().maxStations() - 1,
                "final-capacity fixture did not begin one station below the limit");
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        assembleAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "final-capacity expedition did not launch");
        moveRosterAlongRoute(fixture, launching);

        NpcExpeditionSnapshot establishing = NpcExpeditionSystem.snapshot(
                fixture.world(), fixture.faction());
        require(establishing.state() == NpcExpeditionState.ESTABLISHING,
                "final-capacity expedition did not reach establishment");
        updateAtHome(fixture, 1.0);
        fixture.world().activateSystem(establishing.targetSystemId());
        require(NpcStationConstructionSystem.snapshot(fixture.world(), fixture.faction()).active(),
                "the expedition's own commitment blocked its final allowed construction plan");

        int guard = 0;
        while (NpcStationConstructionSystem.snapshot(fixture.world(), fixture.faction()).active()
                && guard++ < 240) {
            fixture.world().updateCurrentSystem(1.0);
        }
        require(guard < 240 && targetHasCorsairStation(
                        fixture.world(), establishing.targetSystemId()),
                "the final allowed expedition foothold did not complete");
        require(globalStationCount(fixture.world(), fixture.faction().id())
                        == fixture.faction().maxStations(),
                "final-capacity establishment exceeded or failed to reach the station cap");
    }

    private static void validatePrelaunchAbortRefund() {
        Fixture fixture = fixture("NPC Expedition Prelaunch Abort");
        EnumMap<Material, Double> before = galaxyMaterials(fixture.world(), fixture.faction().id());
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        Set<String> roster = roster(reserved);

        fixture.world().activateSystem(reserved.targetSystemId());
        String id = fixture.faction().id() + ":B990";
        fixture.world().bases.put(id, new Base(id, fixture.faction().id(), "outpost",
                fixture.world().width * 0.5, fixture.world().height * 0.5));
        fixture.world().saveActiveSystem();

        require(updateAtHome(fixture, 1.0).state() == NpcExpeditionState.ABORTING,
                "prelaunch target invalidation did not enter ABORTING");
        NpcExpeditionSnapshot failed = updateAtHome(fixture, 1.0);
        require(failed.state() == NpcExpeditionState.FAILED && !failed.launched(),
                "prelaunch abort did not terminate deterministically");
        require(sameMaterials(before, galaxyMaterials(fixture.world(), fixture.faction().id())),
                "prelaunch abort did not refund package and cargo exactly once");
        require(allKeysInSystem(fixture.world(), roster, StarSystems.CORSAIR_SYSTEM_ID),
                "prelaunch abort moved or duplicated reserved ships");
        fixture.world().activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Unit builder = fixture.world().units.get(reserved.builderKey());
        require(builder != null && builder.basePackageType.isBlank(),
                "prelaunch abort left a package on the deployer");
    }

    private static void validateTransitFailureAndRecovery() {
        Fixture fixture = fixture("NPC Expedition Transit Failure");
        EnumMap<Material, Double> before = galaxyMaterials(fixture.world(), fixture.faction().id());
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        assembleAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "transit-loss fixture did not launch");

        String forward = launching.route().get(1);
        String escortKey = launching.combatKeys().get(0);
        moveSelected(fixture.world(), launching.sourceSystemId(), forward,
                Set.of(launching.builderKey(), escortKey), fixture.faction().id());
        require(updateAtHome(fixture, 1.0).launched(),
                "first wormhole transit did not commit the expedition");

        fixture.world().activateSystem(forward);
        Unit builder = fixture.world().units.get(launching.builderKey());
        require(builder != null, "transferred deployer was missing from transit");
        builder.hp = 0;
        builder.x = fixture.world().width * 0.5;
        builder.y = fixture.world().height * 0.5;
        fixture.world().saveActiveSystem();

        require(updateAtHome(fixture, 1.0).state() == NpcExpeditionState.ABORTING,
                "deployer loss in transit did not enter ABORTING");
        updateAtHome(fixture, 1.0);
        moveSelected(fixture.world(), forward, launching.sourceSystemId(),
                Set.of(escortKey), fixture.faction().id());
        NpcExpeditionSnapshot failed = updateAtHome(fixture, 1.0);
        require(failed.state() == NpcExpeditionState.FAILED
                        && failed.cooldownSeconds() >= 44.0,
                "surviving transit ship did not return into a failed-plan cooldown");
        require(globalLiveKeys(fixture.world(), fixture.faction().id()).size()
                        == fixture.initialUnitKeys().size() - 1,
                "transit failure duplicated or removed ships beyond the destroyed deployer");
        require(!sameMaterials(before, galaxyMaterials(fixture.world(), fixture.faction().id())),
                "launched expedition incorrectly refunded committed materials");
    }

    private static void validateTargetCaptureInvalidation() {
        Fixture fixture = fixture("NPC Expedition Target Capture");
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        assembleAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateAtHome(fixture, 1.0);
        String firstHop = launching.route().get(1);
        moveSelected(fixture.world(), launching.sourceSystemId(), firstHop,
                Set.of(launching.combatKeys().get(0)), fixture.faction().id());
        NpcExpeditionSnapshot launched = updateAtHome(fixture, 1.0);
        require(launched.launched(), "target-capture fixture never entered transit");

        String rival = "PHASE8_RIVAL";
        PlayerRegistry.register(rival, "Phase 8 Rival", 0xFF5533, false);
        fixture.world().activateSystem(launched.targetSystemId());
        fixture.world().bases.put(rival + ":B1", new Base(rival + ":B1", rival, "outpost",
                fixture.world().width * 0.5, fixture.world().height * 0.5));
        fixture.world().updateEnvironment(76.0);
        fixture.world().saveActiveSystem();
        GalaxyMapSystem captured = mapSystem(fixture.world().authoritativeGalaxyMapSnapshot(),
                launched.targetSystemId());
        require(captured != null && rival.equals(captured.controllerId()),
                "target system did not enter rival control");

        NpcExpeditionSnapshot aborting = updateAtHome(fixture, 1.0);
        require(aborting.state() == NpcExpeditionState.ABORTING,
                "captured target did not invalidate the launched expedition");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name, Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID, false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();
        NpcStationConstructionSystem.clear(world);
        NpcExpeditionSystem.clear(world);

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
        addUnit(world, faction, 82_001, "station_builder", x + 120, y);
        addUnit(world, faction, 82_002, "prospector", x - 120, y);
        addUnit(world, faction, 82_003, "prospector", x - 170, y + 80);
        addUnit(world, faction, 82_004, "prospector", x - 220, y + 140);
        addUnit(world, faction, 82_005, "frigate", x, y + 120);
        addUnit(world, faction, 82_006, "frigate", x, y - 120);
        addUnit(world, faction, 82_007, "destroyer", x + 180, y + 110);
        addUnit(world, faction, 82_008, "destroyer", x - 180, y - 110);
        addUnit(world, faction, 82_009, "frigate", x + 240, y - 160);
        addUnit(world, faction, 82_010, "frigate", x - 240, y + 160);
        addUnit(world, faction, 82_011, "hauler", x + 260, y + 210);
        addUnit(world, faction, 82_012, "salvager", x + 310, y + 230);
        addUnit(world, faction, 82_013, "deep_miner", x - 300, y + 220);
        for (String topic : faction.researchTopicIds()) world.completeResearch(faction.id(), topic);
        world.saveActiveSystem();

        GalaxyMapSystem home = mapSystem(world.authoritativeGalaxyMapSnapshot(),
                StarSystems.CORSAIR_SYSTEM_ID);
        require(home != null && faction.id().equals(home.controllerId()),
                "fixture did not begin under Corsair control");
        return new Fixture(world, faction, new NpcGalaxyDirector(),
                globalLiveKeys(world, faction.id()));
    }

    private static NpcExpeditionSnapshot startReservedPlan(Fixture fixture) {
        for (int i = 0; i < 120; i++) {
            NpcExpeditionSnapshot snapshot = updateAtHome(fixture, 1.0);
            if (snapshot.active() && snapshot.state() == NpcExpeditionState.ASSEMBLING) return snapshot;
        }
        throw new IllegalStateException("strategically ready Corsairs did not reserve an expedition");
    }

    private static NpcExpeditionSnapshot updateAtHome(Fixture fixture, double dt) {
        fixture.world().activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        fixture.director().update(fixture.world(), dt);
        fixture.world().saveActiveSystem();
        return NpcExpeditionSystem.snapshot(fixture.world(), fixture.faction());
    }

    private static void assembleAtIssuedPositions(Fixture fixture, NpcExpeditionSnapshot snapshot) {
        fixture.world().activateSystem(snapshot.sourceSystemId());
        for (String key : roster(snapshot)) {
            Unit unit = fixture.world().units.get(key);
            require(unit != null, "reserved unit disappeared before assembly: " + key);
            unit.x = unit.targetX;
            unit.y = unit.targetY;
            unit.task = UnitTask.IDLE;
        }
        fixture.world().saveActiveSystem();
    }

    private static void moveRosterAlongRoute(Fixture fixture, NpcExpeditionSnapshot snapshot) {
        Set<String> keys = roster(snapshot);
        for (int i = 0; i < snapshot.route().size() - 1; i++) {
            moveSelected(fixture.world(), snapshot.route().get(i), snapshot.route().get(i + 1),
                    keys, fixture.faction().id());
            snapshot = updateAtHome(fixture, 1.0);
            require(snapshot.active(), "expedition plan disappeared during route travel");
        }
    }

    private static void moveSelected(World world, String from, String to,
                                     Set<String> keys, String factionId) {
        world.activateSystem(from);
        WormholeGate gate = gateTo(world, to);
        require(gate != null, "planned wormhole disappeared: " + from + " -> " + to);
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
                "selected ships did not make a real wormhole transfer");
        world.saveActiveSystem();
    }

    private static WormholeGate gateTo(World world, String target) {
        for (WormholeGate gate : world.wormholes) if (target.equals(gate.toSystemId)) return gate;
        return null;
    }

    private static EnumMap<Material, Double> totalCommitment(NpcFaction faction,
                                                              NpcExpeditionSnapshot snapshot) {
        EnumMap<Material, Double> result = new EnumMap<>(Material.class);
        result.putAll(snapshot.supplies());
        BaseType foothold = Rules.base(faction.baseType());
        for (Cost cost : foothold.buildCost) result.merge(cost.material(), cost.amount(), Double::sum);
        return result;
    }

    private static Set<String> roster(NpcExpeditionSnapshot snapshot) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (!snapshot.builderKey().isBlank()) keys.add(snapshot.builderKey());
        if (!snapshot.workerKey().isBlank()) keys.add(snapshot.workerKey());
        keys.addAll(snapshot.combatKeys());
        keys.addAll(snapshot.supportKeys());
        return keys;
    }

    private static boolean targetHasCorsairStation(World world, String systemId) {
        return firstFactionBase(world, systemId, Config.CORSAIRS_ID) != null;
    }

    private static Base firstFactionBase(World world, String systemId, String factionId) {
        String previous = world.activeSystemId();
        String status = world.status;
        world.activateSystem(systemId);
        try {
            for (Base base : world.bases.values()) {
                if (factionId.equals(base.playerId) && base.hp > 0) return base;
            }
            return null;
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = status;
        }
    }

    private static boolean allKeysInSystem(World world, Set<String> keys, String systemId) {
        String previous = world.activeSystemId();
        String status = world.status;
        world.activateSystem(systemId);
        try {
            for (String key : keys) {
                Unit unit = world.units.get(key);
                if (unit == null || unit.hp <= 0) return false;
            }
            return true;
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = status;
        }
    }

    private static String unrelatedSystem(World world, String excluded) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !system.id().equals(excluded)) return system.id();
        }
        throw new IllegalStateException("galaxy contains no background system");
    }

    private static int globalStationCount(World world, String factionId) {
        String previous = world.activeSystemId();
        String status = world.status;
        int count = 0;
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null) continue;
                world.activateSystem(system.id());
                for (Base base : world.bases.values()) {
                    if (factionId.equals(base.playerId) && base.hp > 0) count++;
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = status;
        }
        return count;
    }

    private static Set<String> globalLiveKeys(World world, String factionId) {
        String previous = world.activeSystemId();
        String status = world.status;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null) continue;
                world.activateSystem(system.id());
                for (Unit unit : world.units.values()) {
                    if (factionId.equals(unit.playerId) && unit.hp > 0) keys.add(unit.key());
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = status;
        }
        return keys;
    }

    private static EnumMap<Material, Double> galaxyMaterials(World world, String factionId) {
        String previous = world.activeSystemId();
        String status = world.status;
        EnumMap<Material, Double> result = new EnumMap<>(Material.class);
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null) continue;
                world.activateSystem(system.id());
                for (Base base : world.bases.values()) {
                    if (factionId.equals(base.playerId) && base.hp > 0) merge(result, base.inventory);
                }
                for (Unit unit : world.units.values()) {
                    if (factionId.equals(unit.playerId) && unit.hp > 0) merge(result, unit.inventory);
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = status;
        }
        return result;
    }

    private static void merge(EnumMap<Material, Double> target, Map<Material, Double> source) {
        for (Map.Entry<Material, Double> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Double::sum);
        }
    }

    private static void assertMaterialDelta(Map<Material, Double> before,
                                            Map<Material, Double> after,
                                            Map<Material, Double> expected,
                                            String context) {
        for (Material material : Material.values()) {
            double actual = before.getOrDefault(material, 0.0) - after.getOrDefault(material, 0.0);
            double wanted = expected.getOrDefault(material, 0.0);
            require(Math.abs(actual - wanted) < EPSILON,
                    context + " changed " + material + " by " + actual + " instead of " + wanted);
        }
    }

    private static boolean sameMaterials(Map<Material, Double> first, Map<Material, Double> second) {
        for (Material material : Material.values()) {
            if (Math.abs(first.getOrDefault(material, 0.0)
                    - second.getOrDefault(material, 0.0)) >= EPSILON) return false;
        }
        return true;
    }

    private static GalaxyMapSystem mapSystem(GalaxyMapSnapshot map, String id) {
        if (map == null || map.systems() == null) return null;
        for (GalaxyMapSystem system : map.systems()) if (system != null && id.equals(system.id())) return system;
        return null;
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

    private record Fixture(World world, NpcFaction faction,
                           NpcGalaxyDirector director, Set<String> initialUnitKeys) { }
}
