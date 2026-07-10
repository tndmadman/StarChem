package com.tndmadman.rts;

import java.util.*;

final class LogisticsSystem {
    static final String SHUTTLE_TYPE = "logistics_shuttle";
    private static final double DOCK_RANGE_FACTOR = 0.42;
    private static final double CLOSEST_SOURCE_BIAS = 1.5;
    private static final double RECHECK_INTERVAL = 2.0;
    private final List<LogisticsRequest> requests = new ArrayList<>();
    private long nextRequestId = 1;

    boolean queueBuildShip(World world, Base target, ShipType ship) {
        return queue(world, target, LogisticsJobKind.SHIP, ship.id, ship.name, ship.buildCost);
    }

    boolean queueBasePackage(World world, Base target, BaseType station) {
        return queue(world, target, LogisticsJobKind.STATION_PACKAGE, station.id, station.name + " package", station.buildCost);
    }

    boolean queueCraftable(World world, Base target, CraftableItem item) {
        return queue(world, target, LogisticsJobKind.CRAFTABLE, item.id, item.name, item.requiredResources);
    }

    boolean queueResearch(World world, Base target, ResearchTopic topic) {
        return queue(world, target, LogisticsJobKind.RESEARCH, topic.id, topic.name + " research", topic.requiredResources);
    }

    void update(World world, double dt) {
        deliverActiveShuttles(world);
        cleanupDeadRequests(world);
        recheckWaitingRequests(world, dt);
        updateBaseStatuses(world);
        completeReadyRequests(world);
    }

    private boolean queue(World world, Base target, LogisticsJobKind kind, String itemId, String itemName, List<Cost> cost) {
        if (target == null || cost.isEmpty()) return false;
        LogisticsRequest queued = queuedRequest(target, kind, itemId);
        if (queued != null) {
            target.logisticsStatus = waitLabel(queued, 1);
            world.status = "Already waiting on resources for " + queued.itemName + ".";
            return true;
        }
        if (!Rules.SHIPS.containsKey(SHUTTLE_TYPE)) return false;
        List<Cost> missing = missingAt(target, cost);
        if (missing.isEmpty() || !availableHangarsCanCover(world, target, missing)) return false;

        LogisticsRequest request = new LogisticsRequest("LR" + nextRequestId++, target.playerId, target.id, kind, itemId, itemName, cost, missing);
        requests.add(request);
        for (Cost need : missing) dispatchMaterial(world, target, request, need.material(), need.amount());
        target.logisticsStatus = waitLabel(request, 1);
        world.status = "Queued " + itemName + " - waiting on resources from other hangars.";
        return true;
    }

    private LogisticsRequest queuedRequest(Base target, LogisticsJobKind kind, String itemId) {
        for (LogisticsRequest request : requests) {
            if (request.targetBaseId.equals(target.id) && request.kind == kind && request.itemId.equals(itemId)) return request;
        }
        return null;
    }

    private List<Cost> missingAt(Base base, List<Cost> cost) {
        List<Cost> out = new ArrayList<>();
        for (Cost c : cost) {
            double missing = c.amount() - base.inventory.getOrDefault(c.material(), 0.0);
            if (missing > 0.05) out.add(new Cost(c.material(), missing));
        }
        return out;
    }

    private boolean availableHangarsCanCover(World world, Base target, List<Cost> missing) {
        for (Cost need : missing) {
            double available = 0;
            for (Base source : sourceHangars(world, target, need.material())) {
                available += source.inventory.getOrDefault(need.material(), 0.0);
                if (available + 0.001 >= need.amount()) break;
            }
            if (available + 0.001 < need.amount()) return false;
        }
        return true;
    }

    private List<Base> sourceHangars(World world, Base target, Material material) {
        List<Base> out = new ArrayList<>();
        for (Base source : world.bases.values()) {
            if (source == target || !source.playerId.equals(target.playerId)) continue;
            if (source.inventory.getOrDefault(material, 0.0) <= 0.05) continue;
            out.add(source);
        }
        out.sort(Comparator.comparingDouble(source -> Calc.distance(source.x, source.y, target.x, target.y)));
        return out;
    }

    private void dispatchMaterial(World world, Base target, LogisticsRequest request, Material material, double amount) {
        double remaining = amount;
        double shuttleCapacity = Math.max(1, Rules.ship(SHUTTLE_TYPE).cargoCapacity);
        List<Base> sources = sourceHangars(world, target, material);
        while (remaining > 0.05) {
            List<Base> active = activeSources(sources, material);
            if (active.isEmpty()) return;
            boolean sentAny = false;
            for (int i = 0; i < active.size() && remaining > 0.05; i++) {
                Base source = active.get(i);
                int sourcesLeft = active.size() - i;
                double share = remaining / Math.max(1, sourcesLeft);
                if (i == 0 && active.size() > 1) share *= CLOSEST_SOURCE_BIAS;
                double sent = dispatchFromSource(world, source, target, request, material, Math.min(remaining, share), shuttleCapacity);
                remaining -= sent;
                sentAny |= sent > 0.05;
            }
            if (!sentAny) return;
        }
    }

    private List<Base> activeSources(List<Base> sources, Material material) {
        List<Base> out = new ArrayList<>();
        for (Base source : sources) if (source.inventory.getOrDefault(material, 0.0) > 0.05) out.add(source);
        return out;
    }

    private double dispatchFromSource(World world, Base source, Base target, LogisticsRequest request, Material material, double requested, double shuttleCapacity) {
        double remaining = Math.min(requested, source.inventory.getOrDefault(material, 0.0));
        double sent = 0;
        while (remaining > 0.05) {
            double take = Math.min(shuttleCapacity, remaining);
            debit(source, material, take);
            request.dispatched(material, take);
            Unit shuttle = new Unit(source.playerId, nextUnitId(world, source.playerId), SHUTTLE_TYPE, undockX(source, target), undockY(source, target));
            shuttle.logisticsTargetBaseId = target.id;
            shuttle.logisticsRequestId = request.id;
            shuttle.addCargo(material, take);
            moveToward(shuttle, target);
            world.units.put(shuttle.key(), shuttle);
            sent += take;
            remaining -= take;
        }
        return sent;
    }

    private void deliverActiveShuttles(World world) {
        Iterator<Unit> it = world.units.values().iterator();
        while (it.hasNext()) {
            Unit shuttle = it.next();
            if (!SHUTTLE_TYPE.equals(shuttle.shipTypeId) || shuttle.logisticsTargetBaseId.isBlank()) continue;
            Base target = world.bases.get(shuttle.logisticsTargetBaseId);
            if (target == null || shuttle.cargoUsed() <= 0.05) {
                it.remove();
                continue;
            }
            moveToward(shuttle, target);
            double dockRange = Math.max(42, target.type().unloadRange * DOCK_RANGE_FACTOR);
            if (Calc.distance(shuttle.x, shuttle.y, target.x, target.y) > dockRange) continue;

            LogisticsRequest request = requestById(shuttle.logisticsRequestId);
            for (Material material : Material.values()) {
                double amount = shuttle.inventory.getOrDefault(material, 0.0);
                if (amount <= 0.05) continue;
                HangarStore.add(target.inventory, material, amount);
                if (request != null) request.delivered(material, amount);
            }
            it.remove();
            if (PlayerRegistry.isLocal(target.playerId)) world.status = "Logistics shuttle docked at " + target.type().name + ".";
        }
    }

    private void recheckWaitingRequests(World world, double dt) {
        for (LogisticsRequest request : requests) {
            Base target = world.bases.get(request.targetBaseId);
            if (target == null) continue;
            request.recheckTimer += dt;
            if (request.recheckTimer < RECHECK_INTERVAL) continue;
            request.recheckTimer = 0;
            request.refreshAgainst(target);
            if (request.ready()) continue;
            for (Material material : Material.values()) {
                double amount = request.undispatchedAmount(material);
                if (amount > 0.05) dispatchMaterial(world, target, request, material, amount);
            }
        }
    }

    private void completeReadyRequests(World world) {
        Iterator<LogisticsRequest> it = requests.iterator();
        while (it.hasNext()) {
            LogisticsRequest request = it.next();
            Base target = world.bases.get(request.targetBaseId);
            if (target == null) {
                it.remove();
                continue;
            }
            if (!request.ready()) continue;
            if (!HangarStore.canAfford(target.inventory, request.cost)) {
                target.logisticsStatus = "Resources delivered; still short: " + request.itemName;
                continue;
            }
            if (!finish(world, target, request)) continue;
            target.logisticsStatus = "";
            it.remove();
        }
    }

    private boolean finish(World world, Base target, LogisticsRequest request) {
        return switch (request.kind) {
            case SHIP -> finishShip(world, target, request);
            case STATION_PACKAGE -> finishStationPackage(world, target, request);
            case CRAFTABLE -> finishCraftable(world, target, request);
            case RESEARCH -> finishResearch(world, target, request);
        };
    }

    private boolean finishShip(World world, Base target, LogisticsRequest request) {
        ShipType ship = Rules.ship(request.itemId);
        boolean queued = ProductionSystem.enqueueShip(world, target, ship, false);
        if (queued) world.status = "Logistics delivered resources. Queued " + ship.name + ".";
        return queued;
    }

    private boolean finishStationPackage(World world, Base target, LogisticsRequest request) {
        BaseType station = Rules.base(request.itemId);
        boolean queued = ProductionSystem.enqueuePackage(world, target, station, false);
        if (queued) world.status = "Logistics delivered resources. Queued " + station.name + " package.";
        else target.logisticsStatus = "Resources ready; " + world.status;
        return queued;
    }

    private boolean finishCraftable(World world, Base target, LogisticsRequest request) {
        CraftableItem item = CraftingRules.item(request.itemId);
        if (item == null) {
            world.status = "Logistics request failed: unknown craftable " + request.itemId + ".";
            return true;
        }
        boolean queued = ProductionSystem.enqueueCraftable(world, target, item, false);
        if (queued) world.status = "Logistics delivered resources. Queued " + item.name + ".";
        return queued;
    }

    private boolean finishResearch(World world, Base target, LogisticsRequest request) {
        ResearchTopic topic = ResearchRules.topic(request.itemId);
        if (topic == null) {
            world.status = "Logistics request failed: unknown research " + request.itemId + ".";
            return true;
        }
        if (world.hasResearch(target.playerId, topic.id) || ProductionSystem.researchQueued(world, target.playerId, topic.id)) return true;
        boolean queued = ProductionSystem.enqueueResearch(world, target, topic, false);
        if (queued) world.status = "Logistics delivered resources. Queued " + topic.name + " research.";
        return queued;
    }

    private void cleanupDeadRequests(World world) {
        requests.removeIf(request -> !world.bases.containsKey(request.targetBaseId));
        Set<String> liveIds = new HashSet<>();
        for (LogisticsRequest request : requests) liveIds.add(request.targetBaseId);
        for (Base base : world.bases.values()) if (!liveIds.contains(base.id) && base.logisticsStatus.startsWith("Waiting on resources")) base.logisticsStatus = "";
    }

    private void updateBaseStatuses(World world) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, LogisticsRequest> first = new HashMap<>();
        for (LogisticsRequest request : requests) {
            counts.put(request.targetBaseId, counts.getOrDefault(request.targetBaseId, 0) + 1);
            first.putIfAbsent(request.targetBaseId, request);
        }
        for (Map.Entry<String, LogisticsRequest> e : first.entrySet()) {
            Base base = world.bases.get(e.getKey());
            if (base == null) continue;
            base.logisticsStatus = waitLabel(e.getValue(), counts.getOrDefault(e.getKey(), 1));
        }
    }

    private String waitLabel(LogisticsRequest request, int count) {
        String prefix = request.ready() ? "Resources delivered: " : "Waiting on resources from other hangars: ";
        String suffix = count > 1 ? " +" + (count - 1) + " queued" : "";
        return prefix + request.itemName + suffix;
    }

    private LogisticsRequest requestById(String id) {
        for (LogisticsRequest request : requests) if (request.id.equals(id)) return request;
        return null;
    }

    private void moveToward(Unit shuttle, Base target) {
        shuttle.task = UnitTask.MOVE;
        shuttle.attackTarget = "";
        shuttle.automationResourceId = -1;
        shuttle.targetX = target.x;
        shuttle.targetY = target.y;
    }

    private double undockX(Base source, Base target) {
        double a = Math.atan2(target.y - source.y, target.x - source.x);
        return source.x + Math.cos(a) * Math.max(58, source.type().buildRadius * 0.8);
    }

    private double undockY(Base source, Base target) {
        double a = Math.atan2(target.y - source.y, target.x - source.x);
        return source.y + Math.sin(a) * Math.max(58, source.type().buildRadius * 0.8);
    }

    private void debit(Base source, Material material, double amount) {
        double next = source.inventory.getOrDefault(material, 0.0) - amount;
        if (next <= 0.05) source.inventory.remove(material);
        else source.inventory.put(material, next);
    }

    private int nextUnitId(World world, String playerId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (unit.playerId.equals(playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
    }
}

enum LogisticsJobKind { SHIP, STATION_PACKAGE, CRAFTABLE, RESEARCH }

final class LogisticsRequest {
    final String id, playerId, targetBaseId, itemId, itemName;
    final LogisticsJobKind kind;
    final List<Cost> cost;
    final EnumMap<Material, Double> awaiting = new EnumMap<>(Material.class);
    final EnumMap<Material, Double> inTransit = new EnumMap<>(Material.class);
    double recheckTimer = 0;

    LogisticsRequest(String id, String playerId, String targetBaseId, LogisticsJobKind kind, String itemId, String itemName, List<Cost> cost, List<Cost> missing) {
        this.id = id;
        this.playerId = playerId;
        this.targetBaseId = targetBaseId;
        this.kind = kind;
        this.itemId = itemId;
        this.itemName = itemName;
        this.cost = List.copyOf(cost);
        for (Cost c : missing) awaiting.put(c.material(), c.amount());
    }

    void refreshAgainst(Base target) {
        for (Cost c : cost) {
            double missing = c.amount() - target.inventory.getOrDefault(c.material(), 0.0);
            if (missing <= 0.05) awaiting.remove(c.material());
            else awaiting.put(c.material(), missing);
        }
    }

    void dispatched(Material material, double amount) {
        inTransit.put(material, inTransit.getOrDefault(material, 0.0) + amount);
    }

    void delivered(Material material, double amount) {
        double nextAwaiting = awaiting.getOrDefault(material, 0.0) - amount;
        if (nextAwaiting <= 0.05) awaiting.remove(material);
        else awaiting.put(material, nextAwaiting);

        double nextInTransit = inTransit.getOrDefault(material, 0.0) - amount;
        if (nextInTransit <= 0.05) inTransit.remove(material);
        else inTransit.put(material, nextInTransit);
    }

    double undispatchedAmount(Material material) {
        return Math.max(0, awaiting.getOrDefault(material, 0.0) - inTransit.getOrDefault(material, 0.0));
    }

    boolean ready() { return awaiting.isEmpty(); }
}
