package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Assigns refit work across every owned refit-capable station in the active system. */
final class RefitQueuePlanner {
    private static final int UNLIMITED_CAPACITY = 1_000_000;
    private static final double OFFLINE_QUEUE_PENALTY_SECONDS = 900;
    private static final double BLOCKED_QUEUE_PENALTY_SECONDS = 3_600;

    private RefitQueuePlanner() { }

    static Result enqueue(World world, String playerId, List<Unit> requested,
                          ShipLoadoutDefinition loadout, boolean free, Base preferredBase) {
        if (world == null || playerId == null || playerId.isBlank() || loadout == null) {
            return Result.fail("Refit request is incomplete.");
        }

        List<Unit> units = cleanUnits(world, playerId, requested, loadout);
        if (units.isEmpty()) return Result.fail("No available ships can be queued for this refit.");
        if (!free && !WeaponRules.unlocked(world, playerId, loadout)) {
            return Result.fail(loadout.displayName() + " requires research: "
                    + WeaponRules.missingResearchLabel(world, playerId, loadout) + ".");
        }

        List<Base> stations = stations(world, playerId);
        if (stations.isEmpty()) return Result.fail("No owned refit-capable station exists in this system.");

        List<Cost> cost = WeaponRules.refitCost(loadout);
        Map<Base,Integer> remainingCapacity = new LinkedHashMap<>();
        int totalCapacity = 0;
        for (Base station : stations) {
            int capacity = capacity(station, cost, free);
            remainingCapacity.put(station, capacity);
            totalCapacity = Math.min(UNLIMITED_CAPACITY, totalCapacity + capacity);
        }
        if (totalCapacity < units.size()) {
            return Result.fail("Refit network can fund only " + totalCapacity + " of " + units.size()
                    + " jobs. Distribute " + Rules.formatCost(cost)
                    + " among the Outpost and Shipyard hangars.");
        }

        Map<Base,Double> availableAt = new LinkedHashMap<>();
        for (Base station : stations) availableAt.put(station, queueAvailableAt(world, station));

        List<Assignment> plan = new ArrayList<>();
        Map<Base,Integer> distribution = new LinkedHashMap<>();
        for (Unit unit : units) {
            Base station = chooseStation(unit, loadout, preferredBase, stations,
                    remainingCapacity, availableAt);
            if (station == null) return Result.fail("No refit station can accept every requested job.");
            double ready = Math.max(availableAt.getOrDefault(station, 0.0), travelSeconds(unit, station));
            availableAt.put(station, ready + loadout.refitTimeSeconds());
            remainingCapacity.put(station, Math.max(0, remainingCapacity.get(station) - 1));
            distribution.merge(station, 1, Integer::sum);
            plan.add(new Assignment(station, unit));
        }

        List<Queued> committed = new ArrayList<>();
        for (Assignment assignment : plan) {
            Base station = assignment.station();
            Unit unit = assignment.unit();
            int before = station.productionQueue.size();
            if (!ProductionSystem.enqueueRefit(world, station, unit, loadout, free)) {
                rollback(world, playerId, committed);
                return Result.fail("Refit queue assignment failed for " + unit.type().name + ": " + world.status);
            }
            ProductionJob job = queuedJob(station, unit.key(), loadout.id(), before);
            if (job != null) committed.add(new Queued(station, job.id));
        }

        String message = "Queued " + plan.size() + " " + Rules.ship(loadout.hullId()).name
                + " refit" + (plan.size() == 1 ? "" : "s") + " across " + distribution.size()
                + " station" + (distribution.size() == 1 ? "" : "s") + ": "
                + distributionLabel(distribution) + ".";
        world.status = message;
        AlertCenter.push(world, message);
        return Result.ok(plan.size(), distribution, plan.get(0).station(), message);
    }

    static Base bestStation(World world, Unit unit, ShipLoadoutDefinition loadout, boolean free) {
        if (world == null || unit == null || loadout == null) return null;
        List<Base> stations = stations(world, unit.playerId);
        Map<Base,Integer> capacity = new LinkedHashMap<>();
        Map<Base,Double> availableAt = new LinkedHashMap<>();
        List<Cost> cost = WeaponRules.refitCost(loadout);
        for (Base station : stations) {
            capacity.put(station, capacity(station, cost, free));
            availableAt.put(station, queueAvailableAt(world, station));
        }
        return chooseStation(unit, loadout, null, stations, capacity, availableAt);
    }

    static List<Base> stations(World world, String playerId) {
        if (world == null || playerId == null || playerId.isBlank()) return List.of();
        List<Base> out = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || !playerId.equals(base.playerId)
                    || !base.type().canRefitShips) continue;
            out.add(base);
        }
        out.sort(Comparator.comparing(base -> base.id));
        return List.copyOf(out);
    }

    private static List<Unit> cleanUnits(World world, String playerId, List<Unit> requested,
                                         ShipLoadoutDefinition loadout) {
        if (requested == null || requested.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<Unit> out = new ArrayList<>();
        for (Unit unit : requested) {
            if (unit == null || unit.hp <= 0 || !playerId.equals(unit.playerId)
                    || !loadout.hullId().equals(unit.shipTypeId)
                    || loadout.id().equals(unit.loadoutId)
                    || ProductionSystem.refitReserved(world, unit.key())
                    || !seen.add(unit.key())) continue;
            out.add(unit);
        }
        return List.copyOf(out);
    }

    private static Base chooseStation(Unit unit, ShipLoadoutDefinition loadout, Base preferredBase,
                                      List<Base> stations, Map<Base,Integer> remainingCapacity,
                                      Map<Base,Double> availableAt) {
        Base best = null;
        double bestFinish = Double.MAX_VALUE;
        for (Base station : stations) {
            if (remainingCapacity.getOrDefault(station, 0) <= 0) continue;
            double finish = Math.max(availableAt.getOrDefault(station, 0.0), travelSeconds(unit, station))
                    + loadout.refitTimeSeconds();
            if (station == preferredBase) finish -= 0.001;
            if (finish < bestFinish - 0.000001
                    || Math.abs(finish - bestFinish) <= 0.000001
                    && (best == null || station.id.compareTo(best.id) < 0)) {
                best = station;
                bestFinish = finish;
            }
        }
        return best;
    }

    private static double queueAvailableAt(World world, Base station) {
        double available = StationFuelRules.isOperational(station) ? 0 : OFFLINE_QUEUE_PENALTY_SECONDS;
        for (ProductionJob job : station.productionQueue) {
            if (ProductionSystem.waitingForResources(job)) available += BLOCKED_QUEUE_PENALTY_SECONDS;
            if (job.kind == ProductionJobKind.REFIT) {
                Unit subject = world.units.get(job.subjectUnitKey);
                if (subject != null && subject.hp > 0) {
                    available = Math.max(available, travelSeconds(subject, station));
                }
            }
            available += Math.max(0, job.remaining);
        }
        return available;
    }

    private static double travelSeconds(Unit unit, Base station) {
        double distance = Math.max(0, Calc.distance(unit.x, unit.y, station.x, station.y)
                - Math.max(0, station.type().refitRange * 0.55));
        return distance / Math.max(1, unit.type().speed);
    }

    private static int capacity(Base station, List<Cost> cost, boolean free) {
        if (free || cost == null || cost.isEmpty()) return UNLIMITED_CAPACITY;
        int capacity = UNLIMITED_CAPACITY;
        for (Cost item : cost) {
            if (item == null || item.amount() <= 0) continue;
            double available = station.inventory.getOrDefault(item.material(), 0.0);
            capacity = Math.min(capacity, (int)Math.floor((available + 0.000001) / item.amount()));
        }
        return Math.max(0, capacity);
    }

    private static ProductionJob queuedJob(Base station, String unitKey, String loadoutId, int startIndex) {
        for (int i = Math.max(0, startIndex); i < station.productionQueue.size(); i++) {
            ProductionJob job = station.productionQueue.get(i);
            if (job.kind == ProductionJobKind.REFIT && unitKey.equals(job.subjectUnitKey)
                    && loadoutId.equals(job.loadoutId)) return job;
        }
        return null;
    }

    private static void rollback(World world, String playerId, List<Queued> committed) {
        for (int i = committed.size() - 1; i >= 0; i--) {
            Queued queued = committed.get(i);
            ProductionSystem.cancel(world, playerId, queued.station().id, queued.jobId());
        }
    }

    private static String distributionLabel(Map<Base,Integer> distribution) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<Base,Integer> entry : distribution.entrySet()) {
            Base station = entry.getKey();
            labels.add(station.type().name + " " + station.id + " ×" + entry.getValue());
        }
        return String.join(", ", labels);
    }

    record Result(boolean success, int queued, int stationsUsed, Base primaryBase,
                  Map<String,Integer> distribution, String message) {
        static Result ok(int queued, Map<Base,Integer> distribution, Base primaryBase, String message) {
            Map<String,Integer> copy = new LinkedHashMap<>();
            for (Map.Entry<Base,Integer> entry : distribution.entrySet()) copy.put(entry.getKey().id, entry.getValue());
            return new Result(true, queued, distribution.size(), primaryBase, Map.copyOf(copy), message);
        }

        static Result fail(String message) {
            return new Result(false, 0, 0, null, Map.of(),
                    message == null || message.isBlank() ? "Refit queue request was rejected." : message);
        }
    }

    private record Assignment(Base station, Unit unit) { }
    private record Queued(Base station, String jobId) { }
}
