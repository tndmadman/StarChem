package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Authoritative information warfare shared by fog, snapshots, AI, radar automation, and UI. */
final class IntelWarfareSystem {
    static final String CONTACT_SMALL = "sensor_contact_small";
    static final String CONTACT_MEDIUM = "sensor_contact_medium";
    static final String CONTACT_LARGE = "sensor_contact_large";
    static final String CONTACT_STATION = "sensor_contact_station";

    private static final double CONTACT_THRESHOLD = 0.62;
    private static final double CLASSIFIED_THRESHOLD = 0.84;
    private static final double IDENTIFIED_THRESHOLD = 1.10;
    private static final double DETAILED_THRESHOLD = 1.52;
    private static final double MEMORY_SECONDS = 45.0;
    private static final double RESPONSE_INTERVAL = 0.45;
    private static final double RESPONSE_MEMORY_SECONDS = 18.0;
    private static final Map<String, StructureIntelRule> STRUCTURES = loadStructureRules();
    private static final Map<World, RuntimeState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private IntelWarfareSystem() { }

    enum DetectionStage {
        NONE,
        CONTACT,
        CLASSIFIED,
        IDENTIFIED,
        DETAILED;

        boolean atLeast(DetectionStage other) { return ordinal() >= other.ordinal(); }
    }

    enum RadarMode {
        PASSIVE(0.64, 0.62, 0.00),
        ACTIVE(1.00, 1.55, 0.08),
        FOCUSED(1.55, 2.25, 0.28);

        final double rangeMultiplier;
        final double emissionMultiplier;
        final double identificationBonus;

        RadarMode(double rangeMultiplier, double emissionMultiplier, double identificationBonus) {
            this.rangeMultiplier = rangeMultiplier;
            this.emissionMultiplier = emissionMultiplier;
            this.identificationBonus = identificationBonus;
        }

        RadarMode next() {
            return switch (this) {
                case PASSIVE -> ACTIVE;
                case ACTIVE -> FOCUSED;
                case FOCUSED -> PASSIVE;
            };
        }
    }

    record IntelSensor(double x, double y, double range, double identificationBonus, String sourceKey) {
        double rangeSquared() { return range * range; }
    }

    record IntelMemory(String key, String ownerId, String typeId, boolean station, DetectionStage stage,
                       double x, double y, double vx, double vy, double lastSeenTime, double uncertainty,
                       boolean decoySuspected) { }

    static void update(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt <= 0) return;
        SystemRuntime runtime = systemRuntime(world);
        runtime.responseTimer -= dt;
        updateMemory(world, runtime);
        if (runtime.responseTimer <= 0) {
            runtime.responseTimer = RESPONSE_INTERVAL;
            dispatchRadarResponses(world, runtime);
            investigateLastKnownContacts(world, runtime);
        }
        prune(world, runtime);
    }

    static boolean isRadar(String typeId) { return "radar".equals(rule(typeId).role); }
    static boolean isJammer(String typeId) { return "jammer".equals(rule(typeId).role); }
    static boolean isDecoy(String typeId) { return "decoy".equals(rule(typeId).role); }
    static int radarTier(String typeId) { return Math.max(0, rule(typeId).tier); }
    static int dispatchLimit(String typeId) { return Math.max(0, rule(typeId).resourceDispatchLimit); }
    static int surveyPower(String typeId) { return Math.max(0, rule(typeId).surveyPower); }

    static RadarMode radarMode(World world, Base radar) {
        if (world == null || radar == null || !isRadar(radar.typeId)) return RadarMode.ACTIVE;
        SystemRuntime runtime = systemRuntime(world);
        return runtime.radarModes.computeIfAbsent(radar.id,
                ignored -> defaultMode(rule(radar.typeId).defaultMode));
    }

    static boolean setRadarMode(World world, Base radar, RadarMode mode, String actorId) {
        if (world == null || radar == null || mode == null || !isRadar(radar.typeId)
                || actorId == null || !actorId.equals(radar.playerId)) return false;
        systemRuntime(world).radarModes.put(radar.id, mode);
        world.status = radar.type().name + " switched to " + mode.name().toLowerCase(Locale.ROOT) + " scan mode.";
        return true;
    }

    static boolean cycleRadarMode(World world, Base radar, String actorId) {
        return setRadarMode(world, radar, radarMode(world, radar).next(), actorId);
    }

    static void setIntelAlliance(World world, String firstPlayerId, String secondPlayerId, boolean shared) {
        if (world == null || invalid(firstPlayerId) || invalid(secondPlayerId) || firstPlayerId.equals(secondPlayerId)) return;
        RuntimeState state = state(world);
        setAllianceOneWay(state, firstPlayerId, secondPlayerId, shared);
        setAllianceOneWay(state, secondPlayerId, firstPlayerId, shared);
    }

    static boolean allied(World world, String firstPlayerId, String secondPlayerId) {
        if (firstPlayerId == null || secondPlayerId == null) return false;
        if (firstPlayerId.equals(secondPlayerId)) return true;
        RuntimeState state = STATES.get(world);
        return state != null && state.allies.getOrDefault(firstPlayerId, Set.of()).contains(secondPlayerId);
    }

    static List<IntelSensor> sensors(World world, String viewerId) {
        if (world == null || invalid(viewerId)) return List.of();
        List<IntelSensor> out = new ArrayList<>();
        Set<String> owners = sensorOwners(world, viewerId);
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || !owners.contains(unit.playerId)) continue;
            double range = ordinaryUnitRange(world, unit);
            if (range > 0) out.add(new IntelSensor(unit.x, unit.y, range, 0, "U:" + unit.key()));
        }
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || !owners.contains(base.playerId)) continue;
            double range = baseSensorRange(world, base);
            if (range <= 0) continue;
            double bonus = isRadar(base.typeId) ? radarMode(world, base).identificationBonus : 0;
            out.add(new IntelSensor(base.x, base.y, range, bonus, "B:" + base.id));
        }
        return List.copyOf(out);
    }

    static boolean pointVisible(World world, String viewerId, double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
        for (IntelSensor sensor : sensors(world, viewerId)) {
            double dx = x - sensor.x;
            double dy = y - sensor.y;
            if (dx * dx + dy * dy <= sensor.rangeSquared()) return true;
        }
        return false;
    }

    static DetectionStage unitStage(World world, String viewerId, Unit target) {
        if (target == null || target.hp <= 0) return DetectionStage.NONE;
        if (allied(world, viewerId, target.playerId)) return DetectionStage.DETAILED;
        return stageFor(world, viewerId, target.x, target.y, unitSignature(target), false);
    }

    static DetectionStage baseStage(World world, String viewerId, Base target) {
        if (target == null || target.hp <= 0) return DetectionStage.NONE;
        if (allied(world, viewerId, target.playerId)) return DetectionStage.DETAILED;
        DetectionStage stage = stageFor(world, viewerId, target.x, target.y, baseSignature(world, target), true);
        if (isDecoy(target.typeId) && stage == DetectionStage.IDENTIFIED) return DetectionStage.CLASSIFIED;
        return stage;
    }

    static DetectionStage resourceStage(World world, String viewerId, ResourceNode node) {
        if (world == null || node == null || !node.active) return DetectionStage.NONE;
        DetectionStage best = pointVisible(world, viewerId, node.x, node.y)
                ? DetectionStage.CONTACT : DetectionStage.NONE;
        for (Base base : world.bases.values()) {
            if (base == null || base.hp <= 0 || !allied(world, viewerId, base.playerId)
                    || !isRadar(base.typeId)) continue;
            double range = baseSensorRange(world, base);
            double distance = Calc.distance(base.x, base.y, node.x, node.y);
            if (distance > range) continue;
            int power = surveyPower(base.typeId);
            DetectionStage stage = switch (power) {
                case 0 -> DetectionStage.CONTACT;
                case 1 -> DetectionStage.CLASSIFIED;
                case 2 -> DetectionStage.IDENTIFIED;
                default -> DetectionStage.DETAILED;
            };
            if (distance > range * 0.82 && stage.ordinal() > DetectionStage.CONTACT.ordinal()) {
                stage = DetectionStage.values()[stage.ordinal() - 1];
            }
            if (stage.ordinal() > best.ordinal()) best = stage;
        }
        return best;
    }

    static double ordinaryUnitRange(World world, Unit unit) {
        if (unit == null) return 0;
        double baseline = 260.0 + Math.max(0.35, unit.type().size.scale) * 70.0;
        double configured = Math.max(0, unit.type().scoutRange);
        double raw = Math.max(baseline, configured) * SystemModifierRules.sensorRange(world);
        return raw * sensorJammingMultiplier(world, unit.playerId, unit.x, unit.y, 0);
    }

    static double baseSensorRange(World world, Base base) {
        if (base == null) return 0;
        double ordinary = Math.max(650.0, Math.max(0, base.type().unloadRange) * 4.5);
        StructureIntelRule rule = rule(base.typeId);
        double raw = Math.max(ordinary, Math.max(0, rule.sensorRange));
        double counterJam = Math.max(0, Math.min(0.8, rule.counterJamStrength));
        if (isRadar(base.typeId)) raw *= radarMode(world, base).rangeMultiplier;
        return raw * SystemModifierRules.sensorRange(world)
                * sensorJammingMultiplier(world, base.playerId, base.x, base.y, counterJam);
    }

    static String contactShipType(Unit target, DetectionStage stage) {
        if (stage == DetectionStage.CONTACT || target == null) return CONTACT_MEDIUM;
        double size = target.type().size.scale;
        if (size <= 1.15) return CONTACT_SMALL;
        if (size >= 2.6) return CONTACT_LARGE;
        return CONTACT_MEDIUM;
    }

    static double approximateX(World world, String key, DetectionStage stage, double exactX) {
        return exactX + jitter(world, key, stage, true);
    }

    static double approximateY(World world, String key, DetectionStage stage, double exactY) {
        return exactY + jitter(world, key, stage, false);
    }

    static double uncertainty(DetectionStage stage, double ageSeconds) {
        double base = switch (stage) {
            case CONTACT -> 180;
            case CLASSIFIED -> 95;
            case IDENTIFIED -> 36;
            case DETAILED -> 12;
            default -> 260;
        };
        return Math.min(900, base + Math.max(0, ageSeconds)
                * (stage.atLeast(DetectionStage.IDENTIFIED) ? 18 : 28));
    }

    static List<IntelMemory> memories(World world, String viewerId) {
        if (world == null || invalid(viewerId)) return List.of();
        Map<String, IntelMemory> memory = systemRuntime(world).memoryByViewer.get(viewerId);
        if (memory == null || memory.isEmpty()) return List.of();
        List<IntelMemory> out = new ArrayList<>(memory.values());
        out.sort(Comparator.comparingDouble(IntelMemory::lastSeenTime).reversed());
        return List.copyOf(out);
    }

    static StructureIntelRule rule(String typeId) {
        StructureIntelRule rule = typeId == null ? null : STRUCTURES.get(typeId);
        return rule == null ? StructureIntelRule.EMPTY : rule;
    }

    static boolean radarAttackActive(World world, Unit unit, String targetKey) {
        if (world == null || unit == null || targetKey == null || targetKey.isBlank()) return false;
        RadarResponseAssignment assignment = systemRuntime(world).radarResponses.get(unit.key());
        if (assignment == null || !assignment.attack || !targetKey.equals(assignment.targetKey)) return false;
        Base radar = world.bases.get(assignment.radarId);
        if (!validRadarAssignment(world, radar, unit)) return false;
        if (!CombatPolicySystem.mayAutoAcquire(world, unit)) return false;
        double tx = CombatTarget.x(world, targetKey);
        double ty = CombatTarget.y(world, targetKey);
        return GameplayCommandNumbers.finite(tx, ty)
                && Calc.distance(radar.x, radar.y, tx, ty) <= responseRadius(world, radar);
    }

    static boolean radarPursuitAllowed(World world, Unit unit, double targetX, double targetY) {
        if (world == null || unit == null || !GameplayCommandNumbers.finite(targetX, targetY)) return false;
        RadarResponseAssignment assignment = systemRuntime(world).radarResponses.get(unit.key());
        if (assignment == null || !assignment.attack) return false;
        Base radar = world.bases.get(assignment.radarId);
        if (!validRadarAssignment(world, radar, unit) || !CombatPolicySystem.mayAutoAcquire(world, unit)) return false;
        double radius = responseRadius(world, radar);
        return Calc.distance(radar.x, radar.y, targetX, targetY) <= radius
                && Calc.distance(radar.x, radar.y, unit.x, unit.y) <= radius * 1.15;
    }

    static int radarResponseCount(World world, String radarId) {
        if (world == null || radarId == null || radarId.isBlank()) return 0;
        int count = 0;
        for (RadarResponseAssignment assignment : systemRuntime(world).radarResponses.values()) {
            if (radarId.equals(assignment.radarId)) count++;
        }
        return count;
    }

    static String radarResponseTarget(World world, String unitKey) {
        if (world == null || unitKey == null || unitKey.isBlank()) return "";
        RadarResponseAssignment assignment = systemRuntime(world).radarResponses.get(unitKey);
        return assignment == null ? "" : assignment.targetKey;
    }

    private static DetectionStage stageFor(World world, String viewerId, double x, double y,
                                           double signature, boolean station) {
        if (world == null || invalid(viewerId)) return DetectionStage.NONE;
        double best = 0;
        for (IntelSensor sensor : sensors(world, viewerId)) {
            double distance = Math.max(1, Calc.distance(sensor.x, sensor.y, x, y));
            double quality = sensor.range / distance * Math.max(0.15, signature)
                    + sensor.identificationBonus + (station ? 0.04 : 0);
            if (quality > best) best = quality;
        }
        if (best >= DETAILED_THRESHOLD) return DetectionStage.DETAILED;
        if (best >= IDENTIFIED_THRESHOLD) return DetectionStage.IDENTIFIED;
        if (best >= CLASSIFIED_THRESHOLD) return DetectionStage.CLASSIFIED;
        if (best >= CONTACT_THRESHOLD) return DetectionStage.CONTACT;
        return DetectionStage.NONE;
    }

    private static double unitSignature(Unit unit) {
        double signature = 0.48 + Math.max(0.35, unit.type().size.scale) * 0.24;
        signature += Math.min(0.40, Math.max(0, unit.type().speed) / 900.0);
        if (unit.weaponFlashTimer > 0) signature += 1.15;
        if (unit.task == UnitTask.ATTACK) signature += 0.55;
        else if (unit.task == UnitTask.AUTO_HARVEST) signature += 0.30;
        else if (unit.task == UnitTask.MOVE) signature += 0.12;
        if (!unit.basePackageType.isBlank()) signature += 0.28;
        if (unit.shipTypeId.startsWith("sensor_contact_")) signature = 0.8;
        return signature;
    }

    private static double baseSignature(World world, Base base) {
        StructureIntelRule rule = rule(base.typeId);
        double signature = 1.0 + Math.min(1.2, Math.max(0, base.type().maxHp) / 2500.0);
        signature *= Math.max(0.25, rule.signatureMultiplier);
        if (isRadar(base.typeId)) signature *= radarMode(world, base).emissionMultiplier;
        if (isJammer(base.typeId)) signature *= 2.25;
        if (!base.productionQueue.isEmpty()) signature += 0.30;
        return signature;
    }

    private static double sensorJammingMultiplier(World world, String sensorOwner, double x, double y,
                                                  double counterJam) {
        if (world == null) return 1.0;
        double jam = 0;
        for (Base jammer : world.bases.values()) {
            if (jammer == null || jammer.hp <= 0 || allied(world, sensorOwner, jammer.playerId)
                    || !isJammer(jammer.typeId)) continue;
            StructureIntelRule rule = rule(jammer.typeId);
            double distance = Calc.distance(x, y, jammer.x, jammer.y);
            if (rule.jamRange <= 0 || distance > rule.jamRange) continue;
            double falloff = 1.0 - distance / Math.max(1, rule.jamRange);
            jam += Math.max(0, rule.jamStrength) * (0.35 + 0.65 * falloff);
        }
        jam = Math.max(0, Math.min(0.82, jam - counterJam));
        return Math.max(0.18, 1.0 - jam);
    }

    private static void updateMemory(World world, SystemRuntime runtime) {
        Set<String> viewers = assetOwners(world);
        viewers.addAll(state(world).allies.keySet());
        double now = world.systemTime();
        for (String viewer : viewers) {
            Map<String, IntelMemory> memory = runtime.memoryByViewer.computeIfAbsent(viewer,
                    ignored -> new LinkedHashMap<>());
            for (Unit target : world.units.values()) {
                if (target == null || target.hp <= 0 || allied(world, viewer, target.playerId)) continue;
                DetectionStage stage = unitStage(world, viewer, target);
                if (stage == DetectionStage.NONE) continue;
                String key = "U:" + target.key();
                IntelMemory previous = memory.get(key);
                double elapsed = previous == null ? 0 : Math.max(0.05, now - previous.lastSeenTime);
                double vx = previous == null ? 0 : (target.x - previous.x) / elapsed;
                double vy = previous == null ? 0 : (target.y - previous.y) / elapsed;
                memory.put(key, new IntelMemory(key, target.playerId, target.shipTypeId, false, stage,
                        target.x, target.y, vx, vy, now, uncertainty(stage, 0), false));
            }
            for (Base target : world.bases.values()) {
                if (target == null || target.hp <= 0 || allied(world, viewer, target.playerId)) continue;
                DetectionStage stage = baseStage(world, viewer, target);
                if (stage == DetectionStage.NONE) continue;
                String key = "B:" + target.id;
                memory.put(key, new IntelMemory(key, target.playerId, target.typeId, true, stage,
                        target.x, target.y, 0, 0, now, uncertainty(stage, 0),
                        isDecoy(target.typeId) && !stage.atLeast(DetectionStage.DETAILED)));
            }
            for (Map.Entry<String, IntelMemory> entry : new ArrayList<>(memory.entrySet())) {
                IntelMemory old = entry.getValue();
                double age = Math.max(0, now - old.lastSeenTime);
                if (age > MEMORY_SECONDS) {
                    memory.remove(entry.getKey());
                    continue;
                }
                if (age > 0.05) {
                    memory.put(entry.getKey(), new IntelMemory(old.key, old.ownerId, old.typeId, old.station,
                            old.stage, old.x, old.y, old.vx, old.vy, old.lastSeenTime,
                            uncertainty(old.stage, age), old.decoySuspected));
                }
            }
        }
    }

    private static void dispatchRadarResponses(World world, SystemRuntime runtime) {
        Map<String,RadarResponseAssignment> previous = new LinkedHashMap<>(runtime.radarResponses);
        Map<String,RadarResponseAssignment> next = new LinkedHashMap<>();
        Set<String> claimedUnits = new LinkedHashSet<>();
        List<Base> radars = new ArrayList<>();
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && isRadar(base.typeId)) radars.add(base);
        }
        radars.sort(Comparator.comparingInt((Base radar) -> radarTier(radar.typeId)).reversed()
                .thenComparing(radar -> radar.id));

        for (Base radar : radars) {
            StructureIntelRule rule = rule(radar.typeId);
            if (rule.responseShipLimit <= 0 || "observe".equals(rule.responseMode)) continue;
            List<Unit> responders = responseCandidates(world, runtime, radar, rule, claimedUnits);
            int assigned = 0;
            for (Unit unit : responders) {
                if (assigned >= rule.responseShipLimit) break;
                TargetChoice target = bestResponseTarget(world, runtime, radar, unit);
                if (target == null) continue;
                RadarResponseAssignment assignment = assignRadarResponse(world, radar, unit, target);
                if (assignment == null) continue;
                next.put(unit.key(), assignment);
                claimedUnits.add(unit.key());
                assigned++;
            }
        }

        runtime.radarResponses.clear();
        runtime.radarResponses.putAll(next);
        for (Map.Entry<String,RadarResponseAssignment> entry : previous.entrySet()) {
            if (!next.containsKey(entry.getKey())) releaseRadarResponse(world, entry.getKey(), entry.getValue());
        }
    }

    private static List<Unit> responseCandidates(World world, SystemRuntime runtime, Base radar,
                                                 StructureIntelRule rule, Set<String> claimedUnits) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : world.units.values()) {
            if (unit == null || unit.hp <= 0 || claimedUnits.contains(unit.key())
                    || !radar.playerId.equals(unit.playerId) || !WeaponRules.armed(unit)
                    || ProductionSystem.refitReserved(world, unit.key())) continue;
            if (!CombatPolicySystem.mayAutoAcquire(world, unit)) continue;
            AttackIntentSource intent = CombatPolicySystem.attackIntent(world, unit);
            if (intent == AttackIntentSource.EXPLICIT) continue;
            if (Calc.distance(unit.x, unit.y, radar.x, radar.y) > rule.responseRadius) continue;

            boolean guarding = guardsRadar(unit, radar);
            RadarResponseAssignment existing = runtime.radarResponses.get(unit.key());
            boolean continuing = existing != null && radar.id.equals(existing.radarId);
            boolean queued = !UnitCommandQueueSystem.commands(world, unit.key()).isEmpty();
            boolean idle = unit.task == UnitTask.IDLE && unit.orderType == UnitOrderType.NONE && !queued;
            if (!guarding && !continuing && !idle) continue;
            out.add(unit);
        }
        out.sort(Comparator.comparingDouble((Unit unit) -> responderScore(runtime, radar, unit))
                .thenComparing(unit -> unit.key()));
        return out;
    }

    private static double responderScore(SystemRuntime runtime, Base radar, Unit unit) {
        RadarResponseAssignment existing = runtime.radarResponses.get(unit.key());
        int bucket = guardsRadar(unit, radar) ? 0
                : existing != null && radar.id.equals(existing.radarId) ? 1 : 2;
        return bucket * 10_000_000.0 + Calc.distance(unit.x, unit.y, radar.x, radar.y);
    }

    private static TargetChoice bestResponseTarget(World world, SystemRuntime runtime, Base radar, Unit responder) {
        TargetChoice best = bestLiveResponseTarget(world, radar, responder);
        if (best != null) return best;
        if (CombatPolicySystem.stance(world, responder) != CombatStance.AGGRESSIVE) return null;
        return bestRememberedResponseTarget(world, runtime, radar, responder);
    }

    private static TargetChoice bestLiveResponseTarget(World world, Base radar, Unit responder) {
        TargetChoice best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double maxRange = responseRadius(world, radar);
        for (Unit enemy : world.units.values()) {
            if (enemy == null || enemy.hp <= 0 || allied(world, radar.playerId, enemy.playerId)) continue;
            DetectionStage stage = unitStage(world, radar.playerId, enemy);
            if (!stage.atLeast(DetectionStage.CLASSIFIED)) continue;
            double radarDistance = Calc.distance(radar.x, radar.y, enemy.x, enemy.y);
            if (radarDistance > maxRange) continue;
            String key = CombatTarget.unit(enemy);
            double score = radarTargetScore(world, radar, responder, key, stage, enemy.x, enemy.y);
            if (!Double.isFinite(score) || score >= bestScore) continue;
            double tx = stage.atLeast(DetectionStage.IDENTIFIED)
                    ? enemy.x : approximateX(world, key, stage, enemy.x);
            double ty = stage.atLeast(DetectionStage.IDENTIFIED)
                    ? enemy.y : approximateY(world, key, stage, enemy.y);
            bestScore = score;
            best = new TargetChoice(key, tx, ty, stage, true);
        }
        for (Base enemy : world.bases.values()) {
            if (enemy == null || enemy.hp <= 0 || allied(world, radar.playerId, enemy.playerId)) continue;
            DetectionStage stage = baseStage(world, radar.playerId, enemy);
            if (!stage.atLeast(DetectionStage.CLASSIFIED)) continue;
            double radarDistance = Calc.distance(radar.x, radar.y, enemy.x, enemy.y);
            if (radarDistance > maxRange) continue;
            String key = CombatTarget.base(enemy);
            double score = radarTargetScore(world, radar, responder, key, stage, enemy.x, enemy.y);
            if (!Double.isFinite(score) || score >= bestScore) continue;
            double tx = stage.atLeast(DetectionStage.IDENTIFIED)
                    ? enemy.x : approximateX(world, key, stage, enemy.x);
            double ty = stage.atLeast(DetectionStage.IDENTIFIED)
                    ? enemy.y : approximateY(world, key, stage, enemy.y);
            bestScore = score;
            best = new TargetChoice(key, tx, ty, stage, true);
        }
        return best;
    }

    private static TargetChoice bestRememberedResponseTarget(World world, SystemRuntime runtime,
                                                              Base radar, Unit responder) {
        Map<String,IntelMemory> memory = runtime.memoryByViewer.get(radar.playerId);
        if (memory == null || memory.isEmpty()) return null;
        double now = world.systemTime();
        TargetChoice best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (IntelMemory candidate : memory.values()) {
            double age = now - candidate.lastSeenTime;
            if (age < 0 || age > RESPONSE_MEMORY_SECONDS || !candidate.stage.atLeast(DetectionStage.CLASSIFIED)
                    || !DiplomacySystem.hostile(world, radar.playerId, candidate.ownerId)) continue;
            if (CombatTarget.alive(world, candidate.key)
                    && (candidate.station ? baseStage(world, radar.playerId, CombatTarget.base(world, candidate.key))
                    : unitStage(world, radar.playerId, CombatTarget.unit(world, candidate.key)))
                    .atLeast(DetectionStage.CLASSIFIED)) continue;

            double predictSeconds = candidate.stage.atLeast(DetectionStage.IDENTIFIED) ? Math.min(5, age) : 0;
            double exactX = candidate.x + candidate.vx * predictSeconds;
            double exactY = candidate.y + candidate.vy * predictSeconds;
            if (Calc.distance(radar.x, radar.y, exactX, exactY) > responseRadius(world, radar)) continue;
            double score = radarMemoryScore(world, responder, candidate, exactX, exactY, age);
            if (!Double.isFinite(score) || score >= bestScore) continue;
            double tx = approximateX(world, candidate.key, candidate.stage, exactX);
            double ty = approximateY(world, candidate.key, candidate.stage, exactY);
            bestScore = score;
            best = new TargetChoice(candidate.key, tx, ty, candidate.stage, false);
        }
        return best;
    }

    private static double radarTargetScore(World world, Base radar, Unit responder, String targetKey,
                                           DetectionStage stage, double x, double y) {
        if (!CombatTarget.enemy(world, responder, targetKey)) return Double.POSITIVE_INFINITY;
        Unit targetUnit = CombatTarget.unit(world, targetKey);
        Base targetBase = CombatTarget.base(world, targetKey);
        boolean structure = targetBase != null;
        boolean combat = targetUnit != null && WeaponRules.armed(world, targetUnit);
        boolean logistics = targetUnit != null && logisticsOrWorker(targetUnit.type(), combat);
        boolean smallCraft = targetUnit != null && targetUnit.type().size.scale <= 1.0;
        boolean assignedThreat = targetUnit != null && !CombatPolicySystem.protectedTarget(world, responder).isBlank()
                && CombatPolicySystem.protectedTarget(world, responder).equals(targetUnit.attackTarget);
        boolean threat = assignedThreat || radarThreatensOwner(world, radar.playerId, targetUnit);
        if (CombatPolicySystem.stance(world, responder) == CombatStance.DEFENSIVE && !threat) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = Calc.distance(responder.x, responder.y, x, y);
        if (targetUnit != null && targetUnit.weaponFlashTimer > 0) distance *= 0.88;
        if (targetBase != null) {
            if (isJammer(targetBase.typeId)) distance *= 0.45;
            else if (isRadar(targetBase.typeId)) distance *= 0.62;
        }
        int bucket = policyBucket(CombatPolicySystem.priority(world, responder), structure, combat,
                logistics, smallCraft, threat, assignedThreat);
        double identificationFactor = stage.atLeast(DetectionStage.IDENTIFIED) ? 0.92 : 1.0;
        return bucket * 10_000_000.0 + distance * identificationFactor;
    }

    private static double radarMemoryScore(World world, Unit responder, IntelMemory memory,
                                           double x, double y, double age) {
        ShipType type = memory.station ? null : Rules.SHIPS.get(memory.typeId);
        boolean structure = memory.station;
        boolean combat = type != null && WeaponRules.armed(type);
        boolean logistics = type != null && logisticsOrWorker(type, combat);
        boolean smallCraft = type != null && type.size.scale <= 1.0;
        int bucket = policyBucket(CombatPolicySystem.priority(world, responder), structure, combat,
                logistics, smallCraft, false, false);
        double distance = Calc.distance(responder.x, responder.y, x, y);
        double stalePenalty = age * 40 + memory.uncertainty;
        if (memory.decoySuspected) stalePenalty += 500;
        return bucket * 10_000_000.0 + distance + stalePenalty;
    }

    private static int policyBucket(TargetPriorityPolicy priority, boolean structure, boolean combat,
                                    boolean logistics, boolean smallCraft, boolean threat, boolean assignedThreat) {
        return switch (priority) {
            case NEAREST_THREAT -> threat ? 0 : 1;
            case PROTECT_ASSIGNED_TARGET -> assignedThreat ? 0 : threat ? 1 : 2;
            case SCREENING -> smallCraft ? 0 : combat ? 1 : structure ? 2 : 3;
            case COMBAT_FIRST -> combat ? 0 : structure ? 1 : logistics ? 2 : 3;
            case LOGISTICS_FIRST -> logistics ? 0 : combat ? 1 : structure ? 2 : 3;
            case STRUCTURES_FIRST -> structure ? 0 : 1;
            case STRUCTURES_LAST -> structure ? 1 : 0;
        };
    }

    private static boolean radarThreatensOwner(World world, String ownerId, Unit hostile) {
        if (world == null || hostile == null || hostile.attackTarget == null || hostile.attackTarget.isBlank()) return false;
        return ownerId.equals(CombatTarget.owner(world, hostile.attackTarget));
    }

    private static boolean logisticsOrWorker(ShipType type, boolean combat) {
        if (type == null) return false;
        return !type.harvestKinds.isEmpty()
                || type.cargoCapacity >= 300
                || type.tractorBeamCount > 0
                || type.baseBuilder
                || type.scoutRange > 0 && !combat;
    }

    private static RadarResponseAssignment assignRadarResponse(World world, Base radar, Unit unit, TargetChoice target) {
        if (!target.attackable() && guardsRadar(unit, radar)) return null;
        if (target.attackable()) {
            CombatPolicySystem.markAutomaticAttack(world, unit);
            unit.attack(target.key);
            if (unit.task != UnitTask.ATTACK || !target.key.equals(unit.attackTarget)) {
                CombatPolicySystem.clearAttackIntent(world, unit);
                return null;
            }
            return new RadarResponseAssignment(radar.id, target.key, true, target.x, target.y);
        }

        if (CombatPolicySystem.attackIntent(world, unit) == AttackIntentSource.EXPLICIT) return null;
        CombatPolicySystem.clearAttackIntent(world, unit);
        unit.moveTo(target.x, target.y);
        return new RadarResponseAssignment(radar.id, target.key, false, target.x, target.y);
    }

    private static void releaseRadarResponse(World world, String unitKey, RadarResponseAssignment assignment) {
        Unit unit = world.units.get(unitKey);
        if (unit == null || assignment == null) return;
        if (assignment.attack) {
            if (CombatPolicySystem.attackIntent(world, unit) == AttackIntentSource.AUTOMATIC
                    && assignment.targetKey.equals(unit.attackTarget)) {
                unit.attack("");
                CombatPolicySystem.clearAttackIntent(world, unit);
            }
            return;
        }
        if (unit.task == UnitTask.MOVE && unit.attackTarget.isBlank()
                && Calc.distance(unit.targetX, unit.targetY, assignment.x, assignment.y) <= 8) {
            unit.targetX = unit.x;
            unit.targetY = unit.y;
            unit.task = UnitTask.IDLE;
        }
    }

    private static boolean guardsRadar(Unit unit, Base radar) {
        return unit != null && radar != null && unit.orderType == UnitOrderType.GUARD
                && CombatTarget.base(radar).equals(unit.orderTarget);
    }

    private static boolean validRadarAssignment(World world, Base radar, Unit unit) {
        return radar != null && radar.hp > 0 && isRadar(radar.typeId)
                && unit != null && unit.hp > 0 && radar.playerId.equals(unit.playerId)
                && rule(radar.typeId).responseShipLimit > 0
                && !"observe".equals(rule(radar.typeId).responseMode);
    }

    private static double responseRadius(World world, Base radar) {
        if (world == null || radar == null) return 0;
        StructureIntelRule rule = rule(radar.typeId);
        double configured = Math.max(0, rule.responseRadius);
        double sensorBound = Math.max(0, baseSensorRange(world, radar) * 1.15);
        if (configured <= 0) return sensorBound;
        if (sensorBound <= 0) return configured;
        return Math.min(configured, sensorBound);
    }

    private static void investigateLastKnownContacts(World world, SystemRuntime runtime) {
        double now = world.systemTime();
        for (String owner : assetOwners(world)) {
            if (!NpcRules.isNpcFaction(owner)) continue;
            Map<String, IntelMemory> memory = runtime.memoryByViewer.get(owner);
            if (memory == null || memory.isEmpty()) continue;
            IntelMemory newest = null;
            for (IntelMemory candidate : memory.values()) {
                double age = now - candidate.lastSeenTime;
                if (age < 0 || age > 18 || candidate.stage == DetectionStage.NONE) continue;
                if (newest == null || candidate.lastSeenTime > newest.lastSeenTime) newest = candidate;
            }
            if (newest == null) continue;
            double predictedX = newest.x + newest.vx * Math.min(5, now - newest.lastSeenTime);
            double predictedY = newest.y + newest.vy * Math.min(5, now - newest.lastSeenTime);
            for (Unit unit : world.units.values()) {
                if (!owner.equals(unit.playerId) || unit.hp <= 0 || !WeaponRules.armed(unit)) continue;
                if (runtime.radarResponses.containsKey(unit.key())) continue;
                if (unit.task != UnitTask.IDLE || unit.orderType != UnitOrderType.NONE) continue;
                unit.issueMove(predictedX, predictedY);
                break;
            }
        }
    }

    private static void prune(World world, SystemRuntime runtime) {
        Set<String> liveBases = new LinkedHashSet<>();
        for (Base base : world.bases.values()) liveBases.add(base.id);
        runtime.radarModes.keySet().removeIf(id -> !liveBases.contains(id));
        runtime.radarResponses.entrySet().removeIf(entry -> {
            Unit unit = world.units.get(entry.getKey());
            return unit == null || unit.hp <= 0 || !liveBases.contains(entry.getValue().radarId);
        });
    }

    private static Set<String> sensorOwners(World world, String viewerId) {
        Set<String> owners = new LinkedHashSet<>();
        owners.add(viewerId);
        RuntimeState state = STATES.get(world);
        if (state != null) owners.addAll(state.allies.getOrDefault(viewerId, Set.of()));
        return owners;
    }

    private static Set<String> assetOwners(World world) {
        Set<String> owners = new LinkedHashSet<>();
        for (Unit unit : world.units.values()) {
            if (unit != null && unit.hp > 0 && !invalid(unit.playerId)) owners.add(unit.playerId);
        }
        for (Base base : world.bases.values()) {
            if (base != null && base.hp > 0 && !invalid(base.playerId)) owners.add(base.playerId);
        }
        return owners;
    }

    private static void setAllianceOneWay(RuntimeState state, String owner, String ally, boolean shared) {
        Set<String> allies = state.allies.computeIfAbsent(owner, ignored -> new LinkedHashSet<>());
        if (shared) allies.add(ally);
        else allies.remove(ally);
    }

    private static double jitter(World world, String key, DetectionStage stage, boolean xAxis) {
        if (stage.atLeast(DetectionStage.IDENTIFIED)) return 0;
        long timeBucket = world == null ? 0 : (long)Math.floor(world.systemTime() / 2.5);
        long seed = (key == null ? 0 : key.hashCode()) * 0x9E3779B97F4A7C15L
                ^ timeBucket * 0xC2B2AE3D27D4EB4FL ^ (xAxis ? 0x51A3L : 0xB77DL);
        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdL;
        seed ^= seed >>> 33;
        double normalized = ((seed >>> 11) & 0x1FFFFF) / (double)0x1FFFFF * 2.0 - 1.0;
        double radius = stage == DetectionStage.CONTACT ? 125 : 55;
        return normalized * radius;
    }

    private static RadarMode defaultMode(String configured) {
        try {
            return RadarMode.valueOf(configured == null ? "ACTIVE" : configured.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return RadarMode.ACTIVE;
        }
    }

    private static RuntimeState state(World world) {
        return STATES.computeIfAbsent(world, ignored -> new RuntimeState());
    }

    private static SystemRuntime systemRuntime(World world) {
        RuntimeState state = state(world);
        String systemId = world == null || world.activeSystemId() == null || world.activeSystemId().isBlank()
                ? "DEFAULT" : world.activeSystemId();
        return state.systems.computeIfAbsent(systemId, ignored -> new SystemRuntime());
    }

    private static boolean invalid(String value) {
        return value == null || value.isBlank() || "WAIT".equals(value) || "SENSOR_CONTACT".equals(value);
    }

    private static Map<String, StructureIntelRule> loadStructureRules() {
        Path path = stationConfigPath();
        if (!Files.exists(path)) return Map.of();
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));
            Map<String,Object> stations = object(root.getOrDefault("stationTypes", root));
            Map<String,StructureIntelRule> out = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : stations.entrySet()) {
                Map<String,Object> row = object(entry.getValue());
                String role = string(row, "role", "").trim().toLowerCase(Locale.ROOT);
                out.put(entry.getKey(), new StructureIntelRule(
                        entry.getKey(), role, integer(row, "radarTier", 0), number(row, "sensorRange", 0),
                        integer(row, "resourceDispatchLimit", 0), integer(row, "surveyPower", 0),
                        number(row, "jamRange", 0), number(row, "jamStrength", 0),
                        number(row, "counterJamStrength", 0), number(row, "signatureMultiplier", 1),
                        string(row, "defaultRadarMode", "ACTIVE"),
                        string(row, "responseMode", "observe").toLowerCase(Locale.ROOT),
                        integer(row, "responseShipLimit", 0), number(row, "responseRadius", 0),
                        string(row, "decoyProfile", "")));
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception ex) {
            throw new RuleConfigurationException("Could not load intel-warfare station fields from "
                    + path + ": " + ex.getMessage());
        }
    }

    private static Path stationConfigPath() {
        Path manifest = Path.of("config/starchem.json");
        if (!Files.exists(manifest)) return Path.of("config/stations.json");
        try {
            Map<String,Object> root = object(MiniJson.parse(Files.readString(manifest)));
            Map<String,Object> files = object(root.get("files"));
            return Path.of(string(files, "stations", "config/stations.json"));
        } catch (Exception ignored) {
            return Path.of("config/stations.json");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
    }

    private static String string(Map<String,Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Map<String,Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double number(Map<String,Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    static record StructureIntelRule(String typeId, String role, int tier, double sensorRange,
                                     int resourceDispatchLimit, int surveyPower, double jamRange,
                                     double jamStrength, double counterJamStrength, double signatureMultiplier,
                                     String defaultMode, String responseMode, int responseShipLimit,
                                     double responseRadius, String decoyProfile) {
        static final StructureIntelRule EMPTY = new StructureIntelRule("", "", 0, 0, 0, 0,
                0, 0, 0, 1, "ACTIVE", "observe", 0, 0, "");
    }

    private record TargetChoice(String key, double x, double y, DetectionStage stage, boolean live) {
        boolean attackable() { return live && stage.atLeast(DetectionStage.IDENTIFIED) && key != null && !key.isBlank(); }
    }

    private record RadarResponseAssignment(String radarId, String targetKey, boolean attack, double x, double y) { }

    private static final class RuntimeState {
        final Map<String,SystemRuntime> systems = new LinkedHashMap<>();
        final Map<String,Set<String>> allies = new LinkedHashMap<>();
    }

    private static final class SystemRuntime {
        final Map<String,RadarMode> radarModes = new LinkedHashMap<>();
        final Map<String,Map<String,IntelMemory>> memoryByViewer = new LinkedHashMap<>();
        final Map<String,RadarResponseAssignment> radarResponses = new LinkedHashMap<>();
        double responseTimer;
    }
}
