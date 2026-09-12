package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Authoritative gameplay layer for planets and moons.
 *
 * <p>CelestialSystem owns deterministic orbital presentation. This class owns the mutable gameplay
 * state that sits on top of those bodies: intel, deposits, installations, claims, objectives and
 * strategic bonuses. Physical resources and stations remain normal ResourceNode/Base instances so
 * the existing mining, combat, logistics and replication systems continue to be authoritative.</p>
 */
final class CelestialGameplaySystem {
    static final double SCAN_SECONDS = 4.0;
    static final double ANALYZE_SECONDS = 12.0;
    static final double HOLD_OBJECTIVE_SECONDS = 120.0;
    static final double EXTRACT_OBJECTIVE_AMOUNT = 500.0;

    private static final Map<CelestialSystem, WorldSystemState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CelestialGameplaySystem() { }

    static void registerState(WorldSystemState state) {
        if (state == null || state.celestials == null) return;
        STATES.put(state.celestials, state);
        ensureBodyStates(state);
    }

    static void onCelestialUpdate(CelestialSystem celestials, double dt) {
        WorldSystemState state = STATES.get(celestials);
        if (state == null) return;
        ensureBodyStates(state);
        ensureBodyDeposits(state);
        anchorNearbyStations(state);
        updateAnchoredResources(state);
        updateOrbitalStations(state, dt);
        updateClaimsAndInstallations(state);
        updateScanning(state, dt);
        updateObjectives(state, dt);
    }

    static CelestialBodyState bodyState(WorldSystemState state, String bodyId) {
        if (state == null || bodyId == null) return null;
        ensureBodyStates(state);
        return state.celestialBodies.get(bodyId);
    }

    static List<CelestialBodyState> bodyStates(WorldSystemState state) {
        if (state == null) return List.of();
        ensureBodyStates(state);
        return List.copyOf(state.celestialBodies.values());
    }

    static CelestialIntelLevel intel(WorldSystemState state, String bodyId, String playerId) {
        CelestialBodyState body = bodyState(state, bodyId);
        if (body == null || playerId == null || playerId.isBlank()) return CelestialIntelLevel.VISIBLE;
        return body.intelByPlayer.getOrDefault(playerId, CelestialIntelLevel.VISIBLE);
    }

    static double bonusMultiplier(WorldSystemState state, String playerId, CelestialBonusKind kind) {
        if (state == null || playerId == null || playerId.isBlank() || kind == null) return 1.0;
        ensureBodyStates(state);
        double bonus = 0.0;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            if (body.contested || !playerId.equals(body.claimantId)) continue;
            if (!installationSupports(body.installations, kind)) continue;
            bonus += body.profile.bonuses().getOrDefault(kind, 0.0);
        }
        return 1.0 + Math.min(0.35, Math.max(0.0, bonus));
    }

    static CelestialObjectiveStatus objectiveStatus(
            WorldSystemState state, String bodyId, String playerId, CelestialObjectiveType objective) {
        CelestialBodyState body = bodyState(state, bodyId);
        if (body == null || playerId == null || objective == null) {
            return new CelestialObjectiveStatus(objective, bodyId == null ? "" : bodyId, 0, 1, false);
        }
        return switch (objective) {
            case SCAN -> {
                double progress = intel(state, bodyId, playerId).ordinal() >= CelestialIntelLevel.SCANNED.ordinal() ? 1 : 0;
                yield new CelestialObjectiveStatus(objective, bodyId, progress, 1, progress >= 1);
            }
            case CLAIM -> {
                double progress = !body.contested && playerId.equals(body.claimantId) ? 1 : 0;
                yield new CelestialObjectiveStatus(objective, bodyId, progress, 1, progress >= 1);
            }
            case HOLD -> {
                double progress = body.holdSecondsByPlayer.getOrDefault(playerId, 0.0);
                yield new CelestialObjectiveStatus(objective, bodyId, progress, HOLD_OBJECTIVE_SECONDS,
                        progress >= HOLD_OBJECTIVE_SECONDS);
            }
            case EXTRACT -> {
                double progress = body.extractedByPlayer.getOrDefault(playerId, 0.0);
                yield new CelestialObjectiveStatus(objective, bodyId, progress, EXTRACT_OBJECTIVE_AMOUNT,
                        progress >= EXTRACT_OBJECTIVE_AMOUNT);
            }
        };
    }

    private static void ensureBodyStates(WorldSystemState state) {
        for (CelestialSystem.BodyView view : state.celestials.bodyViews()) {
            if (view.visualClass() == CelestialVisualClass.STAR) continue;
            state.celestialBodies.computeIfAbsent(view.id(), id ->
                    new CelestialBodyState(profileFor(state, view)));
        }
    }

    private static CelestialGameplayProfile profileFor(WorldSystemState state, CelestialSystem.BodyView view) {
        String visual = view.visualClass() == null ? "ROCKY" : view.visualClass().name();
        List<Material> source = state.definition != null && state.definition.spawnMaterials() != null
                && !state.definition.spawnMaterials().isEmpty()
                ? state.definition.spawnMaterials() : Arrays.asList(Material.values());
        List<Material> deposits = new ArrayList<>();
        if (!source.isEmpty()) {
            int count = Math.min(source.size(), 1 + Math.floorMod(Objects.hash(state.id, view.id(), "count"), 3));
            for (int i = 0; i < count; i++) {
                Material material = source.get(Math.floorMod(Objects.hash(state.id, view.id(), i), source.size()));
                if (!deposits.contains(material)) deposits.add(material);
            }
            if (deposits.isEmpty()) deposits.add(source.get(0));
        }

        List<String> traits = new ArrayList<>();
        List<String> hazards = new ArrayList<>();
        EnumMap<CelestialBonusKind, Double> bonuses = new EnumMap<>(CelestialBonusKind.class);
        int slots;
        switch (visual) {
            case "TERRESTRIAL" -> {
                traits.add("Habitable biosphere");
                traits.add("Developed orbital approaches");
                bonuses.put(CelestialBonusKind.RESEARCH, 0.08);
                bonuses.put(CelestialBonusKind.LOGISTICS, 0.06);
                slots = 3;
            }
            case "DESERT" -> {
                traits.add("High solar flux");
                bonuses.put(CelestialBonusKind.PRODUCTION, 0.09);
                bonuses.put(CelestialBonusKind.SENSOR, 0.04);
                slots = 2;
            }
            case "ICE" -> {
                traits.add("Cryogenic volatiles");
                bonuses.put(CelestialBonusKind.RESEARCH, 0.07);
                bonuses.put(CelestialBonusKind.SENSOR, 0.07);
                slots = 2;
            }
            case "LAVA" -> {
                traits.add("Geologically active");
                hazards.add("Extreme thermal environment");
                bonuses.put(CelestialBonusKind.MINING, 0.12);
                bonuses.put(CelestialBonusKind.PRODUCTION, 0.07);
                slots = 2;
            }
            case "GAS_GIANT" -> {
                traits.add("Deep atmospheric resources");
                hazards.add("Severe gravity well");
                bonuses.put(CelestialBonusKind.MINING, 0.09);
                bonuses.put(CelestialBonusKind.LOGISTICS, 0.07);
                slots = 4;
            }
            case "ICE_GIANT" -> {
                traits.add("Volatile-rich atmosphere");
                bonuses.put(CelestialBonusKind.RESEARCH, 0.06);
                bonuses.put(CelestialBonusKind.LOGISTICS, 0.08);
                slots = 3;
            }
            case "TOXIC" -> {
                traits.add("Rare chemical environment");
                hazards.add("Corrosive atmosphere");
                bonuses.put(CelestialBonusKind.MINING, 0.12);
                bonuses.put(CelestialBonusKind.RESEARCH, 0.05);
                slots = 2;
            }
            case "INDUSTRIAL" -> {
                traits.add("Industrial legacy infrastructure");
                bonuses.put(CelestialBonusKind.PRODUCTION, 0.10);
                bonuses.put(CelestialBonusKind.LOGISTICS, 0.10);
                bonuses.put(CelestialBonusKind.REPAIR, 0.08);
                slots = 4;
            }
            case "DEAD" -> {
                traits.add("Low-interference surface");
                bonuses.put(CelestialBonusKind.MINING, 0.09);
                bonuses.put(CelestialBonusKind.SENSOR, 0.05);
                slots = 2;
            }
            default -> {
                traits.add("Mineral-rich crust");
                bonuses.put(CelestialBonusKind.MINING, 0.08);
                slots = 2;
            }
        }
        if (view.moon()) {
            traits.add("Low-gravity orbital access");
            bonuses.merge(CelestialBonusKind.LOGISTICS, 0.03, Double::sum);
            slots = Math.max(1, slots - 1);
        }
        return new CelestialGameplayProfile(view.id(), view.name(), visual, deposits, traits, hazards, slots, bonuses);
    }

    private static void ensureBodyDeposits(WorldSystemState state) {
        Set<Integer> usedIds = new HashSet<>();
        int nextId = 1;
        for (ResourceNode node : state.resources) {
            usedIds.add(node.id);
            nextId = Math.max(nextId, node.id + 1);
        }
        for (CelestialBodyState body : state.celestialBodies.values()) {
            if (body.depositsSeeded) continue;
            CelestialSystem.BodyView view = state.celestials.bodyView(body.profile.bodyId());
            if (view == null) continue;
            int slot = 0;
            for (Material material : body.profile.deposits()) {
                while (usedIds.contains(nextId)) nextId++;
                double orbitRadius = Math.max(view.radius() + 130.0, view.radius() * 1.35) + slot * 75.0;
                double angle = normalizedAngle(Objects.hash(state.id, view.id(), material.name(), slot));
                double speed = 0.018 + 0.004 * (slot + 1);
                double x = view.x() + Math.cos(angle) * orbitRadius;
                double y = view.y() + Math.sin(angle) * orbitRadius;
                ResourceNode node = new ResourceNode(
                        nextId,
                        material.label + " deposit",
                        NodeKind.MINERAL_ASTEROID,
                        material,
                        x,
                        y,
                        1800.0 + slot * 450.0,
                        12.0,
                        28.0);
                node.orbit(view.x(), view.y(), orbitRadius, angle, speed);
                node.celestialAnchorBodyId = view.id();
                state.resources.add(node);
                body.resourceNodeIds.add(node.id);
                body.lastResourceAmounts.put(node.id, node.amount);
                usedIds.add(nextId);
                nextId++;
                slot++;
            }
            body.depositsSeeded = true;
        }
    }

    private static void anchorNearbyStations(WorldSystemState state) {
        List<CelestialSystem.BodyView> bodies = state.celestials.bodyViews();
        for (Base base : state.bases.values()) {
            if (base.celestialAnchorBodyId != null && !base.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (CelestialSystem.BodyView body : bodies) {
                if (body.visualClass() == CelestialVisualClass.STAR) continue;
                double distance = Math.hypot(base.x - body.x(), base.y - body.y());
                double captureDistance = Math.max(500.0, body.radius() + 420.0);
                if (distance <= captureDistance && distance < nearestDistance) {
                    nearest = body;
                    nearestDistance = distance;
                }
            }
            if (nearest == null) continue;
            base.celestialAnchorBodyId = nearest.id();
            base.celestialOrbitRadius = Math.max(nearestDistance, nearest.radius() + base.interactionRadius() + 60.0);
            base.celestialOrbitAngle = Math.atan2(base.y - nearest.y(), base.x - nearest.x());
            base.celestialOrbitSpeed = 0.010 * Math.sqrt(400.0 / Math.max(200.0, base.celestialOrbitRadius));
        }
    }

    private static void updateAnchoredResources(WorldSystemState state) {
        for (ResourceNode node : state.resources) {
            if (node.celestialAnchorBodyId == null || node.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView body = state.celestials.bodyView(node.celestialAnchorBodyId);
            if (body == null) continue;
            node.orbitCenterX = body.x();
            node.orbitCenterY = body.y();
            if (!node.orbiting) {
                double radius = Math.max(body.radius() + 130.0, Math.hypot(node.x - body.x(), node.y - body.y()));
                double angle = Math.atan2(node.y - body.y(), node.x - body.x());
                node.orbit(body.x(), body.y(), radius, angle, 0.02);
            }
        }
    }

    private static void updateOrbitalStations(WorldSystemState state, double dt) {
        if (!Double.isFinite(dt)) dt = 0;
        for (Base base : state.bases.values()) {
            if (base.celestialAnchorBodyId == null || base.celestialAnchorBodyId.isBlank()) continue;
            CelestialSystem.BodyView body = state.celestials.bodyView(base.celestialAnchorBodyId);
            if (body == null) continue;
            base.celestialOrbitAngle += base.celestialOrbitSpeed * dt;
            base.x = body.x() + Math.cos(base.celestialOrbitAngle) * base.celestialOrbitRadius;
            base.y = body.y() + Math.sin(base.celestialOrbitAngle) * base.celestialOrbitRadius;
        }
    }

    private static void updateClaimsAndInstallations(WorldSystemState state) {
        for (CelestialBodyState body : state.celestialBodies.values()) {
            body.orbitalBaseIds.clear();
            body.installations.clear();
            Set<String> owners = new HashSet<>();
            for (Base base : state.bases.values()) {
                if (!body.profile.bodyId().equals(base.celestialAnchorBodyId)) continue;
                body.orbitalBaseIds.add(base.id);
                owners.add(base.playerId);
                if (body.installations.size() < body.profile.installationSlots()) {
                    body.installations.add(installationType(base));
                }
            }
            body.contested = owners.size() > 1;
            body.claimantId = owners.size() == 1 ? owners.iterator().next() : "";
        }
    }

    private static void updateScanning(WorldSystemState state, double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            CelestialSystem.BodyView view = state.celestials.bodyView(body.profile.bodyId());
            if (view == null) continue;
            Set<String> scanners = new HashSet<>();
            double unitRange = view.radius() + 900.0;
            double stationRange = view.radius() + 1200.0;
            for (Unit unit : state.units.values()) {
                if (Math.hypot(unit.x - view.x(), unit.y - view.y()) <= unitRange) scanners.add(unit.playerId);
            }
            for (Base base : state.bases.values()) {
                if (Math.hypot(base.x - view.x(), base.y - view.y()) <= stationRange) scanners.add(base.playerId);
            }
            for (String playerId : scanners) {
                double seconds = body.scanSecondsByPlayer.merge(playerId, dt, Double::sum);
                CelestialIntelLevel level = seconds >= ANALYZE_SECONDS ? CelestialIntelLevel.ANALYZED
                        : seconds >= SCAN_SECONDS ? CelestialIntelLevel.SCANNED : CelestialIntelLevel.VISIBLE;
                body.intelByPlayer.put(playerId, level);
            }
        }
    }

    private static void updateObjectives(WorldSystemState state, double dt) {
        if (!Double.isFinite(dt) || dt < 0) dt = 0;
        for (CelestialBodyState body : state.celestialBodies.values()) {
            if (!body.contested && body.claimantId != null && !body.claimantId.isBlank()) {
                body.holdSecondsByPlayer.merge(body.claimantId, dt, Double::sum);
            }
            for (int resourceId : body.resourceNodeIds) {
                ResourceNode node = resourceById(state, resourceId);
                if (node == null) continue;
                double previous = body.lastResourceAmounts.getOrDefault(resourceId, node.amount);
                double extracted = Math.max(0.0, previous - node.amount);
                if (extracted > 0 && !body.contested && body.claimantId != null && !body.claimantId.isBlank()) {
                    body.extractedByPlayer.merge(body.claimantId, extracted, Double::sum);
                }
                body.lastResourceAmounts.put(resourceId, node.amount);
            }
        }
    }

    private static ResourceNode resourceById(WorldSystemState state, int id) {
        for (ResourceNode node : state.resources) if (node.id == id) return node;
        return null;
    }

    private static CelestialInstallationType installationType(Base base) {
        String type = base.typeId == null ? "" : base.typeId.toLowerCase(Locale.ROOT);
        if (type.contains("research") || type.contains("lab")) return CelestialInstallationType.RESEARCH_SITE;
        if (type.contains("sensor") || type.contains("radar") || type.contains("observ")) return CelestialInstallationType.SENSOR_ARRAY;
        if (type.contains("log") || type.contains("cargo") || type.contains("depot") || type.contains("repair")) {
            return CelestialInstallationType.LOGISTICS_HUB;
        }
        return CelestialInstallationType.EXTRACTOR;
    }

    private static boolean installationSupports(List<CelestialInstallationType> installations, CelestialBonusKind kind) {
        if (installations == null || installations.isEmpty()) return false;
        return switch (kind) {
            case MINING -> installations.contains(CelestialInstallationType.EXTRACTOR);
            case RESEARCH -> installations.contains(CelestialInstallationType.RESEARCH_SITE);
            case SENSOR -> installations.contains(CelestialInstallationType.SENSOR_ARRAY);
            case LOGISTICS, REPAIR -> installations.contains(CelestialInstallationType.LOGISTICS_HUB);
            case PRODUCTION, SHIELD -> true;
        };
    }

    private static double normalizedAngle(int seed) {
        long unsigned = Integer.toUnsignedLong(seed);
        return (unsigned / (double) 0x1_0000_0000L) * Math.PI * 2.0;
    }
}

enum CelestialIntelLevel {
    VISIBLE,
    SCANNED,
    ANALYZED
}

enum CelestialInstallationType {
    EXTRACTOR,
    RESEARCH_SITE,
    SENSOR_ARRAY,
    LOGISTICS_HUB
}

enum CelestialBonusKind {
    MINING,
    RESEARCH,
    SENSOR,
    LOGISTICS,
    PRODUCTION,
    REPAIR,
    SHIELD
}

enum CelestialObjectiveType {
    SCAN,
    CLAIM,
    HOLD,
    EXTRACT
}

record CelestialGameplayProfile(
        String bodyId,
        String bodyName,
        String bodyType,
        List<Material> deposits,
        List<String> traits,
        List<String> hazards,
        int installationSlots,
        Map<CelestialBonusKind, Double> bonuses
) {
    CelestialGameplayProfile {
        bodyId = bodyId == null ? "" : bodyId;
        bodyName = bodyName == null ? bodyId : bodyName;
        bodyType = bodyType == null ? "ROCKY" : bodyType;
        deposits = deposits == null ? List.of() : List.copyOf(deposits);
        traits = traits == null ? List.of() : List.copyOf(traits);
        hazards = hazards == null ? List.of() : List.copyOf(hazards);
        installationSlots = Math.max(1, installationSlots);
        bonuses = bonuses == null ? Map.of() : Map.copyOf(bonuses);
    }
}

record CelestialObjectiveStatus(
        CelestialObjectiveType objective,
        String bodyId,
        double progress,
        double target,
        boolean complete
) { }

final class CelestialBodyState {
    final CelestialGameplayProfile profile;
    String claimantId = "";
    boolean contested;
    final Map<String, CelestialIntelLevel> intelByPlayer = new HashMap<>();
    final Map<String, Double> scanSecondsByPlayer = new HashMap<>();
    final Map<String, Double> holdSecondsByPlayer = new HashMap<>();
    final Map<String, Double> extractedByPlayer = new HashMap<>();
    final List<CelestialInstallationType> installations = new ArrayList<>();
    final List<Integer> resourceNodeIds = new ArrayList<>();
    final List<String> orbitalBaseIds = new ArrayList<>();
    final Map<Integer, Double> lastResourceAmounts = new HashMap<>();
    boolean depositsSeeded;

    CelestialBodyState(CelestialGameplayProfile profile) {
        this.profile = profile;
    }
}
