package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Positions organized-faction freighters as distributed mobile depots around
 * the mining fleet instead of stacking them at the first station.
 *
 * Anchors are derived deterministically from local miners and their assigned
 * resource nodes. Multiple freighters use weighted farthest-first clustering,
 * followed by a bounded separation pass, so they cover distinct mining areas
 * without jittering between equivalent choices every simulation tick.
 */
final class NpcMobileDepotSystem {
    private static final double MIN_DEPOT_SPACING = 760.0;
    private static final double ARRIVAL_RADIUS = 75.0;
    private static final double RETARGET_DISTANCE = 120.0;
    private static final double MAP_MARGIN = 190.0;
    private static final int CLUSTER_ITERATIONS = 4;
    private static final int SEPARATION_ITERATIONS = 12;

    private NpcMobileDepotSystem() { }

    static void update(World world, NpcFaction faction) {
        if (world == null || faction == null
                || faction.behavior() != NpcBehavior.FACTION) return;

        List<Unit> depots = eligibleDepots(world, faction);
        if (depots.isEmpty()) return;
        List<WeightedPoint> mining = miningPoints(world, faction);
        List<Anchor> anchors = anchors(world, faction, mining, depots.size());
        if (anchors.isEmpty()) return;

        List<Anchor> remaining = new ArrayList<>(anchors);
        for (Unit depot : depots) {
            Anchor anchor = nearest(depot, remaining);
            if (anchor == null) break;
            remaining.remove(anchor);
            command(world, faction, depot, anchor);
        }
    }

    private static List<Unit> eligibleDepots(World world, NpcFaction faction) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || !MobileDepot.isDepot(unit)) continue;
            if (NpcExpeditionSystem.ownsUnit(world, unit.key())
                    || NpcRecoverySystem.ownsUnit(world, unit)
                    || NpcRepairEvacuationSystem.ownsUnit(world, unit)) continue;
            out.add(unit);
        }
        out.sort(Comparator.comparingInt(unit -> unit.unitId));
        return out;
    }

    private static List<WeightedPoint> miningPoints(World world, NpcFaction faction) {
        List<WeightedPoint> workers = new ArrayList<>();
        Set<NodeKind> harvestKinds = new LinkedHashSet<>();
        int ordinal = 0;
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0
                    || unit.type().harvestKinds.isEmpty()) continue;
            if (NpcExpeditionSystem.ownsUnit(world, unit.key())
                    || NpcRecoverySystem.ownsUnit(world, unit)
                    || NpcRepairEvacuationSystem.ownsUnit(world, unit)) continue;
            harvestKinds.addAll(unit.type().harvestKinds);
            ResourceNode target = resource(world, unit.automationResourceId);
            double x = target == null ? unit.x : target.x;
            double y = target == null ? unit.y : target.y;
            double cargoPressure = unit.type().cargoCapacity <= 0 ? 0
                    : unit.cargoUsed() / Math.max(1.0, unit.type().cargoCapacity);
            workers.add(new WeightedPoint(x, y, 5.0 + cargoPressure * 2.0,
                    unit.unitId * 10_000 + ordinal++));
        }

        List<WeightedPoint> all = new ArrayList<>(workers);
        if (!harvestKinds.isEmpty()) {
            for (ResourceNode node : world.resources) {
                if (!node.active || node.amount <= 0.05
                        || !harvestKinds.contains(node.kind)) continue;
                double density = Math.max(0.15,
                        node.amount / Math.max(1.0, node.maxAmount));
                all.add(new WeightedPoint(node.x, node.y,
                        workers.isEmpty() ? 1.0 + density : 0.18 + density * 0.35,
                        1_000_000_000 + node.id));
            }
        }
        all.sort(Comparator
                .comparingDouble((WeightedPoint point) -> -point.weight)
                .thenComparingInt(point -> point.ordinal));
        return all;
    }

    private static List<Anchor> anchors(World world, NpcFaction faction,
                                         List<WeightedPoint> points, int count) {
        if (count <= 0) return List.of();
        if (points.isEmpty()) return fallbackAnchors(world, faction, count);

        int seedCount = Math.min(count, points.size());
        List<Anchor> centers = new ArrayList<>();
        centers.add(new Anchor(points.get(0).x, points.get(0).y));
        while (centers.size() < seedCount) {
            WeightedPoint best = null;
            double bestScore = -1;
            for (WeightedPoint point : points) {
                double distance = nearestDistance(point.x, point.y, centers);
                double score = distance * distance * Math.max(0.2, point.weight);
                if (score > bestScore + 0.001
                        || Math.abs(score - bestScore) <= 0.001
                        && (best == null || point.ordinal < best.ordinal)) {
                    best = point;
                    bestScore = score;
                }
            }
            if (best == null) break;
            centers.add(new Anchor(best.x, best.y));
        }

        for (int iteration = 0; iteration < CLUSTER_ITERATIONS; iteration++) {
            double[] sumX = new double[centers.size()];
            double[] sumY = new double[centers.size()];
            double[] weight = new double[centers.size()];
            for (WeightedPoint point : points) {
                int index = nearestIndex(point.x, point.y, centers);
                sumX[index] += point.x * point.weight;
                sumY[index] += point.y * point.weight;
                weight[index] += point.weight;
            }
            for (int i = 0; i < centers.size(); i++) {
                if (weight[i] <= 0.001) continue;
                centers.set(i, new Anchor(sumX[i] / weight[i], sumY[i] / weight[i]));
            }
        }

        if (centers.size() < count) {
            Anchor center = weightedCenter(points);
            int startIndex = centers.size();
            int missing = count - startIndex;
            double radius = Math.max(MIN_DEPOT_SPACING,
                    MIN_DEPOT_SPACING * (0.7 + count * 0.12));
            for (int i = 0; i < missing; i++) {
                double angle = (startIndex + i) * Math.PI * 2.0 / count
                        + deterministicAngle(world, faction);
                centers.add(new Anchor(center.x + Math.cos(angle) * radius,
                        center.y + Math.sin(angle) * radius));
            }
        }

        clampAll(world, centers);
        separate(world, faction, centers);
        return List.copyOf(centers);
    }

    private static void separate(World world, NpcFaction faction, List<Anchor> anchors) {
        for (int iteration = 0; iteration < SEPARATION_ITERATIONS; iteration++) {
            boolean changed = false;
            for (int i = 0; i < anchors.size(); i++) {
                for (int j = i + 1; j < anchors.size(); j++) {
                    Anchor a = anchors.get(i);
                    Anchor b = anchors.get(j);
                    double dx = b.x - a.x;
                    double dy = b.y - a.y;
                    double distance = Math.hypot(dx, dy);
                    if (distance + 0.001 >= MIN_DEPOT_SPACING) continue;
                    if (distance < 0.001) {
                        double angle = deterministicAngle(world, faction)
                                + (i * 31 + j * 17) * 0.37;
                        dx = Math.cos(angle);
                        dy = Math.sin(angle);
                        distance = 1.0;
                    }
                    double nx = dx / distance;
                    double ny = dy / distance;
                    double deficit = MIN_DEPOT_SPACING - distance;
                    Anchor nextA = clamp(world,
                            new Anchor(a.x - nx * deficit * 0.5,
                                    a.y - ny * deficit * 0.5));
                    Anchor nextB = clamp(world,
                            new Anchor(b.x + nx * deficit * 0.5,
                                    b.y + ny * deficit * 0.5));

                    double remaining = MIN_DEPOT_SPACING
                            - Calc.distance(nextA.x, nextA.y, nextB.x, nextB.y);
                    if (remaining > 0.001) {
                        nextB = clamp(world, new Anchor(
                                nextB.x + nx * remaining,
                                nextB.y + ny * remaining));
                        remaining = MIN_DEPOT_SPACING
                                - Calc.distance(nextA.x, nextA.y, nextB.x, nextB.y);
                    }
                    if (remaining > 0.001) {
                        nextA = clamp(world, new Anchor(
                                nextA.x - nx * remaining,
                                nextA.y - ny * remaining));
                    }
                    anchors.set(i, nextA);
                    anchors.set(j, nextB);
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private static List<Anchor> fallbackAnchors(World world, NpcFaction faction, int count) {
        Base centerBase = null;
        for (Base base : world.bases.values()) {
            if (faction.id().equals(base.playerId) && base.hp > 0) {
                centerBase = base;
                break;
            }
        }
        double cx = centerBase == null ? world.width * 0.5 : centerBase.x;
        double cy = centerBase == null ? world.height * 0.5 : centerBase.y;
        double radius = count == 1 ? 540.0 : Math.max(700.0, MIN_DEPOT_SPACING);
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = deterministicAngle(world, faction)
                    + i * Math.PI * 2.0 / count;
            out.add(new Anchor(cx + Math.cos(angle) * radius,
                    cy + Math.sin(angle) * radius));
        }
        clampAll(world, out);
        separate(world, faction, out);
        return List.copyOf(out);
    }

    private static void clampAll(World world, List<Anchor> anchors) {
        for (int i = 0; i < anchors.size(); i++) {
            anchors.set(i, clamp(world, anchors.get(i)));
        }
    }

    private static Anchor clamp(World world, Anchor anchor) {
        return new Anchor(
                Calc.clamp(anchor.x, MAP_MARGIN, world.width - MAP_MARGIN),
                Calc.clamp(anchor.y, MAP_MARGIN, world.height - MAP_MARGIN));
    }

    private static void command(World world, NpcFaction faction,
                                Unit depot, Anchor anchor) {
        double distance = Calc.distance(depot.x, depot.y, anchor.x, anchor.y);
        if (distance > ARRIVAL_RADIUS) {
            boolean changed = depot.task != UnitTask.MOVE
                    || Calc.distance(depot.targetX, depot.targetY,
                    anchor.x, anchor.y) > RETARGET_DISTANCE;
            depot.clearOrder();
            depot.attackTarget = "";
            depot.automationResourceId = -1;
            depot.issueMove(anchor.x, anchor.y);
            if (changed) {
                AiBrainLog.event(world, faction.id(), "mobile_depot",
                        "Freighter #" + depot.unitId + " assigned mining anchor "
                                + coordinate(anchor.x, anchor.y));
            }
            return;
        }

        depot.clearOrder();
        depot.attackTarget = "";
        depot.automationResourceId = -1;
        depot.task = UnitTask.MOVE;
        depot.targetX = depot.x;
        depot.targetY = depot.y;
    }

    private static Anchor nearest(Unit unit, List<Anchor> anchors) {
        Anchor best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Anchor anchor : anchors) {
            double distance = Calc.distance(unit.x, unit.y, anchor.x, anchor.y);
            if (distance < bestDistance) {
                best = anchor;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int nearestIndex(double x, double y, List<Anchor> anchors) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            Anchor anchor = anchors.get(i);
            double distance = Calc.distance(x, y, anchor.x, anchor.y);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double nearestDistance(double x, double y, List<Anchor> anchors) {
        double best = Double.MAX_VALUE;
        for (Anchor anchor : anchors) {
            best = Math.min(best, Calc.distance(x, y, anchor.x, anchor.y));
        }
        return best;
    }

    private static Anchor weightedCenter(List<WeightedPoint> points) {
        double x = 0;
        double y = 0;
        double weight = 0;
        for (WeightedPoint point : points) {
            x += point.x * point.weight;
            y += point.y * point.weight;
            weight += point.weight;
        }
        return weight <= 0.001 ? new Anchor(0, 0)
                : new Anchor(x / weight, y / weight);
    }

    private static ResourceNode resource(World world, int id) {
        if (id < 0) return null;
        for (ResourceNode node : world.resources) {
            if (node.id == id && node.active && node.amount > 0.05) return node;
        }
        return null;
    }

    private static double deterministicAngle(World world, NpcFaction faction) {
        long seed = world.systemSeed();
        seed ^= (long)world.activeSystemId().hashCode() << 32;
        seed ^= faction.id().hashCode() * 31L;
        return Math.floorMod(seed, 10_000L) / 10_000.0 * Math.PI * 2.0;
    }

    private static String coordinate(double x, double y) {
        return "(" + (int)Math.round(x) + "," + (int)Math.round(y) + ")";
    }

    private record WeightedPoint(double x, double y, double weight, int ordinal) { }
    private record Anchor(double x, double y) { }
}
