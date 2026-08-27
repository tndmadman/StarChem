package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GalaxyEventValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private GalaxyEventValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem galaxy event validation passed.");
    }

    static void validateOrThrow() {
        validateDiscoveryAndMaterialization();
        validateHiddenEventsDoNotLeak();
        validateDeterministicMaterialization();
        validateClosingWormholeRejectsTransit();
        validateRuntimePersistenceShape();
    }

    private static void validateDiscoveryAndMaterialization() {
        World world = world(991_337L);
        String systemId = world.activeSystemId();
        String target = otherSystem(world, systemId);
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Unit scout = new Unit("SOLO", 77, Rules.STARTING_SHIP, x, y);
        world.units.put(scout.key(), scout);

        List<Object> events = new ArrayList<>();
        events.add(event("EV-RICH", "rich_rare_earths", systemId, x, y, 1000, Map.of()));
        events.add(event("EV-SALVAGE", "derelict_convoy", systemId, x, y, 1000, Map.of()));
        events.add(event("EV-DISTRESS", "distress_beacon", systemId, x, y, 1000, Map.of()));
        events.add(event("EV-PIRATE", "pirate_ambush", systemId, x, y, 1000, Map.of()));
        events.add(event("EV-ION", "ion_storm", systemId, x, y, 1000, Map.of()));
        events.add(event("EV-WORM", "unstable_wormhole", systemId, x, y, 1000,
                Map.of("targetSystemId", target, "targetX", Double.toString(x + 250), "targetY", Double.toString(y + 150))));
        GalaxyEventDirector.restore(world, runtime(systemId, events));
        GalaxyEventDirector.update(world, 0.25);

        require(world.resources.size() >= 4, "rich-resource event did not materialize ordinary resource nodes");
        require(world.items.size() >= 5, "derelict event did not materialize ordinary salvage items");
        require(hasOwner(world, "NPC_RAIDERS"), "NPC-backed encounter did not materialize raiders");
        require(hasOwner(world, "NPC_MINERS"), "distress encounter did not materialize a civilian NPC");
        require(SystemModifierRules.sensorRange(world) < StarSystems.get(systemId).modifiers().sensorRange(),
                "temporary environmental event modifier was not composed with system rules");
        require(hasGate(world, "EV-WORM:A"), "unstable wormhole did not materialize its source gate");

        List<GalaxyEventView> views = GalaxyEventDirector.viewsFor(world, "SOLO");
        require(views.size() == 6, "discovered event projection did not include all discovered events");
        List<String> rows = GalaxyEventWire.encodeRows(world, "SOLO");
        require(rows.size() == 6, "galaxy event wire did not serialize discovered events");
        for (String row : rows) require(GalaxyEventWire.decodeRow(row) != null, "galaxy event wire row did not decode");
    }

    private static void validateHiddenEventsDoNotLeak() {
        World world = world(44_221L);
        String systemId = world.activeSystemId();
        double x = world.width * 0.8;
        double y = world.height * 0.8;
        Unit scout = new Unit("SOLO", 1, Rules.STARTING_SHIP, world.width * 0.1, world.height * 0.1);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId,
                List.of(event("EV-HIDDEN", "rich_rare_earths", systemId, x, y, 1000, Map.of()))));
        GalaxyEventDirector.update(world, 0.1);
        require(GalaxyEventDirector.viewsFor(world, "SOLO").isEmpty(), "hidden event leaked into player projection");
        require(GalaxyEventWire.encodeRows(world, "SOLO").isEmpty(), "hidden event leaked into galaxy wire rows");
        require(world.resources.isEmpty(), "hidden resource event materialized before discovery");
    }

    private static void validateDeterministicMaterialization() {
        World first = world(123_456_789L);
        World second = world(123_456_789L);
        String systemId = first.activeSystemId();
        require(systemId.equals(second.activeSystemId()), "determinism worlds did not start in the same system");
        double x = first.width * 0.5;
        double y = first.height * 0.5;

        materializeRich(first, systemId, x, y);
        List<String> a = resourceFingerprint(first);
        materializeRich(second, systemId, x, y);
        List<String> b = resourceFingerprint(second);
        require(a.equals(b), "same seed/event id produced different event resource state");
    }

    private static void validateClosingWormholeRejectsTransit() {
        World world = world(992_201L);
        String systemId = world.activeSystemId();
        String target = otherSystem(world, systemId);
        Map<String,Object> row = event("EV-CLOSING", "unstable_wormhole", systemId,
                world.width * 0.5, world.height * 0.5, 1000,
                Map.of("targetSystemId", target, "targetX", "700", "targetY", "700", "closeAt", "500"));
        row.put("phase", GalaxyEventPhase.CLOSING.name());
        row.put("materialized", true);
        row.put("discoveredBy", List.of("SOLO"));
        row.put("ownedWormholes", List.of("EV-CLOSING:A", "EV-CLOSING:B"));
        GalaxyEventDirector.restore(world, runtime(systemId, List.of(row)));
        WormholeGate gate = new WormholeGate("EV-CLOSING:A", systemId, target, 600, 600, 700, 700);
        world.wormholes.add(gate);
        require(!gate.contains(600, 600), "closing unstable wormhole still accepted new transit");
    }

    private static void validateRuntimePersistenceShape() {
        World world = world(555_001L);
        String systemId = world.activeSystemId();
        Map<String,Object> saved = runtime(systemId,
                List.of(event("EV-SAVE", "ion_storm", systemId, 500, 500, 1200, Map.of())));
        GalaxyEventDirector.restore(world, saved);
        Map<String,Object> captured = GalaxyEventDirector.capture(world);
        require(ServerSaveStore.longValue(captured, "sequence", -1) == 12,
                "event director sequence did not survive runtime capture/restore");
        require(ServerSaveStore.list(captured.get("events")).size() == 1,
                "event runtime capture did not preserve active event state");
        Map<String,Object> scheduler = SystemSimulationScheduler.capture(world);
        require(scheduler.containsKey(GalaxyEventDirector.saveKey()),
                "event state was not embedded in authoritative runtime persistence");
    }

    private static void materializeRich(World world, String systemId, double x, double y) {
        PlayerRegistry.activate(world);
        Unit scout = new Unit("SOLO", 90, Rules.STARTING_SHIP, x, y);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId,
                List.of(event("EV-DETERMINISTIC", "rich_rare_earths", systemId, x, y, 1000, Map.of()))));
        GalaxyEventDirector.update(world, 0.1);
    }

    private static List<String> resourceFingerprint(World world) {
        List<String> out = new ArrayList<>();
        for (ResourceNode node : world.resources) {
            out.add(node.id + "|" + node.material + "|" + Double.toString(node.amount)
                    + "|" + Double.toString(node.x) + "|" + Double.toString(node.y));
        }
        return List.copyOf(out);
    }

    private static Map<String,Object> runtime(String systemId, List<Object> events) {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("sequence", 12L);
        root.put("clockBySystem", Map.of(systemId, 100.0));
        root.put("nextEvaluationBySystem", Map.of(systemId, 100_000.0));
        root.put("events", events);
        root.put("retiredGateIds", List.of());
        return root;
    }

    private static Map<String,Object> event(String id, String definitionId, String systemId,
                                            double x, double y, double expiresAt, Map<String,String> custom) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("definitionId", definitionId);
        row.put("systemId", systemId);
        row.put("x", x);
        row.put("y", y);
        row.put("phase", GalaxyEventPhase.HIDDEN.name());
        row.put("createdAt", 0.0);
        row.put("activatedAt", -1.0);
        row.put("expiresAt", expiresAt);
        row.put("materialized", false);
        row.put("discoveredBy", List.of());
        row.put("ownedResources", List.of());
        row.put("ownedItems", List.of());
        row.put("ownedUnits", List.of());
        row.put("ownedWormholes", List.of());
        row.put("custom", new LinkedHashMap<>(custom));
        return row;
    }

    private static World world(long seed) {
        PlayerRegistry.reset("SOLO", "Event Validator", 0x50BEFF);
        World world = new World("Event Validator", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.useSystemSeed(seed);
        world.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
        return world;
    }

    private static String otherSystem(World world, String source) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !source.equals(system.id())) return system.id();
        }
        throw new IllegalStateException("Galaxy event validation requires at least two systems.");
    }

    private static boolean hasOwner(World world, String playerId) {
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) return true;
        return false;
    }

    private static boolean hasGate(World world, String gateId) {
        for (WormholeGate gate : world.wormholes) if (gateId.equals(gate.id)) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
