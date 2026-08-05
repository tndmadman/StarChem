package com.tndmadman.rts;

import java.util.*;
import java.util.function.Supplier;

final class ResourceSync {
    private static final int DIRTY_SENDS = 12;
    private static final int FULL_SENDS = 3;
    private static final Map<World, Map<Integer, Integer>> DIRTY = new WeakHashMap<>();
    private static final Map<World, Integer> FULL = new WeakHashMap<>();
    private static final Map<World, Map<String, Map<String, LinkedHashMap<Integer, SentResource>>>> SENT =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<PlayerContext> PLAYER_CONTEXT = new ThreadLocal<>();

    private ResourceSync() { }

    static void mark(World world, ResourceNode node) {
        if (world == null || node == null) return;
        DIRTY.computeIfAbsent(world, w -> new LinkedHashMap<>()).put(node.id, DIRTY_SENDS);
    }

    static void markFull(World world) {
        if (world != null) FULL.put(world, FULL_SENDS);
    }

    static <T> T withPlayerContext(String playerId, boolean fullResources, Supplier<T> supplier) {
        if (supplier == null) return null;
        PlayerContext previous = PLAYER_CONTEXT.get();
        PlayerContext current = new PlayerContext(playerId, fullResources);
        PLAYER_CONTEXT.set(current);
        try {
            T result = supplier.get();
            commit(current);
            return result;
        } finally {
            if (previous == null) PLAYER_CONTEXT.remove();
            else PLAYER_CONTEXT.set(previous);
        }
    }

    static boolean authorizedTombstone(String playerId, int resourceId) {
        PlayerContext context = PLAYER_CONTEXT.get();
        return context != null && Objects.equals(context.playerId, playerId)
                && context.tombstoneIds.contains(resourceId);
    }

    static List<ResourceState> snapshot(World world) {
        CelestialPacketCache.capture(world);
        PlayerContext context = PLAYER_CONTEXT.get();
        if (context != null && context.valid()) {
            ResourceSyncMode.consumeFull();
            return snapshotForPlayer(world, context);
        }
        if (ResourceSyncMode.consumeFull()) {
            List<ResourceState> out = all(world);
            ResourceNetDebug.snapshotBuilt(world, "initial-full", out);
            return out;
        }
        int fullLeft = FULL.getOrDefault(world, 0);
        if (fullLeft > 0) {
            FULL.put(world, fullLeft - 1);
            List<ResourceState> out = all(world);
            ResourceNetDebug.snapshotBuilt(world, "marked-full-left-" + fullLeft, out);
            return out;
        }
        Set<Integer> ids = new LinkedHashSet<>();
        Map<Integer, Integer> dirty = DIRTY.get(world);
        if (dirty != null) ids.addAll(dirty.keySet());
        for (Unit unit : world.units.values()) if (unit.automationResourceId > 0) ids.add(unit.automationResourceId);
        List<ResourceState> out = new ArrayList<>();
        for (Integer id : ids) {
            ResourceNode r = world.findResource(id);
            if (r != null) out.add(state(r));
        }
        decay(dirty);
        ResourceNetDebug.snapshotBuilt(world, "partial-ids-" + ids.size(), out);
        return out;
    }

    private static List<ResourceState> snapshotForPlayer(World world, PlayerContext context) {
        String systemId = world.activeSystemId();
        if (systemId == null) systemId = "";

        VisibilityRules.Frame visibility = VisibilityRules.frame(world, context.playerId);
        LinkedHashMap<Integer, SentResource> current = new LinkedHashMap<>();
        for (ResourceNode node : world.resources) {
            if (node == null || !node.active) continue;
            IntelWarfareSystem.DetectionStage stage = visibility.resourceStage(node);
            if (stage == IntelWarfareSystem.DetectionStage.NONE) continue;
            current.put(node.id, new SentResource(state(node), stage));
        }

        List<ResourceState> outgoing = new ArrayList<>();
        synchronized (SENT) {
            LinkedHashMap<Integer, SentResource> previous = previous(world, context.playerId, systemId);
            boolean full = context.fullResources || consumeMarkedFull(world);
            if (full) {
                for (SentResource sent : current.values()) outgoing.add(sent.state);
            } else {
                for (SentResource sent : current.values()) {
                    SentResource prior = previous.get(sent.state.id());
                    if (prior == null || prior.stage != sent.stage
                            || materiallyChanged(prior.state, sent.state)
                            || targetedByPlayer(world, context.playerId, sent.state.id())) {
                        outgoing.add(sent.state);
                    }
                }
                for (SentResource prior : new ArrayList<>(previous.values())) {
                    if (current.containsKey(prior.state.id())) continue;
                    outgoing.add(tombstone(prior.state));
                    context.tombstoneIds.add(prior.state.id());
                }
            }
        }

        context.pending = new Pending(world, systemId, current);
        ResourceNetDebug.snapshotBuilt(world,
                context.fullResources ? "player-full-" + context.playerId
                        : "player-delta-" + context.playerId,
                outgoing);
        return outgoing;
    }

    private static LinkedHashMap<Integer, SentResource> previous(World world, String playerId, String systemId) {
        Map<String, Map<String, LinkedHashMap<Integer, SentResource>>> byPlayer = SENT.get(world);
        if (byPlayer == null) return new LinkedHashMap<>();
        Map<String, LinkedHashMap<Integer, SentResource>> bySystem = byPlayer.get(playerId);
        if (bySystem == null) return new LinkedHashMap<>();
        LinkedHashMap<Integer, SentResource> previous = bySystem.get(systemId);
        return previous == null ? new LinkedHashMap<>() : previous;
    }

    private static void commit(PlayerContext context) {
        if (context == null || !context.valid() || context.pending == null) return;
        Pending pending = context.pending;
        synchronized (SENT) {
            Map<String, Map<String, LinkedHashMap<Integer, SentResource>>> byPlayer =
                    SENT.computeIfAbsent(pending.world, ignored -> new LinkedHashMap<>());
            Map<String, LinkedHashMap<Integer, SentResource>> bySystem =
                    byPlayer.computeIfAbsent(context.playerId, ignored -> new LinkedHashMap<>());
            bySystem.put(pending.systemId, new LinkedHashMap<>(pending.current));
        }
    }

    private static boolean consumeMarkedFull(World world) {
        int fullLeft = FULL.getOrDefault(world, 0);
        if (fullLeft <= 0) return false;
        if (fullLeft == 1) FULL.remove(world);
        else FULL.put(world, fullLeft - 1);
        return true;
    }

    private static boolean targetedByPlayer(World world, String playerId, int resourceId) {
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || unit.automationResourceId != resourceId) continue;
            if (IntelWarfareSystem.allied(world, playerId, unit.playerId)) return true;
        }
        return false;
    }

    private static boolean materiallyChanged(ResourceState first, ResourceState second) {
        if (first == null || second == null) return first != second;
        return first.id() != second.id()
                || !Objects.equals(first.name(), second.name())
                || !Objects.equals(first.kind(), second.kind())
                || !Objects.equals(first.material(), second.material())
                || Double.compare(first.maxAmount(), second.maxAmount()) != 0
                || Double.compare(first.harvestRate(), second.harvestRate()) != 0
                || Double.compare(first.radius(), second.radius()) != 0
                || Double.compare(first.amount(), second.amount()) != 0
                || first.active() != second.active()
                || Double.compare(first.respawnTimer(), second.respawnTimer()) != 0
                || Double.compare(first.orbitCenterX(), second.orbitCenterX()) != 0
                || Double.compare(first.orbitCenterY(), second.orbitCenterY()) != 0
                || Double.compare(first.orbitRadius(), second.orbitRadius()) != 0
                || Double.compare(first.orbitSpeed(), second.orbitSpeed()) != 0
                || first.orbiting() != second.orbiting();
    }

    private static ResourceState tombstone(ResourceState prior) {
        return new ResourceState(prior.id(), "Hidden resource", NodeKind.SILICATE_ROCK.name(),
                Material.IRON.name(), prior.x(), prior.y(), 1, 0, 0, 0, false, 0,
                prior.orbitCenterX(), prior.orbitCenterY(), prior.orbitRadius(),
                prior.orbitAngle(), prior.orbitSpeed(), prior.orbiting());
    }

    private static List<ResourceState> all(World world) {
        List<ResourceState> out = new ArrayList<>();
        for (ResourceNode r : world.resources) out.add(state(r));
        return out;
    }

    private static ResourceState state(ResourceNode r) {
        return new ResourceState(r.id, r.name, r.kind.name(), r.material.name(), r.x, r.y,
                r.maxAmount, r.harvestRate, r.radius, r.amount, r.active, r.respawnTimer,
                r.orbitCenterX, r.orbitCenterY, r.orbitRadius, r.orbitAngle, r.orbitSpeed, r.orbiting);
    }

    private static void decay(Map<Integer, Integer> dirty) {
        if (dirty == null) return;
        Iterator<Map.Entry<Integer, Integer>> it = dirty.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left <= 0) it.remove();
            else entry.setValue(left);
        }
    }

    private static final class PlayerContext {
        final String playerId;
        final boolean fullResources;
        final Set<Integer> tombstoneIds = new LinkedHashSet<>();
        Pending pending;

        PlayerContext(String playerId, boolean fullResources) {
            this.playerId = playerId == null ? "" : playerId;
            this.fullResources = fullResources;
        }

        boolean valid() {
            return !playerId.isBlank();
        }
    }

    private record SentResource(ResourceState state, IntelWarfareSystem.DetectionStage stage) { }
    private record Pending(World world, String systemId, LinkedHashMap<Integer, SentResource> current) { }
}
