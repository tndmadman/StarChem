package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Coordinates local organized-faction combat ships as deterministic squads.
 *
 * Ordinary tactical AI issues broad intentions first. This system then turns
 * eligible combat ships into cohesive role-aware squads. Expedition transit
 * and Phase 10 repair or evacuation orders remain authoritative.
 */
final class NpcSquadCombatSystem {
    private static final int MAX_SQUAD_SIZE = 5;
    private static final double REVIEW_SECONDS = 0.65;
    private static final double RECOVERY_HP_RATIO = 0.72;
    private static final double DAMAGED_RESERVE_RATIO = 0.86;
    private static final double WITHDRAW_AVERAGE_HP = 0.58;
    private static final double COHESION_DISTANCE = 760.0;
    private static final double REGROUP_THREAT_DISTANCE = 430.0;
    private static final double OVERKILL_ALLOWANCE = 1.12;
    private static final Map<World, Map<String, RuntimeState>> RUNTIMES = new WeakHashMap<>();

    private NpcSquadCombatSystem() { }

    static synchronized void update(World world, NpcFaction faction,
                                    NpcStrategicState strategy, double dt) {
        if (world == null || faction == null || faction.behavior() != NpcBehavior.FACTION) return;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        runtime.reviewTimer -= Math.max(0, Double.isFinite(dt) ? dt : 0);
        if (runtime.reviewTimer > 0) return;
        runtime.reviewTimer = REVIEW_SECONDS;

        List<Unit> combat = localCombatUnits(world, faction);
        if (combat.isEmpty()) {
            runtime.squads = List.of();
            return;
        }

        List<SquadState> squads = buildSquads(world, faction, combat, runtime.squads);
        Map<String, Double> fleetAssignedDamage = new LinkedHashMap<>();
        for (SquadState squad : squads) {
            commandSquad(world, faction, strategy, squad, fleetAssignedDamage);
        }
        runtime.squads = List.copyOf(squads);
    }

    static synchronized NpcSquadCombatSnapshot snapshot(World world, NpcFaction faction) {
        if (world == null || faction == null) return NpcSquadCombatSnapshot.NONE;
        RuntimeState runtime = runtime(world, faction);
        runtime.resetForSeed(world.systemSeed());
        List<NpcSquadView> views = new ArrayList<>();
        for (SquadState squad : runtime.squads) {
            views.add(new NpcSquadView(
                    squad.id,
                    squad.mode,
                    List.copyOf(squad.memberKeys),
                    Map.copyOf(squad.roles),
                    Map.copyOf(squad.targets),
                    squad.protectedKey,
                    squad.anchorX,
                    squad.anchorY,
                    squad.averageHp,
                    squad.maxSpread));
        }
        return new NpcSquadCombatSnapshot(List.copyOf(views));
    }

    static synchronized void clear(World world) {
        if (world != null) RUNTIMES.remove(world);
    }

    static synchronized Map<String,Object> capture(World world) {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String, RuntimeState> byKey = RUNTIMES.get(world);
        if (byKey == null) return out;
        List<Object> rows = new ArrayList<>();
        for (Map.Entry<String, RuntimeState> entry : byKey.entrySet()) {
            RuntimeState runtime = entry.getValue();
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("key", entry.getKey());
            row.put("seed", runtime.seed);
            row.put("reviewTimer", runtime.reviewTimer);
            List<Object> squads = new ArrayList<>();
            for (SquadState squad : runtime.squads) squads.add(captureSquad(squad));
            row.put("squads", squads);
            rows.add(row);
        }
        out.put("runtimes", rows);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        Map<String, RuntimeState> byKey = new LinkedHashMap<>();
        for (Object item : ServerSaveStore.list(data.get("runtimes"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String key = ServerSaveStore.string(row, "key", "");
            if (key.isBlank()) continue;
            RuntimeState runtime = new RuntimeState(ServerSaveStore.longValue(row, "seed", world.systemSeed()));
            runtime.reviewTimer = Math.max(0, ServerSaveStore.doubleValue(row, "reviewTimer", 0));
            List<SquadState> squads = new ArrayList<>();
            for (Object squadItem : ServerSaveStore.list(row.get("squads"))) {
                SquadState squad = restoreSquad(squadItem);
                if (squad != null) squads.add(squad);
            }
            runtime.squads = List.copyOf(squads);
            byKey.put(key, runtime);
        }
        if (byKey.isEmpty()) RUNTIMES.remove(world);
        else RUNTIMES.put(world, byKey);
    }

    private static Map<String,Object> captureSquad(SquadState squad) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", squad.id);
        out.put("memberKeys", List.copyOf(squad.memberKeys));
        Map<String,Object> roles = new LinkedHashMap<>();
        for (Map.Entry<String,NpcSquadRole> entry : squad.roles.entrySet()) roles.put(entry.getKey(), entry.getValue().name());
        out.put("roles", roles);
        out.put("targets", new LinkedHashMap<>(squad.targets));
        out.put("mode", squad.mode.name());
        out.put("protectedKey", squad.protectedKey);
        out.put("anchorX", squad.anchorX);
        out.put("anchorY", squad.anchorY);
        out.put("averageHp", squad.averageHp);
        out.put("maxSpread", squad.maxSpread);
        return out;
    }

    private static SquadState restoreSquad(Object saved) {
        Map<String,Object> data = ServerSaveStore.object(saved);
        String id = ServerSaveStore.string(data, "id", "");
        if (id.isBlank()) return null;
        SquadState squad = new SquadState(id);
        for (Object item : ServerSaveStore.list(data.get("memberKeys"))) {
            String key = ServerSaveStore.asString(item, "");
            if (!key.isBlank()) squad.memberKeys.add(key);
        }
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(data.get("roles")).entrySet()) {
            NpcSquadRole role = ServerSaveStore.enumValue(NpcSquadRole.class, entry.getValue(), null);
            if (role != null) squad.roles.put(entry.getKey(), role);
        }
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(data.get("targets")).entrySet()) {
            String target = ServerSaveStore.asString(entry.getValue(), "");
            if (!target.isBlank()) squad.targets.put(entry.getKey(), target);
        }
        squad.mode = ServerSaveStore.enumValue(NpcSquadMode.class, data.get("mode"), NpcSquadMode.HOLDING);
        squad.protectedKey = ServerSaveStore.string(data, "protectedKey", "");
        squad.anchorX = ServerSaveStore.doubleValue(data, "anchorX", 0);
        squad.anchorY = ServerSaveStore.doubleValue(data, "anchorY", 0);
        squad.averageHp = ServerSaveStore.doubleValue(data, "averageHp", 0);
        squad.maxSpread = ServerSaveStore.doubleValue(data, "maxSpread", 0);
        return squad;
    }

    private static List<Unit> localCombatUnits(World world, NpcFaction faction) {
        NpcExpeditionSnapshot expedition = NpcExpeditionSystem.snapshot(world, faction);
        boolean expeditionCombatAllowed = expedition.active()
                && world.activeSystemId().equals(expedition.targetSystemId())
                && (expedition.state() == NpcExpeditionState.ESTABLISHING
                || expedition.state() == NpcExpeditionState.DEFENDING);
        Set<String> expeditionCombat = new LinkedHashSet<>(expedition.combatKeys());

        List<Unit> result = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0 || !WeaponRules.armed(unit)) continue;
            boolean expeditionOwned = NpcExpeditionSystem.ownsUnit(world, unit.key());
            if (expeditionOwned && (!expeditionCombatAllowed || !expeditionCombat.contains(unit.key()))) continue;
            result.add(unit);
        }
        result.sort(Comparator.comparingInt(unit -> unit.unitId));
        return result;
    }

    private static List<SquadState> buildSquads(World world, NpcFaction faction,
                                                List<Unit> combat, List<SquadState> previous) {
        Map<String, NpcSquadMode> priorModes = new LinkedHashMap<>();
        for (SquadState squad : previous) priorModes.put(String.join(",", squad.memberKeys), squad.mode);

        int squadCount = Math.max(1, (combat.size() + MAX_SQUAD_SIZE - 1) / MAX_SQUAD_SIZE);
        List<SquadState> result = new ArrayList<>();
        for (int index = 0; index < squadCount; index++) {
            int start = index * combat.size() / squadCount;
            int end = (index + 1) * combat.size() / squadCount;
            List<Unit> members = new ArrayList<>(combat.subList(start, end));
            String id = world.activeSystemId() + ":" + faction.id() + ":S" + (index + 1);
            SquadState squad = new SquadState(id);
            for (Unit member : members) squad.memberKeys.add(member.key());
            assignRoles(members, squad);
            squad.mode = priorModes.getOrDefault(String.join(",", squad.memberKeys), NpcSquadMode.HOLDING);
            result.add(squad);
        }
        return result;
    }

    private static void assignRoles(List<Unit> members, SquadState squad) {
        Unit protectedUnit = members.stream()
                .filter(unit -> hpRatio(unit) >= RECOVERY_HP_RATIO)
                .max(Comparator
                        .comparingDouble((Unit unit) -> WeaponRules.maxRange(unit))
                        .thenComparingDouble(unit -> unit.type().size.scale)
                        .thenComparingInt(unit -> -unit.unitId))
                .orElse(members.get(0));
        squad.protectedKey = protectedUnit.key();

        boolean hasArtillery = false;
        for (Unit unit : members) {
            NpcSquadRole role;
            double hp = hpRatio(unit);
            double range = WeaponRules.maxRange(unit);
            if (hp < RECOVERY_HP_RATIO) {
                role = NpcSquadRole.RETREATING_CASUALTY;
            } else if (!WeaponRules.screenWeapons(unit).isEmpty()) {
                role = NpcSquadRole.SCREEN;
            } else if (range >= 900.0) {
                role = NpcSquadRole.ARTILLERY;
                hasArtillery = true;
            } else if (hp < DAMAGED_RESERVE_RATIO) {
                role = NpcSquadRole.RESERVE;
            } else {
                role = NpcSquadRole.LINE;
            }
            squad.roles.put(unit.key(), role);
        }

        if (hasArtillery && squad.roles.values().stream().noneMatch(role -> role == NpcSquadRole.SCREEN)) {
            members.stream()
                    .filter(unit -> squad.roles.get(unit.key()) == NpcSquadRole.LINE)
                    .max(Comparator.comparingDouble((Unit unit) -> unit.type().speed)
                            .thenComparingInt(unit -> -unit.unitId))
                    .ifPresent(unit -> squad.roles.put(unit.key(), NpcSquadRole.ESCORT));
        }
        if (members.size() >= MAX_SQUAD_SIZE) {
            Unit reserve = members.get(members.size() - 1);
            if (squad.roles.get(reserve.key()) == NpcSquadRole.LINE) {
                squad.roles.put(reserve.key(), NpcSquadRole.RESERVE);
            }
        }
    }

    private static void commandSquad(World world, NpcFaction faction,
                                     NpcStrategicState strategy, SquadState squad,
                                     Map<String, Double> fleetAssignedDamage) {
        List<Unit> members = livingMembers(world, squad.memberKeys);
        if (members.isEmpty()) return;

        List<Unit> active = new ArrayList<>();
        for (Unit unit : members) {
            NpcSquadRole role = squad.roles.getOrDefault(unit.key(), NpcSquadRole.LINE);
            if (role == NpcSquadRole.RETREATING_CASUALTY || recoveryOwns(unit, world)) continue;
            active.add(unit);
        }
        if (active.isEmpty()) {
            squad.mode = NpcSquadMode.WITHDRAWING;
            squad.targets.clear();
            updateMetrics(squad, members);
            return;
        }
        updateMetrics(squad, active);

        Base anchorBase = nearestFriendlyBase(world, faction.id(), squad.anchorX, squad.anchorY);
        double objectiveX = anchorBase == null ? squad.anchorX : anchorBase.x;
        double objectiveY = anchorBase == null ? squad.anchorY : anchorBase.y;
        NpcExpeditionSnapshot expedition = NpcExpeditionSystem.snapshot(world, faction);
        boolean expeditionDefense = expedition.active()
                && expedition.targetSystemId().equals(world.activeSystemId())
                && (expedition.state() == NpcExpeditionState.ESTABLISHING
                || expedition.state() == NpcExpeditionState.DEFENDING);

        List<TargetInfo> targets = targets(world, faction, strategy, expeditionDefense,
                objectiveX, objectiveY, squad.anchorX, squad.anchorY);
        boolean withdraw = strategy == NpcStrategicState.RETREAT
                || squad.averageHp < WITHDRAW_AVERAGE_HP
                || (!targets.isEmpty() && active.size() < 2);
        if (withdraw) {
            squad.mode = NpcSquadMode.WITHDRAWING;
            squad.targets.clear();
            withdraw(world, active, anchorBase, squad.anchorX, squad.anchorY);
            return;
        }

        double nearestThreat = nearestTargetDistance(targets, squad.anchorX, squad.anchorY);
        if (squad.maxSpread > COHESION_DISTANCE && nearestThreat > REGROUP_THREAT_DISTANCE) {
            squad.mode = NpcSquadMode.REGROUPING;
            squad.targets.clear();
            regroup(world, active, squad.anchorX, squad.anchorY);
            return;
        }

        if (targets.isEmpty()) {
            squad.mode = NpcSquadMode.HOLDING;
            squad.targets.clear();
            holdFormation(world, squad, active, objectiveX, objectiveY);
            return;
        }

        squad.mode = NpcSquadMode.ENGAGING;
        Map<String, String> assignments = assignTargets(
                world, faction, squad, active, targets, fleetAssignedDamage);
        squad.targets.clear();
        squad.targets.putAll(assignments);

        Unit protectedUnit = world.units.get(squad.protectedKey);
        TargetInfo primaryThreat = nearestTargetTo(targets,
                protectedUnit == null ? squad.anchorX : protectedUnit.x,
                protectedUnit == null ? squad.anchorY : protectedUnit.y);
        int screenIndex = 0;
        for (Unit unit : active) {
            NpcSquadRole role = squad.roles.getOrDefault(unit.key(), NpcSquadRole.LINE);
            String targetKey = assignments.getOrDefault(unit.key(), "");
            TargetInfo target = targetByKey(targets, targetKey);
            if ((role == NpcSquadRole.SCREEN || role == NpcSquadRole.ESCORT)
                    && protectedUnit != null && protectedUnit != unit && primaryThreat != null) {
                if (commandScreen(world, unit, protectedUnit, primaryThreat, screenIndex++)) continue;
            }
            if (role == NpcSquadRole.RESERVE && target != null
                    && Calc.distance(target.x, target.y, objectiveX, objectiveY) > 1100.0) {
                moveToFormation(world, unit, objectiveX, objectiveY, role, unit.unitId);
                continue;
            }
            if (target != null) commandAtRange(world, unit, role, target);
            else moveToFormation(world, unit, objectiveX, objectiveY, role, unit.unitId);
        }
    }

    private static Map<String, String> assignTargets(World world, NpcFaction faction,
                                                     SquadState squad, List<Unit> units,
                                                     List<TargetInfo> targets,
                                                     Map<String, Double> assignedDamage) {
        List<Unit> ordered = new ArrayList<>(units);
        ordered.sort(Comparator
                .comparingInt((Unit unit) -> rolePriority(squad.roles.get(unit.key())))
                .thenComparingInt(unit -> unit.unitId));
        Map<String, String> assignments = new LinkedHashMap<>();
        for (Unit unit : ordered) {
            NpcSquadRole role = squad.roles.getOrDefault(unit.key(), NpcSquadRole.LINE);
            TargetInfo best = chooseTarget(world, faction, unit, role, targets, assignedDamage, true);
            if (best == null) best = chooseTarget(world, faction, unit, role, targets, assignedDamage, false);
            if (best == null) continue;
            assignments.put(unit.key(), best.key);
            assignedDamage.merge(best.key, projectedVolley(unit, best), Double::sum);
        }
        return assignments;
    }

    private static TargetInfo chooseTarget(World world, NpcFaction faction, Unit unit,
                                           NpcSquadRole role, List<TargetInfo> targets,
                                           Map<String, Double> assignedDamage,
                                           boolean rejectSaturated) {
        TargetInfo best = null;
        double bestScore = -Double.MAX_VALUE;
        for (TargetInfo target : targets) {
            double assigned = assignedDamage.getOrDefault(target.key, 0.0);
            if (rejectSaturated && assigned >= target.effectiveHp * OVERKILL_ALLOWANCE) continue;
            double distance = Calc.distance(unit.x, unit.y, target.x, target.y);
            double range = Math.max(1.0, WeaponRules.maxRange(unit)
                    * SystemModifierRules.weaponRange(world));
            double score = target.baseScore + target.threatScore;
            score += (1.0 - target.hpRatio) * 105.0;
            score -= distance / Math.max(120.0, range) * 18.0;
            score -= assigned / Math.max(1.0, target.effectiveHp) * 190.0;
            if (target.worker && faction.preferWorkerTargets()) score += 52.0;
            if (target.base && faction.attackBases()) score += 62.0;
            if (target.armed && (role == NpcSquadRole.SCREEN || role == NpcSquadRole.ESCORT)) score += 30.0;
            if (role == NpcSquadRole.ARTILLERY && target.base) score += 42.0;
            if (role == NpcSquadRole.RESERVE && distance > range * 1.25) score -= 50.0;
            if (target.key.equals(unit.attackTarget)) score += 16.0;
            score -= Math.floorMod(target.key.hashCode(), 10_000) * 0.000001;
            if (score > bestScore) {
                bestScore = score;
                best = target;
            }
        }
        return best;
    }

    private static boolean commandScreen(World world, Unit screen, Unit protectedUnit,
                                         TargetInfo threat, int index) {
        double dx = threat.x - protectedUnit.x;
        double dy = threat.y - protectedUnit.y;
        double length = Math.max(1.0, Math.hypot(dx, dy));
        double forward = 145.0 + Math.min(65.0, protectedUnit.type().size.scale * 22.0);
        double lateral = (index % 2 == 0 ? 1 : -1) * (45.0 + (index / 2) * 28.0);
        double nx = dx / length;
        double ny = dy / length;
        double x = Calc.clamp(protectedUnit.x + nx * forward - ny * lateral, 0, world.width);
        double y = Calc.clamp(protectedUnit.y + ny * forward + nx * lateral, 0, world.height);
        double distanceToSlot = Calc.distance(screen.x, screen.y, x, y);
        double threatDistance = Calc.distance(screen.x, screen.y, threat.x, threat.y);
        double range = Math.max(1.0, WeaponRules.maxRange(screen)
                * SystemModifierRules.weaponRange(world));
        if (distanceToSlot > 72.0 && threatDistance > range * 0.58) {
            issueMove(screen, x, y);
            return true;
        }
        if (threatDistance <= range * 1.10) {
            issueAttack(screen, threat.key);
            return true;
        }
        issueMove(screen, x, y);
        return true;
    }

    private static void commandAtRange(World world, Unit unit, NpcSquadRole role, TargetInfo target) {
        double range = Math.max(1.0, WeaponRules.maxRange(unit)
                * SystemModifierRules.weaponRange(world));
        double preferred = range * preferredRangeFraction(role);
        double distance = Calc.distance(unit.x, unit.y, target.x, target.y);
        double minimum = preferred * (role == NpcSquadRole.SCREEN ? 0.28 : 0.58);
        if (distance < minimum && role != NpcSquadRole.SCREEN) {
            double dx = unit.x - target.x;
            double dy = unit.y - target.y;
            double length = Math.max(1.0, Math.hypot(dx, dy));
            double side = Math.floorMod(unit.unitId, 2) == 0 ? 1.0 : -1.0;
            double x = target.x + dx / length * preferred - dy / length * 55.0 * side;
            double y = target.y + dy / length * preferred + dx / length * 55.0 * side;
            issueMove(unit, Calc.clamp(x, 0, world.width), Calc.clamp(y, 0, world.height));
            return;
        }
        issueAttack(unit, target.key);
    }

    private static void withdraw(World world, List<Unit> units, Base base,
                                 double fallbackX, double fallbackY) {
        double x = base == null ? fallbackX : base.x;
        double y = base == null ? fallbackY : base.y;
        int index = 0;
        for (Unit unit : units) {
            double angle = index++ * 1.7;
            issueMove(unit,
                    Calc.clamp(x + Math.cos(angle) * 125.0, 0, world.width),
                    Calc.clamp(y + Math.sin(angle) * 125.0, 0, world.height));
        }
    }

    private static void regroup(World world, List<Unit> units, double x, double y) {
        int index = 0;
        for (Unit unit : units) {
            double angle = index * 2.15;
            double radius = index == 0 ? 0 : 75.0 + (index / 4) * 45.0;
            issueMove(unit,
                    Calc.clamp(x + Math.cos(angle) * radius, 0, world.width),
                    Calc.clamp(y + Math.sin(angle) * radius, 0, world.height));
            index++;
        }
    }

    private static void holdFormation(World world, SquadState squad, List<Unit> units,
                                      double x, double y) {
        for (Unit unit : units) {
            NpcSquadRole role = squad.roles.getOrDefault(unit.key(), NpcSquadRole.LINE);
            moveToFormation(world, unit, x, y, role, unit.unitId);
        }
    }

    private static void moveToFormation(World world, Unit unit, double x, double y,
                                        NpcSquadRole role, int ordinal) {
        double radius = switch (role) {
            case SCREEN -> 230.0;
            case ESCORT -> 175.0;
            case LINE -> 145.0;
            case ARTILLERY -> 285.0;
            case RESERVE -> 350.0;
            case RETREATING_CASUALTY -> 80.0;
        };
        double angle = Math.floorMod(ordinal, 16) * Math.PI * 2.0 / 16.0;
        double tx = Calc.clamp(x + Math.cos(angle) * radius, 0, world.width);
        double ty = Calc.clamp(y + Math.sin(angle) * radius, 0, world.height);
        if (Calc.distance(unit.x, unit.y, tx, ty) > 42.0) issueMove(unit, tx, ty);
        else hold(unit);
    }

    private static List<TargetInfo> targets(World world, NpcFaction faction,
                                            NpcStrategicState strategy,
                                            boolean expeditionDefense,
                                            double objectiveX, double objectiveY,
                                            double squadX, double squadY) {
        boolean aggressive = strategy.allowsRaid();
        double defenseRadius = expeditionDefense ? 2300.0 : Math.max(500.0, faction.defendRange());
        List<TargetInfo> result = new ArrayList<>();
        if (faction.attackUnits()) {
            for (Unit enemy : world.units.values()) {
                if (enemy.hp <= 0 || !hostile(faction, enemy.playerId)) continue;
                if (!aggressive && !expeditionDefense
                        && Calc.distance(enemy.x, enemy.y, objectiveX, objectiveY) > defenseRadius
                        && Calc.distance(enemy.x, enemy.y, squadX, squadY) > defenseRadius) continue;
                if (expeditionDefense
                        && Calc.distance(enemy.x, enemy.y, objectiveX, objectiveY) > defenseRadius) continue;
                result.add(TargetInfo.unit(enemy));
            }
        }
        if (faction.attackBases()) {
            for (Base enemy : world.bases.values()) {
                if (enemy.hp <= 0 || !hostile(faction, enemy.playerId)) continue;
                if (!aggressive && !expeditionDefense
                        && Calc.distance(enemy.x, enemy.y, objectiveX, objectiveY) > defenseRadius
                        && Calc.distance(enemy.x, enemy.y, squadX, squadY) > defenseRadius) continue;
                if (expeditionDefense
                        && Calc.distance(enemy.x, enemy.y, objectiveX, objectiveY) > defenseRadius) continue;
                result.add(TargetInfo.base(enemy));
            }
        }
        result.sort(Comparator.comparing(target -> target.key));
        return result;
    }

    private static boolean hostile(NpcFaction faction, String ownerId) {
        if (ownerId == null || ownerId.isBlank() || faction.id().equals(ownerId)) return false;
        return !NpcRules.isNpcFaction(ownerId) || faction.attackNpcFactions();
    }

    private static void updateMetrics(SquadState squad, List<Unit> members) {
        double x = 0;
        double y = 0;
        double hp = 0;
        for (Unit member : members) {
            x += member.x;
            y += member.y;
            hp += hpRatio(member);
        }
        squad.anchorX = x / members.size();
        squad.anchorY = y / members.size();
        squad.averageHp = hp / members.size();
        squad.maxSpread = 0;
        for (Unit member : members) {
            squad.maxSpread = Math.max(squad.maxSpread,
                    Calc.distance(member.x, member.y, squad.anchorX, squad.anchorY));
        }
    }

    private static boolean recoveryOwns(Unit unit, World world) {
        if (unit == null) return false;
        if (hpRatio(unit) < RECOVERY_HP_RATIO) return true;
        if (unit.orderType != UnitOrderType.ESCORT || world == null) return false;
        Unit escorted = CombatTarget.unit(world, unit.orderTarget);
        return escorted != null && escorted.hp > 0 && hpRatio(escorted) < 0.98;
    }

    private static double projectedVolley(Unit unit, TargetInfo target) {
        double maxRange = Math.max(1.0, WeaponRules.maxRange(unit));
        double effectiveDistance = Math.min(maxRange * 0.82,
                Calc.distance(unit.x, unit.y, target.x, target.y));
        double damage = WeaponRules.volley(unit, effectiveDistance).damage();
        return Math.max(1.0, damage);
    }

    private static double preferredRangeFraction(NpcSquadRole role) {
        return switch (role) {
            case SCREEN -> 0.43;
            case ESCORT -> 0.58;
            case LINE -> 0.73;
            case ARTILLERY -> 0.90;
            case RESERVE -> 0.80;
            case RETREATING_CASUALTY -> 0.35;
        };
    }

    private static int rolePriority(NpcSquadRole role) {
        if (role == null) return 3;
        return switch (role) {
            case ARTILLERY -> 0;
            case LINE -> 1;
            case SCREEN, ESCORT -> 2;
            case RESERVE -> 3;
            case RETREATING_CASUALTY -> 4;
        };
    }

    private static double hpRatio(Unit unit) {
        return unit.hp / Math.max(1.0, unit.type().maxHp);
    }

    private static List<Unit> livingMembers(World world, Collection<String> keys) {
        List<Unit> result = new ArrayList<>();
        for (String key : keys) {
            Unit unit = world.units.get(key);
            if (unit != null && unit.hp > 0) result.add(unit);
        }
        return result;
    }

    private static Base nearestFriendlyBase(World world, String factionId, double x, double y) {
        Base best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Base base : world.bases.values()) {
            if (!factionId.equals(base.playerId) || base.hp <= 0) continue;
            double distance = Calc.distance(x, y, base.x, base.y);
            if (distance < bestDistance) {
                best = base;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double nearestTargetDistance(List<TargetInfo> targets, double x, double y) {
        double best = Double.MAX_VALUE;
        for (TargetInfo target : targets) best = Math.min(best, Calc.distance(x, y, target.x, target.y));
        return best;
    }

    private static TargetInfo nearestTargetTo(List<TargetInfo> targets, double x, double y) {
        TargetInfo best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TargetInfo target : targets) {
            double distance = Calc.distance(x, y, target.x, target.y);
            if (distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static TargetInfo targetByKey(List<TargetInfo> targets, String key) {
        if (key == null || key.isBlank()) return null;
        for (TargetInfo target : targets) if (key.equals(target.key)) return target;
        return null;
    }

    private static void issueMove(Unit unit, double x, double y) {
        if (unit != null) unit.issueMove(x, y);
    }

    private static void issueAttack(Unit unit, String targetKey) {
        if (unit != null) unit.issueAttack(targetKey);
    }

    private static void hold(Unit unit) {
        unit.clearOrder();
        unit.attackTarget = "";
        unit.task = UnitTask.IDLE;
        unit.targetX = unit.x;
        unit.targetY = unit.y;
    }

    private static RuntimeState runtime(World world, NpcFaction faction) {
        Map<String, RuntimeState> byKey = RUNTIMES.computeIfAbsent(world, ignored -> new LinkedHashMap<>());
        String key = world.activeSystemId() + "|" + faction.id();
        return byKey.computeIfAbsent(key, ignored -> new RuntimeState(world.systemSeed()));
    }

    private static final class RuntimeState {
        long seed;
        double reviewTimer;
        List<SquadState> squads = List.of();

        RuntimeState(long seed) { this.seed = seed; }

        void resetForSeed(long currentSeed) {
            if (seed == currentSeed) return;
            seed = currentSeed;
            reviewTimer = 0;
            squads = List.of();
        }
    }

    private static final class SquadState {
        final String id;
        final List<String> memberKeys = new ArrayList<>();
        final Map<String, NpcSquadRole> roles = new LinkedHashMap<>();
        final Map<String, String> targets = new LinkedHashMap<>();
        NpcSquadMode mode = NpcSquadMode.HOLDING;
        String protectedKey = "";
        double anchorX;
        double anchorY;
        double averageHp;
        double maxSpread;

        SquadState(String id) { this.id = id; }
    }

    private static final class TargetInfo {
        final String key;
        final double x;
        final double y;
        final double effectiveHp;
        final double hpRatio;
        final double threatScore;
        final double baseScore;
        final boolean armed;
        final boolean worker;
        final boolean base;

        TargetInfo(String key, double x, double y, double effectiveHp, double hpRatio,
                   double threatScore, double baseScore,
                   boolean armed, boolean worker, boolean base) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.effectiveHp = Math.max(1.0, effectiveHp);
            this.hpRatio = Math.max(0.0, Math.min(1.0, hpRatio));
            this.threatScore = threatScore;
            this.baseScore = baseScore;
            this.armed = armed;
            this.worker = worker;
            this.base = base;
        }

        static TargetInfo unit(Unit unit) {
            ShipType type = unit.type();
            boolean armed = WeaponRules.armed(type);
            double maxEffective = Math.max(1.0, type.maxHp + type.maxShield);
            double currentEffective = Math.max(0.0, unit.hp) + Math.max(0.0, unit.shield);
            WeaponVolley volley = WeaponRules.volley(type,
                    Math.max(1.0, WeaponRules.maxRange(type) * 0.75));
            double dps = volley.damage() <= 0 ? 0
                    : volley.damage() / Math.max(0.2, volley.cooldownSeconds());
            double threat = armed ? 85.0 + dps * 0.65 + type.size.scale * 18.0 : 8.0;
            double strategic = type.baseBuilder ? 88.0
                    : !type.harvestKinds.isEmpty() ? 48.0
                    : type.cargoCapacity > 120 ? 32.0 : 18.0;
            return new TargetInfo(CombatTarget.unit(unit), unit.x, unit.y,
                    currentEffective, currentEffective / maxEffective,
                    threat, strategic, armed, !type.harvestKinds.isEmpty(), false);
        }

        static TargetInfo base(Base base) {
            BaseType type = base.type();
            double maxEffective = Math.max(1.0, type.maxHp + type.maxShield);
            double currentEffective = Math.max(0.0, base.hp) + Math.max(0.0, base.shield);
            double strategic = 125.0;
            if ("shipyard".equals(base.typeId)) strategic += 62.0;
            else if ("manufacturing".equals(base.typeId)) strategic += 45.0;
            else if ("laboratory".equals(base.typeId)) strategic += 38.0;
            return new TargetInfo(CombatTarget.base(base), base.x, base.y,
                    currentEffective, currentEffective / maxEffective,
                    35.0, strategic, false, false, true);
        }
    }
}

enum NpcSquadRole {
    SCREEN,
    LINE,
    ARTILLERY,
    ESCORT,
    RESERVE,
    RETREATING_CASUALTY
}

enum NpcSquadMode {
    HOLDING,
    REGROUPING,
    ENGAGING,
    WITHDRAWING
}

record NpcSquadView(String id, NpcSquadMode mode, List<String> members,
                    Map<String, NpcSquadRole> roles, Map<String, String> targets,
                    String protectedKey, double anchorX, double anchorY,
                    double averageHp, double maxSpread) { }

record NpcSquadCombatSnapshot(List<NpcSquadView> squads) {
    static final NpcSquadCombatSnapshot NONE = new NpcSquadCombatSnapshot(List.of());
}
