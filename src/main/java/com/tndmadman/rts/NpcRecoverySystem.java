package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Handles local recovery for organized NPC factions.
 *
 * Damaged ships retreat to a friendly station and pay for hull repairs. A
 * stationless group attempts an emergency rebuild, then evacuation. Assets are
 * scuttled only after the group has no station, no wormhole route, and no
 * builder/depot recovery path for the full stranded grace period.
 */
final class NpcRecoverySystem {
    private static final double REPAIR_START_RATIO = 0.72;
    private static final double RETREAT_REPAIR_RATIO = 0.95;
    private static final double REPAIR_HP_PER_SECOND = 18.0;
    private static final double STRANDED_GRACE_SECONDS = 45.0;
    private static final List<Cost> EMERGENCY_OUTPOST_COST = List.of(
            new Cost(Material.IRON, 180.0),
            new Cost(Material.COPPER, 90.0),
            new Cost(Material.SILICATES, 100.0),
            new Cost(Material.ICE, 40.0));
    private static final Map<World, Map<String, RecoveryRuntime>> RUNTIMES = new WeakHashMap<>();

    private NpcRecoverySystem() { }

    static synchronized void update(World world, NpcFaction faction) {
        if (world == null || faction == null || !faction.enabled()
                || faction.behavior() != NpcBehavior.FACTION) return;

        String systemId = world.activeSystemId();
        RecoveryRuntime runtime = runtime(world, faction, systemId);
        runtime.resetForSeed(world.systemSeed());
        double dt = runtime.advance(world.systemTime());

        List<Unit> units = livingUnits(world, faction.id());
        List<Base> bases = livingBases(world, faction.id());
        if (units.isEmpty()) {
            runtime.strandedSeconds = 0;
            transition(world, faction, runtime, NpcRecoveryState.IDLE,
                    "no local ships require recovery");
            return;
        }

        if (!bases.isEmpty()) {
            runtime.strandedSeconds = 0;
            boolean repairing = repairDamagedShips(world, faction, units, bases, dt);
            transition(world, faction, runtime,
                    repairing ? NpcRecoveryState.REPAIRING : NpcRecoveryState.ACTIVE,
                    repairing ? "damaged ships returning for paid hull repair" : "local station support available");
            return;
        }

        if (tryEmergencyRebuild(world, faction, units)) {
            runtime.strandedSeconds = 0;
            transition(world, faction, runtime, NpcRecoveryState.REBUILDING,
                    "emergency foothold established");
            return;
        }

        WormholeGate gate = evacuationGate(world, faction);
        if (gate != null) {
            runtime.strandedSeconds = 0;
            evacuate(units, gate);
            transition(world, faction, runtime, NpcRecoveryState.EVACUATING,
                    "withdrawing through wormhole to " + gate.toSystemId);
            return;
        }

        rallyAtRecoveryAsset(world, units);
        runtime.strandedSeconds += dt;
        if (hasRecoveryPotential(units)) {
            transition(world, faction, runtime, NpcRecoveryState.STRANDED_RECOVERY,
                    "stranded group preserving builder or mobile-depot recovery assets");
            return;
        }

        if (runtime.strandedSeconds + 0.001 < STRANDED_GRACE_SECONDS) {
            transition(world, faction, runtime, NpcRecoveryState.STRANDED,
                    "no escape route; scuttle grace period active");
            return;
        }

        int removed = scuttle(world, faction.id());
        transition(world, faction, runtime, NpcRecoveryState.SCUTTLED,
                "recovery impossible; scuttled " + removed + " stranded ship(s)");
    }

    static synchronized NpcRecoveryState state(World world, NpcFaction faction, String systemId) {
        if (world == null || faction == null) return NpcRecoveryState.IDLE;
        return runtime(world, faction, systemId).state;
    }

    static synchronized double strandedSeconds(World world, NpcFaction faction, String systemId) {
        if (world == null || faction == null) return 0;
        return runtime(world, faction, systemId).strandedSeconds;
    }

    private static boolean repairDamagedShips(World world, NpcFaction faction, List<Unit> units,
                                              List<Base> bases, double dt) {
        boolean retreating = NpcStrategicDirector.state(world, faction) == NpcStrategicState.RETREAT;
        double threshold = retreating ? RETREAT_REPAIR_RATIO : REPAIR_START_RATIO;
        boolean repairing = false;
        for (Unit unit : units) {
            ShipType type = unit.type();
            double ratio = unit.hp / Math.max(1.0, type.maxHp);
            if (ratio + 0.0001 >= threshold && unit.hp + 0.001 >= type.maxHp) continue;
            if (ratio + 0.0001 >= threshold && !retreating) continue;

            repairing = true;
            Base station = nearestBase(bases, unit.x, unit.y);
            if (station == null) continue;
            double serviceRange = Math.max(55.0, station.type().unloadRange * 0.72);
            if (Calc.distance(unit.x, unit.y, station.x, station.y) > serviceRange) {
                unit.issueMove(station.x, station.y);
                continue;
            }

            unit.clearOrder();
            unit.attackTarget = "";
            unit.task = UnitTask.IDLE;
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            double missing = Math.max(0, type.maxHp - unit.hp);
            double amount = Math.min(missing, REPAIR_HP_PER_SECOND * Math.max(0, dt));
            if (amount <= 0.001) continue;
            List<Cost> cost = repairCost(amount);
            if (!NpcResourceBudget.spend(world, faction, NpcBudgetCategory.STATION_RECOVERY, cost)) continue;
            unit.hp = Math.min(type.maxHp, unit.hp + amount);
        }
        return repairing;
    }

    private static List<Cost> repairCost(double hp) {
        return List.of(
                new Cost(Material.IRON, Math.max(0.05, hp * 0.04)),
                new Cost(Material.COPPER, Math.max(0.02, hp * 0.015)));
    }

    private static boolean tryEmergencyRebuild(World world, NpcFaction faction, List<Unit> units) {
        Unit loadedBuilder = units.stream()
                .filter(unit -> unit.type().baseBuilder && !unit.basePackageType.isBlank())
                .min(Comparator.comparingInt(unit -> unit.unitId))
                .orElse(null);
        if (loadedBuilder != null && Rules.BASES.containsKey(loadedBuilder.basePackageType)) {
            return world.placePackage(loadedBuilder);
        }

        Unit builder = units.stream()
                .filter(unit -> unit.type().baseBuilder && unit.basePackageType.isBlank())
                .min(Comparator.comparingInt(unit -> unit.unitId))
                .orElse(null);
        if (builder == null) return false;

        List<Cost> cost = emergencyCost(faction);
        if (!canCoverFromUnits(units, cost)) return false;
        consumeFromUnits(units, cost);

        String typeId = Rules.BASES.containsKey(faction.baseType()) ? faction.baseType() : Rules.DEFAULT_BASE;
        String baseId = nextBaseId(world, faction.id());
        Base base = new Base(baseId, faction.id(), typeId,
                Calc.clamp(builder.x, 0, world.width), Calc.clamp(builder.y, 0, world.height));
        world.units.remove(builder.key());
        transferSurplusToBase(units, builder, base);
        world.bases.put(base.id, base);
        world.status = faction.name() + " established an emergency " + base.type().name + ".";
        return true;
    }

    private static List<Cost> emergencyCost(NpcFaction faction) {
        BaseType type = Rules.findBase(faction.baseType());
        if (type != null && type.buildCost != null && !type.buildCost.isEmpty()) return type.buildCost;
        return EMERGENCY_OUTPOST_COST;
    }

    private static boolean canCoverFromUnits(List<Unit> units, List<Cost> cost) {
        for (Cost need : cost) {
            double total = 0;
            for (Unit unit : units) total += unit.inventory.getOrDefault(need.material(), 0.0);
            if (total + 0.001 < need.amount()) return false;
        }
        return true;
    }

    private static void consumeFromUnits(List<Unit> units, List<Cost> cost) {
        List<Unit> ordered = new ArrayList<>(units);
        ordered.sort(Comparator.comparingInt(unit -> unit.unitId));
        for (Cost need : cost) {
            double remaining = need.amount();
            for (Unit unit : ordered) {
                if (remaining <= 0.001) break;
                double held = unit.inventory.getOrDefault(need.material(), 0.0);
                if (held <= 0.001) continue;
                double take = Math.min(held, remaining);
                double left = held - take;
                if (left <= 0.05) unit.inventory.remove(need.material());
                else unit.inventory.put(need.material(), left);
                remaining -= take;
            }
        }
    }

    private static void transferSurplusToBase(List<Unit> units, Unit consumedBuilder, Base base) {
        for (Unit unit : units) {
            if (unit == consumedBuilder) continue;
            for (Material material : new ArrayList<>(unit.inventory.keySet())) {
                double amount = unit.inventory.getOrDefault(material, 0.0);
                if (amount <= 0.001) continue;
                HangarStore.add(base.inventory, material, amount);
                unit.inventory.remove(material);
            }
        }
    }

    private static WormholeGate evacuationGate(World world, NpcFaction faction) {
        if (world.wormholes.isEmpty()) return null;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        String homeId = NpcFactionRuntime.homeSystemIdFor(faction);
        WormholeGate best = null;
        double bestScore = -Double.MAX_VALUE;
        for (WormholeGate gate : world.wormholes) {
            GalaxyMapSystem target = system(map, gate.toSystemId);
            if (target != null && target.home()) continue;
            double score = 0;
            if (homeId.equals(gate.toSystemId)) score += 1000;
            if (target != null && faction.id().equals(target.controllerId())) score += 400;
            if (target != null && target.staticSystem()) score += 80;
            if (target != null && target.controlStatus() == SystemControlStatus.NEUTRAL) score += 20;
            score -= Math.floorMod(gate.toSystemId.hashCode(), 1000) * 0.0001;
            if (score > bestScore) {
                bestScore = score;
                best = gate;
            }
        }
        return best;
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        if (snapshot == null || snapshot.systems() == null || id == null) return null;
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && id.equals(system.id())) return system;
        }
        return null;
    }

    private static void evacuate(List<Unit> units, WormholeGate gate) {
        for (Unit unit : units) unit.issueMove(gate.x, gate.y);
    }

    private static void rallyAtRecoveryAsset(World world, List<Unit> units) {
        Unit anchor = units.stream()
                .filter(MobileDepot::isDepot)
                .min(Comparator.comparingInt(unit -> unit.unitId))
                .orElseGet(() -> units.stream()
                        .filter(unit -> unit.type().baseBuilder)
                        .min(Comparator.comparingInt(unit -> unit.unitId))
                        .orElse(null));
        if (anchor == null) return;
        int index = 0;
        for (Unit unit : units) {
            if (unit == anchor) continue;
            double angle = index++ * 1.7;
            unit.issueMove(
                    Calc.clamp(anchor.x + Math.cos(angle) * 110.0, 0, world.width),
                    Calc.clamp(anchor.y + Math.sin(angle) * 110.0, 0, world.height));
        }
    }

    private static boolean hasRecoveryPotential(List<Unit> units) {
        boolean builder = false;
        boolean depot = false;
        boolean worker = false;
        for (Unit unit : units) {
            builder |= unit.type().baseBuilder;
            depot |= MobileDepot.isDepot(unit);
            worker |= !unit.type().harvestKinds.isEmpty();
        }
        return builder && (depot || worker || canCoverFromUnits(units, EMERGENCY_OUTPOST_COST));
    }

    private static int scuttle(World world, String factionId) {
        int removed = 0;
        for (Unit unit : new ArrayList<>(world.units.values())) {
            if (!factionId.equals(unit.playerId) || unit.hp <= 0) continue;
            world.explodeUnit(unit);
            world.units.remove(unit.key());
            removed++;
        }
        return removed;
    }

    private static Base nearestBase(List<Base> bases, double x, double y) {
        Base best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Base base : bases) {
            double distance = Calc.distance(x, y, base.x, base.y);
            if (distance < bestDistance) {
                best = base;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static List<Unit> livingUnits(World world, String factionId) {
        List<Unit> units = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (factionId.equals(unit.playerId) && unit.hp > 0) units.add(unit);
        }
        return units;
    }

    private static List<Base> livingBases(World world, String factionId) {
        List<Base> bases = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) bases.add(base);
        }
        return bases;
    }

    private static String nextBaseId(World world, String factionId) {
        int max = 0;
        String prefix = factionId + ":B";
        for (String id : world.bases.keySet()) {
            if (!id.startsWith(prefix)) continue;
            try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { }
        }
        return prefix + (max + 1);
    }

    private static RecoveryRuntime runtime(World world, NpcFaction faction, String systemId) {
        Map<String, RecoveryRuntime> byKey = RUNTIMES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
        String key = faction.id() + "|" + (systemId == null ? "" : systemId);
        return byKey.computeIfAbsent(key, ignored -> new RecoveryRuntime(world.systemSeed()));
    }

    private static void transition(World world, NpcFaction faction, RecoveryRuntime runtime,
                                   NpcRecoveryState next, String detail) {
        if (runtime.state == next) return;
        NpcRecoveryState previous = runtime.state;
        runtime.state = next;
        AiDevLog.add(world, faction, "recovery " + previous + " -> " + next + " [" + detail + "]");
    }

    private static final class RecoveryRuntime {
        long seed;
        NpcRecoveryState state = NpcRecoveryState.IDLE;
        double lastSystemTime = Double.NaN;
        double strandedSeconds;

        RecoveryRuntime(long seed) { this.seed = seed; }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            state = NpcRecoveryState.IDLE;
            lastSystemTime = Double.NaN;
            strandedSeconds = 0;
        }

        double advance(double systemTime) {
            double dt = Double.isFinite(lastSystemTime) && systemTime >= lastSystemTime
                    ? Math.min(5.0, systemTime - lastSystemTime) : 0;
            lastSystemTime = systemTime;
            return Math.max(0, dt);
        }
    }
}

enum NpcRecoveryState {
    IDLE,
    ACTIVE,
    REPAIRING,
    REBUILDING,
    EVACUATING,
    STRANDED_RECOVERY,
    STRANDED,
    SCUTTLED
}
