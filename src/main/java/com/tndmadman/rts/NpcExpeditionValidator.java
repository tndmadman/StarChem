package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        validatePrelaunchAbortRefund();
        validateTransitFailureAndRecovery();
        validateTargetCaptureInvalidation();
    }

    private static void validateSuccessfulPersistentEstablishment() {
        Fixture fixture = fixture("NPC Expedition Success");
        EnumMap<Material, Double> beforeReservation = galaxyMaterials(
                fixture.world, fixture.faction.id());
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        require(reserved.state() == NpcExpeditionState.ASSEMBLING,
                "expedition did not persist through PLANNING and RESERVING");
        require(reserved.route().size() >= 2
                        && StarSystems.CORSAIR_SYSTEM_ID.equals(reserved.route().get(0))
                        && reserved.targetSystemId().equals(reserved.route().get(reserved.route().size() - 1)),
                "expedition did not own a complete source-to-target route");
        require(!reserved.builderKey().isBlank() && !reserved.workerKey().isBlank()
                        && reserved.combatKeys().size() >= 2,
                "expedition reservation did not own its required roster");
        require(!reserved.supplies().isEmpty(),
                "expedition reserved no strategic supplies");
        assertMaterialDelta(beforeReservation,
                galaxyMaterials(fixture.world, fixture.faction.id()), reserved.supplies(),
                "expedition reservation");

        Set<String> roster = roster(reserved);
        require(roster.stream().allMatch(key -> NpcExpeditionSystem.ownsUnit(fixture.world, key)),
                "reserved roster was not protected by one authoritative expedition plan");
        require(!targetHasCorsairStation(fixture.world, reserved.targetSystemId()),
                "expedition created a completed foothold before travel");

        String backgroundView = unrelatedSystem(fixture.world, reserved.sourceSystemId());
        fixture.world.activateSystem(backgroundView);
        fixture.world.update(1.0);
        NpcExpeditionSnapshot persisted = NpcExpeditionSystem.snapshot(
                fixture.world, fixture.faction);
        require(persisted.active()
                        && persisted.targetSystemId().equals(reserved.targetSystemId())
                        && roster(persisted).equals(roster),
                "expedition plan or roster changed during background simulation");

        assembleInstantlyAtIssuedPositions(fixture, persisted);
        NpcExpeditionSnapshot launching = updateDirectorAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "assembled expedition did not enter LAUNCHING");

        moveWholeRosterAlongRoute(fixture, launching);
        NpcExpeditionSnapshot establishing = NpcExpeditionSystem.snapshot(
                fixture.world, fixture.faction);
        require(establishing.state() == NpcExpeditionState.ESTABLISHING,
                "physically transferred expedition did not enter ESTABLISHING");
        require(allRosterInSystem(fixture.world, roster(establishing), establishing.targetSystemId()),
                "expedition roster did not arrive through real wormhole transfers");

        updateDirectorAtHome(fixture, 1.0);
        fixture.world.activateSystem(establishing.targetSystemId());
        NpcStationConstructionSnapshot construction = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(construction.active(),
                "arriving expedition did not start the Phase 7 construction pipeline");
        require(!targetHasCorsairStation(fixture.world, establishing.targetSystemId()),
                "foothold appeared before timed construction completed");

        int constructionGuard = 0;
        while (NpcStationConstructionSystem.snapshot(fixture.world, fixture.faction).active()
                && constructionGuard++ < 240) {
            fixture.world.updateCurrentSystem(1.0);
        }
        require(constructionGuard < 240,
                "expedition foothold construction did not finish");
        require(targetHasCorsairStation(fixture.world, establishing.targetSystemId()),
                "Phase 7 construction did not create the expedition foothold");
        require(!fixture.world.units.containsKey(establishing.builderKey()),
                "single-use expedition deployer survived foothold completion");

        NpcExpeditionSnapshot defending = updateDirectorAtHome(fixture, 1.0);
        require(defending.state() == NpcExpeditionState.DEFENDING
                        && defending.suppliesDelivered(),
                "completed foothold did not receive supplies and enter DEFENDING");
        Base foothold = firstCorsairBaseInSystem(
                fixture.world, defending.targetSystemId(), fixture.faction.id());
        require(foothold != null && !foothold.inventory.isEmpty(),
                "reserved supplies were not delivered to the completed foothold");

        NpcExpeditionSnapshot terminal = defending;
        for (int i = 0; i < 20 && terminal.state() != NpcExpeditionState.SUCCEEDED; i++) {
            terminal = updateDirectorAtHome(fixture, 1.0);
        }
        require(terminal.state() == NpcExpeditionState.SUCCEEDED,
                "surviving foothold did not satisfy expedition success criteria");
        require(terminal.cooldownSeconds() >= 149.0,
                "successful expedition did not start one authoritative cooldown");
        require(globalLiveUnitKeys(fixture.world, fixture.faction.id()).size()
                        == fixture.initialUnitKeys.size() - 1,
                "successful expedition duplicated or lost ships beyond its consumed deployer");
    }

    private static void validatePrelaunchAbortRefund() {
        Fixture fixture = fixture("NPC Expedition Prelaunch Abort");
        EnumMap<Material, Double> beforeReservation = galaxyMaterials(
                fixture.world, fixture.faction.id());
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        Set<String> roster = roster(reserved);

        fixture.world.activateSystem(reserved.targetSystemId());
        String invalidatingBaseId = fixture.faction.id() + ":B990";
        fixture.world.bases.put(invalidatingBaseId,
                new Base(invalidatingBaseId, fixture.faction.id(), "outpost",
                        fixture.world.width * 0.5, fixture.world.height * 0.5));
        fixture.world.saveActiveSystem();

        NpcExpeditionSnapshot aborting = updateDirectorAtHome(fixture, 1.0);
        require(aborting.state() == NpcExpeditionState.ABORTING,
                "prelaunch target invalidation did not enter ABORTING");
        NpcExpeditionSnapshot failed = updateDirectorAtHome(fixture, 1.0);
        require(failed.state() == NpcExpeditionState.FAILED && !failed.launched(),
                "prelaunch abort did not terminate deterministically");
        assertSameMaterials(beforeReservation,
                galaxyMaterials(fixture.world, fixture.faction.id()),
                "prelaunch abort did not refund supplies exactly once");
        require(allRosterInSystem(fixture.world, roster, StarSystems.CORSAIR_SYSTEM_ID),
                "prelaunch abort moved or duplicated reserved ships");
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Unit builder = fixture.world.units.get(reserved.builderKey());
        require(builder != null && builder.basePackageType.isBlank(),
                "prelaunch abort left the deployer package reserved");
    }

    private static void validateTransitFailureAndRecovery() {
        Fixture fixture = fixture("NPC Expedition Transit Failure");
        EnumMap<Material, Double> beforeReservation = galaxyMaterials(
                fixture.world, fixture.faction.id());
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        assembleInstantlyAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateDirectorAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "transit-loss fixture did not launch");

        String forwardSystem = launching.route().get(1);
        String movedCombatKey = launching.combatKeys().get(0);
        moveSelectedThroughGate(fixture.world, launching.sourceSystemId(), forwardSystem,
                Set.of(launching.builderKey(), movedCombatKey), fixture.faction.id());
        NpcExpeditionSnapshot split = updateDirectorAtHome(fixture, 1.0);
        require(split.launched(),
                "first physical wormhole transfer did not mark the expedition launched");

        fixture.world.activateSystem(forwardSystem);
        Unit lostBuilder = fixture.world.units.get(launching.builderKey());
        require(lostBuilder != null, "transferred deployer was not present in the transit system");
        lostBuilder.hp = 0;
        lostBuilder.x = fixture.world.width * 0.5;
        lostBuilder.y = fixture.world.height * 0.5;
        fixture.world.saveActiveSystem();

        NpcExpeditionSnapshot aborting = updateDirectorAtHome(fixture, 1.0);
        require(aborting.state() == NpcExpeditionState.ABORTING,
                "required deployer loss in transit did not enter ABORTING");
        updateDirectorAtHome(fixture, 1.0);

        moveSelectedThroughGate(fixture.world, forwardSystem, launching.sourceSystemId(),
                Set.of(movedCombatKey), fixture.faction.id());
        NpcExpeditionSnapshot failed = updateDirectorAtHome(fixture, 1.0);
        require(failed.state() == NpcExpeditionState.FAILED,
                "surviving transit ship did not complete deterministic return recovery");
        require(failed.cooldownSeconds() >= 44.0,
                "failed expedition did not apply its retry cooldown");
        require(allLiveKeysUnique(fixture.world, fixture.faction.id()),
                "transit failure duplicated a ship identity");
        require(globalLiveUnitKeys(fixture.world, fixture.faction.id()).size()
                        == fixture.initialUnitKeys.size() - 1,
                "transit failure removed or duplicated ships beyond the destroyed deployer");
        require(!sameMaterials(beforeReservation,
                        galaxyMaterials(fixture.world, fixture.faction.id())),
                "launched expedition incorrectly refunded committed supplies after transit loss");
    }

    private static void validateTargetCaptureInvalidation() {
        Fixture fixture = fixture("NPC Expedition Target Capture");
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        assembleInstantlyAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateDirectorAtHome(fixture, 1.0);

        String firstHop = launching.route().get(1);
        moveSelectedThroughGate(fixture.world, launching.sourceSystemId(), firstHop,
                Set.of(launching.combatKeys().get(0)), fixture.faction.id());
        NpcExpeditionSnapshot launched = updateDirectorAtHome(fixture, 1.0);
        require(launched.launched(),
                "target-capture fixture did not begin physical transit");

        String rivalId = "PHASE8_RIVAL";
        PlayerRegistry.register(rivalId, "Phase 8 Rival", 0xFF5533, false);
        fixture.world.activateSystem(launched.targetSystemId());
        String rivalBaseId = rivalId + ":B1";
        fixture.world.bases.put(rivalBaseId,
                new Base(rivalBaseId, rivalId, "outpost",
                        fixture.world.width * 0.5, fixture.world.height * 0.5));
        fixture.world.updateEnvironment(76.0);
        fixture.world.saveActiveSystem();
        GalaxyMapSystem captured = mapSystem(
                fixture.world.authoritativeGalaxyMapSnapshot(), launched.targetSystemId());
        require(captured != null && rivalId.equals(captured.controllerId()),
                "target system did not enter rival control for invalidation coverage");

        NpcExpeditionSnapshot aborting = updateDirectorAtHome(fixture, 1.0);
        require(aborting.state() == NpcExpeditionState.ABORTING,
                "target capture after launch did not invalidate the expedition");
        require(aborting.reason().contains("target") || aborting.reason().contains("route"),
                "target-capture abort did not report a strategic invalidation reason");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();
        NpcStationConstructionSystem.clear(world);
        NpcExpeditionSystem.clear(world);

        NpcFaction faction = corsairs();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Base source = addBase(world, faction, 1, "outpost", x, y);
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
        for (String topicId : faction.researchTopicIds()) world.completeResearch(faction.id(), topicId);
        world.saveActiveSystem();

        GalaxyMapSystem home = mapSystem(world.authoritativeGalaxyMapSnapshot(),
                StarSystems.CORSAIR_SYSTEM_ID);
        require(home != null && faction.id().equals(home.controllerId()),
                "expedition fixture does not begin with Corsair control of Corsair Den");
        return new Fixture(world, faction, source, new NpcGalaxyDirector(),
                globalLiveUnitKeys(world, faction.id()));
    }

    private static NpcExpeditionSnapshot startReservedPlan(Fixture fixture) {
        NpcExpeditionSnapshot snapshot = NpcExpeditionSnapshot.NONE;
        for (int i = 0; i < 120; i++) {
            snapshot = updateDirectorAtHome(fixture, 1.0);
            if (snapshot.active() && snapshot.state() == NpcExpeditionState.ASSEMBLING) return snapshot;
        }
        throw new IllegalStateException("strategically ready Corsairs did not reserve an expedition");
    }

    private static NpcExpeditionSnapshot updateDirectorAtHome(Fixture fixture, double dt) {
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        fixture.director.update(fixture.world, dt);
        fixture.world.saveActiveSystem();
        return NpcExpeditionSystem.snapshot(fixture.world, fixture.faction);
    }

    private static void assembleInstantlyAtIssuedPositions(Fixture fixture,
                                                           NpcExpeditionSnapshot snapshot) {
        fixture.world.activateSystem(snapshot.sourceSystemId());
        for (String key : roster(snapshot)) {
            Unit unit = fixture.world.units.get(key);
            require(unit != null, "reserved expedition unit disappeared before assembly: " + key);
            unit.x = unit.targetX;
            unit.y = unit.targetY;
            unit.task = UnitTask.IDLE;
        }
        fixture.world.saveActiveSystem();
    }

    private static void moveWholeRosterAlongRoute(Fixture fixture,
                                                  NpcExpeditionSnapshot snapshot) {
        Set<String> roster = roster(snapshot);
        for (int i = 0; i < snapshot.route().size() - 1; i++) {
            String from = snapshot.route().get(i);
            String to = snapshot.route().get(i + 1);
            moveSelectedThroughGate(fixture.world, from, to, roster, fixture.faction.id());
            NpcExpeditionSnapshot progressed = updateDirectorAtHome(fixture, 1.0);
            require(progressed.active(),
                    "expedition plan disappeared during physical route traversal");
            snapshot = progressed;
        }
    }

    private static void moveSelectedThroughGate(World world, String fromSystemId,
                                                String toSystemId, Set<String> keys,
                                                String factionId) {
        world.activateSystem(fromSystemId);
        WormholeGate gate = gateTo(world, toSystemId);
        require(gate != null,
                "planned wormhole route disappeared from " + fromSystemId + " to " + toSystemId);
        int moved = 0;
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            unit.x = gate.x;
            unit.y = gate.y;
            unit.targetX = gate.x;
            unit.targetY = gate.y;
            unit.wormholeCooldown = 0;
            moved++;
        }
        require(moved > 0, "no selected expedition ships were present for wormhole transfer");
        require(world.transferTouchingShips(factionId),
                "selected expedition ships did not transfer through a real wormhole");
        world.saveActiveSystem();
    }

    private static WormholeGate gateTo(World world, String targetSystemId) {
        for (WormholeGate gate : world.wormholes) {
            if (targetSystemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static boolean targetHasCorsairStation(World world, String targetSystemId) {
        return firstCorsairBaseInSystem(world, targetSystemId, Config.CORSAIRS_ID) != null;
    }

    private static Base firstCorsairBaseInSystem(World world, String systemId, String factionId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(systemId);
        try {
            for (Base base : world.bases.values()) {
                if (factionId.equals(base.playerId) && base.hp > 0) return base;
            }
            return null;
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static boolean allRosterInSystem(World world, Set<String> roster, String systemId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        world.activateSystem(systemId);
        try {
            for (String key : roster) {
                Unit unit = world.units.get(key);
                if (unit == null || unit.hp <= 0) return false;
            }
            return true;
        } finally {
            world.saveActiveSystem();
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static Set<String> roster(NpcExpeditionSnapshot snapshot) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (!snapshot.builderKey().isBlank()) keys.add(snapshot.builderKey());
        if (!snapshot.workerKey().isBlank()) keys.add(snapshot.workerKey());
        keys.addAll(snapshot.combatKeys());
        keys.addAll(snapshot.supportKeys());
        return keys;
    }

    private static String unrelatedSystem(World world, String excluded) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !system.id().equals(excluded)) return system.id();
        }
        throw new IllegalStateException("galaxy contains no background system");
    }

    private static Set<String> globalLiveUnitKeys(World world, String factionId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
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
            world.status = previousStatus;
        }
        return keys;
    }

    private static boolean allLiveKeysUnique(World world, String factionId) {
        int encountered = 0;
        Set<String> unique = new LinkedHashSet<>();
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null) continue;
                world.activateSystem(system.id());
                for (Unit unit : world.units.values()) {
                    if (!factionId.equals(unit.playerId) || unit.hp <= 0) continue;
                    encountered++;
                    unique.add(unit.key());
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return encountered == unique.size();
    }

    private static EnumMap<Material, Double> galaxyMaterials(World world, String factionId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        EnumMap<Material, Double> total = new EnumMap<>(Material.class);
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null) continue;
                world.activateSystem(system.id());
                for (Base base : world.bases.values()) {
                    if (!factionId.equals(base.playerId) || base.hp <= 0) continue;
                    mergeInventory(total, base.inventory);
                }
                for (Unit unit : world.units.values()) {
                    if (!factionId.equals(unit.playerId) || unit.hp <= 0) continue;
                    mergeInventory(total, unit.inventory);
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
        return total;
    }

    private static void mergeInventory(EnumMap<Material, Double> total,
                                       Map<Material, Double> inventory) {
        for (Map.Entry<Material, Double> entry : inventory.entrySet()) {
            total.merge(entry.getKey(), entry.getValue(), Double::sum);
        }
    }

    private static void assertMaterialDelta(Map<Material, Double> before,
                                            Map<Material, Double> after,
                                            Map<Material, Double> expected,
                                            String context) {
        for (Material material : Material.values()) {
            double delta = before.getOrDefault(material, 0.0)
                    - after.getOrDefault(material, 0.0);
            double reserved = expected.getOrDefault(material, 0.0);
            require(Math.abs(delta - reserved) < EPSILON,
                    context + " changed " + material + " by " + delta
                            + " instead of " + reserved);
        }
    }

    private static void assertSameMaterials(Map<Material, Double> expected,
                                            Map<Material, Double> actual,
                                            String message) {
        require(sameMaterials(expected, actual), message);
    }

    private static boolean sameMaterials(Map<Material, Double> first,
                                         Map<Material, Double> second) {
        for (Material material : Material.values()) {
            if (Math.abs(first.getOrDefault(material, 0.0)
                    - second.getOrDefault(material, 0.0)) >= EPSILON) return false;
        }
        return true;
    }

    private static GalaxyMapSystem mapSystem(GalaxyMapSnapshot map, String id) {
        if (map == null || map.systems() == null) return null;
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && id.equals(system.id())) return system;
        }
        return null;
    }

    private static Base addBase(World world, NpcFaction faction, int id,
                                String type, double x, double y) {
        String baseId = faction.id() + ":B" + id;
        Base base = new Base(baseId, faction.id(), type, x, y);
        world.bases.put(baseId, base);
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

    private record Fixture(World world, NpcFaction faction, Base source,
                           NpcGalaxyDirector director, Set<String> initialUnitKeys) { }
}
