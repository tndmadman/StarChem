package com.tndmadman.rts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Persistent, server-authoritative inter-system cargo routes.
 *
 * Route configuration is world scoped. Cargo itself is never virtualized: a
 * committed shipment is removed from the source hangar only when a real
 * Hauler/Freighter loads it, remains in Unit.inventory while travelling, and
 * is therefore lost/salvaged through the ordinary ship-destruction path.
 */
final class LogisticsRouteSystem {
    static final String COMMAND_CREATE = "LOG_ROUTE_CREATE";
    static final String COMMAND_UPDATE = "LOG_ROUTE_UPDATE";
    static final String COMMAND_PAUSE = "LOG_ROUTE_PAUSE";
    static final String COMMAND_RESUME = "LOG_ROUTE_RESUME";
    static final String COMMAND_DELETE = "LOG_ROUTE_DELETE";

    static final int MAX_ROUTES_PER_PLAYER = 64;
    static final int MAX_MATERIALS = 16;
    static final int MAX_TRANSPORTS = 12;
    static final int MAX_ESCORTS = 24;
    static final int MAX_COMMAND_CHARS = 4096;

    private static final String UNIT_MARKER = "ROUTE:";
    private static final String STATUS_MARKER = "Inter-system routes: ";
    private static final double EPSILON = 0.05;
    private static final double DOCK_FACTOR = 0.55;
    private static final Map<World, RouteRuntime> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private LogisticsRouteSystem() { }

    enum RoutePhase {
        WAITING,
        LOADING,
        OUTBOUND,
        UNLOADING,
        RETURNING,
        BLOCKED,
        PAUSED
    }

    record RouteView(String id, RoutePhase phase, String destinationSystemId,
                     String destinationBaseId, List<Material> materials,
                     double sourceReserve, double destinationTarget,
                     double batchSize, int priority, int transportCount,
                     int escortCount) { }

    static synchronized boolean applyCommand(World world, String playerId, String sourceBaseId,
                                             String action, String value) {
        if (world == null || !validToken(playerId, 64) || !validToken(sourceBaseId, 128)
                || action == null) return false;
        Base source = world.bases.get(sourceBaseId);
        if (source == null || source.hp <= 0 || !playerId.equals(source.playerId)) return false;
        String command = action.trim().toUpperCase(Locale.ROOT);
        if (!command.startsWith("LOG_ROUTE_")) return false;

        RouteRuntime runtime = state(world);
        boolean changed = switch (command) {
            case COMMAND_CREATE -> create(world, runtime, source, playerId, value);
            case COMMAND_UPDATE -> updateDefinition(world, runtime, source, playerId, value);
            case COMMAND_PAUSE -> pause(runtime, playerId, sourceBaseId, value, true);
            case COMMAND_RESUME -> resume(runtime, playerId, sourceBaseId, value);
            case COMMAND_DELETE -> delete(world, runtime, playerId, sourceBaseId, value);
            default -> false;
        };
        if (changed) {
            refreshStatusesForCurrentSystem(world, runtime);
            world.status = commandStatus(command, value);
        }
        return changed;
    }

    static synchronized void update(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt < 0) return;
        RouteRuntime runtime = STATES.get(world);
        if (runtime == null || runtime.routes.isEmpty()) return;
        String activeSystemId = clean(world.activeSystemId());
        if (activeSystemId.isBlank()) return;

        List<LogisticsRoute> ordered = new ArrayList<>(runtime.routes.values());
        ordered.sort(Comparator.comparingInt((LogisticsRoute route) -> route.priority).reversed()
                .thenComparing(route -> route.id));

        for (LogisticsRoute route : ordered) {
            if (route == null) continue;
            route.phase = route.paused ? RoutePhase.PAUSED : RoutePhase.WAITING;
            if (!route.paused) route.blockedReason = "";
            reconcileAssignments(world, runtime, route, activeSystemId);
            observeDestination(world, route, activeSystemId);
            if (route.paused) {
                stopAssignedInSystem(world, route, activeSystemId);
                continue;
            }
            updateRouteInSystem(world, runtime, route, activeSystemId, dt);
            updateEscortsInSystem(world, route, activeSystemId);
            if (!route.blockedReason.isBlank()) route.phase = RoutePhase.BLOCKED;
        }
        refreshStatusesForCurrentSystem(world, runtime);
    }

    static synchronized boolean ownsTransport(World world, Unit unit) {
        return unit != null && ownsTransport(world, unit.key());
    }

    static synchronized boolean ownsTransport(World world, String unitKey) {
        RouteRuntime runtime = STATES.get(world);
        if (runtime == null || unitKey == null || unitKey.isBlank()) return false;
        for (LogisticsRoute route : runtime.routes.values()) {
            if (route.transportKeys.contains(unitKey)) return true;
        }
        return false;
    }

    static synchronized boolean ownsEscort(World world, String unitKey) {
        RouteRuntime runtime = STATES.get(world);
        if (runtime == null || unitKey == null || unitKey.isBlank()) return false;
        for (LogisticsRoute route : runtime.routes.values()) {
            if (route.escortKeys.contains(unitKey)) return true;
        }
        return false;
    }

    /** Player-issued legacy commands take precedence over route automation. */
    static synchronized void releaseForManualCommand(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return;
        RouteRuntime runtime = STATES.get(world);
        if (runtime == null) return;
        for (LogisticsRoute route : runtime.routes.values()) {
            boolean transport = route.transportKeys.remove(unitKey);
            boolean escort = route.escortKeys.remove(unitKey);
            if (!transport && !escort) continue;
            route.shipCargo.remove(unitKey);
            route.paused = true;
            route.blockedReason = "manual control of " + unitKey;
            route.phase = RoutePhase.PAUSED;
        }
        Unit unit = world.units.get(unitKey);
        if (unit != null && unit.logisticsRequestId.startsWith(UNIT_MARKER)) {
            unit.logisticsRequestId = "";
            unit.logisticsTargetBaseId = "";
        }
        refreshStatusesForCurrentSystem(world, runtime);
    }

    static synchronized Map<String,Object> capture(World world) {
        RouteRuntime runtime = STATES.get(world);
        if (runtime == null) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("nextRouteId", runtime.nextRouteId);
        List<Object> routes = new ArrayList<>();
        for (LogisticsRoute route : runtime.routes.values()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", route.id);
            row.put("ownerId", route.ownerId);
            row.put("sourceSystemId", route.sourceSystemId);
            row.put("sourceBaseId", route.sourceBaseId);
            row.put("destinationSystemId", route.destinationSystemId);
            row.put("destinationBaseId", route.destinationBaseId);
            List<Object> materials = new ArrayList<>();
            for (Material material : route.materials) materials.add(material.name());
            row.put("materials", materials);
            row.put("sourceReserve", route.sourceReserve);
            row.put("destinationTarget", route.destinationTarget);
            row.put("batchSize", route.batchSize);
            row.put("priority", route.priority);
            row.put("paused", route.paused);
            row.put("autoPool", route.autoPool);
            row.put("transportKeys", new ArrayList<>(route.transportKeys));
            row.put("escortKeys", new ArrayList<>(route.escortKeys));
            row.put("phase", route.phase.name());
            row.put("observedDestination", ServerSaveStore.materialMap(route.observedDestination));
            routes.add(row);
        }
        out.put("routes", routes);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        RouteRuntime runtime = new RouteRuntime();
        Map<String,Object> data = ServerSaveStore.object(saved);
        runtime.nextRouteId = Math.max(1, ServerSaveStore.longValue(data, "nextRouteId", 1));
        for (Object item : ServerSaveStore.list(data.get("routes"))) {
            if (runtime.routes.size() >= MAX_ROUTES_PER_PLAYER * 64) break;
            Map<String,Object> row = ServerSaveStore.object(item);
            LogisticsRoute route = restoreRoute(row);
            if (route == null || runtime.routes.containsKey(route.id)) continue;
            route.needsCargoReconcile = true;
            route.destinationObserved = false;
            route.shipCargo.clear();
            runtime.routes.put(route.id, route);
        }
        if (runtime.routes.isEmpty()) STATES.remove(world);
        else STATES.put(world, runtime);
    }

    static synchronized void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    static synchronized void removePlayer(World world, String playerId) {
        RouteRuntime runtime = STATES.get(world);
        if (runtime == null || playerId == null || playerId.isBlank()) return;
        runtime.routes.values().removeIf(route -> playerId.equals(route.ownerId));
        if (runtime.routes.isEmpty()) STATES.remove(world);
    }

    static synchronized int routeCount(World world) {
        RouteRuntime runtime = STATES.get(world);
        return runtime == null ? 0 : runtime.routes.size();
    }

    static synchronized List<RouteView> viewsForSource(World world, Base source) {
        if (source == null) return List.of();
        RouteRuntime runtime = STATES.get(world);
        if (runtime != null) {
            List<RouteView> out = new ArrayList<>();
            String systemId = world == null ? "" : clean(world.activeSystemId());
            for (LogisticsRoute route : runtime.routes.values()) {
                if (!route.ownerId.equals(source.playerId) || !route.sourceBaseId.equals(source.id)
                        || !route.sourceSystemId.equals(systemId)) continue;
                out.add(view(route));
            }
            out.sort(Comparator.comparing(RouteView::id));
            if (!out.isEmpty()) return List.copyOf(out);
        }
        return viewsFromStatus(source.logisticsStatus);
    }

    static List<RouteView> viewsFromStatus(String status) {
        if (status == null) return List.of();
        int marker = status.indexOf(STATUS_MARKER);
        if (marker < 0) return List.of();
        String payload = status.substring(marker + STATUS_MARKER.length()).trim();
        if (payload.isBlank()) return List.of();
        List<RouteView> out = new ArrayList<>();
        for (String raw : payload.split(";\\s*")) {
            RouteView view = parseStatusView(raw.trim());
            if (view != null) out.add(view);
        }
        return List.copyOf(out);
    }

    static String encodeSpec(String routeId, String destinationSystemId, String destinationBaseId,
                             List<Material> materials, double sourceReserve, double destinationTarget,
                             double batchSize, int priority, List<String> transports,
                             List<String> escorts, boolean keepAssignments) {
        String materialText = joinMaterials(materials);
        String transportText = keepAssignments && (transports == null || transports.isEmpty())
                ? "KEEP" : transports == null || transports.isEmpty() ? "AUTO" : String.join(",", transports);
        String escortText = keepAssignments && (escorts == null || escorts.isEmpty())
                ? "KEEP" : escorts == null || escorts.isEmpty() ? "NONE" : String.join(",", escorts);
        return "v1~" + clean(routeId) + '~' + clean(destinationSystemId) + '~' + clean(destinationBaseId)
                + '~' + materialText + '~' + sourceReserve + '~' + destinationTarget + '~' + batchSize
                + '~' + Math.max(0, Math.min(100, priority)) + '~' + transportText + '~' + escortText;
    }

    static boolean isTransport(Unit unit) {
        return unit != null && ("hauler".equals(unit.shipTypeId) || "freighter".equals(unit.shipTypeId));
    }

    static List<String> pathForTest(World world, String fromSystemId, String toSystemId) {
        return path(world, fromSystemId, toSystemId);
    }

    private static boolean create(World world, RouteRuntime runtime, Base source,
                                  String playerId, String encoded) {
        if (countOwned(runtime, playerId) >= MAX_ROUTES_PER_PLAYER) return false;
        RouteSpec spec = parseSpec(encoded);
        if (spec == null || !spec.routeId.isBlank() || spec.keepTransports || spec.keepEscorts) return false;
        String sourceSystemId = clean(world.activeSystemId());
        if (sourceSystemId.isBlank() || sourceSystemId.equals(spec.destinationSystemId)) return false;
        DestinationValidation destination = validateDestination(world, playerId,
                spec.destinationSystemId, spec.destinationBaseId, spec.materials);
        if (!destination.valid) return false;
        if (path(world, sourceSystemId, spec.destinationSystemId).size() < 2) return false;

        String id = "LR" + runtime.nextRouteId++;
        LogisticsRoute route = new LogisticsRoute(id, playerId, sourceSystemId, source.id,
                spec.destinationSystemId, spec.destinationBaseId, spec.materials,
                spec.sourceReserve, spec.destinationTarget, spec.batchSize, spec.priority);
        route.observedDestination.putAll(destination.inventory);
        route.destinationObserved = true;
        if (!applyAssignments(world, runtime, route, spec, false)) return false;
        route.needsCargoReconcile = true;
        runtime.routes.put(route.id, route);
        return true;
    }

    private static boolean updateDefinition(World world, RouteRuntime runtime, Base source,
                                            String playerId, String encoded) {
        RouteSpec spec = parseSpec(encoded);
        if (spec == null || spec.routeId.isBlank()) return false;
        LogisticsRoute route = runtime.routes.get(spec.routeId);
        if (route == null || !route.ownerId.equals(playerId) || !route.sourceBaseId.equals(source.id)
                || !route.sourceSystemId.equals(world.activeSystemId())) return false;
        if (route.sourceSystemId.equals(spec.destinationSystemId)
                || path(world, route.sourceSystemId, spec.destinationSystemId).size() < 2) return false;
        if (totalInTransit(route) > EPSILON) {
            if (!route.destinationSystemId.equals(spec.destinationSystemId)
                    || !route.destinationBaseId.equals(spec.destinationBaseId)
                    || !route.materials.equals(new LinkedHashSet<>(spec.materials))
                    || !spec.keepTransports) return false;
        }
        DestinationValidation destination = validateDestination(world, playerId,
                spec.destinationSystemId, spec.destinationBaseId, spec.materials);
        if (!destination.valid) return false;
        if (!applyAssignments(world, runtime, route, spec, true)) return false;

        route.destinationSystemId = spec.destinationSystemId;
        route.destinationBaseId = spec.destinationBaseId;
        route.materials.clear();
        route.materials.addAll(spec.materials);
        route.sourceReserve = spec.sourceReserve;
        route.destinationTarget = spec.destinationTarget;
        route.batchSize = spec.batchSize;
        route.priority = spec.priority;
        route.observedDestination.clear();
        route.observedDestination.putAll(destination.inventory);
        route.destinationObserved = true;
        route.paused = false;
        route.blockedReason = "";
        route.needsCargoReconcile = true;
        return true;
    }

    private static boolean pause(RouteRuntime runtime, String playerId, String sourceBaseId,
                                 String routeId, boolean explicit) {
        LogisticsRoute route = route(runtime, playerId, sourceBaseId, routeId);
        if (route == null) return false;
        route.paused = true;
        route.phase = RoutePhase.PAUSED;
        route.blockedReason = explicit ? "" : route.blockedReason;
        return true;
    }

    private static boolean resume(RouteRuntime runtime, String playerId, String sourceBaseId, String routeId) {
        LogisticsRoute route = route(runtime, playerId, sourceBaseId, routeId);
        if (route == null) return false;
        route.paused = false;
        route.blockedReason = "";
        route.phase = RoutePhase.WAITING;
        route.needsCargoReconcile = true;
        return true;
    }

    private static boolean delete(World world, RouteRuntime runtime, String playerId,
                                  String sourceBaseId, String routeId) {
        LogisticsRoute route = route(runtime, playerId, sourceBaseId, routeId);
        if (route == null) return false;
        runtime.routes.remove(route.id);
        for (String key : route.transportKeys) {
            String location = world.ownerUnitLocations(route.ownerId).get(key);
            if (!world.activeSystemId().equals(location)) continue;
            Unit unit = world.units.get(key);
            if (unit != null && (UNIT_MARKER + route.id).equals(unit.logisticsRequestId)) {
                unit.logisticsRequestId = "";
                unit.logisticsTargetBaseId = "";
                unit.targetX = unit.x;
                unit.targetY = unit.y;
                if (unit.task == UnitTask.MOVE) unit.task = UnitTask.IDLE;
            }
        }
        return true;
    }

    private static LogisticsRoute route(RouteRuntime runtime, String playerId,
                                        String sourceBaseId, String routeId) {
        String id = clean(routeId);
        LogisticsRoute route = runtime.routes.get(id);
        return route != null && route.ownerId.equals(playerId) && route.sourceBaseId.equals(sourceBaseId)
                ? route : null;
    }

    private static boolean applyAssignments(World world, RouteRuntime runtime, LogisticsRoute route,
                                            RouteSpec spec, boolean updating) {
        LinkedHashSet<String> transports = new LinkedHashSet<>();
        boolean autoPool;
        if (updating && spec.keepTransports) {
            transports.addAll(route.transportKeys);
            autoPool = route.autoPool;
        } else if (spec.autoTransports) {
            autoPool = true;
            Unit auto = autoTransport(world, runtime, route.ownerId, route.id);
            if (auto != null) transports.add(auto.key());
        } else {
            autoPool = false;
            for (String key : spec.transportKeys) {
                Unit unit = world.units.get(key);
                if (!validTransportAssignment(world, runtime, route, unit)) return false;
                transports.add(key);
            }
        }
        if (transports.size() > MAX_TRANSPORTS) return false;

        LinkedHashSet<String> escorts = new LinkedHashSet<>();
        if (updating && spec.keepEscorts) escorts.addAll(route.escortKeys);
        else {
            for (String key : spec.escortKeys) {
                Unit unit = world.units.get(key);
                if (!validEscortAssignment(world, runtime, route, unit, transports)) return false;
                escorts.add(key);
            }
        }
        if (escorts.size() > MAX_ESCORTS) return false;

        route.transportKeys.clear();
        route.transportKeys.addAll(transports);
        route.escortKeys.clear();
        route.escortKeys.addAll(escorts);
        route.autoPool = autoPool;
        return true;
    }

    private static Unit autoTransport(World world, RouteRuntime runtime, String ownerId, String currentRouteId) {
        List<Unit> candidates = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!ownerId.equals(unit.playerId) || unit.hp <= 0 || !isTransport(unit)
                    || unit.cargoUsed() > EPSILON || ProductionSystem.refitReserved(world, unit.key())
                    || assignedElsewhere(runtime, unit.key(), currentRouteId)) continue;
            candidates.add(unit);
        }
        candidates.sort(Comparator.comparingDouble((Unit unit) -> -unit.type().cargoCapacity)
                .thenComparingInt(unit -> unit.unitId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean validTransportAssignment(World world, RouteRuntime runtime,
                                                    LogisticsRoute route, Unit unit) {
        return unit != null && unit.hp > 0 && route.ownerId.equals(unit.playerId) && isTransport(unit)
                && !ProductionSystem.refitReserved(world, unit.key())
                && (unit.cargoUsed() <= EPSILON || route.transportKeys.contains(unit.key()))
                && !assignedElsewhere(runtime, unit.key(), route.id);
    }

    private static boolean validEscortAssignment(World world, RouteRuntime runtime, LogisticsRoute route,
                                                  Unit unit, Set<String> transports) {
        return unit != null && unit.hp > 0 && route.ownerId.equals(unit.playerId)
                && !transports.contains(unit.key()) && WeaponRules.armed(world, unit)
                && !ProductionSystem.refitReserved(world, unit.key())
                && !assignedElsewhere(runtime, unit.key(), route.id);
    }

    private static boolean assignedElsewhere(RouteRuntime runtime, String unitKey, String routeId) {
        for (LogisticsRoute other : runtime.routes.values()) {
            if (other.id.equals(routeId)) continue;
            if (other.transportKeys.contains(unitKey) || other.escortKeys.contains(unitKey)) return true;
        }
        return false;
    }

    private static void reconcileAssignments(World world, RouteRuntime runtime, LogisticsRoute route,
                                             String activeSystemId) {
        Map<String,String> locations = world.ownerUnitLocations(route.ownerId);
        route.transportKeys.removeIf(key -> {
            if (locations.containsKey(key)) return false;
            route.shipCargo.remove(key);
            return true;
        });
        route.escortKeys.removeIf(key -> !locations.containsKey(key));

        if (route.autoPool && route.transportKeys.isEmpty()
                && route.sourceSystemId.equals(activeSystemId)) {
            Unit replacement = autoTransport(world, runtime, route.ownerId, route.id);
            if (replacement != null) route.transportKeys.add(replacement.key());
        }

        if (route.needsCargoReconcile) {
            for (String key : route.transportKeys) {
                if (!activeSystemId.equals(locations.get(key))) continue;
                Unit unit = world.units.get(key);
                if (unit != null) captureCargo(route, unit);
            }
            boolean allObserved = true;
            for (String key : route.transportKeys) {
                if (locations.containsKey(key) && !route.shipCargo.containsKey(key)) {
                    allObserved = false;
                    break;
                }
            }
            if (allObserved) route.needsCargoReconcile = false;
        }
    }

    private static void observeDestination(World world, LogisticsRoute route, String activeSystemId) {
        if (!route.destinationSystemId.equals(activeSystemId)) return;
        Base destination = world.bases.get(route.destinationBaseId);
        if (destination == null || destination.hp <= 0 || !route.ownerId.equals(destination.playerId)) {
            route.destinationObserved = false;
            route.blockedReason = "destination base unavailable";
            return;
        }
        route.observedDestination.clear();
        for (Material material : route.materials) {
            double value = destination.inventory.getOrDefault(material, 0.0);
            if (value > EPSILON) route.observedDestination.put(material, value);
        }
        route.destinationObserved = true;
    }

    private static void updateRouteInSystem(World world, RouteRuntime runtime, LogisticsRoute route,
                                            String activeSystemId, double dt) {
        if (route.transportKeys.isEmpty()) {
            route.blockedReason = route.autoPool ? "waiting for an available Hauler/Freighter" : "no assigned transport";
            return;
        }
        Map<String,String> locations = world.ownerUnitLocations(route.ownerId);
        List<String> keys = new ArrayList<>(route.transportKeys);
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (!activeSystemId.equals(locations.get(key))) continue;
            Unit transport = world.units.get(key);
            if (transport == null || transport.hp <= 0) continue;
            if (ProductionSystem.refitReserved(world, key)) {
                releaseAssigned(route, transport, true, "transport reserved for refit");
                continue;
            }
            if (UnitCommandQueueSystem.hasPlayerIntent(world, transport) || transport.orderType != UnitOrderType.NONE) {
                releaseAssigned(route, transport, true, "manual transport order");
                continue;
            }
            if (!permittedCargoOnly(route, transport)) {
                route.blockedReason = "transport " + key + " contains non-route cargo";
                stop(transport);
                continue;
            }

            transport.logisticsRequestId = UNIT_MARKER + route.id;
            if (transport.cargoUsed() > EPSILON) {
                transport.logisticsTargetBaseId = route.destinationBaseId;
                if (activeSystemId.equals(route.destinationSystemId)) {
                    unload(world, route, transport, dt);
                } else {
                    moveAcrossGalaxy(world, route, transport, activeSystemId,
                            route.destinationSystemId, RoutePhase.OUTBOUND);
                }
            } else {
                transport.logisticsTargetBaseId = route.sourceBaseId;
                if (activeSystemId.equals(route.sourceSystemId)) {
                    loadOrWait(world, runtime, route, transport);
                } else {
                    moveAcrossGalaxy(world, route, transport, activeSystemId,
                            route.sourceSystemId, RoutePhase.RETURNING);
                }
            }
        }
    }

    private static void loadOrWait(World world, RouteRuntime runtime,
                                   LogisticsRoute route, Unit transport) {
        Base source = world.bases.get(route.sourceBaseId);
        if (source == null || source.hp <= 0 || !route.ownerId.equals(source.playerId)) {
            route.blockedReason = "source base unavailable";
            stop(transport);
            return;
        }
        if (!route.destinationObserved || route.needsCargoReconcile) {
            route.phase = maxPhase(route.phase, RoutePhase.WAITING);
            stopNear(source, transport);
            return;
        }
        double dock = Math.max(42, source.type().unloadRange * DOCK_FACTOR);
        if (Calc.distance(transport.x, transport.y, source.x, source.y) > dock) {
            route.phase = maxPhase(route.phase, RoutePhase.LOADING);
            move(transport, source.x, source.y);
            return;
        }

        double remaining = Math.min(transport.freeCargo(), route.batchSize);
        double loaded = 0;
        for (Material material : route.materials) {
            if (remaining <= EPSILON) break;
            double committed = committedToDestination(runtime, route, material);
            double observed = route.observedDestination.getOrDefault(material, 0.0);
            double need = Math.max(0, route.destinationTarget - observed - committed);
            double available = Math.max(0, source.inventory.getOrDefault(material, 0.0) - route.sourceReserve);
            double take = Math.min(remaining, Math.min(need, available));
            if (take <= EPSILON) continue;
            debit(source, material, take);
            transport.addCargo(material, take);
            remaining -= take;
            loaded += take;
        }
        captureCargo(route, transport);
        if (loaded > EPSILON) {
            route.phase = maxPhase(route.phase, RoutePhase.OUTBOUND);
            String next = nextHop(world, route.sourceSystemId, route.destinationSystemId);
            if (next == null) {
                route.blockedReason = "no wormhole path to destination";
                stop(transport);
                return;
            }
            WormholeGate gate = gateTo(world, next);
            if (gate == null) {
                route.blockedReason = "required wormhole gate is unavailable";
                stop(transport);
                return;
            }
            move(transport, gate.x, gate.y);
        } else {
            route.phase = maxPhase(route.phase, RoutePhase.WAITING);
            stopNear(source, transport);
        }
    }

    private static void unload(World world, LogisticsRoute route, Unit transport, double dt) {
        Base destination = world.bases.get(route.destinationBaseId);
        if (destination == null || destination.hp <= 0 || !route.ownerId.equals(destination.playerId)) {
            route.blockedReason = "destination base unavailable";
            stop(transport);
            return;
        }
        double dock = Math.max(42, destination.type().unloadRange * DOCK_FACTOR);
        if (Calc.distance(transport.x, transport.y, destination.x, destination.y) > dock) {
            route.phase = maxPhase(route.phase, RoutePhase.UNLOADING);
            move(transport, destination.x, destination.y);
            return;
        }
        double remaining = Math.max(0, destination.type().unloadRate * Math.max(0, dt));
        if (remaining <= EPSILON && dt > 0) remaining = Math.min(1.0, transport.cargoUsed());
        for (Material material : route.materials) {
            if (remaining <= EPSILON) break;
            double held = transport.inventory.getOrDefault(material, 0.0);
            if (held <= EPSILON) continue;
            double take = Math.min(held, remaining);
            setCargo(transport, material, held - take);
            HangarStore.add(destination.inventory, material, take);
            route.observedDestination.put(material,
                    route.observedDestination.getOrDefault(material, 0.0) + take);
            remaining -= take;
            transport.unloadingThisFrame = true;
        }
        captureCargo(route, transport);
        if (transport.cargoUsed() <= EPSILON) {
            route.phase = maxPhase(route.phase, RoutePhase.RETURNING);
            String next = nextHop(world, route.destinationSystemId, route.sourceSystemId);
            if (next == null) {
                route.blockedReason = "no wormhole path back to source";
                stop(transport);
                return;
            }
            WormholeGate gate = gateTo(world, next);
            if (gate == null) {
                route.blockedReason = "return wormhole gate is unavailable";
                stop(transport);
                return;
            }
            move(transport, gate.x, gate.y);
        } else {
            route.phase = maxPhase(route.phase, RoutePhase.UNLOADING);
        }
    }

    private static void moveAcrossGalaxy(World world, LogisticsRoute route, Unit transport,
                                         String activeSystemId, String targetSystemId,
                                         RoutePhase phase) {
        String next = nextHop(world, activeSystemId, targetSystemId);
        if (next == null) {
            route.blockedReason = "wormhole path unavailable from " + activeSystemId;
            stop(transport);
            return;
        }
        WormholeGate gate = gateTo(world, next);
        if (gate == null || !activeSystemId.equals(gate.fromSystemId)) {
            route.blockedReason = "required wormhole gate is unavailable";
            stop(transport);
            return;
        }
        route.phase = maxPhase(route.phase, phase);
        move(transport, gate.x, gate.y);
    }

    private static void updateEscortsInSystem(World world, LogisticsRoute route, String activeSystemId) {
        if (route.escortKeys.isEmpty()) return;
        Map<String,String> locations = world.ownerUnitLocations(route.ownerId);
        Unit lead = null;
        for (String key : route.transportKeys) {
            if (!activeSystemId.equals(locations.get(key))) continue;
            Unit candidate = world.units.get(key);
            if (candidate != null && candidate.hp > 0) {
                lead = candidate;
                break;
            }
        }
        for (String key : new ArrayList<>(route.escortKeys)) {
            if (!activeSystemId.equals(locations.get(key))) continue;
            Unit escort = world.units.get(key);
            if (escort == null || escort.hp <= 0) continue;
            boolean expectedEscortOrder = lead != null && escort.orderType == UnitOrderType.ESCORT
                    && lead.key().equals(escort.orderTarget);
            if (UnitCommandQueueSystem.hasPlayerIntent(world, escort)
                    || escort.orderType != UnitOrderType.NONE && !expectedEscortOrder) {
                releaseAssigned(route, escort, false, "manual escort order");
                continue;
            }
            if (lead == null) {
                stop(escort);
                continue;
            }

            WormholeGate leadGate = gateNearTarget(world, lead.targetX, lead.targetY);
            if (leadGate != null && Calc.distance(lead.x, lead.y, leadGate.x, leadGate.y) < 900) {
                escort.clearOrder();
                move(escort, leadGate.x, leadGate.y);
                continue;
            }
            if (!expectedEscortOrder) {
                escort.setOrder(new UnitOrderCommand(escort.playerId, escort.unitId, UnitOrderType.ESCORT,
                        escort.x, escort.y, escort.x, escort.y,
                        UnitOrderSystem.defaultRadius(UnitOrderType.ESCORT), lead.key(), 0));
            }
        }
    }

    private static void stopAssignedInSystem(World world, LogisticsRoute route, String activeSystemId) {
        Map<String,String> locations = world.ownerUnitLocations(route.ownerId);
        for (String key : route.transportKeys) {
            if (!activeSystemId.equals(locations.get(key))) continue;
            Unit unit = world.units.get(key);
            if (unit != null) stop(unit);
        }
        for (String key : route.escortKeys) {
            if (!activeSystemId.equals(locations.get(key))) continue;
            Unit unit = world.units.get(key);
            if (unit != null) {
                unit.clearOrder();
                stop(unit);
            }
        }
    }

    private static void releaseAssigned(LogisticsRoute route, Unit unit,
                                        boolean transport, String reason) {
        if (transport) {
            route.transportKeys.remove(unit.key());
            route.shipCargo.remove(unit.key());
        } else route.escortKeys.remove(unit.key());
        route.paused = true;
        route.blockedReason = reason;
        route.phase = RoutePhase.PAUSED;
        if (unit.logisticsRequestId.startsWith(UNIT_MARKER)) {
            unit.logisticsRequestId = "";
            unit.logisticsTargetBaseId = "";
        }
    }

    private static boolean permittedCargoOnly(LogisticsRoute route, Unit unit) {
        for (Map.Entry<Material,Double> entry : unit.inventory.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > EPSILON
                    && !route.materials.contains(entry.getKey())) return false;
        }
        return true;
    }

    private static double committedToDestination(RouteRuntime runtime, LogisticsRoute route,
                                                 Material material) {
        double total = 0;
        for (LogisticsRoute other : runtime.routes.values()) {
            if (!other.ownerId.equals(route.ownerId)
                    || !other.destinationSystemId.equals(route.destinationSystemId)
                    || !other.destinationBaseId.equals(route.destinationBaseId)) continue;
            total += inTransit(other, material);
        }
        return total;
    }

    private static double inTransit(LogisticsRoute route, Material material) {
        double total = 0;
        for (EnumMap<Material,Double> cargo : route.shipCargo.values()) {
            total += cargo.getOrDefault(material, 0.0);
        }
        return total;
    }

    private static double totalInTransit(LogisticsRoute route) {
        double total = 0;
        for (EnumMap<Material,Double> cargo : route.shipCargo.values()) {
            for (double amount : cargo.values()) if (Double.isFinite(amount) && amount > 0) total += amount;
        }
        return total;
    }

    private static void captureCargo(LogisticsRoute route, Unit unit) {
        EnumMap<Material,Double> cargo = new EnumMap<>(Material.class);
        for (Material material : route.materials) {
            double amount = unit.inventory.getOrDefault(material, 0.0);
            if (amount > EPSILON) cargo.put(material, amount);
        }
        route.shipCargo.put(unit.key(), cargo);
    }

    private static void refreshStatusesForCurrentSystem(World world, RouteRuntime runtime) {
        if (world == null || runtime == null) return;
        String systemId = clean(world.activeSystemId());
        Map<String,List<LogisticsRoute>> bySource = new LinkedHashMap<>();
        for (LogisticsRoute route : runtime.routes.values()) {
            if (!route.sourceSystemId.equals(systemId)) continue;
            bySource.computeIfAbsent(route.sourceBaseId, ignored -> new ArrayList<>()).add(route);
        }
        for (Base base : world.bases.values()) {
            String existing = stripRouteStatus(base.logisticsStatus);
            List<LogisticsRoute> routes = bySource.get(base.id);
            if (routes == null || routes.isEmpty()) {
                base.logisticsStatus = existing;
                continue;
            }
            routes.sort(Comparator.comparing(route -> route.id));
            StringBuilder summary = new StringBuilder(STATUS_MARKER);
            for (int i = 0; i < routes.size(); i++) {
                if (i > 0) summary.append("; ");
                summary.append(statusRow(routes.get(i)));
            }
            base.logisticsStatus = existing.isBlank() ? summary.toString() : existing + " | " + summary;
        }
    }

    private static String stripRouteStatus(String status) {
        String value = status == null ? "" : status;
        int marker = value.indexOf(STATUS_MARKER);
        if (marker < 0) return value.trim();
        String head = value.substring(0, marker).trim();
        while (head.endsWith("|") || head.endsWith(";")) head = head.substring(0, head.length() - 1).trim();
        return head;
    }

    private static String statusRow(LogisticsRoute route) {
        return route.id + ' ' + route.phase.name() + " -> " + route.destinationSystemId + '/'
                + route.destinationBaseId + " [materials=" + joinMaterials(new ArrayList<>(route.materials))
                + " reserve=" + compact(route.sourceReserve) + " target=" + compact(route.destinationTarget)
                + " batch=" + compact(route.batchSize) + " priority=" + route.priority
                + " transports=" + route.transportKeys.size() + " escorts=" + route.escortKeys.size() + ']';
    }

    private static RouteView parseStatusView(String row) {
        try {
            int first = row.indexOf(' ');
            int arrow = row.indexOf(" -> ");
            int bracket = row.indexOf(" [", arrow + 4);
            int close = row.lastIndexOf(']');
            if (first <= 0 || arrow <= first || bracket <= arrow || close <= bracket) return null;
            String id = row.substring(0, first).trim();
            RoutePhase phase = RoutePhase.valueOf(row.substring(first + 1, arrow).trim());
            String destination = row.substring(arrow + 4, bracket).trim();
            int slash = destination.indexOf('/');
            if (slash <= 0 || slash + 1 >= destination.length()) return null;
            String systemId = destination.substring(0, slash);
            String baseId = destination.substring(slash + 1);
            Map<String,String> fields = new LinkedHashMap<>();
            for (String token : row.substring(bracket + 2, close).split("\\s+")) {
                int equals = token.indexOf('=');
                if (equals > 0) fields.put(token.substring(0, equals), token.substring(equals + 1));
            }
            List<Material> materials = parseMaterials(fields.get("materials"));
            return new RouteView(id, phase, systemId, baseId, materials,
                    number(fields.get("reserve"), 0), number(fields.get("target"), 0),
                    number(fields.get("batch"), 0), integer(fields.get("priority"), 0),
                    integer(fields.get("transports"), 0), integer(fields.get("escorts"), 0));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static RouteView view(LogisticsRoute route) {
        return new RouteView(route.id, route.phase, route.destinationSystemId, route.destinationBaseId,
                List.copyOf(route.materials), route.sourceReserve, route.destinationTarget,
                route.batchSize, route.priority, route.transportKeys.size(), route.escortKeys.size());
    }

    private static LogisticsRoute restoreRoute(Map<String,Object> row) {
        String id = ServerSaveStore.string(row, "id", "");
        String ownerId = ServerSaveStore.string(row, "ownerId", "");
        String sourceSystemId = ServerSaveStore.string(row, "sourceSystemId", "");
        String sourceBaseId = ServerSaveStore.string(row, "sourceBaseId", "");
        String destinationSystemId = ServerSaveStore.string(row, "destinationSystemId", "");
        String destinationBaseId = ServerSaveStore.string(row, "destinationBaseId", "");
        if (!validToken(id, 128) || !validToken(ownerId, 64)
                || !validToken(sourceSystemId, 128) || !validToken(sourceBaseId, 128)
                || !validToken(destinationSystemId, 128) || !validToken(destinationBaseId, 128)) return null;
        List<Material> materials = new ArrayList<>();
        for (Object item : ServerSaveStore.list(row.get("materials"))) {
            if (materials.size() >= MAX_MATERIALS) break;
            try {
                Material material = Material.valueOf(String.valueOf(item));
                if (!materials.contains(material)) materials.add(material);
            } catch (RuntimeException ignored) { }
        }
        if (materials.isEmpty()) return null;
        double reserve = boundedNumber(ServerSaveStore.doubleValue(row, "sourceReserve", 0), 0, 1_000_000);
        double target = boundedNumber(ServerSaveStore.doubleValue(row, "destinationTarget", 0), EPSILON, 1_000_000);
        double batch = boundedNumber(ServerSaveStore.doubleValue(row, "batchSize", 1), EPSILON, 1_000_000);
        int priority = Math.max(0, Math.min(100, ServerSaveStore.intValue(row, "priority", 0)));
        LogisticsRoute route = new LogisticsRoute(id, ownerId, sourceSystemId, sourceBaseId,
                destinationSystemId, destinationBaseId, materials, reserve, target, batch, priority);
        route.paused = ServerSaveStore.boolValue(row, "paused", false);
        route.autoPool = ServerSaveStore.boolValue(row, "autoPool", false);
        route.phase = ServerSaveStore.enumValue(RoutePhase.class, row.get("phase"), RoutePhase.WAITING);
        addKeys(route.transportKeys, row.get("transportKeys"), MAX_TRANSPORTS);
        addKeys(route.escortKeys, row.get("escortKeys"), MAX_ESCORTS);
        route.observedDestination.putAll(ServerSaveStore.restoreMaterialMap(row.get("observedDestination")));
        route.observedDestination.keySet().retainAll(route.materials);
        return route;
    }

    private static RouteSpec parseSpec(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_COMMAND_CHARS) return null;
        String[] parts = encoded.split("~", -1);
        if (parts.length != 11 || !"v1".equals(parts[0])) return null;
        String routeId = clean(parts[1]);
        String destinationSystemId = clean(parts[2]);
        String destinationBaseId = clean(parts[3]);
        if (!routeId.isBlank() && !validToken(routeId, 128)
                || !validToken(destinationSystemId, 128) || !validToken(destinationBaseId, 128)) return null;
        List<Material> materials = parseMaterials(parts[4]);
        if (materials.isEmpty() || materials.size() > MAX_MATERIALS) return null;
        double reserve = number(parts[5], Double.NaN);
        double target = number(parts[6], Double.NaN);
        double batch = number(parts[7], Double.NaN);
        int priority = integer(parts[8], -1);
        if (!Double.isFinite(reserve) || reserve < 0 || reserve > 1_000_000
                || !Double.isFinite(target) || target <= EPSILON || target > 1_000_000
                || !Double.isFinite(batch) || batch <= EPSILON || batch > 1_000_000
                || priority < 0 || priority > 100) return null;

        boolean keepTransports = "KEEP".equalsIgnoreCase(parts[9]);
        boolean autoTransports = "AUTO".equalsIgnoreCase(parts[9]);
        List<String> transports = keepTransports || autoTransports ? List.of()
                : parseKeys(parts[9], MAX_TRANSPORTS);
        if (!keepTransports && !autoTransports && transports.isEmpty()) return null;
        boolean keepEscorts = "KEEP".equalsIgnoreCase(parts[10]);
        List<String> escorts = keepEscorts || "NONE".equalsIgnoreCase(parts[10]) || parts[10].isBlank()
                ? List.of() : parseKeys(parts[10], MAX_ESCORTS);
        if (!keepEscorts && !"NONE".equalsIgnoreCase(parts[10]) && !parts[10].isBlank()
                && escorts.isEmpty()) return null;
        return new RouteSpec(routeId, destinationSystemId, destinationBaseId, materials,
                reserve, target, batch, priority, transports, escorts,
                keepTransports, keepEscorts, autoTransports);
    }

    private static DestinationValidation validateDestination(World world, String playerId,
                                                             String systemId, String baseId,
                                                             List<Material> materials) {
        if (world == null || materials == null || materials.isEmpty()) return DestinationValidation.INVALID;
        String previous = world.activeSystemId();
        if (!systemExists(world, systemId)) return DestinationValidation.INVALID;
        try {
            world.activateSystem(systemId);
            if (!systemId.equals(world.activeSystemId())) return DestinationValidation.INVALID;
            Base destination = world.bases.get(baseId);
            if (destination == null || destination.hp <= 0 || !playerId.equals(destination.playerId)) {
                return DestinationValidation.INVALID;
            }
            EnumMap<Material,Double> inventory = new EnumMap<>(Material.class);
            for (Material material : materials) {
                double amount = destination.inventory.getOrDefault(material, 0.0);
                if (amount > EPSILON) inventory.put(material, amount);
            }
            return new DestinationValidation(true, inventory);
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
        }
    }

    private static boolean systemExists(World world, String systemId) {
        if (!validToken(systemId, 128)) return false;
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        for (GalaxyMapSystem system : snapshot.systems()) if (systemId.equals(system.id())) return true;
        return false;
    }

    private static List<String> path(World world, String fromSystemId, String toSystemId) {
        String from = clean(fromSystemId);
        String to = clean(toSystemId);
        if (world == null || from.isBlank() || to.isBlank()) return List.of();
        if (from.equals(to)) return List.of(from);
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        Map<String,LinkedHashSet<String>> adjacency = new LinkedHashMap<>();
        for (GalaxyMapSystem system : snapshot.systems()) adjacency.put(system.id(), new LinkedHashSet<>());
        for (GalaxyMapLink link : snapshot.links()) {
            if (link == null || !adjacency.containsKey(link.fromSystemId())
                    || !adjacency.containsKey(link.toSystemId())) continue;
            adjacency.get(link.fromSystemId()).add(link.toSystemId());
            adjacency.get(link.toSystemId()).add(link.fromSystemId());
        }
        if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) return List.of();
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String,String> previous = new LinkedHashMap<>();
        queue.add(from);
        previous.put(from, "");
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            List<String> neighbors = new ArrayList<>(adjacency.getOrDefault(current, new LinkedHashSet<>()));
            neighbors.sort(String::compareTo);
            for (String neighbor : neighbors) {
                if (previous.containsKey(neighbor)) continue;
                previous.put(neighbor, current);
                if (neighbor.equals(to)) {
                    queue.clear();
                    break;
                }
                queue.addLast(neighbor);
            }
        }
        if (!previous.containsKey(to)) return List.of();
        ArrayDeque<String> reversed = new ArrayDeque<>();
        String cursor = to;
        while (!cursor.isBlank()) {
            reversed.addFirst(cursor);
            cursor = previous.getOrDefault(cursor, "");
        }
        return List.copyOf(reversed);
    }

    private static String nextHop(World world, String fromSystemId, String toSystemId) {
        List<String> path = path(world, fromSystemId, toSystemId);
        return path.size() < 2 ? null : path.get(1);
    }

    private static WormholeGate gateTo(World world, String destinationSystemId) {
        if (world == null || destinationSystemId == null) return null;
        for (WormholeGate gate : world.wormholes) {
            if (destinationSystemId.equals(gate.toSystemId)) return gate;
        }
        return null;
    }

    private static WormholeGate gateNearTarget(World world, double x, double y) {
        if (world == null) return null;
        for (WormholeGate gate : world.wormholes) {
            if (Calc.distance(x, y, gate.x, gate.y) < 4) return gate;
        }
        return null;
    }

    private static void move(Unit unit, double x, double y) {
        unit.clearOrder();
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.moveTo(x, y);
    }

    private static void stop(Unit unit) {
        unit.attackTarget = "";
        unit.automationResourceId = -1;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
        if (unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION) unit.task = UnitTask.IDLE;
    }

    private static void stopNear(Base base, Unit transport) {
        if (base == null || transport == null) return;
        double dock = Math.max(42, base.type().unloadRange * DOCK_FACTOR);
        if (Calc.distance(transport.x, transport.y, base.x, base.y) > dock) move(transport, base.x, base.y);
        else stop(transport);
    }

    private static void debit(Base source, Material material, double amount) {
        double next = source.inventory.getOrDefault(material, 0.0) - amount;
        if (next <= EPSILON) source.inventory.remove(material);
        else source.inventory.put(material, next);
    }

    private static void setCargo(Unit unit, Material material, double amount) {
        if (amount <= EPSILON) unit.inventory.remove(material);
        else unit.inventory.put(material, amount);
    }

    private static RoutePhase maxPhase(RoutePhase current, RoutePhase candidate) {
        return phaseWeight(candidate) > phaseWeight(current) ? candidate : current;
    }

    private static int phaseWeight(RoutePhase phase) {
        if (phase == null) return 0;
        return switch (phase) {
            case WAITING -> 0;
            case LOADING -> 1;
            case RETURNING -> 2;
            case OUTBOUND -> 3;
            case UNLOADING -> 4;
            case BLOCKED -> 5;
            case PAUSED -> 6;
        };
    }

    private static int countOwned(RouteRuntime runtime, String playerId) {
        int count = 0;
        for (LogisticsRoute route : runtime.routes.values()) if (route.ownerId.equals(playerId)) count++;
        return count;
    }

    private static void addKeys(Set<String> target, Object saved, int limit) {
        for (Object item : ServerSaveStore.list(saved)) {
            if (target.size() >= limit) break;
            String key = clean(String.valueOf(item));
            if (validToken(key, 160)) target.add(key);
        }
    }

    private static List<String> parseKeys(String text, int limit) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : text.split(",")) {
            if (out.size() >= limit) return List.of();
            String key = clean(raw);
            if (!validToken(key, 160)) return List.of();
            out.add(key);
        }
        return List.copyOf(out);
    }

    private static List<Material> parseMaterials(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<Material> out = new LinkedHashSet<>();
        for (String raw : text.split(",")) {
            if (out.size() >= MAX_MATERIALS) return List.of();
            try { out.add(Material.valueOf(raw.trim().toUpperCase(Locale.ROOT))); }
            catch (RuntimeException ignored) { return List.of(); }
        }
        return List.copyOf(out);
    }

    private static String joinMaterials(List<Material> materials) {
        if (materials == null || materials.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Material material : materials) {
            if (material == null || count++ >= MAX_MATERIALS) continue;
            if (!out.isEmpty()) out.append(',');
            out.append(material.name());
        }
        return out.toString();
    }

    private static boolean validToken(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('|') >= 0
                || value.indexOf('~') >= 0 || value.indexOf(',') >= 0) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    private static String commandStatus(String command, String value) {
        String id = clean(value);
        return switch (command) {
            case COMMAND_CREATE -> "Inter-system logistics route created.";
            case COMMAND_UPDATE -> "Inter-system logistics route updated.";
            case COMMAND_PAUSE -> "Logistics route " + id + " paused.";
            case COMMAND_RESUME -> "Logistics route " + id + " resumed.";
            case COMMAND_DELETE -> "Logistics route " + id + " deleted.";
            default -> "Logistics routes updated.";
        };
    }

    private static String compact(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static double number(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double boundedNumber(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static RouteRuntime state(World world) {
        return STATES.computeIfAbsent(world, ignored -> new RouteRuntime());
    }

    private static final class RouteRuntime {
        final Map<String,LogisticsRoute> routes = new LinkedHashMap<>();
        long nextRouteId = 1;
    }

    private static final class LogisticsRoute {
        final String id;
        final String ownerId;
        final String sourceSystemId;
        final String sourceBaseId;
        String destinationSystemId;
        String destinationBaseId;
        final LinkedHashSet<Material> materials = new LinkedHashSet<>();
        double sourceReserve;
        double destinationTarget;
        double batchSize;
        int priority;
        boolean paused;
        boolean autoPool;
        boolean needsCargoReconcile = true;
        boolean destinationObserved;
        RoutePhase phase = RoutePhase.WAITING;
        String blockedReason = "";
        final LinkedHashSet<String> transportKeys = new LinkedHashSet<>();
        final LinkedHashSet<String> escortKeys = new LinkedHashSet<>();
        final EnumMap<Material,Double> observedDestination = new EnumMap<>(Material.class);
        final Map<String,EnumMap<Material,Double>> shipCargo = new LinkedHashMap<>();

        LogisticsRoute(String id, String ownerId, String sourceSystemId, String sourceBaseId,
                       String destinationSystemId, String destinationBaseId, List<Material> materials,
                       double sourceReserve, double destinationTarget, double batchSize, int priority) {
            this.id = id;
            this.ownerId = ownerId;
            this.sourceSystemId = sourceSystemId;
            this.sourceBaseId = sourceBaseId;
            this.destinationSystemId = destinationSystemId;
            this.destinationBaseId = destinationBaseId;
            this.materials.addAll(materials);
            this.sourceReserve = sourceReserve;
            this.destinationTarget = destinationTarget;
            this.batchSize = batchSize;
            this.priority = priority;
        }
    }

    private record RouteSpec(String routeId, String destinationSystemId, String destinationBaseId,
                             List<Material> materials, double sourceReserve, double destinationTarget,
                             double batchSize, int priority, List<String> transportKeys,
                             List<String> escortKeys, boolean keepTransports, boolean keepEscorts,
                             boolean autoTransports) { }

    private record DestinationValidation(boolean valid, EnumMap<Material,Double> inventory) {
        private static final DestinationValidation INVALID =
                new DestinationValidation(false, new EnumMap<>(Material.class));
    }
}
