package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GalaxyEventMultiplayerValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private GalaxyEventMultiplayerValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem galaxy event multiplayer validation passed.");
    }

    static void validateOrThrow() {
        validateSimultaneousDiscoveryMaterializesOnce();
        validateAllianceSharingAndEnemyIsolation();
        validateRewardAndNoticeProjection();
        validateHiddenTemporaryTopologyDoesNotLeak();
        validateObserverVisibilityPolicies();
    }

    private static void validateSimultaneousDiscoveryMaterializesOnce() {
        World world = world(1_297_001L);
        registerPlayers(world);
        String systemId = world.activeSystemId();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Unit first = new Unit("SOLO", 1001, Rules.STARTING_SHIP, x, y);
        Unit second = new Unit("P2", 1002, Rules.STARTING_SHIP, x, y);
        world.units.put(first.key(), first);
        world.units.put(second.key(), second);
        GalaxyEventDirector.restore(world, runtime(systemId, List.of(event("EV-MULTI", "rich_rare_earths",
                systemId, x, y, 1000))));

        GalaxyEventDirector.update(world, 0.25);
        Map<String,Object> saved = capturedEvent(world, "EV-MULTI");
        require("ACTIVE".equals(ServerSaveStore.string(saved, "phase", "")),
                "simultaneously discovered event did not activate");
        require(ServerSaveStore.list(saved.get("discoveredBy")).contains("SOLO")
                        && ServerSaveStore.list(saved.get("discoveredBy")).contains("P2"),
                "simultaneous discovery did not record both discovering players");
        int owned = ServerSaveStore.list(saved.get("ownedResources")).size();
        require(owned == 4, "simultaneous discovery materialized the rich event more than once: " + owned);
        require(GalaxyEventDirector.viewsFor(world, "SOLO").size() == 1,
                "first discovering player did not receive exactly one event projection");
        require(GalaxyEventDirector.viewsFor(world, "P2").size() == 1,
                "second discovering player did not receive exactly one event projection");
        require(GalaxyEventDirector.viewsFor(world, "P3").isEmpty(),
                "non-discovering enemy received a simultaneous event projection");
        require(GalaxyEventWire.encodeRows(world, "P3").isEmpty(),
                "non-discovering enemy received event wire rows");
    }

    private static void validateAllianceSharingAndEnemyIsolation() {
        World world = world(1_297_002L);
        registerPlayers(world);
        IntelWarfareSystem.setIntelAlliance(world, "SOLO", "P2", true);
        String systemId = world.activeSystemId();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Unit scout = new Unit("SOLO", 1011, Rules.STARTING_SHIP, x, y);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId, List.of(event("EV-ALLY", "rich_rare_earths",
                systemId, x, y, 1000))));

        GalaxyEventDirector.update(world, 0.25);
        Map<String,Object> saved = capturedEvent(world, "EV-ALLY");
        require(ServerSaveStore.list(saved.get("discoveredBy")).contains("P2"),
                "allied player did not receive shared event discovery");
        require(GalaxyEventDirector.viewsFor(world, "P2").size() == 1,
                "allied player did not receive the shared event view");
        require(GalaxyEventDirector.viewsFor(world, "P3").isEmpty(),
                "non-allied player learned an allied-only event");
        require(GalaxyEventWire.encodeRows(world, "P3").isEmpty(),
                "non-allied player leaked an allied-only event through galaxy wire");
        require(!GameNoticeCenter.drain(world, "P2").isEmpty(),
                "allied discovered player did not receive an event notice");
        require(GameNoticeCenter.drain(world, "P3").isEmpty(),
                "non-allied player received a hidden event notice");
    }

    private static void validateRewardAndNoticeProjection() {
        World world = world(1_297_002_1L);
        registerPlayers(world);
        String systemId = world.activeSystemId();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Unit permitted = new Unit("P2", 1021, Rules.STARTING_SHIP, x, y);
        world.units.put(permitted.key(), permitted);
        Map<String,Object> active = event("EV-REWARD-WIRE", "pirate_ambush", systemId, x, y, 1000);
        active.put("phase", GalaxyEventPhase.ACTIVE.name());
        active.put("materialized", true);
        active.put("activatedAt", 100.0);
        active.put("discoveredBy", List.of("P2"));
        GalaxyEventDirector.restore(world, runtime(systemId, List.of(active)));
        GalaxyEventDirector.update(world, 0.01);
        Map<String,Object> materialized = capturedEvent(world, "EV-REWARD-WIRE");
        for (Object raw : ServerSaveStore.list(materialized.get("ownedUnits"))) world.units.remove(String.valueOf(raw));
        GalaxyEventDirector.update(world, 0.1);
        Map<String,Object> completed = capturedEvent(world, "EV-REWARD-WIRE");
        int rewardId = ServerSaveStore.intValue(completed, "rewardItemId", -1);
        require(rewardId >= 0, "reward projection fixture did not create a physical reward");

        Snapshot source = WorldNetAccess.snapshot(world, 77);
        Snapshot permittedView = FogSnapshotFilter.forPlayer(world, "P2", source);
        Snapshot enemyView = FogSnapshotFilter.forPlayer(world, "P3", source);
        require(containsItem(permittedView, rewardId),
                "permitted discovered client did not receive the event reward item");
        require(!containsItem(enemyView, rewardId),
                "non-permitted client received a hidden event reward item");
    }

    private static void validateHiddenTemporaryTopologyDoesNotLeak() {
        World world = world(1_297_003L);
        registerPlayers(world);
        String source = world.activeSystemId();
        String target = otherSystem(world, source);
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Map<String,Object> hidden = event("EV-HIDDEN-WH", "unstable_wormhole", source, x, y, 1000);
        hidden.put("custom", Map.of("targetSystemId", target, "targetX", "700", "targetY", "700"));
        GalaxyEventDirector.restore(world, runtime(source, List.of(hidden)));
        require(GalaxyEventDirector.temporaryLinksFor(world, "SOLO").isEmpty(),
                "hidden wormhole leaked temporary topology to the local player");
        require(GalaxyEventDirector.temporaryLinksFor(world, "P3").isEmpty(),
                "hidden wormhole leaked temporary topology to an enemy");

        Map<String,Object> discovered = event("EV-DISC-WH", "unstable_wormhole", source, x, y, 1000);
        discovered.put("phase", GalaxyEventPhase.ACTIVE.name());
        discovered.put("materialized", true);
        discovered.put("discoveredBy", List.of("SOLO"));
        discovered.put("ownedWormholes", List.of("EV-DISC-WH:A", "EV-DISC-WH:B"));
        discovered.put("custom", Map.of("targetSystemId", target, "targetX", "700", "targetY", "700"));
        GalaxyEventDirector.restore(world, runtime(source, List.of(discovered)));
        require(containsLink(GalaxyEventDirector.temporaryLinksFor(world, "SOLO"), source, target),
                "discovering player did not receive its temporary wormhole topology");
        require(GalaxyEventDirector.temporaryLinksFor(world, "P3").isEmpty(),
                "undiscovered enemy received temporary wormhole topology");
        require(GalaxyEventWire.encodeRows(world, "P3").isEmpty(),
                "undiscovered enemy received discovered-player wormhole metadata");
    }

    private static void validateObserverVisibilityPolicies() {
        World world = world(1_297_004L);
        registerPlayers(world);
        ObserverSessions.configure(null, world);
        String source = world.activeSystemId();
        String target = otherSystem(world, source);
        double x = world.width * 0.5;
        double y = world.height * 0.5;

        Map<String,Object> rich = event("EV-OBS-RICH", "rich_rare_earths", source, x, y, 1000);
        rich.put("phase", GalaxyEventPhase.ACTIVE.name());
        rich.put("activatedAt", 100.0);
        rich.put("discoveredBy", List.of("P2"));

        Map<String,Object> wormhole = event("EV-OBS-WORM", "unstable_wormhole", source, x + 50, y + 50, 1000);
        wormhole.put("phase", GalaxyEventPhase.ACTIVE.name());
        wormhole.put("activatedAt", 100.0);
        wormhole.put("materialized", true);
        wormhole.put("discoveredBy", List.of("P2"));
        wormhole.put("ownedWormholes", List.of("EV-OBS-WORM:A", "EV-OBS-WORM:B"));
        wormhole.put("custom", Map.of("targetSystemId", target, "targetX", "700", "targetY", "700"));

        Map<String,Object> hidden = event("EV-OBS-HIDDEN", "rich_rare_earths", source, x + 100, y + 100, 1000);
        GalaxyEventDirector.restore(world, runtime(source, List.of(rich, wormhole, hidden)));

        setObserverGrant(world, new ObserverSessions.Grant("P9", "Observer", ObserverSessions.VisibilityMode.FULL, ""));
        require(hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-RICH")
                        && hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-WORM")
                        && !hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-HIDDEN"),
                "FULL observer event projection omitted revealed events or leaked a hidden event");
        require(containsLink(GalaxyEventDirector.temporaryLinksFor(world, "P9"), source, target),
                "FULL observer did not receive revealed temporary event topology");

        setObserverGrant(world, new ObserverSessions.Grant("P9", "Observer",
                ObserverSessions.VisibilityMode.PLAYER_FOLLOW, "P3"));
        require(GalaxyEventDirector.viewsFor(world, "P9").isEmpty()
                        && GalaxyEventDirector.temporaryLinksFor(world, "P9").isEmpty(),
                "PLAYER_FOLLOW observer inherited event intel not known by the followed player");

        setObserverGrant(world, new ObserverSessions.Grant("P9", "Observer",
                ObserverSessions.VisibilityMode.PLAYER_FOLLOW, "P2"));
        require(hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-RICH")
                        && hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-WORM"),
                "PLAYER_FOLLOW observer did not inherit the followed player's event intel");
        require(containsLink(GalaxyEventDirector.temporaryLinksFor(world, "P9"), source, target),
                "PLAYER_FOLLOW observer did not inherit the followed player's temporary topology");

        setObserverGrant(world, new ObserverSessions.Grant("P9", "Observer", ObserverSessions.VisibilityMode.PUBLIC, ""));
        require(hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-RICH")
                        && hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-WORM")
                        && !hasEvent(GalaxyEventDirector.viewsFor(world, "P9"), "EV-OBS-HIDDEN"),
                "PUBLIC observer did not use the deterministic public anchor's event visibility");
        require(containsLink(GalaxyEventDirector.temporaryLinksFor(world, "P9"), source, target),
                "PUBLIC observer did not use the public anchor's temporary event topology");
    }

    @SuppressWarnings("unchecked")
    private static void setObserverGrant(World world, ObserverSessions.Grant grant) {
        try {
            Field statesField = ObserverSessions.class.getDeclaredField("STATES");
            statesField.setAccessible(true);
            Map<World,Object> states = (Map<World,Object>) statesField.get(null);
            Object state = states.get(world);
            require(state != null, "observer state was not configured for event projection validation");
            Field grantsField = state.getClass().getDeclaredField("grants");
            grantsField.setAccessible(true);
            Map<String,ObserverSessions.Grant> grants = (Map<String,ObserverSessions.Grant>) grantsField.get(state);
            grants.put(grant.playerId(), grant);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("could not configure observer event projection fixture", ex);
        }
    }

    private static boolean hasEvent(List<GalaxyEventView> views, String eventId) {
        for (GalaxyEventView view : views) if (view != null && eventId.equals(view.eventId())) return true;
        return false;
    }

    private static void registerPlayers(World world) {
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Local", 0x50BEFF);
        PlayerRegistry.register("P2", "Ally", 0x44DD66, false);
        PlayerRegistry.register("P3", "Enemy", 0xDD4455, false);
    }

    private static Map<String,Object> runtime(String systemId, List<Object> events) {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("sequence", 1L);
        root.put("clockBySystem", Map.of(systemId, 100.0));
        root.put("nextEvaluationBySystem", Map.of(systemId, 100_000.0));
        root.put("cooldownUntilByDefinition", Map.of());
        root.put("events", events);
        root.put("retiredGateIds", List.of());
        return root;
    }

    private static Map<String,Object> event(String id, String definitionId, String systemId,
                                            double x, double y, double expiresAt) {
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
        row.put("custom", Map.of());
        return row;
    }

    private static Map<String,Object> capturedEvent(World world, String eventId) {
        for (Object raw : ServerSaveStore.list(GalaxyEventDirector.capture(world).get("events"))) {
            Map<String,Object> row = ServerSaveStore.object(raw);
            if (eventId.equals(ServerSaveStore.string(row, "id", ""))) return row;
        }
        return Map.of();
    }

    private static World world(long seed) {
        World world = new World("Event Multiplayer Validator", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Event Multiplayer Validator", 0x50BEFF);
        world.useSystemSeed(seed);
        world.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
        return world;
    }

    private static String otherSystem(World world, String source) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !source.equals(system.id())) return system.id();
        }
        throw new IllegalStateException("multiplayer event validation requires two systems");
    }

    private static boolean containsLink(List<GalaxyMapLink> links, String from, String to) {
        for (GalaxyMapLink link : links) {
            if (link == null) continue;
            if ((from.equals(link.fromSystemId()) && to.equals(link.toSystemId()))
                    || (to.equals(link.fromSystemId()) && from.equals(link.toSystemId()))) return true;
        }
        return false;
    }

    private static boolean containsItem(Snapshot snapshot, int itemId) {
        if (snapshot == null) return false;
        for (ItemState item : snapshot.items()) if (item != null && item.id() == itemId) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
