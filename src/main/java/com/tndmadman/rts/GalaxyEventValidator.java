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
        validateConfiguredSpawnSafety();
        validateRichResourceMaterialization();
        validateSalvageMaterialization();
        validateNpcEncounterMaterialization();
        validateEnvironmentalModifierMaterialization();
        validateWormholeMaterialization();
        validateEventProjectionSerialization();
        validateHiddenEventsDoNotLeak();
        validateDeterministicMaterialization();
        validateClosingWormholeRejectsTransit();
        validateRuntimePersistenceShape();
    }

    private static void validateConfiguredSpawnSafety() {
        World world = world(991_330L);
        String systemId = world.activeSystemId();
        double x = world.width * 0.5;
        double y = world.height * 0.5;

        Unit fleet = new Unit("SOLO", 700, Rules.STARTING_SHIP, x, y);
        world.units.put(fleet.key(), fleet);
        require(!GalaxyEventDirector.spawnLocationSafeForValidation(world, "pirate_ambush", x, y),
                "hostile event spawn safety allowed a pirate ambush directly on a player fleet");
        world.units.remove(fleet.key());

        Base production = new Base("SOLO:B700", "SOLO", Rules.DEFAULT_BASE, x, y);
        world.bases.put(production.id, production);
        require(!GalaxyEventDirector.spawnLocationSafeForValidation(world, "ion_storm", x, y),
                "environmental event spawn safety allowed a hazard directly on a player base");
        world.bases.remove(production.id);

        String target = otherSystem(world, systemId);
        WormholeGate gate = new WormholeGate("VALIDATOR-GATE", systemId, target, x, y, x + 500, y + 500);
        world.wormholes.add(gate);
        require(!GalaxyEventDirector.spawnLocationSafeForValidation(world, "unstable_wormhole", x, y),
                "temporary wormhole spawn safety allowed a gate directly on existing topology");
    }

    private static void validateRichResourceMaterialization() {
        World world = world(991_337L);
        String systemId = world.activeSystemId();
        discoverSingle(world, "EV-RICH", "rich_rare_earths", Map.of());

        Map<String,Object> saved = capturedEvent(world, "EV-RICH");
        int owned = ServerSaveStore.list(saved.get("ownedResources")).size();
        require(owned == 4, "rich-resource event tracked " + owned + " resource nodes; expected 4");
        require(countOwnedResources(world, saved) == owned,
                "rich-resource event did not leave all tracked resource nodes in the active system");
        require(GalaxyEventDirector.viewsFor(world, "SOLO").size() == 1,
                "rich-resource event was not visible after discovery in " + systemId);
    }

    private static void validateSalvageMaterialization() {
        World world = world(991_338L);
        discoverSingle(world, "EV-SALVAGE", "derelict_convoy", Map.of());

        Map<String,Object> saved = capturedEvent(world, "EV-SALVAGE");
        int owned = ServerSaveStore.list(saved.get("ownedItems")).size();
        int present = countOwnedItems(world, saved);
        require(owned == 5, "derelict event tracked " + owned + " salvage items; expected 5; world has "
                + world.items.size() + " item(s)");
        require(present == owned, "derelict event tracked " + owned + " salvage items but only " + present
                + " remain in world.items");
    }

    private static void validateNpcEncounterMaterialization() {
        World pirateWorld = world(991_339L);
        discoverSingle(pirateWorld, "EV-PIRATE", "pirate_ambush", Map.of());
        Map<String,Object> pirate = capturedEvent(pirateWorld, "EV-PIRATE");
        int pirateOwned = ServerSaveStore.list(pirate.get("ownedUnits")).size();
        require(pirateOwned == 4, "pirate event tracked " + pirateOwned + " NPC units; expected 4");
        require(countOwner(pirateWorld, "NPC_RAIDERS") >= 4,
                "pirate encounter did not materialize its raider force");

        World distressWorld = world(991_340L);
        discoverSingle(distressWorld, "EV-DISTRESS", "distress_beacon", Map.of());
        Map<String,Object> distress = capturedEvent(distressWorld, "EV-DISTRESS");
        int distressOwned = ServerSaveStore.list(distress.get("ownedUnits")).size();
        require(distressOwned == 4, "distress event tracked " + distressOwned + " NPC units; expected civilian + 3 attackers");
        require(countOwner(distressWorld, "NPC_MINERS") >= 1,
                "distress encounter did not materialize its civilian NPC");
        require(countOwner(distressWorld, "NPC_RAIDERS") >= 3,
                "distress encounter did not materialize its attackers");
    }

    private static void validateEnvironmentalModifierMaterialization() {
        World world = world(991_341L);
        String systemId = world.activeSystemId();
        double baseSensors = StarSystems.get(systemId).modifiers().sensorRange();
        discoverSingle(world, "EV-ION", "ion_storm", Map.of());
        require(SystemModifierRules.sensorRange(world) < baseSensors,
                "temporary environmental event modifier was not composed with system rules");
    }

    private static void validateWormholeMaterialization() {
        World world = world(991_342L);
        String systemId = world.activeSystemId();
        String target = otherSystem(world, systemId);
        discoverSingle(world, "EV-WORM", "unstable_wormhole",
                Map.of("targetSystemId", target, "targetX", "900", "targetY", "750"));
        require(hasGate(world, "EV-WORM:A"), "unstable wormhole did not materialize its source gate");
        require(containsLink(GalaxyEventDirector.temporaryLinksFor(world, "SOLO"), systemId, target),
                "discovered unstable wormhole did not project a temporary galaxy link");

        GalaxyMapSnapshot withoutPair = withoutLink(world.authoritativeGalaxyMapSnapshot(), systemId, target);
        GalaxyMapWire.Decoded ownerView = GalaxyMapWire.decode(GalaxyMapWire.encode(1, withoutPair, "SOLO", Map.of()));
        require(containsLink(ownerView.snapshot().links(), systemId, target),
                "owner galaxy wire omitted a discovered active unstable-wormhole shortcut");

        GalaxyMapWire.Decoded publicView = GalaxyMapWire.decode(GalaxyMapWire.encode(1, withoutPair));
        require(!containsLink(publicView.snapshot().links(), systemId, target),
                "unscoped galaxy wire leaked an owner-discovered unstable-wormhole shortcut");
    }

    private static void validateEventProjectionSerialization() {
        World world = world(991_343L);
        String systemId = world.activeSystemId();
        String target = otherSystem(world, systemId);
        double x = world.width * 0.5;
        double y = world.height * 0.5;

        List<Object> events = new ArrayList<>();
        events.add(discoveredProjectionEvent("EV-RICH-P", "rich_rare_earths", systemId, x, y, Map.of()));
        events.add(discoveredProjectionEvent("EV-SALVAGE-P", "derelict_convoy", systemId, x, y, Map.of()));
        events.add(discoveredProjectionEvent("EV-DISTRESS-P", "distress_beacon", systemId, x, y, Map.of()));
        events.add(discoveredProjectionEvent("EV-PIRATE-P", "pirate_ambush", systemId, x, y, Map.of()));
        events.add(discoveredProjectionEvent("EV-ION-P", "ion_storm", systemId, x, y, Map.of()));
        events.add(discoveredProjectionEvent("EV-WORM-P", "unstable_wormhole", systemId, x, y,
                Map.of("targetSystemId", target, "targetX", "900", "targetY", "750")));
        GalaxyEventDirector.restore(world, runtime(systemId, events));

        List<GalaxyEventView> views = GalaxyEventDirector.viewsFor(world, "SOLO");
        require(views.size() == 6, "discovered event projection did not include all six event categories");
        List<String> rows = GalaxyEventWire.encodeRows(world, "SOLO");
        require(rows.size() == 6, "galaxy event wire did not serialize all discovered event categories");
        for (String row : rows) require(GalaxyEventWire.decodeRow(row) != null,
                "galaxy event wire row did not decode");
    }

    private static void validateHiddenEventsDoNotLeak() {
        World world = world(44_221L);
        String systemId = world.activeSystemId();
        double x = world.width * 0.8;
        double y = world.height * 0.8;
        int resourcesBefore = world.resources.size();
        int itemsBefore = world.items.size();
        Unit scout = new Unit("SOLO", 1, Rules.STARTING_SHIP, world.width * 0.1, world.height * 0.1);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId,
                List.of(event("EV-HIDDEN", "rich_rare_earths", systemId, x, y, 1000, Map.of()))));
        GalaxyEventDirector.update(world, 0.1);
        require(GalaxyEventDirector.viewsFor(world, "SOLO").isEmpty(), "hidden event leaked into player projection");
        require(GalaxyEventWire.encodeRows(world, "SOLO").isEmpty(), "hidden event leaked into galaxy wire rows");
        require(world.resources.size() == resourcesBefore, "hidden resource event materialized before discovery");
        require(world.items.size() == itemsBefore, "hidden event changed world items before discovery");
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
        require(!containsLink(GalaxyEventDirector.temporaryLinksFor(world, "SOLO"), systemId, target),
                "closing unstable wormhole was still advertised as active temporary topology");
    }

    private static void validateRuntimePersistenceShape() {
        World world = world(555_001L);
        String systemId = world.activeSystemId();
        Map<String,Object> saved = runtime(systemId,
                List.of(event("EV-SAVE", "ion_storm", systemId, 500, 500, 1200, Map.of())));
        String cooldownKey = systemId + '\u0000' + "ion_storm";
        saved.put("cooldownUntilByDefinition", Map.of(cooldownKey, 333.0));
        GalaxyEventDirector.restore(world, saved);
        Map<String,Object> captured = GalaxyEventDirector.capture(world);
        require(ServerSaveStore.longValue(captured, "sequence", -1) == 12,
                "event director sequence did not survive runtime capture/restore");
        require(ServerSaveStore.list(captured.get("events")).size() == 1,
                "event runtime capture did not preserve active event state");
        double cooldown = ServerSaveStore.asDouble(
                ServerSaveStore.object(captured.get("cooldownUntilByDefinition")).get(cooldownKey), -1);
        require(Math.abs(cooldown - 333.0) < 0.000001,
                "event per-definition cooldown did not survive runtime capture/restore");
        Map<String,Object> scheduler = SystemSimulationScheduler.capture(world);
        require(scheduler.containsKey(GalaxyEventDirector.saveKey()),
                "event state was not embedded in authoritative runtime persistence");
    }

    private static void discoverSingle(World world, String id, String definitionId, Map<String,String> custom) {
        PlayerRegistry.activate(world);
        String systemId = world.activeSystemId();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Unit scout = new Unit("SOLO", 77, Rules.STARTING_SHIP, x, y);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId,
                List.of(event(id, definitionId, systemId, x, y, 1000, custom))));
        GalaxyEventDirector.update(world, 0.25);
    }

    private static void materializeRich(World world, String systemId, double x, double y) {
        PlayerRegistry.activate(world);
        Unit scout = new Unit("SOLO", 90, Rules.STARTING_SHIP, x, y);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId,
                List.of(event("EV-DETERMINISTIC", "rich_rare_earths", systemId, x, y, 1000, Map.of()))));
        GalaxyEventDirector.update(world, 0.1);
    }

    private static Map<String,Object> discoveredProjectionEvent(String id, String definitionId, String systemId,
                                                                double x, double y, Map<String,String> custom) {
        Map<String,Object> row = event(id, definitionId, systemId, x, y, 1000, custom);
        row.put("phase", GalaxyEventPhase.ACTIVE.name());
        row.put("activatedAt", 100.0);
        row.put("discoveredBy", List.of("SOLO"));
        return row;
    }

    private static Map<String,Object> capturedEvent(World world, String eventId) {
        Map<String,Object> captured = GalaxyEventDirector.capture(world);
        for (Object item : ServerSaveStore.list(captured.get("events"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            if (eventId.equals(ServerSaveStore.string(row, "id", ""))) return row;
        }
        return Map.of();
    }

    private static int countOwnedResources(World world, Map<String,Object> event) {
        Set<Integer> ids = new java.util.LinkedHashSet<>();
        for (Object value : ServerSaveStore.list(event.get("ownedResources"))) {
            if (value instanceof Number number) ids.add(number.intValue());
        }
        int count = 0;
        for (ResourceNode node : world.resources) if (ids.contains(node.id)) count++;
        return count;
    }

    private static int countOwnedItems(World world, Map<String,Object> event) {
        Set<Integer> ids = new java.util.LinkedHashSet<>();
        for (Object value : ServerSaveStore.list(event.get("ownedItems"))) {
            if (value instanceof Number number) ids.add(number.intValue());
        }
        int count = 0;
        for (WorldItem item : world.items) if (ids.contains(item.id) && !item.empty()) count++;
        return count;
    }

    private static int countOwner(World world, String playerId) {
        int count = 0;
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId) && unit.hp > 0) count++;
        return count;
    }

    private static GalaxyMapSnapshot withoutLink(GalaxyMapSnapshot snapshot, String from, String to) {
        List<GalaxyMapLink> links = new ArrayList<>();
        if (snapshot != null && snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                if (link == null || sameLink(link, from, to)) continue;
                links.add(link);
            }
        }
        return snapshot == null
                ? new GalaxyMapSnapshot("", List.of(), List.of())
                : new GalaxyMapSnapshot(snapshot.activeSystemId(), snapshot.systems(), List.copyOf(links));
    }

    private static boolean sameLink(GalaxyMapLink link, String from, String to) {
        return (from.equals(link.fromSystemId()) && to.equals(link.toSystemId()))
                || (to.equals(link.fromSystemId()) && from.equals(link.toSystemId()));
    }

    private static boolean containsLink(List<GalaxyMapLink> links, String from, String to) {
        for (GalaxyMapLink link : links) {
            if (link != null && sameLink(link, from, to)) return true;
        }
        return false;
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
        root.put("cooldownUntilByDefinition", Map.of());
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
        World world = new World("Event Validator", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Event Validator", 0x50BEFF);
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

    private static boolean hasGate(World world, String gateId) {
        for (WormholeGate gate : world.wormholes) if (gateId.equals(gate.id)) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
