package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Generic runtime for config/events.json version-3 advanced events.
 *
 * This layer intentionally owns mechanics, not content. Every event-specific
 * number and authored choice is supplied by JSON. The existing v2 director
 * remains responsible for the original six event kinds; this runtime adds
 * staged objectives, moving storms, chains, competition, research/diplomacy
 * rewards, temporary hidden systems, history and administrative controls.
 */
final class GalaxyEventExtensions {
    private static final String SAVE_KEY = "__galaxyEventExtensions";
    private static final String EVENT_OWNER_PREFIX = "EVENT_";
    private static final Map<World, AdvancedRuntimeState> STATES = new WeakHashMap<>();
    private static GalaxyEventExtensionCatalog catalog;

    private GalaxyEventExtensions() { }

    static String saveKey() { return SAVE_KEY; }

    static synchronized void update(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt <= 0 || !authoritative(world)) return;
        GalaxyEventExtensionCatalog rules = catalog();
        AdvancedEventDirectorDefinition director = rules.director();
        AdvancedRuntimeState state = STATES.computeIfAbsent(world, ignored -> new AdvancedRuntimeState());
        if (!director.enabled() || state.paused) return;
        String systemId = clean(world.activeSystemId());
        if (systemId.isBlank()) return;

        double clock = state.clockBySystem.getOrDefault(systemId, 0.0) + dt;
        state.clockBySystem.put(systemId, clock);
        runScheduledChains(world, state, systemId, clock);
        ensurePocketGates(world, state, systemId);
        discover(world, state, systemId, dt);
        advance(world, state, systemId, clock, dt);
        evaluateSpawn(world, state, systemId, clock);
        enforceEventOrders(world, state, systemId);
    }

    static synchronized double nextDueInSeconds(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank()) return Double.POSITIVE_INFINITY;
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null || state.paused || !catalog().director().enabled()) return Double.POSITIVE_INFINITY;
        double clock = state.clockBySystem.getOrDefault(systemId, 0.0);
        double best = Math.max(0, state.nextEvaluationBySystem.getOrDefault(systemId,
                catalog().director().initialDelaySeconds()) - clock);
        for (AdvancedEventInstance event : state.events.values()) {
            if (terminal(event.phase)) continue;
            if (systemId.equals(event.currentSystemId) || systemId.equals(event.sourceSystemId)
                    || systemId.equals(event.pocketSystemId)) {
                best = Math.min(best, Math.max(0, event.expiresAt - clockFor(state, event.currentSystemId)));
                AdvancedEventDefinition definition = catalog().byId(event.definitionId);
                if (definition != null && definition.scope().mode() == AdvancedScopeMode.GALAXY_MOVING) {
                    best = Math.min(best, Math.max(0, event.nextScopeMoveAt - clockFor(state, event.currentSystemId)));
                }
                AdvancedEventStage stage = currentStage(definition, event);
                if (stage != null && stage.timeoutSeconds() > 0) {
                    best = Math.min(best, Math.max(0,
                            event.stageStartedAt + stage.timeoutSeconds() - clockFor(state, event.currentSystemId)));
                }
            }
        }
        for (AdvancedScheduledChain chain : state.scheduledChains) {
            if (systemId.equals(chain.systemId())) best = Math.min(best, Math.max(0, chain.dueAt() - clock));
        }
        return Double.isFinite(best) ? Math.max(0.01, best) : Double.POSITIVE_INFINITY;
    }

    static synchronized SystemModifiers temporaryModifiers(World world, String systemId) {
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null || systemId == null || systemId.isBlank()) return SystemModifiers.STANDARD;
        double mining = 1, respawn = 1, sensors = 1, shields = 1, movement = 1, weapons = 1, damage = 0;
        for (AdvancedEventInstance event : state.events.values()) {
            if (event.phase != GalaxyEventPhase.ACTIVE || !systemId.equals(event.currentSystemId)) continue;
            AdvancedEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) continue;
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

    static synchronized List<GalaxyEventView> viewsFor(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank() || !authoritative(world)) return List.of();
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null) return List.of();
        List<GalaxyEventView> out = new ArrayList<>();
        for (AdvancedEventInstance event : state.events.values()) {
            if (terminal(event.phase) || !visibleToRecipient(world, event, playerId)) continue;
            AdvancedEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) continue;
            double clock = clockFor(state, event.currentSystemId);
            double remaining = Math.max(0, event.expiresAt - clock);
            String name = definition.name();
            AdvancedEventStage stage = currentStage(definition, event);
            if (stage != null && !stage.name().isBlank()) name += " — " + stage.name();
            out.add(new GalaxyEventView(event.id, event.definitionId, definition.wireKind(),
                    event.currentSystemId, name, event.phase, event.x, event.y, remaining));
        }
        out.sort(Comparator.comparing(GalaxyEventView::systemId).thenComparing(GalaxyEventView::eventId));
        return List.copyOf(out);
    }

    static synchronized List<GalaxyMapLink> temporaryLinksFor(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank() || !authoritative(world)) return List.of();
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null) return List.of();
        List<GalaxyMapLink> out = new ArrayList<>();
        for (AdvancedEventInstance event : state.events.values()) {
            if (event.pocketSystemId.isBlank() || terminal(event.phase) || !visibleToRecipient(world, event, playerId)) continue;
            out.add(new GalaxyMapLink(event.sourceSystemId, event.pocketSystemId));
        }
        return List.copyOf(out);
    }

    static synchronized boolean systemVisibleTo(World world, String playerId, String systemId) {
        if (world == null || systemId == null || systemId.isBlank()) return false;
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null) return true;
        for (AdvancedEventInstance event : state.events.values()) {
            if (!systemId.equals(event.pocketSystemId)) continue;
            return playerId != null && !playerId.isBlank() && visibleToRecipient(world, event, playerId);
        }
        return true;
    }

    static synchronized List<AdvancedEventHistoryEntry> history(World world) {
        AdvancedRuntimeState state = STATES.get(world);
        return state == null ? List.of() : List.copyOf(state.history);
    }

    static synchronized Map<String,Object> capture(World world) {
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("sequence", state.sequence);
        out.put("paused", state.paused);
        out.put("clockBySystem", new LinkedHashMap<>(state.clockBySystem));
        out.put("nextEvaluationBySystem", new LinkedHashMap<>(state.nextEvaluationBySystem));
        out.put("cooldownUntilByDefinition", new LinkedHashMap<>(state.cooldownUntilByDefinition));
        List<Object> events = new ArrayList<>();
        for (AdvancedEventInstance event : state.events.values()) events.add(event.capture());
        out.put("events", events);
        List<Object> chains = new ArrayList<>();
        for (AdvancedScheduledChain chain : state.scheduledChains) chains.add(chain.capture());
        out.put("scheduledChains", chains);
        List<Object> history = new ArrayList<>();
        for (AdvancedEventHistoryEntry entry : state.history) history.add(entry.capture());
        out.put("history", history);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> root = ServerSaveStore.object(saved);
        if (root.isEmpty()) {
            STATES.remove(world);
            return;
        }
        AdvancedRuntimeState state = new AdvancedRuntimeState();
        state.sequence = Math.max(0, ServerSaveStore.longValue(root, "sequence", 0));
        state.paused = ServerSaveStore.boolValue(root, "paused", false);
        restoreDoubleMap(root.get("clockBySystem"), state.clockBySystem);
        restoreDoubleMap(root.get("nextEvaluationBySystem"), state.nextEvaluationBySystem);
        restoreDoubleMap(root.get("cooldownUntilByDefinition"), state.cooldownUntilByDefinition);
        for (Object value : ServerSaveStore.list(root.get("events"))) {
            AdvancedEventInstance event = AdvancedEventInstance.restore(value);
            if (event != null && catalog().byId(event.definitionId) != null) state.events.put(event.id, event);
        }
        for (Object value : ServerSaveStore.list(root.get("scheduledChains"))) {
            AdvancedScheduledChain chain = AdvancedScheduledChain.restore(value);
            if (chain != null && catalog().byId(chain.definitionId()) != null) state.scheduledChains.add(chain);
        }
        for (Object value : ServerSaveStore.list(root.get("history"))) {
            AdvancedEventHistoryEntry entry = AdvancedEventHistoryEntry.restore(value);
            if (entry != null) state.history.add(entry);
        }
        trimHistory(state);
        STATES.put(world, state);
    }

    static synchronized void removeSystems(World world, Iterable<String> systemIds) {
        AdvancedRuntimeState state = STATES.get(world);
        if (state == null || systemIds == null) return;
        Set<String> removed = new LinkedHashSet<>();
        for (String id : systemIds) if (!clean(id).isBlank()) removed.add(clean(id));
        for (String id : removed) {
            state.clockBySystem.remove(id);
            state.nextEvaluationBySystem.remove(id);
            state.cooldownUntilByDefinition.keySet().removeIf(key -> key.startsWith(id + "\u0000"));
        }
        state.events.values().removeIf(event -> removed.contains(event.sourceSystemId)
                || removed.contains(event.currentSystemId) || removed.contains(event.pocketSystemId));
        state.scheduledChains.removeIf(chain -> removed.contains(chain.systemId()));
    }

    static synchronized List<String> admin(World world, List<String> args) {
        if (world == null || !authoritative(world)) return List.of("Galaxy event administration requires the authoritative world.");
        AdvancedRuntimeState state = STATES.computeIfAbsent(world, ignored -> new AdvancedRuntimeState());
        String action = args == null || args.isEmpty() ? "status" : clean(args.get(0)).toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> {
                return List.of("Advanced events: " + (state.paused ? "paused" : "running")
                        + " | active=" + activeCount(state) + " | history=" + state.history.size()
                        + " | definitions=" + catalog().definitions().size());
            }
            case "pause" -> { state.paused = true; return List.of("Advanced galaxy events paused."); }
            case "resume" -> { state.paused = false; return List.of("Advanced galaxy events resumed."); }
            case "reload" -> {
                catalog = GalaxyEventExtensionCatalog.load();
                return List.of("Reloaded config/events.json version " + GalaxyEventExtensionCatalog.SCHEMA_VERSION + ".");
            }
            case "list" -> {
                List<String> out = new ArrayList<>();
                for (AdvancedEventInstance event : state.events.values()) {
                    out.add(event.id + " | " + event.definitionId + " | " + event.phase + " | " + event.currentSystemId
                            + " | stage=" + event.stageIndex + " | winner=" + clean(event.winnerId));
                }
                return out.isEmpty() ? List.of("No active advanced galaxy events.") : List.copyOf(out);
            }
            case "history" -> {
                List<String> out = new ArrayList<>();
                for (AdvancedEventHistoryEntry entry : state.history) {
                    out.add(entry.eventId() + " | " + entry.definitionId() + " | " + entry.phase()
                            + " | " + entry.systemId() + " | winner=" + entry.winnerId());
                }
                return out.isEmpty() ? List.of("Advanced galaxy event history is empty.") : List.copyOf(out);
            }
            case "clear" -> {
                for (AdvancedEventInstance event : new ArrayList<>(state.events.values())) cleanupOwned(world, event);
                state.events.clear();
                state.scheduledChains.clear();
                return List.of("Cleared active advanced galaxy events and scheduled chains.");
            }
            case "force" -> {
                if (args.size() < 2) return List.of("Usage: galaxy-events force <definition-id> [system-id]");
                String definitionId = clean(args.get(1));
                AdvancedEventDefinition definition = catalog().byId(definitionId);
                if (definition == null) return List.of("Unknown advanced galaxy event: " + definitionId);
                String systemId = args.size() >= 3 ? clean(args.get(2)) : clean(world.activeSystemId());
                String previous = clean(world.activeSystemId());
                if (!systemId.equals(previous)) world.activateSystem(systemId);
                AdvancedEventInstance event = spawnDefinition(world, state, definition, systemId,
                        clockFor(state, systemId), true, null);
                if (!systemId.equals(previous) && !previous.isBlank()) world.activateSystem(previous);
                return event == null ? List.of("Could not place advanced galaxy event in " + systemId + ".")
                        : List.of("Forced " + definition.id() + " as " + event.id + " in " + systemId + ".");
            }
            default -> {
                return List.of("Usage: galaxy-events <status|pause|resume|reload|list|history|clear|force <id> [system]>");
            }
        }
    }

    static synchronized void clear(World world) { if (world != null) STATES.remove(world); }
    static synchronized void reloadCatalogForValidation() { catalog = null; }

    static synchronized AdvancedEventInstance forceSpawnForValidation(World world, String definitionId) {
        if (world == null) return null;
        AdvancedEventDefinition definition = catalog().byId(definitionId);
        if (definition == null) return null;
        AdvancedRuntimeState state = STATES.computeIfAbsent(world, ignored -> new AdvancedRuntimeState());
        return spawnDefinition(world, state, definition, world.activeSystemId(),
                clockFor(state, world.activeSystemId()), true, null);
    }

    private static void evaluateSpawn(World world, AdvancedRuntimeState state, String systemId, double clock) {
        AdvancedEventDirectorDefinition director = catalog().director();
        double next = state.nextEvaluationBySystem.getOrDefault(systemId, director.initialDelaySeconds());
        if (clock + 0.000001 < next) return;
        state.nextEvaluationBySystem.put(systemId, clock + director.evaluationSeconds());
        long sequence = ++state.sequence;
        Random random = new Random(mixSeed(world.systemSeed(), sequence, systemId));
        if (random.nextDouble() > director.spawnChance()) return;
        if (activeCount(state) >= director.maxActiveGalaxy() || activeCount(state, systemId) >= director.maxActivePerSystem()) return;
        boolean home = isHomeSystem(world, systemId);
        List<AdvancedEventDefinition> candidates = new ArrayList<>();
        double totalWeight = 0;
        for (AdvancedEventDefinition definition : catalog().definitions()) {
            if (!definition.enabled() || definition.weight() <= 0) continue;
            if (activeDefinitionCount(state, definition.id()) >= definition.maxActiveInstances()) continue;
            if (home && !definition.safeForHome()) continue;
            if (clock < definition.minimumAgeSeconds()) continue;
            if (state.cooldownUntilByDefinition.getOrDefault(cooldownKey(systemId, definition.id()), 0.0) > clock) continue;
            if (!definition.eligibleRoles().isEmpty()) {
                String role = clean(StarSystems.get(systemId).role()).toLowerCase(Locale.ROOT);
                if (!definition.eligibleRoles().contains(role)) continue;
            }
            candidates.add(definition);
            totalWeight += definition.weight();
        }
        if (candidates.isEmpty() || totalWeight <= 0) return;
        double pick = random.nextDouble() * totalWeight;
        AdvancedEventDefinition selected = candidates.get(candidates.size() - 1);
        for (AdvancedEventDefinition candidate : candidates) {
            pick -= candidate.weight();
            if (pick <= 0) { selected = candidate; break; }
        }
        spawnDefinition(world, state, selected, systemId, clock, false, random);
    }

    private static AdvancedEventInstance spawnDefinition(World world, AdvancedRuntimeState state,
                                                         AdvancedEventDefinition definition,
                                                         String systemId, double clock,
                                                         boolean forceReveal, Random suppliedRandom) {
        if (definition == null || systemId == null || systemId.isBlank()) return null;
        Random random = suppliedRandom == null
                ? new Random(mixSeed(world.systemSeed(), ++state.sequence, definition.id() + "|" + systemId))
                : suppliedRandom;
        AdvancedPoint point = safeSpawnPoint(world, definition, random);
        if (point == null) return null;
        double duration = definition.minDurationSeconds()
                + random.nextDouble() * Math.max(0, definition.maxDurationSeconds() - definition.minDurationSeconds());
        String id = "AX-" + Long.toUnsignedString(mixSeed(world.systemSeed(), state.sequence,
                definition.id() + "|" + systemId), 36).toUpperCase(Locale.ROOT);
        AdvancedEventInstance event = new AdvancedEventInstance(id, definition.id(), systemId, systemId,
                point.x(), point.y(), GalaxyEventPhase.HIDDEN, clock, clock + duration);
        event.nextScopeMoveAt = clock + definition.scope().moveEverySeconds();
        state.events.put(id, event);
        state.cooldownUntilByDefinition.put(cooldownKey(systemId, definition.id()), clock + definition.cooldownSeconds());
        if (forceReveal) {
            String owner = firstHumanPlayer(world);
            if (!owner.isBlank()) event.discoveredBy.add(owner);
            event.phase = GalaxyEventPhase.ACTIVE;
            event.activatedAt = clock;
            materializeInitial(world, event, definition);
        }
        return event;
    }

    private static AdvancedPoint safeSpawnPoint(World world, AdvancedEventDefinition definition, Random random) {
        for (int i = 0; i < definition.placement().attempts(); i++) {
            double x = marginPoint(random, world.width);
            double y = marginPoint(random, world.height);
            if (safeSpawnLocation(world, definition.placement(), x, y)) return new AdvancedPoint(x, y);
        }
        return null;
    }

    private static boolean safeSpawnLocation(World world, AdvancedEventPlacement placement, double x, double y) {
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || !humanOwner(unit.playerId)) continue;
            if (Calc.distance(x, y, unit.x, unit.y) < placement.minDistanceFromPlayerAssets()) return false;
        }
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || !humanOwner(base.playerId)) continue;
            if (Calc.distance(x, y, base.x, base.y) < placement.minDistanceFromPlayerAssets()) return false;
        }
        for (WormholeGate gate : world.wormholes) {
            if (gate != null && Calc.distance(x, y, gate.x, gate.y) < placement.minDistanceFromWormholes()) return false;
        }
        return true;
    }

    private static void discover(World world, AdvancedRuntimeState state, String systemId, double dt) {
        List<PlayerInfo> players = List.copyOf(PlayerRegistry.snapshotPlayers());
        for (AdvancedEventInstance event : state.events.values()) {
            if (event.phase != GalaxyEventPhase.HIDDEN || !systemId.equals(event.currentSystemId)) continue;
            AdvancedEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) continue;
            for (PlayerInfo player : players) {
                if (player == null || !humanOwner(player.id())) continue;
                boolean contact = discoveryContact(world, player.id(), event, definition.discovery());
                if (definition.discovery().mode() == AdvancedDiscoveryMode.SCAN) {
                    double progress = contact ? event.discoveryProgress.getOrDefault(player.id(), 0.0) + dt : 0;
                    event.discoveryProgress.put(player.id(), progress);
                    if (progress + 0.000001 < definition.discovery().scanSeconds()) continue;
                } else if (!contact) continue;
                shareDiscovery(world, event, player.id(), players, definition.name());
                if (event.phase == GalaxyEventPhase.HIDDEN) {
                    event.phase = GalaxyEventPhase.ACTIVE;
                    event.activatedAt = clockFor(state, systemId);
                    materializeInitial(world, event, definition);
                }
            }
        }
    }

    private static boolean discoveryContact(World world, String playerId, AdvancedEventInstance event,
                                            AdvancedEventDiscovery discovery) {
        if (discovery.mode() == AdvancedDiscoveryMode.SYSTEM_ENTRY) return hasPlayerAsset(world, playerId);
        if (discovery.mode() == AdvancedDiscoveryMode.PROXIMITY) return playerAssetNear(world, playerId,
                event.x, event.y, discovery.radius());
        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        for (VisibilityRules.Sensor sensor : frame.sensors()) {
            double range = Math.min(discovery.radius(), Math.max(0, sensor.range()));
            if (range > 0 && Calc.distance(sensor.x(), sensor.y(), event.x, event.y) <= range) return true;
        }
        return false;
    }

    private static void shareDiscovery(World world, AdvancedEventInstance event, String discoverer,
                                       List<PlayerInfo> players, String name) {
        for (PlayerInfo player : players) {
            if (player == null || !humanOwner(player.id())) continue;
            if (!player.id().equals(discoverer) && !DiplomacySystem.allied(world, discoverer, player.id())) continue;
            if (event.discoveredBy.add(player.id())) {
                GameNoticeCenter.publish(world, player.id(), NoticeCategory.SYSTEM,
                        "Sensors discovered " + name + " in " + event.currentSystemId + ".", false);
            }
        }
    }

    private static void materializeInitial(World world, AdvancedEventInstance event, AdvancedEventDefinition definition) {
        if (event.initialMaterialized) return;
        event.initialMaterialized = true;
        spawn(world, event, definition.spawn(), 0);
        if (definition.spawn().pocketSystem().enabled()) createPocketSystem(world, event, definition.spawn().pocketSystem());
        event.stageStartedAt = event.activatedAt >= 0 ? event.activatedAt : 0;
        if (!definition.stages().isEmpty()) materializeStage(world, event, definition, 0);
    }

    private static void materializeStage(World world, AdvancedEventInstance event,
                                         AdvancedEventDefinition definition, int index) {
        if (index < 0 || index >= definition.stages().size() || event.materializedStages.contains(index)) return;
        AdvancedEventStage stage = definition.stages().get(index);
        event.materializedStages.add(index);
        spawn(world, event, stage.spawn(), index + 1);
        if (stage.spawn().pocketSystem().enabled()) createPocketSystem(world, event, stage.spawn().pocketSystem());
        event.custom.put("stageInitialResources:" + index, Integer.toString(event.ownedResources.size()));
        event.custom.put("stageInitialItems:" + index, Integer.toString(event.ownedItems.size()));
        event.custom.put("stageInitialUnits:" + index, Integer.toString(event.ownedUnits.size()));
    }

    private static void spawn(World world, AdvancedEventInstance event, AdvancedEventSpawn spawn, int salt) {
        if (spawn == null) return;
        Random random = new Random(mixSeed(world.systemSeed(), event.id.hashCode(), event.definitionId + "|" + salt));
        int resourceId = nextResourceId(world);
        for (AdvancedResourceSpawn spec : spawn.resources()) {
            NodeKind kind = gas(spec.material()) ? NodeKind.GAS_CLOUD : NodeKind.SILICATE_ROCK;
            for (int i = 0; i < spec.count(); i++) {
                AdvancedPoint p = spread(world, event.x, event.y, spec.spreadRadius(), random);
                ResourceNode node = new ResourceNode(resourceId++, kind, p.x(), p.y(), 48,
                        spec.amount(), Map.of(spec.material(), 1.0), spec.harvestRate());
                world.resources.add(node);
                event.ownedResources.add(node.id);
            }
        }
        for (AdvancedItemSpawn spec : spawn.items()) {
            for (int i = 0; i < spec.count(); i++) {
                AdvancedPoint p = spread(world, event.x, event.y, spec.spreadRadius(), random);
                WorldItem item = world.addWorldItem(spec.material(), spec.amount(), p.x(), p.y(), 0, 0,
                        random.nextDouble() * Math.PI * 2, 0);
                if (item != null) event.ownedItems.add(item.id);
            }
        }
        for (AdvancedUnitSpawn spec : spawn.units()) {
            String owner = clean(spec.ownerId());
            if (owner.isBlank()) owner = EVENT_OWNER_PREFIX + spec.role().name();
            PlayerRegistry.register(owner, clean(spec.ownerName()).isBlank() ? owner : spec.ownerName(), spec.ownerColorRgb(), false);
            int nextUnit = nextUnitNumber(world, owner);
            for (int i = 0; i < spec.count(); i++) {
                AdvancedPoint p = spread(world, event.x, event.y, spec.spreadRadius(), random);
                Unit unit;
                try { unit = new Unit(owner, nextUnit++, spec.shipTypeId(), p.x(), p.y()); }
                catch (RuntimeException ex) { throw new IllegalStateException("Invalid event ship type " + spec.shipTypeId(), ex); }
                world.units.put(unit.key(), unit);
                event.ownedUnits.add(unit.key());
                event.unitRoles.put(unit.key(), spec.role());
            }
        }
    }

    private static void advance(World world, AdvancedRuntimeState state, String systemId, double clock, double dt) {
        List<String> remove = new ArrayList<>();
        for (AdvancedEventInstance event : new ArrayList<>(state.events.values())) {
            AdvancedEventDefinition definition = catalog().byId(event.definitionId);
            if (definition == null) { cleanupOwned(world, event); remove.add(event.id); continue; }
            if (terminal(event.phase)) { remove.add(event.id); continue; }

            if (definition.scope().mode() == AdvancedScopeMode.GALAXY_MOVING
                    && systemId.equals(event.currentSystemId) && event.phase == GalaxyEventPhase.ACTIVE
                    && clock >= event.nextScopeMoveAt && event.scopeMoves < definition.scope().systemCount() - 1) {
                moveScope(world, state, event, definition, clock);
            }

            if (event.phase == GalaxyEventPhase.HIDDEN) {
                if (systemId.equals(event.currentSystemId) && clock >= event.expiresAt) finish(world, state, event,
                        definition, GalaxyEventPhase.EXPIRED, "");
                continue;
            }

            boolean relevant = systemId.equals(event.currentSystemId) || systemId.equals(event.pocketSystemId);
            if (!relevant) continue;

            if (event.phase == GalaxyEventPhase.CLOSING) {
                if (!pocketOccupied(world, event)) finish(world, state, event, definition, GalaxyEventPhase.EXPIRED, event.winnerId);
                continue;
            }

            AdvancedEventStage stage = currentStage(definition, event);
            if (stage != null) {
                double stageClock = clockFor(state, event.currentSystemId);
                if (stage.timeoutSeconds() > 0 && stageClock >= event.stageStartedAt + stage.timeoutSeconds()) {
                    finish(world, state, event, definition, GalaxyEventPhase.FAILED, event.winnerId);
                    continue;
                }
                if (stage.objective().npcCompetition() && stage.objective().npcCompetitionSeconds() > 0
                        && stageClock >= event.stageStartedAt + stage.objective().npcCompetitionSeconds()) {
                    finish(world, state, event, definition, GalaxyEventPhase.FAILED, "NPC_COMPETITOR");
                    continue;
                }
                ObjectiveResolution resolution = evaluateObjective(world, event, definition, stage, dt);
                if (resolution.failed()) {
                    finish(world, state, event, definition, GalaxyEventPhase.FAILED, resolution.playerId());
                    continue;
                }
                if (resolution.completed()) {
                    String winner = clean(resolution.playerId());
                    if (!winner.isBlank() && !winner.startsWith(EVENT_OWNER_PREFIX)) event.winnerId = winner;
                    applyRewards(world, event.winnerId, stage.rewards());
                    if (resolution.choice() != null) applyRewards(world, event.winnerId, resolution.choice().rewards());
                    advanceStage(world, event, definition, resolution.choice() == null ? "" : resolution.choice().nextStageId());
                    if (event.stageIndex >= definition.stages().size()) {
                        finish(world, state, event, definition, GalaxyEventPhase.COMPLETED, event.winnerId);
                        continue;
                    }
                }
            }

            if (clockFor(state, event.currentSystemId) >= event.expiresAt) {
                if (!event.pocketSystemId.isBlank() && pocketOccupied(world, event)) beginPocketClosing(world, event);
                else finish(world, state, event, definition, GalaxyEventPhase.EXPIRED, event.winnerId);
            }
        }
        for (String id : remove) state.events.remove(id);
    }

    private static ObjectiveResolution evaluateObjective(World world, AdvancedEventInstance event,
                                                         AdvancedEventDefinition definition,
                                                         AdvancedEventStage stage, double dt) {
        AdvancedEventObjective objective = stage.objective();
        List<String> nearby = playersNear(world, event.x, event.y, objective.radius());
        return switch (objective.type()) {
            case SCAN -> objectiveScan(world, event, objective, dt);
            case HOLD_AREA -> objectiveHold(world, event, objective, nearby, dt);
            case DELIVER -> objectiveConsume(world, event, objective.material(), objective.amount(), nearby,
                    event.x, event.y, objective.radius(), "deliver");
            case REPAIR -> objectiveConsume(world, event, objective.repairMaterial(), objective.repairAmount(), nearby,
                    event.x, event.y, objective.radius(), "repair");
            case ESCORT -> objectiveEscort(world, event, objective, nearby);
            case DESTROY -> objectiveDestroy(world, event, objective, nearby);
            case SALVAGE -> objectiveSalvage(world, event, objective, nearby);
            case MINE -> objectiveMine(world, event, objective, nearby);
            case SURVIVE -> objectiveSurvive(event, objective, nearby, dt);
            case REACH_LOCATION -> objectiveReach(world, event, objective, nearby);
            case CHOICE -> objectiveChoice(world, event, objective);
        };
    }

    private static ObjectiveResolution objectiveScan(World world, AdvancedEventInstance event,
                                                      AdvancedEventObjective objective, double dt) {
        List<String> players = sortedDiscovered(event);
        for (String playerId : players) {
            boolean contact = sensorContact(world, playerId, event.x, event.y, objective.radius());
            double progress = contact ? event.progressByPlayer.getOrDefault("scan:" + playerId, 0.0) + dt : 0;
            event.progressByPlayer.put("scan:" + playerId, progress);
            if (progress + 0.000001 >= objective.seconds()) return ObjectiveResolution.completed(playerId);
        }
        return ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveHold(World world, AdvancedEventInstance event,
                                                      AdvancedEventObjective objective, List<String> nearby, double dt) {
        if (nearby.isEmpty()) return ObjectiveResolution.PENDING;
        if (objective.contested() && hostileContest(world, nearby)) return ObjectiveResolution.PENDING;
        String playerId = nearby.get(0);
        double progress = event.progressByPlayer.getOrDefault("hold:" + playerId, 0.0) + dt;
        event.progressByPlayer.put("hold:" + playerId, progress);
        return progress + 0.000001 >= objective.seconds() ? ObjectiveResolution.completed(playerId) : ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveConsume(World world, AdvancedEventInstance event, Material material,
                                                         double target, List<String> nearby, double x, double y,
                                                         double radius, String keyPrefix) {
        if (material == null || target <= 0) return ObjectiveResolution.failed("");
        for (String playerId : nearby) {
            String key = keyPrefix + ":" + playerId;
            double progress = event.progressByPlayer.getOrDefault(key, 0.0);
            double need = Math.max(0, target - progress);
            if (need <= 0) return ObjectiveResolution.completed(playerId);
            double consumed = consumeCargo(world, playerId, material, need, x, y, radius);
            if (consumed > 0) {
                progress += consumed;
                event.progressByPlayer.put(key, progress);
                if (progress + 0.000001 >= target) return ObjectiveResolution.completed(playerId);
            }
        }
        return ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveEscort(World world, AdvancedEventInstance event,
                                                        AdvancedEventObjective objective, List<String> nearby) {
        Unit civilian = firstRoleUnit(world, event, AdvancedUnitRole.CIVILIAN);
        if (civilian == null) return ObjectiveResolution.failed(event.winnerId);
        if (event.winnerId.isBlank() && !nearby.isEmpty()) event.winnerId = nearby.get(0);
        if (event.winnerId.isBlank()) return ObjectiveResolution.PENDING;
        AdvancedPoint target = escortTarget(world, event.winnerId, civilian, objective.destination(), event);
        if (target == null) return ObjectiveResolution.PENDING;
        civilian.moveTo(target.x(), target.y());
        return Calc.distance(civilian.x, civilian.y, target.x(), target.y()) <= Math.max(30, objective.radius() * 0.25)
                ? ObjectiveResolution.completed(event.winnerId) : ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveDestroy(World world, AdvancedEventInstance event,
                                                         AdvancedEventObjective objective, List<String> nearby) {
        int initial = parseInt(event.custom.get("stageInitialUnits:" + event.stageIndex), event.ownedUnits.size());
        int alive = 0;
        for (String key : event.ownedUnits) {
            AdvancedUnitRole role = event.unitRoles.get(key);
            if (role != AdvancedUnitRole.HOSTILE && role != AdvancedUnitRole.BOSS && role != AdvancedUnitRole.COMPETITOR) continue;
            if (world.units.containsKey(key) || EventSystemAccess.unitExists(world, key)) alive++;
        }
        int destroyed = Math.max(0, initial - alive);
        int target = objective.targetCount() > 0 ? objective.targetCount() : initial;
        if (target <= 0 || destroyed < target) return ObjectiveResolution.PENDING;
        return ObjectiveResolution.completed(!nearby.isEmpty() ? nearby.get(0) : firstDiscovered(event));
    }

    private static ObjectiveResolution objectiveSalvage(World world, AdvancedEventInstance event,
                                                         AdvancedEventObjective objective, List<String> nearby) {
        int initial = parseInt(event.custom.get("stageInitialItems:" + event.stageIndex), event.ownedItems.size());
        int remaining = EventSystemAccess.remainingItems(world, event.ownedItems);
        int collected = Math.max(0, initial - remaining);
        int target = objective.targetCount() > 0 ? objective.targetCount() : initial;
        return target > 0 && collected >= target
                ? ObjectiveResolution.completed(!nearby.isEmpty() ? nearby.get(0) : firstDiscovered(event))
                : ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveMine(World world, AdvancedEventInstance event,
                                                      AdvancedEventObjective objective, List<String> nearby) {
        int initial = parseInt(event.custom.get("stageInitialResources:" + event.stageIndex), event.ownedResources.size());
        int remaining = EventSystemAccess.remainingResources(world, event.ownedResources);
        int depleted = Math.max(0, initial - remaining);
        int target = objective.targetCount() > 0 ? objective.targetCount() : initial;
        return target > 0 && depleted >= target
                ? ObjectiveResolution.completed(!nearby.isEmpty() ? nearby.get(0) : firstDiscovered(event))
                : ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveSurvive(AdvancedEventInstance event,
                                                         AdvancedEventObjective objective, List<String> nearby, double dt) {
        String player = !nearby.isEmpty() ? nearby.get(0) : firstDiscovered(event);
        if (player.isBlank()) return ObjectiveResolution.PENDING;
        String key = "survive:" + player;
        double progress = event.progressByPlayer.getOrDefault(key, 0.0) + dt;
        event.progressByPlayer.put(key, progress);
        return progress + 0.000001 >= objective.seconds() ? ObjectiveResolution.completed(player) : ObjectiveResolution.PENDING;
    }

    private static ObjectiveResolution objectiveReach(World world, AdvancedEventInstance event,
                                                       AdvancedEventObjective objective, List<String> nearby) {
        if (!event.pocketSystemId.isBlank() && objective.destination() == AdvancedDestination.WORMHOLE) {
            for (String player : event.discoveredBy) {
                if (world.ownerUnitLocations(player).containsValue(event.pocketSystemId)) return ObjectiveResolution.completed(player);
            }
        }
        return nearby.isEmpty() ? ObjectiveResolution.PENDING : ObjectiveResolution.completed(nearby.get(0));
    }

    private static ObjectiveResolution objectiveChoice(World world, AdvancedEventInstance event,
                                                        AdvancedEventObjective objective) {
        for (AdvancedChoice choice : objective.choices()) {
            double x = event.x + choice.offsetX();
            double y = event.y + choice.offsetY();
            List<String> nearby = playersNear(world, x, y, choice.radius());
            if (!nearby.isEmpty()) return ObjectiveResolution.choice(nearby.get(0), choice);
        }
        return ObjectiveResolution.PENDING;
    }

    private static void advanceStage(World world, AdvancedEventInstance event, AdvancedEventDefinition definition,
                                     String explicitNextStageId) {
        int next = event.stageIndex + 1;
        if (explicitNextStageId != null && !explicitNextStageId.isBlank()) {
            for (int i = 0; i < definition.stages().size(); i++) {
                if (explicitNextStageId.equals(definition.stages().get(i).id())) { next = i; break; }
            }
        }
        event.stageIndex = next;
        event.stageStartedAt = clockFor(STATES.get(world), event.currentSystemId);
        if (next < definition.stages().size()) {
            materializeStage(world, event, definition, next);
            notifyDiscovered(world, event, definition.name() + ": " + definition.stages().get(next).name(), NoticeCategory.SYSTEM);
        }
    }

    private static void finish(World world, AdvancedRuntimeState state, AdvancedEventInstance event,
                               AdvancedEventDefinition definition, GalaxyEventPhase phase, String winner) {
        if (terminal(event.phase)) return;
        event.phase = phase;
        if (!clean(winner).isBlank() && !winner.startsWith(EVENT_OWNER_PREFIX)) event.winnerId = winner;
        if (phase == GalaxyEventPhase.COMPLETED) {
            applyRewards(world, event.winnerId, definition.rewards());
            notifyDiscovered(world, event, definition.name() + " completed.", NoticeCategory.SYSTEM);
        } else if (phase == GalaxyEventPhase.FAILED) {
            notifyDiscovered(world, event, definition.name() + " failed.", NoticeCategory.WARNING);
        }
        cleanupOwned(world, event);
        addHistory(state, new AdvancedEventHistoryEntry(event.id, event.definitionId, event.sourceSystemId,
                phase, event.winnerId, clockFor(state, event.currentSystemId)));
        scheduleChain(state, event, definition, phase);
    }

    private static void applyRewards(World world, String playerId, List<AdvancedEventReward> rewards) {
        if (world == null || playerId == null || playerId.isBlank() || rewards == null) return;
        for (AdvancedEventReward reward : rewards) {
            switch (reward.type()) {
                case MATERIAL -> grantMaterial(world, playerId, reward.material(), reward.amount());
                case RESEARCH -> world.completeResearch(playerId, reward.researchId());
                case NOTICE -> {
                    if (!reward.message().isBlank()) GameNoticeCenter.publish(world, playerId, NoticeCategory.SYSTEM,
                            reward.message(), false);
                }
                case RELATIONSHIP -> DiplomacySystem.setRelationship(world, playerId, reward.factionId(), reward.relationship());
            }
        }
    }

    private static void grantMaterial(World world, String playerId, Material material, double amount) {
        if (material == null || amount <= 0) return;
        Base base = null;
        for (Base candidate : world.bases.values()) {
            if (candidate.hp > 0 && playerId.equals(candidate.playerId)) { base = candidate; break; }
        }
        if (base != null) {
            base.inventory.merge(material, amount, Double::sum);
            return;
        }
        for (Unit unit : world.units.values()) {
            if (!playerId.equals(unit.playerId) || unit.hp <= 0) continue;
            double take = Math.min(amount, unit.freeCargo());
            if (take > 0) unit.addCargo(material, take);
            return;
        }
    }

    private static double consumeCargo(World world, String playerId, Material material, double requested,
                                       double x, double y, double radius) {
        if (requested <= 0) return 0;
        List<Unit> candidates = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (unit.hp > 0 && playerId.equals(unit.playerId)
                    && Calc.distance(unit.x, unit.y, x, y) <= radius) candidates.add(unit);
        }
        candidates.sort(Comparator.comparing(Unit::key));
        double consumed = 0;
        for (Unit unit : candidates) {
            double held = unit.inventory.getOrDefault(material, 0.0);
            double take = Math.min(held, requested - consumed);
            if (take <= 0) continue;
            double left = held - take;
            if (left <= 0.05) unit.inventory.remove(material); else unit.inventory.put(material, left);
            consumed += take;
            if (consumed + 0.000001 >= requested) break;
        }
        return consumed;
    }

    private static void enforceEventOrders(World world, AdvancedRuntimeState state, String systemId) {
        for (AdvancedEventInstance event : state.events.values()) {
            if (event.phase != GalaxyEventPhase.ACTIVE || !systemId.equals(event.currentSystemId)) continue;
            for (String key : event.ownedUnits) {
                Unit unit = world.units.get(key);
                if (unit == null || unit.hp <= 0) continue;
                AdvancedUnitRole role = event.unitRoles.get(key);
                if (role == AdvancedUnitRole.HOSTILE || role == AdvancedUnitRole.BOSS || role == AdvancedUnitRole.COMPETITOR) {
                    String target = nearestHumanTarget(world, unit);
                    if (!target.isBlank()) unit.attack(target);
                }
            }
        }
    }

    private static String nearestHumanTarget(World world, Unit attacker) {
        String best = "";
        double bestDistance = Double.MAX_VALUE;
        for (Unit target : world.units.values()) {
            if (target == null || target.hp <= 0 || !humanOwner(target.playerId)) continue;
            double distance = Calc.distance(attacker.x, attacker.y, target.x, target.y);
            if (distance < bestDistance) { bestDistance = distance; best = CombatTarget.unit(target); }
        }
        for (Base target : world.bases.values()) {
            if (target == null || target.hp <= 0 || !humanOwner(target.playerId)) continue;
            double distance = Calc.distance(attacker.x, attacker.y, target.x, target.y);
            if (distance < bestDistance) { bestDistance = distance; best = CombatTarget.base(target); }
        }
        return best;
    }

    private static void moveScope(World world, AdvancedRuntimeState state, AdvancedEventInstance event,
                                  AdvancedEventDefinition definition, double clock) {
        List<String> systems = new ArrayList<>();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map != null) for (GalaxyMapSystem system : map.systems()) {
            if (system != null && system.id() != null && !system.id().isBlank() && !system.id().equals(event.currentSystemId)
                    && !system.id().equals(event.pocketSystemId)) systems.add(system.id());
        }
        systems.sort(String::compareTo);
        if (systems.isEmpty()) return;
        int index = Math.floorMod((event.id + "|" + event.scopeMoves).hashCode(), systems.size());
        event.currentSystemId = systems.get(index);
        event.scopeMoves++;
        event.nextScopeMoveAt = clockFor(state, event.currentSystemId) + definition.scope().moveEverySeconds();
        notifyDiscovered(world, event, definition.name() + " moved into " + event.currentSystemId + ".", NoticeCategory.WARNING);
    }

    private static void scheduleChain(AdvancedRuntimeState state, AdvancedEventInstance event,
                                      AdvancedEventDefinition definition, GalaxyEventPhase phase) {
        AdvancedEventChain chain = definition.chain();
        String next = phase == GalaxyEventPhase.COMPLETED ? chain.onComplete() : chain.onFail();
        if (next.isBlank() || chain.chance() <= 0) return;
        Random random = new Random(mixSeed(event.id.hashCode(), event.stageIndex, next));
        if (random.nextDouble() > chain.chance()) return;
        double now = clockFor(state, event.sourceSystemId);
        state.scheduledChains.add(new AdvancedScheduledChain(next, event.sourceSystemId,
                now + chain.delaySeconds(), event.winnerId));
    }

    private static void runScheduledChains(World world, AdvancedRuntimeState state, String systemId, double clock) {
        List<AdvancedScheduledChain> due = new ArrayList<>();
        for (AdvancedScheduledChain chain : state.scheduledChains) {
            if (systemId.equals(chain.systemId()) && clock >= chain.dueAt()) due.add(chain);
        }
        state.scheduledChains.removeAll(due);
        for (AdvancedScheduledChain chain : due) {
            AdvancedEventDefinition definition = catalog().byId(chain.definitionId());
            AdvancedEventInstance event = spawnDefinition(world, state, definition, systemId, clock, true, null);
            if (event != null && !chain.inheritedWinnerId().isBlank()) event.winnerId = chain.inheritedWinnerId();
        }
    }

    private static void createPocketSystem(World world, AdvancedEventInstance event, AdvancedPocketSystem pocket) {
        if (!pocket.enabled() || !event.pocketSystemId.isBlank()) return;
        String systemId = event.id.toLowerCase(Locale.ROOT) + "-" + clean(pocket.idSuffix()).toLowerCase(Locale.ROOT);
        WorldSystemState state = EventSystemAccess.ensureTemporarySystem(world, systemId, pocket.templateId());
        if (state == null) return;
        event.pocketSystemId = systemId;
        event.custom.put("pocketExitX", Double.toString(pocket.exitX()));
        event.custom.put("pocketExitY", Double.toString(pocket.exitY()));
        event.custom.put("pocketGateOffsetX", Double.toString(pocket.gateOffsetX()));
        event.custom.put("pocketGateOffsetY", Double.toString(pocket.gateOffsetY()));
        ensurePocketGates(world, STATES.get(world), world.activeSystemId());
    }

    private static void ensurePocketGates(World world, AdvancedRuntimeState state, String activeSystemId) {
        if (state == null) return;
        for (AdvancedEventInstance event : state.events.values()) {
            if (event.pocketSystemId.isBlank() || terminal(event.phase)) continue;
            double px = EventSystemAccess.systemWidth(world, event.pocketSystemId)
                    * parseDouble(event.custom.get("pocketExitX"), 0.5);
            double py = EventSystemAccess.systemHeight(world, event.pocketSystemId)
                    * parseDouble(event.custom.get("pocketExitY"), 0.5);
            double sourceX = event.x + parseDouble(event.custom.get("pocketGateOffsetX"), 0);
            double sourceY = event.y + parseDouble(event.custom.get("pocketGateOffsetY"), 0);
            String a = event.id + ":POCKET:A";
            String b = event.id + ":POCKET:B";
            if (event.phase == GalaxyEventPhase.ACTIVE) {
                EventSystemAccess.ensureGate(world, event.sourceSystemId,
                        new WormholeGate(a, event.sourceSystemId, event.pocketSystemId, sourceX, sourceY, px, py));
            }
            EventSystemAccess.ensureGate(world, event.pocketSystemId,
                    new WormholeGate(b, event.pocketSystemId, event.sourceSystemId, px, py, sourceX, sourceY));
            event.ownedWormholes.add(a);
            event.ownedWormholes.add(b);
        }
    }

    private static void beginPocketClosing(World world, AdvancedEventInstance event) {
        if (event.phase == GalaxyEventPhase.CLOSING) return;
        event.phase = GalaxyEventPhase.CLOSING;
        EventSystemAccess.removeGate(world, event.sourceSystemId, event.id + ":POCKET:A");
        notifyDiscovered(world, event, "Temporary pocket is closing; entry is disabled and remaining ships should exit.",
                NoticeCategory.WARNING);
    }

    private static boolean pocketOccupied(World world, AdvancedEventInstance event) {
        return !event.pocketSystemId.isBlank() && EventSystemAccess.hasHumanAssets(world, event.pocketSystemId);
    }

    private static void cleanupOwned(World world, AdvancedEventInstance event) {
        EventSystemAccess.removeOwned(world, event.ownedResources, event.ownedItems, event.ownedUnits, event.ownedWormholes);
        if (!event.pocketSystemId.isBlank() && !EventSystemAccess.hasHumanAssets(world, event.pocketSystemId)) {
            EventSystemAccess.removeTemporarySystem(world, event.pocketSystemId);
        }
    }

    private static AdvancedPoint escortTarget(World world, String playerId, Unit civilian,
                                              AdvancedDestination destination, AdvancedEventInstance event) {
        if (destination == AdvancedDestination.NEAREST_BASE) {
            Base best = null; double dist = Double.MAX_VALUE;
            for (Base base : world.bases.values()) {
                if (!playerId.equals(base.playerId) || base.hp <= 0) continue;
                double d = Calc.distance(civilian.x, civilian.y, base.x, base.y);
                if (d < dist) { dist = d; best = base; }
            }
            if (best != null) return new AdvancedPoint(best.x, best.y);
        } else if (destination == AdvancedDestination.WORMHOLE) {
            WormholeGate best = null; double dist = Double.MAX_VALUE;
            for (WormholeGate gate : world.wormholes) {
                double d = Calc.distance(civilian.x, civilian.y, gate.x, gate.y);
                if (d < dist) { dist = d; best = gate; }
            }
            if (best != null) return new AdvancedPoint(best.x, best.y);
        }
        return new AdvancedPoint(event.x, event.y);
    }

    private static Unit firstRoleUnit(World world, AdvancedEventInstance event, AdvancedUnitRole role) {
        for (Map.Entry<String,AdvancedUnitRole> entry : event.unitRoles.entrySet()) {
            if (entry.getValue() != role) continue;
            Unit unit = world.units.get(entry.getKey());
            if (unit != null && unit.hp > 0) return unit;
        }
        return null;
    }

    private static boolean hostileContest(World world, List<String> players) {
        for (int i = 0; i < players.size(); i++) for (int j = i + 1; j < players.size(); j++) {
            if (DiplomacySystem.hostile(world, players.get(i), players.get(j))) return true;
        }
        return false;
    }

    private static List<String> playersNear(World world, double x, double y, double radius) {
        Set<String> out = new LinkedHashSet<>();
        for (Unit unit : world.units.values()) {
            if (unit.hp > 0 && humanOwner(unit.playerId) && Calc.distance(unit.x, unit.y, x, y) <= radius) out.add(unit.playerId);
        }
        for (Base base : world.bases.values()) {
            if (base.hp > 0 && humanOwner(base.playerId) && Calc.distance(base.x, base.y, x, y) <= radius) out.add(base.playerId);
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static boolean playerAssetNear(World world, String playerId, double x, double y, double radius) {
        for (Unit unit : world.units.values()) if (unit.hp > 0 && playerId.equals(unit.playerId)
                && Calc.distance(unit.x, unit.y, x, y) <= radius) return true;
        for (Base base : world.bases.values()) if (base.hp > 0 && playerId.equals(base.playerId)
                && Calc.distance(base.x, base.y, x, y) <= radius) return true;
        return false;
    }

    private static boolean hasPlayerAsset(World world, String playerId) {
        for (Unit unit : world.units.values()) if (unit.hp > 0 && playerId.equals(unit.playerId)) return true;
        for (Base base : world.bases.values()) if (base.hp > 0 && playerId.equals(base.playerId)) return true;
        return false;
    }

    private static boolean sensorContact(World world, String playerId, double x, double y, double radius) {
        VisibilityRules.Frame frame = VisibilityRules.frame(world, playerId);
        for (VisibilityRules.Sensor sensor : frame.sensors()) {
            double range = Math.min(radius, Math.max(0, sensor.range()));
            if (range > 0 && Calc.distance(sensor.x(), sensor.y(), x, y) <= range) return true;
        }
        return false;
    }

    private static boolean visibleToRecipient(World world, AdvancedEventInstance event, String recipientId) {
        if (!ObserverSessions.isObserver(world, recipientId)) return event.discoveredBy.contains(recipientId);
        ObserverSessions.VisibilityMode mode = ObserverSessions.mode(world, recipientId);
        if (mode == ObserverSessions.VisibilityMode.FULL) return event.phase != GalaxyEventPhase.HIDDEN && !event.discoveredBy.isEmpty();
        String owner = ObserverSessions.visibilityOwner(world, recipientId);
        return !owner.isBlank() && event.discoveredBy.contains(owner);
    }

    private static boolean isHomeSystem(World world, String systemId) {
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (map == null) return false;
        for (GalaxyMapSystem system : map.systems()) if (systemId.equals(system.id())) return system.home();
        return false;
    }

    private static String firstHumanPlayer(World world) {
        List<String> players = playersNear(world, world.width * 0.5, world.height * 0.5,
                Math.max(world.width, world.height) * 2.0);
        if (!players.isEmpty()) return players.get(0);
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) if (player != null && humanOwner(player.id())) return player.id();
        return "";
    }

    private static List<String> sortedDiscovered(AdvancedEventInstance event) {
        List<String> out = new ArrayList<>(event.discoveredBy);
        out.removeIf(id -> !humanOwner(id));
        out.sort(String::compareTo);
        return out;
    }

    private static String firstDiscovered(AdvancedEventInstance event) {
        List<String> players = sortedDiscovered(event);
        return players.isEmpty() ? "" : players.get(0);
    }

    private static boolean humanOwner(String ownerId) {
        return ownerId != null && !ownerId.isBlank() && !"WAIT".equals(ownerId)
                && !ownerId.startsWith(EVENT_OWNER_PREFIX) && !NpcRules.isNpcFaction(ownerId);
    }

    private static int nextResourceId(World world) {
        int next = 1;
        for (ResourceNode node : world.resources) next = Math.max(next, node.id + 1);
        for (WorldSystemState state : EventSystemAccess.systems(world).values()) {
            for (ResourceNode node : state.resources) next = Math.max(next, node.id + 1);
        }
        return next;
    }

    private static int nextUnitNumber(World world, String ownerId) {
        int next = 1;
        for (String key : world.ownerUnitLocations(ownerId).keySet()) {
            int at = key.lastIndexOf(':');
            if (at < 0) continue;
            try { next = Math.max(next, Integer.parseInt(key.substring(at + 1)) + 1); }
            catch (RuntimeException ignored) { }
        }
        for (Unit unit : world.units.values()) if (ownerId.equals(unit.playerId)) next = Math.max(next, unit.unitId + 1);
        return next;
    }

    private static AdvancedPoint spread(World world, double x, double y, double radius, Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = random.nextDouble() * Math.max(0, radius);
        return new AdvancedPoint(Calc.clamp(x + Math.cos(angle) * distance, 40, Math.max(40, world.width - 40)),
                Calc.clamp(y + Math.sin(angle) * distance, 40, Math.max(40, world.height - 40)));
    }

    private static boolean gas(Material material) {
        return material == Material.HYDROGEN || material == Material.HELIUM || material == Material.METHANE
                || material == Material.AMMONIA || material == Material.NITROGEN || material == Material.NEON
                || material == Material.ARGON || material == Material.XENON;
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

    private static double clockFor(AdvancedRuntimeState state, String systemId) {
        return state == null ? 0 : state.clockBySystem.getOrDefault(clean(systemId), 0.0);
    }

    private static String cooldownKey(String systemId, String definitionId) {
        return clean(systemId) + '\u0000' + clean(definitionId);
    }

    private static int activeCount(AdvancedRuntimeState state) {
        int count = 0;
        for (AdvancedEventInstance event : state.events.values()) if (!terminal(event.phase)) count++;
        return count;
    }

    private static int activeCount(AdvancedRuntimeState state, String systemId) {
        int count = 0;
        for (AdvancedEventInstance event : state.events.values()) {
            if (!terminal(event.phase) && systemId.equals(event.currentSystemId)) count++;
        }
        return count;
    }

    private static int activeDefinitionCount(AdvancedRuntimeState state, String definitionId) {
        int count = 0;
        for (AdvancedEventInstance event : state.events.values()) {
            if (!terminal(event.phase) && definitionId.equals(event.definitionId)) count++;
        }
        return count;
    }

    private static AdvancedEventStage currentStage(AdvancedEventDefinition definition, AdvancedEventInstance event) {
        return definition == null || event.stageIndex < 0 || event.stageIndex >= definition.stages().size()
                ? null : definition.stages().get(event.stageIndex);
    }

    private static boolean terminal(GalaxyEventPhase phase) {
        return phase == GalaxyEventPhase.COMPLETED || phase == GalaxyEventPhase.FAILED || phase == GalaxyEventPhase.EXPIRED;
    }

    private static void notifyDiscovered(World world, AdvancedEventInstance event, String text, NoticeCategory category) {
        for (String playerId : event.discoveredBy) GameNoticeCenter.publish(world, playerId, category, text, false);
    }

    private static boolean authoritative(World world) { return world != null && "SOLO".equals(PlayerRegistry.localId()); }

    private static void restoreDoubleMap(Object saved, Map<String,Double> out) {
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(saved).entrySet()) {
            String key = clean(entry.getKey());
            double value = ServerSaveStore.asDouble(entry.getValue(), 0);
            if (!key.isBlank() && Double.isFinite(value) && value >= 0) out.put(key, value);
        }
    }

    private static void addHistory(AdvancedRuntimeState state, AdvancedEventHistoryEntry entry) {
        state.history.add(entry);
        trimHistory(state);
    }

    private static void trimHistory(AdvancedRuntimeState state) {
        int limit = catalog().director().historyLimit();
        while (state.history.size() > limit) state.history.remove(0);
    }

    private static double parseDouble(String value, double fallback) {
        try { double parsed = Double.parseDouble(value); return Double.isFinite(parsed) ? parsed : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ex) { return fallback; }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static GalaxyEventExtensionCatalog catalog() {
        if (catalog == null) catalog = GalaxyEventExtensionCatalog.load();
        return catalog;
    }

    private record AdvancedPoint(double x, double y) { }

    private record ObjectiveResolution(boolean completed, boolean failed, String playerId, AdvancedChoice choice) {
        static final ObjectiveResolution PENDING = new ObjectiveResolution(false, false, "", null);
        static ObjectiveResolution completed(String playerId) { return new ObjectiveResolution(true, false, clean(playerId), null); }
        static ObjectiveResolution failed(String playerId) { return new ObjectiveResolution(false, true, clean(playerId), null); }
        static ObjectiveResolution choice(String playerId, AdvancedChoice choice) { return new ObjectiveResolution(true, false, clean(playerId), choice); }
    }

    private static final class AdvancedRuntimeState {
        long sequence;
        boolean paused;
        final Map<String,Double> clockBySystem = new LinkedHashMap<>();
        final Map<String,Double> nextEvaluationBySystem = new LinkedHashMap<>();
        final Map<String,Double> cooldownUntilByDefinition = new LinkedHashMap<>();
        final Map<String,AdvancedEventInstance> events = new LinkedHashMap<>();
        final List<AdvancedScheduledChain> scheduledChains = new ArrayList<>();
        final List<AdvancedEventHistoryEntry> history = new ArrayList<>();
    }
}

final class AdvancedEventInstance {
    final String id;
    final String definitionId;
    final String sourceSystemId;
    String currentSystemId;
    final double x;
    final double y;
    GalaxyEventPhase phase;
    final double createdAt;
    double activatedAt = -1;
    final double expiresAt;
    int stageIndex;
    double stageStartedAt;
    boolean initialMaterialized;
    String winnerId = "";
    String pocketSystemId = "";
    int scopeMoves;
    double nextScopeMoveAt = Double.POSITIVE_INFINITY;
    final Set<String> discoveredBy = new LinkedHashSet<>();
    final Map<String,Double> discoveryProgress = new LinkedHashMap<>();
    final Map<String,Double> progressByPlayer = new LinkedHashMap<>();
    final Set<Integer> ownedResources = new LinkedHashSet<>();
    final Set<Integer> ownedItems = new LinkedHashSet<>();
    final Set<String> ownedUnits = new LinkedHashSet<>();
    final Set<String> ownedWormholes = new LinkedHashSet<>();
    final Map<String,AdvancedUnitRole> unitRoles = new LinkedHashMap<>();
    final Set<Integer> materializedStages = new LinkedHashSet<>();
    final Map<String,String> custom = new LinkedHashMap<>();

    AdvancedEventInstance(String id, String definitionId, String sourceSystemId, String currentSystemId,
                          double x, double y, GalaxyEventPhase phase, double createdAt, double expiresAt) {
        this.id = id; this.definitionId = definitionId; this.sourceSystemId = sourceSystemId;
        this.currentSystemId = currentSystemId; this.x = x; this.y = y;
        this.phase = phase; this.createdAt = createdAt; this.expiresAt = expiresAt;
    }

    Map<String,Object> capture() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", id); out.put("definitionId", definitionId); out.put("sourceSystemId", sourceSystemId);
        out.put("currentSystemId", currentSystemId); out.put("x", x); out.put("y", y); out.put("phase", phase.name());
        out.put("createdAt", createdAt); out.put("activatedAt", activatedAt); out.put("expiresAt", expiresAt);
        out.put("stageIndex", stageIndex); out.put("stageStartedAt", stageStartedAt);
        out.put("initialMaterialized", initialMaterialized); out.put("winnerId", winnerId);
        out.put("pocketSystemId", pocketSystemId); out.put("scopeMoves", scopeMoves); out.put("nextScopeMoveAt", nextScopeMoveAt);
        out.put("discoveredBy", new ArrayList<>(discoveredBy));
        out.put("discoveryProgress", new LinkedHashMap<>(discoveryProgress));
        out.put("progressByPlayer", new LinkedHashMap<>(progressByPlayer));
        out.put("ownedResources", new ArrayList<>(ownedResources)); out.put("ownedItems", new ArrayList<>(ownedItems));
        out.put("ownedUnits", new ArrayList<>(ownedUnits)); out.put("ownedWormholes", new ArrayList<>(ownedWormholes));
        Map<String,Object> roles = new LinkedHashMap<>();
        for (Map.Entry<String,AdvancedUnitRole> entry : unitRoles.entrySet()) roles.put(entry.getKey(), entry.getValue().name());
        out.put("unitRoles", roles); out.put("materializedStages", new ArrayList<>(materializedStages));
        out.put("custom", new LinkedHashMap<>(custom));
        return out;
    }

    static AdvancedEventInstance restore(Object saved) {
        Map<String,Object> row = ServerSaveStore.object(saved);
        String id = ServerSaveStore.string(row, "id", "").trim();
        String definitionId = ServerSaveStore.string(row, "definitionId", "").trim();
        String source = ServerSaveStore.string(row, "sourceSystemId", "").trim();
        if (id.isBlank() || definitionId.isBlank() || source.isBlank()) return null;
        String current = ServerSaveStore.string(row, "currentSystemId", source).trim();
        AdvancedEventInstance event = new AdvancedEventInstance(id, definitionId, source, current,
                ServerSaveStore.doubleValue(row, "x", 0), ServerSaveStore.doubleValue(row, "y", 0),
                ServerSaveStore.enumValue(GalaxyEventPhase.class, row.get("phase"), GalaxyEventPhase.HIDDEN),
                ServerSaveStore.doubleValue(row, "createdAt", 0), ServerSaveStore.doubleValue(row, "expiresAt", 0));
        event.activatedAt = ServerSaveStore.doubleValue(row, "activatedAt", -1);
        event.stageIndex = Math.max(0, ServerSaveStore.intValue(row, "stageIndex", 0));
        event.stageStartedAt = Math.max(0, ServerSaveStore.doubleValue(row, "stageStartedAt", 0));
        event.initialMaterialized = ServerSaveStore.boolValue(row, "initialMaterialized", false);
        event.winnerId = ServerSaveStore.string(row, "winnerId", "").trim();
        event.pocketSystemId = ServerSaveStore.string(row, "pocketSystemId", "").trim();
        event.scopeMoves = Math.max(0, ServerSaveStore.intValue(row, "scopeMoves", 0));
        event.nextScopeMoveAt = ServerSaveStore.doubleValue(row, "nextScopeMoveAt", Double.POSITIVE_INFINITY);
        restoreStrings(row.get("discoveredBy"), event.discoveredBy);
        restoreDoubleMap(row.get("discoveryProgress"), event.discoveryProgress);
        restoreDoubleMap(row.get("progressByPlayer"), event.progressByPlayer);
        restoreInts(row.get("ownedResources"), event.ownedResources); restoreInts(row.get("ownedItems"), event.ownedItems);
        restoreStrings(row.get("ownedUnits"), event.ownedUnits); restoreStrings(row.get("ownedWormholes"), event.ownedWormholes);
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(row.get("unitRoles")).entrySet()) {
            try { event.unitRoles.put(entry.getKey(), AdvancedUnitRole.valueOf(String.valueOf(entry.getValue()).trim())); }
            catch (RuntimeException ignored) { }
        }
        restoreInts(row.get("materializedStages"), event.materializedStages);
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(row.get("custom")).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) event.custom.put(entry.getKey(), entry.getValue().toString());
        }
        return event;
    }

    private static void restoreStrings(Object saved, Set<String> out) {
        for (Object value : ServerSaveStore.list(saved)) {
            String text = ServerSaveStore.asString(value, "").trim(); if (!text.isBlank()) out.add(text);
        }
    }
    private static void restoreInts(Object saved, Set<Integer> out) {
        for (Object value : ServerSaveStore.list(saved)) if (value instanceof Number n && n.intValue() >= 0) out.add(n.intValue());
    }
    private static void restoreDoubleMap(Object saved, Map<String,Double> out) {
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(saved).entrySet()) {
            double value = ServerSaveStore.asDouble(entry.getValue(), 0); if (Double.isFinite(value) && value >= 0) out.put(entry.getKey(), value);
        }
    }
}

record AdvancedScheduledChain(String definitionId, String systemId, double dueAt, String inheritedWinnerId) {
    Map<String,Object> capture() {
        Map<String,Object> out = new LinkedHashMap<>(); out.put("definitionId", definitionId); out.put("systemId", systemId);
        out.put("dueAt", dueAt); out.put("inheritedWinnerId", inheritedWinnerId); return out;
    }
    static AdvancedScheduledChain restore(Object saved) {
        Map<String,Object> row = ServerSaveStore.object(saved);
        String definition = ServerSaveStore.string(row, "definitionId", "").trim();
        String system = ServerSaveStore.string(row, "systemId", "").trim();
        if (definition.isBlank() || system.isBlank()) return null;
        return new AdvancedScheduledChain(definition, system, ServerSaveStore.doubleValue(row, "dueAt", 0),
                ServerSaveStore.string(row, "inheritedWinnerId", "").trim());
    }
}

record AdvancedEventHistoryEntry(String eventId, String definitionId, String systemId,
                                 GalaxyEventPhase phase, String winnerId, double resolvedAt) {
    Map<String,Object> capture() {
        Map<String,Object> out = new LinkedHashMap<>(); out.put("eventId", eventId); out.put("definitionId", definitionId);
        out.put("systemId", systemId); out.put("phase", phase.name()); out.put("winnerId", winnerId); out.put("resolvedAt", resolvedAt);
        return out;
    }
    static AdvancedEventHistoryEntry restore(Object saved) {
        Map<String,Object> row = ServerSaveStore.object(saved);
        String eventId = ServerSaveStore.string(row, "eventId", "").trim();
        String definitionId = ServerSaveStore.string(row, "definitionId", "").trim();
        String systemId = ServerSaveStore.string(row, "systemId", "").trim();
        if (eventId.isBlank() || definitionId.isBlank() || systemId.isBlank()) return null;
        return new AdvancedEventHistoryEntry(eventId, definitionId, systemId,
                ServerSaveStore.enumValue(GalaxyEventPhase.class, row.get("phase"), GalaxyEventPhase.EXPIRED),
                ServerSaveStore.string(row, "winnerId", "").trim(), ServerSaveStore.doubleValue(row, "resolvedAt", 0));
    }
}

/** Reflection is isolated here because World deliberately keeps its coordinator private. */
final class EventSystemAccess {
    private EventSystemAccess() { }

    @SuppressWarnings("unchecked")
    static Map<String,WorldSystemState> systems(World world) {
        try {
            Object coordinator = coordinator(world);
            Field field = GalaxyCoordinator.class.getDeclaredField("systems");
            field.setAccessible(true);
            return (Map<String,WorldSystemState>) field.get(coordinator);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not access galaxy systems for event runtime.", ex);
        }
    }

    static WorldSystemState ensureTemporarySystem(World world, String id, String templateId) {
        Map<String,WorldSystemState> systems = systems(world);
        WorldSystemState existing = systems.get(id);
        if (existing != null) return existing;
        try {
            Object coordinator = coordinator(world);
            Method method = GalaxyCoordinator.class.getDeclaredMethod("createSystem", String.class, String.class,
                    StarSystemDefinition.class, SystemLifetime.class, String.class);
            method.setAccessible(true);
            return (WorldSystemState) method.invoke(coordinator, id, templateId, StarSystems.get(templateId),
                    SystemLifetime.TEMPORARY, "");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not create temporary event system " + id + ".", ex);
        }
    }

    static void removeTemporarySystem(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank() || systemId.equals(world.activeSystemId())) return;
        WorldSystemState state = systems(world).get(systemId);
        if (state != null && state.lifetime == SystemLifetime.TEMPORARY && !hasHumanAssets(world, systemId)) {
            systems(world).remove(systemId);
            for (WorldSystemState other : systems(world).values()) other.wormholes.removeIf(g -> systemId.equals(g.toSystemId));
        }
    }

    static int systemWidth(World world, String systemId) {
        WorldSystemState state = systems(world).get(systemId); return state == null ? world.width : state.width();
    }
    static int systemHeight(World world, String systemId) {
        WorldSystemState state = systems(world).get(systemId); return state == null ? world.height : state.height();
    }

    static void ensureGate(World world, String systemId, WormholeGate gate) {
        if (world == null || gate == null || systemId == null || systemId.isBlank()) return;
        if (systemId.equals(world.activeSystemId())) {
            for (WormholeGate existing : world.wormholes) if (gate.id.equals(existing.id)) return;
            world.wormholes.add(gate);
        } else {
            WorldSystemState state = systems(world).get(systemId);
            if (state == null) return;
            for (WormholeGate existing : state.wormholes) if (gate.id.equals(existing.id)) return;
            state.wormholes.add(gate);
        }
    }

    static void removeGate(World world, String systemId, String gateId) {
        if (systemId.equals(world.activeSystemId())) world.wormholes.removeIf(g -> gateId.equals(g.id));
        WorldSystemState state = systems(world).get(systemId);
        if (state != null) state.wormholes.removeIf(g -> gateId.equals(g.id));
    }

    static boolean hasHumanAssets(World world, String systemId) {
        WorldSystemState state = systems(world).get(systemId);
        if (state == null) return false;
        for (Unit unit : state.units.values()) if (unit.hp > 0 && human(unit.playerId)) return true;
        for (Base base : state.bases.values()) if (base.hp > 0 && human(base.playerId)) return true;
        if (systemId.equals(world.activeSystemId())) {
            for (Unit unit : world.units.values()) if (unit.hp > 0 && human(unit.playerId)) return true;
            for (Base base : world.bases.values()) if (base.hp > 0 && human(base.playerId)) return true;
        }
        return false;
    }

    static boolean unitExists(World world, String key) {
        if (world.units.containsKey(key)) return true;
        for (WorldSystemState state : systems(world).values()) if (state.units.containsKey(key)) return true;
        return false;
    }

    static int remainingItems(World world, Set<Integer> ids) {
        int count = 0;
        Set<Integer> present = new LinkedHashSet<>();
        for (WorldItem item : world.items) if (!item.empty()) present.add(item.id);
        for (WorldSystemState state : systems(world).values()) for (WorldItem item : state.items) if (!item.empty()) present.add(item.id);
        for (Integer id : ids) if (present.contains(id)) count++;
        return count;
    }

    static int remainingResources(World world, Set<Integer> ids) {
        int count = 0;
        Set<Integer> present = new LinkedHashSet<>();
        for (ResourceNode node : world.resources) if (node.amount > 0.05) present.add(node.id);
        for (WorldSystemState state : systems(world).values()) for (ResourceNode node : state.resources) if (node.amount > 0.05) present.add(node.id);
        for (Integer id : ids) if (present.contains(id)) count++;
        return count;
    }

    static void removeOwned(World world, Set<Integer> resources, Set<Integer> items,
                            Set<String> units, Set<String> wormholes) {
        world.resources.removeIf(node -> resources.contains(node.id));
        world.items.removeIf(item -> items.contains(item.id));
        world.units.entrySet().removeIf(entry -> units.contains(entry.getKey()));
        world.wormholes.removeIf(gate -> wormholes.contains(gate.id));
        for (WorldSystemState state : systems(world).values()) {
            state.resources.removeIf(node -> resources.contains(node.id));
            state.items.removeIf(item -> items.contains(item.id));
            state.units.entrySet().removeIf(entry -> units.contains(entry.getKey()));
            state.wormholes.removeIf(gate -> wormholes.contains(gate.id));
        }
    }

    private static Object coordinator(World world) throws ReflectiveOperationException {
        Field field = World.class.getDeclaredField("galaxy"); field.setAccessible(true); return field.get(world);
    }

    private static boolean human(String ownerId) {
        return ownerId != null && !ownerId.isBlank() && !"WAIT".equals(ownerId)
                && !ownerId.startsWith("EVENT_") && !NpcRules.isNpcFaction(ownerId);
    }
}
