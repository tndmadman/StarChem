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
        return queue(world, target, ProductionJobKind.SHIP, ship.id, ship.name, ship.buildCost, ship.buildTimeSeconds);
    }

    boolean queueBasePackage(World world, Base target, BaseType station) {
        return queue(world, target, ProductionJobKind.STATION_PACKAGE, station.id, station.name + " package",
                station.buildCost, station.buildTimeSeconds);
    }

    boolean queueCraftable(World world, Base target, CraftableItem item) {
        return queue(world, target, ProductionJobKind.CRAFTABLE, item.id, item.name,
                item.requiredResources, item.timeSeconds);
    }

    boolean queueResearch(World world, Base target, ResearchTopic topic) {
        return queue(world, target, ProductionJobKind.RESEARCH, topic.id, topic.name + " research",
                topic.requiredResources, topic.timeSeconds);
    }

    void update(World world, double dt) {
        if (world == null) return;
        deliverActiveShuttles(world);
        reconcileInTransit(world);
        cleanupDeadRequests(world);
        recheckWaitingRequests(world, dt);
        completeReadyRequests(world);
        updateBaseStatuses(world);
    }

    private boolean queue(World world, Base target, ProductionJobKind kind, String itemId, String itemName,
                          List<Cost> cost, double duration) {
        if (world == null || target == null || cost.isEmpty()) return false;
        if (!Rules.SHIPS.containsKey(SHUTTLE_TYPE)) return false;
        List<Cost> missing = missingAt(target, cost);
        if (missing.isEmpty() || !availableHangarsCanCover(world, target, missing)) return false;

        ProductionJob job = ProductionSystem.enqueueWaiting(world, target, kind, itemId, itemName, duration);
        if (job == null) return false;
        LogisticsRequest request = new LogisticsRequest("LR" + nextRequestId++, world.activeSystemId(),
                target.playerId, target.id, target, job.id, itemName, cost);
        request.reserveAvailable(target);
        requests.add(request);
        dispatchOutstanding(world, target, request);
        target.logisticsStatus = waitLabel(request, requestCount(world, target));
        return true;
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

    private void dispatchOutstanding(World world, Base target, LogisticsRequest request) {
        for (Material material : Material.values()) {
            double amount = request.undispatchedAmount(material);
            if (amount > 0.05) dispatchMaterial(world, target, request, material, amount);
        }
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
                double sent = dispatchFromSource(world, source, target, request, material,
                        Math.min(remaining, share), shuttleCapacity);
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

    private double dispatchFromSource(World world, Base source, Base target, LogisticsRequest request,
                                      Material material, double requested, double shuttleCapacity) {
        double remaining = Math.min(requested, source.inventory.getOrDefault(material, 0.0));
        double sent = 0;
        while (remaining > 0.05) {
            double take = Math.min(shuttleCapacity, remaining);
            debit(source, material, take);
            request.dispatched(material, take);
            Unit shuttle = new Unit(source.playerId, nextUnitId(world, source.playerId), SHUTTLE_TYPE,
                    undockX(source, target), undockY(source, target));
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
            if (!SHUTTLE_TYPE.equals(shuttle.shipTypeId)) continue;
            if (shuttle.cargoUsed() <= 0.05) {
                it.remove();
                continue;
            }

            LogisticsRequest request = requestById(shuttle.logisticsRequestId);
            boolean linked = request != null && request.matches(world.activeSystemId(), shuttle.logisticsTargetBaseId);
            if (request != null && !linked) {
                rerouteOrphan(world, shuttle);
                continue;
            }

            Base target = world.bases.get(shuttle.logisticsTargetBaseId);
            if (target == null) {
                rerouteOrphan(world, shuttle);
                continue;
            }
            moveToward(shuttle, target);
            double dockRange = Math.max(42, target.type().unloadRange * DOCK_RANGE_FACTOR);
            if (Calc.distance(shuttle.x, shuttle.y, target.x, target.y) > dockRange) continue;

            for (Material material : Material.values()) {
                double amount = shuttle.inventory.getOrDefault(material, 0.0);
                if (amount <= 0.05) continue;
                if (linked) {
                    double overflow = request.delivered(material, amount);
                    if (overflow > 0.001) HangarStore.add(target.inventory, material, overflow);
                } else HangarStore.add(target.inventory, material, amount);
            }
            it.remove();
            if (PlayerRegistry.isLocal(target.playerId)) world.status = "Logistics shuttle docked at " + target.type().name + ".";
        }
    }

    private void rerouteOrphan(World world, Unit shuttle) {
        Base fallback = world.nearestBase(shuttle.playerId, shuttle.x, shuttle.y);
        shuttle.logisticsRequestId = "";
        if (fallback == null) {
            shuttle.logisticsTargetBaseId = "";
            shuttle.task = UnitTask.IDLE;
            return;
        }
        shuttle.logisticsTargetBaseId = fallback.id;
        moveToward(shuttle, fallback);
    }

    private void reconcileInTransit(World world) {
        String systemId = world.activeSystemId();
        for (LogisticsRequest request : requests) if (request.inSystem(systemId)) request.clearInTransit();
        for (Unit shuttle : world.units.values()) {
            if (!SHUTTLE_TYPE.equals(shuttle.shipTypeId)) continue;
            LogisticsRequest request = requestById(shuttle.logisticsRequestId);
            if (request == null || !request.matches(systemId, shuttle.logisticsTargetBaseId)) continue;
            request.trackInTransit(shuttle.inventory);
        }
    }

    private void recheckWaitingRequests(World world, double dt) {
        String systemId = world.activeSystemId();
        for (LogisticsRequest request : requests) {
            if (!request.inSystem(systemId)) continue;
            Base target = world.bases.get(request.targetBaseId);
            if (target == null) continue;
            request.recheckTimer += Math.max(0, dt);
            if (request.recheckTimer < RECHECK_INTERVAL) continue;
            request.recheckTimer = 0;
            request.reserveAvailable(target);
            if (!request.ready()) dispatchOutstanding(world, target, request);
        }
    }

    private void completeReadyRequests(World world) {
        String systemId = world.activeSystemId();
        Iterator<LogisticsRequest> it = requests.iterator();
        while (it.hasNext()) {
            LogisticsRequest request = it.next();
            if (!request.inSystem(systemId)) continue;
            Base target = world.bases.get(request.targetBaseId);
            if (target == null) continue;
            ProductionJob job = ProductionSystem.findJob(target, request.productionJobId);
            if (!ProductionSystem.waitingForResources(job)) continue;
            request.reserveAvailable(target);
            if (!request.ready()) continue;

            request.depositHeld(target);
            if (!ProductionSystem.fundWaitingJob(world, target, request.productionJobId)) {
                request.reserveAvailable(target);
                target.logisticsStatus = "Resources delivered; still short: " + request.itemName;
                continue;
            }
            it.remove();
        }
    }

    void cancelJob(Base target, String productionJobId) {
        if (target == null || productionJobId == null || productionJobId.isBlank()) return;
        Iterator<LogisticsRequest> it = requests.iterator();
        while (it.hasNext()) {
            LogisticsRequest request = it.next();
            if (request.targetRef != target || !request.productionJobId.equals(productionJobId)) continue;
            request.refundHeld(target);
            it.remove();
        }
        refreshStatusForTarget(target);
    }

    private void cleanupDeadRequests(World world) {
        String systemId = world.activeSystemId();
        Iterator<LogisticsRequest> it = requests.iterator();
        while (it.hasNext()) {
            LogisticsRequest request = it.next();
            if (!request.inSystem(systemId)) continue;
            Base target = world.bases.get(request.targetBaseId);
            if (target == null) {
                it.remove();
                continue;
            }
            ProductionJob job = ProductionSystem.findJob(target, request.productionJobId);
            if (ProductionSystem.waitingForResources(job)) continue;
            request.refundHeld(target);
            it.remove();
        }
    }

    private void updateBaseStatuses(World world) {
        for (Base base : world.bases.values()) refreshStatus(world, base);
    }

    private void refreshStatus(World world, Base base) {
        LogisticsRequest first = null;
        int count = 0;
        String systemId = world.activeSystemId();
        for (LogisticsRequest request : requests) {
            if (!request.inSystem(systemId) || !request.targetBaseId.equals(base.id)) continue;
            if (first == null) first = request;
            count++;
        }
        base.logisticsStatus = first == null ? "" : waitLabel(first, count);
    }

    private void refreshStatusForTarget(Base base) {
        LogisticsRequest first = null;
        int count = 0;
        for (LogisticsRequest request : requests) {
            if (request.targetRef != base) continue;
            if (first == null) first = request;
            count++;
        }
        base.logisticsStatus = first == null ? "" : waitLabel(first, count);
    }

    private int requestCount(World world, Base target) {
        int count = 0;
        String systemId = world.activeSystemId();
        for (LogisticsRequest request : requests) {
            if (request.inSystem(systemId) && request.targetBaseId.equals(target.id)) count++;
        }
        return count;
    }

    private String waitLabel(LogisticsRequest request, int count) {
        String prefix = request.ready() ? "Resources delivered: " : "Waiting on resources from other hangars: ";
        String suffix = count > 1 ? " +" + (count - 1) + " queued" : "";
        return prefix + request.itemName + suffix;
    }

    private LogisticsRequest requestById(String id) {
        if (id == null || id.isBlank()) return null;
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

final class LogisticsRequest {
    final String id, targetSystemId, playerId, targetBaseId, productionJobId, itemName;
    final Base targetRef;
    final List<Cost> cost;
    final EnumMap<Material, Double> held = new EnumMap<>(Material.class);
    final EnumMap<Material, Double> inTransit = new EnumMap<>(Material.class);
    double recheckTimer = 0;

    LogisticsRequest(String id, String targetSystemId, String playerId, String targetBaseId, Base targetRef,
                     String productionJobId, String itemName, List<Cost> cost) {
        this.id = id;
        this.targetSystemId = targetSystemId == null ? "" : targetSystemId;
        this.playerId = playerId;
        this.targetBaseId = targetBaseId;
        this.targetRef = targetRef;
        this.productionJobId = productionJobId;
        this.itemName = itemName;
        this.cost = List.copyOf(cost);
    }

    boolean inSystem(String systemId) { return targetSystemId.equals(systemId == null ? "" : systemId); }
    boolean matches(String systemId, String baseId) { return inSystem(systemId) && targetBaseId.equals(baseId); }

    void reserveAvailable(Base target) {
        if (target == null) return;
        for (Cost c : cost) {
            double needed = remainingNeeded(c.material());
            if (needed <= 0.05) continue;
            double available = target.inventory.getOrDefault(c.material(), 0.0);
            double take = Math.min(needed, available);
            if (take <= 0.001) continue;
            double next = available - take;
            if (next <= 0.05) target.inventory.remove(c.material());
            else target.inventory.put(c.material(), next);
            held.put(c.material(), held.getOrDefault(c.material(), 0.0) + take);
        }
    }

    void dispatched(Material material, double amount) {
        if (material == null || amount <= 0.001) return;
        inTransit.put(material, inTransit.getOrDefault(material, 0.0) + amount);
    }

    double delivered(Material material, double amount) {
        if (material == null || amount <= 0.001) return 0;
        double accepted = Math.min(amount, remainingNeeded(material));
        if (accepted > 0.001) held.put(material, held.getOrDefault(material, 0.0) + accepted);
        double nextInTransit = inTransit.getOrDefault(material, 0.0) - amount;
        if (nextInTransit <= 0.05) inTransit.remove(material);
        else inTransit.put(material, nextInTransit);
        return Math.max(0, amount - accepted);
    }

    void clearInTransit() { inTransit.clear(); }

    void trackInTransit(EnumMap<Material, Double> cargo) {
        if (cargo == null) return;
        for (Map.Entry<Material, Double> entry : cargo.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0.001) continue;
            inTransit.put(entry.getKey(), inTransit.getOrDefault(entry.getKey(), 0.0) + entry.getValue());
        }
    }

    double undispatchedAmount(Material material) {
        return Math.max(0, remainingNeeded(material) - inTransit.getOrDefault(material, 0.0));
    }

    double remainingNeeded(Material material) {
        double required = 0;
        for (Cost c : cost) if (c.material() == material) required += c.amount();
        return Math.max(0, required - held.getOrDefault(material, 0.0));
    }

    boolean ready() {
        for (Cost c : cost) if (remainingNeeded(c.material()) > 0.05) return false;
        return true;
    }

    void depositHeld(Base target) {
        if (target == null) return;
        for (Map.Entry<Material, Double> entry : held.entrySet()) {
            if (entry.getValue() > 0.001) HangarStore.add(target.inventory, entry.getKey(), entry.getValue());
        }
        held.clear();
    }

    void refundHeld(Base target) { depositHeld(target); }
}