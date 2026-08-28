package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-authoritative, deterministic dynamic-galaxy event orchestration.
 *
 * Hidden events carry metadata only. Gameplay entities are materialized only
 * after authoritative discovery, preventing undiscovered resources, loot,
 * fleets, modifiers, and temporary topology from leaking through ordinary
 * snapshots. Event-owned entities are tracked by exact ids so cleanup never
 * deletes unrelated nearby gameplay state.
 */
final class GalaxyEventDirector {
    private static final String SAVE_KEY = "__galaxyEvents";
    private static final Map<World, RuntimeState> STATES = new WeakHashMap<>();
    private static final Map<World, List<GalaxyEventView>> REMOTE_VIEWS = new WeakHashMap<>();
    private static GalaxyEventCatalog catalog;

    private GalaxyEventDirector() { }

    static String saveKey() { return SAVE_KEY; }

    static synchronized void update(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt <= 0 || !authoritative(world)) return;
        GalaxyEventCatalog rules = catalog();
        if (!rules.enabled()) return;

        RuntimeState state = STATES.computeIfAbsent(world, ignored -> new RuntimeState());
        String systemId = clean(world.activeSystemId());
        if (systemId.isBlank()) return;

        double clock = state.clockBySystem.getOrDefault(systemId, 0.0) + dt;
        state.clockBySystem.put(systemId, clock);

        retireStaleGates(world, state);
        ensureMaterializedState(world, state, systemId);
        discover(world, state, systemId, clock);
        advance(world, state, systemId, clock, rules);
        evaluateSpawn(world, state, systemId, clock, rules);
    }

    static synchronized SystemModifiers temporaryModifiers(World world, String systemId) {
        RuntimeState state = STATES.get(world);
        if (state == null || systemId == null || systemId.isBlank()) return SystemModifiers.STANDARD;
        double mining = 1, respawn = 1, sensors = 1, shields = 1, movement = 1, weapons = 1, damage = 0;
        for (GalaxyEventInstance event : state.events.values()) {
            if (!systemId.equals(event.systemId) || event.phase != GalaxyEventPhase.ACTIVE) continue;
            GalaxyEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null || definition.kind() != GalaxyEventKind.ENVIRONMENTAL) continue;
            SystemModifiers modifier = definition.modifiers();
            mining *= modifier.miningYield();
            respawn *= modifier.resourceRespawn();
            sensors *= modifier.sensorRange();
            shields *= modifier.shieldRegen();
            movement *= modifier.movementSpeed();
            weapons *= modifier.weaponRange();
            damage += modifier.environmentalDamagePerSecond();
        }
        return new SystemModifiers(mining, respawn, sensors, shields, movement, weapons, damage);
    }

    static synchronized boolean wormholeAcceptsTransit(World world, String gateId) {
        if (world == null || gateId == null || gateId.isBlank()) return true;
        RuntimeState state = STATES.get(world);
        if (state == null) return true;
        if (state.retiredGateIds.contains(gateId)) return false;
        for (GalaxyEventInstance event : state.events.values()) {
            if (!event.ownedWormholes.contains(gateId)) continue;
            return event.phase == GalaxyEventPhase.ACTIVE;
        }
        return true;
    }

    static synchronized List<GalaxyMapLink> temporaryLinksFor(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        if (!authoritative(world)) {
            List<GalaxyMapLink> out = new ArrayList<>();
            for (GalaxyEventView view : remoteViews(world)) {
                if (view.kind() != GalaxyEventKind.UNSTABLE_WORMHOLE || view.phase() != GalaxyEventPhase.ACTIVE) continue;
            }
            return List.copyOf(out);
        }
        RuntimeState state = STATES.get(world);
        if (state == null) return List.of();
        List<GalaxyMapLink> out = new ArrayList<>();
        for (GalaxyEventInstance event : state.events.values()) {
            if (event.phase != GalaxyEventPhase.ACTIVE || !event.discoveredBy.contains(playerId)) continue;
            GalaxyEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null || definition.kind() != GalaxyEventKind.UNSTABLE_WORMHOLE) continue;
            String target = clean(event.custom.get("targetSystemId"));
            if (target.isBlank() || target.equals(event.systemId)) continue;
            out.add(new GalaxyMapLink(event.systemId, target));
        }
        return List.copyOf(out);
    }

    static synchronized List<GalaxyEventView> viewsFor(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        if (!authoritative(world)) return remoteViews(world);
        RuntimeState state = STATES.get(world);
        if (state == null) return List.of();
        List<GalaxyEventView> out = new ArrayList<>();
        for (GalaxyEventInstance event : state.events.values()) {
            if (!event.discoveredBy.contains(playerId)) continue;
            if (terminal(event.phase)) continue;
            GalaxyEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) continue;
            double remaining = Math.max(0, event.expiresAt - state.clockBySystem.getOrDefault(event.systemId, 0.0));
            out.add(new GalaxyEventView(event.id, event.definitionId, definition.kind(), event.systemId,
                    definition.name(), event.phase, event.x, event.y, remaining));
        }
        out.sort(Comparator.comparing(GalaxyEventView::systemId).thenComparing(GalaxyEventView::eventId));
        return List.copyOf(out);
    }

    static synchronized List<GalaxyEventView> visibleViews(World world) {
        if (world == null) return List.of();
        return viewsFor(world, PlayerRegistry.localId());
    }

    static synchronized void replaceRemoteViews(World world, List<GalaxyEventView> views) {
        if (world == null) return;
        List<GalaxyEventView> safe = views == null ? List.of() : List.copyOf(views);
        if (safe.isEmpty()) REMOTE_VIEWS.remove(world);
        else REMOTE_VIEWS.put(world, safe);
    }

    static synchronized List<GalaxyEventView> remoteViews(World world) {
        List<GalaxyEventView> views = REMOTE_VIEWS.get(world);
        return views == null ? List.of() : views;
    }

    static synchronized Map<String,Object> capture(World world) {
        RuntimeState state = STATES.get(world);
        if (state == null) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("sequence", state.sequence);
        out.put("clockBySystem", new LinkedHashMap<>(state.clockBySystem));
        out.put("nextEvaluationBySystem", new LinkedHashMap<>(state.nextEvaluationBySystem));
        out.put("cooldownUntilByDefinition", new LinkedHashMap<>(state.cooldownUntilByDefinition));
        out.put("retiredGateIds", new ArrayList<>(state.retiredGateIds));
        List<Object> events = new ArrayList<>();
        for (GalaxyEventInstance event : state.events.values()) events.add(event.capture());
        out.put("events", events);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> root = ServerSaveStore.object(saved);
        if (root.isEmpty()) {
            STATES.remove(world);
            return;
        }
        RuntimeState state = new RuntimeState();
        state.sequence = Math.max(0, ServerSaveStore.longValue(root, "sequence", 0));
        restoreDoubleMap(root.get("clockBySystem"), state.clockBySystem);
        restoreDoubleMap(root.get("nextEvaluationBySystem"), state.nextEvaluationBySystem);
        restoreDoubleMap(root.get("cooldownUntilByDefinition"), state.cooldownUntilByDefinition);
        for (Object item : ServerSaveStore.list(root.get("retiredGateIds"))) {
            String id = clean(ServerSaveStore.asString(item, ""));
            if (!id.isBlank()) state.retiredGateIds.add(id);
        }
        for (Object item : ServerSaveStore.list(root.get("events"))) {
            GalaxyEventInstance event = GalaxyEventInstance.restore(item);
            if (event == null || catalog().byId(event.definitionId) == null) continue;
            state.events.put(event.id, event);
        }
        STATES.put(world, state);
    }

    static synchronized void removeSystems(World world, Iterable<String> systemIds) {
        RuntimeState state = STATES.get(world);
        if (state == null || systemIds == null) return;
        Set<String> removed = new LinkedHashSet<>();
        for (String id : systemIds) {
            String value = clean(id);
            if (!value.isBlank()) removed.add(value);
        }
        if (removed.isEmpty()) return;
        for (String id : removed) {
            state.clockBySystem.remove(id);
            state.nextEvaluationBySystem.remove(id);
            String prefix = id + '\u0000';
            state.cooldownUntilByDefinition.keySet().removeIf(key -> key.startsWith(prefix));
        }
        state.events.values().removeIf(event -> {
            String target = clean(event.custom.get("targetSystemId"));
            if (!removed.contains(event.systemId) && !removed.contains(target)) return false;
            state.retiredGateIds.addAll(event.ownedWormholes);
            return true;
        });
    }

    static synchronized void clear(World world) {
        if (world == null) return;
        STATES.remove(world);
        REMOTE_VIEWS.remove(world);
    }

    static synchronized void reloadCatalogForValidation() { catalog = null; }

    static synchronized boolean spawnLocationSafeForValidation(World world, String definitionId, double x, double y) {
        GalaxyEventDefinition definition = catalog().byId(definitionId);
        return definition != null && safeSpawnLocation(world, definition, x, y);
    }

    private static void evaluateSpawn(World world, RuntimeState state, String systemId, double clock,
                                      GalaxyEventCatalog rules) {
        double next = state.nextEvaluationBySystem.getOrDefault(systemId, rules.initialDelaySeconds());
        if (clock + 0.000001 < next) return;
        state.nextEvaluationBySystem.put(systemId, clock + rules.evaluationSeconds());

        long sequence = ++state.sequence;
        Random random = new Random(mixSeed(world.systemSeed(), sequence, systemId));
        if (random.nextDouble() > rules.spawnChance()) return;
        if (activeCount(state) >= rules.maxActiveGalaxy() || activeCount(state, systemId) >= rules.maxActivePerSystem()) return;

        boolean home = isHomeSystem(world, systemId);
        List<GalaxyEventDefinition> candidates = new ArrayList<>();
        double totalWeight = 0;
        for (GalaxyEventDefinition definition : rules.definitions()) {
            if (!definition.enabled() || definition.weight() <= 0) continue;
            if (home && !definition.safeForHome()) continue;
            if (!definitionEligible(state, systemId, clock, definition)) continue;
            if (definition.kind() == GalaxyEventKind.UNSTABLE_WORMHOLE && !hasWormholeTarget(world, systemId)) continue;
            candidates.add(definition);
            totalWeight += definition.weight();
        }
        if (candidates.isEmpty() || totalWeight <= 0) return;

        double pick = random.nextDouble() * totalWeight;
        GalaxyEventDefinition selected = candidates.get(candidates.size() - 1);
        for (GalaxyEventDefinition definition : candidates) {
            pick -= definition.weight();
            if (pick <= 0) {
                selected = definition;
                break;
            }
        }

        SpawnPoint point = safeSpawnPoint(world, selected, random);
        if (point == null) return;
        double duration = selected.minDurationSeconds()
                + random.nextDouble() * Math.max(0, selected.maxDurationSeconds() - selected.minDurationSeconds());
        String id = eventId(world.systemSeed(), sequence, selected.id(), systemId);
        GalaxyEventInstance event = new GalaxyEventInstance(id, selected.id(), systemId, point.x(), point.y(),
                GalaxyEventPhase.HIDDEN, clock, clock + duration);
        if (selected.kind() == GalaxyEventKind.UNSTABLE_WORMHOLE) {
            String target = wormholeTarget(world, systemId, random);
            if (target == null || target.isBlank()) return;
            event.custom.put("targetSystemId", target);
            event.custom.put("targetX", Double.toString(marginPoint(random, world.width)));
            event.custom.put("targetY", Double.toString(marginPoint(random, world.height)));
        }
        state.events.put(event.id, event);
        state.cooldownUntilByDefinition.put(cooldownKey(systemId, selected.id()), clock + selected.cooldownSeconds());
    }

    private static boolean definitionEligible(RuntimeState state, String systemId, double clock,
                                              GalaxyEventDefinition definition) {
        if (clock + 0.000001 < definition.minimumAgeSeconds()) return false;
        if (state.cooldownUntilByDefinition.getOrDefault(cooldownKey(systemId, definition.id()), 0.0) > clock) return false;
        Set<String> roles = definition.eligibleRoles();
        if (roles.isEmpty()) return true;
        String role = clean(StarSystems.get(systemId).role()).toLowerCase(Locale.ROOT);
        return roles.contains(role);
    }

    private static SpawnPoint safeSpawnPoint(World world, GalaxyEventDefinition definition, Random random) {
        int attempts = Math.max(1, definition.placementAttempts());
        for (int attempt = 0; attempt < attempts; attempt++) {
            double x = marginPoint(random, world.width);
            double y = marginPoint(random, world.height);
            if (safeSpawnLocation(world, definition, x, y)) return new SpawnPoint(x, y);
        }
        return null;
    }

    private static boolean safeSpawnLocation(World world, GalaxyEventDefinition definition, double x, double y) {
        if (world == null || definition == null || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        double assetDistance = Math.max(0, definition.minDistanceFromPlayerAssets());
        if (assetDistance > 0) {
            for (Unit unit : world.units.values()) {
                if (unit == null || unit.hp <= 0 || NpcRules.isNpcFaction(unit.playerId)) continue;
                if (Calc.distance(x, y, unit.x, unit.y) < assetDistance) return false;
            }
            for (Base base : world.bases.values()) {
                if (base == null || base.hp <= 0 || NpcRules.isNpcFaction(base.playerId)) continue;
                double required = base.productionQueue.isEmpty() ? assetDistance : assetDistance * 1.5;
                if (Calc.distance(x, y, base.x, base.y) < required) return false;
            }
        }
        double wormholeDistance = Math.max(0, definition.minDistanceFromWormholes());
        if (wormholeDistance > 0) {
            for (WormholeGate gate : world.wormholes) {
                if (gate != null && Calc.distance(x, y, gate.x, gate.y) < wormholeDistance) return false;
            }
        }
        return true;
    }

    private static String cooldownKey(String systemId, String definitionId) {
        return clean(systemId) + '\u0000' + clean(definitionId);
    }

    private static void discover(World world, RuntimeState state, String systemId, double clock) {
        List<PlayerInfo> players = List.copyOf(PlayerRegistry.snapshotPlayers());
        for (GalaxyEventInstance event : new ArrayList<>(state.events.values())) {
            if (!systemId.equals(event.systemId) || terminal(event.phase) || event.phase == GalaxyEventPhase.CLOSING) continue;
            GalaxyEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) continue;

            boolean newlyActivated = false;
            for (PlayerInfo player : players) {
                if (player == null || player.id() == null || player.id().isBlank() || "WAIT".equals(player.id())
                        || NpcRules.isNpcFaction(player.id())) continue;
                if (!eventDetectableBy(world, player.id(), event, definition)) continue;
                if (shareDiscovery(world, event, player.id(), players, definition)) newlyActivated = true;
            }

            if (newlyActivated && event.phase == GalaxyEventPhase.HIDDEN) {
                event.phase = GalaxyEventPhase.ACTIVE;
                event.activatedAt = clock;
                materialize(world, state, event, definition);
            }
        }
    }

    private static boolean eventDetectableBy(World world, String playerId, GalaxyEventInstance event,
                                             GalaxyEventDefinition definition) {
        double cap = Math.max(25, definition.discoveryRadius());
        if (definition.discoveryRule() == GalaxyEventDiscoveryRule.PROXIMITY) {
            for (Unit unit : world.units.values()) {
                if (unit == null || unit.hp <= 0 || !IntelWarfareSystem.allied(world, playerId, unit.playerId)) continue;
                if (Calc.distance(unit.x, unit.y, event.x, event.y) <= cap) return true;
            }
            for (Base base : world.bases.values()) {
                if (base == null || base.hp <= 0 || !IntelWarfareSystem.allied(world, playerId, base.playerId)) continue;
                if (Calc.distance(base.x, base.y, event.x, event.y) <= cap) return true;
            }
            return false;
        }
        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        for (VisibilityRules.Sensor sensor : frame.sensors()) {
            double range = Math.min(cap, Math.max(0, sensor.range()));
            if (range <= 0) continue;
            if (Calc.distance(sensor.x(), sensor.y(), event.x, event.y) <= range) return true;
        }
        return false;
    }

    private static boolean shareDiscovery(World world, GalaxyEventInstance event, String discoverer,
                                          List<PlayerInfo> players, GalaxyEventDefinition definition) {
        boolean changed = false;
        for (PlayerInfo player : players) {
            if (player == null || player.id() == null || player.id().isBlank() || "WAIT".equals(player.id())
                    || NpcRules.isNpcFaction(player.id())) continue;
            if (!player.id().equals(discoverer) && !IntelWarfareSystem.allied(world, discoverer, player.id())) continue;
            if (!event.discoveredBy.add(player.id())) continue;
            changed = true;
            GameNoticeCenter.publish(world, player.id(), NoticeCategory.SYSTEM,
                    "Sensors discovered " + definition.name() + " in " + event.systemId + ".", false);
        }
        return changed;
    }

    private static void advance(World world, RuntimeState state, String systemId, double clock,
                                GalaxyEventCatalog rules) {
        List<String> remove = new ArrayList<>();
        for (GalaxyEventInstance event : new ArrayList<>(state.events.values())) {
            boolean primary = systemId.equals(event.systemId);
            boolean target = systemId.equals(clean(event.custom.get("targetSystemId")));
            if (!primary && !target) continue;
            GalaxyEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) {
                retire(event, state);
                remove.add(event.id);
                continue;
            }

            if (definition.kind() == GalaxyEventKind.UNSTABLE_WORMHOLE && event.materialized) {
                ensureWormholeGate(world, event, systemId);
            }
            if (!primary) continue;

            if (event.phase == GalaxyEventPhase.ACTIVE) {
                switch (definition.kind()) {
                    case RICH_RESOURCE -> {
                        if (ownedResourcesDepleted(world, event)) complete(world, state, event, definition, GalaxyEventPhase.COMPLETED);
                    }
                    case DERELICT_SALVAGE -> {
                        if (ownedItemsGone(world, event)) complete(world, state, event, definition, GalaxyEventPhase.COMPLETED);
                    }
                    case PIRATE_AMBUSH -> {
                        if (ownedUnitsGone(world, event)) complete(world, state, event, definition, GalaxyEventPhase.COMPLETED);
                    }
                    case DISTRESS_SIGNAL -> advanceDistress(world, state, event, definition);
                    case ENVIRONMENTAL, UNSTABLE_WORMHOLE -> { }
                }
            }

            if ((event.phase == GalaxyEventPhase.HIDDEN || event.phase == GalaxyEventPhase.ACTIVE)
                    && clock >= event.expiresAt) {
                if (definition.kind() == GalaxyEventKind.UNSTABLE_WORMHOLE && event.materialized) {
                    event.phase = GalaxyEventPhase.CLOSING;
                    event.custom.put("closeAt", Double.toString(clock + rules.wormholeClosingSeconds()));
                    notifyDiscovered(world, event, "Unstable wormhole is collapsing; new transit is disabled.", NoticeCategory.WARNING);
                } else {
                    complete(world, state, event, definition, GalaxyEventPhase.EXPIRED);
                }
            } else if (event.phase == GalaxyEventPhase.CLOSING) {
                double closeAt = parseDouble(event.custom.get("closeAt"), clock);
                if (clock >= closeAt) complete(world, state, event, definition, GalaxyEventPhase.EXPIRED);
            }

            if (terminal(event.phase)) remove.add(event.id);
        }
        for (String id : remove) state.events.remove(id);
    }

    private static void advanceDistress(World world, RuntimeState state, GalaxyEventInstance event,
                                        GalaxyEventDefinition definition) {
        String civilian = clean(event.custom.get("civilianUnitKey"));
        boolean civilianAlive = civilian.isBlank() || world.units.containsKey(civilian);
        int attackers = 0;
        for (String key : event.ownedUnits) {
            if (key.equals(civilian)) continue;
            if (world.units.containsKey(key)) attackers++;
        }
        if (!civilianAlive) complete(world, state, event, definition, GalaxyEventPhase.FAILED);
        else if (attackers == 0) complete(world, state, event, definition, GalaxyEventPhase.COMPLETED);
    }

    private static void materialize(World world, RuntimeState state, GalaxyEventInstance event,
                                    GalaxyEventDefinition definition) {
        if (event.materialized) return;
        event.materialized = true;
        Random random = new Random(mixSeed(world.systemSeed(), event.id.hashCode(), event.definitionId));
        switch (definition.kind()) {
            case RICH_RESOURCE -> spawnRichResources(world, event, definition, random);
            case DERELICT_SALVAGE -> spawnSalvage(world, event, definition, random);
            case PIRATE_AMBUSH -> spawnPirates(world, event, definition, random);
            case DISTRESS_SIGNAL -> spawnDistress(world, event, definition, random);
            case ENVIRONMENTAL -> notifyDiscovered(world, event,
                    definition.name() + " is affecting local system performance.", NoticeCategory.WARNING);
            case UNSTABLE_WORMHOLE -> {
                event.ownedWormholes.add(event.id + ":A");
                event.ownedWormholes.add(event.id + ":B");
                ensureWormholeGate(world, event, world.activeSystemId());
            }
        }
    }

    private static void ensureMaterializedState(World world, RuntimeState state, String systemId) {
        for (GalaxyEventInstance event : state.events.values()) {
            if (!event.materialized) continue;
            GalaxyEventDefinition definition = catalog().byId(event.definitionId);
            if (definition != null && definition.kind() == GalaxyEventKind.UNSTABLE_WORMHOLE) {
                ensureWormholeGate(world, event, systemId);
            }
        }
    }

    private static void spawnRichResources(World world, GalaxyEventInstance event,
                                           GalaxyEventDefinition definition, Random random) {
        Material material = parseMaterial(definition.resourceMaterial(), Material.RARE_EARTHS);
        NodeKind kind = material == Material.HYDROGEN || material == Material.HELIUM || material == Material.METHANE
                ? NodeKind.GAS_CLOUD : NodeKind.SILICATE_ROCK;
        int nextId = nextResourceId(world);
        int count = Math.max(1, definition.entityCount());
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 45 + random.nextDouble() * 125;
            double x = Calc.clamp(event.x + Math.cos(angle) * distance, 40, Math.max(40, world.width - 40));
            double y = Calc.clamp(event.y + Math.sin(angle) * distance, 40, Math.max(40, world.height - 40));
            double amount = Math.max(50, definition.amount() * (0.85 + random.nextDouble() * 0.3));
            ResourceNode node = new ResourceNode(nextId++, definition.name(), kind, material, x, y,
                    amount, Math.max(2, amount / 60.0), 34);
            world.resources.add(node);
            event.ownedResources.add(node.id);
        }
    }

    private static void spawnSalvage(World world, GalaxyEventInstance event,
                                     GalaxyEventDefinition definition, Random random) {
        Material material = parseMaterial(definition.salvageMaterial(), Material.SCRAP_METAL);
        int count = Math.max(1, definition.entityCount());
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 25 + random.nextDouble() * 120;
            WorldItem item = world.addWorldItem(material,
                    Math.max(1, definition.amount() * (0.7 + random.nextDouble() * 0.6)),
                    Calc.clamp(event.x + Math.cos(angle) * distance, 20, Math.max(20, world.width - 20)),
                    Calc.clamp(event.y + Math.sin(angle) * distance, 20, Math.max(20, world.height - 20)),
                    random.nextDouble() * 8 - 4, random.nextDouble() * 8 - 4,
                    random.nextDouble() * Math.PI * 2, random.nextDouble() * 0.8 - 0.4);
            if (item != null) event.ownedItems.add(item.id);
        }
    }

    private static void spawnPirates(World world, GalaxyEventInstance event,
                                     GalaxyEventDefinition definition, Random random) {
        String factionId = clean(definition.npcFactionId());
        if (factionId.isBlank()) factionId = "NPC_RAIDERS";
        registerNpcFaction(factionId);
        int count = Math.max(1, definition.entityCount());
        for (int i = 0; i < count; i++) {
            String ship = i == count - 1 && count >= 3 ? "destroyer" : "frigate";
            Unit unit = spawnEventUnit(world, factionId, ship,
                    event.x + random.nextDouble() * 180 - 90,
                    event.y + random.nextDouble() * 180 - 90);
            if (unit != null) event.ownedUnits.add(unit.key());
        }
    }

    private static void spawnDistress(World world, GalaxyEventInstance event,
                                      GalaxyEventDefinition definition, Random random) {
        registerNpcFaction("NPC_MINERS");
        Unit civilian = spawnEventUnit(world, "NPC_MINERS", Rules.STARTING_SHIP, event.x, event.y);
        if (civilian != null) {
            civilian.clearOrder();
            civilian.task = UnitTask.IDLE;
            civilian.targetX = civilian.x;
            civilian.targetY = civilian.y;
            event.ownedUnits.add(civilian.key());
            event.custom.put("civilianUnitKey", civilian.key());
        }
        spawnPirates(world, event, definition, random);
    }

    private static Unit spawnEventUnit(World world, String playerId, String shipType, double x, double y) {
        try {
            Rules.ship(shipType);
            int unitId = 1;
            for (Unit existing : new ArrayList<>(world.units.values())) {
                if (playerId.equals(existing.playerId)) unitId = Math.max(unitId, existing.unitId + 1);
            }
            Unit unit = new Unit(playerId, unitId, shipType,
                    Calc.clamp(x, 30, Math.max(30, world.width - 30)),
                    Calc.clamp(y, 30, Math.max(30, world.height - 30)));
            world.units.put(unit.key(), unit);
            return unit;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void registerNpcFaction(String factionId) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.id().equals(factionId)) continue;
            PlayerRegistry.register(faction.id(), faction.name(), faction.rgb(), false);
            return;
        }
    }

    private static void ensureWormholeGate(World world, GalaxyEventInstance event, String systemId) {
        if (world == null || event == null || systemId == null || systemId.isBlank()) return;
        if (event.phase != GalaxyEventPhase.ACTIVE && event.phase != GalaxyEventPhase.CLOSING) return;
        String target = clean(event.custom.get("targetSystemId"));
        if (target.isBlank()) return;
        String sourceGateId = event.id + ":A";
        String targetGateId = event.id + ":B";
        double targetX = Calc.clamp(parseDouble(event.custom.get("targetX"), world.width * 0.5), 40, Math.max(40, world.width - 40));
        double targetY = Calc.clamp(parseDouble(event.custom.get("targetY"), world.height * 0.5), 40, Math.max(40, world.height - 40));
        if (systemId.equals(event.systemId) && !hasGate(world, sourceGateId)) {
            world.wormholes.add(new WormholeGate(sourceGateId, event.systemId, target, event.x, event.y, targetX, targetY));
        } else if (systemId.equals(target) && !hasGate(world, targetGateId)) {
            world.wormholes.add(new WormholeGate(targetGateId, target, event.systemId, targetX, targetY, event.x, event.y));
        }
    }

    private static boolean hasGate(World world, String id) {
        for (WormholeGate gate : world.wormholes) if (gate != null && id.equals(gate.id)) return true;
        return false;
    }

    private static void complete(World world, RuntimeState state, GalaxyEventInstance event,
                                 GalaxyEventDefinition definition, GalaxyEventPhase terminalPhase) {
        if (terminal(event.phase)) return;
        event.phase = terminalPhase;
        if (terminalPhase == GalaxyEventPhase.COMPLETED) {
            notifyDiscovered(world, event, definition.name() + " completed.", NoticeCategory.SYSTEM);
        } else if (terminalPhase == GalaxyEventPhase.FAILED) {
            notifyDiscovered(world, event, definition.name() + " failed.", NoticeCategory.WARNING);
        }
        cleanupOwned(world, state, event);
    }

    private static void cleanupOwned(World world, RuntimeState state, GalaxyEventInstance event) {
        if (world.activeSystemId().equals(event.systemId)) {
            world.resources.removeIf(node -> event.ownedResources.contains(node.id));
            world.items.removeIf(item -> event.ownedItems.contains(item.id));
            world.units.entrySet().removeIf(entry -> event.ownedUnits.contains(entry.getKey()));
        }
        if (!event.ownedWormholes.isEmpty()) {
            state.retiredGateIds.addAll(event.ownedWormholes);
            world.wormholes.removeIf(gate -> event.ownedWormholes.contains(gate.id));
        }
    }

    private static void retire(GalaxyEventInstance event, RuntimeState state) {
        state.retiredGateIds.addAll(event.ownedWormholes);
    }

    private static void retireStaleGates(World world, RuntimeState state) {
        if (state.retiredGateIds.isEmpty()) return;
        Set<String> removed = new LinkedHashSet<>();
        world.wormholes.removeIf(gate -> {
            if (gate == null || !state.retiredGateIds.contains(gate.id)) return false;
            removed.add(gate.id);
            return true;
        });
        state.retiredGateIds.removeAll(removed);
    }

    private static boolean ownedResourcesDepleted(World world, GalaxyEventInstance event) {
        if (event.ownedResources.isEmpty()) return event.materialized;
        for (ResourceNode node : world.resources) {
            if (event.ownedResources.contains(node.id) && node.amount > 0.05) return false;
        }
        return true;
    }

    private static boolean ownedItemsGone(World world, GalaxyEventInstance event) {
        if (event.ownedItems.isEmpty()) return event.materialized;
        for (WorldItem item : world.items) if (event.ownedItems.contains(item.id) && !item.empty()) return false;
        return true;
    }

    private static boolean ownedUnitsGone(World world, GalaxyEventInstance event) {
        if (event.ownedUnits.isEmpty()) return event.materialized;
        for (String key : event.ownedUnits) if (world.units.containsKey(key)) return false;
        return true;
    }

    private static void notifyDiscovered(World world, GalaxyEventInstance event, String text, NoticeCategory category) {
        for (String playerId : event.discoveredBy) GameNoticeCenter.publish(world, playerId, category, text, false);
    }

    private static boolean hasWormholeTarget(World world, String sourceSystemId) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map == null || map.systems() == null) return false;
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && system.id() != null && !system.id().isBlank() && !system.id().equals(sourceSystemId)) return true;
        }
        return false;
    }

    private static String wormholeTarget(World world, String sourceSystemId, Random random) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map == null || map.systems() == null) return null;
        List<String> candidates = new ArrayList<>();
        for (GalaxyMapSystem system : map.systems()) {
            if (system == null || system.id() == null || system.id().isBlank() || system.id().equals(sourceSystemId)) continue;
            candidates.add(system.id());
        }
        candidates.sort(String::compareTo);
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static boolean isHomeSystem(World world, String systemId) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map == null || map.systems() == null) return false;
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && systemId.equals(system.id())) return system.home();
        }
        return false;
    }

    private static int activeCount(RuntimeState state) {
        int count = 0;
        for (GalaxyEventInstance event : state.events.values()) if (!terminal(event.phase)) count++;
        return count;
    }

    private static int activeCount(RuntimeState state, String systemId) {
        int count = 0;
        for (GalaxyEventInstance event : state.events.values()) {
            if (systemId.equals(event.systemId) && !terminal(event.phase)) count++;
        }
        return count;
    }

    private static boolean terminal(GalaxyEventPhase phase) {
        return phase == GalaxyEventPhase.COMPLETED || phase == GalaxyEventPhase.FAILED || phase == GalaxyEventPhase.EXPIRED;
    }

    private static int nextResourceId(World world) {
        int next = 1;
        for (ResourceNode node : world.resources) next = Math.max(next, node.id + 1);
        return next;
    }

    private static double marginPoint(Random random, int extent) {
        double margin = Math.min(300, Math.max(60, extent * 0.08));
        return margin + random.nextDouble() * Math.max(1, extent - margin * 2);
    }

    private static long mixSeed(long seed, long sequence, String salt) {
        long z = seed ^ (sequence * 0x9E3779B97F4A7C15L) ^ (salt == null ? 0 : salt.hashCode() * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static String eventId(long seed, long sequence, String definitionId, String systemId) {
        long mixed = mixSeed(seed, sequence, definitionId + "|" + systemId);
        return "EV-" + Long.toUnsignedString(mixed, 36).toUpperCase(Locale.ROOT)
                + "-" + Long.toUnsignedString(sequence, 36).toUpperCase(Locale.ROOT);
    }

    private static Material parseMaterial(String value, Material fallback) {
        try { return Material.valueOf(clean(value).toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean authoritative(World world) {
        // PeerNetwork assigns WAIT/P*/observer identities to remote clients and SOLO to solo/host/dedicated worlds.
        return world != null && "SOLO".equals(PlayerRegistry.localId());
    }

    private static void restoreDoubleMap(Object saved, Map<String,Double> destination) {
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(saved).entrySet()) {
            String key = clean(entry.getKey());
            double value = ServerSaveStore.asDouble(entry.getValue(), 0);
            if (!key.isBlank() && Double.isFinite(value) && value >= 0) destination.put(key, value);
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static GalaxyEventCatalog catalog() {
        if (catalog == null) catalog = GalaxyEventCatalog.load();
        return catalog;
    }

    private record SpawnPoint(double x, double y) { }

    private static final class RuntimeState {
        long sequence;
        final Map<String,Double> clockBySystem = new LinkedHashMap<>();
        final Map<String,Double> nextEvaluationBySystem = new LinkedHashMap<>();
        final Map<String,Double> cooldownUntilByDefinition = new LinkedHashMap<>();
        final Map<String,GalaxyEventInstance> events = new LinkedHashMap<>();
        final Set<String> retiredGateIds = new LinkedHashSet<>();
    }
}

enum GalaxyEventKind {
    RICH_RESOURCE,
    DERELICT_SALVAGE,
    DISTRESS_SIGNAL,
    PIRATE_AMBUSH,
    ENVIRONMENTAL,
    UNSTABLE_WORMHOLE
}

enum GalaxyEventPhase {
    HIDDEN,
    ACTIVE,
    CLOSING,
    COMPLETED,
    FAILED,
    EXPIRED
}

enum GalaxyEventDiscoveryRule {
    SENSOR,
    PROXIMITY
}

record GalaxyEventView(String eventId, String definitionId, GalaxyEventKind kind, String systemId,
                       String name, GalaxyEventPhase phase, double x, double y, double remainingSeconds) { }

final class GalaxyEventInstance {
    final String id;
    final String definitionId;
    final String systemId;
    final double x;
    final double y;
    GalaxyEventPhase phase;
    final double createdAt;
    double activatedAt = -1;
    final double expiresAt;
    boolean materialized;
    final Set<String> discoveredBy = new LinkedHashSet<>();
    final Set<Integer> ownedResources = new LinkedHashSet<>();
    final Set<Integer> ownedItems = new LinkedHashSet<>();
    final Set<String> ownedUnits = new LinkedHashSet<>();
    final Set<String> ownedWormholes = new LinkedHashSet<>();
    final Map<String,String> custom = new LinkedHashMap<>();

    GalaxyEventInstance(String id, String definitionId, String systemId, double x, double y,
                        GalaxyEventPhase phase, double createdAt, double expiresAt) {
        this.id = id;
        this.definitionId = definitionId;
        this.systemId = systemId;
        this.x = x;
        this.y = y;
        this.phase = phase == null ? GalaxyEventPhase.HIDDEN : phase;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    Map<String,Object> capture() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("definitionId", definitionId);
        out.put("systemId", systemId);
        out.put("x", x);
        out.put("y", y);
        out.put("phase", phase.name());
        out.put("createdAt", createdAt);
        out.put("activatedAt", activatedAt);
        out.put("expiresAt", expiresAt);
        out.put("materialized", materialized);
        out.put("discoveredBy", new ArrayList<>(discoveredBy));
        out.put("ownedResources", new ArrayList<>(ownedResources));
        out.put("ownedItems", new ArrayList<>(ownedItems));
        out.put("ownedUnits", new ArrayList<>(ownedUnits));
        out.put("ownedWormholes", new ArrayList<>(ownedWormholes));
        out.put("custom", new LinkedHashMap<>(custom));
        return out;
    }

    static GalaxyEventInstance restore(Object saved) {
        Map<String,Object> row = ServerSaveStore.object(saved);
        String id = ServerSaveStore.string(row, "id", "").trim();
        String definitionId = ServerSaveStore.string(row, "definitionId", "").trim();
        String systemId = ServerSaveStore.string(row, "systemId", "").trim();
        if (id.isBlank() || definitionId.isBlank() || systemId.isBlank()) return null;
        GalaxyEventInstance event = new GalaxyEventInstance(id, definitionId, systemId,
                ServerSaveStore.doubleValue(row, "x", 0), ServerSaveStore.doubleValue(row, "y", 0),
                ServerSaveStore.enumValue(GalaxyEventPhase.class, row.get("phase"), GalaxyEventPhase.HIDDEN),
                ServerSaveStore.doubleValue(row, "createdAt", 0),
                ServerSaveStore.doubleValue(row, "expiresAt", 0));
        event.activatedAt = ServerSaveStore.doubleValue(row, "activatedAt", -1);
        event.materialized = ServerSaveStore.boolValue(row, "materialized", false);
        restoreStrings(row.get("discoveredBy"), event.discoveredBy);
        restoreInts(row.get("ownedResources"), event.ownedResources);
        restoreInts(row.get("ownedItems"), event.ownedItems);
        restoreStrings(row.get("ownedUnits"), event.ownedUnits);
        restoreStrings(row.get("ownedWormholes"), event.ownedWormholes);
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(row.get("custom")).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) event.custom.put(entry.getKey(), entry.getValue().toString());
        }
        return event;
    }

    private static void restoreStrings(Object saved, Set<String> out) {
        for (Object item : ServerSaveStore.list(saved)) {
            String value = ServerSaveStore.asString(item, "").trim();
            if (!value.isBlank()) out.add(value);
        }
    }

    private static void restoreInts(Object saved, Set<Integer> out) {
        for (Object item : ServerSaveStore.list(saved)) {
            if (item instanceof Number number && number.intValue() >= 0) out.add(number.intValue());
        }
    }
}

record GalaxyEventDefinition(
        String id,
        String name,
        GalaxyEventKind kind,
        boolean enabled,
        double weight,
        boolean safeForHome,
        Set<String> eligibleRoles,
        double minimumAgeSeconds,
        double cooldownSeconds,
        GalaxyEventDiscoveryRule discoveryRule,
        double minDurationSeconds,
        double maxDurationSeconds,
        double discoveryRadius,
        double minDistanceFromPlayerAssets,
        double minDistanceFromWormholes,
        int placementAttempts,
        int entityCount,
        double amount,
        String resourceMaterial,
        String salvageMaterial,
        String npcFactionId,
        SystemModifiers modifiers
) {
    GalaxyEventDefinition {
        eligibleRoles = eligibleRoles == null ? Set.of() : Set.copyOf(eligibleRoles);
        discoveryRule = discoveryRule == null ? GalaxyEventDiscoveryRule.SENSOR : discoveryRule;
        resourceMaterial = resourceMaterial == null ? "" : resourceMaterial.trim();
        salvageMaterial = salvageMaterial == null ? "" : salvageMaterial.trim();
        npcFactionId = npcFactionId == null ? "" : npcFactionId.trim();
        modifiers = modifiers == null ? SystemModifiers.STANDARD : modifiers;
    }
}

final class GalaxyEventCatalog {
    private static final Path CONFIG_PATH = Path.of("config/events.json");
    private final boolean enabled;
    private final double initialDelaySeconds;
    private final double evaluationSeconds;
    private final double spawnChance;
    private final int maxActiveGalaxy;
    private final int maxActivePerSystem;
    private final double wormholeClosingSeconds;
    private final List<GalaxyEventDefinition> definitions;
    private final Map<String,GalaxyEventDefinition> byId;

    private GalaxyEventCatalog(boolean enabled, double initialDelaySeconds, double evaluationSeconds,
                               double spawnChance, int maxActiveGalaxy, int maxActivePerSystem,
                               double wormholeClosingSeconds, List<GalaxyEventDefinition> definitions) {
        this.enabled = enabled;
        this.initialDelaySeconds = Math.max(0, initialDelaySeconds);
        this.evaluationSeconds = Math.max(5, evaluationSeconds);
        this.spawnChance = Calc.clamp(spawnChance, 0, 1);
        this.maxActiveGalaxy = Math.max(1, maxActiveGalaxy);
        this.maxActivePerSystem = Math.max(1, maxActivePerSystem);
        this.wormholeClosingSeconds = Math.max(0.5, wormholeClosingSeconds);
        this.definitions = List.copyOf(definitions);
        Map<String,GalaxyEventDefinition> index = new LinkedHashMap<>();
        for (GalaxyEventDefinition definition : definitions) {
            validateDefinition(definition);
            if (index.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate galaxy event id: " + definition.id());
            }
        }
        byId = Map.copyOf(index);
    }

    static GalaxyEventCatalog load() {
        if (!Files.exists(CONFIG_PATH)) return defaults();
        try {
            Object parsed = MiniJson.parse(Files.readString(CONFIG_PATH));
            Map<String,Object> root = ServerSaveStore.object(parsed);
            Map<String,Object> director = ServerSaveStore.object(root.get("director"));
            List<GalaxyEventDefinition> definitions = new ArrayList<>();
            for (Object item : ServerSaveStore.list(root.get("events"))) {
                GalaxyEventDefinition definition = parseDefinition(ServerSaveStore.object(item));
                if (definition != null) definitions.add(definition);
            }
            if (definitions.isEmpty()) throw new IllegalStateException("config/events.json contains no valid events.");
            return new GalaxyEventCatalog(
                    ServerSaveStore.boolValue(director, "enabled", true),
                    ServerSaveStore.doubleValue(director, "initialDelaySeconds", 30),
                    ServerSaveStore.doubleValue(director, "evaluationSeconds", 45),
                    ServerSaveStore.doubleValue(director, "spawnChance", 0.35),
                    ServerSaveStore.intValue(director, "maxActiveGalaxy", 4),
                    ServerSaveStore.intValue(director, "maxActivePerSystem", 2),
                    ServerSaveStore.doubleValue(director, "wormholeClosingSeconds", 3),
                    definitions);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Could not load galaxy event config: " + ex.getMessage(), ex);
        }
    }

    private static GalaxyEventDefinition parseDefinition(Map<String,Object> row) {
        String id = ServerSaveStore.string(row, "id", "").trim();
        String name = ServerSaveStore.string(row, "name", id).trim();
        if (id.isBlank()) return null;
        GalaxyEventKind kind;
        try {
            kind = GalaxyEventKind.valueOf(ServerSaveStore.string(row, "kind", "").trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Galaxy event " + id + " has invalid kind.");
        }
        GalaxyEventDiscoveryRule discoveryRule;
        try {
            discoveryRule = GalaxyEventDiscoveryRule.valueOf(
                    ServerSaveStore.string(row, "discoveryRule", "SENSOR").trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Galaxy event " + id + " has invalid discoveryRule.");
        }
        double weight = ServerSaveStore.doubleValue(row, "weight", 1);
        double minimumAge = ServerSaveStore.doubleValue(row, "minimumAgeSeconds", 0);
        double cooldown = ServerSaveStore.doubleValue(row, "cooldownSeconds", 180);
        double minDuration = ServerSaveStore.doubleValue(row, "minDurationSeconds", 90);
        double maxDuration = ServerSaveStore.doubleValue(row, "maxDurationSeconds", 180);
        double discoveryRadius = ServerSaveStore.doubleValue(row, "discoveryRadius", 900);
        double minAssetDistance = ServerSaveStore.doubleValue(row, "minDistanceFromPlayerAssets", 500);
        double minWormholeDistance = ServerSaveStore.doubleValue(row, "minDistanceFromWormholes", 350);
        int placementAttempts = ServerSaveStore.intValue(row, "placementAttempts", 24);
        int entityCount = ServerSaveStore.intValue(row, "entityCount", 3);
        double amount = ServerSaveStore.doubleValue(row, "amount", 250);
        if (weight < 0 || minimumAge < 0 || cooldown < 0 || minDuration < 5 || maxDuration < minDuration
                || discoveryRadius < 25 || minAssetDistance < 0 || minWormholeDistance < 0
                || placementAttempts < 1 || placementAttempts > 256 || entityCount < 1 || amount <= 0) {
            throw new IllegalStateException("Galaxy event " + id + " has invalid numeric bounds.");
        }
        Map<String,Object> modifiers = ServerSaveStore.object(row.get("modifiers"));
        return new GalaxyEventDefinition(
                id, name.isBlank() ? id : name, kind,
                ServerSaveStore.boolValue(row, "enabled", true),
                weight,
                ServerSaveStore.boolValue(row, "safeForHome", false),
                parseLowercaseSet(row.get("eligibleRoles")),
                minimumAge,
                cooldown,
                discoveryRule,
                minDuration,
                maxDuration,
                discoveryRadius,
                minAssetDistance,
                minWormholeDistance,
                placementAttempts,
                entityCount,
                amount,
                ServerSaveStore.string(row, "resourceMaterial", "RARE_EARTHS"),
                ServerSaveStore.string(row, "salvageMaterial", "SCRAP_METAL"),
                ServerSaveStore.string(row, "npcFactionId", "NPC_RAIDERS"),
                new SystemModifiers(
                        ServerSaveStore.doubleValue(modifiers, "miningYield", 1),
                        ServerSaveStore.doubleValue(modifiers, "resourceRespawn", 1),
                        ServerSaveStore.doubleValue(modifiers, "sensorRange", 1),
                        ServerSaveStore.doubleValue(modifiers, "shieldRegen", 1),
                        ServerSaveStore.doubleValue(modifiers, "movementSpeed", 1),
                        ServerSaveStore.doubleValue(modifiers, "weaponRange", 1),
                        Math.max(0, ServerSaveStore.doubleValue(modifiers, "environmentalDamagePerSecond", 0))));
    }

    private static void validateDefinition(GalaxyEventDefinition definition) {
        if (definition == null || definition.id() == null || definition.id().isBlank()) {
            throw new IllegalStateException("Galaxy event definitions require a non-empty id.");
        }
        Set<String> knownRoles = new LinkedHashSet<>();
        for (StarSystemDefinition system : StarSystems.options()) {
            if (system != null && system.role() != null && !system.role().isBlank()) {
                knownRoles.add(system.role().trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String role : definition.eligibleRoles()) {
            if (!knownRoles.contains(role)) {
                throw new IllegalStateException("Galaxy event " + definition.id() + " references unknown system role " + role + ".");
            }
        }
        if (definition.kind() == GalaxyEventKind.RICH_RESOURCE) {
            requireMaterial(definition.id(), "resourceMaterial", definition.resourceMaterial());
        }
        if (definition.kind() == GalaxyEventKind.DERELICT_SALVAGE) {
            requireMaterial(definition.id(), "salvageMaterial", definition.salvageMaterial());
        }
        if (definition.kind() == GalaxyEventKind.PIRATE_AMBUSH || definition.kind() == GalaxyEventKind.DISTRESS_SIGNAL) {
            boolean found = false;
            for (NpcFaction faction : NpcRules.factions()) {
                if (faction != null && faction.id().equals(definition.npcFactionId())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException("Galaxy event " + definition.id() + " references unknown NPC faction "
                        + definition.npcFactionId() + ".");
            }
        }
    }

    private static Material requireMaterial(String eventId, String field, String value) {
        try {
            return Material.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Galaxy event " + eventId + " has invalid " + field + ": " + value + ".");
        }
    }

    private static Set<String> parseLowercaseSet(Object value) {
        Set<String> out = new LinkedHashSet<>();
        for (Object item : ServerSaveStore.list(value)) {
            String text = ServerSaveStore.asString(item, "").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank()) out.add(text);
        }
        return Set.copyOf(out);
    }

    private static GalaxyEventCatalog defaults() {
        return new GalaxyEventCatalog(true, 30, 45, 0.35, 4, 2, 3, List.of(
                definition("rich_rare_earths", "Rich Rare-Earth Deposit", GalaxyEventKind.RICH_RESOURCE,
                        true, 3, true, Set.of(), 30, 180, GalaxyEventDiscoveryRule.SENSOR, 150, 300, 850,
                        500, 350, 24, 4, 420, "RARE_EARTHS", "SCRAP_METAL", "NPC_RAIDERS", SystemModifiers.STANDARD),
                definition("derelict_convoy", "Derelict Convoy", GalaxyEventKind.DERELICT_SALVAGE,
                        true, 2.5, true, Set.of("relic"), 45, 240, GalaxyEventDiscoveryRule.SENSOR, 150, 300, 850,
                        600, 400, 24, 5, 55, "RARE_EARTHS", "SCRAP_METAL", "NPC_RAIDERS", SystemModifiers.STANDARD),
                definition("distress_beacon", "Distress Beacon", GalaxyEventKind.DISTRESS_SIGNAL,
                        true, 2, true, Set.of(), 60, 240, GalaxyEventDiscoveryRule.SENSOR, 180, 330, 1050,
                        900, 450, 32, 3, 1, "RARE_EARTHS", "SCRAP_METAL", "NPC_RAIDERS", SystemModifiers.STANDARD),
                definition("pirate_ambush", "Pirate Ambush", GalaxyEventKind.PIRATE_AMBUSH,
                        true, 1.5, false, Set.of("danger"), 90, 300, GalaxyEventDiscoveryRule.PROXIMITY, 150, 260, 850,
                        1200, 500, 40, 4, 1, "RARE_EARTHS", "SCRAP_METAL", "NPC_RAIDERS", SystemModifiers.STANDARD),
                definition("ion_storm", "Ion Storm", GalaxyEventKind.ENVIRONMENTAL,
                        true, 1.25, false, Set.of("danger"), 90, 300, GalaxyEventDiscoveryRule.SENSOR, 120, 240, 1000,
                        1400, 650, 48, 1, 1, "RARE_EARTHS", "SCRAP_METAL", "NPC_RAIDERS",
                        new SystemModifiers(1, 1, 0.65, 1.25, 0.9, 0.85, 0)),
                definition("unstable_wormhole", "Unstable Wormhole", GalaxyEventKind.UNSTABLE_WORMHOLE,
                        true, 0.55, false, Set.of(), 120, 420, GalaxyEventDiscoveryRule.SENSOR, 150, 260, 1100,
                        1000, 900, 48, 1, 1, "RARE_EARTHS", "SCRAP_METAL", "NPC_RAIDERS", SystemModifiers.STANDARD)));
    }

    private static GalaxyEventDefinition definition(String id, String name, GalaxyEventKind kind,
                                                     boolean enabled, double weight, boolean safeForHome,
                                                     Set<String> eligibleRoles, double minimumAgeSeconds,
                                                     double cooldownSeconds, GalaxyEventDiscoveryRule discoveryRule,
                                                     double minDurationSeconds, double maxDurationSeconds,
                                                     double discoveryRadius, double minDistanceFromPlayerAssets,
                                                     double minDistanceFromWormholes, int placementAttempts,
                                                     int entityCount, double amount, String resourceMaterial,
                                                     String salvageMaterial, String npcFactionId,
                                                     SystemModifiers modifiers) {
        return new GalaxyEventDefinition(id, name, kind, enabled, weight, safeForHome, eligibleRoles,
                minimumAgeSeconds, cooldownSeconds, discoveryRule, minDurationSeconds, maxDurationSeconds,
                discoveryRadius, minDistanceFromPlayerAssets, minDistanceFromWormholes, placementAttempts,
                entityCount, amount, resourceMaterial, salvageMaterial, npcFactionId, modifiers);
    }

    boolean enabled() { return enabled; }
    double initialDelaySeconds() { return initialDelaySeconds; }
    double evaluationSeconds() { return evaluationSeconds; }
    double spawnChance() { return spawnChance; }
    int maxActiveGalaxy() { return maxActiveGalaxy; }
    int maxActivePerSystem() { return maxActivePerSystem; }
    double wormholeClosingSeconds() { return wormholeClosingSeconds; }
    List<GalaxyEventDefinition> definitions() { return definitions; }
    GalaxyEventDefinition byId(String id) { return byId.get(id); }
}
