package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class GalaxyEventPersistenceValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private GalaxyEventPersistenceValidator() { }

    public static void main(String[] args) throws Exception {
        validateLifecyclePhaseRoundTrips();
        validateMaterializedEntitiesRoundTrip();
        validateSalvageEntitiesRoundTrip();
        validateNpcEntitiesRoundTrip();
        validateRewardRoundTripAndReplayProtection();
        validateClosingWormholeDrainRoundTrip();
        validatePrunedSourceAndTargetCleanup();
        validateOperatorPolicyRoundTrip();
        System.out.println("StarChem galaxy event persistence validation passed.");
    }

    private static void validateLifecyclePhaseRoundTrips() throws Exception {
        for (GalaxyEventPhase phase : GalaxyEventPhase.values()) {
            Path dir = tempDir("phase-" + phase.name().toLowerCase(java.util.Locale.ROOT));
            try {
                Config config = config(dir, "events-phase-" + phase.name().toLowerCase(java.util.Locale.ROOT));
                World world = world(1_296_900L + phase.ordinal());
                String systemId = world.activeSystemId();
                Map<String,Object> row = event("EV-SAVE-PHASE-" + phase.name(), "ion_storm", systemId,
                        world.width * 0.5, world.height * 0.5, 1000, Map.of());
                row.put("phase", phase.name());
                row.put("materialized", phase != GalaxyEventPhase.HIDDEN);
                if (phase != GalaxyEventPhase.HIDDEN) {
                    row.put("activatedAt", 100.0);
                    row.put("discoveredBy", List.of("SOLO"));
                }
                GalaxyEventDirector.restore(world, runtime(systemId, List.of(row)));
                ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
                store.save(world, config, "event-phase-" + phase.name());
                GalaxyEventDirector.clear(world);
                World loaded = requireLoaded(store.load(config), "phase " + phase + " save did not load");
                PlayerRegistry.activate(loaded);
                Map<String,Object> restored = capturedEvent(loaded, "EV-SAVE-PHASE-" + phase.name());
                require(phase.name().equals(ServerSaveStore.string(restored, "phase", "")),
                        "event phase " + phase + " changed across real server save/load");
            } finally {
                deleteTree(dir);
            }
        }
    }

    private static void validateMaterializedEntitiesRoundTrip() throws Exception {
        Path dir = tempDir("entities");
        try {
            Config config = config(dir, "events-entities");
            World world = world(1_297_001L);
            discoverSingle(world, "EV-SAVE-RICH", "rich_rare_earths", Map.of());
            Map<String,Object> before = capturedEvent(world, "EV-SAVE-RICH");
            Set<Integer> ids = ints(before.get("ownedResources"));
            require(ids.size() == 4, "rich event fixture did not materialize four resources");
            Map<Integer,String> fingerprint = resourceFingerprint(world, ids);

            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(world, config, "event-round-trip");
            GalaxyEventDirector.clear(world);
            World loaded = requireLoaded(store.load(config), "materialized event save did not load");
            PlayerRegistry.activate(loaded);

            Map<String,Object> after = capturedEvent(loaded, "EV-SAVE-RICH");
            require(ids.equals(ints(after.get("ownedResources"))),
                    "event-owned resource ids changed across real server save/load");
            require(fingerprint.equals(resourceFingerprint(loaded, ids)),
                    "event-owned resource entities changed across real server save/load");
            require(!ServerSaveStore.object(after.get("entityRoles")).isEmpty(),
                    "event ownership metadata was lost across real server save/load");

            int beforeCount = resourceFingerprint(loaded, ids).size();
            GalaxyEventDirector.update(loaded, 0.25);
            require(resourceFingerprint(loaded, ids).size() == beforeCount,
                    "restored materialized event duplicated resources after its first update");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateSalvageEntitiesRoundTrip() throws Exception {
        Path dir = tempDir("salvage-entities");
        try {
            Config config = config(dir, "events-salvage-entities");
            World world = world(1_297_001_1L);
            discoverSingle(world, "EV-SAVE-SALVAGE", "derelict_convoy", Map.of());
            Map<String,Object> before = capturedEvent(world, "EV-SAVE-SALVAGE");
            Set<Integer> ids = ints(before.get("ownedItems"));
            require(ids.size() == 5, "derelict fixture did not materialize five salvage items");
            Map<Integer,String> fingerprint = itemFingerprint(world, ids);

            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(world, config, "event-salvage-round-trip");
            GalaxyEventDirector.clear(world);
            World loaded = requireLoaded(store.load(config), "salvage event save did not load");
            PlayerRegistry.activate(loaded);
            Map<String,Object> after = capturedEvent(loaded, "EV-SAVE-SALVAGE");
            require(ids.equals(ints(after.get("ownedItems"))),
                    "event-owned salvage ids changed across real server save/load");
            require(fingerprint.equals(itemFingerprint(loaded, ids)),
                    "event-owned salvage entities changed across real server save/load");
            require(allRoles(after, "ITEM:", GalaxyEventEntityRole.SALVAGE),
                    "salvage ownership roles were lost across real server save/load");
            GalaxyEventDirector.update(loaded, 0.25);
            require(ids.equals(ints(capturedEvent(loaded, "EV-SAVE-SALVAGE").get("ownedItems")))
                            && itemFingerprint(loaded, ids).size() == ids.size(),
                    "restored derelict event duplicated or lost salvage on first update");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateNpcEntitiesRoundTrip() throws Exception {
        Path dir = tempDir("npc-entities");
        try {
            Config config = config(dir, "events-npc-entities");
            World world = world(1_297_001_2L);
            discoverSingle(world, "EV-SAVE-DISTRESS", "distress_beacon", Map.of());
            Map<String,Object> before = capturedEvent(world, "EV-SAVE-DISTRESS");
            Set<String> keys = strings(before.get("ownedUnits"));
            require(keys.size() == 4, "distress fixture did not materialize civilian plus three attackers");
            Map<String,String> fingerprint = unitFingerprint(world, keys);
            String civilian = ServerSaveStore.string(ServerSaveStore.object(before.get("custom")),
                    "civilianUnitKey", "");
            require(!civilian.isBlank(), "distress fixture did not persist its civilian identity");

            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(world, config, "event-npc-round-trip");
            GalaxyEventDirector.clear(world);
            World loaded = requireLoaded(store.load(config), "distress event save did not load");
            PlayerRegistry.activate(loaded);
            Map<String,Object> after = capturedEvent(loaded, "EV-SAVE-DISTRESS");
            require(keys.equals(strings(after.get("ownedUnits"))),
                    "event-owned NPC keys changed across real server save/load");
            require(fingerprint.equals(unitFingerprint(loaded, keys)),
                    "event-owned NPC entities changed across real server save/load");
            require(GalaxyEventDirector.unitRole(loaded, civilian) == GalaxyEventEntityRole.CIVILIAN,
                    "distress civilian role was lost across real server save/load");
            int attackers = 0;
            for (String key : keys) {
                if (key.equals(civilian)) continue;
                require(GalaxyEventDirector.unitRole(loaded, key) == GalaxyEventEntityRole.ATTACKER,
                        "distress attacker role was lost across real server save/load: " + key);
                attackers++;
            }
            require(attackers == 3, "distress fixture did not retain three attackers");
            GalaxyEventDirector.update(loaded, 0.25);
            require(strings(capturedEvent(loaded, "EV-SAVE-DISTRESS").get("ownedUnits")).equals(keys)
                            && unitFingerprint(loaded, keys).size() == keys.size(),
                    "restored distress event duplicated or lost NPCs on first update");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateRewardRoundTripAndReplayProtection() throws Exception {
        Path dir = tempDir("reward");
        try {
            Config config = config(dir, "events-reward");
            World world = world(1_297_002L);
            discoverSingle(world, "EV-SAVE-REWARD", "pirate_ambush", Map.of());
            Map<String,Object> active = capturedEvent(world, "EV-SAVE-REWARD");
            for (Object raw : ServerSaveStore.list(active.get("ownedUnits"))) world.units.remove(String.valueOf(raw));
            GalaxyEventDirector.update(world, 0.25);

            Map<String,Object> completed = capturedEvent(world, "EV-SAVE-REWARD");
            require("COMPLETED".equals(ServerSaveStore.string(completed, "phase", "")),
                    "reward fixture did not complete");
            int rewardId = ServerSaveStore.intValue(completed, "rewardItemId", -1);
            String tx = ServerSaveStore.string(completed, "rewardTransactionId", "");
            WorldItem reward = item(world, rewardId);
            require(reward != null && !tx.isBlank(), "reward fixture did not create a physical reward transaction");
            require(GalaxyEventDirector.claimItemForPickup(world, rewardId, "SOLO"),
                    "reward fixture could not establish its authoritative claimant");
            reward.take(reward.amount / 2.0);
            double remaining = reward.amount;

            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(world, config, "event-reward-pending");
            GalaxyEventDirector.clear(world);
            World loaded = requireLoaded(store.load(config), "pending reward save did not load");
            PlayerRegistry.activate(loaded);
            Map<String,Object> restored = capturedEvent(loaded, "EV-SAVE-REWARD");
            require(tx.equals(ServerSaveStore.string(restored, "rewardTransactionId", "")),
                    "reward transaction id changed across real server save/load");
            require("SOLO".equals(ServerSaveStore.string(restored, "rewardClaimantId", "")),
                    "reward claimant was lost across real server save/load");
            WorldItem restoredReward = item(loaded, rewardId);
            require(restoredReward != null && Math.abs(restoredReward.amount - remaining) < 0.000001,
                    "partially claimed reward amount changed across real server save/load");
            require(countItem(loaded, rewardId) == 1, "reward was duplicated by real server save/load");

            Unit claimant = firstUnit(loaded, "SOLO");
            require(claimant != null, "reward fixture lost the claiming player ship");
            double taken = restoredReward.take(restoredReward.amount);
            GalaxyEventDirector.onItemPickup(loaded, restoredReward, claimant, taken);
            Map<String,Object> claimed = capturedEvent(loaded, "EV-SAVE-REWARD");
            require(ServerSaveStore.boolValue(claimed, "rewardClaimed", false),
                    "fully collected reward was not marked claimed");

            store.save(loaded, config, "event-reward-claimed");
            GalaxyEventDirector.clear(loaded);
            World reloaded = requireLoaded(store.load(config), "claimed reward save did not load");
            PlayerRegistry.activate(reloaded);
            Map<String,Object> claimedReload = capturedEvent(reloaded, "EV-SAVE-REWARD");
            require(ServerSaveStore.boolValue(claimedReload, "rewardClaimed", false),
                    "claimed reward state was lost across restart");
            GalaxyEventDirector.update(reloaded, 0.25);
            require(capturedEvent(reloaded, "EV-SAVE-REWARD").isEmpty(),
                    "settled reward event did not retire after restart");
            require(countItem(reloaded, rewardId) == 0,
                    "claimed reward replayed after event retirement/restart");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validateClosingWormholeDrainRoundTrip() throws Exception {
        Path dir = tempDir("wormhole");
        try {
            Config config = config(dir, "events-wormhole");
            World world = world(1_297_003L);
            String source = world.activeSystemId();
            String target = otherSystem(world, source);
            double x = world.width * 0.5;
            double y = world.height * 0.5;
            Unit committed = new Unit("SOLO", 811, Rules.STARTING_SHIP, x, y);
            world.units.put(committed.key(), committed);
            Map<String,Object> event = event("EV-SAVE-WORM", "unstable_wormhole", source, x, y, 100.05,
                    Map.of("targetSystemId", target, "targetX", "850", "targetY", "700"));
            event.put("phase", GalaxyEventPhase.ACTIVE.name());
            event.put("activatedAt", 100.0);
            event.put("materialized", true);
            event.put("discoveredBy", List.of("SOLO"));
            event.put("ownedWormholes", List.of("EV-SAVE-WORM:A", "EV-SAVE-WORM:B"));
            GalaxyEventDirector.restore(world, runtime(source, List.of(event)));
            GalaxyEventDirector.update(world, 0.1);
            Map<String,Object> closing = capturedEvent(world, "EV-SAVE-WORM");
            require("CLOSING".equals(ServerSaveStore.string(closing, "phase", "")),
                    "wormhole fixture did not enter CLOSING");
            require(strings(closing.get("drainingUnits")).contains(committed.key()),
                    "touching ship was not captured in the closing drain set");

            ServerSaveStore store = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
            store.save(world, config, "event-wormhole-closing");
            GalaxyEventDirector.clear(world);
            World loaded = requireLoaded(store.load(config), "closing wormhole save did not load");
            PlayerRegistry.activate(loaded);
            Map<String,Object> restored = capturedEvent(loaded, "EV-SAVE-WORM");
            require("CLOSING".equals(ServerSaveStore.string(restored, "phase", "")),
                    "closing wormhole phase was lost across real server save/load");
            require(strings(restored.get("drainingUnits")).contains(committed.key()),
                    "closing wormhole drain reservation was lost across real server save/load");

            GalaxyEventDirector.update(loaded, 0.01);
            WormholeGate gate = gateById(loaded, "EV-SAVE-WORM:A");
            Unit restoredShip = loaded.units.get(committed.key());
            require(gate != null && restoredShip != null,
                    "closing wormhole gate/ship did not reconstruct after real server save/load");
            require(gate.containsForTransit(loaded, restoredShip),
                    "restored committed ship was not allowed to drain through closing wormhole");
            require(countGate(loaded, "EV-SAVE-WORM:A") == 1,
                    "closing wormhole gate duplicated after restart reconstruction");
        } finally {
            deleteTree(dir);
        }
    }

    private static void validatePrunedSourceAndTargetCleanup() {
        World sourceWorld = world(1_297_004L);
        PlayerRegistry.register("P2", "Prune Player", 0xFFAA55, false);
        sourceWorld.ensurePlayerHome("P2");
        String home = sourceWorld.playerHomeSystemId("P2");
        sourceWorld.activateSystem(home);
        discoverSingleAtActive(sourceWorld, "EV-PRUNE-SOURCE", "rich_rare_earths", Map.of());
        require(!capturedEvent(sourceWorld, "EV-PRUNE-SOURCE").isEmpty(), "source-prune fixture missing event");
        sourceWorld.units.entrySet().removeIf(entry -> "SOLO".equals(entry.getValue().playerId));
        sourceWorld.saveActiveSystem();
        sourceWorld.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
        Set<String> removed = sourceWorld.removePlayerAndPruneEmptySystems("P2");
        require(removed.contains(home), "dynamic event source system was not pruned");
        require(capturedEvent(sourceWorld, "EV-PRUNE-SOURCE").isEmpty(),
                "event state survived pruning of its source system");

        World targetWorld = world(1_297_005L);
        PlayerRegistry.register("P2", "Prune Target", 0xFFAA55, false);
        targetWorld.ensurePlayerHome("P2");
        String targetHome = targetWorld.playerHomeSystemId("P2");
        String source = targetWorld.activeSystemId();
        double x = targetWorld.width * 0.5;
        double y = targetWorld.height * 0.5;
        Map<String,Object> wormhole = event("EV-PRUNE-TARGET", "unstable_wormhole", source, x, y, 1000,
                Map.of("targetSystemId", targetHome, "targetX", "700", "targetY", "700"));
        wormhole.put("phase", GalaxyEventPhase.ACTIVE.name());
        wormhole.put("activatedAt", 100.0);
        wormhole.put("materialized", true);
        wormhole.put("discoveredBy", List.of("SOLO"));
        wormhole.put("ownedWormholes", List.of("EV-PRUNE-TARGET:A", "EV-PRUNE-TARGET:B"));
        GalaxyEventDirector.restore(targetWorld, runtime(source, List.of(wormhole)));
        GalaxyEventDirector.update(targetWorld, 0.1);
        require(gateById(targetWorld, "EV-PRUNE-TARGET:A") != null, "target-prune fixture missing source gate");
        Set<String> targetRemoved = targetWorld.removePlayerAndPruneEmptySystems("P2");
        require(targetRemoved.contains(targetHome), "dynamic wormhole target system was not pruned");
        require(capturedEvent(targetWorld, "EV-PRUNE-TARGET").isEmpty(),
                "temporary wormhole event survived pruning of its target system");
        require(gateById(targetWorld, "EV-PRUNE-TARGET:A") == null,
                "temporary source gate survived target-system pruning");
    }

    private static void validateOperatorPolicyRoundTrip() throws Exception {
        Config parsed = Config.parse(new String[]{"--server", "34567", "--disable-events", "--event-frequency", "2.5",
                "--event-categories", "RICH_RESOURCE,UNSTABLE_WORMHOLE", "--new-world"});
        require(!parsed.galaxyEventsEnabled, "--disable-events was not parsed");
        require(Math.abs(parsed.galaxyEventFrequency - 2.5) < 0.000001, "--event-frequency was not parsed");
        require(parsed.galaxyEventCategories.equals(Set.of(GalaxyEventKind.RICH_RESOURCE, GalaxyEventKind.UNSTABLE_WORMHOLE)),
                "--event-categories was not parsed");
        requireThrows(() -> Config.parse(new String[]{"--server", "34567", "--event-frequency", "5"}),
                "out-of-range event frequency was accepted");
        requireThrows(() -> Config.parse(new String[]{"--server", "34567", "--event-categories", "NOT_A_CATEGORY"}),
                "unknown event category was accepted");

        Path dir = tempDir("policy");
        try {
            Config saveConfig = config(dir, "events-policy");
            World world = world(1_297_006L);
            Config policyConfig = Config.parse(new String[]{"--solo", "--disable-events", "--event-frequency", "3.0",
                    "--event-categories", "PIRATE_AMBUSH"});
            GalaxyEventDirector.configurePolicy(world, policyConfig, false);
            ServerSaveStore store = new ServerSaveStore(saveConfig.saveDir, saveConfig.saveName, saveConfig.backupCount);
            store.save(world, saveConfig, "event-policy");
            GalaxyEventDirector.clear(world);
            World loaded = requireLoaded(store.load(saveConfig), "event policy save did not load");
            GalaxyEventPolicy policy = GalaxyEventDirector.policy(loaded);
            require(!policy.enabled() && Math.abs(policy.frequencyMultiplier() - 3.0) < 0.000001,
                    "event operator policy did not survive server save/load");
            require(policy.enabledCategories().equals(Set.of(GalaxyEventKind.PIRATE_AMBUSH)),
                    "event category allow-list did not survive server save/load");
        } finally {
            deleteTree(dir);
        }
    }

    private static World world(long seed) {
        World world = new World("Event Persistence Validator", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Event Persistence Validator", 0x50BEFF);
        world.useSystemSeed(seed);
        world.activateSystem(StarSystems.DEFAULT_SYSTEM_ID);
        return world;
    }

    private static void discoverSingle(World world, String id, String definitionId, Map<String,String> custom) {
        discoverSingleAtActive(world, id, definitionId, custom);
    }

    private static void discoverSingleAtActive(World world, String id, String definitionId, Map<String,String> custom) {
        PlayerRegistry.activate(world);
        String systemId = world.activeSystemId();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        Unit scout = new Unit("SOLO", nextUnitId(world, "SOLO"), Rules.STARTING_SHIP, x, y);
        world.units.put(scout.key(), scout);
        GalaxyEventDirector.restore(world, runtime(systemId,
                List.of(event(id, definitionId, systemId, x, y, 1000, custom))));
        GalaxyEventDirector.update(world, 0.25);
    }

    private static int nextUnitId(World world, String owner) {
        int next = 1;
        for (Unit unit : world.units.values()) if (owner.equals(unit.playerId)) next = Math.max(next, unit.unitId + 1);
        return next;
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
        row.put("createdAt", 100.0);
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

    private static Map<String,Object> capturedEvent(World world, String eventId) {
        for (Object item : ServerSaveStore.list(GalaxyEventDirector.capture(world).get("events"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            if (eventId.equals(ServerSaveStore.string(row, "id", ""))) return row;
        }
        return Map.of();
    }

    private static Set<Integer> ints(Object saved) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Object raw : ServerSaveStore.list(saved)) if (raw instanceof Number number) out.add(number.intValue());
        return out;
    }

    private static Set<String> strings(Object saved) {
        Set<String> out = new LinkedHashSet<>();
        for (Object raw : ServerSaveStore.list(saved)) {
            String value = String.valueOf(raw).trim();
            if (!value.isBlank()) out.add(value);
        }
        return out;
    }

    private static Map<Integer,String> resourceFingerprint(World world, Set<Integer> ids) {
        Map<Integer,String> out = new LinkedHashMap<>();
        for (ResourceNode node : world.resources) {
            if (!ids.contains(node.id)) continue;
            out.put(node.id, node.material + "|" + Double.toString(node.amount) + "|"
                    + Double.toString(node.x) + "|" + Double.toString(node.y));
        }
        return Map.copyOf(out);
    }

    private static Map<Integer,String> itemFingerprint(World world, Set<Integer> ids) {
        Map<Integer,String> out = new LinkedHashMap<>();
        for (WorldItem item : world.items) {
            if (!ids.contains(item.id) || item.empty()) continue;
            out.put(item.id, item.material + "|" + Double.toString(item.amount) + "|"
                    + Double.toString(item.x) + "|" + Double.toString(item.y));
        }
        return Map.copyOf(out);
    }

    private static Map<String,String> unitFingerprint(World world, Set<String> keys) {
        Map<String,String> out = new LinkedHashMap<>();
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            out.put(key, unit.playerId + "|" + unit.shipTypeId + "|" + Double.toString(unit.hp) + "|"
                    + Double.toString(unit.x) + "|" + Double.toString(unit.y));
        }
        return Map.copyOf(out);
    }

    private static boolean allRoles(Map<String,Object> event, String prefix, GalaxyEventEntityRole expected) {
        Map<String,Object> roles = ServerSaveStore.object(event.get("entityRoles"));
        int matched = 0;
        for (Map.Entry<String,Object> entry : roles.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            if (!expected.name().equals(String.valueOf(entry.getValue()))) return false;
            matched++;
        }
        return matched > 0;
    }

    private static WorldItem item(World world, int id) {
        for (WorldItem item : world.items) if (item.id == id) return item;
        return null;
    }

    private static int countItem(World world, int id) {
        int count = 0;
        for (WorldItem item : world.items) if (item.id == id && !item.empty()) count++;
        return count;
    }

    private static Unit firstUnit(World world, String owner) {
        for (Unit unit : world.units.values()) if (owner.equals(unit.playerId) && unit.hp > 0) return unit;
        return null;
    }

    private static WormholeGate gateById(World world, String id) {
        for (WormholeGate gate : world.wormholes) if (gate != null && id.equals(gate.id)) return gate;
        return null;
    }

    private static int countGate(World world, String id) {
        int count = 0;
        for (WormholeGate gate : world.wormholes) if (gate != null && id.equals(gate.id)) count++;
        return count;
    }

    private static String otherSystem(World world, String source) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && !source.equals(system.id())) return system.id();
        }
        throw new IllegalStateException("validation requires at least two systems");
    }

    private static Config config(Path dir, String name) {
        return Config.dedicatedServer("Event Persistence Validator", 34567, false, false, NO_NPCS,
                StarSystems.DEFAULT_SYSTEM_ID, "", 1, dir, name, 60, 3, false);
    }

    private static World requireLoaded(Optional<World> loaded, String message) {
        if (loaded.isEmpty()) throw new IllegalStateException(message);
        return loaded.get();
    }

    private static Path tempDir(String name) throws IOException {
        Files.createDirectories(Path.of("build"));
        return Files.createTempDirectory(Path.of("build"), "galaxy-events-" + name + "-");
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void requireThrows(Runnable runnable, String message) {
        boolean rejected = false;
        try { runnable.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
