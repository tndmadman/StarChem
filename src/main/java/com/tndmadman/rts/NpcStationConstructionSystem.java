package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Owns persistent organized-NPC station construction plans.
 *
 * A plan reserves and pays for one package, assigns one deployer, selects a
 * scored site, moves the deployer there, and then uses the parked loaded
 * deployer as the visible construction site until the configured build time is
 * complete. Replanning before or during construction never charges twice.
 * Cancellation before ground work starts refunds the package to its source
 * station; once construction starts, the committed package is not refundable.
 */
final class NpcStationConstructionSystem {
    private static final double ARRIVAL_RADIUS = 26.0;
    private static final double SITE_MARGIN = 170.0;
    private static final double MIN_WORMHOLE_CLEARANCE = 280.0;
    private static final double MIN_ENEMY_CLEARANCE = 360.0;
    private static final int ANGLE_SAMPLES = 24;
    private static final double[] RING_MULTIPLIERS = {1.0, 1.30, 1.62};
    private static final Map<World, Map<String, ConstructionPlan>> PLANS = new WeakHashMap<>();

    private NpcStationConstructionSystem() { }

    static synchronized boolean start(World world, NpcFaction faction, Base source, Unit builder,
                                      String packageType, NpcBudgetCategory category) {
        if (world == null || faction == null || source == null || builder == null
                || packageType == null || packageType.isBlank()) return false;
        if (!faction.id().equals(source.playerId) || source.hp <= 0
                || !faction.id().equals(builder.playerId) || builder.hp <= 0
                || !builder.type().baseBuilder) return false;

        String key = key(world.activeSystemId(), faction.id());
        ConstructionPlan existing = plans(world).get(key);
        if (existing != null) return true;
        if (!builder.basePackageType.isBlank() || reservedByProduction(world, builder)) return false;

        BaseType station = Rules.findBase(packageType);
        if (station == null || !source.type().basePackages.contains(packageType)) return false;
        Site site = selectSite(world, faction, source, station);
        if (site == null) return false;

        NpcBudgetCategory normalized = category == null
                ? NpcBudgetCategory.STATION_RECOVERY : category;
        if (!NpcResourceBudget.canAfford(world, faction, normalized, station.buildCost)) return false;
        if (!NpcResourceBudget.spend(world, faction, normalized, station.buildCost)) return false;

        double duration = station.buildTimeSeconds > 0
                ? station.buildTimeSeconds : Math.max(1.0, faction.stationBuildSeconds());
        builder.basePackageType = packageType;
        prepareBuilder(builder, site.x, site.y, true);
        ConstructionPlan plan = new ConstructionPlan(
                world.activeSystemId(), world.systemSeed(), faction.id(), source.id,
                builder.key(), packageType, normalized, site.x, site.y, duration);
        plans(world).put(key, plan);
        AiDevLog.add(world, faction,
                "station plan reserved: " + station.name + " at "
                        + coordinate(site.x, site.y) + " score=" + (int)Math.round(site.score));
        world.status = faction.name() + " committed a " + station.name + " construction package.";
        return true;
    }

    static synchronized void update(World world, NpcFaction faction, double dt) {
        if (world == null || faction == null || dt < 0) return;
        String key = key(world.activeSystemId(), faction.id());
        ConstructionPlan plan = plans(world).get(key);
        if (plan == null) return;

        if (plan.seed != world.systemSeed()) {
            cancelInternal(world, faction, key, plan, "system seed changed", true);
            return;
        }
        Unit builder = world.units.get(plan.builderKey);
        if (builder == null || builder.hp <= 0) {
            plans(world).remove(key);
            AiDevLog.add(world, faction,
                    "station plan failed: deployer lost while carrying " + plan.packageType);
            return;
        }
        BaseType station = Rules.findBase(plan.packageType);
        if (station == null) {
            cancelInternal(world, faction, key, plan, "station rule removed", plan.phase == NpcConstructionPhase.TRAVELLING);
            return;
        }
        if (livingBaseCount(world, faction.id()) >= faction.maxStations()) {
            cancelInternal(world, faction, key, plan, "station cap reached", plan.phase == NpcConstructionPhase.TRAVELLING);
            return;
        }

        builder.basePackageType = plan.packageType;
        Base source = world.bases.get(plan.sourceBaseId);
        if (!validSite(world, faction, source, station, plan.targetX, plan.targetY)) {
            Site replacement = source == null ? null : selectSite(world, faction, source, station);
            if (replacement == null) {
                holdBuilder(builder);
                plan.waitingForSite = true;
                return;
            }
            plan.targetX = replacement.x;
            plan.targetY = replacement.y;
            plan.phase = NpcConstructionPhase.TRAVELLING;
            plan.remaining = plan.duration;
            plan.waitingForSite = false;
            plan.replans++;
            prepareBuilder(builder, plan.targetX, plan.targetY, true);
            AiDevLog.add(world, faction,
                    "station plan replanned: " + station.name + " -> "
                            + coordinate(plan.targetX, plan.targetY));
            return;
        }

        if (plan.phase == NpcConstructionPhase.TRAVELLING) {
            plan.waitingForSite = false;
            if (Calc.distance(builder.x, builder.y, plan.targetX, plan.targetY) > ARRIVAL_RADIUS) {
                prepareBuilder(builder, plan.targetX, plan.targetY, true);
                return;
            }
            builder.x = Calc.clamp(plan.targetX, 0, world.width);
            builder.y = Calc.clamp(plan.targetY, 0, world.height);
            holdBuilder(builder);
            plan.phase = NpcConstructionPhase.CONSTRUCTING;
            plan.remaining = plan.duration;
            AiDevLog.add(world, faction,
                    "station construction started: " + station.name + " at "
                            + coordinate(plan.targetX, plan.targetY));
            world.status = faction.name() + " began constructing " + station.name + ".";
            return;
        }

        if (Calc.distance(builder.x, builder.y, plan.targetX, plan.targetY) > ARRIVAL_RADIUS) {
            prepareBuilder(builder, plan.targetX, plan.targetY, true);
            return;
        }
        builder.x = Calc.clamp(plan.targetX, 0, world.width);
        builder.y = Calc.clamp(plan.targetY, 0, world.height);
        holdBuilder(builder);
        plan.remaining = Math.max(0.0, plan.remaining - dt);
        if (plan.remaining > 0.001) return;

        int next = nextBaseNumber(world, faction.id());
        String baseId = faction.id() + ":B" + next;
        Base completed = new Base(baseId, faction.id(), plan.packageType, plan.targetX, plan.targetY);
        world.bases.put(baseId, completed);
        world.units.remove(builder.key());
        plans(world).remove(key);
        world.status = faction.name() + " completed " + station.name + ".";
        SystemAudio.playForPlayer(world, faction.id(), SoundCue.PLACE_STATION);
        AiDevLog.add(world, faction,
                "station construction completed: " + station.name + " " + baseId);
    }

    static synchronized boolean cancel(World world, NpcFaction faction, String reason) {
        if (world == null || faction == null) return false;
        String key = key(world.activeSystemId(), faction.id());
        ConstructionPlan plan = plans(world).get(key);
        if (plan == null) return false;
        boolean refundable = plan.phase == NpcConstructionPhase.TRAVELLING;
        cancelInternal(world, faction, key, plan,
                reason == null || reason.isBlank() ? "cancelled" : reason, refundable);
        return true;
    }

    static synchronized boolean hasActivePlan(World world, NpcFaction faction) {
        return world != null && faction != null
                && plans(world).containsKey(key(world.activeSystemId(), faction.id()));
    }

    static synchronized boolean hasAnyActivePlan(World world, NpcFaction faction) {
        if (world == null || faction == null) return false;
        String suffix = "|" + faction.id();
        for (String key : plans(world).keySet()) if (key.endsWith(suffix)) return true;
        return false;
    }

    static synchronized boolean ownsBuilder(World world, String builderKey) {
        if (world == null || builderKey == null || builderKey.isBlank()) return false;
        for (ConstructionPlan plan : plans(world).values()) {
            if (builderKey.equals(plan.builderKey)) return true;
        }
        return false;
    }

    static synchronized NpcStationConstructionSnapshot snapshot(World world, NpcFaction faction) {
        if (world == null || faction == null) return NpcStationConstructionSnapshot.NONE;
        ConstructionPlan plan = plans(world).get(key(world.activeSystemId(), faction.id()));
        if (plan == null) return NpcStationConstructionSnapshot.NONE;
        return new NpcStationConstructionSnapshot(true, plan.systemId, plan.builderKey,
                plan.packageType, plan.phase, plan.targetX, plan.targetY,
                plan.duration, plan.remaining, plan.replans, plan.waitingForSite);
    }

    static synchronized void clear(World world) {
        if (world != null) PLANS.remove(world);
    }

    private static void cancelInternal(World world, NpcFaction faction, String key,
                                       ConstructionPlan plan, String reason, boolean refund) {
        plans(world).remove(key);
        Unit builder = world.units.get(plan.builderKey);
        Base source = world.bases.get(plan.sourceBaseId);
        boolean refunded = refund && builder != null && builder.hp > 0 && source != null && source.hp > 0;
        if (refunded) {
            BaseType station = Rules.findBase(plan.packageType);
            if (station != null) {
                for (Cost cost : station.buildCost) {
                    HangarStore.add(source.inventory, cost.material(), cost.amount());
                }
            }
        }
        if (builder != null && builder.hp > 0) {
            builder.basePackageType = "";
            holdBuilder(builder);
        }
        AiDevLog.add(world, faction,
                "station plan cancelled: " + reason + (refunded ? " [refunded]" : " [package lost]"));
    }

    private static void prepareBuilder(Unit builder, double x, double y, boolean move) {
        builder.clearOrder();
        builder.attackTarget = "";
        builder.automationResourceId = -1;
        if (move) builder.issueMove(x, y);
        else holdBuilder(builder);
    }

    private static void holdBuilder(Unit builder) {
        builder.clearOrder();
        builder.attackTarget = "";
        builder.automationResourceId = -1;
        builder.task = UnitTask.IDLE;
        builder.targetX = builder.x;
        builder.targetY = builder.y;
    }

    private static Site selectSite(World world, NpcFaction faction, Base source, BaseType station) {
        if (source == null || station == null) return null;
        double spacing = Math.max(360.0, faction.stationSpacing());
        List<Site> candidates = new ArrayList<>();
        int ordinal = 0;
        for (double ring : RING_MULTIPLIERS) {
            double radius = spacing * ring;
            for (int i = 0; i < ANGLE_SAMPLES; i++) {
                double angle = i * Math.PI * 2.0 / ANGLE_SAMPLES
                        + deterministicOffset(world, faction, station, ring);
                double x = source.x + Math.cos(angle) * radius;
                double y = source.y + Math.sin(angle) * radius;
                if (!validSite(world, faction, source, station, x, y)) {
                    ordinal++;
                    continue;
                }
                candidates.add(new Site(x, y,
                        score(world, faction, source, station, x, y, ordinal++)));
            }
        }
        return candidates.stream()
                .max(Comparator.comparingDouble((Site site) -> site.score)
                        .thenComparingDouble(site -> -site.x)
                        .thenComparingDouble(site -> -site.y))
                .orElse(null);
    }

    private static double deterministicOffset(World world, NpcFaction faction,
                                              BaseType station, double ring) {
        long seed = world.systemSeed();
        seed ^= ((long)world.activeSystemId().hashCode() << 32);
        seed ^= faction.id().hashCode() * 31L;
        seed ^= station.id.hashCode() * 17L;
        seed ^= Double.doubleToLongBits(ring);
        return Math.floorMod(seed, 10_000L) / 10_000.0 * Math.PI * 2.0 / ANGLE_SAMPLES;
    }

    private static boolean validSite(World world, NpcFaction faction, Base source,
                                     BaseType station, double x, double y) {
        double margin = Math.max(SITE_MARGIN, station.buildRadius + 90.0);
        if (x < margin || y < margin || x > world.width - margin || y > world.height - margin) return false;

        double minimumSpacing = Math.max(300.0, faction.stationSpacing() * 0.72);
        for (Base base : world.bases.values()) {
            if (base.hp <= 0) continue;
            double minimum = minimumSpacing;
            if (!faction.id().equals(base.playerId)) minimum = Math.max(minimum, MIN_ENEMY_CLEARANCE);
            if (Calc.distance(x, y, base.x, base.y) < minimum) return false;
        }
        for (ResourceNode node : world.resources) {
            if (!node.active) continue;
            if (Calc.distance(x, y, node.x, node.y) < node.radius + station.buildRadius + 80.0) return false;
        }
        for (WormholeGate gate : world.wormholes) {
            if (Calc.distance(x, y, gate.x, gate.y) < MIN_WORMHOLE_CLEARANCE) return false;
        }
        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0 || faction.id().equals(unit.playerId)) continue;
            if (!NpcRules.isNpcFaction(unit.playerId) || faction.attackNpcFactions()) {
                if (Calc.distance(x, y, unit.x, unit.y) < MIN_ENEMY_CLEARANCE) return false;
            }
        }
        return source == null || Calc.distance(x, y, source.x, source.y) >= minimumSpacing;
    }

    private static double score(World world, NpcFaction faction, Base source,
                                BaseType station, double x, double y, int ordinal) {
        double score = 0.0;
        double spacing = Math.max(360.0, faction.stationSpacing());
        double sourceDistance = Calc.distance(x, y, source.x, source.y);
        score -= Math.abs(sourceDistance - spacing * 1.18) * 0.12;

        double boundary = Math.min(Math.min(x, world.width - x), Math.min(y, world.height - y));
        score += Math.min(700.0, boundary) * 0.08;

        double nearestFriendly = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            nearestFriendly = Math.min(nearestFriendly, Calc.distance(x, y, base.x, base.y));
        }
        if (nearestFriendly < Double.MAX_VALUE) score -= nearestFriendly * 0.025;

        double resourceWeight = "manufacturing".equals(station.id) ? 2.4
                : "laboratory".equals(station.id) ? 0.45 : 0.9;
        for (ResourceNode node : world.resources) {
            if (!node.active || node.amount <= 0.05) continue;
            double distance = Calc.distance(x, y, node.x, node.y);
            if (distance < 1700.0) score += resourceWeight * (1700.0 - distance) / 45.0;
        }

        for (WormholeGate gate : world.wormholes) {
            double distance = Calc.distance(x, y, gate.x, gate.y);
            if (distance >= 2200.0) continue;
            if ("shipyard".equals(station.id)) {
                score += Math.max(0.0, 1500.0 - Math.abs(distance - 1050.0)) / 40.0;
            } else if ("laboratory".equals(station.id)) {
                score -= Math.max(0.0, 1500.0 - distance) / 35.0;
            }
        }

        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0 || faction.id().equals(unit.playerId)) continue;
            if (NpcRules.isNpcFaction(unit.playerId) && !faction.attackNpcFactions()) continue;
            double distance = Calc.distance(x, y, unit.x, unit.y);
            double threat = WeaponRules.armed(unit.type()) ? 1.8 : 0.65;
            score -= threat * Math.max(0.0, 2200.0 - distance) / 18.0;
        }
        for (Base base : world.bases.values()) {
            if (base.hp <= 0 || faction.id().equals(base.playerId)) continue;
            if (NpcRules.isNpcFaction(base.playerId) && !faction.attackNpcFactions()) continue;
            double distance = Calc.distance(x, y, base.x, base.y);
            score -= Math.max(0.0, 2600.0 - distance) / 16.0;
        }

        if ("laboratory".equals(station.id)) score += nearestThreatDistance(world, faction, x, y) * 0.025;
        if ("manufacturing".equals(station.id)) score += activeResourceCount(world, x, y, 1200.0) * 12.0;
        score -= ordinal * 0.0001;
        return score;
    }

    private static double nearestThreatDistance(World world, NpcFaction faction, double x, double y) {
        double nearest = 3000.0;
        for (Unit unit : world.units.values()) {
            if (unit.hp <= 0 || faction.id().equals(unit.playerId)) continue;
            if (NpcRules.isNpcFaction(unit.playerId) && !faction.attackNpcFactions()) continue;
            nearest = Math.min(nearest, Calc.distance(x, y, unit.x, unit.y));
        }
        return nearest;
    }

    private static int activeResourceCount(World world, double x, double y, double radius) {
        int count = 0;
        for (ResourceNode node : world.resources) {
            if (node.active && node.amount > 0.05 && Calc.distance(x, y, node.x, node.y) <= radius) count++;
        }
        return count;
    }

    private static boolean reservedByProduction(World world, Unit builder) {
        for (Base base : world.bases.values()) {
            for (ProductionJob job : base.productionQueue) {
                if (job.kind == ProductionJobKind.STATION_PACKAGE
                        && builder.key().equals(job.reservedUnitKey)) return true;
            }
        }
        return false;
    }

    private static int livingBaseCount(World world, String factionId) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) count++;
        }
        return count;
    }

    private static int nextBaseNumber(World world, String factionId) {
        int max = 0;
        String prefix = factionId + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    private static Map<String, ConstructionPlan> plans(World world) {
        return PLANS.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
    }

    private static String key(String systemId, String factionId) {
        return (systemId == null ? "" : systemId) + "|" + (factionId == null ? "" : factionId);
    }

    private static String coordinate(double x, double y) {
        return "(" + (int)Math.round(x) + "," + (int)Math.round(y) + ")";
    }

    private static final class ConstructionPlan {
        final String systemId;
        final long seed;
        final String factionId;
        final String sourceBaseId;
        final String builderKey;
        final String packageType;
        final NpcBudgetCategory category;
        final double duration;
        double targetX;
        double targetY;
        double remaining;
        int replans;
        boolean waitingForSite;
        NpcConstructionPhase phase = NpcConstructionPhase.TRAVELLING;

        ConstructionPlan(String systemId, long seed, String factionId, String sourceBaseId,
                         String builderKey, String packageType, NpcBudgetCategory category,
                         double targetX, double targetY, double duration) {
            this.systemId = systemId;
            this.seed = seed;
            this.factionId = factionId;
            this.sourceBaseId = sourceBaseId;
            this.builderKey = builderKey;
            this.packageType = packageType;
            this.category = category;
            this.targetX = targetX;
            this.targetY = targetY;
            this.duration = Math.max(1.0, duration);
            this.remaining = this.duration;
        }
    }

    private record Site(double x, double y, double score) { }
}

enum NpcConstructionPhase {
    TRAVELLING,
    CONSTRUCTING
}

record NpcStationConstructionSnapshot(boolean active, String systemId, String builderKey,
                                      String packageType, NpcConstructionPhase phase,
                                      double targetX, double targetY, double duration,
                                      double remaining, int replans, boolean waitingForSite) {
    static final NpcStationConstructionSnapshot NONE = new NpcStationConstructionSnapshot(
            false, "", "", "", NpcConstructionPhase.TRAVELLING,
            0, 0, 0, 0, 0, false);
}
