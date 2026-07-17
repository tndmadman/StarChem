package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs persistent depot-pickup and demand-aware station-delivery routes for
 * haulers. Empty haulers claim loaded freighters, drain them, and carry the
 * resulting material to the station that can use it best.
 */
final class HaulerSystem {
    private static final String PICKUP_PREFIX = "MOBILE_DEPOT:";
    private static final String DELIVERY_MARKER = "MOBILE_DEPOT_DELIVERY";
    private static final double DEPART_FILL_RATIO = 0.70;
    private static final double SOURCE_REACHED_FACTOR = 0.55;
    private static final double DESTINATION_REACHED_FACTOR = 0.55;
    private static final double EPSILON = 0.05;

    private final Map<String, HaulerRun> runs = new LinkedHashMap<>();

    void update(World world, Unit hauler, double dt) {
        if (world == null || !MobileDepot.isHauler(hauler) || hauler.hp <= 0) return;
        if (NpcExpeditionSystem.ownsUnit(world, hauler.key())
                || NpcRecoverySystem.ownsUnit(world, hauler)
                || NpcRepairEvacuationSystem.ownsUnit(world, hauler)) return;
        if (!NpcRules.isNpcFaction(hauler.playerId)
                && hauler.orderType != UnitOrderType.NONE) return;

        cleanupCurrentSystem(world);
        String key = runKey(world, hauler);
        HaulerRun run = runs.computeIfAbsent(key,
                ignored -> new HaulerRun(hauler.key(), world.activeSystemId()));

        if (hauler.cargoUsed() > EPSILON) {
            if (run.phase == HaulerPhase.PICKUP) {
                Unit source = source(world, run);
                boolean sourceUsable = source != null && source.cargoUsed() > EPSILON
                        && hauler.freeCargo() > EPSILON;
                double fill = hauler.cargoUsed()
                        / Math.max(1.0, hauler.type().cargoCapacity);
                if (sourceUsable && fill + 0.001 < DEPART_FILL_RATIO) {
                    collect(world, hauler, run, source, dt);
                    if (hauler.freeCargo() > EPSILON
                            && source.cargoUsed() > EPSILON) return;
                }
                beginDelivery(world, hauler, run);
            } else if (run.phase != HaulerPhase.DELIVER) {
                beginDelivery(world, hauler, run);
            }
            deliver(world, hauler, run, dt);
            return;
        }

        if (run.phase == HaulerPhase.DELIVER) clearRun(hauler, run);
        Unit source = source(world, run);
        if (source == null || source.cargoUsed() <= EPSILON) {
            source = selectSource(world, hauler, key);
            if (source == null) {
                clearRun(hauler, run);
                return;
            }
            assignSource(world, hauler, run, source);
        }
        collect(world, hauler, run, source, dt);
        if (hauler.cargoUsed() > EPSILON
                && (hauler.freeCargo() <= EPSILON
                || source.cargoUsed() <= EPSILON
                || hauler.cargoUsed() / Math.max(1.0, hauler.type().cargoCapacity)
                >= DEPART_FILL_RATIO)) {
            beginDelivery(world, hauler, run);
            deliver(world, hauler, run, dt);
        }
    }

    private void collect(World world, Unit hauler, HaulerRun run,
                         Unit source, double dt) {
        if (source == null || source.hp <= 0 || source.cargoUsed() <= EPSILON) return;
        if (MobileDepot.drainTo(hauler, source, dt)) {
            run.lastProgressTime = world.systemTime();
        }
        if (hauler.freeCargo() <= EPSILON || source.cargoUsed() <= EPSILON) return;
        move(hauler, source.x, source.y);
        world.moveTowardOrbit(hauler, source.x, source.y,
                MobileDepot.range(source) * SOURCE_REACHED_FACTOR);
        hauler.task = UnitTask.MOVE;
    }

    private void deliver(World world, Unit hauler, HaulerRun run, double dt) {
        Base target = destination(world, hauler, run);
        if (target == null) {
            target = world.nearestBase(hauler.playerId, hauler.x, hauler.y);
            if (target == null) return;
            assignDestination(world, hauler, run, target);
        }

        if (MobileDepot.unloadToBase(hauler, target, dt)) {
            run.lastProgressTime = world.systemTime();
            if (hauler.cargoUsed() <= EPSILON) {
                AiBrainLog.event(world, hauler.playerId, "mobile_depot",
                        "Hauler #" + hauler.unitId + " completed delivery to "
                                + target.id);
                clearRun(hauler, run);
                return;
            }
        }
        move(hauler, target.x, target.y);
        world.moveTowardOrbit(hauler, target.x, target.y,
                target.type().unloadRange * DESTINATION_REACHED_FACTOR);
        hauler.task = UnitTask.MOVE;
    }

    private Unit selectSource(World world, Unit hauler, String ownRunKey) {
        List<Unit> loaded = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(hauler.playerId) || unit.hp <= 0
                    || unit.cargoUsed() <= EPSILON
                    || !MobileDepot.haulerCanDrain(unit)) continue;
            if (NpcExpeditionSystem.ownsUnit(world, unit.key())
                    || NpcRecoverySystem.ownsUnit(world, unit)
                    || NpcRepairEvacuationSystem.ownsUnit(world, unit)) continue;
            loaded.add(unit);
        }
        loaded.sort(Comparator.comparingInt(unit -> unit.unitId));
        Unit best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Unit source : loaded) {
            double reserved = reservedPickupCapacity(world, source.key(), ownRunKey);
            double unclaimed = source.cargoUsed() - reserved;
            if (unclaimed <= EPSILON) continue;
            double score = (MobileDepot.isDepot(source) ? 100_000.0 : 0.0)
                    + unclaimed * 10.0
                    - Calc.distance(hauler.x, hauler.y, source.x, source.y) * 0.12;
            if (score > bestScore) {
                best = source;
                bestScore = score;
            }
        }
        if (best != null) return best;

        // Every loaded depot may already have a claim. A very full depot can
        // still accept another hauler instead of leaving capacity stranded.
        for (Unit source : loaded) {
            double score = (MobileDepot.isDepot(source) ? 100_000.0 : 0.0)
                    + source.cargoUsed() * 4.0
                    - Calc.distance(hauler.x, hauler.y, source.x, source.y) * 0.15;
            if (score > bestScore) {
                best = source;
                bestScore = score;
            }
        }
        return best;
    }

    private double reservedPickupCapacity(World world, String sourceKey,
                                          String ownRunKey) {
        double total = 0;
        for (Map.Entry<String, HaulerRun> entry : runs.entrySet()) {
            if (entry.getKey().equals(ownRunKey)) continue;
            HaulerRun run = entry.getValue();
            if (run.phase != HaulerPhase.PICKUP
                    || !run.sourceKey.equals(sourceKey)
                    || !run.systemId.equals(world.activeSystemId())) continue;
            Unit assigned = world.units.get(run.haulerKey);
            if (assigned != null && assigned.hp > 0) total += assigned.freeCargo();
        }
        return total;
    }

    private void assignSource(World world, Unit hauler, HaulerRun run, Unit source) {
        run.phase = HaulerPhase.PICKUP;
        run.sourceKey = source.key();
        run.targetBaseId = "";
        hauler.logisticsRequestId = PICKUP_PREFIX + source.key();
        hauler.logisticsTargetBaseId = "";
        AiBrainLog.event(world, hauler.playerId, "mobile_depot",
                "Hauler #" + hauler.unitId + " assigned to drain "
                        + source.shipTypeId + " #" + source.unitId);
    }

    private void beginDelivery(World world, Unit hauler, HaulerRun run) {
        Base target = bestDestination(world, hauler);
        run.phase = HaulerPhase.DELIVER;
        run.sourceKey = "";
        run.targetBaseId = target == null ? "" : target.id;
        hauler.logisticsRequestId = DELIVERY_MARKER;
        hauler.logisticsTargetBaseId = run.targetBaseId;
        if (target != null) {
            AiBrainLog.event(world, hauler.playerId, "mobile_depot",
                    "Hauler #" + hauler.unitId + " carrying "
                            + (int)Math.round(hauler.cargoUsed())
                            + " routed to " + target.id);
        }
    }

    private void assignDestination(World world, Unit hauler,
                                   HaulerRun run, Base target) {
        run.phase = HaulerPhase.DELIVER;
        run.targetBaseId = target.id;
        hauler.logisticsRequestId = DELIVERY_MARKER;
        hauler.logisticsTargetBaseId = target.id;
    }

    private Unit source(World world, HaulerRun run) {
        if (run == null || run.sourceKey.isBlank()
                || !run.systemId.equals(world.activeSystemId())) return null;
        Unit source = world.units.get(run.sourceKey);
        return source != null && source.hp > 0
                && MobileDepot.haulerCanDrain(source) ? source : null;
    }

    private Base destination(World world, Unit hauler, HaulerRun run) {
        if (run == null || run.targetBaseId.isBlank()
                || !run.systemId.equals(world.activeSystemId())) return null;
        Base target = world.bases.get(run.targetBaseId);
        if (target == null || target.hp <= 0
                || !hauler.playerId.equals(target.playerId)) return null;
        return target;
    }

    Base bestDestination(World world, Unit hauler) {
        if (world == null || hauler == null || hauler.cargoUsed() <= EPSILON) return null;
        Base best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!hauler.playerId.equals(base.playerId) || base.hp <= 0) continue;
            double score = destinationScore(base, hauler)
                    - Calc.distance(hauler.x, hauler.y, base.x, base.y) * 0.018;
            if (score > bestScore + 0.001
                    || Math.abs(score - bestScore) <= 0.001
                    && (best == null || base.id.compareTo(best.id) < 0)) {
                best = base;
                bestScore = score;
            }
        }
        return best;
    }

    private double destinationScore(Base base, Unit hauler) {
        double score = 0;
        for (Material material : Material.values()) {
            double carried = hauler.inventory.getOrDefault(material, 0.0);
            if (carried <= EPSILON) continue;
            double held = base.inventory.getOrDefault(material, 0.0);

            double productionNeed = waitingProductionNeed(base, material);
            score += Math.min(carried, productionNeed) * 900.0;

            StationFuelRequirement fuel = StationFuelRules.requirement(base.typeId);
            if (fuel != null && fuel.material() == material) {
                double fuelTarget = StationFuelRules.hasFuelDemand(base) ? 140.0 : 45.0;
                score += Math.min(carried, Math.max(0, fuelTarget - held)) * 240.0;
            }

            double affinity = materialAffinity(base, material);
            double desiredStock = 35.0 + affinity * 42.0;
            score += Math.min(carried, Math.max(0, desiredStock - held))
                    * (5.0 + affinity * 16.0);

            // Outposts remain useful fallback warehouses, but specialized
            // stations beat them whenever they have a real material need.
            if ("outpost".equals(base.typeId)) {
                score += Math.min(carried, Math.max(0, 90.0 - held)) * 3.0;
            }
        }
        return score;
    }

    private double waitingProductionNeed(Base base, Material material) {
        double required = 0;
        for (ProductionJob job : base.productionQueue) {
            if (!ProductionSystem.waitingForResources(job)) continue;
            for (Cost cost : ProductionSystem.costFor(job)) {
                if (cost.material() == material) required += cost.amount();
            }
        }
        return Math.max(0, required - base.inventory.getOrDefault(material, 0.0));
    }

    private double materialAffinity(Base base, Material material) {
        double affinity = 0;
        for (String shipTypeId : base.type().buildableShips) {
            ShipType ship = Rules.findShip(shipTypeId);
            if (ship != null && uses(ship.buildCost, material)) affinity += 1.5;
        }
        for (String stationTypeId : base.type().basePackages) {
            BaseType station = Rules.findBase(stationTypeId);
            if (station != null && uses(station.buildCost, material)) affinity += 2.0;
        }
        for (CraftableItem item : CraftingRules.forStation(base.typeId)) {
            if (uses(item.requiredResources, material)) affinity += 2.5;
        }
        for (ResearchTopic topic : ResearchRules.forStation(base.typeId)) {
            if (uses(topic.requiredResources, material)) affinity += 1.5;
        }
        return Math.min(8.0, affinity);
    }

    private boolean uses(List<Cost> cost, Material material) {
        for (Cost entry : cost) if (entry.material() == material) return true;
        return false;
    }

    private void clearRun(Unit hauler, HaulerRun run) {
        run.phase = HaulerPhase.IDLE;
        run.sourceKey = "";
        run.targetBaseId = "";
        hauler.logisticsRequestId = "";
        hauler.logisticsTargetBaseId = "";
    }

    private void move(Unit hauler, double x, double y) {
        hauler.clearOrder();
        hauler.attackTarget = "";
        hauler.automationResourceId = -1;
        hauler.issueMove(x, y);
    }

    private void cleanupCurrentSystem(World world) {
        String prefix = world.activeSystemId() + "|";
        runs.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix)
                && (!world.units.containsKey(entry.getValue().haulerKey)
                || !MobileDepot.isHauler(world.units.get(entry.getValue().haulerKey))));
    }

    private String runKey(World world, Unit hauler) {
        return world.activeSystemId() + "|" + hauler.key();
    }

    private enum HaulerPhase {
        IDLE,
        PICKUP,
        DELIVER
    }

    private static final class HaulerRun {
        final String haulerKey;
        final String systemId;
        HaulerPhase phase = HaulerPhase.IDLE;
        String sourceKey = "";
        String targetBaseId = "";
        double lastProgressTime;

        HaulerRun(String haulerKey, String systemId) {
            this.haulerKey = haulerKey;
            this.systemId = systemId == null ? "" : systemId;
        }
    }
}
