package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-authoritative persistent player/NPC fleet identity and strategic command layer.
 *
 * Fleet identity owns membership and strategic intent only. Physical location remains
 * authoritative at the per-unit galaxy layer, so a fleet can naturally span multiple
 * systems while crossing wormholes without being duplicated or teleported as a group.
 */
enum FleetOrderType {
    NONE,
    MOVE_TO_SYSTEM,
    RALLY,
    PATROL,
    DEFEND,
    GUARD_WORMHOLE,
    ESCORT,
    RAID,
    INVADE,
    REPAIR_REFIT,
    RESUPPLY,
    RETREAT_REGROUP
}

enum FleetOrderState { IDLE, EXECUTING, COMPLETE, BLOCKED }

enum FleetMutationResult { APPLIED, NOT_FOUND, FORBIDDEN, INVALID, CONFLICT, LIMIT }

record FleetStrategicOrder(long orderId, FleetOrderType type, FleetOrderState state,
                           String destinationSystemId, String targetKey, String detail) {
    static final FleetStrategicOrder NONE = new FleetStrategicOrder(
            0, FleetOrderType.NONE, FleetOrderState.IDLE, "", "", "");

    FleetStrategicOrder {
        orderId = Math.max(0, orderId);
        if (type == null) type = FleetOrderType.NONE;
        if (state == null) state = FleetOrderState.IDLE;
        destinationSystemId = clean(destinationSystemId, 128);
        targetKey = clean(targetKey, 256);
        detail = clean(detail, 512);
    }

    FleetStrategicOrder withState(FleetOrderState next, String nextDetail) {
        return new FleetStrategicOrder(orderId, type, next, destinationSystemId, targetKey, nextDetail);
    }

    private static String clean(String value, int max) {
        String out = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return out.length() <= max ? out : out.substring(0, max);
    }
}

record FleetView(long fleetId, String ownerId, String name, Set<String> memberKeys,
                 FleetFormation formation, CombatStance combatStance,
                 TargetPriorityPolicy targetPriority, String homeBaseId,
                 String rallySystemId, double rallyX, double rallyY,
                 FleetStrategicOrder order, long revision,
                 Map<String,Integer> shipsBySystem) {
    FleetView {
        ownerId = ownerId == null ? "" : ownerId;
        name = name == null ? "" : name;
        memberKeys = memberKeys == null ? Set.of() : Set.copyOf(memberKeys);
        if (formation == null) formation = FleetFormation.GRID;
        if (combatStance == null) combatStance = CombatStance.AGGRESSIVE;
        if (targetPriority == null) targetPriority = TargetPriorityPolicy.NEAREST_THREAT;
        homeBaseId = homeBaseId == null ? "" : homeBaseId;
        rallySystemId = rallySystemId == null ? "" : rallySystemId;
        order = order == null ? FleetStrategicOrder.NONE : order;
        revision = Math.max(0, revision);
        shipsBySystem = shipsBySystem == null ? Map.of() : Map.copyOf(shipsBySystem);
    }

    int livingShips() {
        int count = 0;
        for (int value : shipsBySystem.values()) count += Math.max(0, value);
        return count;
    }

    boolean splitAcrossSystems() { return shipsBySystem.size() > 1; }
}

record FleetCreateResult(FleetMutationResult result, long fleetId) {
    static FleetCreateResult failed(FleetMutationResult result) {
        return new FleetCreateResult(result, 0);
    }
}

record FleetCommandResult(boolean applied, long orderId, String message) {
    static FleetCommandResult rejected(String message) {
        return new FleetCommandResult(false, 0, message == null ? "Fleet command rejected." : message);
    }
}

final class FleetManager {
    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_FLEETS_PER_OWNER = 128;
    static final int MAX_MEMBERS_PER_FLEET = 512;

    private static final Map<World, RuntimeState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FleetManager() { }

    static synchronized FleetCreateResult create(World world, String ownerId, String name,
                                                  Collection<String> memberKeys) {
        String owner = cleanOwner(ownerId);
        String fleetName = cleanName(name);
        LinkedHashSet<String> members = cleanMembers(memberKeys);
        if (world == null || owner.isBlank() || fleetName.isBlank() || members.isEmpty()) {
            return FleetCreateResult.failed(FleetMutationResult.INVALID);
        }
        RuntimeState state = state(world);
        if (countOwnerFleets(state, owner) >= MAX_FLEETS_PER_OWNER
                || members.size() > MAX_MEMBERS_PER_FLEET) {
            return FleetCreateResult.failed(FleetMutationResult.LIMIT);
        }
        Map<String,String> live = FleetWire.ownerLocations(world, owner);
        for (String key : members) {
            if (!ownedLiveKey(owner, key, live)) return FleetCreateResult.failed(FleetMutationResult.FORBIDDEN);
            if (state.fleetByUnit.containsKey(key)) return FleetCreateResult.failed(FleetMutationResult.CONFLICT);
        }

        long id = Math.max(1, state.nextFleetId++);
        FleetData fleet = new FleetData(id, owner, fleetName);
        fleet.members.addAll(members);
        state.fleets.put(id, fleet);
        for (String key : members) state.fleetByUnit.put(key, id);
        StrategicSummaryService.invalidate(world);
        return new FleetCreateResult(FleetMutationResult.APPLIED, id);
    }

    static synchronized FleetMutationResult rename(World world, String ownerId, long fleetId,
                                                    long expectedRevision, String name) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        String clean = cleanName(name);
        if (clean.isBlank()) return FleetMutationResult.INVALID;
        if (clean.equals(fleet.name)) return FleetMutationResult.APPLIED;
        fleet.name = clean;
        fleet.revision++;
        StrategicSummaryService.invalidate(world);
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetMutationResult disband(World world, String ownerId, long fleetId,
                                                     long expectedRevision) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        RuntimeState state = state(world);
        state.fleets.remove(fleetId);
        for (String key : fleet.members) state.fleetByUnit.remove(key, fleetId);
        StrategicSummaryService.invalidate(world);
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetMutationResult addMembers(World world, String ownerId, long fleetId,
                                                        long expectedRevision,
                                                        Collection<String> memberKeys) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        LinkedHashSet<String> additions = cleanMembers(memberKeys);
        if (additions.isEmpty()) return FleetMutationResult.INVALID;
        if (fleet.members.size() + additions.size() > MAX_MEMBERS_PER_FLEET) return FleetMutationResult.LIMIT;
        RuntimeState state = state(world);
        Map<String,String> live = FleetWire.ownerLocations(world, fleet.ownerId);
        for (String key : additions) {
            if (!ownedLiveKey(fleet.ownerId, key, live)) return FleetMutationResult.FORBIDDEN;
            Long assigned = state.fleetByUnit.get(key);
            if (assigned != null && assigned != fleetId) return FleetMutationResult.CONFLICT;
        }
        boolean changed = false;
        for (String key : additions) {
            if (fleet.members.add(key)) {
                state.fleetByUnit.put(key, fleetId);
                changed = true;
            }
        }
        if (changed) {
            fleet.revision++;
            StrategicSummaryService.invalidate(world);
        }
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetMutationResult removeMembers(World world, String ownerId, long fleetId,
                                                           long expectedRevision,
                                                           Collection<String> memberKeys) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        LinkedHashSet<String> removals = cleanMembers(memberKeys);
        if (removals.isEmpty()) return FleetMutationResult.INVALID;
        RuntimeState state = state(world);
        boolean changed = false;
        for (String key : removals) {
            if (fleet.members.remove(key)) {
                state.fleetByUnit.remove(key, fleetId);
                changed = true;
            }
        }
        if (changed) {
            fleet.revision++;
            reconcileOrder(world, fleet);
            StrategicSummaryService.invalidate(world);
        }
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetMutationResult merge(World world, String ownerId, long destinationFleetId,
                                                   long sourceFleetId, long destinationRevision,
                                                   long sourceRevision) {
        if (destinationFleetId == sourceFleetId) return FleetMutationResult.INVALID;
        FleetData destination = owned(world, ownerId, destinationFleetId);
        FleetData source = owned(world, ownerId, sourceFleetId);
        if (destination == null || source == null) {
            if (state(world).fleets.containsKey(destinationFleetId) || state(world).fleets.containsKey(sourceFleetId)) {
                return FleetMutationResult.FORBIDDEN;
            }
            return FleetMutationResult.NOT_FOUND;
        }
        if (!revisionMatches(destination, destinationRevision) || !revisionMatches(source, sourceRevision)) {
            return FleetMutationResult.CONFLICT;
        }
        if (destination.members.size() + source.members.size() > MAX_MEMBERS_PER_FLEET) return FleetMutationResult.LIMIT;
        RuntimeState state = state(world);
        destination.members.addAll(source.members);
        for (String key : source.members) state.fleetByUnit.put(key, destinationFleetId);
        state.fleets.remove(sourceFleetId);
        destination.revision++;
        StrategicSummaryService.invalidate(world);
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetCreateResult split(World world, String ownerId, long sourceFleetId,
                                                 long expectedRevision, String newName,
                                                 Collection<String> memberKeys) {
        FleetData source = owned(world, ownerId, sourceFleetId);
        if (source == null) return FleetCreateResult.failed(existenceResult(world, ownerId, sourceFleetId));
        if (!revisionMatches(source, expectedRevision)) return FleetCreateResult.failed(FleetMutationResult.CONFLICT);
        LinkedHashSet<String> moving = cleanMembers(memberKeys);
        if (moving.isEmpty() || moving.size() >= source.members.size() || !source.members.containsAll(moving)) {
            return FleetCreateResult.failed(FleetMutationResult.INVALID);
        }
        RuntimeState state = state(world);
        if (countOwnerFleets(state, source.ownerId) >= MAX_FLEETS_PER_OWNER) {
            return FleetCreateResult.failed(FleetMutationResult.LIMIT);
        }
        String cleanName = cleanName(newName);
        if (cleanName.isBlank()) return FleetCreateResult.failed(FleetMutationResult.INVALID);
        long id = Math.max(1, state.nextFleetId++);
        FleetData created = new FleetData(id, source.ownerId, cleanName);
        created.formation = source.formation;
        created.combatStance = source.combatStance;
        created.targetPriority = source.targetPriority;
        created.homeBaseId = source.homeBaseId;
        created.rallySystemId = source.rallySystemId;
        created.rallyX = source.rallyX;
        created.rallyY = source.rallyY;
        for (String key : moving) {
            source.members.remove(key);
            created.members.add(key);
            state.fleetByUnit.put(key, id);
        }
        source.revision++;
        state.fleets.put(id, created);
        reconcileOrder(world, source);
        StrategicSummaryService.invalidate(world);
        return new FleetCreateResult(FleetMutationResult.APPLIED, id);
    }

    static synchronized FleetMutationResult setFormation(World world, String ownerId, long fleetId,
                                                          long expectedRevision,
                                                          FleetFormation formation) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        if (formation == null) return FleetMutationResult.INVALID;
        fleet.formation = formation;
        fleet.revision++;
        StrategicSummaryService.invalidate(world);
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetMutationResult setHomeAndRally(World world, String ownerId, long fleetId,
                                                             long expectedRevision, String homeBaseId,
                                                             String rallySystemId,
                                                             double rallyX, double rallyY) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        if (!finite(rallyX, rallyY)) return FleetMutationResult.INVALID;
        fleet.homeBaseId = clean(homeBaseId, 128);
        fleet.rallySystemId = clean(rallySystemId, 128);
        fleet.rallyX = rallyX;
        fleet.rallyY = rallyY;
        fleet.revision++;
        StrategicSummaryService.invalidate(world);
        return FleetMutationResult.APPLIED;
    }

    static synchronized FleetMutationResult setPolicies(World world, String ownerId, long fleetId,
                                                         long expectedRevision, CombatStance stance,
                                                         TargetPriorityPolicy priority) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return existenceResult(world, ownerId, fleetId);
        if (!revisionMatches(fleet, expectedRevision)) return FleetMutationResult.CONFLICT;
        if (stance == null || priority == null) return FleetMutationResult.INVALID;
        fleet.combatStance = stance;
        fleet.targetPriority = priority;
        fleet.revision++;
        StrategicSummaryService.invalidate(world);
        return FleetMutationResult.APPLIED;
    }

    static synchronized Optional<FleetView> view(World world, String ownerId, long fleetId) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null) return Optional.empty();
        reconcileMembers(world, fleet);
        reconcileOrder(world, fleet);
        return Optional.of(toView(world, fleet));
    }

    static synchronized List<FleetView> viewsForOwner(World world, String ownerId) {
        String owner = cleanOwner(ownerId);
        if (world == null || owner.isBlank()) return List.of();
        RuntimeState state = state(world);
        List<FleetView> out = new ArrayList<>();
        for (FleetData fleet : state.fleets.values()) {
            if (!owner.equals(fleet.ownerId)) continue;
            reconcileMembers(world, fleet);
            reconcileOrder(world, fleet);
            out.add(toView(world, fleet));
        }
        out.sort((a, b) -> Long.compare(a.fleetId(), b.fleetId()));
        return List.copyOf(out);
    }

    static synchronized long fleetIdForUnit(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return 0;
        return state(world).fleetByUnit.getOrDefault(unitKey, 0L);
    }

    static synchronized void removeUnit(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return;
        RuntimeState state = state(world);
        Long fleetId = state.fleetByUnit.remove(unitKey);
        if (fleetId == null) return;
        FleetData fleet = state.fleets.get(fleetId);
        if (fleet == null || !fleet.members.remove(unitKey)) return;
        fleet.revision++;
        reconcileOrder(world, fleet);
        StrategicSummaryService.invalidate(world);
    }

    static synchronized void removeOwner(World world, String ownerId) {
        if (world == null) return;
        String owner = cleanOwner(ownerId);
        if (owner.isBlank()) return;
        RuntimeState state = state(world);
        List<Long> remove = new ArrayList<>();
        for (FleetData fleet : state.fleets.values()) if (owner.equals(fleet.ownerId)) remove.add(fleet.fleetId);
        for (long id : remove) {
            FleetData fleet = state.fleets.remove(id);
            if (fleet != null) for (String key : fleet.members) state.fleetByUnit.remove(key, id);
        }
        StrategicSummaryService.invalidate(world);
    }

    static synchronized void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (world == null) return out;
        RuntimeState state = state(world);
        for (FleetData fleet : new ArrayList<>(state.fleets.values())) {
            reconcileMembers(world, fleet);
            reconcileOrder(world, fleet);
        }
        out.put("nextFleetId", state.nextFleetId);
        out.put("nextOrderId", state.nextOrderId);
        List<Object> rows = new ArrayList<>();
        for (FleetData fleet : state.fleets.values()) rows.add(captureFleet(fleet));
        out.put("fleets", rows);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        RuntimeState restored = new RuntimeState();
        restored.nextFleetId = Math.max(1, ServerSaveStore.longValue(data, "nextFleetId", 1));
        restored.nextOrderId = Math.max(1, ServerSaveStore.longValue(data, "nextOrderId", 1));
        Map<String,Map<String,String>> liveByOwner = new HashMap<>();
        for (Object raw : ServerSaveStore.list(data.get("fleets"))) {
            Map<String,Object> row = ServerSaveStore.object(raw);
            long fleetId = Math.max(0, ServerSaveStore.longValue(row, "fleetId", 0));
            String owner = cleanOwner(ServerSaveStore.string(row, "ownerId", ""));
            String name = cleanName(ServerSaveStore.string(row, "name", ""));
            if (fleetId <= 0 || owner.isBlank() || name.isBlank() || restored.fleets.containsKey(fleetId)) continue;
            if (countOwnerFleets(restored, owner) >= MAX_FLEETS_PER_OWNER) continue;
            FleetData fleet = new FleetData(fleetId, owner, name);
            fleet.revision = Math.max(0, ServerSaveStore.longValue(row, "revision", 0));
            fleet.formation = ServerSaveStore.enumValue(FleetFormation.class, row.get("formation"), FleetFormation.GRID);
            fleet.combatStance = ServerSaveStore.enumValue(CombatStance.class, row.get("combatStance"), CombatStance.AGGRESSIVE);
            fleet.targetPriority = ServerSaveStore.enumValue(TargetPriorityPolicy.class, row.get("targetPriority"), TargetPriorityPolicy.NEAREST_THREAT);
            fleet.homeBaseId = clean(ServerSaveStore.string(row, "homeBaseId", ""), 128);
            fleet.rallySystemId = clean(ServerSaveStore.string(row, "rallySystemId", ""), 128);
            fleet.rallyX = finiteOrZero(ServerSaveStore.doubleValue(row, "rallyX", 0));
            fleet.rallyY = finiteOrZero(ServerSaveStore.doubleValue(row, "rallyY", 0));
            fleet.order = restoreOrder(row.get("order"));
            Map<String,String> live = liveByOwner.computeIfAbsent(owner, world::ownerUnitLocations);
            for (Object memberRaw : ServerSaveStore.list(row.get("members"))) {
                if (fleet.members.size() >= MAX_MEMBERS_PER_FLEET) break;
                String key = clean(ServerSaveStore.asString(memberRaw, ""), 256);
                if (!ownedLiveKey(owner, key, live) || restored.fleetByUnit.containsKey(key)) continue;
                fleet.members.add(key);
                restored.fleetByUnit.put(key, fleetId);
            }
            restored.fleets.put(fleetId, fleet);
            restored.nextFleetId = Math.max(restored.nextFleetId, fleetId + 1);
            restored.nextOrderId = Math.max(restored.nextOrderId, fleet.order.orderId() + 1);
        }
        STATES.put(world, restored);
        StrategicSummaryService.invalidate(world);
    }

    static synchronized long beginOrder(World world, String ownerId, long fleetId,
                                        FleetOrderType type, String destinationSystemId,
                                        String targetKey, String detail) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null || type == null || type == FleetOrderType.NONE) return 0;
        RuntimeState state = state(world);
        long orderId = Math.max(1, state.nextOrderId++);
        fleet.order = new FleetStrategicOrder(orderId, type, FleetOrderState.EXECUTING,
                destinationSystemId, targetKey, detail);
        fleet.revision++;
        StrategicSummaryService.invalidate(world);
        return orderId;
    }

    static synchronized void orderBlocked(World world, String ownerId, long fleetId,
                                          long orderId, String detail) {
        FleetData fleet = owned(world, ownerId, fleetId);
        if (fleet == null || fleet.order.orderId() != orderId) return;
        fleet.order = fleet.order.withState(FleetOrderState.BLOCKED, detail);
        fleet.revision++;
        StrategicSummaryService.invalidate(world);
    }

    static synchronized long revision(World world, String ownerId, long fleetId) {
        FleetData fleet = owned(world, ownerId, fleetId);
        return fleet == null ? -1 : fleet.revision;
    }

    private static FleetView toView(World world, FleetData fleet) {
        Map<String,Integer> counts = new LinkedHashMap<>();
        Map<String,String> locations = FleetWire.ownerLocations(world, fleet.ownerId);
        for (String key : fleet.members) {
            String system = locations.get(key);
            if (system != null && !system.isBlank()) counts.merge(system, 1, Integer::sum);
        }
        return new FleetView(fleet.fleetId, fleet.ownerId, fleet.name, fleet.members,
                fleet.formation, fleet.combatStance, fleet.targetPriority,
                fleet.homeBaseId, fleet.rallySystemId, fleet.rallyX, fleet.rallyY,
                fleet.order, fleet.revision, counts);
    }

    private static void reconcileMembers(World world, FleetData fleet) {
        Map<String,String> live = FleetWire.ownerLocations(world, fleet.ownerId);
        RuntimeState state = state(world);
        boolean changed = false;
        for (String key : new ArrayList<>(fleet.members)) {
            if (ownedLiveKey(fleet.ownerId, key, live)) continue;
            fleet.members.remove(key);
            state.fleetByUnit.remove(key, fleet.fleetId);
            changed = true;
        }
        if (changed) fleet.revision++;
    }

    private static void reconcileOrder(World world, FleetData fleet) {
        FleetStrategicOrder order = fleet.order;
        if (order == null || order.type() == FleetOrderType.NONE
                || order.state() == FleetOrderState.COMPLETE
                || order.state() == FleetOrderState.BLOCKED) return;
        if (fleet.members.isEmpty()) {
            fleet.order = order.withState(FleetOrderState.BLOCKED, "Fleet has no surviving members.");
            fleet.revision++;
            return;
        }
        String destination = order.destinationSystemId();
        if (destination.isBlank()) return;
        Map<String,String> locations = FleetWire.ownerLocations(world, fleet.ownerId);
        for (String key : fleet.members) if (!destination.equals(locations.get(key))) return;
        fleet.order = order.withState(FleetOrderState.COMPLETE, "Fleet arrived in " + destination + ".");
        fleet.revision++;
    }

    private static Map<String,Object> captureFleet(FleetData fleet) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("fleetId", fleet.fleetId);
        row.put("ownerId", fleet.ownerId);
        row.put("name", fleet.name);
        row.put("revision", fleet.revision);
        row.put("members", List.copyOf(fleet.members));
        row.put("formation", fleet.formation.name());
        row.put("combatStance", fleet.combatStance.name());
        row.put("targetPriority", fleet.targetPriority.name());
        row.put("homeBaseId", fleet.homeBaseId);
        row.put("rallySystemId", fleet.rallySystemId);
        row.put("rallyX", fleet.rallyX);
        row.put("rallyY", fleet.rallyY);
        row.put("order", captureOrder(fleet.order));
        return row;
    }

    private static Map<String,Object> captureOrder(FleetStrategicOrder order) {
        FleetStrategicOrder safe = order == null ? FleetStrategicOrder.NONE : order;
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("orderId", safe.orderId());
        row.put("type", safe.type().name());
        row.put("state", safe.state().name());
        row.put("destinationSystemId", safe.destinationSystemId());
        row.put("targetKey", safe.targetKey());
        row.put("detail", safe.detail());
        return row;
    }

    private static FleetStrategicOrder restoreOrder(Object saved) {
        Map<String,Object> row = ServerSaveStore.object(saved);
        if (row.isEmpty()) return FleetStrategicOrder.NONE;
        return new FleetStrategicOrder(
                Math.max(0, ServerSaveStore.longValue(row, "orderId", 0)),
                ServerSaveStore.enumValue(FleetOrderType.class, row.get("type"), FleetOrderType.NONE),
                ServerSaveStore.enumValue(FleetOrderState.class, row.get("state"), FleetOrderState.IDLE),
                ServerSaveStore.string(row, "destinationSystemId", ""),
                ServerSaveStore.string(row, "targetKey", ""),
                ServerSaveStore.string(row, "detail", ""));
    }

    private static RuntimeState state(World world) {
        return STATES.computeIfAbsent(world, ignored -> new RuntimeState());
    }

    private static FleetData owned(World world, String ownerId, long fleetId) {
        if (world == null || fleetId <= 0) return null;
        String owner = cleanOwner(ownerId);
        FleetData fleet = state(world).fleets.get(fleetId);
        return fleet != null && owner.equals(fleet.ownerId) ? fleet : null;
    }

    private static FleetMutationResult existenceResult(World world, String ownerId, long fleetId) {
        if (world == null || fleetId <= 0) return FleetMutationResult.INVALID;
        FleetData fleet = state(world).fleets.get(fleetId);
        if (fleet == null) return FleetMutationResult.NOT_FOUND;
        return cleanOwner(ownerId).equals(fleet.ownerId) ? FleetMutationResult.NOT_FOUND : FleetMutationResult.FORBIDDEN;
    }

    private static boolean revisionMatches(FleetData fleet, long expectedRevision) {
        return expectedRevision < 0 || fleet.revision == expectedRevision;
    }

    private static int countOwnerFleets(RuntimeState state, String ownerId) {
        int count = 0;
        for (FleetData fleet : state.fleets.values()) if (ownerId.equals(fleet.ownerId)) count++;
        return count;
    }

    private static boolean ownedLiveKey(String ownerId, String key, Map<String,String> locations) {
        return key != null && key.startsWith(ownerId + ":")
                && locations != null && locations.containsKey(key);
    }

    private static LinkedHashSet<String> cleanMembers(Collection<String> keys) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (keys == null) return out;
        for (String raw : keys) {
            String key = clean(raw, 256);
            if (!key.isBlank()) out.add(key);
        }
        return out;
    }

    private static String cleanOwner(String value) { return clean(value, 128); }

    private static String cleanName(String value) {
        String clean = clean(value, MAX_NAME_LENGTH);
        if (clean.isBlank()) return "";
        for (int i = 0; i < clean.length(); i++) if (Character.isISOControl(clean.charAt(i))) return "";
        return clean;
    }

    private static String clean(String value, int max) {
        String out = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return out.length() <= max ? out : out.substring(0, max);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static double finiteOrZero(double value) { return Double.isFinite(value) ? value : 0; }

    private static final class RuntimeState {
        long nextFleetId = 1;
        long nextOrderId = 1;
        final Map<Long,FleetData> fleets = new LinkedHashMap<>();
        final Map<String,Long> fleetByUnit = new HashMap<>();
    }

    private static final class FleetData {
        final long fleetId;
        final String ownerId;
        final LinkedHashSet<String> members = new LinkedHashSet<>();
        String name;
        FleetFormation formation = FleetFormation.GRID;
        CombatStance combatStance = CombatStance.AGGRESSIVE;
        TargetPriorityPolicy targetPriority = TargetPriorityPolicy.NEAREST_THREAT;
        String homeBaseId = "";
        String rallySystemId = "";
        double rallyX;
        double rallyY;
        FleetStrategicOrder order = FleetStrategicOrder.NONE;
        long revision;

        FleetData(long fleetId, String ownerId, String name) {
            this.fleetId = fleetId;
            this.ownerId = ownerId;
            this.name = name;
        }
    }
}

/**
 * Translates fleet-level strategic movement into the existing per-unit authoritative
 * command queue. This deliberately does not implement movement or combat itself.
 */
final class FleetCommandService {
    private FleetCommandService() { }

    static FleetCommandResult moveToSystem(World world, String ownerId, long fleetId,
                                           String destinationSystemId) {
        return issueTravel(world, ownerId, fleetId, FleetOrderType.MOVE_TO_SYSTEM,
                destinationSystemId, false, 0, 0);
    }

    static FleetCommandResult rally(World world, String ownerId, long fleetId,
                                    String destinationSystemId, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return FleetCommandResult.rejected("Invalid rally coordinates.");
        return issueTravel(world, ownerId, fleetId, FleetOrderType.RALLY,
                destinationSystemId, true, x, y);
    }

    static FleetCommandResult retreatRegroup(World world, String ownerId, long fleetId,
                                             String destinationSystemId, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return FleetCommandResult.rejected("Invalid regroup coordinates.");
        return issueTravel(world, ownerId, fleetId, FleetOrderType.RETREAT_REGROUP,
                destinationSystemId, true, x, y);
    }

    static FleetCommandResult applyCombatPolicy(World world, String ownerId, long fleetId,
                                                CombatStance stance, TargetPriorityPolicy priority) {
        if (world == null || stance == null || priority == null) return FleetCommandResult.rejected("Invalid fleet combat policy.");
        Optional<FleetView> maybe = FleetManager.view(world, ownerId, fleetId);
        if (maybe.isEmpty()) return FleetCommandResult.rejected("Fleet not found or not owned by player.");
        FleetView fleet = maybe.get();
        if (fleet.memberKeys().isEmpty()) return FleetCommandResult.rejected("Fleet has no surviving members.");
        Map<String,String> locations = FleetWire.ownerLocations(world, fleet.ownerId());
        List<PolicyPlan> plans = new ArrayList<>();
        for (String key : fleet.memberKeys()) {
            String systemId = locations.get(key);
            int unitId = unitId(key, fleet.ownerId());
            if (systemId == null || systemId.isBlank() || unitId < 0) return FleetCommandResult.rejected("Fleet contains an unavailable ship.");
            plans.add(new PolicyPlan(unitId, systemId, UnitCommandQueueSystem.revision(world, key)));
        }
        for (PolicyPlan plan : plans) {
            UnitQueueApplyResult result = UnitCommandQueueSystem.applyGlobal(world,
                    new UnitQueueMutation(fleet.ownerId(), plan.unitId(), UnitQueueOperation.POLICY,
                            plan.revision(), QueuedUnitCommand.policy(plan.systemId(), stance, priority)));
            if (result != UnitQueueApplyResult.APPLIED) return FleetCommandResult.rejected("Fleet policy update was rejected for one or more ships.");
        }
        FleetMutationResult saved = FleetManager.setPolicies(world, fleet.ownerId(), fleet.fleetId(), -1, stance, priority);
        return saved == FleetMutationResult.APPLIED
                ? new FleetCommandResult(true, 0, "Fleet combat policy updated.")
                : FleetCommandResult.rejected("Fleet policy changed concurrently.");
    }

    private static FleetCommandResult issueTravel(World world, String ownerId, long fleetId,
                                                  FleetOrderType type, String destinationSystemId,
                                                  boolean rally, double rallyX, double rallyY) {
        if (world == null) return FleetCommandResult.rejected("World is unavailable.");
        String destination = clean(destinationSystemId);
        if (destination.isBlank()) return FleetCommandResult.rejected("Destination system is required.");
        Optional<FleetView> maybe = FleetManager.view(world, ownerId, fleetId);
        if (maybe.isEmpty()) return FleetCommandResult.rejected("Fleet not found or not owned by player.");
        FleetView fleet = maybe.get();
        if (fleet.memberKeys().isEmpty()) return FleetCommandResult.rejected("Fleet has no surviving members.");
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        if (!containsSystem(map, destination)) return FleetCommandResult.rejected("Destination system is not available.");

        Map<String,String> locations = FleetWire.ownerLocations(world, fleet.ownerId());
        List<UnitTravelPlan> plans = new ArrayList<>();
        int formationIndex = 0;
        for (String key : fleet.memberKeys()) {
            String source = locations.get(key);
            int id = unitId(key, fleet.ownerId());
            if (source == null || source.isBlank() || id < 0) return FleetCommandResult.rejected("Fleet contains an unavailable ship.");
            List<String> route = shortestRoute(map, source, destination);
            if (route.isEmpty()) return FleetCommandResult.rejected("No wormhole route exists for all fleet members.");
            List<QueuedUnitCommand> commands = commandsForRoute(world, route);
            if (commands == null) return FleetCommandResult.rejected("A wormhole route changed while the order was being prepared.");
            if (rally) {
                if (commands.size() >= UnitCommandQueueSystem.MAX_QUEUE) return FleetCommandResult.rejected("Fleet route exceeds command queue capacity.");
                double[] target = formationTarget(rallyX, rallyY, formationIndex++, fleet.memberKeys().size(), fleet.formation());
                commands.add(QueuedUnitCommand.move(destination, target[0], target[1]));
            }
            if (commands.size() > UnitCommandQueueSystem.MAX_QUEUE) return FleetCommandResult.rejected("Fleet route exceeds command queue capacity.");
            if (!preflightUnit(world, fleet.ownerId(), id, source)) return FleetCommandResult.rejected("A fleet member cannot currently receive orders.");
            plans.add(new UnitTravelPlan(id, key, List.copyOf(commands), UnitCommandQueueSystem.revision(world, key)));
        }

        long orderId = FleetManager.beginOrder(world, fleet.ownerId(), fleet.fleetId(), type,
                destination, "", rally ? "Rallying in " + destination : "Moving to " + destination);
        if (orderId <= 0) return FleetCommandResult.rejected("Fleet order could not be created.");

        List<UnitTravelPlan> touched = new ArrayList<>();
        for (UnitTravelPlan plan : plans) {
            if (!applyPlan(world, fleet.ownerId(), plan)) {
                clearTouched(world, fleet.ownerId(), touched);
                FleetManager.orderBlocked(world, fleet.ownerId(), fleet.fleetId(), orderId,
                        "A member command queue changed while the fleet order was being applied.");
                return new FleetCommandResult(false, orderId, "Fleet order blocked by a changed member command queue.");
            }
            touched.add(plan);
        }
        if (rally) FleetManager.setHomeAndRally(world, fleet.ownerId(), fleet.fleetId(), -1,
                fleet.homeBaseId(), destination, rallyX, rallyY);
        return new FleetCommandResult(true, orderId,
                rally ? "Fleet rally order issued." : "Fleet cross-system movement order issued.");
    }

    private static boolean applyPlan(World world, String ownerId, UnitTravelPlan plan) {
        long revision = plan.revision();
        if (plan.commands().isEmpty()) {
            return UnitCommandQueueSystem.applyGlobal(world,
                    new UnitQueueMutation(ownerId, plan.unitId(), UnitQueueOperation.CLEAR,
                            revision, null)) == UnitQueueApplyResult.APPLIED;
        }
        for (int i = 0; i < plan.commands().size(); i++) {
            UnitQueueOperation operation = i == 0 ? UnitQueueOperation.REPLACE : UnitQueueOperation.APPEND;
            UnitQueueApplyResult result = UnitCommandQueueSystem.applyGlobal(world,
                    new UnitQueueMutation(ownerId, plan.unitId(), operation, revision,
                            plan.commands().get(i)));
            if (result != UnitQueueApplyResult.APPLIED) return false;
            revision++;
        }
        return true;
    }

    private static void clearTouched(World world, String ownerId, List<UnitTravelPlan> touched) {
        for (UnitTravelPlan plan : touched) {
            long revision = UnitCommandQueueSystem.revision(world, plan.unitKey());
            UnitCommandQueueSystem.applyGlobal(world,
                    new UnitQueueMutation(ownerId, plan.unitId(), UnitQueueOperation.CLEAR, revision, null));
        }
    }

    private static boolean preflightUnit(World world, String ownerId, int unitId, String systemId) {
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            world.activateSystem(systemId);
            Unit unit = world.units.get(Unit.key(ownerId, unitId));
            return unit != null && ownerId.equals(unit.playerId) && unit.hp > 0
                    && !ProductionSystem.refitReserved(world, unit.key());
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static List<QueuedUnitCommand> commandsForRoute(World world, List<String> route) {
        List<QueuedUnitCommand> commands = new ArrayList<>();
        if (route.size() <= 1) return commands;
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        try {
            for (int i = 0; i + 1 < route.size(); i++) {
                String source = route.get(i);
                String destination = route.get(i + 1);
                world.activateSystem(source);
                WormholeGate selected = null;
                for (WormholeGate gate : world.wormholes) {
                    if (gate != null && source.equals(gate.fromSystemId) && destination.equals(gate.toSystemId)) {
                        selected = gate;
                        break;
                    }
                }
                if (selected == null) return null;
                commands.add(QueuedUnitCommand.wormhole(source, selected.id, destination));
            }
            return commands;
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }

    private static List<String> shortestRoute(GalaxyMapSnapshot map, String source, String destination) {
        if (source.equals(destination)) return List.of(source);
        Map<String,LinkedHashSet<String>> adjacency = new LinkedHashMap<>();
        if (map != null && map.systems() != null) {
            for (GalaxyMapSystem system : map.systems()) if (system != null) adjacency.putIfAbsent(system.id(), new LinkedHashSet<>());
        }
        if (map != null && map.links() != null) {
            for (GalaxyMapLink link : map.links()) {
                if (link == null) continue;
                adjacency.computeIfAbsent(link.fromSystemId(), ignored -> new LinkedHashSet<>()).add(link.toSystemId());
                adjacency.computeIfAbsent(link.toSystemId(), ignored -> new LinkedHashSet<>()).add(link.fromSystemId());
            }
        }
        if (!adjacency.containsKey(source) || !adjacency.containsKey(destination)) return List.of();
        Deque<String> queue = new ArrayDeque<>();
        Map<String,String> parent = new HashMap<>();
        queue.add(source);
        parent.put(source, "");
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacency.getOrDefault(current, new LinkedHashSet<>())) {
                if (parent.containsKey(next)) continue;
                parent.put(next, current);
                if (destination.equals(next)) {
                    List<String> route = new ArrayList<>();
                    String cursor = destination;
                    while (!cursor.isBlank()) {
                        route.add(cursor);
                        cursor = parent.getOrDefault(cursor, "");
                    }
                    Collections.reverse(route);
                    return route;
                }
                queue.addLast(next);
            }
        }
        return List.of();
    }

    private static boolean containsSystem(GalaxyMapSnapshot map, String systemId) {
        if (map == null || map.systems() == null) return false;
        for (GalaxyMapSystem system : map.systems()) if (system != null && systemId.equals(system.id())) return true;
        return false;
    }

    private static int unitId(String unitKey, String ownerId) {
        String prefix = ownerId + ":";
        if (unitKey == null || !unitKey.startsWith(prefix)) return -1;
        try { return Integer.parseInt(unitKey.substring(prefix.length())); }
        catch (RuntimeException ex) { return -1; }
    }

    private static double[] formationTarget(double x, double y, int index, int count, FleetFormation formation) {
        double spacing = 54, ox = 0, oy = 0;
        switch (formation == null ? FleetFormation.GRID : formation) {
            case LINE -> ox = (index - (count - 1) / 2.0) * spacing;
            case COLUMN -> oy = (index - (count - 1) / 2.0) * spacing;
            case WEDGE -> {
                if (index > 0) {
                    int rank = (index + 1) / 2;
                    int side = index % 2 == 1 ? -1 : 1;
                    ox = side * rank * spacing;
                    oy = rank * spacing;
                }
            }
            case GRID -> {
                int cols = (int)Math.ceil(Math.sqrt(Math.max(1, count)));
                double rows = Math.ceil(count / (double)cols);
                int col = index % cols;
                int row = index / cols;
                ox = (col - (cols - 1) / 2.0) * 42;
                oy = (row - (rows - 1) / 2.0) * 42;
            }
        }
        return new double[]{x + ox, y + oy};
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private record UnitTravelPlan(int unitId, String unitKey,
                                  List<QueuedUnitCommand> commands, long revision) { }
    private record PolicyPlan(int unitId, String systemId, long revision) { }
}
