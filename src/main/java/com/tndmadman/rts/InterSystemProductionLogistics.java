package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes production-demand cargo between player-owned stations in different
 * star systems using the ordinary logistics shuttle hull.
 *
 * Cargo is never virtualized: material is debited from the source hangar when
 * the shuttle is created, remains in Unit.inventory through every wormhole
 * hop, and is deposited into an owned destination hangar on arrival. The
 * marker stored in logisticsRequestId is persisted with the unit, so these
 * shipments survive save/load without a second persistence structure.
 */
final class InterSystemProductionLogistics {
    private static final String MARKER = "XPROD~";
    private static final double EPSILON = 0.05;
    private static final double DOCK_RANGE_FACTOR = 0.42;

    private InterSystemProductionLogistics() { }

    static boolean manages(Unit unit) {
        return unit != null
                && LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)
                && unit.logisticsRequestId != null
                && unit.logisticsRequestId.startsWith(MARKER);
    }

    /** Advance marked shuttles that are currently loaded in this system. */
    static void update(World world) {
        if (world == null) return;
        String currentSystemId = clean(world.activeSystemId());
        if (currentSystemId.isBlank()) return;

        Iterator<Unit> iterator = world.units.values().iterator();
        while (iterator.hasNext()) {
            Unit shuttle = iterator.next();
            if (!manages(shuttle)) continue;
            ShipmentMarker marker = parse(shuttle.logisticsRequestId);
            if (marker == null || !shuttle.playerId.equals(marker.playerId)) {
                releaseInvalid(world, shuttle);
                continue;
            }
            if (shuttle.cargoUsed() <= EPSILON) {
                iterator.remove();
                continue;
            }

            if (currentSystemId.equals(marker.targetSystemId)) {
                Base target = world.bases.get(shuttle.logisticsTargetBaseId);
                if (target == null || target.hp <= 0 || !shuttle.playerId.equals(target.playerId)) {
                    target = world.nearestBase(shuttle.playerId, shuttle.x, shuttle.y);
                }
                if (target == null) {
                    stop(shuttle);
                    continue;
                }
                move(shuttle, target.x, target.y);
                double dockRange = Math.max(42, target.type().unloadRange * DOCK_RANGE_FACTOR);
                if (Calc.distance(shuttle.x, shuttle.y, target.x, target.y) > dockRange) continue;
                deposit(target, shuttle);
                iterator.remove();
                continue;
            }

            List<String> path = LogisticsRouteSystem.pathForTest(
                    world, shuttle.playerId, currentSystemId, marker.targetSystemId);
            if (path.size() < 2) {
                stop(shuttle);
                continue;
            }
            WormholeGate gate = gateTo(world, path.get(1));
            if (gate == null) {
                stop(shuttle);
                continue;
            }
            move(shuttle, gate.x, gate.y);
        }
    }

    /**
     * Rebuild request in-transit accounting from marked shuttles in every
     * system. This keeps the existing LogisticsRequest anti-double-dispatch
     * logic authoritative even while cargo is several wormhole hops away.
     */
    static void trackInTransit(World world, List<LogisticsRequest> requests, String targetSystemId) {
        if (world == null || requests == null || requests.isEmpty()) return;
        Map<String, LogisticsRequest> relevant = new HashMap<>();
        for (LogisticsRequest request : requests) {
            if (request != null && request.inSystem(targetSystemId)) relevant.put(request.id, request);
        }
        if (relevant.isEmpty()) return;

        String previous = clean(world.activeSystemId());
        List<String> systemIds = systemIds(world);
        try {
            for (String systemId : systemIds) {
                if (!systemId.equals(world.activeSystemId())) world.activateSystem(systemId);
                for (Unit shuttle : world.units.values()) {
                    if (!manages(shuttle) || shuttle.cargoUsed() <= EPSILON) continue;
                    ShipmentMarker marker = parse(shuttle.logisticsRequestId);
                    if (marker == null || !targetSystemId.equals(marker.targetSystemId)) continue;
                    LogisticsRequest request = relevant.get(marker.requestId);
                    if (request == null || !request.playerId.equals(shuttle.playerId)) continue;
                    request.trackInTransit(shuttle.inventory);
                }
            }
        } finally {
            if (!previous.isBlank() && !previous.equals(world.activeSystemId())) world.activateSystem(previous);
        }
    }

    /** Total material available in owned remote stations that can reach target. */
    static double remoteAvailableAmount(World world, String targetSystemId, Base target, Material material) {
        double total = 0;
        for (SourceCandidate source : remoteSources(world, targetSystemId, target, material)) {
            total += source.available;
        }
        return total;
    }

    /**
     * Dispatch from remote stations strictly nearest-first. A source is used
     * up to the full outstanding amount (or its available stock) before the
     * next, farther source is touched.
     */
    static double dispatch(World world, String targetSystemId, Base target,
                           LogisticsRequest request, Material material, double requested) {
        if (world == null || target == null || request == null || material == null || requested <= EPSILON) {
            return 0;
        }
        double remaining = requested;
        double sent = 0;
        double capacity = Math.max(1, Rules.ship(LogisticsSystem.SHUTTLE_TYPE).cargoCapacity);
        for (SourceCandidate source : remoteSources(world, targetSystemId, target, material)) {
            if (remaining <= EPSILON) break;
            double dispatched = dispatchFromSource(world, source, targetSystemId, target,
                    request, material, remaining, capacity);
            remaining -= dispatched;
            sent += dispatched;
        }
        return sent;
    }

    private static double dispatchFromSource(World world, SourceCandidate candidate,
                                             String targetSystemId, Base target,
                                             LogisticsRequest request, Material material,
                                             double requested, double capacity) {
        String previous = clean(world.activeSystemId());
        try {
            world.activateSystem(candidate.systemId);
            if (!candidate.systemId.equals(world.activeSystemId())) return 0;
            Base source = world.bases.get(candidate.baseId);
            if (source == null || source.hp <= 0 || !request.playerId.equals(source.playerId)) return 0;

            List<String> path = LogisticsRouteSystem.pathForTest(
                    world, request.playerId, candidate.systemId, targetSystemId);
            if (path.size() < 2) return 0;
            WormholeGate gate = gateTo(world, path.get(1));
            if (gate == null) return 0;

            double available = source.inventory.getOrDefault(material, 0.0);
            double remaining = Math.min(requested, available);
            if (remaining <= EPSILON) return 0;

            int nextId = nextGalaxyUnitId(world, request.playerId);
            double sent = 0;
            while (remaining > EPSILON) {
                double take = Math.min(capacity, remaining);
                debit(source, material, take);
                Unit shuttle = new Unit(request.playerId, nextId++, LogisticsSystem.SHUTTLE_TYPE,
                        undockX(source, gate), undockY(source, gate));
                shuttle.logisticsRequestId = marker(request, targetSystemId);
                shuttle.logisticsTargetBaseId = target.id;
                shuttle.addCargo(material, take);
                move(shuttle, gate.x, gate.y);
                world.units.put(shuttle.key(), shuttle);
                request.dispatched(material, take);
                sent += take;
                remaining -= take;
            }
            return sent;
        } finally {
            if (!previous.isBlank() && !previous.equals(world.activeSystemId())) world.activateSystem(previous);
        }
    }

    private static List<SourceCandidate> remoteSources(World world, String targetSystemId,
                                                        Base target, Material material) {
        if (world == null || target == null || material == null) return List.of();
        String previous = clean(world.activeSystemId());
        List<SourceCandidate> out = new ArrayList<>();
        List<String> systemIds = systemIds(world);
        try {
            for (String systemId : systemIds) {
                if (systemId.equals(targetSystemId)) continue;
                List<String> path = LogisticsRouteSystem.pathForTest(
                        world, target.playerId, systemId, targetSystemId);
                if (path.size() < 2) continue;
                world.activateSystem(systemId);
                if (!systemId.equals(world.activeSystemId())) continue;
                WormholeGate departure = gateTo(world, path.get(1));
                if (departure == null) continue;
                int hops = path.size() - 1;
                for (Base source : world.bases.values()) {
                    if (source.hp <= 0 || !target.playerId.equals(source.playerId)) continue;
                    double available = source.inventory.getOrDefault(material, 0.0);
                    if (available <= EPSILON) continue;
                    double departureDistance = Calc.distance(source.x, source.y, departure.x, departure.y);
                    out.add(new SourceCandidate(systemId, source.id, available, hops, departureDistance));
                }
            }
        } finally {
            if (!previous.isBlank() && !previous.equals(world.activeSystemId())) world.activateSystem(previous);
        }
        out.sort(Comparator
                .comparingInt((SourceCandidate source) -> source.hops)
                .thenComparingDouble(source -> source.departureDistance)
                .thenComparing(source -> source.systemId)
                .thenComparing(source -> source.baseId));
        return List.copyOf(out);
    }

    private static List<String> systemIds(World world) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        List<String> ids = new ArrayList<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && system.id() != null && !system.id().isBlank()) ids.add(system.id());
        }
        return List.copyOf(ids);
    }

    private static int nextGalaxyUnitId(World world, String playerId) {
        int next = 1;
        for (String key : world.ownerUnitLocations(playerId).keySet()) {
            int separator = key.lastIndexOf(':');
            if (separator < 0 || separator + 1 >= key.length()) continue;
            try {
                next = Math.max(next, Integer.parseInt(key.substring(separator + 1)) + 1);
            } catch (NumberFormatException ignored) { }
        }
        return next;
    }

    private static String marker(LogisticsRequest request, String targetSystemId) {
        return MARKER + request.id + '~' + clean(targetSystemId) + '~' + clean(request.playerId);
    }

    private static ShipmentMarker parse(String value) {
        if (value == null || !value.startsWith(MARKER)) return null;
        String[] parts = value.split("~", -1);
        if (parts.length != 4 || !"XPROD".equals(parts[0])) return null;
        String requestId = clean(parts[1]);
        String targetSystemId = clean(parts[2]);
        String playerId = clean(parts[3]);
        if (requestId.isBlank() || targetSystemId.isBlank() || playerId.isBlank()) return null;
        return new ShipmentMarker(requestId, targetSystemId, playerId);
    }

    private static WormholeGate gateTo(World world, String destinationSystemId) {
        for (WormholeGate gate : world.wormholes) {
            if (destinationSystemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static void deposit(Base target, Unit shuttle) {
        for (Material material : Material.values()) {
            double amount = shuttle.inventory.getOrDefault(material, 0.0);
            if (amount > EPSILON) HangarStore.add(target.inventory, material, amount);
        }
        shuttle.inventory.clear();
    }

    private static void debit(Base source, Material material, double amount) {
        double next = source.inventory.getOrDefault(material, 0.0) - amount;
        if (next <= EPSILON) source.inventory.remove(material);
        else source.inventory.put(material, next);
    }

    private static double undockX(Base source, WormholeGate gate) {
        double angle = Math.atan2(gate.y - source.y, gate.x - source.x);
        return source.x + Math.cos(angle) * Math.max(58, source.type().buildRadius * 0.8);
    }

    private static double undockY(Base source, WormholeGate gate) {
        double angle = Math.atan2(gate.y - source.y, gate.x - source.x);
        return source.y + Math.sin(angle) * Math.max(58, source.type().buildRadius * 0.8);
    }

    private static void move(Unit shuttle, double x, double y) {
        shuttle.task = UnitTask.MOVE;
        shuttle.attackTarget = "";
        shuttle.automationResourceId = -1;
        shuttle.targetX = x;
        shuttle.targetY = y;
    }

    private static void stop(Unit shuttle) {
        shuttle.task = UnitTask.IDLE;
        shuttle.targetX = shuttle.x;
        shuttle.targetY = shuttle.y;
    }

    private static void releaseInvalid(World world, Unit shuttle) {
        shuttle.logisticsRequestId = "";
        Base fallback = world.nearestBase(shuttle.playerId, shuttle.x, shuttle.y);
        if (fallback == null) {
            stop(shuttle);
            return;
        }
        shuttle.logisticsTargetBaseId = fallback.id;
        move(shuttle, fallback.x, fallback.y);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record SourceCandidate(String systemId, String baseId, double available,
                                   int hops, double departureDistance) { }

    private record ShipmentMarker(String requestId, String targetSystemId, String playerId) { }
}
